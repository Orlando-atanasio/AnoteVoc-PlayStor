package com.example.ui.viewmodel

import com.example.domain.editor.ConflictResolutionOption
import com.example.domain.editor.ConsolidationReadiness
import com.example.domain.editor.FolderAvailability
import com.example.domain.editor.NoteEditorBaseline
import com.example.domain.editor.NoteEditorSessionEvaluator
import com.example.domain.editor.NoteEditorSessionIdentity
import com.example.domain.editor.RecoveryConflict
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NoteContentState
import com.example.domain.model.NoteRecoveryDraftKeys
import com.example.domain.repository.FolderRepository
import com.example.domain.repository.InvalidRecoveryDraftException
import com.example.domain.repository.NoteRecoveryDraftRepository
import com.example.domain.repository.NoteRepository

internal sealed interface StructuralRunnerResult {
  data class Succeeded(val data: StructuralOutcomeData) : StructuralRunnerResult

  data class Failed(
    val cause: Throwable,
    val fallbackData: StructuralOutcomeData? = null,
  ) : StructuralRunnerResult
}

/**
 * Executor isolado de operações estruturais (`ConsolidateNote`, `ResolveConflict`,
 * `DiscardDraftAndClose`), rodando fora do loop consumidor e sem acoplar I/O ao lock do mailbox.
 */
internal class SessionStructuralRunner(
  private val noteRepository: NoteRepository,
  private val folderRepository: FolderRepository,
  private val draftRepository: NoteRecoveryDraftRepository,
  private val activeSessionRegistry: ActiveSessionRegistry,
  private val handoffTracker: ReplacementHandoffTracker,
  private val replacementReconciler: PendingReplacementReconciler,
  private val sessionIdGenerator: () -> String,
  private val onLeaseCommitted: (SessionLeaseToken) -> Unit = {},
) {
  suspend fun runConsolidateNote(
    command: SessionCommand.ConsolidateNote,
    runtimeSnapshot: SessionRuntimeState,
    executor: SingleFlightRoomPersistenceExecutor,
  ): StructuralRunnerResult {
    val baseline = runtimeSnapshot.baseline
    val refreshedFolders =
      buildSet {
        runtimeSnapshot.snapshot.effectiveFolderId?.let { add(it) }
        runtimeSnapshot.snapshot.fallbackSourceFolderId?.let { add(it) }
      }.let { ids ->
        if (ids.isEmpty()) emptyMap() else folderRepository.getFoldersByIds(ids)
      }

    val (updatedSnap, updatedContext) =
      NoteEditorSessionEvaluator.applyFolderAvailabilityToSnapshot(
        snapshot = runtimeSnapshot.snapshot,
        folderAvailabilityById = refreshedFolders,
        previousContext = runtimeSnapshot.folderContext,
        baseline = runtimeSnapshot.baseline,
      )
    val refreshedEval =
      NoteEditorSessionEvaluator.evaluateSession(
        NoteEditorSessionInputAlias(
          lifecycleStage = com.example.domain.editor.SessionLifecycleStage.EDITING,
          baseline = runtimeSnapshot.baseline,
          snapshot = updatedSnap,
          folderContext = updatedContext,
          activeConflict = runtimeSnapshot.activeConflict,
          blockReason = null,
        )
      )

    val effectiveFolderIdForSave: Long? =
      when (val readiness = refreshedEval.consolidationReadiness) {
        is ConsolidationReadiness.Ready -> readiness.effectiveFolderId
        is ConsolidationReadiness.RequiresExplicitFolderConfirmation ->
          if (command.allowFallbackToRoot) {
            null
          } else {
            return StructuralRunnerResult.Failed(
              IllegalStateException(
                "Pasta original (${readiness.fallbackSourceFolderId}) indisponível; confirmação explícita para salvar na raiz necessária."
              )
            )
          }
        is ConsolidationReadiness.BlockedByUnknownFolderState ->
          return StructuralRunnerResult.Failed(
            IllegalStateException(
              readiness.reason
                ?: "Estado desconhecido da pasta destino ${readiness.folderId} impede consolidação."
            )
          )
        is ConsolidationReadiness.BlockedByConflict ->
          return StructuralRunnerResult.Failed(
            IllegalStateException(
              "Conflito ativo na nota ${readiness.conflict.targetNoteId} impede consolidação."
            )
          )
        is ConsolidationReadiness.BlockedBySessionState ->
          return StructuralRunnerResult.Failed(
            IllegalStateException("Estado atual da sessão bloqueia consolidação.")
          )
      }

    val targetNoteId = runtimeSnapshot.identity.targetNoteId
    return if (targetNoteId == null) {
      when (
        val res =
          executor.consolidateCreateNote(
            title = updatedSnap.title,
            content = updatedSnap.document,
            folderId = effectiveFolderIdForSave,
            isPinned = updatedSnap.isPinned,
            paperStyle = updatedSnap.paperStyle,
          )
      ) {
        is ConsolidateNoteExecutionResult.Consolidated -> {
          handoffTracker.invalidateCandidateDraft(runtimeSnapshot.identity.draftId)
          val created = res.note
          val newIdentity =
            NoteEditorSessionIdentity(
              draftId =
                NoteRecoveryDraftKeys.forExistingNote(
                  noteId = created.id,
                  sessionId = runtimeSnapshot.identity.sessionId,
                ),
              sessionId = runtimeSnapshot.identity.sessionId,
              targetNoteId = created.id,
            )
          val transferRes =
            activeSessionRegistry.reserveIdentityTransfer(
              currentLease = runtimeSnapshot.lease,
              targetIdentity = RegisteredSessionIdentity.fromSessionIdentity(newIdentity),
            )
          val transferToken =
            when (transferRes) {
              is ReserveTransferResult.Reserved -> transferRes.transferToken
              else ->
                return StructuralRunnerResult.Failed(
                  IllegalStateException(
                    "Nota criada (${created.id}), mas reserva de transferência no Registry falhou: $transferRes"
                  )
                )
            }
          val commitRes = activeSessionRegistry.commitIdentityTransfer(transferToken)
          val newLease =
            when (commitRes) {
              is CommitTransferResult.Committed -> commitRes.newLease
              is CommitTransferResult.AlreadyCommitted -> commitRes.newLease
              else ->
                return StructuralRunnerResult.Failed(
                  IllegalStateException("Falha ao confirmar transferência pós-criação: $commitRes")
                )
            }
          onLeaseCommitted(newLease)
          val newExecutor =
            SingleFlightRoomPersistenceExecutor(
              draftRepository = draftRepository,
              noteRepository = noteRepository,
              initialIdentity = newIdentity,
              initialPresence = CheckpointPresence.ABSENT,
              initialConfirmedRevision = 0L,
              initialHighestAllocatedRevision = res.snapshot.highestAllocatedRevision,
              initialLastCheckpointAt = null,
            )
          StructuralRunnerResult.Succeeded(
            StructuralOutcomeData.ConsolidatedNew(
              createdNote = created,
              newIdentity = newIdentity,
              newLease = newLease,
              newExecutor = newExecutor,
              resolvedFolderAvailability = refreshedFolders,
            )
          )
        }
        is ConsolidateNoteExecutionResult.Conflict ->
          StructuralRunnerResult.Failed(res.error)
        is ConsolidateNoteExecutionResult.Failed ->
          StructuralRunnerResult.Failed(res.cause)
      }
    } else {
      val expectedBaseRevision = (baseline as? NoteEditorBaseline.ExistingNote)?.baseRevision
      when (
        val res =
          executor.consolidateUpdateNote(
            noteId = targetNoteId,
            title = updatedSnap.title,
            content = updatedSnap.document,
            folderId = effectiveFolderIdForSave,
            paperStyle = updatedSnap.paperStyle,
            expectedBaseRevision = expectedBaseRevision,
          )
      ) {
        is ConsolidateNoteExecutionResult.Consolidated -> {
          handoffTracker.invalidateCandidateDraft(runtimeSnapshot.identity.draftId)
          StructuralRunnerResult.Succeeded(
            StructuralOutcomeData.ConsolidatedExisting(
              updatedNote = res.note,
              resolvedFolderAvailability = refreshedFolders,
            )
          )
        }
        is ConsolidateNoteExecutionResult.Conflict -> {
          val latestNote = noteRepository.getNoteById(targetNoteId)
          val conflict =
            RecoveryConflict.NoteDiverged(
              draftId = runtimeSnapshot.identity.draftId,
              targetNoteId = targetNoteId,
              draftBaseRevision = expectedBaseRevision ?: 0L,
              currentNoteRevision = latestNote?.revision ?: res.error.actualRevision,
              draftBaseUpdatedAt =
                (baseline as? NoteEditorBaseline.ExistingNote)?.baseNoteUpdatedAt,
              currentNoteUpdatedAt = latestNote?.updatedAt ?: 0L,
            )
          StructuralRunnerResult.Failed(
            cause = res.error,
            fallbackData =
              StructuralOutcomeData.ConsolidationConflictDetected(
                conflict = conflict,
                latestNote = latestNote,
              ),
          )
        }
        is ConsolidateNoteExecutionResult.Failed ->
          StructuralRunnerResult.Failed(res.cause)
      }
    }
  }

  suspend fun runResolveConflict(
    command: SessionCommand.ResolveConflict,
    runtimeSnapshot: SessionRuntimeState,
    executor: SingleFlightRoomPersistenceExecutor,
    onPendingReplacementSet: (UncommittedReplacementContext?) -> Unit,
  ): StructuralRunnerResult {
    val activeConflict =
      runtimeSnapshot.activeConflict
        ?: return StructuralRunnerResult.Failed(
          IllegalStateException("Não existe conflito ativo para resolver.")
        )
    val targetNoteId =
      runtimeSnapshot.identity.targetNoteId
        ?: return StructuralRunnerResult.Failed(
          IllegalStateException("Resolução de conflito exige rascunho vinculado a nota existente.")
        )

    return when (command.option) {
      ConflictResolutionOption.KEEP_DRAFT_OVER_CURRENT_NOTE ->
        runKeepDraftOverCurrentNote(targetNoteId, runtimeSnapshot, executor)

      ConflictResolutionOption.DISCARD_DRAFT_USE_CURRENT_NOTE ->
        runDiscardDraftUseCurrentNote(targetNoteId, runtimeSnapshot, executor)

      ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE ->
        runSaveDraftAsNewNote(
          targetNoteId = targetNoteId,
          runtimeSnapshot = runtimeSnapshot,
          executor = executor,
          onPendingReplacementSet = onPendingReplacementSet,
        )
    }
  }

  private suspend fun runKeepDraftOverCurrentNote(
    targetNoteId: Long,
    runtimeSnapshot: SessionRuntimeState,
    executor: SingleFlightRoomPersistenceExecutor,
  ): StructuralRunnerResult {
    val latestNote =
      noteRepository.getNoteById(targetNoteId)
        ?: return StructuralRunnerResult.Failed(
          IllegalStateException(
            "A nota $targetNoteId não foi encontrada para manter o rascunho."
          )
        )

    if (latestNote.isDeleted) {
      return StructuralRunnerResult.Failed(
        IllegalStateException("A nota $targetNoteId está em Apagados.")
      )
    }

    val noteContent =
      (latestNote.contentState as? NoteContentState.Valid)?.document
        ?: return StructuralRunnerResult.Failed(
          IllegalStateException("O conteúdo atual da nota $targetNoteId é ilegível.")
        )

    val folderIds =
      setOfNotNull(
        latestNote.folderId,
        runtimeSnapshot.snapshot.effectiveFolderId,
        runtimeSnapshot.snapshot.fallbackSourceFolderId,
      )

    val folderMap =
      if (folderIds.isEmpty()) {
        emptyMap()
      } else {
        folderRepository.getFoldersByIds(folderIds)
      }

    val rebasedBaseline =
      NoteEditorBaseline.ExistingNote(
        noteId = latestNote.id,
        baseRevision = latestNote.revision,
        baseNoteUpdatedAt = latestNote.updatedAt,
        title = latestNote.title,
        normalizedContent =
          NoteEditorSessionEvaluator.canonicalizeDocument(noteContent),
        paperStyle = latestNote.paperStyle,
        staticFolderId = latestNote.folderId,
        currentDbIsPinned = latestNote.isPinned,
      )

    val originalSnapshot =
      runtimeSnapshot.snapshot.copy(
        isPinned = latestNote.isPinned,
      )

    // É obrigatório utilizar o snapshot atualizado retornado pela reconciliação.
    val (rebasedSnapshot, rebasedContext) =
      NoteEditorSessionEvaluator.applyFolderAvailabilityToSnapshot(
        snapshot = originalSnapshot,
        folderAvailabilityById = folderMap,
        previousContext = runtimeSnapshot.folderContext,
        baseline = rebasedBaseline,
      )

    val rebasedEval =
      NoteEditorSessionEvaluator.evaluateSession(
        NoteEditorSessionInputAlias(
          lifecycleStage =
            com.example.domain.editor.SessionLifecycleStage.EDITING,
          baseline = rebasedBaseline,
          snapshot = rebasedSnapshot,
          folderContext = rebasedContext,
          activeConflict = null,
          blockReason = null,
        )
      )

    return if (!rebasedEval.needsDraftRetention) {
      when (val delRes = executor.deleteCheckpoint(runtimeSnapshot.identity.draftId)) {
        is DeleteCheckpointExecutionResult.Deleted ->
          StructuralRunnerResult.Succeeded(
            StructuralOutcomeData.KeptDraftOverCurrentNote(
              latestNote = latestNote,
              checkpointAt = null,
              resolvedFolderAvailability = folderMap,
              deletedCleanDraft = true,
              rebasedSnapshot = rebasedSnapshot,
              rebasedFolderContext = rebasedContext,
            )
          )

        is DeleteCheckpointExecutionResult.Failed ->
          StructuralRunnerResult.Failed(delRes.cause)
      }
    } else {
      val payload =
        CheckpointSavePayload.fromIdentity(
          identity = runtimeSnapshot.identity,
          folderId = rebasedSnapshot.effectiveFolderId,
          initialFolderId = null,
          fallbackSourceFolderId = rebasedSnapshot.fallbackSourceFolderId,
          isPinned = latestNote.isPinned,
          title = rebasedSnapshot.title,
          content = rebasedSnapshot.document,
          paperStyle = rebasedSnapshot.paperStyle,
          initialPaperStyle = null,
          baseRevision = latestNote.revision,
          baseNoteUpdatedAt = latestNote.updatedAt,
        )

      when (val saveRes = executor.saveCheckpoint(payload)) {
        is SaveCheckpointExecutionResult.Saved ->
          StructuralRunnerResult.Succeeded(
            StructuralOutcomeData.KeptDraftOverCurrentNote(
              latestNote = latestNote,
              checkpointAt = saveRes.draft.checkpointAt,
              resolvedFolderAvailability = folderMap,
              deletedCleanDraft = false,
              rebasedSnapshot = rebasedSnapshot,
              rebasedFolderContext = rebasedContext,
            )
          )

        is SaveCheckpointExecutionResult.Failed ->
          StructuralRunnerResult.Failed(saveRes.cause)

        is SaveCheckpointExecutionResult.IgnoredStaleRevision ->
          StructuralRunnerResult.Failed(
            IllegalStateException(
              "Revisão obsoleta ao rebazar rascunho " +
                "(${saveRes.attemptedCheckpointRevision} <= " +
                "${saveRes.persistedCheckpointRevision})."
            )
          )

        is SaveCheckpointExecutionResult.RevisionOverflow ->
          StructuralRunnerResult.Failed(
            IllegalStateException("Limite de revisões de checkpoint atingido.")
          )
      }
    }
  }

  private suspend fun runDiscardDraftUseCurrentNote(
    targetNoteId: Long,
    runtimeSnapshot: SessionRuntimeState,
    executor: SingleFlightRoomPersistenceExecutor,
  ): StructuralRunnerResult {
    val latestNote =
      noteRepository.getNoteById(targetNoteId)
        ?: return StructuralRunnerResult.Failed(
          IllegalStateException("A nota $targetNoteId não foi encontrada.")
        )
    if (latestNote.isDeleted) {
      return StructuralRunnerResult.Failed(
        IllegalStateException("A nota $targetNoteId está em Apagados.")
      )
    }
    if (latestNote.contentState is NoteContentState.RequiresHandling) {
      return StructuralRunnerResult.Failed(
        IllegalStateException("O conteúdo atual da nota $targetNoteId exige tratamento manual.")
      )
    }

    val folderMap =
      latestNote.folderId?.let { folderRepository.getFoldersByIds(setOf(it)) } ?: emptyMap()

    return when (val delRes = executor.deleteCheckpoint(runtimeSnapshot.identity.draftId)) {
      is DeleteCheckpointExecutionResult.Deleted -> {
        handoffTracker.invalidateCandidateDraft(runtimeSnapshot.identity.draftId)
        StructuralRunnerResult.Succeeded(
          StructuralOutcomeData.DiscardedDraftToCurrentNote(
            latestNote = latestNote,
            resolvedFolderAvailability = folderMap,
          )
        )
      }
      is DeleteCheckpointExecutionResult.Failed -> StructuralRunnerResult.Failed(delRes.cause)
    }
  }

  private suspend fun runSaveDraftAsNewNote(
    targetNoteId: Long,
    runtimeSnapshot: SessionRuntimeState,
    executor: SingleFlightRoomPersistenceExecutor,
    onPendingReplacementSet: (UncommittedReplacementContext?) -> Unit,
  ): StructuralRunnerResult {
    val newSessionId =
      NoteRecoveryDraftKeys.normalizeAndValidateSessionId(sessionIdGenerator())
    require(newSessionId != runtimeSnapshot.identity.sessionId) {
      "Identificador de sessão gerado colidiu com a sessão atual."
    }

    val newIdentity =
      NoteEditorSessionIdentity(
        draftId = NoteRecoveryDraftKeys.forNewNote(newSessionId),
        sessionId = newSessionId,
        targetNoteId = null,
      )
    val snap = runtimeSnapshot.snapshot
    val newBaseline =
      NoteEditorBaseline.NewNote(
        title = "",
        normalizedContent = NoteEditorSessionEvaluator.canonicalizeDocument(NoteContentDocument.empty()),
        paperStyle = snap.paperStyle,
        staticFolderId = snap.intendedUserFolderId,
        initialIsPinned = false,
      )
    val newPayload =
      CheckpointSavePayload.fromIdentity(
        identity = newIdentity,
        folderId = snap.effectiveFolderId,
        initialFolderId = snap.intendedUserFolderId,
        fallbackSourceFolderId = snap.fallbackSourceFolderId,
        isPinned = snap.isPinned,
        title = snap.title,
        content = snap.document,
        paperStyle = snap.paperStyle,
        initialPaperStyle = snap.paperStyle,
        baseRevision = 0L,
        baseNoteUpdatedAt = null,
      )
    val newDraftRevision = 1L

    val transferToken =
      when (
        val res =
          activeSessionRegistry.reserveIdentityTransfer(
            currentLease = runtimeSnapshot.lease,
            targetIdentity = RegisteredSessionIdentity.fromSessionIdentity(newIdentity),
          )
      ) {
        is ReserveTransferResult.Reserved -> res.transferToken
        else ->
          return StructuralRunnerResult.Failed(
            IllegalStateException("Falha ao reservar nova identidade no Registry: $res")
          )
      }

    val handoffRecord =
      try {
        handoffTracker.registerPending(
          oldIdentity = runtimeSnapshot.identity,
          newPayload = newPayload,
          newDraftRevision = newDraftRevision,
        )
      } catch (t: Throwable) {
        activeSessionRegistry.rollbackIdentityTransfer(transferToken)
        return StructuralRunnerResult.Failed(t)
      }

    val context =
      UncommittedReplacementContext(
        pendingState =
          PendingReplacementState(
            oldDraftId = runtimeSnapshot.identity.draftId,
            expectedOldSessionId = runtimeSnapshot.identity.sessionId,
            expectedOldTargetNoteId = targetNoteId,
            oldIdentity = runtimeSnapshot.identity,
            newPayload = newPayload,
            newDraftRevision = newDraftRevision,
          ),
        transferToken = transferToken,
        handoffId = handoffRecord.handoffId,
        targetBaseline = newBaseline,
      )
    onPendingReplacementSet(context)

    val execResult =
      executor.replaceCheckpointWithNewNote(
        oldDraftId = runtimeSnapshot.identity.draftId,
        expectedOldSessionId = runtimeSnapshot.identity.sessionId,
        expectedOldTargetNoteId = targetNoteId,
        newPayload = newPayload,
        newDraftRevision = newDraftRevision,
      )

    val savedDraftOrNull =
      when (execResult) {
        is ReplaceCheckpointExecutionResult.Replaced -> execResult.draft
        is ReplaceCheckpointExecutionResult.AlreadyReplaced -> execResult.draft
        is ReplaceCheckpointExecutionResult.RevisionOverflow,
        is ReplaceCheckpointExecutionResult.Failed -> null
      }

    if (savedDraftOrNull != null) {
      val savedDraft = savedDraftOrNull
      return when (val commitRes = activeSessionRegistry.commitIdentityTransfer(transferToken)) {
        is CommitTransferResult.Committed -> {
          onLeaseCommitted(commitRes.newLease)
          handoffTracker.markResolved(runtimeSnapshot.identity.draftId, handoffRecord.handoffId)
          onPendingReplacementSet(null)
          StructuralRunnerResult.Succeeded(
            StructuralOutcomeData.SavedDraftAsNewNote(
              newIdentity = newIdentity,
              newLease = commitRes.newLease,
              newDraft = savedDraft,
              targetBaseline = newBaseline,
            )
          )
        }
        is CommitTransferResult.AlreadyCommitted -> {
          onLeaseCommitted(commitRes.newLease)
          handoffTracker.markResolved(runtimeSnapshot.identity.draftId, handoffRecord.handoffId)
          onPendingReplacementSet(null)
          StructuralRunnerResult.Succeeded(
            StructuralOutcomeData.SavedDraftAsNewNote(
              newIdentity = newIdentity,
              newLease = commitRes.newLease,
              newDraft = savedDraft,
              targetBaseline = newBaseline,
            )
          )
        }
        CommitTransferResult.RejectedAlreadyRolledBack,
        CommitTransferResult.InvalidOrObsoleteToken -> {
          val reconciled =
            replacementReconciler.reconcile(
              executor = executor,
              currentLease = runtimeSnapshot.lease,
              context = context,
              forShutdown = false,
            )
          return when (reconciled) {
            is ReplacementReconciliationResult.PromotedToNew -> {
              onLeaseCommitted(reconciled.newLease)
              onPendingReplacementSet(null)

              StructuralRunnerResult.Succeeded(
                StructuralOutcomeData.ReconciledIndeterminate(reconciled)
              )
            }

            is ReplacementReconciliationResult.RevertedToOld,
            is ReplacementReconciliationResult.NeitherPresent,
            is ReplacementReconciliationResult.DivergentAnomaly -> {
              onPendingReplacementSet(null)

              StructuralRunnerResult.Failed(
                cause =
                  IllegalStateException(
                    "Commit no Registry falhou após substituição no Room."
                  ),
                fallbackData = StructuralOutcomeData.ReconciledIndeterminate(reconciled),
              )
            }

            is ReplacementReconciliationResult.IndeterminateReadFailure -> {
              // Preservar o contexto pendente para uma reconciliação posterior.
              StructuralRunnerResult.Failed(
                cause = reconciled.cause,
                fallbackData = StructuralOutcomeData.ReconciledIndeterminate(reconciled),
              )
            }
          }
        }
      }
    }

    when (execResult) {
      is ReplaceCheckpointExecutionResult.Replaced,
      is ReplaceCheckpointExecutionResult.AlreadyReplaced ->
        return StructuralRunnerResult.Failed(
          IllegalStateException("Estado inesperado pós-substituição.")
        )

      is ReplaceCheckpointExecutionResult.RevisionOverflow -> {
        handoffTracker.clearByHandoffId(runtimeSnapshot.identity.draftId, handoffRecord.handoffId)
        activeSessionRegistry.rollbackIdentityTransfer(transferToken)
        onPendingReplacementSet(null)
        return StructuralRunnerResult.Failed(
          IllegalStateException("Estouro de revisão ao converter rascunho em nova nota.")
        )
      }

      is ReplaceCheckpointExecutionResult.Failed -> {
        val snapAfterFail = execResult.snapshot
        if (
          !snapAfterFail.hasPendingReplacement &&
            execResult.cause is InvalidRecoveryDraftException
        ) {
          handoffTracker.clearByHandoffId(
            runtimeSnapshot.identity.draftId,
            handoffRecord.handoffId,
          )
          activeSessionRegistry.rollbackIdentityTransfer(transferToken)
          onPendingReplacementSet(null)
          return StructuralRunnerResult.Failed(execResult.cause)
        }

        val reconciled =
          replacementReconciler.reconcile(
            executor = executor,
            currentLease = runtimeSnapshot.lease,
            context = context,
            forShutdown = false,
          )
        return when (reconciled) {
          is ReplacementReconciliationResult.PromotedToNew -> {
            onLeaseCommitted(reconciled.newLease)
            onPendingReplacementSet(null)
            StructuralRunnerResult.Succeeded(
              StructuralOutcomeData.ReconciledIndeterminate(reconciled)
            )
          }
          is ReplacementReconciliationResult.RevertedToOld,
          is ReplacementReconciliationResult.NeitherPresent,
          is ReplacementReconciliationResult.DivergentAnomaly -> {
            onPendingReplacementSet(null)
            StructuralRunnerResult.Failed(
              cause = execResult.cause,
              fallbackData = StructuralOutcomeData.ReconciledIndeterminate(reconciled),
            )
          }
          is ReplacementReconciliationResult.IndeterminateReadFailure -> {
            // Estado F: preserva onPendingReplacementSet(context) e associação PENDING no tracker.
            StructuralRunnerResult.Failed(
              cause = reconciled.cause,
              fallbackData = StructuralOutcomeData.ReconciledIndeterminate(reconciled),
            )
          }
        }
      }
    }
  }

  suspend fun runDiscardDraftAndClose(
    runtimeSnapshot: SessionRuntimeState,
    executor: SingleFlightRoomPersistenceExecutor,
  ): StructuralRunnerResult =
    when (val delRes = executor.deleteCheckpoint(runtimeSnapshot.identity.draftId)) {
      is DeleteCheckpointExecutionResult.Deleted -> {
        handoffTracker.invalidateCandidateDraft(runtimeSnapshot.identity.draftId)
        StructuralRunnerResult.Succeeded(StructuralOutcomeData.DiscardedAndClosed)
      }
      is DeleteCheckpointExecutionResult.Failed -> StructuralRunnerResult.Failed(delRes.cause)
    }
}
