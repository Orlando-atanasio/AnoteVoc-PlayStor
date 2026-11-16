package com.example.ui.viewmodel

import com.example.core.time.ClockProvider
import com.example.core.time.SystemClockProvider
import com.example.data.local.mapper.NoteContentCodec
import com.example.domain.editor.NoteEditorSessionEvaluator
import com.example.domain.editor.NoteEditorSessionIdentity
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NoteContentState
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.NoteRecoveryDraft
import com.example.domain.model.NoteRecoveryDraftKeys
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class ReplacementHandoffStatus {
  PENDING,
  RESOLVED_TO_NEW,
}

data class ReplacementHandoffRecord(
  val handoffId: Long,
  val oldDraftId: String,
  val oldSessionId: String,
  val oldTargetNoteId: Long,
  val candidateNewDraftId: String,
  val candidateNewSessionId: String,
  val expectedContentDigest: String,
  val expectedTitle: String,
  val expectedFolderId: Long?,
  val expectedInitialFolderId: Long?,
  val expectedFallbackSourceFolderId: Long?,
  val expectedPaperStyle: NotePaperStyle,
  val expectedInitialPaperStyle: NotePaperStyle?,
  val expectedIsPinned: Boolean,
  val expectedCheckpointRevision: Long,
  val status: ReplacementHandoffStatus,
  val createdAtMillis: Long,
  val updatedAtMillis: Long,
) {
  fun matchesPersistedDraft(persisted: NoteRecoveryDraft): Boolean {
    if (persisted.draftId != candidateNewDraftId) return false
    if (persisted.sessionId != candidateNewSessionId) return false
    if (persisted.targetNoteId != null) return false
    if (persisted.folderId != expectedFolderId) return false
    if (persisted.initialFolderId != expectedInitialFolderId) return false
    if (persisted.fallbackSourceFolderId != expectedFallbackSourceFolderId) return false
    if (persisted.isPinned != expectedIsPinned) return false
    if (persisted.title != expectedTitle) return false
    if (persisted.paperStyle != expectedPaperStyle) return false
    if (persisted.initialPaperStyle != expectedInitialPaperStyle) return false
    if (persisted.baseRevision != 0L) return false
    if (persisted.baseNoteUpdatedAt != null) return false
    if (persisted.checkpointRevision != expectedCheckpointRevision) return false
    val validContent = (persisted.contentState as? NoteContentState.Valid)?.document ?: return false
    return ReplacementHandoffTracker.computeContentDigest(validContent) == expectedContentDigest
  }
}

sealed interface RedirectVerificationResult {
  data object NoActiveHandoff : RedirectVerificationResult

  data class Verified(
    val record: ReplacementHandoffRecord,
    val candidateDraft: NoteRecoveryDraft,
  ) : RedirectVerificationResult

  data class RejectedMissing(val obsoleteRecord: ReplacementHandoffRecord) : RedirectVerificationResult

  data class RejectedDivergent(
    val obsoleteRecord: ReplacementHandoffRecord,
  ) : RedirectVerificationResult
}

data class ReplacementHandoffTrackerSnapshot(
  val activeCount: Int,
  val pendingCount: Int,
  val resolvedCount: Int,
  val recordsByOldDraftId: Map<String, ReplacementHandoffRecord>,
)

/**
 * Rastreador thread-safe em memória (escopo de [com.example.core.di.AppContainer]) que associa um
 * `oldDraftId` em conversão `SAVE_DRAFT_AS_NEW_NOTE` ao `candidateNewDraftId` esperado antes da
 * tentativa de substituição no Room.
 *
 * Nunca substitui a persistência durável no Room: todo redirecionamento depende de validação integral
 * contra o registro real no banco, rejeitando e invalidando associações ausentes ou divergentes.
 */
class ReplacementHandoffTracker(
  private val clock: ClockProvider = SystemClockProvider(),
) {
  private val lock = Any()
  private val recordsByOldDraftId = HashMap<String, ReplacementHandoffRecord>()
  private val candidateToOldDraftId = HashMap<String, String>()
  private var nextHandoffId: Long = 1L

  fun registerPending(
    oldIdentity: NoteEditorSessionIdentity,
    newPayload: CheckpointSavePayload,
    newDraftRevision: Long,
  ): ReplacementHandoffRecord {
    val oldTargetNoteId =
      requireNotNull(oldIdentity.targetNoteId) {
        "Registro de handoff exige identidade de origem vinculada a nota existente."
      }
    require(newPayload.targetNoteId == null) {
      "Registro de handoff exige destino de nova nota (targetNoteId nulo)."
    }
    require(newPayload.draftId != oldIdentity.draftId) {
      "candidateNewDraftId deve diferir de oldDraftId '${oldIdentity.draftId}'."
    }
    require(newDraftRevision > 0L) {
      "newDraftRevision deve ser positivo: $newDraftRevision"
    }

    val expectedDigest = computeContentDigest(newPayload.content)
    val now = clock.nowMillis()

    return synchronized(lock) {
      val existingByOld = recordsByOldDraftId[oldIdentity.draftId]
      if (existingByOld != null) {
        val matchesExisting =
          existingByOld.oldSessionId == oldIdentity.sessionId &&
            existingByOld.oldTargetNoteId == oldTargetNoteId &&
            existingByOld.candidateNewDraftId == newPayload.draftId &&
            existingByOld.candidateNewSessionId == newPayload.sessionId &&
            existingByOld.expectedContentDigest == expectedDigest &&
            existingByOld.expectedTitle == newPayload.title &&
            existingByOld.expectedFolderId == newPayload.folderId &&
            existingByOld.expectedInitialFolderId == newPayload.initialFolderId &&
            existingByOld.expectedFallbackSourceFolderId == newPayload.fallbackSourceFolderId &&
            existingByOld.expectedPaperStyle == newPayload.paperStyle &&
            existingByOld.expectedInitialPaperStyle == newPayload.initialPaperStyle &&
            existingByOld.expectedIsPinned == newPayload.isPinned &&
            existingByOld.expectedCheckpointRevision == newDraftRevision

        if (matchesExisting && existingByOld.status == ReplacementHandoffStatus.PENDING) {
          return@synchronized existingByOld
        }
        throw IllegalStateException(
          "Já existe associação ativa para oldDraftId='${oldIdentity.draftId}' (${existingByOld.status})."
        )
      }

      val existingOldForCandidate = candidateToOldDraftId[newPayload.draftId]
      if (existingOldForCandidate != null && existingOldForCandidate != oldIdentity.draftId) {
        throw IllegalStateException(
          "candidateNewDraftId='${newPayload.draftId}' já está associado ao rascunho '$existingOldForCandidate'."
        )
      }

      check(nextHandoffId < Long.MAX_VALUE) { "Limite de identificadores de handoff atingido." }
      val record =
        ReplacementHandoffRecord(
          handoffId = nextHandoffId++,
          oldDraftId = oldIdentity.draftId,
          oldSessionId = oldIdentity.sessionId,
          oldTargetNoteId = oldTargetNoteId,
          candidateNewDraftId = newPayload.draftId,
          candidateNewSessionId = newPayload.sessionId,
          expectedContentDigest = expectedDigest,
          expectedTitle = newPayload.title,
          expectedFolderId = newPayload.folderId,
          expectedInitialFolderId = newPayload.initialFolderId,
          expectedFallbackSourceFolderId = newPayload.fallbackSourceFolderId,
          expectedPaperStyle = newPayload.paperStyle,
          expectedInitialPaperStyle = newPayload.initialPaperStyle,
          expectedIsPinned = newPayload.isPinned,
          expectedCheckpointRevision = newDraftRevision,
          status = ReplacementHandoffStatus.PENDING,
          createdAtMillis = now,
          updatedAtMillis = now,
        )
      recordsByOldDraftId[record.oldDraftId] = record
      candidateToOldDraftId[record.candidateNewDraftId] = record.oldDraftId
      record
    }
  }

  fun findActiveCandidate(oldDraftId: String): ReplacementHandoffRecord? {
    val cleanId = oldDraftId.trim()
    if (cleanId.isEmpty()) return null
    NoteRecoveryDraftKeys.validateDraftId(cleanId)
    return synchronized(lock) { recordsByOldDraftId[cleanId] }
  }

  fun markResolved(
    oldDraftId: String,
    handoffId: Long,
  ): Boolean =
    synchronized(lock) {
      val current = recordsByOldDraftId[oldDraftId] ?: return@synchronized false
      if (current.handoffId != handoffId) return@synchronized false
      val updated =
        current.copy(
          status = ReplacementHandoffStatus.RESOLVED_TO_NEW,
          updatedAtMillis = clock.nowMillis(),
        )
      recordsByOldDraftId[oldDraftId] = updated
      true
    }

  fun preservePending(
    oldDraftId: String,
    handoffId: Long,
  ): Boolean =
    synchronized(lock) {
      val current = recordsByOldDraftId[oldDraftId] ?: return@synchronized false
      if (current.handoffId != handoffId) return@synchronized false
      if (current.status != ReplacementHandoffStatus.PENDING) return@synchronized false
      recordsByOldDraftId[oldDraftId] = current.copy(updatedAtMillis = clock.nowMillis())
      true
    }

  fun clearByHandoffId(
    oldDraftId: String,
    handoffId: Long,
  ): Boolean =
    synchronized(lock) {
      val current = recordsByOldDraftId[oldDraftId] ?: return@synchronized false
      if (current.handoffId != handoffId) return@synchronized false
      removeRecordLocked(current)
      true
    }

  fun invalidateCandidateDraft(candidateNewDraftId: String): Boolean {
    val cleanId = candidateNewDraftId.trim()
    if (cleanId.isEmpty()) return false
    return synchronized(lock) {
      val oldDraftId = candidateToOldDraftId[cleanId] ?: return@synchronized false
      val current = recordsByOldDraftId[oldDraftId] ?: return@synchronized false
      if (current.candidateNewDraftId != cleanId) return@synchronized false
      removeRecordLocked(current)
      true
    }
  }

  /**
   * Valida o rascunho candidato carregado do Room contra [record].
   * Caso ausente ou divergente, remove a associação obsoleta se ainda corresponder ao mesmo `handoffId`.
   */
  fun verifyAndResolveCandidate(
    record: ReplacementHandoffRecord,
    persistedCandidate: NoteRecoveryDraft?,
  ): RedirectVerificationResult {
    if (persistedCandidate == null) {
      clearByHandoffId(record.oldDraftId, record.handoffId)
      return RedirectVerificationResult.RejectedMissing(record)
    }
    if (!record.matchesPersistedDraft(persistedCandidate)) {
      clearByHandoffId(record.oldDraftId, record.handoffId)
      return RedirectVerificationResult.RejectedDivergent(record)
    }

    val verifiedRecord =
      synchronized(lock) {
        val current = recordsByOldDraftId[record.oldDraftId]
        if (current == null || current.handoffId != record.handoffId) {
          return@synchronized null
        }
        if (current.status == ReplacementHandoffStatus.RESOLVED_TO_NEW) {
          current
        } else {
          val updated =
            current.copy(
              status = ReplacementHandoffStatus.RESOLVED_TO_NEW,
              updatedAtMillis = clock.nowMillis(),
            )
          recordsByOldDraftId[record.oldDraftId] = updated
          updated
        }
      } ?: return RedirectVerificationResult.RejectedDivergent(record)

    return RedirectVerificationResult.Verified(
      record = verifiedRecord,
      candidateDraft = persistedCandidate,
    )
  }

  fun snapshot(): ReplacementHandoffTrackerSnapshot =
    synchronized(lock) {
      val copy = recordsByOldDraftId.toMap()
      var pending = 0
      var resolved = 0
      for (entry in copy.values) {
        when (entry.status) {
          ReplacementHandoffStatus.PENDING -> pending += 1
          ReplacementHandoffStatus.RESOLVED_TO_NEW -> resolved += 1
        }
      }
      ReplacementHandoffTrackerSnapshot(
        activeCount = copy.size,
        pendingCount = pending,
        resolvedCount = resolved,
        recordsByOldDraftId = copy,
      )
    }

  private fun removeRecordLocked(record: ReplacementHandoffRecord) {
    recordsByOldDraftId.remove(record.oldDraftId)
    if (candidateToOldDraftId[record.candidateNewDraftId] == record.oldDraftId) {
      candidateToOldDraftId.remove(record.candidateNewDraftId)
    }
  }

  companion object {
    fun computeContentDigest(content: NoteContentDocument): String {
      val canonical = NoteEditorSessionEvaluator.canonicalizeDocument(content)
      val json = NoteContentCodec.encode(canonical)
      val digestBytes = MessageDigest.getInstance("SHA-256").digest(json.toByteArray(StandardCharsets.UTF_8))
      return buildString(digestBytes.size * 2) {
        for (byte in digestBytes) {
          val value = byte.toInt() and 0xFF
          append(HEX_CHARS[value ushr 4])
          append(HEX_CHARS[value and 0x0F])
        }
      }
    }

    private val HEX_CHARS = "0123456789abcdef".toCharArray()
  }
}
