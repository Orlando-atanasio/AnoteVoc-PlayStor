package com.example.ui.viewmodel

import com.example.core.coroutines.DispatcherProvider
import com.example.core.time.ClockProvider
import com.example.domain.editor.ConflictResolutionOption
import com.example.domain.editor.ConsolidationReadiness
import com.example.domain.editor.FolderAvailability
import com.example.domain.editor.NoteEditorBaseline
import com.example.domain.editor.NoteEditorOpenRequest
import com.example.domain.editor.RecoveryConflict
import com.example.domain.editor.RecoveryTargetExpectation
import com.example.domain.editor.SessionBlockReason
import com.example.domain.editor.SessionWarning
import com.example.domain.model.AppPreferences
import com.example.domain.model.AppThemeMode
import com.example.domain.model.Folder
import com.example.domain.model.HomeViewMode
import com.example.domain.model.Note
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NoteContentState
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.NoteRecoveryDraft
import com.example.domain.model.NoteRecoveryDraftInput
import com.example.domain.model.NoteRecoveryDraftKeys
import com.example.domain.model.ParagraphBlock
import com.example.domain.model.ReplaceCheckpointWithNewNoteResult
import com.example.domain.model.SaveCheckpointResult
import com.example.domain.policy.NoteContentPolicy
import com.example.domain.repository.FolderDeleteMode
import com.example.domain.repository.FolderRepository
import com.example.domain.repository.NoteConflictException
import com.example.domain.repository.NoteRecoveryDraftRepository
import com.example.domain.repository.NoteRepository
import com.example.domain.repository.PinNoteResult
import com.example.domain.repository.PreferencesRepository
import com.example.domain.repository.RestoreFolderResult
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorSessionCoordinatorTest {

  private class CoordinatorHarness(
    testScope: TestScope,
  ) {
    private val clockCounter = AtomicLong(1_700_100_000_000L)
    val clock =
      object : ClockProvider {
        override fun nowMillis(): Long = clockCounter.incrementAndGet()
      }

    val testDispatcher = StandardTestDispatcher(testScope.testScheduler)
    val dispatchers =
      object : DispatcherProvider {
        override val main: CoroutineDispatcher = testDispatcher
        override val io: CoroutineDispatcher = testDispatcher
        override val default: CoroutineDispatcher = testDispatcher
      }

    val folderRepo = TestFolderRepository()
    val draftRepo = TestDraftRepository(clock)
    val noteRepo = TestNoteRepository(clock, draftRepo)
    val prefsRepo = TestPreferencesRepository()
    val registry = ActiveSessionRegistry()
    val handoffTracker = ReplacementHandoffTracker(clock)

    private val idCounter = AtomicInteger(1)
    private val blockCounter = AtomicInteger(1)

    fun nextSessionId(): String = "sess-${idCounter.getAndIncrement()}"

    fun createLoader(
      sessionIdProvider: () -> String = ::nextSessionId,
    ): SessionOpenLoader =
      SessionOpenLoader(
        noteRepository = noteRepo,
        folderRepository = folderRepo,
        draftRepository = draftRepo,
        preferencesRepository = prefsRepo,
        activeSessionRegistry = registry,
        handoffTracker = handoffTracker,
        sessionIdGenerator = sessionIdProvider,
      )

    fun createReconciler(): PendingReplacementReconciler =
      PendingReplacementReconciler(
        noteRepository = noteRepo,
        draftRepository = draftRepo,
        activeSessionRegistry = registry,
        handoffTracker = handoffTracker,
      )

    fun createCoordinator(
      openRequest: NoteEditorOpenRequest,
      parentScope: CoroutineScope,
      autosaveDebounceMillis: Long = 600L,
      sessionIdProvider: () -> String = ::nextSessionId,
    ): NoteEditorSessionCoordinator =
      NoteEditorSessionCoordinator(
        openRequest = openRequest,
        sessionOpenLoader = createLoader(sessionIdProvider),
        replacementReconciler = createReconciler(),
        handoffTracker = handoffTracker,
        activeSessionRegistry = registry,
        draftRepository = draftRepo,
        noteRepository = noteRepo,
        folderRepository = folderRepo,
        dispatchers = dispatchers,
        clock = clock,
        parentScope = parentScope,
        autosaveDebounceMillis = autosaveDebounceMillis,
        sessionIdGenerator = sessionIdProvider,
        engineBlockIdGenerator = { "blk-${blockCounter.getAndIncrement()}" },
      )
  }

  private suspend fun TestScope.submitAndAwait(
    coordinator: NoteEditorSessionCoordinator,
    buildCmd: (CompletableDeferred<SessionCommandOutcome>) -> SessionCommand,
  ): SessionCommandOutcome {
    val ack = CompletableDeferred<SessionCommandOutcome>()
    val sub = coordinator.submit(buildCmd(ack))
    assertTrue("Comando deveria ter sido aceito: $sub", sub is SubmitCommandResult.Accepted)
    testScheduler.runCurrent()
    return withTimeout(5_000) { ack.await() }
  }

  private fun TestScope.submitMutation(
    coordinator: NoteEditorSessionCoordinator,
    command: SessionCommand,
  ) {
    val sub = coordinator.submit(command)
    assertTrue("Mutação deveria ter sido aceita: $sub", sub is SubmitCommandResult.Accepted)
    testScheduler.runCurrent()
  }

  @Test
  fun createNew_withNullPaperStyle_readsPreferencesRepositoryAndStartsIdleWithActiveLease() =
    runTest {
      val h = CoordinatorHarness(this)
      h.prefsRepo.current = AppPreferences(defaultNotePaper = NotePaperStyle.ELEGANCE_NOIR)
      val coord =
        h.createCoordinator(
          openRequest =
            NoteEditorOpenRequest.CreateNew(
              defaultPaperStyle = null,
              sessionId = "pref-s1",
            ),
          parentScope = this,
        )

      try {
        coord.start()
        testScheduler.runCurrent()
        val initial = coord.awaitInitialized()

        assertEquals(NoteEditorSessionPhase.EDITING, initial.phase)
        assertEquals(NotePaperStyle.ELEGANCE_NOIR, initial.paperStyle)
        assertEquals(AutosaveStatus.Idle, initial.autosaveStatus)
        assertTrue(initial.isSessionFullySynced)
        assertFalse(initial.hasUnsavedChanges)
        assertTrue(h.registry.isSessionReserved("pref-s1"))
        assertTrue(h.registry.isDraftReserved(NoteRecoveryDraftKeys.forNewNote("pref-s1")))
      } finally {
        coord.closeAndAbort()
        testScheduler.runCurrent()
      }
      assertEquals(0, h.registry.snapshot().reservedSessionIds.size)
    }

  @Test
  fun openDuplicateIdentity_blocksWithSessionAlreadyActiveAndReleasesWhenFirstCloses() = runTest {
    val h = CoordinatorHarness(this)
    val note = h.noteRepo.seedNote(id = 10L, title = "Nota 10")
    val c1 =
      h.createCoordinator(
        openRequest = NoteEditorOpenRequest.OpenExisting(noteId = note.id, sessionId = "shared-s"),
        parentScope = this,
      )
    c1.start()
    testScheduler.runCurrent()
    assertEquals(NoteEditorSessionPhase.EDITING, c1.awaitInitialized().phase)

    val c2 =
      h.createCoordinator(
        openRequest = NoteEditorOpenRequest.OpenExisting(noteId = note.id, sessionId = "shared-s"),
        parentScope = this,
      )
    c2.start()
    testScheduler.runCurrent()
    val c2State = c2.awaitInitialized()
    c2.awaitTerminated()

    assertEquals(NoteEditorSessionPhase.BLOCKED, c2State.phase)
    assertTrue(c2State.blockReason is SessionBlockReason.SessionAlreadyActive)
    assertTrue(c2State.isSessionTerminated)
    // Lease original da sessão 1 continua intacta.
    assertTrue(h.registry.isSessionReserved("shared-s"))
    assertEquals(1, h.registry.snapshot().reservedSessionIds.size)

    // Após fechamento gracioso da sessão 1, nova abertura com a mesma chave é permitida.
    assertEquals(SessionCommandOutcome.Completed, submitAndAwait(c1) { SessionCommand.CloseSession(it) })
    c1.awaitTerminated()
    assertEquals(0, h.registry.snapshot().reservedSessionIds.size)

    val c3 =
      h.createCoordinator(
        openRequest = NoteEditorOpenRequest.OpenExisting(noteId = note.id, sessionId = "shared-s"),
        parentScope = this,
      )
    try {
      c3.start()
      testScheduler.runCurrent()
      assertEquals(NoteEditorSessionPhase.EDITING, c3.awaitInitialized().phase)
    } finally {
      c3.closeAndAbort()
      testScheduler.runCurrent()
    }
  }

  @Test
  fun openBlockedOrFailed_releasesLeaseWithoutLeaking() = runTest {
    val h = CoordinatorHarness(this)
    val blockedCoord =
      h.createCoordinator(
        openRequest = NoteEditorOpenRequest.OpenExisting(noteId = 999L, sessionId = "missing-note"),
        parentScope = this,
      )
    blockedCoord.start()
    testScheduler.runCurrent()
    val blockedState = blockedCoord.awaitInitialized()
    blockedCoord.awaitTerminated()
    assertEquals(NoteEditorSessionPhase.BLOCKED, blockedState.phase)
    assertTrue(blockedState.blockReason is SessionBlockReason.TargetNoteNotFound)
    assertEquals(0, h.registry.snapshot().reservedSessionIds.size)

    h.noteRepo.onGetNoteHook = { throw IOException("Falha simulada de leitura") }
    val errorCoord =
      h.createCoordinator(
        openRequest = NoteEditorOpenRequest.OpenExisting(noteId = 1L, sessionId = "err-open"),
        parentScope = this,
      )
    errorCoord.start()
    testScheduler.runCurrent()
    val errorState = errorCoord.awaitInitialized()
    errorCoord.awaitTerminated()
    assertEquals(NoteEditorSessionPhase.ERROR, errorState.phase)
    assertEquals(0, h.registry.snapshot().reservedSessionIds.size)
  }

  @Test
  fun rapidEdits_cancelPreviousDebounceAndPersistSingleCoalescedCheckpoint() = runTest {
    val h = CoordinatorHarness(this)
    val coord =
      h.createCoordinator(
        openRequest = NoteEditorOpenRequest.CreateNew(sessionId = "debounce-s"),
        parentScope = this,
        autosaveDebounceMillis = 600L,
      )
    try {
      coord.start()
      testScheduler.runCurrent()
      coord.awaitInitialized()

      submitMutation(coord, SessionCommand.EditTitle("Titulo 1"))
      testScheduler.advanceTimeBy(300L)
      testScheduler.runCurrent()
      assertEquals(0, h.draftRepo.saveCount.get())

      // Nova mutação antes dos 600ms cancela debounce anterior e reinicia a janela.
      submitMutation(coord, SessionCommand.InsertText("Corpo coalescido"))
      testScheduler.advanceTimeBy(300L)
      testScheduler.runCurrent()
      assertEquals(0, h.draftRepo.saveCount.get())

      // Ao completar 600ms desde a última mutação, ocorre uma única gravação com estado completo.
      testScheduler.advanceTimeBy(300L)
      testScheduler.runCurrent()
      assertEquals(1, h.draftRepo.saveCount.get())

      val state = coord.uiState.value
      assertTrue(state.autosaveStatus is AutosaveStatus.Saved)
      assertTrue(state.isSessionFullySynced)
      val persisted = h.draftRepo.getCheckpointById(NoteRecoveryDraftKeys.forNewNote("debounce-s"))
      assertNotNull(persisted)
      assertEquals("Titulo 1", persisted!!.title)
      assertTrue(
        (persisted.contentState as NoteContentState.Valid).document.blocks.any {
          it.text.contains("Corpo coalescido")
        }
      )
    } finally {
      coord.closeAndAbort()
      testScheduler.runCurrent()
    }
  }

  @Test
  fun undoBackToCleanBaseline_deletesSavedCheckpointAndReturnsToIdle() = runTest {
    val h = CoordinatorHarness(this)
    val coord =
      h.createCoordinator(
        openRequest = NoteEditorOpenRequest.CreateNew(sessionId = "undo-clean"),
        parentScope = this,
      )
    try {
      coord.start()
      testScheduler.runCurrent()
      coord.awaitInitialized()

      submitMutation(coord, SessionCommand.InsertText("Texto temporário"))
      assertEquals(SessionCommandOutcome.Completed, submitAndAwait(coord) { SessionCommand.FlushAutosave(it) })
      val draftId = NoteRecoveryDraftKeys.forNewNote("undo-clean")
      assertNotNull(h.draftRepo.getCheckpointById(draftId))
      assertTrue(coord.uiState.value.autosaveStatus is AutosaveStatus.Saved)

      // Desfaz até coincidir com baseline limpa -> FlushAutosave deve excluir checkpoint no Room.
      submitMutation(coord, SessionCommand.Undo)
      assertEquals(SessionCommandOutcome.Completed, submitAndAwait(coord) { SessionCommand.FlushAutosave(it) })

      assertNull(h.draftRepo.getCheckpointById(draftId))
      assertEquals(1, h.draftRepo.deleteCount.get())
      assertEquals(AutosaveStatus.Idle, coord.uiState.value.autosaveStatus)
      assertFalse(coord.uiState.value.hasUnsavedChanges)
      assertTrue(coord.uiState.value.isSessionFullySynced)
    } finally {
      coord.closeAndAbort()
      testScheduler.runCurrent()
    }
  }

  @Test
  fun folderAvailabilityObserved_whenActiveFolderMovesToTrashOnCleanSession_retainsFallbackCheckpoint() =
    runTest {
      val h = CoordinatorHarness(this)
      val folderA = h.folderRepo.seedFolder(id = 5L, name = "Projetos", isDeleted = false)
      val folderB = h.folderRepo.seedFolder(id = 6L, name = "Estudos", isDeleted = false)
      val existing = h.noteRepo.seedNote(id = 55L, title = "Nota limpa", folderId = folderA.id)

      val coord =
        h.createCoordinator(
          openRequest = NoteEditorOpenRequest.OpenExisting(noteId = existing.id, sessionId = "folder-obs"),
          parentScope = this,
        )
      try {
        coord.start()
        testScheduler.runCurrent()
        val init = coord.awaitInitialized()
        assertEquals(AutosaveStatus.Idle, init.autosaveStatus)
        assertEquals(folderA.id, init.effectiveFolderId)

        // Pasta A vai para lixeira enquanto a sessão estava limpa e sem checkpoint.
        val trashedA = folderA.copy(isDeleted = true, deletedAt = 9_999L)
        h.folderRepo.folders[folderA.id] = trashedA
        coord.submit(SessionCommand.FolderAvailabilityObserved(mapOf(folderA.id to FolderAvailability.InTrash(trashedA))))
        testScheduler.runCurrent()

        val afterFallback = coord.uiState.value
        assertNull(afterFallback.effectiveFolderId)
        assertEquals(folderA.id, afterFallback.fallbackSourceFolderId)
        assertFalse(afterFallback.hasUnsavedChanges)
        assertTrue(afterFallback.hasPendingFolderFallback)
        assertTrue(afterFallback.needsExplicitFolderConfirmation)
        assertTrue(afterFallback.stateGeneration > 0L)
        // Retenção passou a ser necessária -> FlushAutosave grava checkpoint mesmo sem mutação do usuário.
        assertEquals(SessionCommandOutcome.Completed, submitAndAwait(coord) { SessionCommand.FlushAutosave(it) })
        val persisted = h.draftRepo.getCheckpointById(afterFallback.sessionIdentity!!.draftId)
        assertNotNull(persisted)
        assertNull(persisted!!.folderId)
        assertEquals(folderA.id, persisted.fallbackSourceFolderId)

        // Mudança parcial de mapa com pasta B preserva contexto original da baseline (pasta A indisponível).
        submitMutation(coord, SessionCommand.ChangeFolder(folderB.id))
        val movedState = coord.uiState.value
        assertEquals(folderB.id, movedState.effectiveFolderId)
        assertTrue(movedState.warnings.any { it is SessionWarning.OriginalBaselineFolderUnavailable })
      } finally {
        coord.closeAndAbort()
        testScheduler.runCurrent()
      }
    }

  @Test
  fun structuralBarrier_queuesEditsUntilRetryAndCompletionFinishCleanly() = runTest {
    val h = CoordinatorHarness(this)
    val saveEntered = CompletableDeferred<Unit>()
    val saveRelease = CompletableDeferred<Unit>()
    h.draftRepo.onSaveHook = { input ->
      if (input.title == "Durante voo") {
        saveEntered.complete(Unit)
        saveRelease.await()
      }
    }

    val coord =
      h.createCoordinator(
        openRequest = NoteEditorOpenRequest.CreateNew(sessionId = "barrier-s"),
        parentScope = this,
      )
    try {
      coord.start()
      testScheduler.runCurrent()
      coord.awaitInitialized()

      submitMutation(coord, SessionCommand.EditTitle("Durante voo"))
      val flushAck = CompletableDeferred<SessionCommandOutcome>()
      coord.submit(SessionCommand.FlushAutosave(flushAck))
      testScheduler.runCurrent()
      withTimeout(5_000) { saveEntered.await() }

      // Dispara operação estrutural enquanto save está em voo -> barreira fica pendente.
      val consolidateAck = CompletableDeferred<SessionCommandOutcome>()
      coord.submit(SessionCommand.ConsolidateNote(ackDeferred = consolidateAck))
      testScheduler.runCurrent()
      assertTrue(coord.uiState.value.isConsolidating)
      assertEquals(0, h.noteRepo.createCalls.get())

      // Mutação submetida durante barreira deve aguardar liberação sem contaminar consolidação.
      submitMutation(coord, SessionCommand.EditTitle("Após consolidação"))
      assertEquals("Durante voo", coord.uiState.value.title)

      // Libera autosave -> dispara StructuralRetryRequested -> consolida "Durante voo" -> aplica edição na fila.
      saveRelease.complete(Unit)
      testScheduler.runCurrent()

      assertEquals(SessionCommandOutcome.Completed, withTimeout(5_000) { flushAck.await() })
      assertEquals(SessionCommandOutcome.Completed, withTimeout(5_000) { consolidateAck.await() })
      assertEquals(1, h.noteRepo.createCalls.get())
      val createdNote = h.noteRepo.notes.values.single()
      assertEquals("Durante voo", createdNote.title)
      assertEquals("Após consolidação", coord.uiState.value.title)
      assertEquals(createdNote.id, coord.uiState.value.sessionIdentity?.targetNoteId)
    } finally {
      coord.closeAndAbort()
      testScheduler.runCurrent()
    }
  }

  @Test
  fun consolidateNewNote_transitionsIdentityAndSecondConsolidateUpdatesExistingWithoutDuplication() =
    runTest {
      val h = CoordinatorHarness(this)
      val coord =
        h.createCoordinator(
          openRequest = NoteEditorOpenRequest.CreateNew(sessionId = "consolidate-new"),
          parentScope = this,
        )
      try {
        coord.start()
        testScheduler.runCurrent()
        coord.awaitInitialized()

        submitMutation(coord, SessionCommand.EditTitle("Minha nova nota"))
        submitMutation(coord, SessionCommand.InsertText("Conteúdo 1"))
        assertEquals(
          SessionCommandOutcome.Completed,
          submitAndAwait(coord) { SessionCommand.ConsolidateNote(ackDeferred = it) },
        )

        val state1 = coord.uiState.value
        val noteId = state1.sessionIdentity!!.targetNoteId!!
        assertEquals(NoteRecoveryDraftKeys.forExistingNote(noteId, "consolidate-new"), state1.sessionIdentity!!.draftId)
        assertFalse(h.registry.isDraftReserved(NoteRecoveryDraftKeys.forNewNote("consolidate-new")))
        assertTrue(h.registry.isDraftReserved(state1.sessionIdentity!!.draftId))
        assertEquals(AutosaveStatus.Idle, state1.autosaveStatus)
        assertEquals(1, h.noteRepo.createCalls.get())
        assertEquals(0, h.noteRepo.updateCalls.get())

        // Segunda edição e consolidação na mesma sessão atualizam a nota criada sem duplicar.
        submitMutation(coord, SessionCommand.EditTitle("Minha nota v2"))
        assertEquals(SessionCommandOutcome.Completed, submitAndAwait(coord) { SessionCommand.FlushAutosave(it) })
        assertNotNull(h.draftRepo.getCheckpointById(state1.sessionIdentity!!.draftId))

        assertEquals(
          SessionCommandOutcome.Completed,
          submitAndAwait(coord) { SessionCommand.ConsolidateNote(ackDeferred = it) },
        )

        assertEquals(1, h.noteRepo.createCalls.get())
        assertEquals(1, h.noteRepo.updateCalls.get())
        assertEquals("Minha nota v2", h.noteRepo.getNoteById(noteId)?.title)
        assertNull(h.draftRepo.getCheckpointById(state1.sessionIdentity!!.draftId))
        assertEquals(AutosaveStatus.Idle, coord.uiState.value.autosaveStatus)
      } finally {
        coord.closeAndAbort()
        testScheduler.runCurrent()
      }
    }

  @Test
  fun consolidateWithFallbackFolder_blocksWithoutConfirmationAndSavesToRootWhenConfirmed() = runTest {
    val h = CoordinatorHarness(this)
    val folder = h.folderRepo.seedFolder(id = 12L, name = "Apagada", isDeleted = true)
    val coord =
      h.createCoordinator(
        openRequest =
          NoteEditorOpenRequest.CreateNew(
            initialFolderId = folder.id,
            sessionId = "fallback-confirm",
          ),
        parentScope = this,
      )
    try {
      coord.start()
      testScheduler.runCurrent()
      coord.awaitInitialized()

      val blockedRes = submitAndAwait(coord) { SessionCommand.ConsolidateNote(allowFallbackToRoot = false, ackDeferred = it) }
      assertTrue(blockedRes is SessionCommandOutcome.Failed)
      assertEquals(0, h.noteRepo.createCalls.get())

      val confirmedRes = submitAndAwait(coord) { SessionCommand.ConsolidateNote(allowFallbackToRoot = true, ackDeferred = it) }
      assertEquals(SessionCommandOutcome.Completed, confirmedRes)
      assertEquals(1, h.noteRepo.createCalls.get())
      assertNull(h.noteRepo.notes.values.single().folderId)
    } finally {
      coord.closeAndAbort()
      testScheduler.runCurrent()
    }
  }

  @Test
  fun conflictResolution_keepDraftAndDiscardDraft_updateBaselineAndRoomConsistent() = runTest {
    val h = CoordinatorHarness(this)
    val note = h.noteRepo.seedNote(id = 33L, title = "Original", revision = 1L)
    val cKeep =
      h.createCoordinator(
        openRequest = NoteEditorOpenRequest.OpenExisting(noteId = note.id, sessionId = "conflict-keep"),
        parentScope = this,
      )
    try {
      cKeep.start()
      testScheduler.runCurrent()
      cKeep.awaitInitialized()
      submitMutation(cKeep, SessionCommand.EditTitle("Edição local"))
      assertEquals(SessionCommandOutcome.Completed, submitAndAwait(cKeep) { SessionCommand.FlushAutosave(it) })

      // Simula alteração externa na nota elevando revisão no banco.
      h.noteRepo.notes[note.id] = note.copy(title = "Edição externa", revision = 2L, updatedAt = 9_999L)

      val failedConsolidate = submitAndAwait(cKeep) { SessionCommand.ConsolidateNote(ackDeferred = it) }
      assertTrue(failedConsolidate is SessionCommandOutcome.Failed)
      assertTrue(cKeep.uiState.value.activeConflict is RecoveryConflict.NoteDiverged)
      assertEquals(AutosaveStatus.HeldPendingResolution, cKeep.uiState.value.autosaveStatus)

      assertEquals(
        SessionCommandOutcome.Completed,
        submitAndAwait(cKeep) {
          SessionCommand.ResolveConflict(ConflictResolutionOption.KEEP_DRAFT_OVER_CURRENT_NOTE, it)
        },
      )
      assertNull(cKeep.uiState.value.activeConflict)
      assertEquals("Edição local", cKeep.uiState.value.title)
      assertEquals(2L, (cKeep.uiState.value.baseline as NoteEditorBaseline.ExistingNote).baseRevision)
      assertEquals(SessionCommandOutcome.Completed, submitAndAwait(cKeep) { SessionCommand.ConsolidateNote(ackDeferred = it) })
      assertEquals("Edição local", h.noteRepo.getNoteById(note.id)?.title)
    } finally {
      cKeep.closeAndAbort()
      testScheduler.runCurrent()
    }

    // Agora testa DISCARD_DRAFT_USE_CURRENT_NOTE
    val cDiscard =
      h.createCoordinator(
        openRequest = NoteEditorOpenRequest.OpenExisting(noteId = note.id, sessionId = "conflict-discard"),
        parentScope = this,
      )
    try {
      cDiscard.start()
      testScheduler.runCurrent()
      cDiscard.awaitInitialized()
      submitMutation(cDiscard, SessionCommand.EditTitle("Rascunho descartável"))
      assertEquals(SessionCommandOutcome.Completed, submitAndAwait(cDiscard) { SessionCommand.FlushAutosave(it) })
      val oldDraftId = cDiscard.uiState.value.sessionIdentity!!.draftId

      h.noteRepo.notes[note.id] =
        h.noteRepo.getNoteById(note.id)!!.copy(title = "Versão oficial do banco", revision = 4L, updatedAt = 12_345L)
      submitAndAwait(cDiscard) { SessionCommand.ConsolidateNote(ackDeferred = it) }
      assertNotNull(cDiscard.uiState.value.activeConflict)

      assertEquals(
        SessionCommandOutcome.Completed,
        submitAndAwait(cDiscard) {
          SessionCommand.ResolveConflict(ConflictResolutionOption.DISCARD_DRAFT_USE_CURRENT_NOTE, it)
        },
      )
      assertNull(cDiscard.uiState.value.activeConflict)
      assertEquals("Versão oficial do banco", cDiscard.uiState.value.title)
      assertNull(h.draftRepo.getCheckpointById(oldDraftId))
      assertEquals(AutosaveStatus.Idle, cDiscard.uiState.value.autosaveStatus)
    } finally {
      cDiscard.closeAndAbort()
      testScheduler.runCurrent()
    }
  }

  @Test
  fun conflictResolution_keepDraftWhenSelectedFolderUnavailable_savesRebasedFallbackCheckpoint() =
    runTest {
      val h = CoordinatorHarness(this)
      val selectedFolder = h.folderRepo.seedFolder(id = 44L, name = "Temporária", isDeleted = false)
      val note = h.noteRepo.seedNote(id = 34L, title = "Nota Original", folderId = selectedFolder.id, revision = 1L)
      val coord =
        h.createCoordinator(
          openRequest = NoteEditorOpenRequest.OpenExisting(noteId = note.id, sessionId = "conflict-keep-folder"),
          parentScope = this,
        )
      try {
        coord.start()
        testScheduler.runCurrent()
        val opened = coord.awaitInitialized()
        assertEquals(selectedFolder.id, opened.effectiveFolderId)
        assertEquals("Temporária", opened.effectiveFolderName)

        submitMutation(coord, SessionCommand.EditTitle("Rascunho em pasta apagada"))
        assertEquals(SessionCommandOutcome.Completed, submitAndAwait(coord) { SessionCommand.FlushAutosave(it) })

        // Nota diverge no banco (inclusive fixação atualizada externamente) enquanto a pasta ainda está ativa para disparar NoteDiverged.
        h.noteRepo.notes[note.id] =
          note.copy(
            title = "Versão externa",
            revision = 3L,
            updatedAt = 15_000L,
            isPinned = true,
          )

        val failedConsolidate = submitAndAwait(coord) { SessionCommand.ConsolidateNote(ackDeferred = it) }
        assertTrue(failedConsolidate is SessionCommandOutcome.Failed)
        assertTrue(coord.uiState.value.activeConflict is RecoveryConflict.NoteDiverged)

        // Agora a pasta selecionada vai para a lixeira antes da resolução KEEP_DRAFT_OVER_CURRENT_NOTE.
        h.folderRepo.folders[selectedFolder.id] = selectedFolder.copy(isDeleted = true, deletedAt = 16_000L)

        assertEquals(
          SessionCommandOutcome.Completed,
          submitAndAwait(coord) {
            SessionCommand.ResolveConflict(ConflictResolutionOption.KEEP_DRAFT_OVER_CURRENT_NOTE, it)
          },
        )

        val draftId = coord.uiState.value.sessionIdentity!!.draftId
        val persisted = h.draftRepo.getCheckpointById(draftId)
        assertNotNull(persisted)
        assertNull(persisted!!.folderId)
        assertEquals(selectedFolder.id, persisted.fallbackSourceFolderId)
        assertEquals(3L, persisted.baseRevision)
        assertEquals(15_000L, persisted.baseNoteUpdatedAt)
        assertTrue(persisted.isPinned)
        assertEquals("Rascunho em pasta apagada", persisted.title)

        // 1, 2, 3, 4, 5: Estado em memória (SessionRuntimeState) atualizado com snapshot/contexto reconciliados e baseline atual.
        val runtimeState = coord.currentRuntimeSnapshot()!!
        assertNull(runtimeState.snapshot.effectiveFolderId)
        assertEquals(selectedFolder.id, runtimeState.snapshot.fallbackSourceFolderId)
        assertTrue(runtimeState.snapshot.isPinned)
        assertEquals("Rascunho em pasta apagada", runtimeState.snapshot.title)
        assertEquals(FolderAvailability.Root, runtimeState.folderContext.effectiveTarget)
        assertTrue(runtimeState.folderContext.fallbackSource is FolderAvailability.InTrash)
        assertTrue(runtimeState.folderContext.initialBaseline is FolderAvailability.InTrash)
        assertTrue(runtimeState.folderContext.existingNoteFolder is FolderAvailability.InTrash)
        assertNull(runtimeState.effectiveFolderName)

        val runtimeBaseline = runtimeState.baseline as NoteEditorBaseline.ExistingNote
        assertEquals(note.id, runtimeBaseline.noteId)
        assertEquals(3L, runtimeBaseline.baseRevision)
        assertEquals(15_000L, runtimeBaseline.baseNoteUpdatedAt)
        assertEquals("Versão externa", runtimeBaseline.title)
        assertEquals(selectedFolder.id, runtimeBaseline.staticFolderId)
        assertTrue(runtimeBaseline.currentDbIsPinned)

        // 5 e 7: Estado publicado pelo coordenador reflete a reconciliação e coincide com o checkpoint persistido.
        val published = coord.uiState.value
        assertNull(published.activeConflict)
        assertNull(published.effectiveFolderId)
        assertNull(published.effectiveFolderName)
        assertEquals(selectedFolder.id, published.fallbackSourceFolderId)
        assertEquals(persisted.folderId, published.effectiveFolderId)
        assertEquals(persisted.fallbackSourceFolderId, published.fallbackSourceFolderId)
        assertEquals(selectedFolder.id, published.intendedUserFolderId)
        assertTrue(published.hasPendingFolderFallback)
        assertTrue(published.hasUnsavedChanges)
        assertTrue(published.isPinned)
        assertEquals(runtimeBaseline, published.baseline)
        assertTrue(published.needsExplicitFolderConfirmation)
        val readiness = published.consolidationReadiness as ConsolidationReadiness.RequiresExplicitFolderConfirmation
        assertEquals(selectedFolder.id, readiness.fallbackSourceFolderId)
        assertTrue(readiness.fallbackSourceAvailability is FolderAvailability.InTrash)
        assertTrue(
          published.warnings.any {
            it is SessionWarning.FallbackFolderApplied &&
              it.originalFolderId == selectedFolder.id &&
              it.availability is FolderAvailability.InTrash
          }
        )

        // Consistência subsequente: nova edição + FlushAutosave não ressuscita a pasta antiga em memória.
        submitMutation(coord, SessionCommand.EditTitle("Edição subsequente"))
        assertEquals(SessionCommandOutcome.Completed, submitAndAwait(coord) { SessionCommand.FlushAutosave(it) })
        val secondCheckpoint = h.draftRepo.getCheckpointById(draftId)!!
        assertNull(secondCheckpoint.folderId)
        assertEquals(selectedFolder.id, secondCheckpoint.fallbackSourceFolderId)
        assertEquals("Edição subsequente", secondCheckpoint.title)

        // Sem allowFallbackToRoot = true, consolidação continua exigindo confirmação explícita pelo contexto reconciliado.
        val consolidateBlocked =
          submitAndAwait(coord) { SessionCommand.ConsolidateNote(allowFallbackToRoot = false, ackDeferred = it) }
        assertTrue(consolidateBlocked is SessionCommandOutcome.Failed)
      } finally {
        coord.closeAndAbort()
        testScheduler.runCurrent()
      }
    }

  @Test
  fun conflictResolution_keepDraftOverCurrentNote_cleanDraftDeletion_updatesSnapshotContextAndFolderName() =
    runTest {
      val h = CoordinatorHarness(this)
      val folder = h.folderRepo.seedFolder(id = 55L, name = "Nome Antigo", isDeleted = false)
      val note =
        h.noteRepo.seedNote(
          id = 35L,
          title = "Título Inicial",
          folderId = folder.id,
          revision = 1L,
          isPinned = false,
          text = "Corpo convergente",
        )
      val coord =
        h.createCoordinator(
          openRequest = NoteEditorOpenRequest.OpenExisting(noteId = note.id, sessionId = "conflict-keep-clean"),
          parentScope = this,
        )
      try {
        coord.start()
        testScheduler.runCurrent()
        val opened = coord.awaitInitialized()
        assertEquals("Nome Antigo", opened.effectiveFolderName)

        // Usuário edita título e persiste checkpoint.
        submitMutation(coord, SessionCommand.EditTitle("Título Convergente"))
        assertEquals(SessionCommandOutcome.Completed, submitAndAwait(coord) { SessionCommand.FlushAutosave(it) })
        val draftId = coord.uiState.value.sessionIdentity!!.draftId
        assertNotNull(h.draftRepo.getCheckpointById(draftId))

        // Banco diverge adotando o mesmo título/conteúdo + pin atualizado externamente.
        h.noteRepo.notes[note.id] =
          note.copy(
            title = "Título Convergente",
            revision = 4L,
            updatedAt = 25_000L,
            isPinned = true,
          )
        val failedConsolidate = submitAndAwait(coord) { SessionCommand.ConsolidateNote(ackDeferred = it) }
        assertTrue(failedConsolidate is SessionCommandOutcome.Failed)
        assertTrue(coord.uiState.value.activeConflict is RecoveryConflict.NoteDiverged)

        // Pasta é renomeada no repositório antes de KEEP_DRAFT_OVER_CURRENT_NOTE; como após rebase não há
        // diferenças retidas, o checkpoint limpo é excluído.
        val renamedFolder = folder.copy(name = "Nome Atualizado", normalizedName = "nome atualizado", updatedAt = 26_000L)
        h.folderRepo.folders[folder.id] = renamedFolder

        assertEquals(
          SessionCommandOutcome.Completed,
          submitAndAwait(coord) {
            SessionCommand.ResolveConflict(ConflictResolutionOption.KEEP_DRAFT_OVER_CURRENT_NOTE, it)
          },
        )

        // Checkpoint limpo foi excluído.
        assertNull(h.draftRepo.getCheckpointById(draftId))

        // Snapshot, folderContext, effectiveFolderName e baseline em memória refletem a reconciliação.
        val runtimeState = coord.currentRuntimeSnapshot()!!
        assertEquals(folder.id, runtimeState.snapshot.effectiveFolderId)
        assertNull(runtimeState.snapshot.fallbackSourceFolderId)
        assertTrue(runtimeState.snapshot.isPinned)
        assertEquals(FolderAvailability.Active(renamedFolder), runtimeState.folderContext.effectiveTarget)
        assertNull(runtimeState.folderContext.fallbackSource)
        assertEquals(FolderAvailability.Active(renamedFolder), runtimeState.folderContext.existingNoteFolder)
        assertEquals("Nome Atualizado", runtimeState.effectiveFolderName)

        val baseline = runtimeState.baseline as NoteEditorBaseline.ExistingNote
        assertEquals(4L, baseline.baseRevision)
        assertEquals(25_000L, baseline.baseNoteUpdatedAt)
        assertEquals("Título Convergente", baseline.title)
        assertEquals(folder.id, baseline.staticFolderId)
        assertTrue(baseline.currentDbIsPinned)

        // Estado publicado pelo coordenador atualizado sem referências antigas.
        val published = coord.uiState.value
        assertNull(published.activeConflict)
        assertEquals(AutosaveStatus.Idle, published.autosaveStatus)
        assertFalse(published.hasUnsavedChanges)
        assertEquals(folder.id, published.effectiveFolderId)
        assertNull(published.fallbackSourceFolderId)
        assertEquals("Nome Atualizado", published.effectiveFolderName)
        assertTrue(published.isPinned)
        assertEquals(baseline, published.baseline)
        assertEquals(ConsolidationReadiness.Ready(folder.id), published.consolidationReadiness)
        assertTrue(published.isSessionFullySynced)
      } finally {
        coord.closeAndAbort()
        testScheduler.runCurrent()
      }
    }

  @Test
  fun saveDraftAsNewNote_indeterminateStatesA_B_C_D_E_F_andRetryRecovery() = runTest {
    val h = CoordinatorHarness(this)
    val note = h.noteRepo.seedNote(id = 70L, title = "Nota 70", revision = 1L)

    // --- Caso A (antigo presente, novo ausente após IOException pós-chamada) ---
    val coordA =
      h.createCoordinator(
        openRequest = NoteEditorOpenRequest.OpenExisting(noteId = note.id, sessionId = "state-a"),
        parentScope = this,
        sessionIdProvider = { "state-a-new" },
      )
    try {
      coordA.start()
      testScheduler.runCurrent()
      coordA.awaitInitialized()
      submitMutation(coordA, SessionCommand.EditTitle("Draft A"))
      assertEquals(SessionCommandOutcome.Completed, submitAndAwait(coordA) { SessionCommand.FlushAutosave(it) })
      h.noteRepo.notes[note.id] = note.copy(revision = 2L, updatedAt = 5_555L)
      submitAndAwait(coordA) { SessionCommand.ConsolidateNote(ackDeferred = it) }

      h.draftRepo.onReplaceHook = { _, _ -> throw IOException("Falha antes de comitar Estado A") }
      val resA =
        submitAndAwait(coordA) {
          SessionCommand.ResolveConflict(ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE, it)
        }
      assertTrue(resA is SessionCommandOutcome.Failed)
      assertEquals(AutosaveStatus.HeldPendingResolution, coordA.uiState.value.autosaveStatus)
      assertEquals(0, h.handoffTracker.snapshot().activeCount)
      assertFalse(coordA.currentExecutorSnapshot()!!.hasPendingReplacement)
      assertTrue(h.registry.isSessionReserved("state-a"))
      assertFalse(h.registry.isSessionReserved("state-a-new"))
    } finally {
      coordA.closeAndAbort()
      testScheduler.runCurrent()
    }

    // --- Caso B (antigo ausente, novo presente no banco após IOException pós-commit) ---
    val coordB =
      h.createCoordinator(
        openRequest = NoteEditorOpenRequest.OpenExisting(noteId = note.id, sessionId = "state-b"),
        parentScope = this,
        sessionIdProvider = { "state-b-new" },
      )
    try {
      coordB.start()
      testScheduler.runCurrent()
      coordB.awaitInitialized()
      submitMutation(coordB, SessionCommand.EditTitle("Draft B"))
      assertEquals(SessionCommandOutcome.Completed, submitAndAwait(coordB) { SessionCommand.FlushAutosave(it) })
      h.noteRepo.notes[note.id] = h.noteRepo.getNoteById(note.id)!!.copy(revision = 3L, updatedAt = 6_666L)
      submitAndAwait(coordB) { SessionCommand.ConsolidateNote(ackDeferred = it) }

      var injectedFaultB = false
      h.draftRepo.onReplaceHook = { oldId, newInput ->
        val res = h.draftRepo.defaultReplace(oldId, newInput)
        if (!injectedFaultB) {
          injectedFaultB = true
          throw IOException("Queda após commit no SQLite")
        }
        res
      }
      val callsBeforeB = h.draftRepo.replaceCount.get()
      val resB =
        submitAndAwait(coordB) {
          SessionCommand.ResolveConflict(ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE, it)
        }
      assertEquals(SessionCommandOutcome.Completed, resB)
      val expectedNewDraftId = NoteRecoveryDraftKeys.forNewNote("state-b-new")
      assertEquals(expectedNewDraftId, coordB.uiState.value.sessionIdentity!!.draftId)
      assertNull(coordB.uiState.value.activeConflict)
      assertTrue(h.registry.isSessionReserved("state-b-new"))
      assertFalse(h.registry.isSessionReserved("state-b"))
      // Execução: 1ª chamada falhou pós-commit; 2ª foi chamada idempotente do Estado B.
      assertEquals(callsBeforeB + 2, h.draftRepo.replaceCount.get())
    } finally {
      coordB.closeAndAbort()
      testScheduler.runCurrent()
    }

    // --- Caso D (ambos ausentes -> nunca recria via replaceCheckpointWithNewNote) ---
    val coordD =
      h.createCoordinator(
        openRequest = NoteEditorOpenRequest.OpenExisting(noteId = note.id, sessionId = "state-d"),
        parentScope = this,
        sessionIdProvider = { "state-d-new" },
      )
    try {
      coordD.start()
      testScheduler.runCurrent()
      coordD.awaitInitialized()
      submitMutation(coordD, SessionCommand.EditTitle("Draft D"))
      assertEquals(SessionCommandOutcome.Completed, submitAndAwait(coordD) { SessionCommand.FlushAutosave(it) })
      h.noteRepo.notes[note.id] = h.noteRepo.getNoteById(note.id)!!.copy(revision = 4L, updatedAt = 7_777L)
      submitAndAwait(coordD) { SessionCommand.ConsolidateNote(ackDeferred = it) }

      h.draftRepo.onReplaceHook = { oldId, _ ->
        h.draftRepo.drafts.remove(oldId)
        throw IOException("Sumiu tudo Estado D")
      }
      val replaceCountBeforeD = h.draftRepo.replaceCount.get()
      val resD =
        submitAndAwait(coordD) {
          SessionCommand.ResolveConflict(ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE, it)
        }
      assertTrue(resD is SessionCommandOutcome.Failed)
      assertEquals(replaceCountBeforeD + 1, h.draftRepo.replaceCount.get())
      assertNull(h.draftRepo.getCheckpointById(NoteRecoveryDraftKeys.forNewNote("state-d-new")))
      assertEquals(AutosaveStatus.Error("Sumiu tudo Estado D", isTerminal = true), coordD.uiState.value.autosaveStatus)
    } finally {
      coordD.closeAndAbort()
      testScheduler.runCurrent()
    }

    // --- Caso C (ambos os checkpoints coexistem -> STATE_C anomalia sem sobrescrita nem exclusão) ---
    val coordC =
      h.createCoordinator(
        openRequest = NoteEditorOpenRequest.OpenExisting(noteId = note.id, sessionId = "state-c"),
        parentScope = this,
        sessionIdProvider = { "state-c-new" },
      )
    try {
      coordC.start()
      testScheduler.runCurrent()
      coordC.awaitInitialized()
      submitMutation(coordC, SessionCommand.EditTitle("Draft C"))
      assertEquals(SessionCommandOutcome.Completed, submitAndAwait(coordC) { SessionCommand.FlushAutosave(it) })
      val oldDraftIdC = coordC.uiState.value.sessionIdentity!!.draftId
      h.noteRepo.notes[note.id] = h.noteRepo.getNoteById(note.id)!!.copy(revision = 5L, updatedAt = 8_500L)
      submitAndAwait(coordC) { SessionCommand.ConsolidateNote(ackDeferred = it) }

      h.draftRepo.onReplaceHook = { oldId, newInput ->
        val oldSnapshot = h.draftRepo.drafts[oldId]!!
        h.draftRepo.defaultReplace(oldId, newInput)
        // Mantém ambos presentes simultaneamente no banco (coexistência anômala Estado C).
        h.draftRepo.drafts[oldId] = oldSnapshot
        throw IOException("Estado C")
      }
      val newDraftIdC = NoteRecoveryDraftKeys.forNewNote("state-c-new")
      val resC =
        submitAndAwait(coordC) {
          SessionCommand.ResolveConflict(ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE, it)
        }
      assertTrue(resC is SessionCommandOutcome.Failed)
      val errC = coordC.uiState.value.autosaveStatus as AutosaveStatus.Error
      assertTrue(errC.isTerminal)
      assertTrue(errC.message.contains("coexistem"))
      // Nenhum dos dois checkpoints foi sobrescrito ou excluído.
      assertNotNull(h.draftRepo.getCheckpointById(oldDraftIdC))
      assertNotNull(h.draftRepo.getCheckpointById(newDraftIdC))
      assertEquals("Draft C", h.draftRepo.getCheckpointById(newDraftIdC)?.title)
      assertNull(h.handoffTracker.findActiveCandidate(oldDraftIdC))
      assertEquals(0, h.handoffTracker.snapshot().pendingCount)
      assertTrue(h.registry.isSessionReserved("state-c"))
      assertFalse(h.registry.isSessionReserved("state-c-new"))
    } finally {
      coordC.closeAndAbort()
      testScheduler.runCurrent()
    }
    assertEquals(0, h.registry.snapshot().reservedSessionIds.size)

    // --- Caso E (destino divergente -> STATE_E anomalia sem sobrescrita) ---
    val coordE =
      h.createCoordinator(
        openRequest = NoteEditorOpenRequest.OpenExisting(noteId = note.id, sessionId = "state-e"),
        parentScope = this,
        sessionIdProvider = { "state-e-new" },
      )
    try {
      coordE.start()
      testScheduler.runCurrent()
      coordE.awaitInitialized()
      submitMutation(coordE, SessionCommand.EditTitle("Draft E"))
      assertEquals(SessionCommandOutcome.Completed, submitAndAwait(coordE) { SessionCommand.FlushAutosave(it) })
      val oldDraftIdE = coordE.uiState.value.sessionIdentity!!.draftId
      h.noteRepo.notes[note.id] = h.noteRepo.getNoteById(note.id)!!.copy(revision = 6L, updatedAt = 8_888L)
      submitAndAwait(coordE) { SessionCommand.ConsolidateNote(ackDeferred = it) }

      h.draftRepo.onReplaceHook = { oldId, newInput ->
        h.draftRepo.defaultReplace(oldId, newInput.copy(title = "Adulterado"))
        throw IOException("Estado E")
      }
      val newDraftIdE = NoteRecoveryDraftKeys.forNewNote("state-e-new")
      val resE =
        submitAndAwait(coordE) {
          SessionCommand.ResolveConflict(ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE, it)
        }
      assertTrue(resE is SessionCommandOutcome.Failed)
      val errE = coordE.uiState.value.autosaveStatus as AutosaveStatus.Error
      assertTrue(errE.isTerminal)
      assertTrue(errE.message.contains("diverge"))
      assertNull(h.draftRepo.getCheckpointById(oldDraftIdE))
      assertEquals(
        "Adulterado",
        h.draftRepo.getCheckpointById(newDraftIdE)?.title,
      )
      assertNull(h.handoffTracker.findActiveCandidate(oldDraftIdE))
      assertEquals(0, h.handoffTracker.snapshot().pendingCount)
      assertTrue(h.registry.isSessionReserved("state-e"))
      assertFalse(h.registry.isSessionReserved("state-e-new"))
    } finally {
      coordE.closeAndAbort()
      testScheduler.runCurrent()
    }
    assertEquals(0, h.registry.snapshot().reservedSessionIds.size)

    // --- Caso F (falha de leitura na reconciliação -> preserva PENDING no tracker & bloqueia ops) ---
    var nextIdToggle = 0
    val coordF =
      h.createCoordinator(
        openRequest = NoteEditorOpenRequest.OpenExisting(noteId = note.id, sessionId = "state-f"),
        parentScope = this,
        sessionIdProvider = { "state-f-new-${++nextIdToggle}" },
      )
    try {
      coordF.start()
      testScheduler.runCurrent()
      coordF.awaitInitialized()
      submitMutation(coordF, SessionCommand.EditTitle("Draft F"))
      assertEquals(SessionCommandOutcome.Completed, submitAndAwait(coordF) { SessionCommand.FlushAutosave(it) })
      val oldDraftIdF = coordF.uiState.value.sessionIdentity!!.draftId

      h.noteRepo.notes[note.id] = h.noteRepo.getNoteById(note.id)!!.copy(revision = 7L, updatedAt = 9_999L)
      submitAndAwait(coordF) { SessionCommand.ConsolidateNote(ackDeferred = it) }

      h.draftRepo.onReplaceHook = { oldId, newInput ->
        h.draftRepo.defaultReplace(oldId, newInput)
        throw IOException("Falha pós-commit")
      }
      h.draftRepo.onGetCheckpointHook = { throw IOException("Falha de leitura na reconciliação") }

      val resF =
        submitAndAwait(coordF) {
          SessionCommand.ResolveConflict(ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE, it)
        }
      assertTrue(resF is SessionCommandOutcome.Failed)
      val activeHandoff = h.handoffTracker.findActiveCandidate(oldDraftIdF)
      assertNotNull(activeHandoff)
      assertEquals(ReplacementHandoffStatus.PENDING, activeHandoff!!.status)

      // Enquanto pendente/indeterminado, FlushAutosave e ConsolidateNote devem falhar com segurança.
      assertTrue(submitAndAwait(coordF) { SessionCommand.FlushAutosave(it) } is SessionCommandOutcome.Failed)
      assertTrue(submitAndAwait(coordF) { SessionCommand.ConsolidateNote(ackDeferred = it) } is SessionCommandOutcome.Failed)

      // Ao restaurar leitura no Room, recuperação pelo oldDraftId via SessionOpenLoader redireciona ao candidato validado!
      h.draftRepo.onGetCheckpointHook = null
      coordF.closeAndAbort()
      testScheduler.runCurrent()

      val recoveredCoord =
        h.createCoordinator(
          openRequest = NoteEditorOpenRequest.RecoverDraft(oldDraftIdF, RecoveryTargetExpectation.Any),
          parentScope = this,
        )
      try {
        recoveredCoord.start()
        testScheduler.runCurrent()
        val recInit = recoveredCoord.awaitInitialized()
        assertEquals(NoteEditorSessionPhase.EDITING, recInit.phase)
        assertEquals(activeHandoff.candidateNewDraftId, recInit.sessionIdentity!!.draftId)
        assertEquals("Draft F", recInit.title)
        assertEquals(
          ReplacementHandoffStatus.RESOLVED_TO_NEW,
          h.handoffTracker.findActiveCandidate(oldDraftIdF)?.status,
        )
      } finally {
        recoveredCoord.closeAndAbort()
        testScheduler.runCurrent()
      }
    } finally {
      coordF.closeAndAbort()
      testScheduler.runCurrent()
    }
  }

  @Test
  fun closeAndAbort_duringInFlightSaveDraftAsNewNote_joinsReconcilesStateBAndReleasesRegistry() =
    runTest {
      val h = CoordinatorHarness(this)
      val note = h.noteRepo.seedNote(id = 88L, title = "Nota 88", revision = 1L)
      val replaceStarted = CompletableDeferred<Unit>()
      val releaseReplace = CompletableDeferred<Unit>()

      val coord =
        h.createCoordinator(
          openRequest = NoteEditorOpenRequest.OpenExisting(noteId = note.id, sessionId = "abort-rec"),
          parentScope = this,
          sessionIdProvider = { "abort-rec-new" },
        )
      coord.start()
      testScheduler.runCurrent()
      coord.awaitInitialized()

      submitMutation(coord, SessionCommand.EditTitle("Conteúdo salvo antes do abort"))
      assertEquals(SessionCommandOutcome.Completed, submitAndAwait(coord) { SessionCommand.FlushAutosave(it) })
      val oldDraftId = coord.uiState.value.sessionIdentity!!.draftId

      h.noteRepo.notes[note.id] = note.copy(revision = 2L, updatedAt = 15_000L)
      submitAndAwait(coord) { SessionCommand.ConsolidateNote(ackDeferred = it) }

      var injectedAbortFault = false
      h.draftRepo.onReplaceHook = { oldId, newInput ->
        if (!injectedAbortFault) {
          injectedAbortFault = true
          replaceStarted.complete(Unit)
          releaseReplace.await()
          h.draftRepo.defaultReplace(oldId, newInput)
          throw IOException("Cancelamento concorrente simulado após commit")
        }
        h.draftRepo.defaultReplace(oldId, newInput)
      }

      val resolveAck = CompletableDeferred<SessionCommandOutcome>()
      coord.submit(SessionCommand.ResolveConflict(ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE, resolveAck))
      testScheduler.runCurrent()
      withTimeout(5_000) { replaceStarted.await() }

      // Libera commit/exceção e aciona encerramento abrupto imediatamente.
      releaseReplace.complete(Unit)
      coord.closeAndAbort()
      testScheduler.runCurrent()
      coord.awaitTerminated()

      assertEquals(0, h.registry.snapshot().reservedSessionIds.size)
      assertTrue(resolveAck.isCompleted)
      assertEquals(
        ReplacementHandoffStatus.RESOLVED_TO_NEW,
        h.handoffTracker.findActiveCandidate(oldDraftId)?.status,
      )
      assertNotNull(h.draftRepo.getCheckpointById(NoteRecoveryDraftKeys.forNewNote("abort-rec-new")))
    }

  @Test
  fun saveDraftAsNewNote_whenApplyStructuralFailureReceivesPromotedToNew_promotesRuntimeCompletesCommandAndReleasesBarrier() =
    runTest {
      val h = CoordinatorHarness(this)
      val folder = h.folderRepo.seedFolder(id = 44L, name = "Projetos")
      val note = h.noteRepo.seedNote(id = 89L, title = "Nota Original 89", folderId = folder.id, revision = 1L)
      var generatedCounter = 0

      val coord =
        h.createCoordinator(
          openRequest = NoteEditorOpenRequest.OpenExisting(noteId = note.id, sessionId = "fail-promoted-old"),
          parentScope = this,
          sessionIdProvider = { "fail-promoted-new-${++generatedCounter}" },
        )
      try {
        coord.start()
        testScheduler.runCurrent()
        coord.awaitInitialized()

        submitMutation(coord, SessionCommand.EditTitle("Título Editado Preservado"))
        submitMutation(coord, SessionCommand.InsertText("Conteúdo editado preservado"))
        submitMutation(coord, SessionCommand.ChangePaperStyle(NotePaperStyle.PONTILHADO))
        submitMutation(coord, SessionCommand.SetPinned(true))
        assertEquals(
          SessionCommandOutcome.Completed,
          submitAndAwait(coord) { SessionCommand.FlushAutosave(it) },
        )

        val oldRuntime = coord.currentRuntimeSnapshot()!!
        val oldLease = oldRuntime.lease
        val oldDraftId = oldRuntime.identity.draftId

        // Diverge nota no banco e força conflito antes da promoção.
        h.noteRepo.notes[note.id] = note.copy(revision = 2L, updatedAt = 25_000L)
        val consolidateOutcome = submitAndAwait(coord) { SessionCommand.ConsolidateNote(ackDeferred = it) }
        assertTrue(consolidateOutcome is SessionCommandOutcome.Failed)
        assertTrue(coord.uiState.value.activeConflict is RecoveryConflict.NoteDiverged)

        // Simula falha após commit no Room (gerando ReconciledIndeterminate(PromotedToNew))
        // e entrega esse resultado especificamente pelo caminho de falha (applyStructuralFailure).
        var injectedFault = false
        h.draftRepo.onReplaceHook = { oldId, newInput ->
          val res = h.draftRepo.defaultReplace(oldId, newInput)
          if (!injectedFault) {
            injectedFault = true
            throw IOException("Queda pós-commit simulada")
          }
          res
        }
        coord.transformStructuralResultForTest = { raw ->
          val succ = raw as StructuralRunnerResult.Succeeded
          StructuralRunnerResult.Failed(
            cause = IllegalStateException("Falha simulada para exercitar applyStructuralFailure"),
            fallbackData = succ.data,
          )
        }

        val resolveOutcome =
          submitAndAwait(coord) {
            SessionCommand.ResolveConflict(ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE, it)
          }

        assertEquals(SessionCommandOutcome.Completed, resolveOutcome)
        val expectedNewDraftId = NoteRecoveryDraftKeys.forNewNote("fail-promoted-new-1")

        // 1 e 2. runtime.identity, runtime.lease e latestCommittedLease atualizados para a nova identidade/lease.
        val rtAfter = coord.currentRuntimeSnapshot()!!
        assertEquals(expectedNewDraftId, rtAfter.identity.draftId)
        assertEquals("fail-promoted-new-1", rtAfter.identity.sessionId)
        assertNull(rtAfter.identity.targetNoteId)
        assertNotEquals(oldLease, rtAfter.lease)
        assertEquals(rtAfter.lease, coord.latestCommittedLeaseForTest())

        // 3. baseline convertido para NewNote mantendo metadados iniciais do rascunho.
        val newBaseline = rtAfter.baseline as NoteEditorBaseline.NewNote
        assertEquals("", newBaseline.title)
        assertEquals(NotePaperStyle.PONTILHADO, newBaseline.paperStyle)
        assertEquals(folder.id, newBaseline.staticFolderId)
        assertFalse(newBaseline.initialIsPinned)

        // 4, 5, 6 e 7. snapshot, conteúdo, estado publicado, ausência de conflito/erro e AutosaveStatus.Saved.
        assertEquals("Título Editado Preservado", rtAfter.snapshot.title)
        assertTrue(rtAfter.snapshot.isPinned)
        assertEquals(NotePaperStyle.PONTILHADO, rtAfter.snapshot.paperStyle)
        assertEquals(folder.id, rtAfter.snapshot.effectiveFolderId)

        val published = coord.uiState.value
        assertEquals(expectedNewDraftId, published.sessionIdentity!!.draftId)
        assertEquals("fail-promoted-new-1", published.sessionIdentity!!.sessionId)
        assertNull(published.sessionIdentity!!.targetNoteId)
        assertEquals("Título Editado Preservado", published.title)
        assertEquals("Conteúdo editado preservado", published.editorState.document.blocks.single().text)
        assertEquals(folder.name, published.effectiveFolderName)
        assertNull(published.activeConflict)
        assertNull(published.errorMessage)
        assertTrue(published.autosaveStatus is AutosaveStatus.Saved)

        // 8 e 9. Barreira liberada, uncommittedReplacement limpo e fluxo normal continua na mesma sessão promovida.
        assertFalse(coord.hasPendingBarrierForTest())
        assertFalse(coord.isMailboxBarrierActiveForTest())
        assertFalse(coord.hasUncommittedReplacementForTest())
        assertTrue(h.registry.isSessionReserved("fail-promoted-new-1"))
        assertFalse(h.registry.isSessionReserved("fail-promoted-old"))
        assertEquals(
          ReplacementHandoffStatus.RESOLVED_TO_NEW,
          h.handoffTracker.findActiveCandidate(oldDraftId)?.status,
        )

        coord.transformStructuralResultForTest = null
        h.draftRepo.onReplaceHook = null
        submitMutation(coord, SessionCommand.EditTitle("Edição Pós-Promoção"))
        val flushAfter = submitAndAwait(coord) { SessionCommand.FlushAutosave(it) }
        assertEquals(SessionCommandOutcome.Completed, flushAfter)
        assertEquals(1, generatedCounter)
        assertEquals(expectedNewDraftId, coord.uiState.value.sessionIdentity!!.draftId)
        assertEquals("Edição Pós-Promoção", h.draftRepo.getCheckpointById(expectedNewDraftId)?.title)
        assertNull(h.draftRepo.getCheckpointById(oldDraftId))
      } finally {
        coord.closeAndAbort()
        testScheduler.runCurrent()
      }
      assertEquals(0, h.registry.snapshot().reservedSessionIds.size)
    }

  @Test
  fun saveDraftAsNewNote_whenIndeterminateReadFailureOccurs_preservesUncommittedReplacementBlocksNewTransfersAndRecovers() =
    runTest {
      val h = CoordinatorHarness(this)
      val note = h.noteRepo.seedNote(id = 90L, title = "Nota 90", revision = 1L)
      var generatedCounter = 0

      val coord =
        h.createCoordinator(
          openRequest = NoteEditorOpenRequest.OpenExisting(noteId = note.id, sessionId = "indet-read-old"),
          parentScope = this,
          sessionIdProvider = { "indet-read-new-${++generatedCounter}" },
        )
      try {
        coord.start()
        testScheduler.runCurrent()
        coord.awaitInitialized()
        submitMutation(coord, SessionCommand.EditTitle("Conteúdo pendente"))
        assertEquals(SessionCommandOutcome.Completed, submitAndAwait(coord) { SessionCommand.FlushAutosave(it) })
        val oldIdentity = coord.uiState.value.sessionIdentity!!
        val oldLease = coord.currentRuntimeSnapshot()!!.lease

        h.noteRepo.notes[note.id] = note.copy(revision = 2L, updatedAt = 30_000L)
        assertTrue(submitAndAwait(coord) { SessionCommand.ConsolidateNote(ackDeferred = it) } is SessionCommandOutcome.Failed)

        h.draftRepo.onReplaceHook = { oldId, newInput ->
          h.draftRepo.defaultReplace(oldId, newInput)
          throw IOException("Queda após commit no SQLite")
        }
        h.draftRepo.onGetCheckpointHook = { throw IOException("Falha de leitura na reconciliação") }

        val firstOutcome =
          submitAndAwait(coord) {
            SessionCommand.ResolveConflict(ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE, it)
          }
        assertTrue(firstOutcome is SessionCommandOutcome.Failed)
        assertTrue(coord.hasUncommittedReplacementForTest())
        assertEquals(oldIdentity, coord.currentRuntimeSnapshot()!!.identity)
        assertEquals(oldLease, coord.currentRuntimeSnapshot()!!.lease)
        assertEquals(oldLease, coord.latestCommittedLeaseForTest())
        val errStatus = coord.uiState.value.autosaveStatus as AutosaveStatus.Error
        assertFalse(errStatus.isTerminal)

        // Bloqueia operações estruturais e autosave enquanto indeterminado.
        assertTrue(submitAndAwait(coord) { SessionCommand.FlushAutosave(it) } is SessionCommandOutcome.Failed)
        assertTrue(submitAndAwait(coord) { SessionCommand.ConsolidateNote(ackDeferred = it) } is SessionCommandOutcome.Failed)
        assertTrue(submitAndAwait(coord) { SessionCommand.DiscardDraftAndClose(it) } is SessionCommandOutcome.Failed)

        // Bloqueia segunda transferência concorrente no ActiveSessionRegistry sem chamar replaceCheckpointWithNewNote.
        val replaceCountBeforeRetry = h.draftRepo.replaceCount.get()
        val retryTransferOutcome =
          submitAndAwait(coord) {
            SessionCommand.ResolveConflict(ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE, it)
          }
        assertTrue(retryTransferOutcome is SessionCommandOutcome.Failed)
        assertEquals(replaceCountBeforeRetry, h.draftRepo.replaceCount.get())
        assertEquals(1, h.registry.snapshot().pendingTransferCount)
        assertTrue(coord.hasUncommittedReplacementForTest())
      } finally {
        coord.closeAndAbort()
        testScheduler.runCurrent()
      }
      assertEquals(0, h.registry.snapshot().reservedSessionIds.size)
    }

  @Test
  fun saveDraftAsNewNote_whenReconciledAsRevertedNeitherPresentOrDivergent_clearsPendingContextAndNeverPromotes() =
    runTest {
      suspend fun assertNonPromotedCase(
        sessionId: String,
        expectTerminalError: Boolean,
        expectConflictRemains: Boolean,
        hook: suspend CoordinatorHarness.(String, NoteRecoveryDraftInput) -> ReplaceCheckpointWithNewNoteResult,
      ) {
        val h = CoordinatorHarness(this)
        val note = h.noteRepo.seedNote(id = 91L, title = "Nota 91", revision = 1L)
        val coord =
          h.createCoordinator(
            openRequest = NoteEditorOpenRequest.OpenExisting(noteId = note.id, sessionId = sessionId),
            parentScope = this,
            sessionIdProvider = { "$sessionId-new" },
          )
        try {
          coord.start()
          testScheduler.runCurrent()
          coord.awaitInitialized()
          submitMutation(coord, SessionCommand.EditTitle("Draft $sessionId"))
          assertEquals(SessionCommandOutcome.Completed, submitAndAwait(coord) { SessionCommand.FlushAutosave(it) })
          val oldIdentity = coord.uiState.value.sessionIdentity!!
          val oldLease = coord.currentRuntimeSnapshot()!!.lease

          h.noteRepo.notes[note.id] = note.copy(revision = 2L, updatedAt = 35_000L)
          assertTrue(submitAndAwait(coord) { SessionCommand.ConsolidateNote(ackDeferred = it) } is SessionCommandOutcome.Failed)

          h.draftRepo.onReplaceHook = { oldId, newInput -> h.hook(oldId, newInput) }
          val outcome =
            submitAndAwait(coord) {
              SessionCommand.ResolveConflict(ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE, it)
            }

          assertTrue(outcome is SessionCommandOutcome.Failed)
          assertFalse(coord.hasUncommittedReplacementForTest())
          assertEquals(oldIdentity, coord.currentRuntimeSnapshot()!!.identity)
          assertEquals(oldLease, coord.currentRuntimeSnapshot()!!.lease)
          assertEquals(oldLease, coord.latestCommittedLeaseForTest())
          assertTrue(h.registry.isSessionReserved(sessionId))
          assertFalse(h.registry.isSessionReserved("$sessionId-new"))
          if (expectTerminalError) {
            val status = coord.uiState.value.autosaveStatus as AutosaveStatus.Error
            assertTrue(status.isTerminal)
          } else {
            assertEquals(AutosaveStatus.HeldPendingResolution, coord.uiState.value.autosaveStatus)
          }
          if (expectConflictRemains) {
            assertNotNull(coord.uiState.value.activeConflict)
          }
        } finally {
          coord.closeAndAbort()
          testScheduler.runCurrent()
        }
        assertEquals(0, h.registry.snapshot().reservedSessionIds.size)
      }

      assertNonPromotedCase(
        sessionId = "coord-d-reverted",
        expectTerminalError = false,
        expectConflictRemains = true,
      ) { _, _ -> throw IOException("Falha antes do replace") }

      assertNonPromotedCase(
        sessionId = "coord-d-neither",
        expectTerminalError = true,
        expectConflictRemains = true,
      ) { oldId, _ ->
        draftRepo.drafts.remove(oldId)
        throw IOException("Sumiu tudo")
      }

      assertNonPromotedCase(
        sessionId = "coord-d-divergent",
        expectTerminalError = true,
        expectConflictRemains = true,
      ) { oldId, newInput ->
        draftRepo.defaultReplace(oldId, newInput.copy(title = "Adulterado"))
        throw IOException("Divergente")
      }
    }

  @Test
  fun closeSession_withPendingReplacementReconciledAsPromotedToNew_updatesLatestCommittedLeaseAndReleasesNewLease() =
    runTest {
      val h = CoordinatorHarness(this)
      val note = h.noteRepo.seedNote(id = 92L, title = "Nota 92", revision = 1L)
      val coord =
        h.createCoordinator(
          openRequest = NoteEditorOpenRequest.OpenExisting(noteId = note.id, sessionId = "close-prom-old"),
          parentScope = this,
          sessionIdProvider = { "close-prom-new" },
        )
      coord.start()
      testScheduler.runCurrent()
      coord.awaitInitialized()

      submitMutation(coord, SessionCommand.EditTitle("Draft para fechar promovido"))
      assertEquals(SessionCommandOutcome.Completed, submitAndAwait(coord) { SessionCommand.FlushAutosave(it) })
      val oldLease = coord.currentRuntimeSnapshot()!!.lease

      h.noteRepo.notes[note.id] = note.copy(revision = 2L, updatedAt = 40_000L)
      assertTrue(submitAndAwait(coord) { SessionCommand.ConsolidateNote(ackDeferred = it) } is SessionCommandOutcome.Failed)

      // 1ª etapa: replace ocorre no Room, mas reconciliação falha na leitura (Estado F -> deixa uncommittedReplacement ativo).
      h.draftRepo.onReplaceHook = { oldId, newInput ->
        h.draftRepo.defaultReplace(oldId, newInput)
        throw IOException("Falha pós-commit no Room")
      }
      h.draftRepo.onGetCheckpointHook = { throw IOException("Falha temporária de leitura") }
      assertTrue(
        submitAndAwait(coord) {
          SessionCommand.ResolveConflict(ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE, it)
        } is SessionCommandOutcome.Failed
      )
      assertTrue(coord.hasUncommittedReplacementForTest())
      assertEquals(oldLease, coord.latestCommittedLeaseForTest())

      // 2ª etapa: leitura/execução no Room se recuperam e CloseSession reconcilia como PromotedToNew.
      h.draftRepo.onReplaceHook = null
      h.draftRepo.onGetCheckpointHook = null
      val closeOutcome = submitAndAwait(coord) { SessionCommand.CloseSession(it) }
      coord.awaitTerminated()

      assertEquals(SessionCommandOutcome.Completed, closeOutcome)
      val promotedLease = coord.latestCommittedLeaseForTest()!!
      assertNotEquals(oldLease, promotedLease)
      assertEquals(promotedLease, coord.currentRuntimeSnapshot()!!.lease)
      assertEquals(NoteRecoveryDraftKeys.forNewNote("close-prom-new"), coord.uiState.value.sessionIdentity!!.draftId)
      assertFalse(coord.hasUncommittedReplacementForTest())
      assertFalse(h.registry.isLeaseActive(promotedLease))
      assertFalse(h.registry.isLeaseActive(oldLease))
      assertTrue(h.registry.snapshot().reservedSessionIds.isEmpty())
      assertTrue(h.registry.snapshot().reservedDraftIds.isEmpty())
    }

  @Test
  fun closeSession_withPendingReplacementReconciledAsRevertedToOld_updatesLatestCommittedLeaseAndReleasesPreservedLease() =
    runTest {
      val h = CoordinatorHarness(this)
      val note = h.noteRepo.seedNote(id = 93L, title = "Nota 93", revision = 1L)
      val coord =
        h.createCoordinator(
          openRequest = NoteEditorOpenRequest.OpenExisting(noteId = note.id, sessionId = "close-rev-old"),
          parentScope = this,
          sessionIdProvider = { "close-rev-new" },
        )
      coord.start()
      testScheduler.runCurrent()
      coord.awaitInitialized()

      submitMutation(coord, SessionCommand.EditTitle("Draft para fechar revertido"))
      assertEquals(SessionCommandOutcome.Completed, submitAndAwait(coord) { SessionCommand.FlushAutosave(it) })
      val oldLease = coord.currentRuntimeSnapshot()!!.lease

      h.noteRepo.notes[note.id] = note.copy(revision = 2L, updatedAt = 41_000L)
      assertTrue(submitAndAwait(coord) { SessionCommand.ConsolidateNote(ackDeferred = it) } is SessionCommandOutcome.Failed)

      // 1ª etapa: replace falha antes de escrever no Room e leitura de reconciliação falha (Estado F).
      h.draftRepo.onReplaceHook = { _, _ -> throw IOException("Falha antes de substituir") }
      h.draftRepo.onGetCheckpointHook = { throw IOException("Falha temporária de leitura") }
      assertTrue(
        submitAndAwait(coord) {
          SessionCommand.ResolveConflict(ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE, it)
        } is SessionCommandOutcome.Failed
      )
      assertTrue(coord.hasUncommittedReplacementForTest())

      // 2ª etapa: leitura se recupera, CloseSession reconcilia como RevertedToOld e terminal cleanup libera preservedLease.
      h.draftRepo.onGetCheckpointHook = null
      val closeOutcome = submitAndAwait(coord) { SessionCommand.CloseSession(it) }
      coord.awaitTerminated()

      assertEquals(SessionCommandOutcome.Completed, closeOutcome)
      assertEquals(oldLease, coord.latestCommittedLeaseForTest())
      assertEquals(oldLease, coord.currentRuntimeSnapshot()!!.lease)
      assertEquals(NoteRecoveryDraftKeys.forExistingNote(note.id, "close-rev-old"), coord.uiState.value.sessionIdentity!!.draftId)
      assertFalse(coord.hasUncommittedReplacementForTest())
      assertFalse(h.registry.isLeaseActive(oldLease))
      assertTrue(h.registry.snapshot().reservedSessionIds.isEmpty())
      assertTrue(h.registry.snapshot().reservedDraftIds.isEmpty())
    }

  @Test
  fun closeSession_whenReconcilerRemainsIndeterminateReadFailure_keepsOldLeaseAndPreservesPendingHandoff() =
    runTest {
      val h = CoordinatorHarness(this)
      val note = h.noteRepo.seedNote(id = 94L, title = "Nota 94", revision = 1L)
      val coord =
        h.createCoordinator(
          openRequest = NoteEditorOpenRequest.OpenExisting(noteId = note.id, sessionId = "close-indet-old"),
          parentScope = this,
          sessionIdProvider = { "close-indet-new" },
        )
      coord.start()
      testScheduler.runCurrent()
      coord.awaitInitialized()

      submitMutation(coord, SessionCommand.EditTitle("Draft leitura indeterminada"))
      assertEquals(SessionCommandOutcome.Completed, submitAndAwait(coord) { SessionCommand.FlushAutosave(it) })
      val oldRuntime = coord.currentRuntimeSnapshot()!!
      val oldLease = oldRuntime.lease
      val oldDraftId = oldRuntime.identity.draftId

      h.noteRepo.notes[note.id] = note.copy(revision = 2L, updatedAt = 42_000L)
      assertTrue(submitAndAwait(coord) { SessionCommand.ConsolidateNote(ackDeferred = it) } is SessionCommandOutcome.Failed)

      h.draftRepo.onReplaceHook = { oldId, newInput ->
        h.draftRepo.defaultReplace(oldId, newInput)
        throw IOException("Queda após replace")
      }
      h.draftRepo.onGetCheckpointHook = { throw IOException("Leitura persistentemente indisponível") }

      assertTrue(
        submitAndAwait(coord) {
          SessionCommand.ResolveConflict(ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE, it)
        } is SessionCommandOutcome.Failed
      )

      val closeOutcome = submitAndAwait(coord) { SessionCommand.CloseSession(it) }
      coord.awaitTerminated()

      assertTrue(closeOutcome is SessionCommandOutcome.Failed)
      assertEquals(oldLease, coord.latestCommittedLeaseForTest())
      assertEquals(oldLease, coord.currentRuntimeSnapshot()!!.lease)
      assertEquals(oldRuntime.identity, coord.currentRuntimeSnapshot()!!.identity)
      assertTrue(coord.hasUncommittedReplacementForTest())
      assertEquals(
        ReplacementHandoffStatus.PENDING,
        h.handoffTracker.findActiveCandidate(oldDraftId)?.status,
      )
      assertTrue(h.registry.snapshot().reservedSessionIds.isEmpty())
      assertTrue(h.registry.snapshot().reservedDraftIds.isEmpty())
    }

  @Test
  fun persistExistingNotePin_whenNoteModifiedConcurrentlyBeforePinCommit_opensConflictAndKeepsOriginalBaselineRevision() =
    runTest {
      val h = CoordinatorHarness(this)
      val existing =
        h.noteRepo.seedNote(
          id = 210L,
          title = "Original",
          revision = 1L,
          isPinned = false,
          text = "Base",
        )
      val coord =
        h.createCoordinator(
          openRequest = NoteEditorOpenRequest.OpenExisting(existing.id),
          parentScope = backgroundScope,
        )
      coord.start()
      testScheduler.runCurrent()
      coord.awaitInitialized()

      submitMutation(
        coord,
        SessionCommand.ApplyTextChange("p-${existing.id}", "Edição em memória", 17, 17),
      )

      // Simula escrita concorrente disparada durante a janela da operação condicional de pin.
      h.noteRepo.onBeforeConditionalPinHook = { id ->
        val current = h.noteRepo.notes[id]!!
        h.noteRepo.notes[id] =
          current.copy(
            title = "Modificada concorrentemente",
            revision = current.revision + 1L,
            updatedAt = current.updatedAt + 500L,
          )
      }

      val outcome = submitAndAwait(coord) { SessionCommand.PersistExistingNotePin(isPinned = true, ackDeferred = it) }

      assertTrue(outcome is SessionCommandOutcome.Failed)
      val state = coord.uiState.value
      assertNotNull(state.activeConflict)
      assertEquals(1L, (state.baseline as NoteEditorBaseline.ExistingNote).baseRevision)
      assertFalse(h.noteRepo.notes[existing.id]!!.isPinned)
      assertEquals(2L, h.noteRepo.notes[existing.id]!!.revision)
      assertFalse(state.isPinned)
      assertEquals("Edição em memória", state.editorState.document.blocks.first().text)
      coord.closeAndAbort()
    }

  private class TestPreferencesRepository : PreferencesRepository {
    var current = AppPreferences()

    override fun observePreferences(): Flow<AppPreferences> = emptyFlow()

    override suspend fun getPreferences(): AppPreferences = current

    override suspend fun updateThemeMode(themeMode: AppThemeMode) {
      current = current.copy(themeMode = themeMode)
    }

    override suspend fun updateDefaultNotePaper(paperStyle: NotePaperStyle) {
      current = current.copy(defaultNotePaper = paperStyle)
    }

    override suspend fun updateHomeViewMode(homeViewMode: HomeViewMode) {
      current = current.copy(homeViewMode = homeViewMode)
    }
  }

  private class TestFolderRepository : FolderRepository {
    val folders = linkedMapOf<Long, Folder>()

    fun seedFolder(id: Long, name: String, isDeleted: Boolean = false): Folder =
      Folder(
        id = id,
        uid = "uid-$id",
        name = name,
        normalizedName = name.lowercase(),
        isDeleted = isDeleted,
        deletedAt = if (isDeleted) 100L else null,
        createdAt = 10L,
        updatedAt = 20L,
      ).also { folders[id] = it }

    override fun observeActiveFolders(): Flow<List<Folder>> = emptyFlow()

    override fun observeDeletedFolders(): Flow<List<Folder>> = emptyFlow()

    override suspend fun getFolderById(folderId: Long): Folder? = folders[folderId]

    override suspend fun getFoldersByIds(ids: Set<Long>): Map<Long, FolderAvailability> =
      ids.associateWith { id ->
        val f = folders[id]
        when {
          f == null -> FolderAvailability.NotFound(id)
          f.isDeleted -> FolderAvailability.InTrash(f)
          else -> FolderAvailability.Active(f)
        }
      }

    override suspend fun createFolder(name: String): Folder = error("unused")

    override suspend fun renameFolder(folderId: Long, newName: String): Folder = error("unused")

    override suspend fun deleteFolder(folderId: Long, mode: FolderDeleteMode) = error("unused")

    override suspend fun restoreFolder(folderId: Long): RestoreFolderResult = error("unused")

    override suspend fun permanentlyDeleteFolder(folderId: Long, deleteLinkedNotes: Boolean) = error("unused")
  }

  private class TestNoteRepository(
    private val clock: ClockProvider,
    private val draftRepo: TestDraftRepository,
  ) : NoteRepository {
    val notes = linkedMapOf<Long, Note>()
    private val nextId = AtomicLong(100L)
    val createCalls = AtomicInteger(0)
    val updateCalls = AtomicInteger(0)
    var onGetNoteHook: (suspend (Long) -> Unit)? = null
    var onBeforeConditionalPinHook: (suspend (Long) -> Unit)? = null

    fun seedNote(
      id: Long,
      title: String,
      folderId: Long? = null,
      revision: Long = 1L,
      isPinned: Boolean = false,
      text: String = "",
    ): Note =
      Note(
        id = id,
        uid = "note-$id",
        folderId = folderId,
        title = title,
        contentState =
          NoteContentState.Valid(
            if (text.isEmpty()) {
              NoteContentDocument.empty()
            } else {
              NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p-$id", text = text)))
            }
          ),
        plainTextPreview = text,
        paperStyle = NotePaperStyle.LINHAS,
        revision = revision,
        isPinned = isPinned,
        pinnedAt = if (isPinned) 10L else null,
        isDeleted = false,
        deletedAt = null,
        deletedWithFolder = false,
        createdAt = 10L,
        updatedAt = 1_000L + revision,
      ).also { notes[id] = it }

    override fun observeAllActiveNotes(): Flow<List<Note>> = emptyFlow()

    override fun observePinnedActiveNotes(): Flow<List<Note>> = emptyFlow()

    override fun observeActiveNotesByFolder(folderId: Long): Flow<List<Note>> = emptyFlow()

    override fun observeDeletedNotes(): Flow<List<Note>> = emptyFlow()

    override fun observeNoteById(noteId: Long): Flow<Note?> = emptyFlow()

    override suspend fun getNoteById(noteId: Long): Note? {
      onGetNoteHook?.invoke(noteId)
      return notes[noteId]
    }

    override suspend fun createNote(
      title: String,
      content: NoteContentDocument,
      folderId: Long?,
      isPinned: Boolean,
      paperStyle: NotePaperStyle?,
      clearDraftId: String?,
    ): Note {
      createCalls.incrementAndGet()
      val id = nextId.getAndIncrement()
      val created =
        Note(
          id = id,
          uid = "note-$id",
          folderId = folderId,
          title = title,
          contentState = NoteContentState.Valid(NoteContentPolicy.normalizeDocument(content)),
          plainTextPreview = content.blocks.joinToString("\n") { it.text },
          paperStyle = paperStyle ?: NotePaperStyle.LINHAS,
          revision = 1L,
          isPinned = isPinned,
          pinnedAt = if (isPinned) clock.nowMillis() else null,
          isDeleted = false,
          deletedAt = null,
          deletedWithFolder = false,
          createdAt = clock.nowMillis(),
          updatedAt = clock.nowMillis(),
        )
      notes[id] = created
      if (clearDraftId != null) {
        draftRepo.drafts.remove(clearDraftId)
      }
      return created
    }

    override suspend fun updateNote(
      noteId: Long,
      title: String,
      content: NoteContentDocument,
      folderId: Long?,
      paperStyle: NotePaperStyle?,
      expectedBaseRevision: Long?,
      clearDraftId: String?,
    ): Note {
      updateCalls.incrementAndGet()
      val existing = notes[noteId] ?: throw NoSuchElementException("Note $noteId not found")
      if (expectedBaseRevision != null && existing.revision != expectedBaseRevision) {
        throw NoteConflictException(noteId, expectedBaseRevision, existing.revision)
      }
      val updated =
        existing.copy(
          title = title,
          contentState = NoteContentState.Valid(NoteContentPolicy.normalizeDocument(content)),
          plainTextPreview = content.blocks.joinToString("\n") { it.text },
          folderId = folderId,
          paperStyle = paperStyle ?: existing.paperStyle,
          revision = existing.revision + 1L,
          updatedAt = clock.nowMillis(),
        )
      notes[noteId] = updated
      if (clearDraftId != null) {
        draftRepo.drafts.remove(clearDraftId)
      }
      return updated
    }

    override suspend fun updateNotePaperStyle(noteId: Long, paperStyle: NotePaperStyle): Note = error("unused")

    override suspend fun setNotePinned(noteId: Long, pinned: Boolean) = error("unused")

    override suspend fun setNotePinnedIfRevision(
      noteId: Long,
      pinned: Boolean,
      expectedBaseRevision: Long,
    ): PinNoteResult {
      onBeforeConditionalPinHook?.invoke(noteId)
      val existing = notes[noteId] ?: throw NoSuchElementException("Note $noteId not found")
      if (existing.revision != expectedBaseRevision) {
        return PinNoteResult.Conflict(expectedBaseRevision, existing)
      }
      if (existing.isPinned == pinned) {
        return PinNoteResult.Unchanged(existing)
      }
      val updated =
        existing.copy(
          isPinned = pinned,
          pinnedAt = if (pinned) clock.nowMillis() else null,
          revision = existing.revision + 1L,
          updatedAt = clock.nowMillis(),
        )
      notes[noteId] = updated
      return PinNoteResult.Updated(updated)
    }

    override suspend fun moveNoteToFolder(noteId: Long, folderId: Long?) = error("unused")

    override suspend fun softDeleteNote(noteId: Long) = error("unused")

    override suspend fun restoreNote(noteId: Long): Note = error("unused")

    override suspend fun permanentlyDeleteNote(noteId: Long) = error("unused")
  }

  private class TestDraftRepository(
    private val clock: ClockProvider,
  ) : NoteRecoveryDraftRepository {
    val drafts = linkedMapOf<String, NoteRecoveryDraft>()
    val saveCount = AtomicInteger(0)
    val deleteCount = AtomicInteger(0)
    val replaceCount = AtomicInteger(0)
    var onSaveHook: (suspend (NoteRecoveryDraftInput) -> Unit)? = null
    var onGetCheckpointHook: (suspend (String) -> Unit)? = null
    var onReplaceHook: (suspend (String, NoteRecoveryDraftInput) -> ReplaceCheckpointWithNewNoteResult)? = null

    override fun observeAllCheckpoints(): Flow<List<NoteRecoveryDraft>> = emptyFlow()

    override suspend fun getAllCheckpoints(): List<NoteRecoveryDraft> = drafts.values.toList()

    override suspend fun getCheckpointById(draftId: String): NoteRecoveryDraft? {
      onGetCheckpointHook?.invoke(draftId)
      return drafts[draftId]
    }

    override suspend fun getCheckpointBySessionId(sessionId: String): NoteRecoveryDraft? =
      drafts.values.firstOrNull { it.sessionId == sessionId }

    override suspend fun getCheckpointsForNote(noteId: Long): List<NoteRecoveryDraft> =
      drafts.values.filter { it.targetNoteId == noteId }

    override suspend fun saveCheckpoint(input: NoteRecoveryDraftInput): SaveCheckpointResult {
      saveCount.incrementAndGet()
      onSaveHook?.invoke(input)
      val existing = drafts[input.draftId]
      if (existing != null && input.checkpointRevision <= existing.checkpointRevision) {
        return SaveCheckpointResult.IgnoredStaleRevision(
          draftId = input.draftId,
          persistedCheckpointRevision = existing.checkpointRevision,
          attemptedCheckpointRevision = input.checkpointRevision,
        )
      }
      val saved = input.toDraft(clock.nowMillis())
      drafts[input.draftId] = saved
      return SaveCheckpointResult.Saved(saved)
    }

    override suspend fun replaceCheckpointWithNewNote(
      oldDraftId: String,
      expectedOldSessionId: String,
      expectedOldTargetNoteId: Long,
      newInput: NoteRecoveryDraftInput,
    ): ReplaceCheckpointWithNewNoteResult {
      replaceCount.incrementAndGet()
      onReplaceHook?.let { return it.invoke(oldDraftId, newInput) }
      return defaultReplace(oldDraftId, newInput)
    }

    fun defaultReplace(
      oldDraftId: String,
      newInput: NoteRecoveryDraftInput,
    ): ReplaceCheckpointWithNewNoteResult {
      val existingNew = drafts[newInput.draftId]
      val existingOld = drafts[oldDraftId]
      if (existingOld == null && existingNew != null) {
        return ReplaceCheckpointWithNewNoteResult.AlreadyReplaced(existingNew)
      }
      drafts.remove(oldDraftId)
      val inserted = newInput.toDraft(clock.nowMillis())
      drafts[inserted.draftId] = inserted
      return ReplaceCheckpointWithNewNoteResult.Replaced(inserted)
    }

    override suspend fun deleteCheckpoint(draftId: String) {
      deleteCount.incrementAndGet()
      drafts.remove(draftId)
    }

    override suspend fun deleteCheckpointsForNote(noteId: Long) {
      drafts.entries.removeIf { it.value.targetNoteId == noteId }
    }

    private fun NoteRecoveryDraftInput.toDraft(at: Long): NoteRecoveryDraft =
      NoteRecoveryDraft(
        draftId = draftId,
        targetNoteId = targetNoteId,
        sessionId = sessionId,
        folderId = folderId,
        initialFolderId = initialFolderId,
        fallbackSourceFolderId = fallbackSourceFolderId,
        isPinned = isPinned,
        title = title,
        contentState = NoteContentState.Valid(NoteContentPolicy.normalizeDocument(content)),
        paperStyle = paperStyle,
        initialPaperStyle = initialPaperStyle,
        baseRevision = baseRevision,
        checkpointRevision = checkpointRevision,
        baseNoteUpdatedAt = baseNoteUpdatedAt,
        checkpointAt = at,
      )
  }
}
