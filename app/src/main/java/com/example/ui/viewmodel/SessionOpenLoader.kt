package com.example.ui.viewmodel

import com.example.domain.editor.FolderAvailability
import com.example.domain.editor.NoteEditorOpenRequest
import com.example.domain.editor.NoteEditorSessionEvaluator
import com.example.domain.editor.NoteEditorSessionIdentity
import com.example.domain.editor.RecoveryOpeningEvaluation
import com.example.domain.editor.ResolvedDraftFolderContext
import com.example.domain.editor.SessionBlockReason
import com.example.domain.editor.SessionWarning
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.NoteRecoveryDraft
import com.example.domain.model.NoteRecoveryDraftKeys
import com.example.domain.repository.FolderRepository
import com.example.domain.repository.NoteRecoveryDraftRepository
import com.example.domain.repository.NoteRepository
import com.example.domain.repository.PreferencesRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

sealed interface SessionOpenLoadResult {
  data class Ready(
    val evaluation: RecoveryOpeningEvaluation.ReadyToEdit,
    val lease: SessionLeaseToken,
    val effectiveFolderName: String?,
    val folderAvailabilityById: Map<Long, FolderAvailability>,
    val initialPresence: CheckpointPresence,
    val initialConfirmedRevision: Long,
    val initialLastCheckpointAt: Long?,
    val persistedDraftSnapshot: NoteRecoveryDraft? = null,
    val initialDraftFolderIdMetadata: Long? = null,
    val initialDraftPaperStyleMetadata: NotePaperStyle? = null,
    val resolvedViaHandoffFromDraftId: String? = null,
  ) : SessionOpenLoadResult

  data class Blocked(
    val reason: SessionBlockReason,
    val warnings: List<SessionWarning> = emptyList(),
  ) : SessionOpenLoadResult

  data class Failed(
    val message: String,
    val cause: Throwable? = null,
  ) : SessionOpenLoadResult
}

/**
 * Carregador transacional de abertura de sessões do editor (`CreateNew`, `OpenExisting` e
 * `RecoverDraft`), garantindo:
 * - reserva exclusiva no [ActiveSessionRegistry] antes da entrada no loop de edição;
 * - liberação segura do lease em caso de bloqueio, falha ou cancelamento;
 * - resolução da preferência persistida de papel em `CreateNew` quando `defaultPaperStyle` for nulo;
 * - redirecionamento validado no Room via [ReplacementHandoffTracker] quando aplicável.
 */
class SessionOpenLoader(
  private val noteRepository: NoteRepository,
  private val folderRepository: FolderRepository,
  private val draftRepository: NoteRecoveryDraftRepository,
  private val preferencesRepository: PreferencesRepository,
  private val activeSessionRegistry: ActiveSessionRegistry,
  private val handoffTracker: ReplacementHandoffTracker,
  private val sessionIdGenerator: () -> String = { UUID.randomUUID().toString() },
) {
  suspend fun open(request: NoteEditorOpenRequest): SessionOpenLoadResult =
    when (request) {
      is NoteEditorOpenRequest.CreateNew -> openCreateNew(request)
      is NoteEditorOpenRequest.OpenExisting -> openExisting(request)
      is NoteEditorOpenRequest.RecoverDraft -> openRecoverDraft(request)
    }

  private suspend fun openCreateNew(
    request: NoteEditorOpenRequest.CreateNew,
  ): SessionOpenLoadResult {
    val cleanSessionId = resolveCleanSessionId(request.sessionId)
    val identity =
      NoteEditorSessionIdentity(
        draftId = NoteRecoveryDraftKeys.forNewNote(cleanSessionId),
        sessionId = cleanSessionId,
        targetNoteId = null,
      )

    val lease =
      when (
        val res =
          activeSessionRegistry.reserve(RegisteredSessionIdentity.fromSessionIdentity(identity))
      ) {
        is SessionReservationResult.Reserved -> res.lease
        is SessionReservationResult.Conflict ->
          return SessionOpenLoadResult.Blocked(
            SessionBlockReason.SessionAlreadyActive(
              requestedDraftId = identity.draftId,
              requestedSessionId = identity.sessionId,
              requestedTargetNoteId = null,
            )
          )
      }

    return try {
      val resolvedPaperStyle =
        request.defaultPaperStyle ?: preferencesRepository.getPreferences().defaultNotePaper
      val folderIds = setOfNotNull(request.initialFolderId)
      val folderMap =
        if (folderIds.isEmpty()) emptyMap() else folderRepository.getFoldersByIds(folderIds)

      val resolvedRequest =
        request.copy(
          defaultPaperStyle = resolvedPaperStyle,
          sessionId = cleanSessionId,
        )
      when (
        val evalResult =
          NoteEditorSessionEvaluator.evaluateOpening(
            request = resolvedRequest,
            folderAvailabilityById = folderMap,
            generatedSessionId = cleanSessionId,
          )
      ) {
        is RecoveryOpeningEvaluation.Blocked -> {
          releaseLeaseSafely(lease)
          SessionOpenLoadResult.Blocked(reason = evalResult.reason, warnings = evalResult.warnings)
        }
        is RecoveryOpeningEvaluation.ReadyToEdit -> {
          SessionOpenLoadResult.Ready(
            evaluation = evalResult,
            lease = lease,
            effectiveFolderName =
              resolveEffectiveFolderName(
                effectiveFolderId = evalResult.evaluation.effectiveFolderId,
                folderContext = evalResult.folderContext,
              ),
            folderAvailabilityById = folderMap,
            initialPresence = CheckpointPresence.ABSENT,
            initialConfirmedRevision = 0L,
            initialLastCheckpointAt = null,
            persistedDraftSnapshot = null,
            initialDraftFolderIdMetadata = request.initialFolderId,
            initialDraftPaperStyleMetadata = resolvedPaperStyle,
          )
        }
      }
    } catch (c: CancellationException) {
      releaseLeaseSafely(lease)
      throw c
    } catch (t: Throwable) {
      releaseLeaseSafely(lease)
      SessionOpenLoadResult.Failed(
        message = t.message ?: "Não foi possível iniciar nova nota.",
        cause = t,
      )
    }
  }

  private suspend fun openExisting(
    request: NoteEditorOpenRequest.OpenExisting,
  ): SessionOpenLoadResult {
    val cleanSessionId = resolveCleanSessionId(request.sessionId)
    val identity =
      NoteEditorSessionIdentity(
        draftId = NoteRecoveryDraftKeys.forExistingNote(request.noteId, cleanSessionId),
        sessionId = cleanSessionId,
        targetNoteId = request.noteId,
      )

    val lease =
      when (
        val res =
          activeSessionRegistry.reserve(RegisteredSessionIdentity.fromSessionIdentity(identity))
      ) {
        is SessionReservationResult.Reserved -> res.lease
        is SessionReservationResult.Conflict ->
          return SessionOpenLoadResult.Blocked(
            SessionBlockReason.SessionAlreadyActive(
              requestedDraftId = identity.draftId,
              requestedSessionId = identity.sessionId,
              requestedTargetNoteId = request.noteId,
            )
          )
      }

    return try {
      val note = noteRepository.getNoteById(request.noteId)
      val folderIds = setOfNotNull(note?.folderId)
      val folderMap =
        if (folderIds.isEmpty()) emptyMap() else folderRepository.getFoldersByIds(folderIds)

      val resolvedRequest = request.copy(sessionId = cleanSessionId)
      when (
        val evalResult =
          NoteEditorSessionEvaluator.evaluateOpening(
            request = resolvedRequest,
            existingNote = note,
            folderAvailabilityById = folderMap,
            generatedSessionId = cleanSessionId,
          )
      ) {
        is RecoveryOpeningEvaluation.Blocked -> {
          releaseLeaseSafely(lease)
          SessionOpenLoadResult.Blocked(reason = evalResult.reason, warnings = evalResult.warnings)
        }
        is RecoveryOpeningEvaluation.ReadyToEdit -> {
          SessionOpenLoadResult.Ready(
            evaluation = evalResult,
            lease = lease,
            effectiveFolderName =
              resolveEffectiveFolderName(
                effectiveFolderId = evalResult.evaluation.effectiveFolderId,
                folderContext = evalResult.folderContext,
              ),
            folderAvailabilityById = folderMap,
            initialPresence = CheckpointPresence.ABSENT,
            initialConfirmedRevision = 0L,
            initialLastCheckpointAt = null,
            persistedDraftSnapshot = null,
            initialDraftFolderIdMetadata = null,
            initialDraftPaperStyleMetadata = null,
          )
        }
      }
    } catch (c: CancellationException) {
      releaseLeaseSafely(lease)
      throw c
    } catch (t: Throwable) {
      releaseLeaseSafely(lease)
      SessionOpenLoadResult.Failed(
        message = t.message ?: "Não foi possível abrir a nota ${request.noteId}.",
        cause = t,
      )
    }
  }

  private suspend fun openRecoverDraft(
    request: NoteEditorOpenRequest.RecoverDraft,
  ): SessionOpenLoadResult {
    val cleanRequestedDraftId = request.draftId.trim()
    NoteRecoveryDraftKeys.validateDraftId(cleanRequestedDraftId)

    return try {
      val directDraft = draftRepository.getCheckpointById(cleanRequestedDraftId)
      var effectiveRequest = request
      var resolvedViaHandoffFromDraftId: String? = null
      val initialTargetDraft: NoteRecoveryDraft? =
        if (directDraft != null) {
          val staleHandoff = handoffTracker.findActiveCandidate(cleanRequestedDraftId)
          if (staleHandoff != null) {
            val candidateInDb = draftRepository.getCheckpointById(staleHandoff.candidateNewDraftId)
            if (candidateInDb == null) {
              // Estado A: antigo presente e candidato ausente -> limpa associação obsoleta no tracker.
              handoffTracker.clearByHandoffId(staleHandoff.oldDraftId, staleHandoff.handoffId)
            }
          }
          directDraft
        } else {
          resolveCandidateViaHandoff(cleanRequestedDraftId)?.let { (candidate, fromOldId) ->
            effectiveRequest = request.copy(draftId = candidate.draftId)
            resolvedViaHandoffFromDraftId = fromOldId
            candidate
          }
        }

      if (initialTargetDraft == null) {
        return SessionOpenLoadResult.Blocked(
          SessionBlockReason.DraftNotFound(cleanRequestedDraftId)
        )
      }

      val targetIdentity =
        try {
          NoteEditorSessionIdentity(
            draftId = initialTargetDraft.draftId,
            sessionId = initialTargetDraft.sessionId,
            targetNoteId = initialTargetDraft.targetNoteId,
          )
        } catch (e: IllegalArgumentException) {
          return SessionOpenLoadResult.Blocked(
            SessionBlockReason.InvalidPersistedDraftKeys(
              draftId = initialTargetDraft.draftId,
              reason = e.message ?: "Chaves do rascunho inválidas.",
            )
          )
        }

      val lease =
        when (
          val res =
            activeSessionRegistry.reserve(
              RegisteredSessionIdentity.fromSessionIdentity(targetIdentity)
            )
        ) {
          is SessionReservationResult.Reserved -> res.lease
          is SessionReservationResult.Conflict ->
            return SessionOpenLoadResult.Blocked(
              SessionBlockReason.SessionAlreadyActive(
                requestedDraftId = targetIdentity.draftId,
                requestedSessionId = targetIdentity.sessionId,
                requestedTargetNoteId = targetIdentity.targetNoteId,
              )
            )
        }

      try {
        val freshDraft = draftRepository.getCheckpointById(targetIdentity.draftId)
        val existingNote = freshDraft?.targetNoteId?.let { noteRepository.getNoteById(it) }
        val folderIds =
          buildSet {
            freshDraft?.folderId?.let { add(it) }
            freshDraft?.fallbackSourceFolderId?.let { add(it) }
            freshDraft?.initialFolderId?.let { add(it) }
            existingNote?.folderId?.let { add(it) }
          }
        val folderMap =
          if (folderIds.isEmpty()) emptyMap() else folderRepository.getFoldersByIds(folderIds)

        when (
          val evalResult =
            NoteEditorSessionEvaluator.evaluateOpening(
              request = effectiveRequest,
              draft = freshDraft,
              existingNote = existingNote,
              folderAvailabilityById = folderMap,
            )
        ) {
          is RecoveryOpeningEvaluation.Blocked -> {
            releaseLeaseSafely(lease)
            SessionOpenLoadResult.Blocked(
              reason = evalResult.reason,
              warnings = evalResult.warnings,
            )
          }
          is RecoveryOpeningEvaluation.ReadyToEdit -> {
            val verifiedDraft = requireNotNull(freshDraft)
            SessionOpenLoadResult.Ready(
              evaluation = evalResult,
              lease = lease,
              effectiveFolderName =
                resolveEffectiveFolderName(
                  effectiveFolderId = evalResult.evaluation.effectiveFolderId,
                  folderContext = evalResult.folderContext,
                ),
              folderAvailabilityById = folderMap,
              initialPresence = CheckpointPresence.PRESENT,
              initialConfirmedRevision = verifiedDraft.checkpointRevision,
              initialLastCheckpointAt = verifiedDraft.checkpointAt,
              persistedDraftSnapshot = verifiedDraft,
              initialDraftFolderIdMetadata = verifiedDraft.initialFolderId,
              initialDraftPaperStyleMetadata = verifiedDraft.initialPaperStyle,
              resolvedViaHandoffFromDraftId = resolvedViaHandoffFromDraftId,
            )
          }
        }
      } catch (c: CancellationException) {
        releaseLeaseSafely(lease)
        throw c
      } catch (t: Throwable) {
        releaseLeaseSafely(lease)
        SessionOpenLoadResult.Failed(
          message = t.message ?: "Falha ao carregar o rascunho '${targetIdentity.draftId}'.",
          cause = t,
        )
      }
    } catch (c: CancellationException) {
      throw c
    } catch (t: Throwable) {
      SessionOpenLoadResult.Failed(
        message = t.message ?: "Falha ao recuperar o rascunho '$cleanRequestedDraftId'.",
        cause = t,
      )
    }
  }

  private suspend fun resolveCandidateViaHandoff(
    oldDraftId: String,
  ): Pair<NoteRecoveryDraft, String>? {
    val record = handoffTracker.findActiveCandidate(oldDraftId) ?: return null
    val persistedCandidate = draftRepository.getCheckpointById(record.candidateNewDraftId)
    return when (val verified = handoffTracker.verifyAndResolveCandidate(record, persistedCandidate)) {
      is RedirectVerificationResult.Verified -> verified.candidateDraft to oldDraftId
      is RedirectVerificationResult.RejectedMissing,
      is RedirectVerificationResult.RejectedDivergent,
      RedirectVerificationResult.NoActiveHandoff -> null
    }
  }

  private fun resolveCleanSessionId(requestSessionId: String?): String {
    val raw = requestSessionId ?: sessionIdGenerator()
    return NoteRecoveryDraftKeys.normalizeAndValidateSessionId(raw)
  }

  private suspend fun releaseLeaseSafely(lease: SessionLeaseToken) {
    withContext(NonCancellable) {
      activeSessionRegistry.release(lease)
    }
  }

  companion object {
    fun resolveEffectiveFolderName(
      effectiveFolderId: Long?,
      folderContext: ResolvedDraftFolderContext,
      previousEffectiveFolderId: Long? = null,
      previousEffectiveFolderName: String? = null,
    ): String? {
      if (effectiveFolderId == null) return null
      val targetAvailability = folderContext.effectiveTarget
      if (
        targetAvailability is FolderAvailability.Active &&
          targetAvailability.folderId == effectiveFolderId
      ) {
        return targetAvailability.folder.name
      }
      if (previousEffectiveFolderId == effectiveFolderId && previousEffectiveFolderName != null) {
        return previousEffectiveFolderName
      }
      return null
    }
  }
}
