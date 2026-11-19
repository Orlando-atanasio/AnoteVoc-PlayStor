package com.example.ui.viewmodel

import androidx.annotation.VisibleForTesting
import com.example.core.coroutines.DispatcherProvider
import com.example.core.time.ClockProvider
import com.example.domain.editor.ConflictResolutionOption
import com.example.domain.editor.FolderAvailability
import com.example.domain.editor.NoteEditorBaseline
import com.example.domain.editor.NoteEditorEditableSnapshot
import com.example.domain.editor.NoteEditorEngine
import com.example.domain.editor.NoteEditorOpenRequest
import com.example.domain.editor.NoteEditorSessionEvaluator
import com.example.domain.editor.NoteEditorSessionIdentity
import com.example.domain.editor.ResolvedDraftFolderContext
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NoteContentState
import com.example.domain.model.NoteRecoveryDraft
import com.example.domain.repository.FolderRepository
import com.example.domain.repository.NoteRecoveryDraftRepository
import com.example.domain.repository.NoteRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface EditorSessionCoordinator {
  val uiState: StateFlow<NoteEditorUiState>

  fun start(): Job

  fun submit(command: SessionCommand): SubmitCommandResult

  suspend fun awaitInitialized(): NoteEditorUiState

  suspend fun closeAndAbort()
}

/**
 * Coordenador único da sessão do editor (Subetapa 4.3.3-D).
 *
 * Consome [CoalescingCommandMailbox.receiveNext] em loop único serializado, coordena debounce,
 * autosave, fallback por indisponibilidade de pasta, barreiras estruturais, resoluções de
 * conflito e encerramento seguro (`CloseSession` e `closeAndAbort()`).
 */
class NoteEditorSessionCoordinator(
  private val openRequest: NoteEditorOpenRequest,
  private val sessionOpenLoader: SessionOpenLoader,
  private val replacementReconciler: PendingReplacementReconciler,
  private val handoffTracker: ReplacementHandoffTracker,
  private val activeSessionRegistry: ActiveSessionRegistry,
  private val draftRepository: NoteRecoveryDraftRepository,
  private val noteRepository: NoteRepository,
  private val folderRepository: FolderRepository,
  private val dispatchers: DispatcherProvider,
  private val clock: ClockProvider,
  parentScope: CoroutineScope,
  private val autosaveDebounceMillis: Long = DEFAULT_AUTOSAVE_DEBOUNCE_MILLIS,
  private val sessionIdGenerator: () -> String = { UUID.randomUUID().toString() },
  private val engineBlockIdGenerator: () -> String = { UUID.randomUUID().toString() },
) : EditorSessionCoordinator {
  init {
    require(autosaveDebounceMillis >= 0L) {
      "autosaveDebounceMillis não pode ser negativo: $autosaveDebounceMillis"
    }
  }

  private val scope =
    CoroutineScope(
      parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job])
    )
  private val mailbox = CoalescingCommandMailbox(initialUiState = NoteEditorUiState.loading())
  private val structuralRunner =
    SessionStructuralRunner(
      noteRepository = noteRepository,
      folderRepository = folderRepository,
      draftRepository = draftRepository,
      activeSessionRegistry = activeSessionRegistry,
      handoffTracker = handoffTracker,
      replacementReconciler = replacementReconciler,
      sessionIdGenerator = sessionIdGenerator,
      onLeaseCommitted = { newLease -> latestCommittedLease.set(newLease) },
    )
  private val shutdownCoordinator =
    SessionShutdownCoordinator(
      mailbox = mailbox,
      activeSessionRegistry = activeSessionRegistry,
      handoffTracker = handoffTracker,
      replacementReconciler = replacementReconciler,
    )

  private val lifecycleLock = Any()
  private var abortRequested = false
  private var consumerJob: Job? = null

  private val initializedDeferred = CompletableDeferred<NoteEditorUiState>()
  private val terminatedDeferred = CompletableDeferred<Unit>()

  private var runtime: SessionRuntimeState? = null
  private var engine: NoteEditorEngine? = null
  @Volatile private var executor: SingleFlightRoomPersistenceExecutor? = null
  private val uncommittedReplacement = AtomicReference<UncommittedReplacementContext?>(null)
  private val latestCommittedLease = AtomicReference<SessionLeaseToken?>(null)

  private var nextOperationId: Long = 1L
  private var debounceJob: Job? = null
  private var inFlightOp: InFlightOperation? = null
  private var pendingBarrier: PendingStructuralBarrier? = null
  private val pendingFlushWaiters = mutableListOf<PendingFlushWaiter>()
  private var rescheduleAutosaveAfterInFlight = false
  private val structuralOutcomes = ConcurrentHashMap<Long, StructuralOutcomeData>()

  @VisibleForTesting
  @Volatile
  internal var transformStructuralResultForTest: ((StructuralRunnerResult) -> StructuralRunnerResult)? =
    null

  override val uiState: StateFlow<NoteEditorUiState> = mailbox.uiState

  override fun start(): Job {
    synchronized(lifecycleLock) {
      consumerJob?.let { return it }
      if (abortRequested) {
        mailbox.close()
        shutdownCoordinator.drainLeftoverWithCancelled()
        terminatedDeferred.complete(Unit)
        val cancelledJob = Job().apply { cancel() }
        consumerJob = cancelledJob
        return cancelledJob
      }
      val job = scope.launch { runSessionLoop() }
      consumerJob = job
      return job
    }
  }

  override fun submit(command: SessionCommand): SubmitCommandResult = mailbox.submit(command)

  override suspend fun awaitInitialized(): NoteEditorUiState = initializedDeferred.await()

  suspend fun awaitTerminated() {
    terminatedDeferred.await()
  }

  fun currentExecutorSnapshot(): PersistenceExecutorSnapshot? = executor?.snapshot()

  internal fun currentRuntimeSnapshot(): SessionRuntimeState? =
    runtime?.let { it.copy(knownFolderAvailability = it.knownFolderAvailability.toMutableMap()) }

  @VisibleForTesting
  internal fun latestCommittedLeaseForTest(): SessionLeaseToken? = latestCommittedLease.get()

  @VisibleForTesting
  internal fun hasUncommittedReplacementForTest(): Boolean = uncommittedReplacement.get() != null

  @VisibleForTesting
  internal fun hasPendingBarrierForTest(): Boolean = pendingBarrier != null

  @VisibleForTesting
  internal fun isMailboxBarrierActiveForTest(): Boolean = mailbox.barrierActive

  override suspend fun closeAndAbort() {
    val jobToCancel: Job?
    synchronized(lifecycleLock) {
      abortRequested = true
      mailbox.close()
      debounceJob?.cancel()
      debounceJob = null
      jobToCancel = consumerJob
    }

    if (jobToCancel != null) {
      jobToCancel.cancelAndJoin()
      terminatedDeferred.await()
    } else {
      withContext(NonCancellable) {
        shutdownCoordinator.drainLeftoverWithCancelled()
        if (!initializedDeferred.isCompleted) {
          initializedDeferred.complete(mailbox.uiState.value)
        }
        terminatedDeferred.complete(Unit)
      }
    }
    scope.cancel()
  }

  private suspend fun runSessionLoop() {
    try {
      val opened = initializeSession()
      if (!opened) return

      while (true) {
        val item = mailbox.receiveNext() ?: break
        when (item) {
          is MailboxItem.Command -> handleCommand(item.queued)
          is MailboxItem.Completion -> handleCompletion(item.completion)
        }
      }
    } finally {
      withContext(NonCancellable) {
        shutdownCoordinator.performTerminalShutdownCleanup(
          rt = runtime,
          eng = engine,
          executor = executor,
          abortRequested = abortRequested,
          inFlightOpProvider = { inFlightOp },
          clearInFlightOp = { inFlightOp = null },
          pendingBarrierProvider = { pendingBarrier },
          clearPendingBarrier = { pendingBarrier = null },
          uncommittedReplacement = uncommittedReplacement,
          latestCommittedLease = latestCommittedLease,
          cancelDebounce = ::cancelDebounce,
          publishState = { rtState, engState -> publishCurrentRuntimeState(rtState, engState) },
          failFlushWaiters = ::failFlushWaitersUpTo,
        )
        structuralOutcomes.clear()
        if (!initializedDeferred.isCompleted) {
          initializedDeferred.complete(mailbox.uiState.value)
        }
        scope.cancel()
        terminatedDeferred.complete(Unit)
      }
    }
  }

  private suspend fun initializeSession(): Boolean {
    val openResult =
      try {
        sessionOpenLoader.open(openRequest)
      } catch (c: CancellationException) {
        initializedDeferred.cancel(c)
        throw c
      }

    when (openResult) {
      is SessionOpenLoadResult.Blocked -> {
        val state =
          mailbox.publishState(
            candidateState =
              NoteEditorUiState.blocked(
                reason = openResult.reason,
                warnings = openResult.warnings,
              ).copy(isSessionTerminated = true),
            appliedMutationSequence = mailbox.appliedMutationSequence,
            hasInFlightWrite = false,
          )
        mailbox.close()
        shutdownCoordinator.drainLeftoverWithRejected()
        initializedDeferred.complete(state)
        return false
      }
      is SessionOpenLoadResult.Failed -> {
        val state =
          mailbox.publishState(
            candidateState =
              NoteEditorUiState.error(openResult.message).copy(isSessionTerminated = true),
            appliedMutationSequence = mailbox.appliedMutationSequence,
            hasInFlightWrite = false,
          )
        mailbox.close()
        shutdownCoordinator.drainLeftoverWithRejected()
        initializedDeferred.complete(state)
        return false
      }
      is SessionOpenLoadResult.Ready -> {
        val readyEval = openResult.evaluation
        val persistedDraft = openResult.persistedDraftSnapshot
        val needsInitialFallbackSave =
          when {
            persistedDraft == null -> readyEval.evaluation.needsDraftRetention
            persistedDraft.folderId != readyEval.initialSnapshot.effectiveFolderId ||
              persistedDraft.fallbackSourceFolderId !=
              readyEval.initialSnapshot.fallbackSourceFolderId -> true
            else -> false
          }

        val initialGen = if (needsInitialFallbackSave) 1L else 0L
        val initialEngine =
          NoteEditorEngine(
            initialDocument = readyEval.initialSnapshot.document,
            blockIdGenerator = engineBlockIdGenerator,
          )
        val exec =
          SingleFlightRoomPersistenceExecutor(
            draftRepository = draftRepository,
            noteRepository = noteRepository,
            initialIdentity = readyEval.identity,
            initialPresence = openResult.initialPresence,
            initialConfirmedRevision = openResult.initialConfirmedRevision,
            initialHighestAllocatedRevision = openResult.initialConfirmedRevision,
            initialLastCheckpointAt = openResult.initialLastCheckpointAt,
          )
        val rt =
          SessionRuntimeState(
            phase = NoteEditorSessionPhase.EDITING,
            lease = openResult.lease,
            identity = readyEval.identity,
            baseline = readyEval.baseline,
            snapshot = readyEval.initialSnapshot,
            folderContext = readyEval.folderContext,
            effectiveFolderName = openResult.effectiveFolderName,
            activeConflict = readyEval.activeConflict,
            persistedInitialFolderId = openResult.initialDraftFolderIdMetadata,
            persistedInitialPaperStyle = openResult.initialDraftPaperStyleMetadata,
            knownFolderAvailability = openResult.folderAvailabilityById.toMutableMap(),
            stateGeneration = initialGen,
            lastSyncedGeneration = 0L,
            lastCheckpointAt = openResult.initialLastCheckpointAt,
            appliedMutationSequence = mailbox.appliedMutationSequence,
            autosaveStatus =
              when {
                readyEval.activeConflict != null -> AutosaveStatus.HeldPendingResolution
                needsInitialFallbackSave -> AutosaveStatus.PendingDebounce
                readyEval.evaluation.needsDraftRetention ->
                  AutosaveStatus.Saved(
                    syncedGeneration = 0L,
                    checkpointAt = openResult.initialLastCheckpointAt,
                  )
                else -> AutosaveStatus.Idle
              },
            errorMessage = null,
            isConsolidating = false,
            isSessionTerminated = false,
            needsInitialPersistOnOpen = needsInitialFallbackSave,
          )
        runtime = rt
        engine = initialEngine
        executor = exec
        latestCommittedLease.set(openResult.lease)

        val published = publishCurrentRuntimeState(rt, initialEngine)
        if (needsInitialFallbackSave && rt.activeConflict == null) {
          scheduleDebounce(rt.stateGeneration)
        }
        initializedDeferred.complete(published)
        return true
      }
    }
  }

  private suspend fun handleCommand(queued: QueuedSessionCommand) {
    val rt = runtime ?: return
    val eng = engine ?: return
    val cmd = queued.command

    if (rt.isSessionTerminated) {
      cmd.ackDeferred?.complete(SessionCommandOutcome.Rejected(SubmitRejectionReason.MAILBOX_CLOSED))
      return
    }

    if (cmd.isPersistableMutation) {
      val seq = requireNotNull(queued.mutationSequence)
      rt.appliedMutationSequence = seq
      val outcome = SessionMutationApplier.applyEditableCommand(rt, eng, cmd)
      if (outcome.snapshotChanged) {
        rt.stateGeneration += 1L
        rt.errorMessage = null
        rt.needsInitialPersistOnOpen = false
        cancelDebounce()
        if (outcome.requestedFolderLookupId != null) {
          launchFolderLookup(outcome.requestedFolderLookupId)
        }
        onSnapshotOrRetentionUpdated(rt, eng, scheduleAutosave = true)
      } else {
        publishCurrentRuntimeState(rt, eng)
      }
      cmd.ackDeferred?.complete(SessionCommandOutcome.Completed)
      return
    }

    when (cmd) {
      is SessionCommand.SelectBlock -> {
        SessionMutationApplier.applyEditableCommand(rt, eng, cmd)
        publishCurrentRuntimeState(rt, eng)
        cmd.ackDeferred?.complete(SessionCommandOutcome.Completed)
      }
      is SessionCommand.FolderAvailabilityObserved ->
        handleFolderAvailabilityObserved(rt, eng, cmd.availabilityById)
      is SessionCommand.DebounceTimeout -> {
        if (
          cmd.expectedGeneration == rt.stateGeneration &&
            rt.activeConflict == null &&
            pendingBarrier == null &&
            !rt.isSessionTerminated
        ) {
          triggerSyncIfNeeded(rt, eng, flushAck = null)
        }
      }
      is SessionCommand.FlushAutosave -> handleFlushAutosave(rt, eng, cmd.ackDeferred)
      is SessionCommand.ConsolidateNote ->
        handleStructuralCommand(rt, eng, BarrierReason.CONSOLIDATION, queued)
      is SessionCommand.ResolveConflict ->
        handleStructuralCommand(rt, eng, BarrierReason.CONFLICT_RESOLUTION, queued)
      is SessionCommand.DiscardDraftAndClose ->
        handleStructuralCommand(rt, eng, BarrierReason.DISCARD_DRAFT, queued)
      is SessionCommand.PersistExistingNotePin ->
        handleStructuralCommand(rt, eng, BarrierReason.PIN_UPDATE, queued)
      is SessionCommand.CloseSession ->
        shutdownCoordinator.handleGracefulCloseSession(
          rt = rt,
          eng = eng,
          executor = executor,
          inFlightOpProvider = { inFlightOp },
          clearInFlightOp = { inFlightOp = null },
          uncommittedReplacement = uncommittedReplacement,
          latestCommittedLease = latestCommittedLease,
          cancelDebounce = ::cancelDebounce,
          drainCompletion = ::handleCompletion,
          publishState = { r, e -> publishCurrentRuntimeState(r, e) },
          failFlushWaiters = ::failFlushWaitersUpTo,
          ackDeferred = cmd.ackDeferred,
        )
      else -> Unit
    }
  }

  private fun handleFolderAvailabilityObserved(
    rt: SessionRuntimeState,
    eng: NoteEditorEngine,
    incomingMap: Map<Long, FolderAvailability>,
  ) {
    val previousSnapshot = rt.snapshot
    val previousEvaluation = rt.evaluate()
    rt.knownFolderAvailability.putAll(incomingMap)

    val (updatedSnapshot, updatedContext) =
      NoteEditorSessionEvaluator.applyFolderAvailabilityToSnapshot(
        snapshot = rt.snapshot,
        folderAvailabilityById = incomingMap,
        previousContext = rt.folderContext,
        baseline = rt.baseline,
      )

    rt.snapshot = updatedSnapshot
    rt.folderContext = updatedContext
    rt.effectiveFolderName =
      SessionOpenLoader.resolveEffectiveFolderName(
        effectiveFolderId = updatedSnapshot.effectiveFolderId,
        folderContext = updatedContext,
        previousEffectiveFolderId = previousSnapshot.effectiveFolderId,
        previousEffectiveFolderName = rt.effectiveFolderName,
      )

    val nextEval = rt.evaluate()
    val snapshotChanged = updatedSnapshot != previousSnapshot
    val retentionTriggered = !previousEvaluation.needsDraftRetention && nextEval.needsDraftRetention
    val retentionCleared = previousEvaluation.needsDraftRetention && !nextEval.needsDraftRetention

    if (snapshotChanged || retentionTriggered || retentionCleared) {
      rt.stateGeneration += 1L
      rt.errorMessage = null
      cancelDebounce()
      if (snapshotChanged || retentionTriggered) {
        rt.needsInitialPersistOnOpen =
          rt.needsInitialPersistOnOpen ||
            (nextEval.needsDraftRetention &&
              executor?.snapshot()?.checkpointPresence == CheckpointPresence.ABSENT)
      }
      onSnapshotOrRetentionUpdated(rt, eng, scheduleAutosave = true)
    } else {
      publishCurrentRuntimeState(rt, eng)
    }
  }

  private fun onSnapshotOrRetentionUpdated(
    rt: SessionRuntimeState,
    eng: NoteEditorEngine,
    scheduleAutosave: Boolean,
  ) {
    val eval = rt.evaluate()
    val presence = executor?.snapshot()?.checkpointPresence ?: CheckpointPresence.ABSENT

    if (rt.activeConflict != null) {
      rt.autosaveStatus = AutosaveStatus.HeldPendingResolution
      publishCurrentRuntimeState(rt, eng, eval)
      return
    }

    if (!eval.needsDraftRetention && presence == CheckpointPresence.ABSENT && inFlightOp == null) {
      rt.lastSyncedGeneration = rt.stateGeneration
      rt.needsInitialPersistOnOpen = false
      rt.autosaveStatus = AutosaveStatus.Idle
      publishCurrentRuntimeState(rt, eng, eval)
      completeSatisfiedFlushWaiters(rt.lastSyncedGeneration)
      return
    }

    rt.autosaveStatus = AutosaveStatus.PendingDebounce
    publishCurrentRuntimeState(rt, eng, eval)
    if (scheduleAutosave) {
      if (inFlightOp != null) {
        rescheduleAutosaveAfterInFlight = true
      } else {
        scheduleDebounce(rt.stateGeneration)
      }
    }
  }

  private fun handleFlushAutosave(
    rt: SessionRuntimeState,
    eng: NoteEditorEngine,
    ackDeferred: CompletableDeferred<SessionCommandOutcome>?,
  ) {
    if (rt.activeConflict != null) {
      ackDeferred?.complete(
        SessionCommandOutcome.Failed("Conflito ativo impede autosave do rascunho.")
      )
      return
    }
    if (uncommittedReplacement.get() != null || executor?.snapshot()?.hasPendingReplacement == true) {
      ackDeferred?.complete(
        SessionCommandOutcome.Failed("Substituição de checkpoint em estado indeterminado.")
      )
      return
    }

    cancelDebounce()
    val targetGen = rt.stateGeneration
    val eval = rt.evaluate()
    val execSnap = executor?.snapshot()
    val currentOp = inFlightOp

    if (currentOp != null) {
      if (currentOp.targetGeneration < targetGen || currentOp.kind == InFlightKind.STRUCTURAL) {
        rescheduleAutosaveAfterInFlight = true
      }
      if (ackDeferred != null) {
        pendingFlushWaiters.add(PendingFlushWaiter(targetGen, ackDeferred))
      }
      return
    }

    val alreadyCleanAndAbsent =
      !eval.needsDraftRetention && execSnap?.checkpointPresence == CheckpointPresence.ABSENT
    val alreadySavedCurrent =
      eval.needsDraftRetention &&
        !rt.needsInitialPersistOnOpen &&
        rt.stateGeneration == rt.lastSyncedGeneration &&
        execSnap?.checkpointPresence == CheckpointPresence.PRESENT &&
        rt.errorMessage == null &&
        rt.autosaveStatus !is AutosaveStatus.Error

    if (alreadyCleanAndAbsent || alreadySavedCurrent) {
      rt.lastSyncedGeneration = targetGen
      rt.autosaveStatus =
        if (eval.needsDraftRetention) {
          AutosaveStatus.Saved(targetGen, rt.lastCheckpointAt)
        } else {
          AutosaveStatus.Idle
        }
      publishCurrentRuntimeState(rt, eng, eval)
      ackDeferred?.complete(SessionCommandOutcome.Completed)
      return
    }

    triggerSyncIfNeeded(rt, eng, flushAck = ackDeferred)
  }

  private fun triggerSyncIfNeeded(
    rt: SessionRuntimeState,
    eng: NoteEditorEngine,
    flushAck: CompletableDeferred<SessionCommandOutcome>?,
  ) {
    if (inFlightOp != null) {
      rescheduleAutosaveAfterInFlight = true
      if (flushAck != null) {
        pendingFlushWaiters.add(PendingFlushWaiter(rt.stateGeneration, flushAck))
      }
      return
    }
    val exec = executor ?: return
    if (exec.snapshot().hasPendingReplacement || uncommittedReplacement.get() != null) {
      flushAck?.complete(
        SessionCommandOutcome.Failed("Existe substituição de checkpoint em estado indeterminado.")
      )
      return
    }
    if (rt.activeConflict != null) {
      flushAck?.complete(
        SessionCommandOutcome.Failed("Conflito ativo impede persistência automática do rascunho.")
      )
      return
    }

    val eval = rt.evaluate()
    val targetGen = rt.stateGeneration
    val presence = exec.snapshot().checkpointPresence

    if (!eval.needsDraftRetention) {
      if (presence == CheckpointPresence.ABSENT) {
        rt.lastSyncedGeneration = targetGen
        rt.needsInitialPersistOnOpen = false
        rt.autosaveStatus = AutosaveStatus.Idle
        publishCurrentRuntimeState(rt, eng, eval)
        flushAck?.complete(SessionCommandOutcome.Completed)
        completeSatisfiedFlushWaiters(targetGen)
        return
      }
      if (flushAck != null) {
        pendingFlushWaiters.add(PendingFlushWaiter(targetGen, flushAck))
      }
      launchDeleteCheckpointOp(rt, eng, exec, targetGen, eval)
      return
    }

    if (flushAck != null) {
      pendingFlushWaiters.add(PendingFlushWaiter(targetGen, flushAck))
    }
    launchSaveCheckpointOp(rt, eng, exec, targetGen, eval)
  }

  private fun launchSaveCheckpointOp(
    rt: SessionRuntimeState,
    eng: NoteEditorEngine,
    exec: SingleFlightRoomPersistenceExecutor,
    targetGen: Long,
    eval: com.example.domain.editor.NoteEditorSessionEvaluation,
  ) {
    val opId = nextOperationId++
    val payload = rt.buildCheckpointPayload()
    rt.errorMessage = null
    rt.autosaveStatus = AutosaveStatus.Saving

    val job =
      scope.launch(dispatchers.io) {
        val completion =
          when (val result = exec.saveCheckpoint(payload)) {
            is SaveCheckpointExecutionResult.Saved -> {
              handoffTracker.invalidateCandidateDraft(payload.draftId)
              PersistenceCompletion.CheckpointUpsert(
                operationId = opId,
                targetGeneration = targetGen,
                attemptedCheckpointRevision = result.allocatedRevision,
                persistedCheckpointRevision = result.draft.checkpointRevision,
                checkpointAt = result.draft.checkpointAt,
              )
            }
            is SaveCheckpointExecutionResult.IgnoredStaleRevision ->
              PersistenceCompletion.CheckpointUpsert(
                operationId = opId,
                targetGeneration = targetGen,
                attemptedCheckpointRevision = result.attemptedCheckpointRevision,
                persistedCheckpointRevision = result.persistedCheckpointRevision,
                isIgnoredStaleRevision = true,
              )
            is SaveCheckpointExecutionResult.RevisionOverflow ->
              PersistenceCompletion.CheckpointUpsert(
                operationId = opId,
                targetGeneration = targetGen,
                attemptedCheckpointRevision = result.currentHighestAllocatedRevision,
                failure = IllegalStateException("Estouro de revisão do checkpoint."),
              )
            is SaveCheckpointExecutionResult.Failed ->
              PersistenceCompletion.CheckpointUpsert(
                operationId = opId,
                targetGeneration = targetGen,
                attemptedCheckpointRevision = 0L,
                failure = result.cause,
              )
          }
        mailbox.enqueueCompletion(completion)
      }

    inFlightOp =
      InFlightOperation(opId, InFlightKind.AUTOSAVE_UPSERT, targetGen, barrierToken = null, job = job)
    publishCurrentRuntimeState(rt, eng, eval)
  }

  private fun launchDeleteCheckpointOp(
    rt: SessionRuntimeState,
    eng: NoteEditorEngine,
    exec: SingleFlightRoomPersistenceExecutor,
    targetGen: Long,
    eval: com.example.domain.editor.NoteEditorSessionEvaluation,
  ) {
    val opId = nextOperationId++
    val draftId = rt.identity.draftId
    rt.errorMessage = null
    rt.autosaveStatus = AutosaveStatus.Saving

    val job =
      scope.launch(dispatchers.io) {
        val completion =
          when (val result = exec.deleteCheckpoint(draftId)) {
            is DeleteCheckpointExecutionResult.Deleted -> {
              handoffTracker.invalidateCandidateDraft(draftId)
              PersistenceCompletion.CheckpointDelete(opId, targetGen, failure = null)
            }
            is DeleteCheckpointExecutionResult.Failed ->
              PersistenceCompletion.CheckpointDelete(opId, targetGen, failure = result.cause)
          }
        mailbox.enqueueCompletion(completion)
      }

    inFlightOp =
      InFlightOperation(opId, InFlightKind.CLEAN_DELETE, targetGen, barrierToken = null, job = job)
    publishCurrentRuntimeState(rt, eng, eval)
  }

  private fun launchFolderLookup(folderId: Long) {
    val opId = nextOperationId++
    scope.launch(dispatchers.io) {
      val map = folderRepository.getFoldersByIds(setOf(folderId))
      mailbox.enqueueCompletion(PersistenceCompletion.FolderAvailabilityLookup(opId, map))
    }
  }

  private fun handleStructuralCommand(
    rt: SessionRuntimeState,
    eng: NoteEditorEngine,
    reason: BarrierReason,
    queued: QueuedSessionCommand,
  ) {
    val cmd = queued.command
    if (pendingBarrier != null) {
      cmd.ackDeferred?.complete(
        SessionCommandOutcome.Failed("Já existe uma operação estrutural em andamento.")
      )
      return
    }
    if (
      (uncommittedReplacement.get() != null || executor?.snapshot()?.hasPendingReplacement == true) &&
        !(cmd is SessionCommand.ResolveConflict &&
          cmd.option == ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE)
    ) {
      cmd.ackDeferred?.complete(
        SessionCommandOutcome.Failed(
          "Substituição de checkpoint em estado indeterminado bloqueia esta operação."
        )
      )
      return
    }

    cancelDebounce()
    val token =
      mailbox.tryEnterBarrier(reason)
        ?: run {
          cmd.ackDeferred?.complete(
            SessionCommandOutcome.Failed("Não foi possível ativar barreira estrutural.")
          )
          return
        }

    val barrier = PendingStructuralBarrier(token = token, queuedCommand = queued, dispatched = false)
    pendingBarrier = barrier
    if (cmd is SessionCommand.ConsolidateNote) {
      rt.isConsolidating = true
    }
    publishCurrentRuntimeState(rt, eng)

    if (inFlightOp != null) {
      return
    }

    barrier.dispatched = true
    dispatchStructuralBarrier(rt, eng, barrier)
  }

  private fun dispatchStructuralBarrier(
    rt: SessionRuntimeState,
    eng: NoteEditorEngine,
    barrier: PendingStructuralBarrier,
  ) {
    val exec =
      executor
        ?: run {
          barrier.queuedCommand.command.ackDeferred?.complete(
            SessionCommandOutcome.Failed("Executor indisponível.")
          )
          pendingBarrier = null
          mailbox.releaseBarrier(barrier.token)
          return
        }
    val cmd = barrier.queuedCommand.command
    val opId = nextOperationId++
    val snapshotAtDispatch =
      rt.copy(knownFolderAvailability = rt.knownFolderAvailability.toMutableMap())

    val job =
      scope.launch(dispatchers.io) {
        val rawResult: StructuralRunnerResult =
          try {
            when (cmd) {
              is SessionCommand.ConsolidateNote ->
                structuralRunner.runConsolidateNote(cmd, snapshotAtDispatch, exec)
              is SessionCommand.ResolveConflict ->
                structuralRunner.runResolveConflict(
                  command = cmd,
                  runtimeSnapshot = snapshotAtDispatch,
                  executor = exec,
                  onPendingReplacementSet = { ctx -> uncommittedReplacement.set(ctx) },
                )
              is SessionCommand.DiscardDraftAndClose ->
                structuralRunner.runDiscardDraftAndClose(snapshotAtDispatch, exec)
              is SessionCommand.PersistExistingNotePin ->
                structuralRunner.runPersistExistingNotePin(cmd, snapshotAtDispatch)
              else ->
                StructuralRunnerResult.Failed(
                  IllegalStateException("Comando inválido para barreira estrutural: $cmd")
                )
            }
          } catch (c: CancellationException) {
            throw c
          } catch (t: Throwable) {
            StructuralRunnerResult.Failed(t)
          }

        val result = transformStructuralResultForTest?.invoke(rawResult) ?: rawResult

        when (result) {
          is StructuralRunnerResult.Succeeded -> {
            structuralOutcomes[opId] = result.data
            mailbox.enqueueCompletion(
              PersistenceCompletion.StructuralOperation(
                operationId = opId,
                barrierToken = barrier.token,
                succeeded = true,
                terminal = result.data is StructuralOutcomeData.DiscardedAndClosed,
                failure = null,
              )
            )
          }
          is StructuralRunnerResult.Failed -> {
            result.fallbackData?.let { structuralOutcomes[opId] = it }
            mailbox.enqueueCompletion(
              PersistenceCompletion.StructuralOperation(
                operationId = opId,
                barrierToken = barrier.token,
                succeeded = false,
                terminal = false,
                failure = result.cause,
              )
            )
          }
        }
      }

    inFlightOp =
      InFlightOperation(opId, InFlightKind.STRUCTURAL, rt.stateGeneration, barrier.token, job)
  }

  private fun handleCompletion(completion: PersistenceCompletion) {
    val rt = runtime ?: return
    val eng = engine ?: return

    when (completion) {
      is PersistenceCompletion.CheckpointUpsert -> {
        if (inFlightOp?.operationId == completion.operationId) inFlightOp = null
        handleCheckpointUpsertCompletion(rt, eng, completion)
      }
      is PersistenceCompletion.CheckpointDelete -> {
        if (inFlightOp?.operationId == completion.operationId) inFlightOp = null
        handleCheckpointDeleteCompletion(rt, eng, completion)
      }
      is PersistenceCompletion.FolderAvailabilityLookup ->
        handleFolderAvailabilityObserved(rt, eng, completion.availabilityById)
      is PersistenceCompletion.StructuralRetryRequested -> {
        val barrier = pendingBarrier
        if (
          barrier != null &&
            barrier.token == completion.barrierToken &&
            !barrier.dispatched &&
            inFlightOp == null
        ) {
          barrier.dispatched = true
          dispatchStructuralBarrier(rt, eng, barrier)
        }
      }
      is PersistenceCompletion.StructuralOperation -> {
        if (inFlightOp?.operationId == completion.operationId) inFlightOp = null
        handleStructuralOperationCompletion(rt, eng, completion)
      }
    }
  }

  private fun handleCheckpointUpsertCompletion(
    rt: SessionRuntimeState,
    eng: NoteEditorEngine,
    completion: PersistenceCompletion.CheckpointUpsert,
  ) {
    val failure = completion.failure
    if (failure != null) {
      if (completion.targetGeneration == rt.stateGeneration) {
        val msg = failure.message ?: "Falha ao salvar checkpoint do rascunho."
        rt.errorMessage = msg
        rt.autosaveStatus = AutosaveStatus.Error(message = msg, isTerminal = false)
        publishCurrentRuntimeState(rt, eng)
        failFlushWaitersUpTo(completion.targetGeneration, msg, failure)
      }
      scheduleNextAutosaveOrTriggerBarrierRetry(rt, eng)
      return
    }

    if (completion.isIgnoredStaleRevision) {
      rt.lastSyncedGeneration = maxOf(rt.lastSyncedGeneration, completion.targetGeneration)
    } else {
      rt.lastSyncedGeneration = maxOf(rt.lastSyncedGeneration, completion.targetGeneration)
      rt.lastCheckpointAt = completion.checkpointAt ?: rt.lastCheckpointAt
    }

    if (rt.stateGeneration == rt.lastSyncedGeneration) {
      rt.needsInitialPersistOnOpen = false
      rt.errorMessage = null
      rt.autosaveStatus = AutosaveStatus.Saved(rt.lastSyncedGeneration, rt.lastCheckpointAt)
    } else {
      rt.autosaveStatus = AutosaveStatus.PendingDebounce
    }

    publishCurrentRuntimeState(rt, eng)
    completeSatisfiedFlushWaiters(rt.lastSyncedGeneration)
    scheduleNextAutosaveOrTriggerBarrierRetry(rt, eng)
  }

  private fun handleCheckpointDeleteCompletion(
    rt: SessionRuntimeState,
    eng: NoteEditorEngine,
    completion: PersistenceCompletion.CheckpointDelete,
  ) {
    val failure = completion.failure
    if (failure != null) {
      if (completion.targetGeneration == rt.stateGeneration) {
        val msg = failure.message ?: "Falha ao remover checkpoint limpo."
        rt.errorMessage = msg
        rt.autosaveStatus = AutosaveStatus.Error(message = msg, isTerminal = false)
        publishCurrentRuntimeState(rt, eng)
        failFlushWaitersUpTo(completion.targetGeneration, msg, failure)
      }
      scheduleNextAutosaveOrTriggerBarrierRetry(rt, eng)
      return
    }

    rt.lastSyncedGeneration = maxOf(rt.lastSyncedGeneration, completion.targetGeneration)
    rt.lastCheckpointAt = null
    rt.needsInitialPersistOnOpen = false

    val eval = rt.evaluate()
    if (rt.stateGeneration == rt.lastSyncedGeneration && !eval.needsDraftRetention) {
      rt.errorMessage = null
      rt.autosaveStatus = AutosaveStatus.Idle
    } else {
      rt.autosaveStatus = AutosaveStatus.PendingDebounce
    }

    publishCurrentRuntimeState(rt, eng, eval)
    completeSatisfiedFlushWaiters(rt.lastSyncedGeneration)
    scheduleNextAutosaveOrTriggerBarrierRetry(rt, eng)
  }

  private fun scheduleNextAutosaveOrTriggerBarrierRetry(
    rt: SessionRuntimeState,
    eng: NoteEditorEngine,
  ) {
    val barrier = pendingBarrier
    if (barrier != null && !barrier.dispatched && inFlightOp == null) {
      mailbox.enqueueCompletion(
        PersistenceCompletion.StructuralRetryRequested(
          operationId = nextOperationId++,
          barrierToken = barrier.token,
        )
      )
      return
    }

    if (
      (rescheduleAutosaveAfterInFlight || rt.stateGeneration > rt.lastSyncedGeneration) &&
        pendingBarrier == null &&
        inFlightOp == null &&
        rt.activeConflict == null &&
        !rt.isSessionTerminated
    ) {
      rescheduleAutosaveAfterInFlight = false
      if (pendingFlushWaiters.isNotEmpty()) {
        triggerSyncIfNeeded(rt, eng, flushAck = null)
      } else {
        scheduleDebounce(rt.stateGeneration)
      }
    }
  }

  private fun handleStructuralOperationCompletion(
    rt: SessionRuntimeState,
    eng: NoteEditorEngine,
    completion: PersistenceCompletion.StructuralOperation,
  ) {
    val barrier = pendingBarrier
    if (barrier == null || barrier.token != completion.barrierToken) {
      structuralOutcomes.remove(completion.operationId)
      return
    }

    val ackDeferred = barrier.queuedCommand.command.ackDeferred
    val data = structuralOutcomes.remove(completion.operationId)
    rt.isConsolidating = false

    if (completion.succeeded) {
      applyStructuralSuccess(rt, eng, barrier, data, ackDeferred)
    } else {
      applyStructuralFailure(rt, eng, barrier, data, completion.failure, ackDeferred)
    }
  }

  private fun applyStructuralSuccess(
    rt: SessionRuntimeState,
    eng: NoteEditorEngine,
    barrier: PendingStructuralBarrier,
    data: StructuralOutcomeData?,
    ackDeferred: CompletableDeferred<SessionCommandOutcome>?,
  ) {
    when (data) {
      is StructuralOutcomeData.ConsolidatedNew -> {
        val note = data.createdNote
        val noteDoc = (note.contentState as? NoteContentState.Valid)?.document ?: rt.snapshot.document
        rt.knownFolderAvailability.putAll(data.resolvedFolderAvailability)
        rt.identity = data.newIdentity
        rt.lease = data.newLease
        latestCommittedLease.set(data.newLease)
        executor = data.newExecutor
        updateConsolidatedExistingBaseline(rt, note, noteDoc, isNewTransition = true)
      }
      is StructuralOutcomeData.ConsolidatedExisting -> {
        val note = data.updatedNote
        val noteDoc = (note.contentState as? NoteContentState.Valid)?.document ?: rt.snapshot.document
        rt.knownFolderAvailability.putAll(data.resolvedFolderAvailability)
        updateConsolidatedExistingBaseline(rt, note, noteDoc, isNewTransition = false)
      }
      is StructuralOutcomeData.KeptDraftOverCurrentNote -> {
        val note = data.latestNote
        val noteDoc =
          (note.contentState as? NoteContentState.Valid)?.document
            ?: rt.snapshot.document
        val previousEffectiveFolderId = rt.snapshot.effectiveFolderId

        rt.knownFolderAvailability.putAll(data.resolvedFolderAvailability)

        rt.baseline =
          NoteEditorBaseline.ExistingNote(
            noteId = note.id,
            baseRevision = note.revision,
            baseNoteUpdatedAt = note.updatedAt,
            title = note.title,
            normalizedContent =
              NoteEditorSessionEvaluator.canonicalizeDocument(noteDoc),
            paperStyle = note.paperStyle,
            staticFolderId = note.folderId,
            currentDbIsPinned = note.isPinned,
          )

        // Aplicar o mesmo snapshot e contexto reconciliados pelo runner.
        // Não reconstruir o destino usando o estado antigo da sessão.
        rt.snapshot = data.rebasedSnapshot
        rt.folderContext = data.rebasedFolderContext
        rt.effectiveFolderName =
          SessionOpenLoader.resolveEffectiveFolderName(
            effectiveFolderId = data.rebasedSnapshot.effectiveFolderId,
            folderContext = data.rebasedFolderContext,
            previousEffectiveFolderId = previousEffectiveFolderId,
            previousEffectiveFolderName = rt.effectiveFolderName,
          )

        rt.activeConflict = null
        rt.errorMessage = null
        rt.needsInitialPersistOnOpen = false
        rt.stateGeneration += 1L
        rt.lastSyncedGeneration = rt.stateGeneration
        rt.lastCheckpointAt = data.checkpointAt
        rt.autosaveStatus =
          if (data.deletedCleanDraft) {
            AutosaveStatus.Idle
          } else {
            AutosaveStatus.Saved(rt.lastSyncedGeneration, data.checkpointAt)
          }
      }
      is StructuralOutcomeData.DiscardedDraftToCurrentNote -> {
        applyDiscardedDraftToCurrentNote(rt, eng, data)
      }
      is StructuralOutcomeData.SavedDraftAsNewNote -> {
        applyPromotedNewNoteState(rt, data.newIdentity, data.newLease, data.newDraft, data.targetBaseline)
      }
      is StructuralOutcomeData.ReconciledIndeterminate -> {
        val rec = data.result as? ReplacementReconciliationResult.PromotedToNew
        if (rec != null) {
          val baseline =
            NoteEditorBaseline.NewNote(
              title = "",
              normalizedContent =
                NoteEditorSessionEvaluator.canonicalizeDocument(NoteContentDocument.empty()),
              paperStyle = rec.newDraft.paperStyle,
              staticFolderId = rec.newDraft.initialFolderId,
              initialIsPinned = false,
            )
          applyPromotedNewNoteState(rt, rec.newIdentity, rec.newLease, rec.newDraft, baseline)
        }
      }
      is StructuralOutcomeData.ExistingNotePinUpdated -> {
        val note = data.updatedNote
        val currentBaseline = rt.baseline as? NoteEditorBaseline.ExistingNote
        if (
          currentBaseline != null &&
            (note.revision == currentBaseline.baseRevision ||
              note.revision == currentBaseline.baseRevision + 1L)
        ) {
          rt.baseline =
            currentBaseline.copy(
              baseRevision = note.revision,
              baseNoteUpdatedAt = note.updatedAt,
              currentDbIsPinned = note.isPinned,
            )
        }
        rt.snapshot = rt.snapshot.copy(isPinned = note.isPinned)
        rt.errorMessage = null
        val eval = rt.evaluate()
        val hasCheckpoint = executor?.snapshot()?.checkpointPresence == CheckpointPresence.PRESENT
        if (eval.needsDraftRetention || hasCheckpoint) {
          rt.stateGeneration += 1L
          rt.autosaveStatus = AutosaveStatus.PendingDebounce
          rescheduleAutosaveAfterInFlight = true
        }
      }
      StructuralOutcomeData.DiscardedAndClosed -> {
        rt.activeConflict = null
        rt.errorMessage = null
        rt.lastCheckpointAt = null
        rt.needsInitialPersistOnOpen = false
        rt.isSessionTerminated = true
        rt.autosaveStatus = AutosaveStatus.Idle
        publishCurrentRuntimeState(rt, eng)
        ackDeferred?.complete(SessionCommandOutcome.Completed)
        pendingBarrier = null
        mailbox.closeAndReleaseBarrier(barrier.token)
        shutdownCoordinator.drainLeftoverWithCancelled()
        return
      }
      is StructuralOutcomeData.ConsolidationConflictDetected,
      null -> Unit
    }

    publishCurrentRuntimeState(rt, eng)
    ackDeferred?.complete(SessionCommandOutcome.Completed)
    pendingBarrier = null
    mailbox.releaseBarrier(barrier.token)
  }

  private fun applyStructuralFailure(
    rt: SessionRuntimeState,
    eng: NoteEditorEngine,
    barrier: PendingStructuralBarrier,
    data: StructuralOutcomeData?,
    failure: Throwable?,
    ackDeferred: CompletableDeferred<SessionCommandOutcome>?,
  ) {
    var recoveredConfirmedPromotion = false

    when (data) {
      is StructuralOutcomeData.ConsolidationConflictDetected -> {
        rt.activeConflict = data.conflict
        rt.autosaveStatus = AutosaveStatus.HeldPendingResolution
        rt.errorMessage = failure?.message
      }
      is StructuralOutcomeData.ReconciledIndeterminate -> {
        when (val rec = data.result) {
          is ReplacementReconciliationResult.RevertedToOld -> {
            executor = rec.restoredExecutor ?: executor
            rt.lease = rec.preservedLease
            latestCommittedLease.set(rec.preservedLease)
            rt.errorMessage = failure?.message
            rt.autosaveStatus = AutosaveStatus.HeldPendingResolution
          }
          is ReplacementReconciliationResult.IndeterminateReadFailure -> {
            rt.errorMessage = rec.message
            rt.autosaveStatus = AutosaveStatus.Error(message = rec.message, isTerminal = false)
          }
          is ReplacementReconciliationResult.NeitherPresent -> {
            val msg = failure?.message?.takeIf { it.isNotBlank() } ?: rec.message
            rt.errorMessage = msg
            rt.autosaveStatus = AutosaveStatus.Error(message = msg, isTerminal = true)
          }
          is ReplacementReconciliationResult.DivergentAnomaly -> {
            rt.errorMessage = rec.message
            rt.autosaveStatus = AutosaveStatus.Error(message = rec.message, isTerminal = true)
          }
          is ReplacementReconciliationResult.PromotedToNew -> {
            val baseline =
              NoteEditorBaseline.NewNote(
                title = "",
                normalizedContent =
                  NoteEditorSessionEvaluator.canonicalizeDocument(NoteContentDocument.empty()),
                paperStyle = rec.newDraft.initialPaperStyle ?: rec.newDraft.paperStyle,
                staticFolderId = rec.newDraft.initialFolderId,
                initialIsPinned = false,
              )
            applyPromotedNewNoteState(
              rt = rt,
              newIdentity = rec.newIdentity,
              newLease = rec.newLease,
              newDraft = rec.newDraft,
              newBaseline = baseline,
            )
            recoveredConfirmedPromotion = true
          }
        }
      }
      else -> {
        val msg = failure?.message ?: "Falha na operação estrutural."
        rt.errorMessage = msg
        rt.autosaveStatus =
          if (rt.activeConflict == null) {
            AutosaveStatus.Error(message = msg, isTerminal = false)
          } else {
            AutosaveStatus.HeldPendingResolution
          }
      }
    }

    publishCurrentRuntimeState(rt, eng)
    ackDeferred?.complete(
      if (recoveredConfirmedPromotion) {
        SessionCommandOutcome.Completed
      } else {
        SessionCommandOutcome.Failed(
          message = failure?.message ?: "Falha na operação estrutural.",
          cause = failure,
        )
      }
    )
    pendingBarrier = null
    mailbox.releaseBarrier(barrier.token)
  }

  private fun updateConsolidatedExistingBaseline(
    rt: SessionRuntimeState,
    note: com.example.domain.model.Note,
    noteDoc: NoteContentDocument,
    isNewTransition: Boolean,
  ) {
    val canonicalDoc = NoteEditorSessionEvaluator.canonicalizeDocument(noteDoc)
    rt.baseline =
      NoteEditorBaseline.ExistingNote(
        noteId = note.id,
        baseRevision = note.revision,
        baseNoteUpdatedAt = note.updatedAt,
        title = note.title,
        normalizedContent = canonicalDoc,
        paperStyle = note.paperStyle,
        staticFolderId = note.folderId,
        currentDbIsPinned = note.isPinned,
      )
    val resolvedFolderAvail =
      NoteEditorSessionEvaluator.resolveFolderAvailability(note.folderId, rt.knownFolderAvailability)
    rt.snapshot =
      rt.snapshot.copy(
        effectiveFolderId = note.folderId,
        fallbackSourceFolderId = null,
        isPinned = note.isPinned,
      )
    rt.folderContext =
      rt.folderContext.copy(
        effectiveTarget = resolvedFolderAvail,
        fallbackSource = null,
        initialBaseline = resolvedFolderAvail,
        existingNoteFolder = resolvedFolderAvail,
      )
    rt.effectiveFolderName =
      SessionOpenLoader.resolveEffectiveFolderName(
        note.folderId,
        rt.folderContext,
        previousEffectiveFolderId = note.folderId,
        previousEffectiveFolderName = rt.effectiveFolderName,
      )
    if (isNewTransition) {
      rt.persistedInitialFolderId = null
      rt.persistedInitialPaperStyle = null
    }
    rt.activeConflict = null
    rt.lastCheckpointAt = null
    rt.needsInitialPersistOnOpen = false
    rt.errorMessage = null
    rt.lastSyncedGeneration = rt.stateGeneration
    rt.autosaveStatus = AutosaveStatus.Idle
  }

  private fun applyDiscardedDraftToCurrentNote(
    rt: SessionRuntimeState,
    eng: NoteEditorEngine,
    data: StructuralOutcomeData.DiscardedDraftToCurrentNote,
  ) {
    val note = data.latestNote
    val noteDoc = (note.contentState as? NoteContentState.Valid)?.document ?: rt.snapshot.document
    val canonicalDoc = NoteEditorSessionEvaluator.canonicalizeDocument(noteDoc)
    rt.knownFolderAvailability.putAll(data.resolvedFolderAvailability)
    val baseline =
      NoteEditorBaseline.ExistingNote(
        noteId = note.id,
        baseRevision = note.revision,
        baseNoteUpdatedAt = note.updatedAt,
        title = note.title,
        normalizedContent = canonicalDoc,
        paperStyle = note.paperStyle,
        staticFolderId = note.folderId,
        currentDbIsPinned = note.isPinned,
      )
    rt.baseline = baseline
    val initialSnap =
      NoteEditorEditableSnapshot(
        title = note.title,
        document = canonicalDoc,
        effectiveFolderId = note.folderId,
        fallbackSourceFolderId = null,
        paperStyle = note.paperStyle,
        isPinned = note.isPinned,
      )
    val existingFolderAvail =
      NoteEditorSessionEvaluator.resolveFolderAvailability(note.folderId, rt.knownFolderAvailability)
    val initialCtx =
      ResolvedDraftFolderContext(
        effectiveTarget = existingFolderAvail,
        fallbackSource = null,
        initialBaseline = existingFolderAvail,
        existingNoteFolder = existingFolderAvail,
      )
    val (resolvedSnap, resolvedCtx) =
      NoteEditorSessionEvaluator.applyFolderAvailabilityToSnapshot(
        snapshot = initialSnap,
        folderAvailabilityById = rt.knownFolderAvailability,
        previousContext = initialCtx,
        baseline = baseline,
      )
    rt.snapshot = resolvedSnap
    rt.folderContext = resolvedCtx
    rt.effectiveFolderName =
      SessionOpenLoader.resolveEffectiveFolderName(resolvedSnap.effectiveFolderId, resolvedCtx)
    eng.reset(canonicalDoc)
    rt.activeConflict = null
    rt.errorMessage = null
    rt.lastCheckpointAt = null
    rt.stateGeneration += 1L
    val postEval = rt.evaluate()
    if (postEval.needsDraftRetention) {
      rt.needsInitialPersistOnOpen = true
      rt.autosaveStatus = AutosaveStatus.PendingDebounce
      scheduleDebounce(rt.stateGeneration)
    } else {
      rt.needsInitialPersistOnOpen = false
      rt.lastSyncedGeneration = rt.stateGeneration
      rt.autosaveStatus = AutosaveStatus.Idle
    }
  }

  private fun applyPromotedNewNoteState(
    rt: SessionRuntimeState,
    newIdentity: NoteEditorSessionIdentity,
    newLease: SessionLeaseToken,
    newDraft: NoteRecoveryDraft,
    newBaseline: NoteEditorBaseline.NewNote,
  ) {
    rt.identity = newIdentity
    rt.lease = newLease
    latestCommittedLease.set(newLease)
    uncommittedReplacement.set(null)
    rt.baseline = newBaseline
    rt.persistedInitialFolderId = newDraft.initialFolderId
    rt.persistedInitialPaperStyle = newDraft.initialPaperStyle
    rt.folderContext =
      rt.folderContext.copy(
        initialBaseline = rt.folderContext.fallbackSource ?: rt.folderContext.effectiveTarget,
        existingNoteFolder = null,
      )
    rt.activeConflict = null
    rt.errorMessage = null
    rt.needsInitialPersistOnOpen = false
    rt.stateGeneration += 1L
    rt.lastSyncedGeneration = rt.stateGeneration
    rt.lastCheckpointAt = newDraft.checkpointAt
    rt.autosaveStatus = AutosaveStatus.Saved(rt.lastSyncedGeneration, newDraft.checkpointAt)
  }

  private fun publishCurrentRuntimeState(
    rt: SessionRuntimeState,
    eng: NoteEditorEngine,
    precomputedEval: com.example.domain.editor.NoteEditorSessionEvaluation? = null,
  ): NoteEditorUiState {
    val eval = precomputedEval ?: rt.evaluate()
    val publication =
      rt.toPublication(
        evaluation = eval,
        editorState = eng.state,
        hasInFlightPersistence = inFlightOp != null,
      )
    return mailbox.publishState { publication }
  }

  private fun scheduleDebounce(expectedGeneration: Long) {
    cancelDebounce()
    debounceJob =
      scope.launch {
        delay(autosaveDebounceMillis)
        mailbox.submit(SessionCommand.DebounceTimeout(expectedGeneration))
      }
  }

  private fun cancelDebounce() {
    debounceJob?.cancel()
    debounceJob = null
  }

  private fun completeSatisfiedFlushWaiters(syncedGeneration: Long) {
    val iterator = pendingFlushWaiters.iterator()
    while (iterator.hasNext()) {
      val waiter = iterator.next()
      if (waiter.targetGeneration <= syncedGeneration) {
        waiter.ackDeferred.complete(SessionCommandOutcome.Completed)
        iterator.remove()
      }
    }
  }

  private fun failFlushWaitersUpTo(
    maxGeneration: Long,
    message: String,
    cause: Throwable?,
  ) {
    val iterator = pendingFlushWaiters.iterator()
    while (iterator.hasNext()) {
      val waiter = iterator.next()
      if (waiter.targetGeneration <= maxGeneration) {
        waiter.ackDeferred.complete(SessionCommandOutcome.Failed(message, cause))
        iterator.remove()
      }
    }
  }

  companion object {
    const val DEFAULT_AUTOSAVE_DEBOUNCE_MILLIS: Long = 600L
  }
}
