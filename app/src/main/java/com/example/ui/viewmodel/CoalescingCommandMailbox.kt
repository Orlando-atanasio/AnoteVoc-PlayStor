package com.example.ui.viewmodel

import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Caixa de correio coordenada com ponto único de linearização (`lock`) entre:
 * - aceitação síncrona de comandos e sequência monotônica `acceptedMutationSequence`;
 * - fila FIFO de comandos e fila `pendingCompletions` com barreira estrutural e anti-starvation;
 * - publicação linearizada de [NoteEditorUiState] impedindo falsos estados `Saved`/`Idle`.
 *
 * Nenhuma operação de I/O ou callback de continuação é executada sob [lock].
 */
class CoalescingCommandMailbox(
  initialUiState: NoteEditorUiState = NoteEditorUiState.loading(),
  initialAcceptedMutationSequence: Long = 0L,
  initialAppliedMutationSequence: Long = initialAcceptedMutationSequence,
  val maxConsecutiveFifoPolls: Int = DEFAULT_MAX_CONSECUTIVE_FIFO_POLLS,
) {
  init {
    require(initialAcceptedMutationSequence >= 0L) {
      "initialAcceptedMutationSequence não pode ser negativo: $initialAcceptedMutationSequence"
    }
    require(initialAppliedMutationSequence in 0L..initialAcceptedMutationSequence) {
      "initialAppliedMutationSequence ($initialAppliedMutationSequence) deve estar entre 0 e " +
        "$initialAcceptedMutationSequence."
    }
    require(maxConsecutiveFifoPolls > 0) {
      "maxConsecutiveFifoPolls deve ser maior que zero: $maxConsecutiveFifoPolls"
    }
  }

  /**
   * Monitor compartilhado entre aceitação de comandos, controle de fila/barreira e publicação de estado.
   */
  internal val lock = Any()

  private val _uiState = MutableStateFlow(initialUiState)
  val uiState: StateFlow<NoteEditorUiState> = _uiState.asStateFlow()

  private val fifoQueue = ArrayDeque<QueuedSessionCommand>()
  private val pendingCompletions = ArrayDeque<PersistenceCompletion>()
  private val waiters = ArrayDeque<CancellableContinuation<Unit>>()

  private var acceptedSequence: Long = initialAcceptedMutationSequence
  private var appliedSequence: Long = initialAppliedMutationSequence
  private var consecutiveFifoCounter: Int = 0
  private var activeBarrierToken: BarrierToken? = null
  private var nextBarrierTokenId: Long = 1L
  private var closed: Boolean = false

  val acceptedMutationSequence: Long
    get() = synchronized(lock) { acceptedSequence }

  val appliedMutationSequence: Long
    get() = synchronized(lock) { appliedSequence }

  val barrierActive: Boolean
    get() = synchronized(lock) { activeBarrierToken != null }

  val isClosed: Boolean
    get() = synchronized(lock) { closed }

  val consecutiveFifoPolls: Int
    get() = synchronized(lock) { consecutiveFifoCounter }

  val fifoQueueSize: Int
    get() = synchronized(lock) { fifoQueue.size }

  val pendingCompletionsSize: Int
    get() = synchronized(lock) { pendingCompletions.size }

  fun linearizationSnapshot(): MailboxLinearizationSnapshot =
    synchronized(lock) { buildSnapshotLocked() }

  /**
   * Aceita atomicamente um [SessionCommand] sob [lock]:
   * - rejeita após fechamento definitivo (`MAILBOX_CLOSED`);
   * - protege `acceptedMutationSequence` contra overflow em `Long.MAX_VALUE` (`SEQUENCE_OVERFLOW`);
   * - invalida imediatamente `Saved` ou `Idle` para `PendingDebounce` quando aceita mutação
   *   persistível sem barreira ativa.
   */
  fun submit(command: SessionCommand): SubmitCommandResult {
    var result: SubmitCommandResult
    var toWake: List<CancellableContinuation<Unit>> = emptyList()

    synchronized(lock) {
      if (closed) {
        result = SubmitCommandResult.Rejected(SubmitRejectionReason.MAILBOX_CLOSED)
      } else if (command.isPersistableMutation && acceptedSequence == Long.MAX_VALUE) {
        result = SubmitCommandResult.Rejected(SubmitRejectionReason.SEQUENCE_OVERFLOW)
      } else {
        val assignedSeq: Long? =
          if (command.isPersistableMutation) {
            acceptedSequence += 1L
            acceptedSequence
          } else {
            null
          }

        fifoQueue.addLast(QueuedSessionCommand(command = command, mutationSequence = assignedSeq))

        if (command.isPersistableMutation && activeBarrierToken == null) {
          val current = _uiState.value
          if (shouldInvalidateSyncedStatusOnMutation(current)) {
            _uiState.value = current.copy(autosaveStatus = AutosaveStatus.PendingDebounce)
          }
        }

        toWake = detachWaitersLocked()
        result = SubmitCommandResult.Accepted(assignedMutationSequence = assignedSeq)
      }
    }

    resumeWaiters(toWake)

    if (result is SubmitCommandResult.Rejected) {
      val rejected = result as SubmitCommandResult.Rejected
      command.ackDeferred?.complete(SessionCommandOutcome.Rejected(rejected.reason))
    }

    return result
  }

  /**
   * Enfileira uma conclusão assíncrona em `pendingCompletions` e acorda consumidores aguardando.
   */
  fun enqueueCompletion(completion: PersistenceCompletion) {
    val toWake: List<CancellableContinuation<Unit>>
    synchronized(lock) {
      pendingCompletions.addLast(completion)
      toWake = detachWaitersLocked()
    }
    resumeWaiters(toWake)
  }

  /**
   * Ativa barreira estrutural quando nenhuma outra barreira está ativa, retornando seu [BarrierToken].
   */
  fun enterBarrier(reason: BarrierReason): BarrierToken =
    synchronized(lock) {
      check(activeBarrierToken == null) {
        "Já existe barreira estrutural ativa: $activeBarrierToken (solicitado=$reason)."
      }
      check(nextBarrierTokenId < Long.MAX_VALUE) {
        "Limite de identificadores de barreira atingido."
      }
      val token = BarrierToken(id = nextBarrierTokenId++, reason = reason)
      activeBarrierToken = token
      token
    }

  /**
   * Tenta ativar barreira estrutural sem lançar exceção caso já exista barreira ativa.
   */
  fun tryEnterBarrier(reason: BarrierReason): BarrierToken? =
    synchronized(lock) {
      if (activeBarrierToken != null || nextBarrierTokenId >= Long.MAX_VALUE) {
        null
      } else {
        val token = BarrierToken(id = nextBarrierTokenId++, reason = reason)
        activeBarrierToken = token
        token
      }
    }

  /**
   * Libera a barreira ativa somente se [token] corresponder exatamente ao token ativo atual.
   * Quando liberada e houver itens retidos em `fifoQueue` ou fechamento pendente, acorda consumidores.
   */
  fun releaseBarrier(token: BarrierToken): Boolean {
    var released = false
    var toWake: List<CancellableContinuation<Unit>> = emptyList()
    synchronized(lock) {
      if (activeBarrierToken == token) {
        activeBarrierToken = null
        released = true
        if (fifoQueue.isNotEmpty() || pendingCompletions.isNotEmpty() || closed) {
          toWake = detachWaitersLocked()
        }
      }
    }
    resumeWaiters(toWake)
    return released
  }

  /**
   * Retira o próximo item respeitando barreira ativa, prioridade FIFO e limite de justiça.
   */
  fun pollNext(): MailboxItem? = synchronized(lock) { pollNextLocked() }

  /**
   * Suspende de forma cancelável até existir um item entregável segundo as regras de barreira e
   * prioridade, ou até que o mailbox esteja fechado e sem itens entregáveis.
   */
  suspend fun receiveNext(): MailboxItem? {
    while (true) {
      currentCoroutineContext().ensureActive()
      val initialAttempt = synchronized(lock) { attemptPollOrCheckTerminalLocked() }
      when (initialAttempt) {
        is PollAttempt.Deliver -> return initialAttempt.item
        PollAttempt.TerminalEmpty -> return null
        PollAttempt.MustWait -> Unit
      }

      suspendCancellableCoroutine<Unit> { cont ->
        var immediateWake = false
        synchronized(lock) {
          if (attemptPollOrCheckTerminalLocked() != PollAttempt.MustWait) {
            immediateWake = true
          } else {
            waiters.addLast(cont)
          }
        }
        cont.invokeOnCancellation {
          synchronized(lock) {
            waiters.remove(cont)
          }
        }
        if (immediateWake && cont.isActive) {
          cont.resume(Unit)
        }
      }
    }
  }

  /**
   * Ponto único de linearização para publicação de [NoteEditorUiState] sob [lock].
   *
   * Impede que `Saved` ou `Idle` seja exposto se houver mutações persistíveis aceitas e ainda
   * não aplicadas, divergência de geração, gravação em curso, erro ou conflito pendente.
   */
  fun publishState(
    transform: (MailboxLinearizationSnapshot) -> LinearizedStatePublication,
  ): NoteEditorUiState =
    synchronized(lock) {
      val snapshot = buildSnapshotLocked()
      val publication = transform(snapshot)
      publishUnderLockLocked(publication)
    }

  /**
   * Sobrecarga direta para publicar um estado candidato mantendo todas as verificações sob [lock].
   */
  fun publishState(
    candidateState: NoteEditorUiState,
    appliedMutationSequence: Long,
    hasInFlightWrite: Boolean =
      candidateState.autosaveStatus is AutosaveStatus.Saving || candidateState.isConsolidating,
    hasPendingError: Boolean =
      candidateState.errorMessage != null || candidateState.autosaveStatus is AutosaveStatus.Error,
    hasUnresolvedConflict: Boolean =
      candidateState.activeConflict != null ||
        candidateState.autosaveStatus is AutosaveStatus.HeldPendingResolution,
    needsDraftRetention: Boolean = false,
  ): NoteEditorUiState =
    publishState {
      LinearizedStatePublication(
        candidateState = candidateState,
        appliedMutationSequence = appliedMutationSequence,
        hasInFlightWrite = hasInFlightWrite,
        hasPendingError = hasPendingError,
        hasUnresolvedConflict = hasUnresolvedConflict,
        needsDraftRetention = needsDraftRetention,
      )
    }

  /**
   * Fecha definitivamente a aceitação de novos comandos sem descartar itens já aceitos.
   * Consumidores recebem os itens pendentes já aceitos antes de encerrar.
   */
  fun close() {
    val toWake: List<CancellableContinuation<Unit>>
    synchronized(lock) {
      if (closed) return
      closed = true
      toWake = detachWaitersLocked()
    }
    resumeWaiters(toWake)
  }

  /**
   * Drena explicitamente quaisquer itens ainda presentes nas filas durante desligamento terminal.
   */
  fun drainPending(): List<MailboxItem> =
    synchronized(lock) {
      val drained = ArrayList<MailboxItem>(pendingCompletions.size + fifoQueue.size)
      while (pendingCompletions.isNotEmpty()) {
        drained.add(MailboxItem.Completion(pendingCompletions.removeFirst()))
      }
      while (fifoQueue.isNotEmpty()) {
        drained.add(MailboxItem.Command(fifoQueue.removeFirst()))
      }
      consecutiveFifoCounter = 0
      drained
    }

  private fun publishUnderLockLocked(publication: LinearizedStatePublication): NoteEditorUiState {
    val targetApplied = publication.appliedMutationSequence
    require(targetApplied >= appliedSequence) {
      "appliedMutationSequence não pode retroceder: atual=$appliedSequence, recebido=$targetApplied"
    }
    require(targetApplied <= acceptedSequence) {
      "appliedMutationSequence ($targetApplied) não pode exceder acceptedMutationSequence ($acceptedSequence)."
    }

    appliedSequence = targetApplied
    val enforcedState = enforceSyncedInvariantLocked(publication)
    _uiState.value = enforcedState
    return enforcedState
  }

  private fun enforceSyncedInvariantLocked(
    publication: LinearizedStatePublication,
  ): NoteEditorUiState {
    val candidate = publication.candidateState
    if (candidate.phase != NoteEditorSessionPhase.EDITING) {
      return candidate
    }

    val status = candidate.autosaveStatus
    if (status !is AutosaveStatus.Saved && status !is AutosaveStatus.Idle) {
      return candidate
    }

    val hasUnappliedMutations =
      acceptedSequence > publication.appliedMutationSequence || hasPendingPersistableInFifoLocked()
    val generationsAligned = candidate.stateGeneration == candidate.lastSyncedGeneration
    val noPendingOperations =
      !candidate.isConsolidating &&
        !publication.hasInFlightWrite &&
        !publication.hasPendingError &&
        candidate.errorMessage == null &&
        !publication.hasUnresolvedConflict &&
        candidate.activeConflict == null

    val isSafeToExposeSynced =
      when (status) {
        is AutosaveStatus.Saved ->
          !hasUnappliedMutations &&
            generationsAligned &&
            noPendingOperations &&
            status.syncedGeneration == candidate.lastSyncedGeneration

        AutosaveStatus.Idle ->
          !hasUnappliedMutations &&
            generationsAligned &&
            noPendingOperations &&
            !publication.needsDraftRetention

        AutosaveStatus.PendingDebounce,
        AutosaveStatus.Saving,
        AutosaveStatus.HeldPendingResolution,
        is AutosaveStatus.Error -> return candidate
      }

    if (isSafeToExposeSynced) {
      return candidate
    }

    val replacementStatus: AutosaveStatus =
      when {
        publication.hasPendingError || candidate.errorMessage != null ->
          AutosaveStatus.Error(
            message = candidate.errorMessage ?: "Erro pendente impede estado sincronizado.",
            isTerminal = false,
          )

        publication.hasInFlightWrite || candidate.isConsolidating -> AutosaveStatus.Saving

        publication.hasUnresolvedConflict || candidate.activeConflict != null ->
          AutosaveStatus.HeldPendingResolution

        else -> AutosaveStatus.PendingDebounce
      }

    return candidate.copy(autosaveStatus = replacementStatus)
  }

  private fun pollNextLocked(): MailboxItem? {
    if (activeBarrierToken != null) {
      if (pendingCompletions.isNotEmpty()) {
        consecutiveFifoCounter = 0
        return MailboxItem.Completion(pendingCompletions.removeFirst())
      }
      // Com barreira ativa e nenhuma conclusão pendente, fifoQueue permanece retida.
      return null
    }

    if (fifoQueue.isEmpty() && pendingCompletions.isEmpty()) {
      return null
    }

    if (fifoQueue.isEmpty()) {
      consecutiveFifoCounter = 0
      return MailboxItem.Completion(pendingCompletions.removeFirst())
    }

    if (pendingCompletions.isEmpty()) {
      incrementFifoCounterBoundedLocked()
      return MailboxItem.Command(fifoQueue.removeFirst())
    }

    // Ambas as filas contêm itens e não há barreira ativa: aplica limite de justiça contra starvation.
    return if (consecutiveFifoCounter >= maxConsecutiveFifoPolls) {
      consecutiveFifoCounter = 0
      MailboxItem.Completion(pendingCompletions.removeFirst())
    } else {
      incrementFifoCounterBoundedLocked()
      MailboxItem.Command(fifoQueue.removeFirst())
    }
  }

  private fun attemptPollOrCheckTerminalLocked(): PollAttempt {
    val item = pollNextLocked()
    if (item != null) {
      return PollAttempt.Deliver(item)
    }
    if (closed && fifoQueue.isEmpty() && pendingCompletions.isEmpty()) {
      return PollAttempt.TerminalEmpty
    }
    return PollAttempt.MustWait
  }

  private fun incrementFifoCounterBoundedLocked() {
    if (consecutiveFifoCounter < Int.MAX_VALUE) {
      consecutiveFifoCounter += 1
    }
  }

  private fun hasPendingPersistableInFifoLocked(): Boolean =
    fifoQueue.any { it.command.isPersistableMutation }

  private fun buildSnapshotLocked(): MailboxLinearizationSnapshot =
    MailboxLinearizationSnapshot(
      acceptedMutationSequence = acceptedSequence,
      appliedMutationSequence = appliedSequence,
      barrierActive = activeBarrierToken != null,
      isClosed = closed,
      fifoQueueSize = fifoQueue.size,
      pendingCompletionsSize = pendingCompletions.size,
      hasPendingPersistableMutationsInQueue = hasPendingPersistableInFifoLocked(),
      currentUiState = _uiState.value,
    )

  private fun shouldInvalidateSyncedStatusOnMutation(state: NoteEditorUiState): Boolean {
    if (state.phase != NoteEditorSessionPhase.EDITING) return false
    if (state.isConsolidating || state.isSessionTerminated) return false
    return state.autosaveStatus is AutosaveStatus.Saved || state.autosaveStatus == AutosaveStatus.Idle
  }

  private fun detachWaitersLocked(): List<CancellableContinuation<Unit>> {
    if (waiters.isEmpty()) return emptyList()
    val snapshot = waiters.toList()
    waiters.clear()
    return snapshot
  }

  private fun resumeWaiters(continuations: List<CancellableContinuation<Unit>>) {
    for (cont in continuations) {
      if (cont.isActive) {
        cont.resume(Unit)
      }
    }
  }

  private sealed interface PollAttempt {
    data class Deliver(val item: MailboxItem) : PollAttempt

    data object MustWait : PollAttempt

    data object TerminalEmpty : PollAttempt
  }

  companion object {
    const val DEFAULT_MAX_CONSECUTIVE_FIFO_POLLS: Int = 32
  }
}
