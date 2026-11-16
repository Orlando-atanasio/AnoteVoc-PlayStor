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
 * Caixa de correio coordenada (modelo MPSC — múltiplos produtores e consumidor único) com ponto
 * único de linearização (`lock`) entre:
 * - aceitação síncrona de comandos e sequência monotônica `acceptedMutationSequence`;
 * - fila FIFO de comandos e fila `pendingCompletions` com barreira estrutural e anti-starvation;
 * - publicação linearizada de [NoteEditorUiState] impedindo falsos estados `Saved`/`Idle`.
 *
 * Contrato de consumidor único:
 * - Esta classe pressupõe **no máximo um consumidor ativo por vez** chamando [receiveNext] ou
 *   [pollNext], devendo o loop consumidor ser estritamente serializado pelo futuro coordenador.
 * - Não existe trava/restrição em tempo de execução impedindo múltiplas chamadas simultâneas a
 *   [receiveNext]; caso múltiplos consumidores ativos coexistissem, um cancelamento com
 *   requeue (`requeueUndeliveredItem`) poderia violar a ordem FIFO entre comandos.
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
  private val waiters = ArrayDeque<CancellableContinuation<PollAttempt>>()

  @Volatile internal var beforeSuspendRegistrationHook: (() -> Unit)? = null
  @Volatile internal var afterImmediateResumeHook: (() -> Unit)? = null

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

  val activeBarrierReason: BarrierReason?
    get() = synchronized(lock) { activeBarrierToken?.reason }

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
    var toWake: List<CancellableContinuation<PollAttempt>> = emptyList()

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
    val toWake: List<CancellableContinuation<PollAttempt>>
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
   *
   * Responsabilidade contratual do coordenador: só deve ser chamado pelo detentor legítimo após a
   * operação estrutural protegida pela barreira (consolidação, resolução de conflito ou descarte)
   * ter terminado definitivamente; nunca deve ser usado para interromper operação ainda em curso.
   * Quando liberada e houver itens retidos em `fifoQueue` ou fechamento pendente, acorda consumidores.
   */
  fun releaseBarrier(token: BarrierToken): Boolean {
    var released = false
    var toWake: List<CancellableContinuation<PollAttempt>> = emptyList()
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
   * prioridade, ou até que o mailbox esteja fechado, sem barreira ativa e sem itens entregáveis.
   *
   * Contrato de consumidor único:
   * - Não pode haver mais de uma chamada consumidora ativa a [receiveNext] simultaneamente; o
   *   consumo deve ser serializado pelo futuro coordenador da sessão.
   * - Não há bloqueio em tempo de execução contra múltiplos consumidores simultâneos; sob
   *   consumidor único, a recolocação na cabeça da fila via `requeueUndeliveredItem` em caso de
   *   cancelamento preserva integralmente a ordem FIFO.
   *
   * Preserva integralmente o resultado da segunda tentativa atômica dentro de
   * [suspendCancellableCoroutine] (`Deliver`, `TerminalEmpty` ou `MustWait`), garantindo que nenhum
   * item retirado seja descartado nem mesmo em caso de cancelamento concorrente imediato.
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

      beforeSuspendRegistrationHook?.invoke()

      val suspendedAttempt: PollAttempt =
        suspendCancellableCoroutine { cont ->
          var immediateAttempt: PollAttempt? = null
          synchronized(lock) {
            val secondAttempt = attemptPollOrCheckTerminalLocked()
            if (secondAttempt == PollAttempt.MustWait) {
              waiters.addLast(cont)
            } else {
              immediateAttempt = secondAttempt
            }
          }
          cont.invokeOnCancellation {
            synchronized(lock) {
              waiters.remove(cont)
            }
          }
          val resolved = immediateAttempt
          if (resolved != null) {
            if (cont.isActive) {
              cont.resume(resolved) { _, value, _ ->
                if (value is PollAttempt.Deliver) {
                  requeueUndeliveredItem(value.item)
                }
              }
              afterImmediateResumeHook?.invoke()
            } else if (resolved is PollAttempt.Deliver) {
              requeueUndeliveredItem(resolved.item)
            }
          }
        }

      when (suspendedAttempt) {
        is PollAttempt.Deliver -> return suspendedAttempt.item
        PollAttempt.TerminalEmpty -> return null
        PollAttempt.MustWait -> Unit
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
   * Fecha definitivamente a aceitação de novos comandos sem descartar itens já aceitos e sem
   * interromper inseguramente uma operação estrutural em curso.
   *
   * Contrato coordenado de encerramento com barreira ativa:
   * - Novos comandos submetidos após [close] são rejeitados com [SubmitRejectionReason.MAILBOX_CLOSED].
   * - Conclusões assíncronas (`pendingCompletions`) continuam sendo aceitas e priorizadas para que
   *   a operação estrutural ativa possa concluir sem perda de estado.
   * - Se houver barreira estrutural ativa, `fifoQueue` permanece protegida até que o detentor
   *   legítimo libere a barreira via [releaseBarrier] ou [closeAndReleaseBarrier], evitando
   *   desbloqueio indevido; após a liberação, todos os comandos já aceitos em `fifoQueue` são
   *   entregues em ordem antes de [receiveNext] retornar `null`.
   */
  fun close() {
    val toWake: List<CancellableContinuation<PollAttempt>>
    synchronized(lock) {
      if (closed) return
      closed = true
      toWake =
        if (activeBarrierToken == null || pendingCompletions.isNotEmpty()) {
          detachWaitersLocked()
        } else {
          emptyList()
        }
    }
    resumeWaiters(toWake)
  }

  /**
   * Fecha definitivamente a aceitação de novos comandos e libera atomicamente a barreira estrutural
   * ativa somente se [token] corresponder exatamente ao token ativo atual.
   *
   * Contrato explícito de responsabilidade do coordenador:
   * - Este método **só pode ser chamado pelo detentor legítimo depois que a operação estrutural
   *   protegida pela barreira (consolidação, descarte ou resolução de conflito) tiver terminado
   *   definitivamente** (sucesso, falha definitiva ou limpeza final garantida após encerramento da
   *   escrita/transação).
   * - A liberação da barreira **nunca** deve ser usada para interromper, cancelar ou contornar uma
   *   operação estrutural ainda em andamento.
   *
   * Comportamento para token válido ou inválido:
   * - O fechamento da aceitação de novos comandos (`closed = true`) ocorre incondicionalmente na
   *   chamada, mesmo quando [token] é inválido ou divergente (passando a rejeitar novas submissões
   *   com [SubmitRejectionReason.MAILBOX_CLOSED]).
   * - Retorna `true` apenas quando a barreira ativa foi efetivamente liberada por este [token].
   * - Um [token] inválido, divergente ou ausente não desbloqueia a barreira ativa (retorna `false`),
   *   mantendo a proteção da operação estrutural intacta mesmo após o fechamento da aceitação.
   */
  fun closeAndReleaseBarrier(token: BarrierToken): Boolean {
    var released = false
    val toWake: List<CancellableContinuation<PollAttempt>>
    synchronized(lock) {
      val wasClosed = closed
      closed = true
      if (activeBarrierToken == token) {
        activeBarrierToken = null
        released = true
      }
      val canAdvance = activeBarrierToken == null || pendingCompletions.isNotEmpty()
      toWake =
        if (released || (!wasClosed && canAdvance)) {
          detachWaitersLocked()
        } else {
          emptyList()
        }
    }
    resumeWaiters(toWake)
    return released
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
    if (
      closed &&
        activeBarrierToken == null &&
        fifoQueue.isEmpty() &&
        pendingCompletions.isEmpty()
    ) {
      return PollAttempt.TerminalEmpty
    }
    return PollAttempt.MustWait
  }

  private fun requeueUndeliveredItem(item: MailboxItem) {
    val toWake: List<CancellableContinuation<PollAttempt>>
    synchronized(lock) {
      when (item) {
        is MailboxItem.Command -> {
          fifoQueue.addFirst(item.queued)
          if (consecutiveFifoCounter > 0) {
            consecutiveFifoCounter -= 1
          }
        }
        is MailboxItem.Completion -> {
          pendingCompletions.addFirst(item.completion)
        }
      }
      toWake =
        if (activeBarrierToken == null || item is MailboxItem.Completion) {
          detachWaitersLocked()
        } else {
          emptyList()
        }
    }
    resumeWaiters(toWake)
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

  private fun detachWaitersLocked(): List<CancellableContinuation<PollAttempt>> {
    if (waiters.isEmpty()) return emptyList()
    val snapshot = waiters.toList()
    waiters.clear()
    return snapshot
  }

  private fun resumeWaiters(continuations: List<CancellableContinuation<PollAttempt>>) {
    for (cont in continuations) {
      if (cont.isActive) {
        cont.resume(PollAttempt.MustWait)
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
