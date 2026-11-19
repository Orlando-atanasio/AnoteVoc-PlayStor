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
import com.example.domain.repository.NoteRecoveryDraftRepository
import com.example.domain.repository.NoteRepository
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

    val runner =
      SessionStructuralRunner(
        noteRepository = noteRepo,
        folderRepository = folderRepo,
        draftRepository = draftRepo,
        activeSessionRegistry = registry,
        handoffTracker = handoffTracker,
        replacementReconciler = reconciler,
        sessionIdGenerator = { "new-sess-unused" },
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

    override suspend fun moveNoteToFolder(noteId: Long, folderId: Long?) = error("unused")

    override suspend fun softDeleteNote(noteId: Long) = error("unused")

    override suspend fun restoreNote(noteId: Long): Note = error("unused")

    override suspend fun permanentlyDeleteNote(noteId: Long) = error("unused")
  }

  private class RecordingDraftRepository(
    private val clock: ClockProvider,
  ) : NoteRecoveryDraftRepository {
    val savedInputs = mutableListOf<NoteRecoveryDraftInput>()
    val deletedDraftIds = mutableListOf<String>()
    var onSaveHook: (suspend (NoteRecoveryDraftInput) -> SaveCheckpointResult)? = null
    var onDeleteHook: (suspend (String) -> Unit)? = null

    override fun observeAllCheckpoints(): Flow<List<NoteRecoveryDraft>> = emptyFlow()

    override suspend fun getAllCheckpoints(): List<NoteRecoveryDraft> = emptyList()

    override suspend fun getCheckpointById(draftId: String): NoteRecoveryDraft? = null

    override suspend fun getCheckpointBySessionId(sessionId: String): NoteRecoveryDraft? = null

    override suspend fun getCheckpointsForNote(noteId: Long): List<NoteRecoveryDraft> = emptyList()

    override suspend fun saveCheckpoint(input: NoteRecoveryDraftInput): SaveCheckpointResult {
      savedInputs.add(input)
      onSaveHook?.let { return it.invoke(input) }
      val draft =
        NoteRecoveryDraft(
          draftId = input.draftId,
          targetNoteId = input.targetNoteId,
          sessionId = input.sessionId,
          folderId = input.folderId,
          initialFolderId = input.initialFolderId,
          fallbackSourceFolderId = input.fallbackSourceFolderId,
          isPinned = input.isPinned,
          title = input.title,
          contentState = NoteContentState.Valid(NoteContentPolicy.normalizeDocument(input.content)),
          paperStyle = input.paperStyle,
          initialPaperStyle = input.initialPaperStyle,
          baseRevision = input.baseRevision,
          checkpointRevision = input.checkpointRevision,
          baseNoteUpdatedAt = input.baseNoteUpdatedAt,
          checkpointAt = clock.nowMillis(),
        )
      return SaveCheckpointResult.Saved(draft)
    }

    override suspend fun replaceCheckpointWithNewNote(
      oldDraftId: String,
      expectedOldSessionId: String,
      expectedOldTargetNoteId: Long,
      newInput: NoteRecoveryDraftInput,
    ): ReplaceCheckpointWithNewNoteResult = error("unused")

    override suspend fun deleteCheckpoint(draftId: String) {
      deletedDraftIds.add(draftId)
      onDeleteHook?.invoke(draftId)
    }

    override suspend fun deleteCheckpointsForNote(noteId: Long) = error("unused")
  }
}
