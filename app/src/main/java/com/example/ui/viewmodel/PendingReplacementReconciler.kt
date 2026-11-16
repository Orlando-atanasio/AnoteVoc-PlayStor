package com.example.ui.viewmodel

import com.example.data.local.mapper.NoteContentCodec
import com.example.domain.editor.NoteEditorBaseline
import com.example.domain.editor.NoteEditorSessionEvaluator
import com.example.domain.editor.NoteEditorSessionIdentity
import com.example.domain.model.NoteContentState
import com.example.domain.model.NoteRecoveryDraft
import com.example.domain.repository.NoteRecoveryDraftRepository
import com.example.domain.repository.NoteRepository
import kotlinx.coroutines.CancellationException

sealed interface ReplacementReconciliationResult {
  /**
   * Estado A: `oldDraft` permanece presente e `newDraft` está ausente.
   * Reversão no Registry concluída, tracker limpo e novo executor limpo fornecido (quando ativo).
   */
  data class RevertedToOld(
    val restoredExecutor: SingleFlightRoomPersistenceExecutor?,
    val preservedOldDraft: NoteRecoveryDraft,
    val preservedLease: SessionLeaseToken,
  ) : ReplacementReconciliationResult

  /**
   * Estado B (ou pós-substituição sem commit prévio no Registry):
   * `oldDraft` ausente e `newDraft` compatível confirmado no Room.
   * Executor sincronizado via chamada idempotente (zero escrita no SQLite) e transferência
   * efetivada atomicamente no [ActiveSessionRegistry].
   */
  data class PromotedToNew(
    val newIdentity: NoteEditorSessionIdentity,
    val newLease: SessionLeaseToken,
    val newDraft: NoteRecoveryDraft,
  ) : ReplacementReconciliationResult

  /**
   * Estado C / E / divergência grave ou estado de transferência incompatível no Registry.
   * Nenhuma sobrescrita ou exclusão automática é executada.
   */
  data class DivergentAnomaly(
    val stateCode: String,
    val message: String,
    val cause: Throwable? = null,
  ) : ReplacementReconciliationResult

  /**
   * Estado D: nem `oldDraft` nem `newDraft` existem no Room.
   * Nunca chama `replaceCheckpointWithNewNote` para evitar recriação fantasma.
   */
  data class NeitherPresent(
    val message: String,
  ) : ReplacementReconciliationResult

  /**
   * Estado F: falha de leitura no banco impediu determinar o estado persistido no Room.
   * Preserva a entrada `PENDING` no [ReplacementHandoffTracker] para descoberta posterior e impede
   * retentativas cegas.
   */
  data class IndeterminateReadFailure(
    val message: String,
    val cause: Throwable,
  ) : ReplacementReconciliationResult
}

data class UncommittedReplacementContext(
  val pendingState: PendingReplacementState,
  val transferToken: IdentityTransferToken,
  val handoffId: Long,
  val targetBaseline: NoteEditorBaseline.NewNote,
)

/**
 * Reconciliador determinístico de transferências `SAVE_DRAFT_AS_NEW_NOTE` interrompidas,
 * falhas indeterminadas ou sem commit concluído no [ActiveSessionRegistry], cobrindo os
 * Estados A–F do contrato técnico da Subetapa 4.3.3-D.
 */
class PendingReplacementReconciler(
  private val noteRepository: NoteRepository,
  private val draftRepository: NoteRecoveryDraftRepository,
  private val activeSessionRegistry: ActiveSessionRegistry,
  private val handoffTracker: ReplacementHandoffTracker,
) {
  suspend fun reconcile(
    executor: SingleFlightRoomPersistenceExecutor,
    currentLease: SessionLeaseToken,
    context: UncommittedReplacementContext,
    forShutdown: Boolean,
  ): ReplacementReconciliationResult {
    val execSnapshot = executor.snapshot()
    val pending = execSnapshot.pendingReplacement ?: context.pendingState

    // Caso em que o executor já concluiu a substituição e promoveu trackedIdentity, mas
    // commitIdentityTransfer no Registry havia falhado ou sido interrompido antes de retornar.
    if (!execSnapshot.hasPendingReplacement && execSnapshot.draftId == pending.newPayload.draftId) {
      return completePendingRegistryCommitOnly(
        executor = executor,
        context = context,
        pending = pending,
      )
    }

    val persistedOld: NoteRecoveryDraft?
    val persistedNew: NoteRecoveryDraft?
    try {
      persistedOld = draftRepository.getCheckpointById(pending.oldDraftId)
      persistedNew = draftRepository.getCheckpointById(pending.newPayload.draftId)
    } catch (c: CancellationException) {
      throw c
    } catch (t: Throwable) {
      // Estado F: falha de leitura -> preserva associação pendente e não libera prematuramente.
      handoffTracker.preservePending(pending.oldDraftId, context.handoffId)
      return ReplacementReconciliationResult.IndeterminateReadFailure(
        message =
          t.message ?: "Falha de leitura ao reconciliar substituição indeterminada no Room.",
        cause = t,
      )
    }

    // Estado C: ambos os checkpoints coexistem -> anomalia; não sobrescrever nem excluir.
    if (persistedOld != null && persistedNew != null) {
      handoffTracker.clearByHandoffId(pending.oldDraftId, context.handoffId)
      activeSessionRegistry.rollbackIdentityTransfer(context.transferToken)
      return ReplacementReconciliationResult.DivergentAnomaly(
        stateCode = "STATE_C",
        message =
          "Inconsistência crítica: checkpoint antigo '${pending.oldDraftId}' e novo " +
            "'${pending.newPayload.draftId}' coexistem no banco.",
      )
    }

    // Estado A: antigo presente e novo ausente -> substituição não foi efetivada.
    if (persistedOld != null && persistedNew == null) {
      if (
        persistedOld.sessionId != pending.expectedOldSessionId ||
          persistedOld.targetNoteId != pending.expectedOldTargetNoteId
      ) {
        handoffTracker.clearByHandoffId(pending.oldDraftId, context.handoffId)
        activeSessionRegistry.rollbackIdentityTransfer(context.transferToken)
        return ReplacementReconciliationResult.DivergentAnomaly(
          stateCode = "STATE_A_DIVERGENT_OLD",
          message =
            "Checkpoint antigo '${pending.oldDraftId}' diverge da sessão ou nota esperada.",
        )
      }

      handoffTracker.clearByHandoffId(pending.oldDraftId, context.handoffId)
      val rollbackRes = activeSessionRegistry.rollbackIdentityTransfer(context.transferToken)
      val preservedLease =
        when (rollbackRes) {
          is RollbackTransferResult.RolledBack -> rollbackRes.preservedSourceLease
          RollbackTransferResult.AlreadyRolledBack -> currentLease
          RollbackTransferResult.RejectedAlreadyCommitted,
          RollbackTransferResult.InvalidOrObsoleteToken -> {
            return ReplacementReconciliationResult.DivergentAnomaly(
              stateCode = "STATE_A_REGISTRY_MISMATCH",
              message = "Estado do token de transferência incompatível com reversão no Estado A.",
            )
          }
        }

      val restoredExecutor =
        if (forShutdown) {
          null
        } else {
          SingleFlightRoomPersistenceExecutor(
            draftRepository = draftRepository,
            noteRepository = noteRepository,
            initialIdentity = pending.oldIdentity,
            initialPresence = CheckpointPresence.PRESENT,
            initialConfirmedRevision = persistedOld.checkpointRevision,
            initialHighestAllocatedRevision =
              maxOf(execSnapshot.highestAllocatedRevision, persistedOld.checkpointRevision),
            initialLastCheckpointAt = persistedOld.checkpointAt,
          )
        }

      return ReplacementReconciliationResult.RevertedToOld(
        restoredExecutor = restoredExecutor,
        preservedOldDraft = persistedOld,
        preservedLease = preservedLease,
      )
    }

    // Estado D: ambos ausentes -> nunca recriar via replaceCheckpointWithNewNote.
    if (persistedOld == null && persistedNew == null) {
      handoffTracker.clearByHandoffId(pending.oldDraftId, context.handoffId)
      activeSessionRegistry.rollbackIdentityTransfer(context.transferToken)
      return ReplacementReconciliationResult.NeitherPresent(
        message =
          "Nem o checkpoint de origem '${pending.oldDraftId}' nem o destino " +
            "'${pending.newPayload.draftId}' existem no banco.",
      )
    }

    // persistedOld == null && persistedNew != null: distinguir Estado B vs Estado E.
    val candidateNew = requireNotNull(persistedNew)
    if (!matchesExpectedNewDraft(candidateNew, pending.newPayload, pending.newDraftRevision)) {
      // Estado E: destino divergente -> preserva registro no banco, invalida tracker, rejeita.
      handoffTracker.clearByHandoffId(pending.oldDraftId, context.handoffId)
      activeSessionRegistry.rollbackIdentityTransfer(context.transferToken)
      return ReplacementReconciliationResult.DivergentAnomaly(
        stateCode = "STATE_E",
        message =
          "Checkpoint de destino '${candidateNew.draftId}' diverge do payload esperado; " +
            "nenhuma sobrescrita foi executada.",
      )
    }

    // Estado B: substituição já efetivada no Room.
    // Usa repetição integralmente idempotente no executor para sincronizar trackedIdentity e limpar
    // pendingReplacement sem repetir escrita no SQLite (NoteRecoveryDraftRepositoryImpl Caso B).
    return when (
      val syncExec =
        executor.replaceCheckpointWithNewNote(
          oldDraftId = pending.oldDraftId,
          expectedOldSessionId = pending.expectedOldSessionId,
          expectedOldTargetNoteId = pending.expectedOldTargetNoteId,
          newPayload = pending.newPayload,
          newDraftRevision = pending.newDraftRevision,
        )
    ) {
      is ReplaceCheckpointExecutionResult.AlreadyReplaced ->
        commitRegistryAndFinalizeNew(
          context = context,
          pending = pending,
          resolvedDraft = syncExec.draft,
        )

      is ReplaceCheckpointExecutionResult.Replaced ->
        commitRegistryAndFinalizeNew(
          context = context,
          pending = pending,
          resolvedDraft = syncExec.draft,
        )

      is ReplaceCheckpointExecutionResult.RevisionOverflow ->
        ReplacementReconciliationResult.DivergentAnomaly(
          stateCode = "STATE_B_REVISION_OVERFLOW",
          message = "Limite de revisão impediu sincronização idempotente do Estado B.",
        )

      is ReplaceCheckpointExecutionResult.Failed -> {
        // Falha ao tentar leitura idempotente no Estado B -> trata como falha indeterminada (F).
        handoffTracker.preservePending(pending.oldDraftId, context.handoffId)
        ReplacementReconciliationResult.IndeterminateReadFailure(
          message =
            syncExec.cause.message
              ?: "Falha ao sincronizar executor via repetição idempotente no Estado B.",
          cause = syncExec.cause,
        )
      }
    }
  }

  private suspend fun completePendingRegistryCommitOnly(
    executor: SingleFlightRoomPersistenceExecutor,
    context: UncommittedReplacementContext,
    pending: PendingReplacementState,
  ): ReplacementReconciliationResult {
    val persistedNew =
      try {
        draftRepository.getCheckpointById(pending.newPayload.draftId)
      } catch (c: CancellationException) {
        throw c
      } catch (t: Throwable) {
        handoffTracker.preservePending(pending.oldDraftId, context.handoffId)
        return ReplacementReconciliationResult.IndeterminateReadFailure(
          message = t.message ?: "Falha ao confirmar novo checkpoint após substituição.",
          cause = t,
        )
      }

    if (
      persistedNew == null ||
        !matchesExpectedNewDraft(persistedNew, pending.newPayload, pending.newDraftRevision)
    ) {
      return ReplacementReconciliationResult.DivergentAnomaly(
        stateCode = "POST_REPLACE_MISSING_OR_DIVERGENT",
        message =
          "Executor avançou para '${pending.newPayload.draftId}', mas o checkpoint no Room está ausente ou divergente.",
      )
    }

    return commitRegistryAndFinalizeNew(
      context = context,
      pending = pending,
      resolvedDraft = persistedNew,
    )
  }

  private fun commitRegistryAndFinalizeNew(
    context: UncommittedReplacementContext,
    pending: PendingReplacementState,
    resolvedDraft: NoteRecoveryDraft,
  ): ReplacementReconciliationResult {
    val newIdentity =
      NoteEditorSessionIdentity(
        draftId = resolvedDraft.draftId,
        sessionId = resolvedDraft.sessionId,
        targetNoteId = null,
      )

    return when (val commitRes = activeSessionRegistry.commitIdentityTransfer(context.transferToken)) {
      is CommitTransferResult.Committed -> {
        handoffTracker.markResolved(pending.oldDraftId, context.handoffId)
        ReplacementReconciliationResult.PromotedToNew(
          newIdentity = newIdentity,
          newLease = commitRes.newLease,
          newDraft = resolvedDraft,
        )
      }
      is CommitTransferResult.AlreadyCommitted -> {
        handoffTracker.markResolved(pending.oldDraftId, context.handoffId)
        ReplacementReconciliationResult.PromotedToNew(
          newIdentity = newIdentity,
          newLease = commitRes.newLease,
          newDraft = resolvedDraft,
        )
      }
      CommitTransferResult.RejectedAlreadyRolledBack,
      CommitTransferResult.InvalidOrObsoleteToken -> {
        ReplacementReconciliationResult.DivergentAnomaly(
          stateCode = "REGISTRY_COMMIT_REJECTED",
          message =
            "Transferência confirmada no Room para '${newIdentity.draftId}', mas token no Registry estava inválido ou revertido.",
        )
      }
    }
  }

  companion object {
    fun matchesExpectedNewDraft(
      persisted: NoteRecoveryDraft,
      expectedPayload: CheckpointSavePayload,
      expectedRevision: Long,
    ): Boolean {
      if (persisted.draftId != expectedPayload.draftId) return false
      if (persisted.sessionId != expectedPayload.sessionId) return false
      if (persisted.targetNoteId != null) return false
      if (persisted.folderId != expectedPayload.folderId) return false
      if (persisted.initialFolderId != expectedPayload.initialFolderId) return false
      if (persisted.fallbackSourceFolderId != expectedPayload.fallbackSourceFolderId) return false
      if (persisted.isPinned != expectedPayload.isPinned) return false
      if (persisted.title != expectedPayload.title) return false
      if (persisted.paperStyle != expectedPayload.paperStyle) return false
      if (persisted.initialPaperStyle != expectedPayload.initialPaperStyle) return false
      if (persisted.baseRevision != 0L) return false
      if (persisted.baseNoteUpdatedAt != null) return false
      if (persisted.checkpointRevision != expectedRevision) return false
      val doc = (persisted.contentState as? NoteContentState.Valid)?.document ?: return false
      val persistedJson =
        NoteContentCodec.encode(NoteEditorSessionEvaluator.canonicalizeDocument(doc))
      val expectedJson =
        NoteContentCodec.encode(
          NoteEditorSessionEvaluator.canonicalizeDocument(expectedPayload.content)
        )
      return persistedJson == expectedJson
    }
  }
}
