package com.example.ui.viewmodel

import com.example.core.time.ClockProvider
import com.example.domain.editor.ConflictResolutionOption
import com.example.domain.editor.FolderAvailability
import com.example.domain.editor.NoteEditorBaseline
import com.example.domain.editor.NoteEditorEditableSnapshot
import com.example.domain.editor.NoteEditorSessionEvaluator
import com.example.domain.editor.NoteEditorSessionIdentity
import com.example.domain.editor.RecoveryConflict
import com.example.domain.editor.ResolvedDraftFolderContext
import com.example.domain.model.Folder
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
import com.example.domain.repository.NoteDeletedException
import com.example.domain.repository.NoteNotFoundException
import com.example.domain.repository.NoteRecoveryDraftRepository
import com.example.domain.repository.NoteRepository
import com.example.domain.repository.PinNoteResult
import com.example.domain.repository.RestoreFolderResult
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStructuralRunnerTest {

  private class RunnerHarness {
    private val clockCounter = AtomicLong(1_700_200_000_000L)
    val clock =
      object : ClockProvider {
        override fun nowMillis(): Long = clockCounter.incrementAndGet()
      }

    val noteRepo = FakeNoteRepository()
    val folderRepo = FakeFolderRepository()
    val draftRepo = RecordingDraftRepository(clock)
    val registry = ActiveSessionRegistry()
    val handoffTracker = ReplacementHandoffTracker(clock)
    val reconciler =
      PendingReplacementReconciler(
        noteRepository = noteRepo,
        draftRepository = draftRepo,
        activeSessionRegistry = registry,
        handoffTracker = handoffTracker,
      )

    val committedLeases = mutableListOf<SessionLeaseToken>()
    val runner =
      SessionStructuralRunner(
        noteRepository = noteRepo,
        folderRepository = folderRepo,
        draftRepository = draftRepo,
        activeSessionRegistry = registry,
        handoffTracker = handoffTracker,
        replacementReconciler = reconciler,
        sessionIdGenerator = { "new-sess-unused" },
        onLeaseCommitted = { lease -> committedLeases.add(lease) },
      )

    fun createRunner(sessionIdProvider: () -> String): SessionStructuralRunner =
      SessionStructuralRunner(
        noteRepository = noteRepo,
        folderRepository = folderRepo,
        draftRepository = draftRepo,
        activeSessionRegistry = registry,
        handoffTracker = handoffTracker,
        replacementReconciler = reconciler,
        sessionIdGenerator = sessionIdProvider,
        onLeaseCommitted = { lease -> committedLeases.add(lease) },
      )

    fun createIdentity(noteId: Long, sessionId: String = "sess-keep"): NoteEditorSessionIdentity =
      NoteEditorSessionIdentity(
        draftId = NoteRecoveryDraftKeys.forExistingNote(noteId, sessionId),
        sessionId = sessionId,
        targetNoteId = noteId,
      )

    fun createExecutor(
      identity: NoteEditorSessionIdentity,
      presence: CheckpointPresence = CheckpointPresence.PRESENT,
      confirmedRevision: Long = 1L,
      highestAllocatedRevision: Long = confirmedRevision,
      lastCheckpointAt: Long? = 1_700_000_000L,
    ): SingleFlightRoomPersistenceExecutor =
      SingleFlightRoomPersistenceExecutor(
        draftRepository = draftRepo,
        noteRepository = noteRepo,
        initialIdentity = identity,
        initialPresence = presence,
        initialConfirmedRevision = confirmedRevision,
        initialHighestAllocatedRevision = highestAllocatedRevision,
        initialLastCheckpointAt = lastCheckpointAt,
      )

    fun createRuntimeSnapshot(
      identity: NoteEditorSessionIdentity,
      baseline: NoteEditorBaseline.ExistingNote,
      snapshot: NoteEditorEditableSnapshot,
      folderContext: ResolvedDraftFolderContext,
      conflict: RecoveryConflict? =
        RecoveryConflict.NoteDiverged(
          draftId = identity.draftId,
          targetNoteId = requireNotNull(identity.targetNoteId),
          draftBaseRevision = baseline.baseRevision,
          currentNoteRevision = baseline.baseRevision + 1L,
          draftBaseUpdatedAt = baseline.baseNoteUpdatedAt,
          currentNoteUpdatedAt = baseline.baseNoteUpdatedAt + 1_000L,
        ),
    ): SessionRuntimeState {
      val lease = (registry.reserve(identity) as SessionReservationResult.Reserved).lease
      return SessionRuntimeState(
        phase = NoteEditorSessionPhase.EDITING,
        lease = lease,
        identity = identity,
        baseline = baseline,
        snapshot = snapshot,
        folderContext = folderContext,
        effectiveFolderName = null,
        activeConflict = conflict,
        persistedInitialFolderId = null,
        persistedInitialPaperStyle = null,
        knownFolderAvailability = mutableMapOf(),
        stateGeneration = 2L,
        lastSyncedGeneration = 2L,
        lastCheckpointAt = 1_700_000_000L,
        appliedMutationSequence = 2L,
        autosaveStatus = AutosaveStatus.HeldPendingResolution,
        errorMessage = null,
        isConsolidating = false,
        isSessionTerminated = false,
        needsInitialPersistOnOpen = false,
      )
    }
  }

  @Test
  fun keepDraftOverCurrentNote_whenSelectedFolderBecomesUnavailable_usesRebasedSnapshotInEvaluationAndSavesFallbackDestination() =
    runTest {
      val h = RunnerHarness()
      val trashedFolder = h.folderRepo.seedFolder(id = 10L, name = "Pasta Removida", isDeleted = true)
      val sharedDoc = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "b1", text = "Mesmo conteúdo")))

      // Nota atual no DB e rascunho compartilham título, conteúdo, estilo e folderId = 10L.
      // A única razão para retenção após o rebase é a pasta 10L ter ido para a lixeira,
      // exigindo que o rebasedSnapshot tenha effectiveFolderId = null e fallbackSourceFolderId = 10L.
      val latestNote =
        h.noteRepo.seedNote(
          id = 50L,
          title = "Título idêntico",
          document = sharedDoc,
          folderId = trashedFolder.id,
          revision = 4L,
          updatedAt = 9_500L,
          isPinned = true,
          paperStyle = NotePaperStyle.SEM_LINHAS,
        )
      val identity = h.createIdentity(latestNote.id)
      val staleFolder = Folder(id = 10L, uid = "uid-10", name = "Pasta Removida", normalizedName = "pasta removida", isDeleted = false, deletedAt = null, createdAt = 1L, updatedAt = 2L)
      val runtime =
        h.createRuntimeSnapshot(
          identity = identity,
          baseline =
            NoteEditorBaseline.ExistingNote(
              noteId = latestNote.id,
              baseRevision = 1L,
              baseNoteUpdatedAt = 1_000L,
              title = "Título antigo",
              normalizedContent = NoteEditorSessionEvaluator.canonicalizeDocument(NoteContentDocument.empty()),
              paperStyle = NotePaperStyle.LINHAS,
              staticFolderId = trashedFolder.id,
              currentDbIsPinned = false,
            ),
          snapshot =
            NoteEditorEditableSnapshot(
              title = "Título idêntico",
              document = sharedDoc,
              effectiveFolderId = trashedFolder.id,
              fallbackSourceFolderId = null,
              paperStyle = NotePaperStyle.SEM_LINHAS,
              isPinned = false,
            ),
          folderContext =
            ResolvedDraftFolderContext(
              effectiveTarget = FolderAvailability.Active(staleFolder),
              fallbackSource = null,
              initialBaseline = FolderAvailability.Active(staleFolder),
              existingNoteFolder = FolderAvailability.Active(staleFolder),
            ),
        )
      val executor = h.createExecutor(identity = identity, confirmedRevision = 1L)

      val result =
        h.runner.runResolveConflict(
          command = SessionCommand.ResolveConflict(ConflictResolutionOption.KEEP_DRAFT_OVER_CURRENT_NOTE),
          runtimeSnapshot = runtime,
          executor = executor,
          onPendingReplacementSet = {},
        )

      assertTrue(result is StructuralRunnerResult.Succeeded)
      val outcome = (result as StructuralRunnerResult.Succeeded).data as StructuralOutcomeData.KeptDraftOverCurrentNote
      assertFalse(outcome.deletedCleanDraft)
      assertNotNull(outcome.checkpointAt)
      assertEquals(latestNote, outcome.latestNote)
      assertTrue(outcome.resolvedFolderAvailability[trashedFolder.id] is FolderAvailability.InTrash)
      assertNull(outcome.rebasedSnapshot.effectiveFolderId)
      assertEquals(trashedFolder.id, outcome.rebasedSnapshot.fallbackSourceFolderId)
      assertTrue(outcome.rebasedSnapshot.isPinned)
      assertEquals(FolderAvailability.Root, outcome.rebasedFolderContext.effectiveTarget)
      assertTrue(outcome.rebasedFolderContext.fallbackSource is FolderAvailability.InTrash)
      assertTrue(outcome.rebasedFolderContext.existingNoteFolder is FolderAvailability.InTrash)

      // Verifica exatamente os argumentos efetivamente enviados ao executor -> repositório.
      assertTrue(h.draftRepo.deletedDraftIds.isEmpty())
      assertEquals(1, h.draftRepo.savedInputs.size)
      val savedPayload = h.draftRepo.savedInputs.single()
      assertEquals(identity.draftId, savedPayload.draftId)
      assertEquals(identity.sessionId, savedPayload.sessionId)
      assertEquals(latestNote.id, savedPayload.targetNoteId)
      assertNull(savedPayload.folderId)
      assertEquals(trashedFolder.id, savedPayload.fallbackSourceFolderId)
      assertNull(savedPayload.initialFolderId)
      assertNull(savedPayload.initialPaperStyle)
      assertTrue(savedPayload.isPinned)
      assertEquals("Título idêntico", savedPayload.title)
      assertEquals(NoteEditorSessionEvaluator.canonicalizeDocument(sharedDoc), savedPayload.content)
      assertEquals(NotePaperStyle.SEM_LINHAS, savedPayload.paperStyle)
      assertEquals(4L, savedPayload.baseRevision)
      assertEquals(9_500L, savedPayload.baseNoteUpdatedAt)
      assertEquals(2L, savedPayload.checkpointRevision)
    }

  @Test
  fun keepDraftOverCurrentNote_whenReconciliationChangesDestinationAndDraftHasEdits_savesUpdatedDestinationAndAllMetadata() =
    runTest {
      val h = RunnerHarness()
      val activeNoteFolder = h.folderRepo.seedFolder(id = 20L, name = "Ativa", isDeleted = false)
      // Pasta 10L não existe no repositório -> FolderAvailability.NotFound(10L) -> fallback para raiz.
      val latestNote =
        h.noteRepo.seedNote(
          id = 60L,
          title = "Título do banco",
          document = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "db", text = "Texto do banco"))),
          folderId = activeNoteFolder.id,
          revision = 7L,
          updatedAt = 22_000L,
          isPinned = true,
          paperStyle = NotePaperStyle.LINHAS,
        )
      val editedDoc = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "ed", text = "Texto editado pelo usuário")))
      val identity = h.createIdentity(latestNote.id, sessionId = "sess-edited")
      val previousSelectedFolder = Folder(id = 10L, uid = "uid-10", name = "Selecionada", normalizedName = "selecionada", isDeleted = false, deletedAt = null, createdAt = 1L, updatedAt = 2L)

      val runtime =
        h.createRuntimeSnapshot(
          identity = identity,
          baseline =
            NoteEditorBaseline.ExistingNote(
              noteId = latestNote.id,
              baseRevision = 3L,
              baseNoteUpdatedAt = 5_000L,
              title = "Título base",
              normalizedContent = NoteEditorSessionEvaluator.canonicalizeDocument(NoteContentDocument.empty()),
              paperStyle = NotePaperStyle.LINHAS,
              staticFolderId = 10L,
              currentDbIsPinned = false,
            ),
          snapshot =
            NoteEditorEditableSnapshot(
              title = "Título preservado do rascunho",
              document = editedDoc,
              effectiveFolderId = 10L,
              fallbackSourceFolderId = null,
              paperStyle = NotePaperStyle.ELEGANCE_NOIR,
              isPinned = false,
            ),
          folderContext =
            ResolvedDraftFolderContext(
              effectiveTarget = FolderAvailability.Active(previousSelectedFolder),
              fallbackSource = null,
              initialBaseline = FolderAvailability.Active(previousSelectedFolder),
              existingNoteFolder = FolderAvailability.Active(previousSelectedFolder),
            ),
        )
      val executor = h.createExecutor(identity = identity, confirmedRevision = 3L)

      val result =
        h.runner.runResolveConflict(
          command = SessionCommand.ResolveConflict(ConflictResolutionOption.KEEP_DRAFT_OVER_CURRENT_NOTE),
          runtimeSnapshot = runtime,
          executor = executor,
          onPendingReplacementSet = {},
        )

      assertTrue(result is StructuralRunnerResult.Succeeded)
      val outcome = (result as StructuralRunnerResult.Succeeded).data as StructuralOutcomeData.KeptDraftOverCurrentNote
      assertFalse(outcome.deletedCleanDraft)
      assertEquals(setOf(activeNoteFolder.id, 10L), h.folderRepo.requestedIds.single())
      assertNull(outcome.rebasedSnapshot.effectiveFolderId)
      assertEquals(10L, outcome.rebasedSnapshot.fallbackSourceFolderId)
      assertTrue(outcome.rebasedSnapshot.isPinned)
      assertEquals(FolderAvailability.Root, outcome.rebasedFolderContext.effectiveTarget)
      assertEquals(FolderAvailability.NotFound(10L), outcome.rebasedFolderContext.fallbackSource)
      assertEquals(FolderAvailability.Active(activeNoteFolder), outcome.rebasedFolderContext.existingNoteFolder)

      assertEquals(1, h.draftRepo.savedInputs.size)
      val sent = h.draftRepo.savedInputs.single()
      assertNull(sent.folderId)
      assertEquals(10L, sent.fallbackSourceFolderId)
      assertEquals("Título preservado do rascunho", sent.title)
      assertEquals(NoteEditorSessionEvaluator.canonicalizeDocument(editedDoc), sent.content)
      assertEquals(NotePaperStyle.ELEGANCE_NOIR, sent.paperStyle)
      assertTrue(sent.isPinned)
      assertEquals(7L, sent.baseRevision)
      assertEquals(22_000L, sent.baseNoteUpdatedAt)
      assertNull(sent.initialFolderId)
      assertNull(sent.initialPaperStyle)
      assertEquals(4L, sent.checkpointRevision)
    }

  @Test
  fun keepDraftOverCurrentNote_usesReconciledFolderContextInEvaluationInsteadOfStaleUnknownContextAndDeletesCleanDraft() =
    runTest {
      val h = RunnerHarness()
      val activeFolder = h.folderRepo.seedFolder(id = 30L, name = "Restaurada", isDeleted = false)
      val matchingDoc = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "m1", text = "Conteúdo convergente")))
      val latestNote =
        h.noteRepo.seedNote(
          id = 70L,
          title = "Nota convergente",
          document = matchingDoc,
          folderId = activeFolder.id,
          revision = 5L,
          updatedAt = 18_000L,
          isPinned = true,
          paperStyle = NotePaperStyle.PONTILHADO,
        )
      val identity = h.createIdentity(latestNote.id, sessionId = "sess-ctx")

      // O folderContext anterior apontava Unknown(30L). Se evaluateSession usasse o contexto antigo
      // em vez do rebasedContext retornado pela reconciliação, hasUnknownCurrentDestination seria true
      // e o rascunho seria salvo indevidamente em vez de ser excluído.
      val runtime =
        h.createRuntimeSnapshot(
          identity = identity,
          baseline =
            NoteEditorBaseline.ExistingNote(
              noteId = latestNote.id,
              baseRevision = 1L,
              baseNoteUpdatedAt = 2_000L,
              title = "Título antigo",
              normalizedContent = NoteEditorSessionEvaluator.canonicalizeDocument(NoteContentDocument.empty()),
              paperStyle = NotePaperStyle.LINHAS,
              staticFolderId = activeFolder.id,
              currentDbIsPinned = false,
            ),
          snapshot =
            NoteEditorEditableSnapshot(
              title = "Nota convergente",
              document = matchingDoc,
              effectiveFolderId = activeFolder.id,
              fallbackSourceFolderId = null,
              paperStyle = NotePaperStyle.PONTILHADO,
              isPinned = false,
            ),
          folderContext =
            ResolvedDraftFolderContext(
              effectiveTarget = FolderAvailability.Unknown(activeFolder.id, "Desconhecido antes da conciliação"),
              fallbackSource = null,
              initialBaseline = FolderAvailability.Unknown(activeFolder.id),
              existingNoteFolder = FolderAvailability.Unknown(activeFolder.id),
            ),
        )
      val executor = h.createExecutor(identity = identity, confirmedRevision = 2L)

      val result =
        h.runner.runResolveConflict(
          command = SessionCommand.ResolveConflict(ConflictResolutionOption.KEEP_DRAFT_OVER_CURRENT_NOTE),
          runtimeSnapshot = runtime,
          executor = executor,
          onPendingReplacementSet = {},
        )

      assertTrue(result is StructuralRunnerResult.Succeeded)
      val outcome = (result as StructuralRunnerResult.Succeeded).data as StructuralOutcomeData.KeptDraftOverCurrentNote
      assertTrue(outcome.deletedCleanDraft)
      assertNull(outcome.checkpointAt)
      assertEquals(activeFolder.id, outcome.rebasedSnapshot.effectiveFolderId)
      assertNull(outcome.rebasedSnapshot.fallbackSourceFolderId)
      assertTrue(outcome.rebasedSnapshot.isPinned)
      assertEquals(FolderAvailability.Active(activeFolder), outcome.rebasedFolderContext.effectiveTarget)
      assertNull(outcome.rebasedFolderContext.fallbackSource)
      assertEquals(FolderAvailability.Active(activeFolder), outcome.rebasedFolderContext.existingNoteFolder)
      assertTrue(h.draftRepo.savedInputs.isEmpty())
      assertEquals(listOf(identity.draftId), h.draftRepo.deletedDraftIds)
      assertEquals(CheckpointPresence.ABSENT, executor.snapshot().checkpointPresence)
    }

  @Test
  fun keepDraftOverCurrentNote_whenDeleteCheckpointFails_propagatesFailureAndVerifiesDraftIdSentToExecutor() =
    runTest {
      val h = RunnerHarness()
      val matchingDoc = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "c1", text = "Igual")))
      val latestNote =
        h.noteRepo.seedNote(
          id = 80L,
          title = "Igual",
          document = matchingDoc,
          folderId = null,
          revision = 2L,
          updatedAt = 5_000L,
          isPinned = false,
          paperStyle = NotePaperStyle.LINHAS,
        )
      val identity = h.createIdentity(latestNote.id, sessionId = "sess-del-fail")
      val runtime =
        h.createRuntimeSnapshot(
          identity = identity,
          baseline =
            NoteEditorBaseline.ExistingNote(
              noteId = latestNote.id,
              baseRevision = 1L,
              baseNoteUpdatedAt = 1_000L,
              title = "Desatualizado",
              normalizedContent = NoteEditorSessionEvaluator.canonicalizeDocument(NoteContentDocument.empty()),
              paperStyle = NotePaperStyle.LINHAS,
              staticFolderId = null,
              currentDbIsPinned = false,
            ),
          snapshot =
            NoteEditorEditableSnapshot(
              title = "Igual",
              document = matchingDoc,
              effectiveFolderId = null,
              fallbackSourceFolderId = null,
              paperStyle = NotePaperStyle.LINHAS,
              isPinned = false,
            ),
          folderContext = ResolvedDraftFolderContext(effectiveTarget = FolderAvailability.Root),
        )
      val ioError = IOException("Falha simulada ao excluir checkpoint")
      h.draftRepo.onDeleteHook = { throw ioError }
      val executor = h.createExecutor(identity = identity, confirmedRevision = 1L)

      val result =
        h.runner.runResolveConflict(
          command = SessionCommand.ResolveConflict(ConflictResolutionOption.KEEP_DRAFT_OVER_CURRENT_NOTE),
          runtimeSnapshot = runtime,
          executor = executor,
          onPendingReplacementSet = {},
        )

      assertTrue(result is StructuralRunnerResult.Failed)
      assertSame(ioError, (result as StructuralRunnerResult.Failed).cause)
      assertEquals(listOf(identity.draftId), h.draftRepo.deletedDraftIds)
      assertTrue(h.draftRepo.savedInputs.isEmpty())
    }

  @Test
  fun keepDraftOverCurrentNote_whenSaveCheckpointFails_propagatesFailureAfterSendingRebasedPayload() =
    runTest {
      val h = RunnerHarness()
      val deletedFolder = h.folderRepo.seedFolder(id = 90L, name = "Apagada", isDeleted = true)
      val latestNote =
        h.noteRepo.seedNote(
          id = 90L,
          title = "Nota banco",
          document = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "n", text = "Banco"))),
          folderId = null,
          revision = 6L,
          updatedAt = 14_000L,
          isPinned = false,
        )
      val draftDoc = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "d", text = "Rascunho")))
      val identity = h.createIdentity(latestNote.id, sessionId = "sess-save-fail")
      val runtime =
        h.createRuntimeSnapshot(
          identity = identity,
          baseline =
            NoteEditorBaseline.ExistingNote(
              noteId = latestNote.id,
              baseRevision = 1L,
              baseNoteUpdatedAt = 1_000L,
              title = "Antigo",
              normalizedContent = NoteEditorSessionEvaluator.canonicalizeDocument(NoteContentDocument.empty()),
              paperStyle = NotePaperStyle.LINHAS,
              staticFolderId = null,
              currentDbIsPinned = false,
            ),
          snapshot =
            NoteEditorEditableSnapshot(
              title = "Rascunho mantido",
              document = draftDoc,
              effectiveFolderId = deletedFolder.id,
              fallbackSourceFolderId = null,
              paperStyle = NotePaperStyle.LINHAS,
              isPinned = false,
            ),
          folderContext = ResolvedDraftFolderContext(effectiveTarget = FolderAvailability.Unknown(deletedFolder.id)),
        )
      val ioError = IOException("Falha simulada ao salvar checkpoint rebased")
      h.draftRepo.onSaveHook = { throw ioError }
      val executor = h.createExecutor(identity = identity, confirmedRevision = 2L)

      val result =
        h.runner.runResolveConflict(
          command = SessionCommand.ResolveConflict(ConflictResolutionOption.KEEP_DRAFT_OVER_CURRENT_NOTE),
          runtimeSnapshot = runtime,
          executor = executor,
          onPendingReplacementSet = {},
        )

      assertTrue(result is StructuralRunnerResult.Failed)
      assertSame(ioError, (result as StructuralRunnerResult.Failed).cause)
      assertEquals(1, h.draftRepo.savedInputs.size)
      val sent = h.draftRepo.savedInputs.single()
      assertNull(sent.folderId)
      assertEquals(deletedFolder.id, sent.fallbackSourceFolderId)
      assertEquals(6L, sent.baseRevision)
      assertEquals(14_000L, sent.baseNoteUpdatedAt)
    }

  @Test
  fun keepDraftOverCurrentNote_whenSaveReturnsIgnoredStaleRevision_returnsFailedAndDoesNotTreatAsSuccess() =
    runTest {
      val h = RunnerHarness()
      val trashedFolder = h.folderRepo.seedFolder(id = 100L, name = "Lixeira", isDeleted = true)
      val latestNote =
        h.noteRepo.seedNote(
          id = 100L,
          title = "Nota banco stale",
          document = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "ns", text = "Banco"))),
          folderId = null,
          revision = 3L,
          updatedAt = 7_000L,
        )
      val identity = h.createIdentity(latestNote.id, sessionId = "sess-stale")
      val runtime =
        h.createRuntimeSnapshot(
          identity = identity,
          baseline =
            NoteEditorBaseline.ExistingNote(
              noteId = latestNote.id,
              baseRevision = 1L,
              baseNoteUpdatedAt = 1_000L,
              title = "Base",
              normalizedContent = NoteEditorSessionEvaluator.canonicalizeDocument(NoteContentDocument.empty()),
              paperStyle = NotePaperStyle.LINHAS,
              staticFolderId = null,
              currentDbIsPinned = false,
            ),
          snapshot =
            NoteEditorEditableSnapshot(
              title = "Draft stale",
              document = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "ds", text = "Draft"))),
              effectiveFolderId = trashedFolder.id,
              fallbackSourceFolderId = null,
              paperStyle = NotePaperStyle.LINHAS,
              isPinned = false,
            ),
          folderContext = ResolvedDraftFolderContext(effectiveTarget = FolderAvailability.Unknown(trashedFolder.id)),
        )
      h.draftRepo.onSaveHook = { input ->
        SaveCheckpointResult.IgnoredStaleRevision(
          draftId = input.draftId,
          persistedCheckpointRevision = 50L,
          attemptedCheckpointRevision = input.checkpointRevision,
        )
      }
      val executor = h.createExecutor(identity = identity, confirmedRevision = 1L)

      val result =
        h.runner.runResolveConflict(
          command = SessionCommand.ResolveConflict(ConflictResolutionOption.KEEP_DRAFT_OVER_CURRENT_NOTE),
          runtimeSnapshot = runtime,
          executor = executor,
          onPendingReplacementSet = {},
        )

      assertTrue(result is StructuralRunnerResult.Failed)
      val failure = result as StructuralRunnerResult.Failed
      assertTrue(failure.cause is IllegalStateException)
      assertTrue(failure.cause.message!!.contains("Revisão obsoleta"))
      assertTrue(failure.cause.message!!.contains("2 <= 50"))
      // Também confirma que o payload rebased foi realmente submetido antes do retorno de IgnoredStaleRevision.
      assertEquals(1, h.draftRepo.savedInputs.size)
      assertNull(h.draftRepo.savedInputs.single().folderId)
      assertEquals(trashedFolder.id, h.draftRepo.savedInputs.single().fallbackSourceFolderId)
    }

  @Test
  fun keepDraftOverCurrentNote_whenExecutorHitsRevisionOverflow_returnsFailedAndDoesNotTreatAsSuccess() =
    runTest {
      val h = RunnerHarness()
      val latestNote =
        h.noteRepo.seedNote(
          id = 110L,
          title = "Nota banco overflow",
          document = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "no", text = "Banco"))),
          folderId = null,
          revision = 2L,
          updatedAt = 3_000L,
        )
      val identity = h.createIdentity(latestNote.id, sessionId = "sess-overflow")
      val runtime =
        h.createRuntimeSnapshot(
          identity = identity,
          baseline =
            NoteEditorBaseline.ExistingNote(
              noteId = latestNote.id,
              baseRevision = 1L,
              baseNoteUpdatedAt = 1_000L,
              title = "Base",
              normalizedContent = NoteEditorSessionEvaluator.canonicalizeDocument(NoteContentDocument.empty()),
              paperStyle = NotePaperStyle.LINHAS,
              staticFolderId = null,
              currentDbIsPinned = false,
            ),
          snapshot =
            NoteEditorEditableSnapshot(
              title = "Draft divergente para reter",
              document = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "do", text = "Draft"))),
              effectiveFolderId = null,
              fallbackSourceFolderId = null,
              paperStyle = NotePaperStyle.LINHAS,
              isPinned = false,
            ),
          folderContext = ResolvedDraftFolderContext(effectiveTarget = FolderAvailability.Root),
        )
      val executor =
        h.createExecutor(
          identity = identity,
          presence = CheckpointPresence.PRESENT,
          confirmedRevision = Long.MAX_VALUE - 1L,
          highestAllocatedRevision = Long.MAX_VALUE - 1L,
        )

      val result =
        h.runner.runResolveConflict(
          command = SessionCommand.ResolveConflict(ConflictResolutionOption.KEEP_DRAFT_OVER_CURRENT_NOTE),
          runtimeSnapshot = runtime,
          executor = executor,
          onPendingReplacementSet = {},
        )

      assertTrue(result is StructuralRunnerResult.Failed)
      val failure = result as StructuralRunnerResult.Failed
      assertTrue(failure.cause is IllegalStateException)
      assertEquals("Limite de revisões de checkpoint atingido.", failure.cause.message)
      assertTrue(h.draftRepo.savedInputs.isEmpty())
    }

  @Test
  fun saveDraftAsNewNote_whenRoomCommitSucceededBeforeFaultAndReconcilerReturnsPromotedToNew_returnsSucceededAndClearsPendingContext() =
    runTest {
      val h = RunnerHarness()
      val note = h.noteRepo.seedNote(id = 91L, title = "Original 91", revision = 1L)
      val oldIdentity = h.createIdentity(note.id, sessionId = "runner-a-old")
      val editedDoc = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Texto A")))
      val runtime =
        h.createRuntimeSnapshot(
          identity = oldIdentity,
          baseline =
            NoteEditorBaseline.ExistingNote(
              noteId = note.id,
              baseRevision = 1L,
              baseNoteUpdatedAt = 1_000L,
              title = "Original 91",
              normalizedContent = NoteEditorSessionEvaluator.canonicalizeDocument(NoteContentDocument.empty()),
              paperStyle = NotePaperStyle.LINHAS,
              staticFolderId = null,
              currentDbIsPinned = false,
            ),
          snapshot =
            NoteEditorEditableSnapshot(
              title = "Título A",
              document = editedDoc,
              effectiveFolderId = null,
              fallbackSourceFolderId = null,
              paperStyle = NotePaperStyle.PONTILHADO,
              isPinned = true,
            ),
          folderContext = ResolvedDraftFolderContext(effectiveTarget = FolderAvailability.Root),
        )
      val executor = h.createExecutor(identity = oldIdentity, confirmedRevision = 1L)
      var injectFault = true
      h.draftRepo.onReplaceHook = { oldId, newInput ->
        val res = h.draftRepo.defaultReplace(oldId, newInput)
        if (injectFault) {
          injectFault = false
          throw IOException("Queda após commit no SQLite")
        }
        res
      }
      val pendingCalls = mutableListOf<UncommittedReplacementContext?>()
      val runner = h.createRunner { "runner-a-new" }

      val result =
        runner.runResolveConflict(
          command = SessionCommand.ResolveConflict(ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE),
          runtimeSnapshot = runtime,
          executor = executor,
          onPendingReplacementSet = { ctx -> pendingCalls.add(ctx) },
        )

      assertTrue(result is StructuralRunnerResult.Succeeded)
      val outcome = (result as StructuralRunnerResult.Succeeded).data as StructuralOutcomeData.ReconciledIndeterminate
      val rec = outcome.result as ReplacementReconciliationResult.PromotedToNew
      assertEquals(NoteRecoveryDraftKeys.forNewNote("runner-a-new"), rec.newIdentity.draftId)
      assertEquals(listOf(rec.newLease), h.committedLeases)
      assertEquals(2, pendingCalls.size)
      assertNotNull(pendingCalls.first())
      assertNull(pendingCalls.last())
      assertTrue(h.registry.isSessionReserved("runner-a-new"))
      assertFalse(h.registry.isSessionReserved("runner-a-old"))
      assertEquals(
        ReplacementHandoffStatus.RESOLVED_TO_NEW,
        h.handoffTracker.findActiveCandidate(oldIdentity.draftId)?.status,
      )
    }

  @Test
  fun saveDraftAsNewNote_whenRegistryCommitReturnsRejectedAlreadyRolledBack_handlesDivergentAndReadFailureWithoutLeavingStalePromotedState() =
    runTest {
      // Subcaso 1: release(sourceLease) durante a substituição no Room provoca RejectedAlreadyRolledBack
      // e reconciliação em DivergentAnomaly("REGISTRY_COMMIT_REJECTED") limpa o contexto pendente.
      val h1 = RunnerHarness()
      val note1 = h1.noteRepo.seedNote(id = 92L, title = "Nota 92", revision = 1L)
      val id1 = h1.createIdentity(note1.id, sessionId = "reg-rej-1")
      val rt1 =
        h1.createRuntimeSnapshot(
          identity = id1,
          baseline =
            NoteEditorBaseline.ExistingNote(
              noteId = note1.id,
              baseRevision = 1L,
              baseNoteUpdatedAt = 1_000L,
              title = "Nota 92",
              normalizedContent = NoteEditorSessionEvaluator.canonicalizeDocument(NoteContentDocument.empty()),
              paperStyle = NotePaperStyle.LINHAS,
              staticFolderId = null,
              currentDbIsPinned = false,
            ),
          snapshot =
            NoteEditorEditableSnapshot(
              title = "Draft 92",
              document = NoteContentDocument.empty(),
              effectiveFolderId = null,
              fallbackSourceFolderId = null,
              paperStyle = NotePaperStyle.LINHAS,
              isPinned = false,
            ),
          folderContext = ResolvedDraftFolderContext(effectiveTarget = FolderAvailability.Root),
        )
      val exec1 = h1.createExecutor(identity = id1, confirmedRevision = 1L)
      h1.draftRepo.onReplaceHook = { oldId, newInput ->
        // Libera sourceLease durante a janela de I/O, marcando a transferência pendente como RolledBack.
        h1.registry.release(rt1.lease)
        h1.draftRepo.defaultReplace(oldId, newInput)
      }
      val pendingCalls1 = mutableListOf<UncommittedReplacementContext?>()
      val res1 =
        h1.createRunner { "reg-rej-1-new" }.runResolveConflict(
          command = SessionCommand.ResolveConflict(ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE),
          runtimeSnapshot = rt1,
          executor = exec1,
          onPendingReplacementSet = { ctx -> pendingCalls1.add(ctx) },
        )

      assertTrue(res1 is StructuralRunnerResult.Failed)
      val failed1 = res1 as StructuralRunnerResult.Failed
      assertTrue(failed1.cause.message!!.contains("Commit no Registry falhou"))
      val outcome1 = failed1.fallbackData as StructuralOutcomeData.ReconciledIndeterminate
      val anomaly1 = outcome1.result as ReplacementReconciliationResult.DivergentAnomaly
      assertEquals("REGISTRY_COMMIT_REJECTED", anomaly1.stateCode)
      assertTrue(h1.committedLeases.isEmpty())
      assertEquals(2, pendingCalls1.size)
      assertNotNull(pendingCalls1.first())
      assertNull(pendingCalls1.last())

      // Subcaso 2: se a leitura de confirmação pós-replace falhar com IndeterminateReadFailure,
      // preserva o contexto pendente (não chama onPendingReplacementSet(null)).
      val h2 = RunnerHarness()
      val note2 = h2.noteRepo.seedNote(id = 93L, title = "Nota 93", revision = 1L)
      val id2 = h2.createIdentity(note2.id, sessionId = "reg-rej-2")
      val rt2 =
        h2.createRuntimeSnapshot(
          identity = id2,
          baseline =
            NoteEditorBaseline.ExistingNote(
              noteId = note2.id,
              baseRevision = 1L,
              baseNoteUpdatedAt = 1_000L,
              title = "Nota 93",
              normalizedContent = NoteEditorSessionEvaluator.canonicalizeDocument(NoteContentDocument.empty()),
              paperStyle = NotePaperStyle.LINHAS,
              staticFolderId = null,
              currentDbIsPinned = false,
            ),
          snapshot =
            NoteEditorEditableSnapshot(
              title = "Draft 93",
              document = NoteContentDocument.empty(),
              effectiveFolderId = null,
              fallbackSourceFolderId = null,
              paperStyle = NotePaperStyle.LINHAS,
              isPinned = false,
            ),
          folderContext = ResolvedDraftFolderContext(effectiveTarget = FolderAvailability.Root),
        )
      val exec2 = h2.createExecutor(identity = id2, confirmedRevision = 1L)
      h2.draftRepo.onReplaceHook = { oldId, newInput ->
        h2.registry.release(rt2.lease)
        h2.draftRepo.defaultReplace(oldId, newInput)
      }
      h2.draftRepo.onGetCheckpointHook = { throw IOException("Falha de leitura após RejectedAlreadyRolledBack") }
      val pendingCalls2 = mutableListOf<UncommittedReplacementContext?>()
      val res2 =
        h2.createRunner { "reg-rej-2-new" }.runResolveConflict(
          command = SessionCommand.ResolveConflict(ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE),
          runtimeSnapshot = rt2,
          executor = exec2,
          onPendingReplacementSet = { ctx -> pendingCalls2.add(ctx) },
        )

      assertTrue(res2 is StructuralRunnerResult.Failed)
      val failed2 = res2 as StructuralRunnerResult.Failed
      assertTrue(failed2.cause is IOException)
      val outcome2 = failed2.fallbackData as StructuralOutcomeData.ReconciledIndeterminate
      assertTrue(outcome2.result is ReplacementReconciliationResult.IndeterminateReadFailure)
      assertTrue(h2.committedLeases.isEmpty())
      assertEquals(1, pendingCalls2.size)
      assertNotNull(pendingCalls2.single())
    }

  @Test
  fun saveDraftAsNewNote_whenReconcilerReturnsIndeterminateReadFailure_preservesPendingReplacementContext() =
    runTest {
      val h = RunnerHarness()
      val note = h.noteRepo.seedNote(id = 94L, title = "Nota 94", revision = 1L)
      val oldIdentity = h.createIdentity(note.id, sessionId = "runner-f-old")
      val runtime =
        h.createRuntimeSnapshot(
          identity = oldIdentity,
          baseline =
            NoteEditorBaseline.ExistingNote(
              noteId = note.id,
              baseRevision = 1L,
              baseNoteUpdatedAt = 1_000L,
              title = "Nota 94",
              normalizedContent = NoteEditorSessionEvaluator.canonicalizeDocument(NoteContentDocument.empty()),
              paperStyle = NotePaperStyle.LINHAS,
              staticFolderId = null,
              currentDbIsPinned = false,
            ),
          snapshot =
            NoteEditorEditableSnapshot(
              title = "Draft F",
              document = NoteContentDocument.empty(),
              effectiveFolderId = null,
              fallbackSourceFolderId = null,
              paperStyle = NotePaperStyle.LINHAS,
              isPinned = false,
            ),
          folderContext = ResolvedDraftFolderContext(effectiveTarget = FolderAvailability.Root),
        )
      val executor = h.createExecutor(identity = oldIdentity, confirmedRevision = 1L)
      h.draftRepo.onReplaceHook = { oldId, newInput ->
        h.draftRepo.defaultReplace(oldId, newInput)
        throw IOException("Queda no replace")
      }
      h.draftRepo.onGetCheckpointHook = { throw IOException("Falha de leitura na reconciliação") }
      val pendingCalls = mutableListOf<UncommittedReplacementContext?>()

      val result =
        h.createRunner { "runner-f-new" }.runResolveConflict(
          command = SessionCommand.ResolveConflict(ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE),
          runtimeSnapshot = runtime,
          executor = executor,
          onPendingReplacementSet = { ctx -> pendingCalls.add(ctx) },
        )

      assertTrue(result is StructuralRunnerResult.Failed)
      val failed = result as StructuralRunnerResult.Failed
      assertTrue(failed.cause is IOException)
      val outcome = failed.fallbackData as StructuralOutcomeData.ReconciledIndeterminate
      assertTrue(outcome.result is ReplacementReconciliationResult.IndeterminateReadFailure)
      assertTrue(h.committedLeases.isEmpty())
      assertEquals(1, pendingCalls.size)
      assertNotNull(pendingCalls.single())
      assertEquals(
        ReplacementHandoffStatus.PENDING,
        h.handoffTracker.findActiveCandidate(oldIdentity.draftId)?.status,
      )
    }

  @Test
  fun saveDraftAsNewNote_whenReconcilerReturnsRevertedNeitherPresentOrDivergent_returnsFailedAndClearsPendingContext() =
    runTest {
      suspend fun runCase(
        sessionPrefix: String,
        seedOldDraft: Boolean = true,
        hook: suspend RunnerHarness.(String, NoteRecoveryDraftInput) -> ReplaceCheckpointWithNewNoteResult,
      ): ReplacementReconciliationResult {
        val h = RunnerHarness()
        val note = h.noteRepo.seedNote(id = 95L, title = "Nota 95", revision = 1L)
        val oldIdentity = h.createIdentity(note.id, sessionId = "$sessionPrefix-old")
        if (seedOldDraft) {
          h.draftRepo.drafts[oldIdentity.draftId] =
            NoteRecoveryDraft(
              draftId = oldIdentity.draftId,
              targetNoteId = note.id,
              sessionId = oldIdentity.sessionId,
              folderId = null,
              initialFolderId = null,
              fallbackSourceFolderId = null,
              isPinned = false,
              title = "Draft $sessionPrefix",
              contentState = NoteContentState.Valid(NoteContentDocument.empty()),
              paperStyle = NotePaperStyle.LINHAS,
              initialPaperStyle = null,
              baseRevision = 1L,
              checkpointRevision = 1L,
              baseNoteUpdatedAt = 1_000L,
              checkpointAt = 2_000L,
            )
        }
        val runtime =
          h.createRuntimeSnapshot(
            identity = oldIdentity,
            baseline =
              NoteEditorBaseline.ExistingNote(
                noteId = note.id,
                baseRevision = 1L,
                baseNoteUpdatedAt = 1_000L,
                title = "Nota 95",
                normalizedContent = NoteEditorSessionEvaluator.canonicalizeDocument(NoteContentDocument.empty()),
                paperStyle = NotePaperStyle.LINHAS,
                staticFolderId = null,
                currentDbIsPinned = false,
              ),
            snapshot =
              NoteEditorEditableSnapshot(
                title = "Draft $sessionPrefix",
                document = NoteContentDocument.empty(),
                effectiveFolderId = null,
                fallbackSourceFolderId = null,
                paperStyle = NotePaperStyle.LINHAS,
                isPinned = false,
              ),
            folderContext = ResolvedDraftFolderContext(effectiveTarget = FolderAvailability.Root),
          )
        val executor = h.createExecutor(identity = oldIdentity, confirmedRevision = 1L)
        h.draftRepo.onReplaceHook = { oldId, newInput -> h.hook(oldId, newInput) }
        val pendingCalls = mutableListOf<UncommittedReplacementContext?>()

        val result =
          h.createRunner { "$sessionPrefix-new" }.runResolveConflict(
            command = SessionCommand.ResolveConflict(ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE),
            runtimeSnapshot = runtime,
            executor = executor,
            onPendingReplacementSet = { ctx -> pendingCalls.add(ctx) },
          )

        assertTrue(result is StructuralRunnerResult.Failed)
        assertTrue(h.committedLeases.isEmpty())
        assertEquals(2, pendingCalls.size)
        assertNotNull(pendingCalls.first())
        assertNull(pendingCalls.last())
        assertTrue(h.registry.isSessionReserved("$sessionPrefix-old"))
        assertFalse(h.registry.isSessionReserved("$sessionPrefix-new"))
        val outcome = (result as StructuralRunnerResult.Failed).fallbackData as StructuralOutcomeData.ReconciledIndeterminate
        return outcome.result
      }

      val reverted =
        runCase("reverted") { _, _ -> throw IOException("Falha antes da substituição") }
      assertTrue(reverted is ReplacementReconciliationResult.RevertedToOld)

      val neither =
        runCase("neither") { oldId, _ ->
          draftRepo.drafts.remove(oldId)
          throw IOException("Sumiu tudo")
        }
      assertTrue(neither is ReplacementReconciliationResult.NeitherPresent)

      val divergent =
        runCase("divergent") { oldId, newInput ->
          draftRepo.defaultReplace(oldId, newInput.copy(title = "Adulterado"))
          throw IOException("Payload divergente")
        }
      assertTrue(divergent is ReplacementReconciliationResult.DivergentAnomaly)
    }

  @Test
  fun persistExistingNotePin_whenConcurrentWriteAdvancesRevision_returnsConflictAndDoesNotPin() =
    runTest {
      val h = RunnerHarness()
      val note = h.noteRepo.seedNote(id = 201L, title = "Nota 201", revision = 1L, isPinned = false)
      val identity = h.createIdentity(note.id, sessionId = "pin-race")
      val runtime =
        h.createRuntimeSnapshot(
          identity = identity,
          baseline =
            NoteEditorBaseline.ExistingNote(
              noteId = note.id,
              baseRevision = 1L,
              baseNoteUpdatedAt = note.updatedAt,
              title = note.title,
              normalizedContent = NoteEditorSessionEvaluator.canonicalizeDocument(NoteContentDocument.empty()),
              paperStyle = NotePaperStyle.LINHAS,
              staticFolderId = null,
              currentDbIsPinned = false,
            ),
          snapshot =
            NoteEditorEditableSnapshot(
              title = note.title,
              document = NoteContentDocument.empty(),
              effectiveFolderId = null,
              fallbackSourceFolderId = null,
              paperStyle = NotePaperStyle.LINHAS,
              isPinned = false,
            ),
          folderContext = ResolvedDraftFolderContext(effectiveTarget = FolderAvailability.Root),
          conflict = null,
        )

      // Simula uma escrita concorrente que avança revision exatamente antes da atualização condicional.
      h.noteRepo.onBeforeConditionalPin = { noteId ->
        val latest = h.noteRepo.notes[noteId]!!
        h.noteRepo.notes[noteId] =
          latest.copy(
            title = "Alterada concorrentemente",
            revision = latest.revision + 1L,
            updatedAt = latest.updatedAt + 500L,
          )
      }

      val result =
        h.runner.runPersistExistingNotePin(
          command = SessionCommand.PersistExistingNotePin(isPinned = true),
          runtimeSnapshot = runtime,
        )

      assertTrue(result is StructuralRunnerResult.Failed)
      val failed = result as StructuralRunnerResult.Failed
      assertTrue(failed.cause is NoteConflictException)
      val conflictData = failed.fallbackData as StructuralOutcomeData.ConsolidationConflictDetected
      assertEquals(1L, (conflictData.conflict as RecoveryConflict.NoteDiverged).draftBaseRevision)
      assertEquals(2L, conflictData.conflict.currentNoteRevision)
      assertFalse(h.noteRepo.notes[note.id]!!.isPinned)
      assertEquals(2L, h.noteRepo.notes[note.id]!!.revision)
    }

  @Test
  fun persistExistingNotePin_whenRevisionMatches_updatesPinAndBumpsRevisionAtomically() =
    runTest {
      val h = RunnerHarness()
      val note = h.noteRepo.seedNote(id = 202L, title = "Nota 202", revision = 3L, isPinned = false)
      val identity = h.createIdentity(note.id, sessionId = "pin-ok")
      val runtime =
        h.createRuntimeSnapshot(
          identity = identity,
          baseline =
            NoteEditorBaseline.ExistingNote(
              noteId = note.id,
              baseRevision = 3L,
              baseNoteUpdatedAt = note.updatedAt,
              title = note.title,
              normalizedContent = NoteEditorSessionEvaluator.canonicalizeDocument(NoteContentDocument.empty()),
              paperStyle = NotePaperStyle.LINHAS,
              staticFolderId = null,
              currentDbIsPinned = false,
            ),
          snapshot =
            NoteEditorEditableSnapshot(
              title = note.title,
              document = NoteContentDocument.empty(),
              effectiveFolderId = null,
              fallbackSourceFolderId = null,
              paperStyle = NotePaperStyle.LINHAS,
              isPinned = false,
            ),
          folderContext = ResolvedDraftFolderContext(effectiveTarget = FolderAvailability.Root),
          conflict = null,
        )

      val result =
        h.runner.runPersistExistingNotePin(
          command = SessionCommand.PersistExistingNotePin(isPinned = true),
          runtimeSnapshot = runtime,
        )

      assertTrue(result is StructuralRunnerResult.Succeeded)
      val updated = ((result as StructuralRunnerResult.Succeeded).data as StructuralOutcomeData.ExistingNotePinUpdated).updatedNote
      assertTrue(updated.isPinned)
      assertEquals(4L, updated.revision)
    }

  private class FakeFolderRepository : FolderRepository {
    val folders = linkedMapOf<Long, Folder>()
    val requestedIds = mutableListOf<Set<Long>>()

    fun seedFolder(id: Long, name: String, isDeleted: Boolean = false): Folder =
      Folder(
        id = id,
        uid = "folder-$id",
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

    override suspend fun getFoldersByIds(ids: Set<Long>): Map<Long, FolderAvailability> {
      requestedIds.add(ids)
      return ids.associateWith { id ->
        val folder = folders[id]
        when {
          folder == null -> FolderAvailability.NotFound(id)
          folder.isDeleted -> FolderAvailability.InTrash(folder)
          else -> FolderAvailability.Active(folder)
        }
      }
    }

    override suspend fun createFolder(name: String): Folder = error("unused")

    override suspend fun renameFolder(folderId: Long, newName: String): Folder = error("unused")

    override suspend fun deleteFolder(folderId: Long, mode: FolderDeleteMode) = error("unused")

    override suspend fun restoreFolder(folderId: Long): RestoreFolderResult = error("unused")

    override suspend fun permanentlyDeleteFolder(folderId: Long, deleteLinkedNotes: Boolean) = error("unused")
  }

  private class FakeNoteRepository : NoteRepository {
    val notes = linkedMapOf<Long, Note>()
    var onBeforeConditionalPin: (suspend (Long) -> Unit)? = null

    fun seedNote(
      id: Long,
      title: String,
      document: NoteContentDocument = NoteContentDocument.empty(),
      folderId: Long? = null,
      revision: Long = 1L,
      updatedAt: Long = 1_000L,
      isPinned: Boolean = false,
      paperStyle: NotePaperStyle = NotePaperStyle.LINHAS,
    ): Note =
      Note(
        id = id,
        uid = "note-$id",
        folderId = folderId,
        title = title,
        contentState = NoteContentState.Valid(NoteContentPolicy.normalizeDocument(document)),
        plainTextPreview = document.blocks.joinToString("\n") { it.text },
        paperStyle = paperStyle,
        revision = revision,
        isPinned = isPinned,
        pinnedAt = if (isPinned) 500L else null,
        isDeleted = false,
        deletedAt = null,
        deletedWithFolder = false,
        createdAt = 100L,
        updatedAt = updatedAt,
      ).also { notes[id] = it }

    override fun observeAllActiveNotes(): Flow<List<Note>> = emptyFlow()

    override fun observePinnedActiveNotes(): Flow<List<Note>> = emptyFlow()

    override fun observeActiveNotesByFolder(folderId: Long): Flow<List<Note>> = emptyFlow()

    override fun observeDeletedNotes(): Flow<List<Note>> = emptyFlow()

    override fun observeNoteById(noteId: Long): Flow<Note?> = emptyFlow()

    override suspend fun getNoteById(noteId: Long): Note? = notes[noteId]

    override suspend fun createNote(
      title: String,
      content: NoteContentDocument,
      folderId: Long?,
      isPinned: Boolean,
      paperStyle: NotePaperStyle?,
      clearDraftId: String?,
    ): Note = error("unused")

    override suspend fun updateNote(
      noteId: Long,
      title: String,
      content: NoteContentDocument,
      folderId: Long?,
      paperStyle: NotePaperStyle?,
      expectedBaseRevision: Long?,
      clearDraftId: String?,
    ): Note = error("unused")

    override suspend fun updateNotePaperStyle(noteId: Long, paperStyle: NotePaperStyle): Note = error("unused")

    override suspend fun setNotePinned(noteId: Long, pinned: Boolean) = error("unused")

    override suspend fun setNotePinnedIfRevision(
      noteId: Long,
      pinned: Boolean,
      expectedBaseRevision: Long,
    ): PinNoteResult {
      onBeforeConditionalPin?.invoke(noteId)
      val current = notes[noteId] ?: throw NoteNotFoundException(noteId)
      if (current.isDeleted) throw NoteDeletedException(noteId)
      if (current.revision != expectedBaseRevision) {
        return PinNoteResult.Conflict(expectedBaseRevision, current)
      }
      if (current.isPinned == pinned) {
        return PinNoteResult.Unchanged(current)
      }
      val updated =
        current.copy(
          isPinned = pinned,
          pinnedAt = if (pinned) 9_999L else null,
          revision = current.revision + 1L,
          updatedAt = current.updatedAt + 1L,
        )
      notes[noteId] = updated
      return PinNoteResult.Updated(updated)
    }

    override suspend fun moveNoteToFolder(noteId: Long, folderId: Long?) = error("unused")

    override suspend fun softDeleteNote(noteId: Long) = error("unused")

    override suspend fun restoreNote(noteId: Long): Note = error("unused")

    override suspend fun permanentlyDeleteNote(noteId: Long) = error("unused")
  }

  private class RecordingDraftRepository(
    private val clock: ClockProvider,
  ) : NoteRecoveryDraftRepository {
    val drafts = linkedMapOf<String, NoteRecoveryDraft>()
    val savedInputs = mutableListOf<NoteRecoveryDraftInput>()
    val deletedDraftIds = mutableListOf<String>()
    var onSaveHook: (suspend (NoteRecoveryDraftInput) -> SaveCheckpointResult)? = null
    var onDeleteHook: (suspend (String) -> Unit)? = null
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
      savedInputs.add(input)
      onSaveHook?.let { return it.invoke(input) }
      val draft = input.toDraft(clock.nowMillis())
      drafts[input.draftId] = draft
      return SaveCheckpointResult.Saved(draft)
    }

    override suspend fun replaceCheckpointWithNewNote(
      oldDraftId: String,
      expectedOldSessionId: String,
      expectedOldTargetNoteId: Long,
      newInput: NoteRecoveryDraftInput,
    ): ReplaceCheckpointWithNewNoteResult {
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
      deletedDraftIds.add(draftId)
      drafts.remove(draftId)
      onDeleteHook?.invoke(draftId)
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
