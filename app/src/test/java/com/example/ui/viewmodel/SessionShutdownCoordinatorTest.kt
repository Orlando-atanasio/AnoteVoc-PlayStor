package com.example.ui.viewmodel

import com.example.core.time.ClockProvider
import com.example.domain.editor.FolderAvailability
import com.example.domain.editor.NoteEditorBaseline
import com.example.domain.editor.NoteEditorEditableSnapshot
import com.example.domain.editor.NoteEditorEngine
import com.example.domain.editor.NoteEditorSessionEvaluator
import com.example.domain.editor.NoteEditorSessionIdentity
import com.example.domain.editor.ResolvedDraftFolderContext
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
import com.example.domain.repository.NoteRecoveryDraftRepository
import com.example.domain.repository.NoteRepository
import com.example.domain.repository.PinNoteResult
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionShutdownCoordinatorTest {

  private class ShutdownHarness {
    private val clockCounter = AtomicLong(1_700_300_000_000L)
    val clock =
      object : ClockProvider {
        override fun nowMillis(): Long = clockCounter.incrementAndGet()
      }

    val noteRepo = FakeNoteRepository()
    val draftRepo = FakeDraftRepository(clock)
    val registry = ActiveSessionRegistry()
    val handoffTracker = ReplacementHandoffTracker(clock)
    val reconciler =
      PendingReplacementReconciler(
        noteRepository = noteRepo,
        draftRepository = draftRepo,
        activeSessionRegistry = registry,
        handoffTracker = handoffTracker,
      )
    val mailbox =
      CoalescingCommandMailbox(
        initialAcceptedMutationSequence = 2L,
        initialAppliedMutationSequence = 2L,
      )
    val shutdownCoordinator =
      SessionShutdownCoordinator(
        mailbox = mailbox,
        activeSessionRegistry = registry,
        handoffTracker = handoffTracker,
        replacementReconciler = reconciler,
      )

    fun setupPendingTransfer(
      oldSessionId: String,
      newSessionId: String,
      noteId: Long = 50L,
      title: String = "Rascunho pendente",
      persistOldDraft: Boolean = true,
      persistNewDraft: Boolean = false,
    ): PendingFixture {
      val oldIdentity =
        NoteEditorSessionIdentity(
          draftId = NoteRecoveryDraftKeys.forExistingNote(noteId, oldSessionId),
          sessionId = oldSessionId,
          targetNoteId = noteId,
        )
      val newIdentity =
        NoteEditorSessionIdentity(
          draftId = NoteRecoveryDraftKeys.forNewNote(newSessionId),
          sessionId = newSessionId,
          targetNoteId = null,
        )
      val doc = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "b1", text = "Conteúdo")))
      val oldLease = (registry.reserve(oldIdentity) as SessionReservationResult.Reserved).lease

      if (persistOldDraft) {
        draftRepo.drafts[oldIdentity.draftId] =
          NoteRecoveryDraft(
            draftId = oldIdentity.draftId,
            targetNoteId = noteId,
            sessionId = oldSessionId,
            folderId = null,
            initialFolderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = title,
            contentState = NoteContentState.Valid(doc),
            paperStyle = NotePaperStyle.LINHAS,
            initialPaperStyle = null,
            baseRevision = 1L,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = 1_000L,
            checkpointAt = 2_000L,
          )
      }

      val newPayload =
        CheckpointSavePayload.fromIdentity(
          identity = newIdentity,
          folderId = null,
          initialFolderId = null,
          fallbackSourceFolderId = null,
          isPinned = false,
          title = title,
          content = doc,
          paperStyle = NotePaperStyle.LINHAS,
          initialPaperStyle = NotePaperStyle.LINHAS,
          baseRevision = 0L,
          baseNoteUpdatedAt = null,
        )

      if (persistNewDraft) {
        draftRepo.drafts[newIdentity.draftId] =
          NoteRecoveryDraft(
            draftId = newIdentity.draftId,
            targetNoteId = null,
            sessionId = newSessionId,
            folderId = null,
            initialFolderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = title,
            contentState = NoteContentState.Valid(doc),
            paperStyle = NotePaperStyle.LINHAS,
            initialPaperStyle = NotePaperStyle.LINHAS,
            baseRevision = 0L,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = null,
            checkpointAt = 3_000L,
          )
      }

      val transferToken =
        (registry.reserveIdentityTransfer(
          currentLease = oldLease,
          targetIdentity = RegisteredSessionIdentity.fromSessionIdentity(newIdentity),
        ) as ReserveTransferResult.Reserved).transferToken

      val handoff =
        handoffTracker.registerPending(
          oldIdentity = oldIdentity,
          newPayload = newPayload,
          newDraftRevision = 1L,
        )

      val context =
        UncommittedReplacementContext(
          pendingState =
            PendingReplacementState(
              oldDraftId = oldIdentity.draftId,
              expectedOldSessionId = oldIdentity.sessionId,
              expectedOldTargetNoteId = noteId,
              oldIdentity = oldIdentity,
              newPayload = newPayload,
              newDraftRevision = 1L,
            ),
          transferToken = transferToken,
          handoffId = handoff.handoffId,
          targetBaseline =
            NoteEditorBaseline.NewNote(
              title = "",
              normalizedContent = NoteEditorSessionEvaluator.canonicalizeDocument(NoteContentDocument.empty()),
              paperStyle = NotePaperStyle.LINHAS,
              staticFolderId = null,
              initialIsPinned = false,
            ),
        )

      val executor =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepo,
          noteRepository = noteRepo,
          initialIdentity = oldIdentity,
          initialPresence = CheckpointPresence.PRESENT,
          initialConfirmedRevision = 1L,
          initialHighestAllocatedRevision = 1L,
          initialLastCheckpointAt = 2_000L,
        )

      val rt =
        SessionRuntimeState(
          phase = NoteEditorSessionPhase.EDITING,
          lease = oldLease,
          identity = oldIdentity,
          baseline =
            NoteEditorBaseline.ExistingNote(
              noteId = noteId,
              baseRevision = 1L,
              baseNoteUpdatedAt = 1_000L,
              title = "Nota DB",
              normalizedContent = NoteEditorSessionEvaluator.canonicalizeDocument(NoteContentDocument.empty()),
              paperStyle = NotePaperStyle.LINHAS,
              staticFolderId = null,
              currentDbIsPinned = false,
            ),
          snapshot =
            NoteEditorEditableSnapshot(
              title = title,
              document = doc,
              effectiveFolderId = null,
              fallbackSourceFolderId = null,
              paperStyle = NotePaperStyle.LINHAS,
              isPinned = false,
            ),
          folderContext = ResolvedDraftFolderContext(effectiveTarget = FolderAvailability.Root),
          effectiveFolderName = null,
          activeConflict = null,
          persistedInitialFolderId = null,
          persistedInitialPaperStyle = null,
          knownFolderAvailability = mutableMapOf(),
          stateGeneration = 2L,
          lastSyncedGeneration = 2L,
          lastCheckpointAt = 2_000L,
          appliedMutationSequence = 2L,
          autosaveStatus = AutosaveStatus.HeldPendingResolution,
          errorMessage = null,
          isConsolidating = false,
          isSessionTerminated = false,
          needsInitialPersistOnOpen = false,
        )
      val eng = NoteEditorEngine(initialDocument = doc)
      val uncommitted = AtomicReference<UncommittedReplacementContext?>(context)
      val latestCommitted = AtomicReference<SessionLeaseToken?>(oldLease)

      return PendingFixture(
        oldIdentity = oldIdentity,
        newIdentity = newIdentity,
        oldLease = oldLease,
        transferToken = transferToken,
        executor = executor,
        rt = rt,
        eng = eng,
        uncommittedReplacement = uncommitted,
        latestCommittedLease = latestCommitted,
      )
    }

    fun publishState(rt: SessionRuntimeState, eng: NoteEditorEngine): NoteEditorUiState =
      mailbox.publishState {
        rt.toPublication(
          evaluation = rt.evaluate(),
          editorState = eng.state,
          hasInFlightPersistence = false,
        )
      }
  }

  private data class PendingFixture(
    val oldIdentity: NoteEditorSessionIdentity,
    val newIdentity: NoteEditorSessionIdentity,
    val oldLease: SessionLeaseToken,
    val transferToken: IdentityTransferToken,
    val executor: SingleFlightRoomPersistenceExecutor,
    val rt: SessionRuntimeState,
    val eng: NoteEditorEngine,
    val uncommittedReplacement: AtomicReference<UncommittedReplacementContext?>,
    val latestCommittedLease: AtomicReference<SessionLeaseToken?>,
  )

  @Test
  fun gracefulClose_whenReconcilerReturnsPromotedToNew_updatesLatestCommittedLeaseAndReleasesNewLeaseOnTerminalCleanup() =
    runTest {
      val h = ShutdownHarness()
      val f =
        h.setupPendingTransfer(
          oldSessionId = "shutdown-prom-old",
          newSessionId = "shutdown-prom-new",
          persistOldDraft = false,
          persistNewDraft = true,
        )
      val closeAck = CompletableDeferred<SessionCommandOutcome>()

      h.shutdownCoordinator.handleGracefulCloseSession(
        rt = f.rt,
        eng = f.eng,
        executor = f.executor,
        inFlightOpProvider = { null },
        clearInFlightOp = {},
        uncommittedReplacement = f.uncommittedReplacement,
        latestCommittedLease = f.latestCommittedLease,
        cancelDebounce = {},
        drainCompletion = {},
        publishState = h::publishState,
        failFlushWaiters = { _, _, _ -> },
        ackDeferred = closeAck,
      )

      assertEquals(SessionCommandOutcome.Completed, closeAck.await())
      assertNull(f.uncommittedReplacement.get())
      assertEquals(f.newIdentity, f.rt.identity)
      assertEquals(f.transferToken.targetLease, f.rt.lease)
      assertNotEquals(f.oldLease, f.rt.lease)
      assertEquals(f.rt.lease, f.latestCommittedLease.get())
      assertFalse(h.registry.isLeaseActive(f.oldLease))
      assertTrue(h.registry.isLeaseActive(f.rt.lease))

      h.shutdownCoordinator.performTerminalShutdownCleanup(
        rt = f.rt,
        eng = f.eng,
        executor = f.executor,
        abortRequested = false,
        inFlightOpProvider = { null },
        clearInFlightOp = {},
        pendingBarrierProvider = { null },
        clearPendingBarrier = {},
        uncommittedReplacement = f.uncommittedReplacement,
        latestCommittedLease = f.latestCommittedLease,
        cancelDebounce = {},
        publishState = h::publishState,
        failFlushWaiters = { _, _, _ -> },
      )

      assertFalse(h.registry.isLeaseActive(f.transferToken.targetLease))
      assertFalse(h.registry.isLeaseActive(f.oldLease))
      assertFalse(h.registry.isSessionReserved("shutdown-prom-new"))
      assertFalse(h.registry.isSessionReserved("shutdown-prom-old"))
      assertTrue(h.registry.snapshot().reservedSessionIds.isEmpty())
      assertTrue(h.registry.snapshot().reservedDraftIds.isEmpty())
      assertEquals(0, h.registry.snapshot().pendingTransferCount)
    }

  @Test
  fun gracefulClose_whenReconcilerReturnsRevertedToOld_updatesLatestCommittedLeaseAndReleasesPreservedLeaseOnTerminalCleanup() =
    runTest {
      val h = ShutdownHarness()
      val f =
        h.setupPendingTransfer(
          oldSessionId = "shutdown-rev-old",
          newSessionId = "shutdown-rev-new",
          persistOldDraft = true,
          persistNewDraft = false,
        )
      // Simula cenário em que latestCommittedLease estava dessincronizado (ex.: nulo) antes do fechamento.
      f.latestCommittedLease.set(null)
      val closeAck = CompletableDeferred<SessionCommandOutcome>()

      h.shutdownCoordinator.handleGracefulCloseSession(
        rt = f.rt,
        eng = f.eng,
        executor = f.executor,
        inFlightOpProvider = { null },
        clearInFlightOp = {},
        uncommittedReplacement = f.uncommittedReplacement,
        latestCommittedLease = f.latestCommittedLease,
        cancelDebounce = {},
        drainCompletion = {},
        publishState = h::publishState,
        failFlushWaiters = { _, _, _ -> },
        ackDeferred = closeAck,
      )

      assertEquals(SessionCommandOutcome.Completed, closeAck.await())
      assertNull(f.uncommittedReplacement.get())
      assertEquals(f.oldIdentity, f.rt.identity)
      assertEquals(f.oldLease, f.rt.lease)
      assertEquals(f.oldLease, f.latestCommittedLease.get())
      assertTrue(h.registry.isLeaseActive(f.oldLease))
      assertFalse(h.registry.isSessionReserved("shutdown-rev-new"))

      h.shutdownCoordinator.performTerminalShutdownCleanup(
        rt = f.rt,
        eng = f.eng,
        executor = f.executor,
        abortRequested = false,
        inFlightOpProvider = { null },
        clearInFlightOp = {},
        pendingBarrierProvider = { null },
        clearPendingBarrier = {},
        uncommittedReplacement = f.uncommittedReplacement,
        latestCommittedLease = f.latestCommittedLease,
        cancelDebounce = {},
        publishState = h::publishState,
        failFlushWaiters = { _, _, _ -> },
      )

      assertFalse(h.registry.isLeaseActive(f.oldLease))
      assertTrue(h.registry.snapshot().reservedSessionIds.isEmpty())
      assertTrue(h.registry.snapshot().reservedDraftIds.isEmpty())
      assertEquals(0, h.registry.snapshot().pendingTransferCount)
    }

  @Test
  fun gracefulClose_whenReconcilerReturnsIndeterminateReadFailure_doesNotReplaceLeaseWithUnconfirmedIdentityAndPreservesPendingHandoff() =
    runTest {
      val h = ShutdownHarness()
      val f =
        h.setupPendingTransfer(
          oldSessionId = "shutdown-indet-old",
          newSessionId = "shutdown-indet-new",
          persistOldDraft = false,
          persistNewDraft = true,
        )
      h.draftRepo.onGetCheckpointHook = { throw IOException("Falha de leitura durante fechamento") }
      val closeAck = CompletableDeferred<SessionCommandOutcome>()

      h.shutdownCoordinator.handleGracefulCloseSession(
        rt = f.rt,
        eng = f.eng,
        executor = f.executor,
        inFlightOpProvider = { null },
        clearInFlightOp = {},
        uncommittedReplacement = f.uncommittedReplacement,
        latestCommittedLease = f.latestCommittedLease,
        cancelDebounce = {},
        drainCompletion = {},
        publishState = h::publishState,
        failFlushWaiters = { _, _, _ -> },
        ackDeferred = closeAck,
      )

      val outcome = closeAck.await()
      assertTrue(outcome is SessionCommandOutcome.Failed)
      assertEquals(f.oldLease, f.rt.lease)
      assertEquals(f.oldLease, f.latestCommittedLease.get())
      assertNotEquals(f.transferToken.targetLease, f.latestCommittedLease.get())
      assertEquals(f.oldIdentity, f.rt.identity)
      assertNotNull(f.uncommittedReplacement.get())
      assertEquals(
        ReplacementHandoffStatus.PENDING,
        h.handoffTracker.findActiveCandidate(f.oldIdentity.draftId)?.status,
      )

      h.shutdownCoordinator.performTerminalShutdownCleanup(
        rt = f.rt,
        eng = f.eng,
        executor = f.executor,
        abortRequested = false,
        inFlightOpProvider = { null },
        clearInFlightOp = {},
        pendingBarrierProvider = { null },
        clearPendingBarrier = {},
        uncommittedReplacement = f.uncommittedReplacement,
        latestCommittedLease = f.latestCommittedLease,
        cancelDebounce = {},
        publishState = h::publishState,
        failFlushWaiters = { _, _, _ -> },
      )

      assertEquals(f.oldLease, f.rt.lease)
      assertEquals(f.oldLease, f.latestCommittedLease.get())
      assertNotNull(f.uncommittedReplacement.get())
      assertEquals(
        ReplacementHandoffStatus.PENDING,
        h.handoffTracker.findActiveCandidate(f.oldIdentity.draftId)?.status,
      )
      assertFalse(h.registry.isLeaseActive(f.oldLease))
      assertFalse(h.registry.isLeaseActive(f.transferToken.targetLease))
      assertTrue(h.registry.snapshot().reservedSessionIds.isEmpty())
      assertTrue(h.registry.snapshot().reservedDraftIds.isEmpty())
    }

  private class FakeNoteRepository : NoteRepository {
    override fun observeAllActiveNotes(): Flow<List<Note>> = emptyFlow()

    override fun observePinnedActiveNotes(): Flow<List<Note>> = emptyFlow()

    override fun observeActiveNotesByFolder(folderId: Long): Flow<List<Note>> = emptyFlow()

    override fun observeDeletedNotes(): Flow<List<Note>> = emptyFlow()

    override fun observeNoteById(noteId: Long): Flow<Note?> = emptyFlow()

    override suspend fun getNoteById(noteId: Long): Note? = null

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
    ): PinNoteResult = error("unused")

    override suspend fun moveNoteToFolder(noteId: Long, folderId: Long?) = error("unused")

    override suspend fun softDeleteNote(noteId: Long) = error("unused")

    override suspend fun restoreNote(noteId: Long): Note = error("unused")

    override suspend fun permanentlyDeleteNote(noteId: Long) = error("unused")
  }

  private class FakeDraftRepository(
    private val clock: ClockProvider,
  ) : NoteRecoveryDraftRepository {
    val drafts = linkedMapOf<String, NoteRecoveryDraft>()
    var onGetCheckpointHook: (suspend (String) -> Unit)? = null

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

    override suspend fun saveCheckpoint(input: NoteRecoveryDraftInput): SaveCheckpointResult = error("unused")

    override suspend fun replaceCheckpointWithNewNote(
      oldDraftId: String,
      expectedOldSessionId: String,
      expectedOldTargetNoteId: Long,
      newInput: NoteRecoveryDraftInput,
    ): ReplaceCheckpointWithNewNoteResult {
      val existingNew = drafts[newInput.draftId]
      val existingOld = drafts[oldDraftId]
      if (existingOld == null && existingNew != null) {
        return ReplaceCheckpointWithNewNoteResult.AlreadyReplaced(existingNew)
      }
      drafts.remove(oldDraftId)
      val inserted =
        NoteRecoveryDraft(
          draftId = newInput.draftId,
          targetNoteId = newInput.targetNoteId,
          sessionId = newInput.sessionId,
          folderId = newInput.folderId,
          initialFolderId = newInput.initialFolderId,
          fallbackSourceFolderId = newInput.fallbackSourceFolderId,
          isPinned = newInput.isPinned,
          title = newInput.title,
          contentState = NoteContentState.Valid(NoteContentPolicy.normalizeDocument(newInput.content)),
          paperStyle = newInput.paperStyle,
          initialPaperStyle = newInput.initialPaperStyle,
          baseRevision = newInput.baseRevision,
          checkpointRevision = newInput.checkpointRevision,
          baseNoteUpdatedAt = newInput.baseNoteUpdatedAt,
          checkpointAt = clock.nowMillis(),
        )
      drafts[inserted.draftId] = inserted
      return ReplaceCheckpointWithNewNoteResult.Replaced(inserted)
    }

    override suspend fun deleteCheckpoint(draftId: String) {
      drafts.remove(draftId)
    }

    override suspend fun deleteCheckpointsForNote(noteId: Long) {
      drafts.entries.removeIf { it.value.targetNoteId == noteId }
    }
  }
}
