package com.example.ui.viewmodel

import com.example.domain.editor.NoteEditorEngine
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin

internal class SessionShutdownCoordinator(
  private val mailbox: CoalescingCommandMailbox,
  private val activeSessionRegistry: ActiveSessionRegistry,
  private val handoffTracker: ReplacementHandoffTracker,
  private val replacementReconciler: PendingReplacementReconciler,
) {
  suspend fun handleGracefulCloseSession(
    rt: SessionRuntimeState,
    eng: NoteEditorEngine,
    executor: SingleFlightRoomPersistenceExecutor?,
    inFlightOpProvider: () -> InFlightOperation?,
    clearInFlightOp: () -> Unit,
    uncommittedReplacement: AtomicReference<UncommittedReplacementContext?>,
    cancelDebounce: () -> Unit,
    drainCompletion: suspend (PersistenceCompletion) -> Unit,
    publishState: (SessionRuntimeState, NoteEditorEngine) -> NoteEditorUiState,
    failFlushWaiters: (Long, String, Throwable?) -> Unit,
    ackDeferred: CompletableDeferred<SessionCommandOutcome>?,
  ) {
    cancelDebounce()
    mailbox.close()

    val deferredCommands = mutableListOf<QueuedSessionCommand>()
    val currentOp = inFlightOpProvider()
    if (currentOp != null) {
      currentOp.job.join()
      clearInFlightOp()
    }

    while (true) {
      val item = mailbox.pollNext() ?: break
      when (item) {
        is MailboxItem.Completion -> drainCompletion(item.completion)
        is MailboxItem.Command -> deferredCommands.add(item.queued)
      }
    }

    val pendingCtx = uncommittedReplacement.get()
    if (
      executor != null &&
        (pendingCtx != null || executor.snapshot().hasPendingReplacement)
    ) {
      if (pendingCtx != null) {
        val recResult =
          replacementReconciler.reconcile(
            executor = executor,
            currentLease = rt.lease,
            context = pendingCtx,
            forShutdown = true,
          )
        when (recResult) {
          is ReplacementReconciliationResult.PromotedToNew -> {
            uncommittedReplacement.set(null)
            rt.lease = recResult.newLease
            rt.identity = recResult.newIdentity
            rt.lastCheckpointAt = recResult.newDraft.checkpointAt
            rt.isSessionTerminated = true
            rt.autosaveStatus =
              AutosaveStatus.Saved(rt.stateGeneration, recResult.newDraft.checkpointAt)
            publishState(rt, eng)
            finalizeGracefulShutdown(
              ackDeferred = ackDeferred,
              outcome = SessionCommandOutcome.Completed,
              deferredCommands = deferredCommands,
              failFlushWaiters = failFlushWaiters,
            )
            return
          }
          is ReplacementReconciliationResult.RevertedToOld -> {
            uncommittedReplacement.set(null)
            rt.lease = recResult.preservedLease
            rt.isSessionTerminated = true
            publishState(rt, eng)
            finalizeGracefulShutdown(
              ackDeferred = ackDeferred,
              outcome = SessionCommandOutcome.Completed,
              deferredCommands = deferredCommands,
              failFlushWaiters = failFlushWaiters,
            )
            return
          }
          is ReplacementReconciliationResult.NeitherPresent,
          is ReplacementReconciliationResult.DivergentAnomaly -> {
            uncommittedReplacement.set(null)
            val msg =
              when (recResult) {
                is ReplacementReconciliationResult.NeitherPresent -> recResult.message
                is ReplacementReconciliationResult.DivergentAnomaly -> recResult.message
                else -> "Anomalia na reconciliação."
              }
            rt.errorMessage = msg
            rt.autosaveStatus = AutosaveStatus.Error(msg, isTerminal = true)
            rt.isSessionTerminated = true
            publishState(rt, eng)
            finalizeGracefulShutdown(
              ackDeferred = ackDeferred,
              outcome = SessionCommandOutcome.Failed(msg),
              deferredCommands = deferredCommands,
              failFlushWaiters = failFlushWaiters,
            )
            return
          }
          is ReplacementReconciliationResult.IndeterminateReadFailure -> {
            // Estado F: preserva tracker PENDING e libera lease somente no shutdown final.
            rt.errorMessage = recResult.message
            rt.autosaveStatus = AutosaveStatus.Error(recResult.message, isTerminal = true)
            rt.isSessionTerminated = true
            publishState(rt, eng)
            finalizeGracefulShutdown(
              ackDeferred = ackDeferred,
              outcome = SessionCommandOutcome.Failed(recResult.message, recResult.cause),
              deferredCommands = deferredCommands,
              failFlushWaiters = failFlushWaiters,
            )
            return
          }
        }
      }
    }

    if (executor != null && rt.activeConflict == null) {
      val eval = rt.evaluate()
      val execSnap = executor.snapshot()
      if (!eval.needsDraftRetention && execSnap.checkpointPresence != CheckpointPresence.ABSENT) {
        when (val delRes = executor.deleteCheckpoint(rt.identity.draftId)) {
          is DeleteCheckpointExecutionResult.Deleted -> {
            handoffTracker.invalidateCandidateDraft(rt.identity.draftId)
            rt.lastSyncedGeneration = rt.stateGeneration
            rt.lastCheckpointAt = null
            rt.autosaveStatus = AutosaveStatus.Idle
          }
          is DeleteCheckpointExecutionResult.Failed -> {
            val msg = delRes.cause.message ?: "Falha ao limpar checkpoint no fechamento."
            rt.errorMessage = msg
            rt.autosaveStatus = AutosaveStatus.Error(msg, isTerminal = true)
            rt.isSessionTerminated = true
            publishState(rt, eng)
            finalizeGracefulShutdown(
              ackDeferred = ackDeferred,
              outcome = SessionCommandOutcome.Failed(msg, delRes.cause),
              deferredCommands = deferredCommands,
              failFlushWaiters = failFlushWaiters,
            )
            return
          }
        }
      } else if (
        eval.needsDraftRetention &&
          (rt.stateGeneration > rt.lastSyncedGeneration ||
            rt.needsInitialPersistOnOpen ||
            execSnap.checkpointPresence != CheckpointPresence.PRESENT ||
            rt.autosaveStatus is AutosaveStatus.Error)
      ) {
        when (val saveRes = executor.saveCheckpoint(rt.buildCheckpointPayload())) {
          is SaveCheckpointExecutionResult.Saved -> {
            handoffTracker.invalidateCandidateDraft(rt.identity.draftId)
            rt.needsInitialPersistOnOpen = false
            rt.lastSyncedGeneration = rt.stateGeneration
            rt.lastCheckpointAt = saveRes.draft.checkpointAt
            rt.errorMessage = null
            rt.autosaveStatus =
              AutosaveStatus.Saved(rt.stateGeneration, saveRes.draft.checkpointAt)
          }
          is SaveCheckpointExecutionResult.IgnoredStaleRevision -> {
            rt.needsInitialPersistOnOpen = false
            rt.lastSyncedGeneration = rt.stateGeneration
            rt.errorMessage = null
            rt.autosaveStatus = AutosaveStatus.Saved(rt.stateGeneration, rt.lastCheckpointAt)
          }
          is SaveCheckpointExecutionResult.RevisionOverflow -> {
            val msg = "Estouro de revisão no fechamento."
            rt.errorMessage = msg
            rt.autosaveStatus = AutosaveStatus.Error(msg, isTerminal = true)
            rt.isSessionTerminated = true
            publishState(rt, eng)
            finalizeGracefulShutdown(
              ackDeferred = ackDeferred,
              outcome = SessionCommandOutcome.Failed(msg),
              deferredCommands = deferredCommands,
              failFlushWaiters = failFlushWaiters,
            )
            return
          }
          is SaveCheckpointExecutionResult.Failed -> {
            val msg = saveRes.cause.message ?: "Falha ao salvar rascunho no fechamento."
            rt.errorMessage = msg
            rt.autosaveStatus = AutosaveStatus.Error(msg, isTerminal = true)
            rt.isSessionTerminated = true
            publishState(rt, eng)
            finalizeGracefulShutdown(
              ackDeferred = ackDeferred,
              outcome = SessionCommandOutcome.Failed(msg, saveRes.cause),
              deferredCommands = deferredCommands,
              failFlushWaiters = failFlushWaiters,
            )
            return
          }
        }
      }
    }

    rt.isSessionTerminated = true
    publishState(rt, eng)
    finalizeGracefulShutdown(
      ackDeferred = ackDeferred,
      outcome = SessionCommandOutcome.Completed,
      deferredCommands = deferredCommands,
      failFlushWaiters = failFlushWaiters,
    )
  }

  suspend fun performTerminalShutdownCleanup(
    rt: SessionRuntimeState?,
    eng: NoteEditorEngine?,
    executor: SingleFlightRoomPersistenceExecutor?,
    abortRequested: Boolean,
    inFlightOpProvider: () -> InFlightOperation?,
    clearInFlightOp: () -> Unit,
    pendingBarrierProvider: () -> PendingStructuralBarrier?,
    clearPendingBarrier: () -> Unit,
    uncommittedReplacement: AtomicReference<UncommittedReplacementContext?>,
    latestCommittedLease: AtomicReference<SessionLeaseToken?>,
    cancelDebounce: () -> Unit,
    publishState: (SessionRuntimeState, NoteEditorEngine) -> NoteEditorUiState,
    failFlushWaiters: (Long, String, Throwable?) -> Unit,
  ) {
    cancelDebounce()

    val currentOp = inFlightOpProvider()
    if (currentOp != null) {
      if (abortRequested) {
        currentOp.job.cancelAndJoin()
      } else {
        currentOp.job.join()
      }
      clearInFlightOp()
    }

    if (rt != null) {
      latestCommittedLease.get()?.let { committedLease ->
        rt.lease = committedLease
      }
    }

    val pendingCtx = uncommittedReplacement.get()
    if (
      rt != null &&
        executor != null &&
        (pendingCtx != null || executor.snapshot().hasPendingReplacement)
    ) {
      if (pendingCtx != null) {
        val rec =
          replacementReconciler.reconcile(
            executor = executor,
            currentLease = rt.lease,
            context = pendingCtx,
            forShutdown = true,
          )
        when (rec) {
          is ReplacementReconciliationResult.PromotedToNew -> {
            rt.lease = rec.newLease
            latestCommittedLease.set(rec.newLease)
            uncommittedReplacement.set(null)
          }
          is ReplacementReconciliationResult.RevertedToOld -> {
            rt.lease = rec.preservedLease
            latestCommittedLease.set(rec.preservedLease)
            uncommittedReplacement.set(null)
          }
          is ReplacementReconciliationResult.NeitherPresent,
          is ReplacementReconciliationResult.DivergentAnomaly -> {
            uncommittedReplacement.set(null)
          }
          is ReplacementReconciliationResult.IndeterminateReadFailure -> {
            // Mantém handoff PENDING no tracker para descoberta posterior.
          }
        }
      }
    }

    val barrier = pendingBarrierProvider()
    if (barrier != null) {
      barrier.queuedCommand.command.ackDeferred?.complete(SessionCommandOutcome.Cancelled)
      clearPendingBarrier()
      mailbox.closeAndReleaseBarrier(barrier.token)
    } else {
      mailbox.close()
    }

    drainLeftoverWithCancelled()
    failFlushWaiters(Long.MAX_VALUE, "Sessão encerrada.", null)

    if (rt != null) {
      latestCommittedLease.get()?.let { committedLease ->
        rt.lease = committedLease
      }
      rt.isConsolidating = false
      rt.isSessionTerminated = true
      if (eng != null) {
        publishState(rt, eng)
      }
      activeSessionRegistry.release(rt.lease)
    }
  }

  fun drainLeftoverWithRejected() {
    for (item in mailbox.drainPending()) {
      if (item is MailboxItem.Command) {
        item.queued.command.ackDeferred?.complete(
          SessionCommandOutcome.Rejected(SubmitRejectionReason.MAILBOX_CLOSED)
        )
      }
    }
  }

  fun drainLeftoverWithCancelled() {
    for (item in mailbox.drainPending()) {
      if (item is MailboxItem.Command) {
        item.queued.command.ackDeferred?.complete(SessionCommandOutcome.Cancelled)
      }
    }
  }

  private fun finalizeGracefulShutdown(
    ackDeferred: CompletableDeferred<SessionCommandOutcome>?,
    outcome: SessionCommandOutcome,
    deferredCommands: List<QueuedSessionCommand>,
    failFlushWaiters: (Long, String, Throwable?) -> Unit,
  ) {
    mailbox.close()
    for (queued in deferredCommands) {
      val cmd = queued.command
      if (cmd is SessionCommand.CloseSession) {
        cmd.ackDeferred?.complete(outcome)
      } else {
        cmd.ackDeferred?.complete(SessionCommandOutcome.Cancelled)
      }
    }
    for (item in mailbox.drainPending()) {
      if (item is MailboxItem.Command) {
        val cmd = item.queued.command
        if (cmd is SessionCommand.CloseSession) {
          cmd.ackDeferred?.complete(outcome)
        } else {
          cmd.ackDeferred?.complete(SessionCommandOutcome.Cancelled)
        }
      }
    }
    failFlushWaiters(Long.MAX_VALUE, "Sessão encerrada.", null)
    ackDeferred?.complete(outcome)
  }
}
