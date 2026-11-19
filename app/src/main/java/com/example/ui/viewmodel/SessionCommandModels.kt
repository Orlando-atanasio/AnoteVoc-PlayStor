package com.example.ui.viewmodel

import com.example.domain.editor.ConflictResolutionOption
import com.example.domain.editor.FolderAvailability
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.TextFormatStyle
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred

/**
 * Motivos explícitos de rejeição síncrona de comando em [CoalescingCommandMailbox.submit].
 */
enum class SubmitRejectionReason {
  MAILBOX_CLOSED,
  SEQUENCE_OVERFLOW,
}

/**
 * Resultado da tentativa de submissão de um [SessionCommand] no mailbox.
 */
sealed interface SubmitCommandResult {
  data class Accepted(val assignedMutationSequence: Long?) : SubmitCommandResult

  data class Rejected(val reason: SubmitRejectionReason) : SubmitCommandResult
}

/**
 * Resultado opcional entregue via [CompletableDeferred] para comandos com confirmação explícita.
 */
sealed interface SessionCommandOutcome {
  data object Completed : SessionCommandOutcome

  data class Rejected(val reason: SubmitRejectionReason) : SessionCommandOutcome

  data class Failed(val message: String, val cause: Throwable? = null) : SessionCommandOutcome

  data object Cancelled : SessionCommandOutcome
}

/**
 * Comandos da sessão do editor roteados pelo ponto único de serialização do mailbox.
 *
 * Cada comando declara expressamente se pode alterar o estado persistível da nota/rascunho via
 * [isPersistableMutation]. Apenas comandos com [isPersistableMutation] == `true` incrementam
 * `acceptedMutationSequence` e invalidam imediatamente estados publicados como `Saved` ou `Idle`.
 */
sealed interface SessionCommand {
  val isPersistableMutation: Boolean

  val ackDeferred: CompletableDeferred<SessionCommandOutcome>?
    get() = null

  data class EditTitle(val title: String) : SessionCommand {
    override val isPersistableMutation: Boolean = true
  }

  data class InsertText(val text: String) : SessionCommand {
    override val isPersistableMutation: Boolean = true
  }

  data class ApplyTextChange(
    val blockId: String,
    val newText: String,
    val selectionStart: Int,
    val selectionEnd: Int = selectionStart,
  ) : SessionCommand {
    init {
      require(blockId.isNotBlank()) { "blockId não pode ficar em branco." }
      require(selectionStart >= 0) { "selectionStart não pode ser negativo: $selectionStart" }
      require(selectionEnd >= 0) { "selectionEnd não pode ser negativo: $selectionEnd" }
    }

    override val isPersistableMutation: Boolean = true
  }

  data object PressEnter : SessionCommand {
    override val isPersistableMutation: Boolean = true
  }

  data object PressBackspace : SessionCommand {
    override val isPersistableMutation: Boolean = true
  }

  data class ToggleFormat(val style: TextFormatStyle) : SessionCommand {
    override val isPersistableMutation: Boolean = true
  }

  data class ApplyFormat(val style: TextFormatStyle) : SessionCommand {
    override val isPersistableMutation: Boolean = true
  }

  data class RemoveFormat(val style: TextFormatStyle) : SessionCommand {
    override val isPersistableMutation: Boolean = true
  }

  data object ToggleBulletList : SessionCommand {
    override val isPersistableMutation: Boolean = true
  }

  data object ToggleNumberedList : SessionCommand {
    override val isPersistableMutation: Boolean = true
  }

  data object ToggleChecklist : SessionCommand {
    override val isPersistableMutation: Boolean = true
  }

  data class ToggleChecklistItem(val blockId: String) : SessionCommand {
    init {
      require(blockId.isNotBlank()) { "blockId não pode ficar em branco." }
    }

    override val isPersistableMutation: Boolean = true
  }

  data object Undo : SessionCommand {
    override val isPersistableMutation: Boolean = true
  }

  data object Redo : SessionCommand {
    override val isPersistableMutation: Boolean = true
  }

  data class ChangeFolder(val folderId: Long?) : SessionCommand {
    init {
      require(folderId == null || folderId > 0L) {
        "folderId deve ser nulo (raiz) ou positivo: $folderId"
      }
    }

    override val isPersistableMutation: Boolean = true
  }

  data object ConfirmFallbackToRoot : SessionCommand {
    override val isPersistableMutation: Boolean = true
  }

  data class ChangePaperStyle(val paperStyle: NotePaperStyle) : SessionCommand {
    override val isPersistableMutation: Boolean = true
  }

  data class SetPinned(val isPinned: Boolean) : SessionCommand {
    override val isPersistableMutation: Boolean = true
  }

  data object TogglePinned : SessionCommand {
    override val isPersistableMutation: Boolean = true
  }

  // --- Comandos não persistíveis (seleção, observação, consulta, barreiras estruturais e ciclo de vida) ---

  data class SelectBlock(
    val blockId: String,
    val start: Int,
    val end: Int = start,
    override val ackDeferred: CompletableDeferred<SessionCommandOutcome>? = null,
  ) : SessionCommand {
    init {
      require(blockId.isNotBlank()) { "blockId não pode ficar em branco." }
      require(start >= 0) { "start não pode ser negativo: $start" }
      require(end >= start) { "end ($end) não pode ser menor que start ($start)." }
    }

    override val isPersistableMutation: Boolean = false
  }

  class FolderAvailabilityObserved private constructor(
    val availabilityById: Map<Long, FolderAvailability>,
  ) : SessionCommand {
    override val isPersistableMutation: Boolean = false

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is FolderAvailabilityObserved) return false
      return availabilityById == other.availabilityById
    }

    override fun hashCode(): Int = availabilityById.hashCode()

    override fun toString(): String = "FolderAvailabilityObserved(availabilityById=$availabilityById)"

    companion object {
      operator fun invoke(
        availabilityById: Map<Long, FolderAvailability>,
      ): FolderAvailabilityObserved =
        FolderAvailabilityObserved(Collections.unmodifiableMap(availabilityById.toMap()))
    }
  }

  data class DebounceTimeout(val expectedGeneration: Long) : SessionCommand {
    init {
      require(expectedGeneration >= 0L) {
        "expectedGeneration não pode ser negativo: $expectedGeneration"
      }
    }

    override val isPersistableMutation: Boolean = false
  }

  data class FlushAutosave(
    override val ackDeferred: CompletableDeferred<SessionCommandOutcome>? = null,
  ) : SessionCommand {
    override val isPersistableMutation: Boolean = false
  }

  data class ResolveConflict(
    val option: ConflictResolutionOption,
    override val ackDeferred: CompletableDeferred<SessionCommandOutcome>? = null,
  ) : SessionCommand {
    override val isPersistableMutation: Boolean = false
  }

  data class ConsolidateNote(
    val allowFallbackToRoot: Boolean = false,
    override val ackDeferred: CompletableDeferred<SessionCommandOutcome>? = null,
  ) : SessionCommand {
    override val isPersistableMutation: Boolean = false
  }

  data class DiscardDraftAndClose(
    override val ackDeferred: CompletableDeferred<SessionCommandOutcome>? = null,
  ) : SessionCommand {
    override val isPersistableMutation: Boolean = false
  }

  data class CloseSession(
    override val ackDeferred: CompletableDeferred<SessionCommandOutcome>? = null,
  ) : SessionCommand {
    override val isPersistableMutation: Boolean = false
  }

  data class PersistExistingNotePin(
    val isPinned: Boolean,
    override val ackDeferred: CompletableDeferred<SessionCommandOutcome>? = null,
  ) : SessionCommand {
    override val isPersistableMutation: Boolean = false
  }
}

/**
 * Envelope FIFO associando cada comando aceito à sua sequência monotônica de mutação (quando aplicável).
 */
data class QueuedSessionCommand(
  val command: SessionCommand,
  val mutationSequence: Long?,
) {
  init {
    if (command.isPersistableMutation) {
      require(mutationSequence != null && mutationSequence > 0L) {
        "Comando persistível exige mutationSequence positivo, recebido: $mutationSequence"
      }
    } else {
      require(mutationSequence == null) {
        "Comando não persistível não deve receber mutationSequence, recebido: $mutationSequence"
      }
    }
  }
}

/**
 * Conclusões assíncronas enfileiradas em `pendingCompletions` após execução fora do monitor.
 */
sealed interface PersistenceCompletion {
  val operationId: Long

  data class CheckpointUpsert(
    override val operationId: Long,
    val targetGeneration: Long,
    val attemptedCheckpointRevision: Long,
    val persistedCheckpointRevision: Long? = null,
    val checkpointAt: Long? = null,
    val isIgnoredStaleRevision: Boolean = false,
    val failure: Throwable? = null,
  ) : PersistenceCompletion

  data class CheckpointDelete(
    override val operationId: Long,
    val targetGeneration: Long,
    val failure: Throwable? = null,
  ) : PersistenceCompletion

  class FolderAvailabilityLookup private constructor(
    override val operationId: Long,
    val availabilityById: Map<Long, FolderAvailability>,
  ) : PersistenceCompletion {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is FolderAvailabilityLookup) return false
      return operationId == other.operationId && availabilityById == other.availabilityById
    }

    override fun hashCode(): Int {
      var result = operationId.hashCode()
      result = 31 * result + availabilityById.hashCode()
      return result
    }

    override fun toString(): String =
      "FolderAvailabilityLookup(operationId=$operationId, availabilityById=$availabilityById)"

    companion object {
      operator fun invoke(
        operationId: Long,
        availabilityById: Map<Long, FolderAvailability>,
      ): FolderAvailabilityLookup =
        FolderAvailabilityLookup(
          operationId = operationId,
          availabilityById = Collections.unmodifiableMap(availabilityById.toMap()),
        )
    }
  }

  data class StructuralOperation(
    override val operationId: Long,
    val barrierToken: BarrierToken,
    val succeeded: Boolean,
    val terminal: Boolean,
    val failure: Throwable? = null,
  ) : PersistenceCompletion

  data class StructuralRetryRequested(
    override val operationId: Long,
    val barrierToken: BarrierToken,
  ) : PersistenceCompletion
}

/**
 * Item entregue pelo [CoalescingCommandMailbox] ao consumidor da sessão.
 */
sealed interface MailboxItem {
  data class Command(val queued: QueuedSessionCommand) : MailboxItem

  data class Completion(val completion: PersistenceCompletion) : MailboxItem
}

/**
 * Motivo explícito de ativação de barreira estrutural no mailbox.
 */
enum class BarrierReason {
  CONSOLIDATION,
  CONFLICT_RESOLUTION,
  DISCARD_DRAFT,
  PIN_UPDATE,
}

/**
 * Token único de posse de barreira ativa para impedir desbloqueio cruzado ou indevido.
 */
class BarrierToken internal constructor(
  val id: Long,
  val reason: BarrierReason,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is BarrierToken) return false
    return id == other.id && reason == other.reason
  }

  override fun hashCode(): Int {
    var result = id.hashCode()
    result = 31 * result + reason.hashCode()
    return result
  }

  override fun toString(): String = "BarrierToken(id=$id, reason=$reason)"
}

/**
 * Retrato atômico obtido sob `mailbox.lock` para linearizar decisões de publicação de estado.
 */
data class MailboxLinearizationSnapshot(
  val acceptedMutationSequence: Long,
  val appliedMutationSequence: Long,
  val barrierActive: Boolean,
  val isClosed: Boolean,
  val fifoQueueSize: Int,
  val pendingCompletionsSize: Int,
  val hasPendingPersistableMutationsInQueue: Boolean,
  val currentUiState: NoteEditorUiState,
) {
  fun hasUnappliedPersistableMutations(
    forAppliedSequence: Long = appliedMutationSequence,
  ): Boolean =
    acceptedMutationSequence > forAppliedSequence || hasPendingPersistableMutationsInQueue
}

/**
 * Solicitação de publicação de estado submetida ao ponto único de linearização.
 */
data class LinearizedStatePublication(
  val candidateState: NoteEditorUiState,
  val appliedMutationSequence: Long,
  val hasInFlightWrite: Boolean =
    candidateState.autosaveStatus is AutosaveStatus.Saving || candidateState.isConsolidating,
  val hasPendingError: Boolean =
    candidateState.errorMessage != null || candidateState.autosaveStatus is AutosaveStatus.Error,
  val hasUnresolvedConflict: Boolean =
    candidateState.activeConflict != null ||
      candidateState.autosaveStatus is AutosaveStatus.HeldPendingResolution,
  val needsDraftRetention: Boolean = false,
)
