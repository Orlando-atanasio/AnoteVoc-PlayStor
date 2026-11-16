package com.example.ui.viewmodel

import com.example.domain.editor.NoteEditorSessionIdentity
import com.example.domain.model.NoteRecoveryDraftKeys
import java.util.UUID

/**
 * Identidade de sessão gerenciada pelo [ActiveSessionRegistry].
 *
 * Reutiliza integralmente as regras de validação e normalização de [NoteRecoveryDraftKeys].
 * Quando [draftId] for nulo, representa uma sessão ativa sem checkpoint persistido associado,
 * sem inventar uma chave persistida artificialmente.
 */
class RegisteredSessionIdentity private constructor(
  val sessionId: String,
  val draftId: String?,
  val targetNoteId: Long?,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is RegisteredSessionIdentity) return false
    return sessionId == other.sessionId &&
      draftId == other.draftId &&
      targetNoteId == other.targetNoteId
  }

  override fun hashCode(): Int {
    var result = sessionId.hashCode()
    result = 31 * result + (draftId?.hashCode() ?: 0)
    result = 31 * result + (targetNoteId?.hashCode() ?: 0)
    return result
  }

  override fun toString(): String =
    "RegisteredSessionIdentity(sessionId='$sessionId', draftId=$draftId, targetNoteId=$targetNoteId)"

  companion object {
    operator fun invoke(
      sessionId: String,
      draftId: String? = null,
      targetNoteId: Long? = null,
    ): RegisteredSessionIdentity {
      if (targetNoteId != null) {
        require(targetNoteId > 0L) {
          "Identificador de nota alvo inválido para registro de sessão: $targetNoteId"
        }
      }

      return if (draftId == null) {
        val cleanSessionId = NoteRecoveryDraftKeys.normalizeAndValidateSessionId(sessionId)
        RegisteredSessionIdentity(
          sessionId = cleanSessionId,
          draftId = null,
          targetNoteId = targetNoteId,
        )
      } else {
        val (cleanDraftId, cleanSessionId) =
          NoteRecoveryDraftKeys.assertConsistentKeys(
            draftId = draftId,
            sessionId = sessionId,
            targetNoteId = targetNoteId,
          )
        RegisteredSessionIdentity(
          sessionId = cleanSessionId,
          draftId = cleanDraftId,
          targetNoteId = targetNoteId,
        )
      }
    }

    fun fromSessionIdentity(identity: NoteEditorSessionIdentity): RegisteredSessionIdentity =
      invoke(
        sessionId = identity.sessionId,
        draftId = identity.draftId,
        targetNoteId = identity.targetNoteId,
      )

    fun withoutCheckpoint(
      sessionId: String,
      targetNoteId: Long? = null,
    ): RegisteredSessionIdentity =
      invoke(
        sessionId = sessionId,
        draftId = null,
        targetNoteId = targetNoteId,
      )
  }
}

/**
 * Token exclusivo de posse de uma reserva confirmada no [ActiveSessionRegistry].
 * Sem `copy()` para impedir forjamento parcial de identificador ou instância.
 */
class SessionLeaseToken internal constructor(
  internal val registryInstanceId: UUID,
  val id: Long,
  val identity: RegisteredSessionIdentity,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is SessionLeaseToken) return false
    return registryInstanceId == other.registryInstanceId &&
      id == other.id &&
      identity == other.identity
  }

  override fun hashCode(): Int {
    var result = registryInstanceId.hashCode()
    result = 31 * result + id.hashCode()
    result = 31 * result + identity.hashCode()
    return result
  }

  override fun toString(): String = "SessionLeaseToken(id=$id, identity=$identity)"
}

/**
 * Token exclusivo que identifica inequivocamente uma transferência pendente entre uma reserva de
 * origem e uma reserva provisória de destino.
 */
class IdentityTransferToken internal constructor(
  internal val registryInstanceId: UUID,
  val id: Long,
  internal val sourceLease: SessionLeaseToken,
  internal val targetLease: SessionLeaseToken,
) {
  val sourceIdentity: RegisteredSessionIdentity
    get() = sourceLease.identity

  val targetIdentity: RegisteredSessionIdentity
    get() = targetLease.identity

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is IdentityTransferToken) return false
    return registryInstanceId == other.registryInstanceId &&
      id == other.id &&
      sourceLease == other.sourceLease &&
      targetLease == other.targetLease
  }

  override fun hashCode(): Int {
    var result = registryInstanceId.hashCode()
    result = 31 * result + id.hashCode()
    result = 31 * result + sourceLease.hashCode()
    result = 31 * result + targetLease.hashCode()
    return result
  }

  override fun toString(): String =
    "IdentityTransferToken(id=$id, source=${sourceLease.identity}, target=${targetLease.identity})"
}

sealed interface SessionReservationResult {
  data class Reserved(val lease: SessionLeaseToken) : SessionReservationResult

  data class Conflict(
    val requestedIdentity: RegisteredSessionIdentity,
    val sessionIdConflict: Boolean,
    val draftIdConflict: Boolean,
  ) : SessionReservationResult
}

sealed interface ReserveTransferResult {
  data class Reserved(val transferToken: IdentityTransferToken) : ReserveTransferResult

  data class TargetConflict(
    val targetIdentity: RegisteredSessionIdentity,
    val sessionIdConflict: Boolean,
    val draftIdConflict: Boolean,
  ) : ReserveTransferResult

  data object SourceLeaseNotActive : ReserveTransferResult

  data object SourceTransferAlreadyPending : ReserveTransferResult
}

sealed interface CommitTransferResult {
  data class Committed(val newLease: SessionLeaseToken) : CommitTransferResult

  data class AlreadyCommitted(val newLease: SessionLeaseToken) : CommitTransferResult

  data object RejectedAlreadyRolledBack : CommitTransferResult

  data object InvalidOrObsoleteToken : CommitTransferResult
}

sealed interface RollbackTransferResult {
  data class RolledBack(val preservedSourceLease: SessionLeaseToken) : RollbackTransferResult

  data object AlreadyRolledBack : RollbackTransferResult

  data object RejectedAlreadyCommitted : RollbackTransferResult

  data object InvalidOrObsoleteToken : RollbackTransferResult
}

data class ActiveRegistrySnapshot(
  val reservedSessionIds: Set<String>,
  val reservedDraftIds: Set<String>,
  val pendingTransferCount: Int,
)

/**
 * Registro thread-safe para reserva exclusiva de identidades de sessão (`sessionId` e `draftId`).
 *
 * Garante sob monitor único (`lock`) que:
 * - nenhuma colisão parcial deixe reservas órfãs;
 * - duas sessões nunca ocupem o mesmo `sessionId` ou o mesmo `draftId`;
 * - transferências de identidade sejam atômicas via reserva provisória (`reserveIdentityTransfer`),
 *   efetivação (`commitIdentityTransfer`) ou reversão (`rollbackIdentityTransfer`), sem janela
 *   observável com ambas as identidades livres ou com dois proprietários para a mesma identidade.
 */
class ActiveSessionRegistry {
  private val registryInstanceId: UUID = UUID.randomUUID()
  private val lock = Any()

  private val sessionOwners = HashMap<String, OwnershipSlot>()
  private val draftOwners = HashMap<String, OwnershipSlot>()
  private val activeLeases = HashMap<Long, RegisteredSessionIdentity>()
  private val pendingTransfers = HashMap<Long, PendingTransferRecord>()
  private val pendingTransferBySourceLeaseId = HashMap<Long, Long>()
  /**
   * Histórico de transferências indexado por `token.id`.
   *
   * Mantido durante toda a vida útil da instância de [ActiveSessionRegistry] sem expiração ou limite
   * arbitrário de tamanho, pois o descarte de registros impediria distinguir tokens obsoletos/revertidos
   * de tokens efetivados e violaria a idempotência estrita (`AlreadyCommitted`, `AlreadyRolledBack`,
   * `RejectedAlreadyCommitted`, `RejectedAlreadyRolledBack`).
   */
  private val transferHistory = HashMap<Long, TransferOutcomeRecord>()

  private var nextLeaseId: Long = 1L
  private var nextTransferId: Long = 1L

  fun isSessionReserved(sessionId: String): Boolean {
    val cleanSessionId = NoteRecoveryDraftKeys.normalizeAndValidateSessionId(sessionId)
    return synchronized(lock) { sessionOwners.containsKey(cleanSessionId) }
  }

  fun isDraftReserved(draftId: String): Boolean {
    val cleanDraftId = draftId.trim()
    NoteRecoveryDraftKeys.validateDraftId(cleanDraftId)
    return synchronized(lock) { draftOwners.containsKey(cleanDraftId) }
  }

  fun isLeaseActive(lease: SessionLeaseToken): Boolean {
    if (lease.registryInstanceId != registryInstanceId) return false
    return synchronized(lock) { activeLeases[lease.id] == lease.identity }
  }

  fun snapshot(): ActiveRegistrySnapshot =
    synchronized(lock) {
      ActiveRegistrySnapshot(
        reservedSessionIds = sessionOwners.keys.toSet(),
        reservedDraftIds = draftOwners.keys.toSet(),
        pendingTransferCount = pendingTransfers.size,
      )
    }

  fun reserve(identity: NoteEditorSessionIdentity): SessionReservationResult =
    reserve(RegisteredSessionIdentity.fromSessionIdentity(identity))

  fun reserve(identity: RegisteredSessionIdentity): SessionReservationResult =
    synchronized(lock) {
      val sessionConflict = sessionOwners.containsKey(identity.sessionId)
      val draftConflict = identity.draftId != null && draftOwners.containsKey(identity.draftId)
      if (sessionConflict || draftConflict) {
        return@synchronized SessionReservationResult.Conflict(
          requestedIdentity = identity,
          sessionIdConflict = sessionConflict,
          draftIdConflict = draftConflict,
        )
      }

      check(nextLeaseId < Long.MAX_VALUE) { "Limite de identificadores de reserva atingido." }
      val lease =
        SessionLeaseToken(
          registryInstanceId = registryInstanceId,
          id = nextLeaseId++,
          identity = identity,
        )
      val slot = OwnershipSlot(leaseId = lease.id, isProvisional = false)
      sessionOwners[identity.sessionId] = slot
      if (identity.draftId != null) {
        draftOwners[identity.draftId] = slot
      }
      activeLeases[lease.id] = identity
      SessionReservationResult.Reserved(lease)
    }

  /**
   * Libera uma reserva ativa pertencente exatamente a [lease].
   * Caso exista transferência pendente originada de [lease], remove também sua reserva provisória.
   */
  fun release(lease: SessionLeaseToken): Boolean {
    if (lease.registryInstanceId != registryInstanceId) return false
    return synchronized(lock) {
      val currentIdentity = activeLeases[lease.id] ?: return@synchronized false
      if (currentIdentity != lease.identity) {
        return@synchronized false
      }

      val pendingTransferId = pendingTransferBySourceLeaseId.remove(lease.id)
      if (pendingTransferId != null) {
        val pending = pendingTransfers.remove(pendingTransferId)
        if (pending != null) {
          removeProvisionalKeysLocked(pending)
          transferHistory[pendingTransferId] = TransferOutcomeRecord.RolledBack(pending.token)
        }
      }

      activeLeases.remove(lease.id)
      if (sessionOwners[currentIdentity.sessionId]?.leaseId == lease.id) {
        sessionOwners.remove(currentIdentity.sessionId)
      }
      if (currentIdentity.draftId != null && draftOwners[currentIdentity.draftId]?.leaseId == lease.id) {
        draftOwners.remove(currentIdentity.draftId)
      }
      true
    }
  }

  /**
   * Reserva provisoriamente [targetIdentity] sem liberar a identidade original de [currentLease].
   */
  fun reserveIdentityTransfer(
    currentLease: SessionLeaseToken,
    targetIdentity: RegisteredSessionIdentity,
  ): ReserveTransferResult {
    if (currentLease.registryInstanceId != registryInstanceId) {
      return ReserveTransferResult.SourceLeaseNotActive
    }
    return synchronized(lock) {
      val activeSource = activeLeases[currentLease.id]
      if (activeSource == null || activeSource != currentLease.identity) {
        return@synchronized ReserveTransferResult.SourceLeaseNotActive
      }
      if (pendingTransferBySourceLeaseId.containsKey(currentLease.id)) {
        return@synchronized ReserveTransferResult.SourceTransferAlreadyPending
      }

      val sharesSessionKey = targetIdentity.sessionId == activeSource.sessionId
      val sharesDraftKey =
        targetIdentity.draftId != null && targetIdentity.draftId == activeSource.draftId

      val existingSessionOwner = sessionOwners[targetIdentity.sessionId]
      val sessionConflict = existingSessionOwner != null && !sharesSessionKey

      val existingDraftOwner = targetIdentity.draftId?.let { draftOwners[it] }
      val draftConflict = existingDraftOwner != null && !sharesDraftKey

      if (sessionConflict || draftConflict) {
        return@synchronized ReserveTransferResult.TargetConflict(
          targetIdentity = targetIdentity,
          sessionIdConflict = sessionConflict,
          draftIdConflict = draftConflict,
        )
      }

      check(nextLeaseId < Long.MAX_VALUE) { "Limite de identificadores de reserva atingido." }
      check(nextTransferId < Long.MAX_VALUE) { "Limite de identificadores de transferência atingido." }

      val targetLease =
        SessionLeaseToken(
          registryInstanceId = registryInstanceId,
          id = nextLeaseId++,
          identity = targetIdentity,
        )
      val token =
        IdentityTransferToken(
          registryInstanceId = registryInstanceId,
          id = nextTransferId++,
          sourceLease = currentLease,
          targetLease = targetLease,
        )
      val record =
        PendingTransferRecord(
          token = token,
          sharesSessionKey = sharesSessionKey,
          sharesDraftKey = sharesDraftKey,
        )

      if (!sharesSessionKey) {
        sessionOwners[targetIdentity.sessionId] =
          OwnershipSlot(leaseId = targetLease.id, isProvisional = true)
      }
      if (targetIdentity.draftId != null && !sharesDraftKey) {
        draftOwners[targetIdentity.draftId] =
          OwnershipSlot(leaseId = targetLease.id, isProvisional = true)
      }

      pendingTransfers[token.id] = record
      pendingTransferBySourceLeaseId[currentLease.id] = token.id
      transferHistory[token.id] = TransferOutcomeRecord.Pending(token)
      ReserveTransferResult.Reserved(token)
    }
  }

  /**
   * Efetiva atomicamente a transferência identificada por [token], liberando as chaves antigas
   * exclusivas da origem e promovendo a reserva de destino para confirmada.
   */
  fun commitIdentityTransfer(token: IdentityTransferToken): CommitTransferResult {
    if (token.registryInstanceId != registryInstanceId) {
      return CommitTransferResult.InvalidOrObsoleteToken
    }
    return synchronized(lock) {
      val pending = pendingTransfers[token.id]
      if (pending == null) {
        return@synchronized when (val history = transferHistory[token.id]) {
          is TransferOutcomeRecord.Committed ->
            if (history.token == token) {
              CommitTransferResult.AlreadyCommitted(history.token.targetLease)
            } else {
              CommitTransferResult.InvalidOrObsoleteToken
            }
          is TransferOutcomeRecord.RolledBack ->
            if (history.token == token) {
              CommitTransferResult.RejectedAlreadyRolledBack
            } else {
              CommitTransferResult.InvalidOrObsoleteToken
            }
          is TransferOutcomeRecord.Pending,
          null -> CommitTransferResult.InvalidOrObsoleteToken
        }
      }

      if (pending.token != token) {
        return@synchronized CommitTransferResult.InvalidOrObsoleteToken
      }

      val sourceIdentity = pending.token.sourceIdentity
      val targetIdentity = pending.token.targetIdentity
      val sourceLeaseId = pending.token.sourceLease.id
      val targetLeaseId = pending.token.targetLease.id

      // Remove apenas chaves da origem que não pertencem ao destino e promove tudo ao novo leaseId.
      if (!pending.sharesSessionKey && sessionOwners[sourceIdentity.sessionId]?.leaseId == sourceLeaseId) {
        sessionOwners.remove(sourceIdentity.sessionId)
      }
      if (
        sourceIdentity.draftId != null &&
          !pending.sharesDraftKey &&
          draftOwners[sourceIdentity.draftId]?.leaseId == sourceLeaseId
      ) {
        draftOwners.remove(sourceIdentity.draftId)
      }

      val committedSlot = OwnershipSlot(leaseId = targetLeaseId, isProvisional = false)
      sessionOwners[targetIdentity.sessionId] = committedSlot
      if (targetIdentity.draftId != null) {
        draftOwners[targetIdentity.draftId] = committedSlot
      }

      activeLeases.remove(sourceLeaseId)
      activeLeases[targetLeaseId] = targetIdentity

      pendingTransfers.remove(token.id)
      pendingTransferBySourceLeaseId.remove(sourceLeaseId)
      transferHistory[token.id] = TransferOutcomeRecord.Committed(token)

      CommitTransferResult.Committed(pending.token.targetLease)
    }
  }

  /**
   * Cancela a transferência pendente identificada por [token], removendo apenas as reservas
   * provisórias de destino e preservando integralmente a reserva original.
   */
  fun rollbackIdentityTransfer(token: IdentityTransferToken): RollbackTransferResult {
    if (token.registryInstanceId != registryInstanceId) {
      return RollbackTransferResult.InvalidOrObsoleteToken
    }
    return synchronized(lock) {
      val pending = pendingTransfers[token.id]
      if (pending == null) {
        return@synchronized when (val history = transferHistory[token.id]) {
          is TransferOutcomeRecord.Committed -> commitHistoryMismatchOrCommitted(history, token)
          is TransferOutcomeRecord.RolledBack ->
            if (history.token == token) {
              RollbackTransferResult.AlreadyRolledBack
            } else {
              RollbackTransferResult.InvalidOrObsoleteToken
            }
          is TransferOutcomeRecord.Pending,
          null -> RollbackTransferResult.InvalidOrObsoleteToken
        }
      }

      if (pending.token != token) {
        return@synchronized RollbackTransferResult.InvalidOrObsoleteToken
      }

      removeProvisionalKeysLocked(pending)
      pendingTransfers.remove(token.id)
      pendingTransferBySourceLeaseId.remove(pending.token.sourceLease.id)
      transferHistory[token.id] = TransferOutcomeRecord.RolledBack(pending.token)

      RollbackTransferResult.RolledBack(pending.token.sourceLease)
    }
  }

  private fun commitHistoryMismatchOrCommitted(
    history: TransferOutcomeRecord.Committed,
    token: IdentityTransferToken,
  ): RollbackTransferResult =
    if (history.token == token) {
      RollbackTransferResult.RejectedAlreadyCommitted
    } else {
      RollbackTransferResult.InvalidOrObsoleteToken
    }

  private fun removeProvisionalKeysLocked(pending: PendingTransferRecord) {
    val targetIdentity = pending.token.targetIdentity
    val targetLeaseId = pending.token.targetLease.id
    if (!pending.sharesSessionKey) {
      val slot = sessionOwners[targetIdentity.sessionId]
      if (slot != null && slot.isProvisional && slot.leaseId == targetLeaseId) {
        sessionOwners.remove(targetIdentity.sessionId)
      }
    }
    if (targetIdentity.draftId != null && !pending.sharesDraftKey) {
      val slot = draftOwners[targetIdentity.draftId]
      if (slot != null && slot.isProvisional && slot.leaseId == targetLeaseId) {
        draftOwners.remove(targetIdentity.draftId)
      }
    }
  }

  private data class OwnershipSlot(
    val leaseId: Long,
    val isProvisional: Boolean,
  )

  private data class PendingTransferRecord(
    val token: IdentityTransferToken,
    val sharesSessionKey: Boolean,
    val sharesDraftKey: Boolean,
  )

  private sealed interface TransferOutcomeRecord {
    data class Pending(val token: IdentityTransferToken) : TransferOutcomeRecord

    data class Committed(val token: IdentityTransferToken) : TransferOutcomeRecord

    data class RolledBack(val token: IdentityTransferToken) : TransferOutcomeRecord
  }
}
