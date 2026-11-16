package com.example.ui.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.coroutines.DispatcherProvider
import com.example.core.di.AppContainer
import com.example.core.time.ClockProvider
import com.example.data.local.LocalStorageSpec
import com.example.data.local.database.AnoteVoceDatabase
import com.example.data.repository.FolderRepositoryImpl
import com.example.data.repository.NoteRecoveryDraftRepositoryImpl
import com.example.data.repository.NoteRepositoryImpl
import com.example.data.repository.PreferencesRepositoryImpl
import com.example.domain.editor.ConflictResolutionOption
import com.example.domain.editor.NoteEditorBaseline
import com.example.domain.editor.NoteEditorOpenRequest
import com.example.domain.editor.RecoveryConflict
import com.example.domain.editor.RecoveryTargetExpectation
import com.example.domain.editor.SessionBlockReason
import com.example.domain.editor.SessionWarning
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NoteContentState
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.NoteRecoveryDraft
import com.example.domain.model.NoteRecoveryDraftInput
import com.example.domain.model.NoteRecoveryDraftKeys
import com.example.domain.model.ParagraphBlock
import com.example.domain.model.ReplaceCheckpointWithNewNoteResult
import com.example.domain.model.SaveCheckpointResult
import com.example.domain.model.TextFormatStyle
import com.example.domain.repository.FolderDeleteMode
import com.example.domain.repository.NoteRecoveryDraftRepository
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NoteEditorSessionCoordinatorRoomIntegrationTest {

  private lateinit var context: Context
  private lateinit var database: AnoteVoceDatabase
  private lateinit var folderRepository: FolderRepositoryImpl
  private lateinit var noteRepository: NoteRepositoryImpl
  private lateinit var realDraftRepository: NoteRecoveryDraftRepositoryImpl
  private lateinit var preferencesRepository: PreferencesRepositoryImpl

  private val fakeTime = AtomicLong(1_700_400_000_000L)
  private val testClock =
    object : ClockProvider {
      override fun nowMillis(): Long = fakeTime.incrementAndGet()
    }

  private val ioDispatchers =
    object : DispatcherProvider {
      override val main: CoroutineDispatcher = Dispatchers.Default
      override val io: CoroutineDispatcher = Dispatchers.IO
      override val default: CoroutineDispatcher = Dispatchers.Default
    }

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    context.deleteDatabase(LocalStorageSpec.DATABASE_NAME)
    database =
      Room.inMemoryDatabaseBuilder(context, AnoteVoceDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    folderRepository =
      FolderRepositoryImpl(
        database = database,
        folderDao = database.folderDao(),
        noteDao = database.noteDao(),
        dispatchers = ioDispatchers,
        clock = testClock,
      )
    noteRepository =
      NoteRepositoryImpl(
        database = database,
        noteDao = database.noteDao(),
        folderDao = database.folderDao(),
        dispatchers = ioDispatchers,
        clock = testClock,
        appPreferencesDao = database.appPreferencesDao(),
        noteRecoveryDraftDao = database.noteRecoveryDraftDao(),
      )
    realDraftRepository =
      NoteRecoveryDraftRepositoryImpl(
        database = database,
        draftDao = database.noteRecoveryDraftDao(),
        dispatchers = ioDispatchers,
        clock = testClock,
      )
    preferencesRepository =
      PreferencesRepositoryImpl(
        database = database,
        appPreferencesDao = database.appPreferencesDao(),
        dispatchers = ioDispatchers,
        clock = testClock,
      )
  }

  @After
  fun tearDown() {
    database.close()
    context.deleteDatabase(LocalStorageSpec.DATABASE_NAME)
  }

  private suspend fun awaitCmd(
    coordinator: NoteEditorSessionCoordinator,
    build: (CompletableDeferred<SessionCommandOutcome>) -> SessionCommand,
  ): SessionCommandOutcome {
    val ack = CompletableDeferred<SessionCommandOutcome>()
    val sub = coordinator.submit(build(ack))
    assertTrue("Comando deveria ser aceito: $sub", sub is SubmitCommandResult.Accepted)
    return withTimeout(10_000) { ack.await() }
  }

  @Test
  fun createNewWithPreferencePaper_autosavesConsolidatesAndUpdatesInRealSqlite() = runBlocking {
    preferencesRepository.updateDefaultNotePaper(NotePaperStyle.PONTILHADO)
    val folder = folderRepository.createFolder("Trabalho")
    val registry = ActiveSessionRegistry()
    val tracker = ReplacementHandoffTracker(testClock)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val loader =
      SessionOpenLoader(
        noteRepository = noteRepository,
        folderRepository = folderRepository,
        draftRepository = realDraftRepository,
        preferencesRepository = preferencesRepository,
        activeSessionRegistry = registry,
        handoffTracker = tracker,
      )
    val reconciler =
      PendingReplacementReconciler(
        noteRepository = noteRepository,
        draftRepository = realDraftRepository,
        activeSessionRegistry = registry,
        handoffTracker = tracker,
      )

    val coord =
      NoteEditorSessionCoordinator(
        openRequest =
          NoteEditorOpenRequest.CreateNew(
            initialFolderId = folder.id,
            defaultPaperStyle = null,
            initialPinned = false,
            sessionId = "int-new-1",
          ),
        sessionOpenLoader = loader,
        replacementReconciler = reconciler,
        handoffTracker = tracker,
        activeSessionRegistry = registry,
        draftRepository = realDraftRepository,
        noteRepository = noteRepository,
        folderRepository = folderRepository,
        dispatchers = ioDispatchers,
        clock = testClock,
        parentScope = scope,
        autosaveDebounceMillis = 60_000L,
      )

    try {
      coord.start()
      val init = withTimeout(10_000) { coord.awaitInitialized() }
      assertEquals(NotePaperStyle.PONTILHADO, init.paperStyle)
      assertEquals(folder.id, init.effectiveFolderId)
      assertEquals("Trabalho", init.effectiveFolderName)

      coord.submit(SessionCommand.EditTitle("Ata SQLite"))
      coord.submit(SessionCommand.InsertText("Decisões consolidadas"))
      assertEquals(SessionCommandOutcome.Completed, awaitCmd(coord) { SessionCommand.FlushAutosave(it) })

      val draftId = init.sessionIdentity!!.draftId
      assertNotNull(database.noteRecoveryDraftDao().getByDraftId(draftId))

      assertEquals(SessionCommandOutcome.Completed, awaitCmd(coord) { SessionCommand.ConsolidateNote(ackDeferred = it) })
      val postCreateState = coord.uiState.value
      val noteId = postCreateState.sessionIdentity!!.targetNoteId!!
      assertNull(database.noteRecoveryDraftDao().getByDraftId(draftId))
      val storedNote1 = noteRepository.getNoteById(noteId)
      assertNotNull(storedNote1)
      assertEquals("Ata SQLite", storedNote1!!.title)
      assertEquals(NotePaperStyle.PONTILHADO, storedNote1.paperStyle)
      assertEquals(folder.id, storedNote1.folderId)

      // Nova edição e consolidação na mesma sessão atualiza a mesma linha no Room.
      coord.submit(SessionCommand.EditTitle("Ata SQLite v2"))
      assertEquals(SessionCommandOutcome.Completed, awaitCmd(coord) { SessionCommand.ConsolidateNote(ackDeferred = it) })
      val allActive = noteRepository.observeAllActiveNotes().first()
      assertEquals(1, allActive.size)
      assertEquals("Ata SQLite v2", allActive.single().title)
      assertEquals(2L, allActive.single().revision)
    } finally {
      coord.closeAndAbort()
      scope.cancel()
    }
    assertEquals(0, registry.snapshot().reservedSessionIds.size)
  }

  @Test
  fun existingNoteConflictAndSaveDraftAsNewNote_realSqliteStateBPostCommitFaultReconcilesIdempotently() =
    runBlocking {
      val folder = folderRepository.createFolder("Projetos")
      val original =
        noteRepository.createNote(
          title = "Nota Base",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "b0", text = "Base"))),
          folderId = folder.id,
          isPinned = true,
          paperStyle = NotePaperStyle.SEM_LINHAS,
        )

      val faultRepo = FaultInjectingDraftRepository(realDraftRepository)
      val registry = ActiveSessionRegistry()
      val tracker = ReplacementHandoffTracker(testClock)
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

      val loader =
        SessionOpenLoader(
          noteRepository = noteRepository,
          folderRepository = folderRepository,
          draftRepository = faultRepo,
          preferencesRepository = preferencesRepository,
          activeSessionRegistry = registry,
          handoffTracker = tracker,
        )
      val reconciler =
        PendingReplacementReconciler(
          noteRepository = noteRepository,
          draftRepository = faultRepo,
          activeSessionRegistry = registry,
          handoffTracker = tracker,
        )

      val coord =
        NoteEditorSessionCoordinator(
          openRequest = NoteEditorOpenRequest.OpenExisting(noteId = original.id, sessionId = "int-state-b"),
          sessionOpenLoader = loader,
          replacementReconciler = reconciler,
          handoffTracker = tracker,
          activeSessionRegistry = registry,
          draftRepository = faultRepo,
          noteRepository = noteRepository,
          folderRepository = folderRepository,
          dispatchers = ioDispatchers,
          clock = testClock,
          parentScope = scope,
          autosaveDebounceMillis = 60_000L,
          sessionIdGenerator = { "int-new-from-b" },
        )

      try {
        coord.start()
        val init = withTimeout(10_000) { coord.awaitInitialized() }
        val oldDraftId = init.sessionIdentity!!.draftId

        coord.submit(SessionCommand.EditTitle("Minha cópia divergente"))
        coord.submit(SessionCommand.InsertText("Texto preservado em nova nota"))
        assertEquals(SessionCommandOutcome.Completed, awaitCmd(coord) { SessionCommand.FlushAutosave(it) })

        // Alteração externa concorrente no SQLite real eleva revisão da nota original.
        noteRepository.updateNote(
          noteId = original.id,
          title = "Alterada externamente",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "b0", text = "Externa"))),
          folderId = folder.id,
          expectedBaseRevision = original.revision,
        )

        val conflictRes = awaitCmd(coord) { SessionCommand.ConsolidateNote(ackDeferred = it) }
        assertTrue(conflictRes is SessionCommandOutcome.Failed)
        assertTrue(coord.uiState.value.activeConflict is RecoveryConflict.NoteDiverged)

        // Injeta IOException DEPOIS do commit real da transação replaceCheckpointWithNewNote no SQLite.
        faultRepo.throwAfterNextReplaceCommit = true
        val resolutionRes =
          awaitCmd(coord) {
            SessionCommand.ResolveConflict(ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE, it)
          }
        assertEquals(SessionCommandOutcome.Completed, resolutionRes)

        val promotedNewDraftId = NoteRecoveryDraftKeys.forNewNote("int-new-from-b")
        assertEquals(promotedNewDraftId, coord.uiState.value.sessionIdentity!!.draftId)
        assertNull(coord.uiState.value.activeConflict)
        assertTrue(coord.uiState.value.baseline is NoteEditorBaseline.NewNote)
        assertNull(database.noteRecoveryDraftDao().getByDraftId(oldDraftId))
        val persistedNewEntity = database.noteRecoveryDraftDao().getByDraftId(promotedNewDraftId)
        assertNotNull(persistedNewEntity)
        assertEquals("Minha cópia divergente", persistedNewEntity!!.title)
        assertEquals(folder.id, persistedNewEntity.folderId)
        assertTrue(persistedNewEntity.isPinned)
        assertEquals(NotePaperStyle.SEM_LINHAS.name, persistedNewEntity.paperStyle)

        val persistedNewDomain = realDraftRepository.getCheckpointById(promotedNewDraftId)
        assertNotNull(persistedNewDomain)
        assertEquals(NotePaperStyle.SEM_LINHAS, persistedNewDomain!!.paperStyle)

        // Confirma consolidação subsequente criando a nova nota no SQLite sem tocar a original.
        assertEquals(SessionCommandOutcome.Completed, awaitCmd(coord) { SessionCommand.ConsolidateNote(ackDeferred = it) })
        val allNotes = noteRepository.observeAllActiveNotes().first()
        assertEquals(2, allNotes.size)
      } finally {
        coord.closeAndAbort()
        scope.cancel()
      }
      assertEquals(0, registry.snapshot().reservedSessionIds.size)
    }

  @Test
  fun condition5_1_processDeathRecoveryAcrossTwoAppContainerInstances_discoversNewDraftWithoutInMemoryTracker() =
    runBlocking {
      // Simula Container 1 operando sobre o banco SQLite persistente em arquivo (LocalStorageSpec.DATABASE_NAME).
      val appContainer1 = AppContainer(context)
      val folder1 = appContainer1.folderRepository.createFolder("Diário Persistente")
      val originalNote =
        appContainer1.noteRepository.createNote(
          title = "Nota antes da morte do processo",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "d1", text = "Base"))),
          folderId = folder1.id,
          isPinned = true,
          paperStyle = NotePaperStyle.FOREST_MIST,
        )

      val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val faultDraftRepo = FaultInjectingDraftRepository(appContainer1.noteRecoveryDraftRepository)
      val loader1 =
        SessionOpenLoader(
          noteRepository = appContainer1.noteRepository,
          folderRepository = appContainer1.folderRepository,
          draftRepository = faultDraftRepo,
          preferencesRepository = appContainer1.preferencesRepository,
          activeSessionRegistry = appContainer1.activeSessionRegistry,
          handoffTracker = appContainer1.replacementHandoffTracker,
        )
      val reconciler1 =
        PendingReplacementReconciler(
          noteRepository = appContainer1.noteRepository,
          draftRepository = faultDraftRepo,
          activeSessionRegistry = appContainer1.activeSessionRegistry,
          handoffTracker = appContainer1.replacementHandoffTracker,
        )
      val coord1 =
        NoteEditorSessionCoordinator(
          openRequest = NoteEditorOpenRequest.OpenExisting(noteId = originalNote.id, sessionId = "proc-death-old"),
          sessionOpenLoader = loader1,
          replacementReconciler = reconciler1,
          handoffTracker = appContainer1.replacementHandoffTracker,
          activeSessionRegistry = appContainer1.activeSessionRegistry,
          draftRepository = faultDraftRepo,
          noteRepository = appContainer1.noteRepository,
          folderRepository = appContainer1.folderRepository,
          dispatchers = ioDispatchers,
          clock = appContainer1.clock,
          parentScope = scope1,
          autosaveDebounceMillis = 60_000L,
          sessionIdGenerator = { "proc-death-new" },
        )

      val expectedNewDraftId = NoteRecoveryDraftKeys.forNewNote("proc-death-new")
      val oldDraftId = NoteRecoveryDraftKeys.forExistingNote(originalNote.id, "proc-death-old")

      try {
        coord1.start()
        withTimeout(10_000) { coord1.awaitInitialized() }

        coord1.submit(SessionCommand.EditTitle("Conteúdo Sobrevivente"))
        coord1.submit(SessionCommand.InsertText("Texto crítico que sobrevive à morte do processo"))
        coord1.submit(SessionCommand.ToggleFormat(TextFormatStyle.BOLD))
        assertEquals(SessionCommandOutcome.Completed, awaitCmd(coord1) { SessionCommand.FlushAutosave(it) })

        // Eleva revisão no banco para provocar conflito real.
        appContainer1.noteRepository.updateNote(
          noteId = originalNote.id,
          title = "Nota alterada externamente",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "d1", text = "Outro"))),
          folderId = folder1.id,
          expectedBaseRevision = originalNote.revision,
        )
        assertTrue(awaitCmd(coord1) { SessionCommand.ConsolidateNote(ackDeferred = it) } is SessionCommandOutcome.Failed)

        // Commit real ocorre em SQLite (newDraft criado, oldDraft excluído), mas falha pós-commit +
        // falha de leitura na reconciliação deixam transferência sem commit final antes da morte do processo.
        faultDraftRepo.throwAfterNextReplaceCommit = true
        faultDraftRepo.failReads = true
        val resF =
          awaitCmd(coord1) {
            SessionCommand.ResolveConflict(ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE, it)
          }
        assertTrue(resF is SessionCommandOutcome.Failed)
        assertNotNull(appContainer1.replacementHandoffTracker.findActiveCandidate(oldDraftId))

        coord1.closeAndAbort()
        coord1.awaitTerminated()
      } finally {
        scope1.cancel()
        appContainer1.database.close()
      }

      // Simula novo processo: novo AppContainer sem estado em memória (tracker e registry completamente vazios).
      val appContainer2 = AppContainer(context)
      assertEquals(0, appContainer2.replacementHandoffTracker.snapshot().activeCount)
      assertEquals(0, appContainer2.activeSessionRegistry.snapshot().reservedSessionIds.size)

      val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        // Descoberta global de checkpoints no Room encontra apenas o novo rascunho efetivado.
        val observedCheckpoints = appContainer2.noteRecoveryDraftRepository.observeAllCheckpoints().first()
        assertEquals(1, observedCheckpoints.size)
        val allRecoveredCheckpoints = appContainer2.noteRecoveryDraftRepository.getAllCheckpoints()
        assertEquals(1, allRecoveredCheckpoints.size)
        val discovered = allRecoveredCheckpoints.single()
        assertEquals(expectedNewDraftId, discovered.draftId)
        assertNull(appContainer2.noteRecoveryDraftRepository.getCheckpointById(oldDraftId))

        val coord2 =
          appContainer2.createNoteEditorSessionCoordinator(
            openRequest =
              NoteEditorOpenRequest.RecoverDraft(
                draftId = discovered.draftId,
                expectedTarget = RecoveryTargetExpectation.NewNote,
              ),
            parentScope = scope2,
            autosaveDebounceMillis = 60_000L,
          )
        try {
          coord2.start()
          val restoredState = withTimeout(10_000) { coord2.awaitInitialized() }

          assertEquals(NoteEditorSessionPhase.EDITING, restoredState.phase)
          assertEquals(expectedNewDraftId, restoredState.sessionIdentity?.draftId)
          assertEquals("Conteúdo Sobrevivente", restoredState.title)
          assertEquals(folder1.id, restoredState.effectiveFolderId)
          assertEquals("Diário Persistente", restoredState.effectiveFolderName)
          assertEquals(NotePaperStyle.FOREST_MIST, restoredState.paperStyle)
          assertTrue(restoredState.isPinned)
          assertTrue(
            restoredState.editorState.document.blocks.any {
              it.text.contains("Texto crítico que sobrevive à morte do processo")
            }
          )

          // Garante ausência de duplicação ou recriação indevida após abertura de RecoverDraft(newDraftId).
          val checkpointsAfterOpen = appContainer2.noteRecoveryDraftRepository.getAllCheckpoints()
          assertEquals(1, checkpointsAfterOpen.size)
          assertEquals(expectedNewDraftId, checkpointsAfterOpen.single().draftId)
          assertEquals(discovered.checkpointRevision, checkpointsAfterOpen.single().checkpointRevision)
          assertNull(appContainer2.noteRecoveryDraftRepository.getCheckpointById(oldDraftId))

          // Consolidação no novo processo cria nota completa no SQLite sem perda de dados.
          assertEquals(SessionCommandOutcome.Completed, awaitCmd(coord2) { SessionCommand.ConsolidateNote(ackDeferred = it) })
          val activeNotes = appContainer2.noteRepository.observeAllActiveNotes().first()
          assertEquals(2, activeNotes.size)
          assertTrue(activeNotes.any { it.title == "Conteúdo Sobrevivente" && it.paperStyle == NotePaperStyle.FOREST_MIST })
          assertTrue(appContainer2.noteRecoveryDraftRepository.getAllCheckpoints().isEmpty())
        } finally {
          coord2.closeAndAbort()
          coord2.awaitTerminated()
        }
      } finally {
        scope2.cancel()
        appContainer2.database.close()
      }
    }

  @Test
  fun saveDraftAsNewNote_realSqliteStateD_doesNotRecreatePhantomNewDraftAndClearsTracker() =
    runBlocking {
      val original = noteRepository.createNote(title = "Nota D")
      val registry = ActiveSessionRegistry()
      val tracker = ReplacementHandoffTracker(testClock)
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

      val deletingRepo =
        object : NoteRecoveryDraftRepository by realDraftRepository {
          override suspend fun replaceCheckpointWithNewNote(
            oldDraftId: String,
            expectedOldSessionId: String,
            expectedOldTargetNoteId: Long,
            newInput: NoteRecoveryDraftInput,
          ): ReplaceCheckpointWithNewNoteResult {
            realDraftRepository.deleteCheckpoint(oldDraftId)
            throw IOException("Estado D simulado após exclusão de oldDraft sem inserção")
          }
        }

      val loader =
        SessionOpenLoader(
          noteRepository = noteRepository,
          folderRepository = folderRepository,
          draftRepository = deletingRepo,
          preferencesRepository = preferencesRepository,
          activeSessionRegistry = registry,
          handoffTracker = tracker,
        )
      val reconciler =
        PendingReplacementReconciler(
          noteRepository = noteRepository,
          draftRepository = deletingRepo,
          activeSessionRegistry = registry,
          handoffTracker = tracker,
        )

      val coord =
        NoteEditorSessionCoordinator(
          openRequest = NoteEditorOpenRequest.OpenExisting(noteId = original.id, sessionId = "int-state-d"),
          sessionOpenLoader = loader,
          replacementReconciler = reconciler,
          handoffTracker = tracker,
          activeSessionRegistry = registry,
          draftRepository = deletingRepo,
          noteRepository = noteRepository,
          folderRepository = folderRepository,
          dispatchers = ioDispatchers,
          clock = testClock,
          parentScope = scope,
          autosaveDebounceMillis = 60_000L,
          sessionIdGenerator = { "int-new-d" },
        )

      try {
        coord.start()
        withTimeout(10_000) { coord.awaitInitialized() }
        coord.submit(SessionCommand.EditTitle("Conteúdo D"))
        assertEquals(SessionCommandOutcome.Completed, awaitCmd(coord) { SessionCommand.FlushAutosave(it) })

        noteRepository.updateNote(
          noteId = original.id,
          title = "Conflito D",
          content = NoteContentDocument.empty(),
          folderId = null,
          expectedBaseRevision = original.revision,
        )
        assertTrue(awaitCmd(coord) { SessionCommand.ConsolidateNote(ackDeferred = it) } is SessionCommandOutcome.Failed)

        val res =
          awaitCmd(coord) {
            SessionCommand.ResolveConflict(ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE, it)
          }
        assertTrue(res is SessionCommandOutcome.Failed)
        // Estado D: nenhum checkpoint fantasma pode ter sido recriado no SQLite real, e tracker foi limpo.
        assertTrue(database.noteRecoveryDraftDao().getAllCheckpoints().isEmpty())
        assertEquals(0, tracker.snapshot().activeCount)
        assertTrue((coord.uiState.value.autosaveStatus as AutosaveStatus.Error).isTerminal)
      } finally {
        coord.closeAndAbort()
        scope.cancel()
      }
      assertEquals(0, registry.snapshot().reservedSessionIds.size)
    }

  @Test
  fun folderAvailabilityObserved_realSqlitePersistsFallbackSkipsRedundantWriteAndPreservesBaselineWarning() =
    runBlocking {
      val folderOrig = folderRepository.createFolder("Origem")
      val folderDest = folderRepository.createFolder("Destino")
      val note =
        noteRepository.createNote(
          title = "Nota em Pasta",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p0", text = "Corpo"))),
          folderId = folderOrig.id,
        )

      val registry = ActiveSessionRegistry()
      val tracker = ReplacementHandoffTracker(testClock)
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

      val loader =
        SessionOpenLoader(
          noteRepository = noteRepository,
          folderRepository = folderRepository,
          draftRepository = realDraftRepository,
          preferencesRepository = preferencesRepository,
          activeSessionRegistry = registry,
          handoffTracker = tracker,
        )
      val reconciler =
        PendingReplacementReconciler(
          noteRepository = noteRepository,
          draftRepository = realDraftRepository,
          activeSessionRegistry = registry,
          handoffTracker = tracker,
        )

      val coord =
        NoteEditorSessionCoordinator(
          openRequest = NoteEditorOpenRequest.OpenExisting(noteId = note.id, sessionId = "int-folder-obs"),
          sessionOpenLoader = loader,
          replacementReconciler = reconciler,
          handoffTracker = tracker,
          activeSessionRegistry = registry,
          draftRepository = realDraftRepository,
          noteRepository = noteRepository,
          folderRepository = folderRepository,
          dispatchers = ioDispatchers,
          clock = testClock,
          parentScope = scope,
          autosaveDebounceMillis = 60_000L,
        )

      try {
        coord.start()
        val init = withTimeout(10_000) { coord.awaitInitialized() }
        val draftId = init.sessionIdentity!!.draftId
        assertEquals(AutosaveStatus.Idle, init.autosaveStatus)
        assertNull(database.noteRecoveryDraftDao().getByDraftId(draftId))

        // Move a pasta original para a lixeira no SQLite real e injeta observação na sessão limpa.
        folderRepository.deleteFolder(folderOrig.id, FolderDeleteMode.FOLDER_ONLY)
        val availabilityAfterTrash = folderRepository.getFoldersByIds(setOf(folderOrig.id))
        assertTrue(coord.submit(SessionCommand.FolderAvailabilityObserved(availabilityAfterTrash)) is SubmitCommandResult.Accepted)
        assertEquals(SessionCommandOutcome.Completed, awaitCmd(coord) { SessionCommand.FlushAutosave(it) })

        val stateAfterFallback = coord.uiState.value
        assertNull(stateAfterFallback.effectiveFolderId)
        assertEquals(folderOrig.id, stateAfterFallback.fallbackSourceFolderId)
        assertTrue(stateAfterFallback.hasPendingFolderFallback)
        assertTrue(stateAfterFallback.warnings.any { it is SessionWarning.FallbackFolderApplied })
        assertTrue(
          stateAfterFallback.warnings.any {
            it is SessionWarning.OriginalBaselineFolderUnavailable && it.baselineFolderId == folderOrig.id
          }
        )

        val persistedFallback = database.noteRecoveryDraftDao().getByDraftId(draftId)
        assertNotNull(persistedFallback)
        assertNull(persistedFallback!!.folderId)
        assertEquals(folderOrig.id, persistedFallback.fallbackSourceFolderId)
        val genAfterFallback = stateAfterFallback.stateGeneration
        val revAfterFallback = persistedFallback.checkpointRevision

        // Observação idêntica repetida não deve incrementar stateGeneration nem gerar escrita redundante no SQLite.
        assertTrue(coord.submit(SessionCommand.FolderAvailabilityObserved(availabilityAfterTrash)) is SubmitCommandResult.Accepted)
        assertEquals(SessionCommandOutcome.Completed, awaitCmd(coord) { SessionCommand.FlushAutosave(it) })
        assertEquals(genAfterFallback, coord.uiState.value.stateGeneration)
        assertEquals(revAfterFallback, database.noteRecoveryDraftDao().getByDraftId(draftId)!!.checkpointRevision)

        // Alterar destino para folderDest + observação parcial sem folderOrig preserva OriginalBaselineFolderUnavailable.
        assertTrue(coord.submit(SessionCommand.ChangeFolder(folderDest.id)) is SubmitCommandResult.Accepted)
        assertEquals(SessionCommandOutcome.Completed, awaitCmd(coord) { SessionCommand.FlushAutosave(it) })
        val partialAvailability = folderRepository.getFoldersByIds(setOf(folderDest.id))
        assertTrue(coord.submit(SessionCommand.FolderAvailabilityObserved(partialAvailability)) is SubmitCommandResult.Accepted)
        assertEquals(SessionCommandOutcome.Completed, awaitCmd(coord) { SessionCommand.FlushAutosave(it) })

        val stateAfterMove = coord.uiState.value
        assertEquals(folderDest.id, stateAfterMove.effectiveFolderId)
        assertNull(stateAfterMove.fallbackSourceFolderId)
        assertFalse(stateAfterMove.hasPendingFolderFallback)
        assertEquals("Destino", stateAfterMove.effectiveFolderName)
        assertTrue(
          stateAfterMove.warnings.any {
            it is SessionWarning.OriginalBaselineFolderUnavailable && it.baselineFolderId == folderOrig.id
          }
        )
      } finally {
        coord.closeAndAbort()
        coord.awaitTerminated()
        scope.cancel()
      }
      assertEquals(0, registry.snapshot().reservedSessionIds.size)
    }

  @Test
  fun sharedAppContainer_concurrentDuplicateOpenBlocksWithSessionAlreadyActiveAndReopensAfterClose() =
    runBlocking {
      val sharedContainer = AppContainer(context)
      val sharedRegistry = sharedContainer.activeSessionRegistry
      val note =
        sharedContainer.noteRepository.createNote(
          title = "Nota Concorrente",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "c1", text = "Texto inicial"))),
        )
      val sharedSessionId = "shared-appcontainer-sess"
      val expectedDraftId = NoteRecoveryDraftKeys.forExistingNote(note.id, sharedSessionId)

      val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val scope3 = CoroutineScope(SupervisorJob() + Dispatchers.Default)

      val coord1 =
        sharedContainer.createNoteEditorSessionCoordinator(
          openRequest = NoteEditorOpenRequest.OpenExisting(noteId = note.id, sessionId = sharedSessionId),
          parentScope = scope1,
          autosaveDebounceMillis = 60_000L,
        )
      val coord2 =
        sharedContainer.createNoteEditorSessionCoordinator(
          openRequest = NoteEditorOpenRequest.OpenExisting(noteId = note.id, sessionId = sharedSessionId),
          parentScope = scope2,
          autosaveDebounceMillis = 60_000L,
        )

      try {
        coord1.start()
        val init1 = withTimeout(10_000) { coord1.awaitInitialized() }
        assertEquals(NoteEditorSessionPhase.EDITING, init1.phase)
        assertTrue(sharedRegistry.isSessionReserved(sharedSessionId))
        assertTrue(sharedRegistry.isDraftReserved(expectedDraftId))

        // Segundo coordenador criado pelo mesmo AppContainer compartilha o Registry e é bloqueado.
        coord2.start()
        val init2 = withTimeout(10_000) { coord2.awaitInitialized() }
        withTimeout(10_000) { coord2.awaitTerminated() }
        assertEquals(NoteEditorSessionPhase.BLOCKED, init2.phase)
        assertTrue(init2.blockReason is SessionBlockReason.SessionAlreadyActive)
        assertTrue(init2.isSessionTerminated)

        // Nenhuma interferência na primeira sessão ativa: edição, autosave e consolidação ocorrem normalmente.
        assertTrue(sharedRegistry.isSessionReserved(sharedSessionId))
        assertEquals(1, sharedRegistry.snapshot().reservedSessionIds.size)
        coord1.submit(SessionCommand.EditTitle("Atualizada sem interferência"))
        coord1.submit(SessionCommand.InsertText("Conteúdo intacto"))
        assertEquals(SessionCommandOutcome.Completed, awaitCmd(coord1) { SessionCommand.FlushAutosave(it) })
        assertEquals(SessionCommandOutcome.Completed, awaitCmd(coord1) { SessionCommand.ConsolidateNote(ackDeferred = it) })
        assertEquals("Atualizada sem interferência", sharedContainer.noteRepository.getNoteById(note.id)?.title)

        // Encerramento gracioso da primeira sessão libera a identidade no Registry compartilhado.
        assertEquals(SessionCommandOutcome.Completed, awaitCmd(coord1) { SessionCommand.CloseSession(it) })
        withTimeout(10_000) { coord1.awaitTerminated() }
        assertEquals(0, sharedRegistry.snapshot().reservedSessionIds.size)

        // Reabertura bem-sucedida pela nova instância criada pelo mesmo AppContainer.
        val coord3 =
          sharedContainer.createNoteEditorSessionCoordinator(
            openRequest = NoteEditorOpenRequest.OpenExisting(noteId = note.id, sessionId = sharedSessionId),
            parentScope = scope3,
            autosaveDebounceMillis = 60_000L,
          )
        try {
          coord3.start()
          val init3 = withTimeout(10_000) { coord3.awaitInitialized() }
          assertEquals(NoteEditorSessionPhase.EDITING, init3.phase)
          assertEquals("Atualizada sem interferência", init3.title)
          assertTrue(sharedRegistry.isSessionReserved(sharedSessionId))
        } finally {
          coord3.closeAndAbort()
          coord3.awaitTerminated()
        }
      } finally {
        coord2.closeAndAbort()
        coord1.closeAndAbort()
        scope3.cancel()
        scope2.cancel()
        scope1.cancel()
        sharedContainer.database.close()
      }
      assertEquals(0, sharedRegistry.snapshot().reservedSessionIds.size)
    }

  @Test
  fun saveDraftAsNewNote_realSqliteStateCAndStateE_preserveDbWithoutOverwriteAndClearTracker() =
    runBlocking {
      val registry = ActiveSessionRegistry()
      val tracker = ReplacementHandoffTracker(testClock)

      // --- Estado C em SQLite real: ambos oldDraft e newDraft coexistem ---
      val noteC = noteRepository.createNote(title = "Nota C SQLite")
      val coexistRepo =
        object : NoteRecoveryDraftRepository by realDraftRepository {
          override suspend fun replaceCheckpointWithNewNote(
            oldDraftId: String,
            expectedOldSessionId: String,
            expectedOldTargetNoteId: Long,
            newInput: NoteRecoveryDraftInput,
          ): ReplaceCheckpointWithNewNoteResult {
            realDraftRepository.saveCheckpoint(newInput)
            throw IOException("Estado C simulado: antigo e novo coexistem no SQLite")
          }
        }
      val scopeC = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val coordC =
        NoteEditorSessionCoordinator(
          openRequest = NoteEditorOpenRequest.OpenExisting(noteId = noteC.id, sessionId = "int-state-c"),
          sessionOpenLoader =
            SessionOpenLoader(
              noteRepository = noteRepository,
              folderRepository = folderRepository,
              draftRepository = coexistRepo,
              preferencesRepository = preferencesRepository,
              activeSessionRegistry = registry,
              handoffTracker = tracker,
            ),
          replacementReconciler =
            PendingReplacementReconciler(
              noteRepository = noteRepository,
              draftRepository = coexistRepo,
              activeSessionRegistry = registry,
              handoffTracker = tracker,
            ),
          handoffTracker = tracker,
          activeSessionRegistry = registry,
          draftRepository = coexistRepo,
          noteRepository = noteRepository,
          folderRepository = folderRepository,
          dispatchers = ioDispatchers,
          clock = testClock,
          parentScope = scopeC,
          autosaveDebounceMillis = 60_000L,
          sessionIdGenerator = { "int-new-c" },
        )
      try {
        coordC.start()
        val initC = withTimeout(10_000) { coordC.awaitInitialized() }
        val oldDraftIdC = initC.sessionIdentity!!.draftId
        val newDraftIdC = NoteRecoveryDraftKeys.forNewNote("int-new-c")
        coordC.submit(SessionCommand.EditTitle("Conteúdo C SQLite"))
        assertEquals(SessionCommandOutcome.Completed, awaitCmd(coordC) { SessionCommand.FlushAutosave(it) })

        noteRepository.updateNote(
          noteId = noteC.id,
          title = "Conflito C SQLite",
          content = NoteContentDocument.empty(),
          folderId = null,
          expectedBaseRevision = noteC.revision,
        )
        assertTrue(awaitCmd(coordC) { SessionCommand.ConsolidateNote(ackDeferred = it) } is SessionCommandOutcome.Failed)

        val resC =
          awaitCmd(coordC) {
            SessionCommand.ResolveConflict(ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE, it)
          }
        assertTrue(resC is SessionCommandOutcome.Failed)
        val statusC = coordC.uiState.value.autosaveStatus as AutosaveStatus.Error
        assertTrue(statusC.isTerminal)
        assertTrue(statusC.message.contains("coexistem"))
        assertNotNull(database.noteRecoveryDraftDao().getByDraftId(oldDraftIdC))
        assertNotNull(database.noteRecoveryDraftDao().getByDraftId(newDraftIdC))
        assertEquals(2, database.noteRecoveryDraftDao().getAllCheckpoints().size)
        assertEquals(0, tracker.snapshot().activeCount)
        assertTrue(registry.isSessionReserved("int-state-c"))
        assertFalse(registry.isSessionReserved("int-new-c"))
      } finally {
        coordC.closeAndAbort()
        coordC.awaitTerminated()
        scopeC.cancel()
      }
      assertEquals(0, registry.snapshot().reservedSessionIds.size)
      realDraftRepository.deleteCheckpoint(NoteRecoveryDraftKeys.forNewNote("int-new-c"))
      realDraftRepository.deleteCheckpointsForNote(noteC.id)

      // --- Estado E em SQLite real: novo checkpoint divergente preservado sem sobrescrita ---
      val noteE = noteRepository.createNote(title = "Nota E SQLite")
      val tamperedRepo =
        object : NoteRecoveryDraftRepository by realDraftRepository {
          override suspend fun replaceCheckpointWithNewNote(
            oldDraftId: String,
            expectedOldSessionId: String,
            expectedOldTargetNoteId: Long,
            newInput: NoteRecoveryDraftInput,
          ): ReplaceCheckpointWithNewNoteResult {
            realDraftRepository.replaceCheckpointWithNewNote(
              oldDraftId = oldDraftId,
              expectedOldSessionId = expectedOldSessionId,
              expectedOldTargetNoteId = expectedOldTargetNoteId,
              newInput = newInput.copy(title = "Payload Divergente SQLite"),
            )
            throw IOException("Estado E simulado após inserção de destino divergente")
          }
        }
      val scopeE = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val coordE =
        NoteEditorSessionCoordinator(
          openRequest = NoteEditorOpenRequest.OpenExisting(noteId = noteE.id, sessionId = "int-state-e"),
          sessionOpenLoader =
            SessionOpenLoader(
              noteRepository = noteRepository,
              folderRepository = folderRepository,
              draftRepository = tamperedRepo,
              preferencesRepository = preferencesRepository,
              activeSessionRegistry = registry,
              handoffTracker = tracker,
            ),
          replacementReconciler =
            PendingReplacementReconciler(
              noteRepository = noteRepository,
              draftRepository = tamperedRepo,
              activeSessionRegistry = registry,
              handoffTracker = tracker,
            ),
          handoffTracker = tracker,
          activeSessionRegistry = registry,
          draftRepository = tamperedRepo,
          noteRepository = noteRepository,
          folderRepository = folderRepository,
          dispatchers = ioDispatchers,
          clock = testClock,
          parentScope = scopeE,
          autosaveDebounceMillis = 60_000L,
          sessionIdGenerator = { "int-new-e" },
        )
      try {
        coordE.start()
        val initE = withTimeout(10_000) { coordE.awaitInitialized() }
        val oldDraftIdE = initE.sessionIdentity!!.draftId
        val newDraftIdE = NoteRecoveryDraftKeys.forNewNote("int-new-e")
        coordE.submit(SessionCommand.EditTitle("Conteúdo E SQLite"))
        assertEquals(SessionCommandOutcome.Completed, awaitCmd(coordE) { SessionCommand.FlushAutosave(it) })

        noteRepository.updateNote(
          noteId = noteE.id,
          title = "Conflito E SQLite",
          content = NoteContentDocument.empty(),
          folderId = null,
          expectedBaseRevision = noteE.revision,
        )
        assertTrue(awaitCmd(coordE) { SessionCommand.ConsolidateNote(ackDeferred = it) } is SessionCommandOutcome.Failed)

        val resE =
          awaitCmd(coordE) {
            SessionCommand.ResolveConflict(ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE, it)
          }
        assertTrue(resE is SessionCommandOutcome.Failed)
        val statusE = coordE.uiState.value.autosaveStatus as AutosaveStatus.Error
        assertTrue(statusE.isTerminal)
        assertTrue(statusE.message.contains("diverge"))
        assertNull(database.noteRecoveryDraftDao().getByDraftId(oldDraftIdE))
        assertEquals(
          "Payload Divergente SQLite",
          database.noteRecoveryDraftDao().getByDraftId(newDraftIdE)?.title,
        )
        assertEquals(0, tracker.snapshot().activeCount)
        assertTrue(registry.isSessionReserved("int-state-e"))
        assertFalse(registry.isSessionReserved("int-new-e"))
      } finally {
        coordE.closeAndAbort()
        coordE.awaitTerminated()
        scopeE.cancel()
      }
      assertEquals(0, registry.snapshot().reservedSessionIds.size)
    }

  private class FaultInjectingDraftRepository(
    private val delegate: NoteRecoveryDraftRepository,
  ) : NoteRecoveryDraftRepository {
    var throwAfterNextReplaceCommit: Boolean = false
    var failReads: Boolean = false
    val replaceInvocations = AtomicInteger(0)

    override fun observeAllCheckpoints(): Flow<List<NoteRecoveryDraft>> =
      delegate.observeAllCheckpoints()

    override suspend fun getAllCheckpoints(): List<NoteRecoveryDraft> {
      if (failReads) throw IOException("Falha simulada em getAllCheckpoints")
      return delegate.getAllCheckpoints()
    }

    override suspend fun getCheckpointById(draftId: String): NoteRecoveryDraft? {
      if (failReads) throw IOException("Falha simulada em getCheckpointById")
      return delegate.getCheckpointById(draftId)
    }

    override suspend fun getCheckpointBySessionId(sessionId: String): NoteRecoveryDraft? {
      if (failReads) throw IOException("Falha simulada em getCheckpointBySessionId")
      return delegate.getCheckpointBySessionId(sessionId)
    }

    override suspend fun getCheckpointsForNote(noteId: Long): List<NoteRecoveryDraft> =
      delegate.getCheckpointsForNote(noteId)

    override suspend fun saveCheckpoint(input: NoteRecoveryDraftInput): SaveCheckpointResult =
      delegate.saveCheckpoint(input)

    override suspend fun replaceCheckpointWithNewNote(
      oldDraftId: String,
      expectedOldSessionId: String,
      expectedOldTargetNoteId: Long,
      newInput: NoteRecoveryDraftInput,
    ): ReplaceCheckpointWithNewNoteResult {
      replaceInvocations.incrementAndGet()
      val result =
        delegate.replaceCheckpointWithNewNote(
          oldDraftId = oldDraftId,
          expectedOldSessionId = expectedOldSessionId,
          expectedOldTargetNoteId = expectedOldTargetNoteId,
          newInput = newInput,
        )
      if (throwAfterNextReplaceCommit) {
        throwAfterNextReplaceCommit = false
        throw IOException("Falha simulada imediatamente após commit no Room")
      }
      return result
    }

    override suspend fun deleteCheckpoint(draftId: String) = delegate.deleteCheckpoint(draftId)

    override suspend fun deleteCheckpointsForNote(noteId: Long) =
      delegate.deleteCheckpointsForNote(noteId)
  }
}
