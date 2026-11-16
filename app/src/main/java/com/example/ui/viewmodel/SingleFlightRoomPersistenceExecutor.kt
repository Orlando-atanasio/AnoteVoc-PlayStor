package com.example.ui.viewmodel

import com.example.domain.editor.NoteEditorSessionIdentity
import com.example.domain.editor.toImmutableSnapshot
import com.example.domain.model.Note
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.NoteRecoveryDraft
import com.example.domain.model.NoteRecoveryDraftInput
import com.example.domain.model.NoteRecoveryDraftKeys
import com.example.domain.model.ReplaceCheckpointWithNewNoteResult
import com.example.domain.model.SaveCheckpointResult
import com.example.domain.repository.InvalidRecoveryDraftException
import com.example.domain.repository.NoteConflictException
import com.example.domain.repository.NoteRecoveryDraftRepository
import com.example.domain.repository.NoteRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Estado conhecido da existência física do checkpoint da sessão no banco Room.
 *
 * Propriedade exclusiva do [SingleFlightRoomPersistenceExecutor].
 */
enum class CheckpointPresence {
  ABSENT,
  PRESENT,
  UNKNOWN,
}

/**
 * Registro explícito e imutável de substituição de checkpoint pendente ou em estado indeterminado.
 *
 * Enquanto ativo no executor, nenhuma mutação, exclusão, consolidação ou reconciliação da identidade
 * antiga é permitida, e somente a repetição integralmente idêntica pode resolver o estado.
 */
data class PendingReplacementState(
  val oldDraftId: String,
  val expectedOldSessionId: String,
  val expectedOldTargetNoteId: Long,
  val oldIdentity: NoteEditorSessionIdentity,
  val newPayload: CheckpointSavePayload,
  val newDraftRevision: Long,
)

/**
 * Retrato imutável e não bloqueante do estado interno do executor de persistência Room.
 */
data class PersistenceExecutorSnapshot(
  val draftId: String,
  val sessionId: String,
  val targetNoteId: Long?,
  val checkpointPresence: CheckpointPresence,
  val confirmedCheckpointRevision: Long,
  val highestAllocatedRevision: Long,
  val lastCheckpointAt: Long?,
  val hasInFlightOperation: Boolean,
  val pendingReplacement: PendingReplacementState? = null,
) {
  val hasPendingReplacement: Boolean
    get() = pendingReplacement != null

  /**
   * Enquanto houver operação em voo, substituição pendente/indeterminada ou presença `UNKNOWN`,
   * a sessão não pode ser declarada integralmente sincronizada.
   */
  val isSyncConfirmationAllowed: Boolean
    get() =
      checkpointPresence != CheckpointPresence.UNKNOWN &&
        !hasInFlightOperation &&
        pendingReplacement == null
}

/**
 * Payload validado e imutável para gravação de checkpoint de sessão, sem revisão embutida
 * (a revisão é alocada monotonicamente pelo [SingleFlightRoomPersistenceExecutor]).
 */
class CheckpointSavePayload private constructor(
  val draftId: String,
  val sessionId: String,
  val targetNoteId: Long?,
  val folderId: Long?,
  val initialFolderId: Long?,
  val fallbackSourceFolderId: Long?,
  val isPinned: Boolean,
  val title: String,
  val content: NoteContentDocument,
  val paperStyle: NotePaperStyle,
  val initialPaperStyle: NotePaperStyle?,
  val baseRevision: Long,
  val baseNoteUpdatedAt: Long?,
) {
  fun toDraftInput(checkpointRevision: Long): NoteRecoveryDraftInput {
    require(checkpointRevision in 1L until Long.MAX_VALUE) {
      "A revisão alocada para o checkpoint deve estar em 1..${Long.MAX_VALUE - 1}: $checkpointRevision"
    }
    return NoteRecoveryDraftInput(
      draftId = draftId,
      targetNoteId = targetNoteId,
      sessionId = sessionId,
      folderId = folderId,
      initialFolderId = initialFolderId,
      fallbackSourceFolderId = fallbackSourceFolderId,
      isPinned = isPinned,
      title = title,
      content = content,
      paperStyle = paperStyle,
      initialPaperStyle = initialPaperStyle,
      baseRevision = baseRevision,
      checkpointRevision = checkpointRevision,
      baseNoteUpdatedAt = baseNoteUpdatedAt,
    )
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is CheckpointSavePayload) return false
    return draftId == other.draftId &&
      sessionId == other.sessionId &&
      targetNoteId == other.targetNoteId &&
      folderId == other.folderId &&
      initialFolderId == other.initialFolderId &&
      fallbackSourceFolderId == other.fallbackSourceFolderId &&
      isPinned == other.isPinned &&
      title == other.title &&
      content == other.content &&
      paperStyle == other.paperStyle &&
      initialPaperStyle == other.initialPaperStyle &&
      baseRevision == other.baseRevision &&
      baseNoteUpdatedAt == other.baseNoteUpdatedAt
  }

  override fun hashCode(): Int {
    var result = draftId.hashCode()
    result = 31 * result + sessionId.hashCode()
    result = 31 * result + (targetNoteId?.hashCode() ?: 0)
    result = 31 * result + (folderId?.hashCode() ?: 0)
    result = 31 * result + (initialFolderId?.hashCode() ?: 0)
    result = 31 * result + (fallbackSourceFolderId?.hashCode() ?: 0)
    result = 31 * result + isPinned.hashCode()
    result = 31 * result + title.hashCode()
    result = 31 * result + content.hashCode()
    result = 31 * result + paperStyle.hashCode()
    result = 31 * result + (initialPaperStyle?.hashCode() ?: 0)
    result = 31 * result + baseRevision.hashCode()
    result = 31 * result + (baseNoteUpdatedAt?.hashCode() ?: 0)
    return result
  }

  override fun toString(): String =
    "CheckpointSavePayload(draftId='$draftId', sessionId='$sessionId', targetNoteId=$targetNoteId, " +
      "folderId=$folderId, isPinned=$isPinned, baseRevision=$baseRevision)"

  companion object {
    operator fun invoke(
      draftId: String,
      sessionId: String,
      targetNoteId: Long?,
      folderId: Long?,
      initialFolderId: Long? = null,
      fallbackSourceFolderId: Long?,
      isPinned: Boolean,
      title: String,
      content: NoteContentDocument,
      paperStyle: NotePaperStyle,
      initialPaperStyle: NotePaperStyle? = null,
      baseRevision: Long,
      baseNoteUpdatedAt: Long?,
    ): CheckpointSavePayload {
      val (cleanDraftId, cleanSessionId) =
        NoteRecoveryDraftKeys.assertConsistentKeys(
          draftId = draftId,
          sessionId = sessionId,
          targetNoteId = targetNoteId,
        )
      require(folderId == null || fallbackSourceFolderId == null) {
        "folderId e fallbackSourceFolderId são mutuamente exclusivos."
      }
      if (fallbackSourceFolderId != null) {
        require(fallbackSourceFolderId > 0L) {
          "fallbackSourceFolderId inválido: $fallbackSourceFolderId"
        }
      }
      if (targetNoteId == null) {
        require(baseRevision == 0L && baseNoteUpdatedAt == null) {
          "Rascunho de nova nota exige baseRevision=0 e baseNoteUpdatedAt nulo."
        }
      } else {
        require(baseRevision > 0L && baseNoteUpdatedAt != null && baseNoteUpdatedAt > 0L) {
          "Rascunho de nota existente exige baseRevision e baseNoteUpdatedAt positivos."
        }
      }
      return CheckpointSavePayload(
        draftId = cleanDraftId,
        sessionId = cleanSessionId,
        targetNoteId = targetNoteId,
        folderId = folderId,
        initialFolderId = initialFolderId,
        fallbackSourceFolderId = fallbackSourceFolderId,
        isPinned = isPinned,
        title = title,
        content = content.toImmutableSnapshot(),
        paperStyle = paperStyle,
        initialPaperStyle = initialPaperStyle,
        baseRevision = baseRevision,
        baseNoteUpdatedAt = baseNoteUpdatedAt,
      )
    }

    fun fromIdentity(
      identity: NoteEditorSessionIdentity,
      folderId: Long?,
      initialFolderId: Long? = null,
      fallbackSourceFolderId: Long? = null,
      isPinned: Boolean,
      title: String,
      content: NoteContentDocument,
      paperStyle: NotePaperStyle,
      initialPaperStyle: NotePaperStyle? = null,
      baseRevision: Long,
      baseNoteUpdatedAt: Long?,
    ): CheckpointSavePayload =
      invoke(
        draftId = identity.draftId,
        sessionId = identity.sessionId,
        targetNoteId = identity.targetNoteId,
        folderId = folderId,
        initialFolderId = initialFolderId,
        fallbackSourceFolderId = fallbackSourceFolderId,
        isPinned = isPinned,
        title = title,
        content = content,
        paperStyle = paperStyle,
        initialPaperStyle = initialPaperStyle,
        baseRevision = baseRevision,
        baseNoteUpdatedAt = baseNoteUpdatedAt,
      )
  }
}

sealed interface SaveCheckpointExecutionResult {
  val snapshot: PersistenceExecutorSnapshot

  data class Saved(
    val draft: NoteRecoveryDraft,
    val allocatedRevision: Long,
    override val snapshot: PersistenceExecutorSnapshot,
  ) : SaveCheckpointExecutionResult

  data class IgnoredStaleRevision(
    val draftId: String,
    val persistedCheckpointRevision: Long,
    val attemptedCheckpointRevision: Long,
    override val snapshot: PersistenceExecutorSnapshot,
  ) : SaveCheckpointExecutionResult

  data class RevisionOverflow(
    val draftId: String,
    val currentHighestAllocatedRevision: Long,
    override val snapshot: PersistenceExecutorSnapshot,
  ) : SaveCheckpointExecutionResult

  data class Failed(
    val cause: Throwable,
    override val snapshot: PersistenceExecutorSnapshot,
  ) : SaveCheckpointExecutionResult
}

sealed interface DeleteCheckpointExecutionResult {
  val snapshot: PersistenceExecutorSnapshot

  data class Deleted(
    val draftId: String,
    override val snapshot: PersistenceExecutorSnapshot,
  ) : DeleteCheckpointExecutionResult

  data class Failed(
    val draftId: String,
    val cause: Throwable,
    override val snapshot: PersistenceExecutorSnapshot,
  ) : DeleteCheckpointExecutionResult
}

sealed interface ReplaceCheckpointExecutionResult {
  val snapshot: PersistenceExecutorSnapshot

  data class Replaced(
    val draft: NoteRecoveryDraft,
    override val snapshot: PersistenceExecutorSnapshot,
  ) : ReplaceCheckpointExecutionResult

  data class AlreadyReplaced(
    val draft: NoteRecoveryDraft,
    override val snapshot: PersistenceExecutorSnapshot,
  ) : ReplaceCheckpointExecutionResult

  data class RevisionOverflow(
    val draftId: String,
    val currentHighestAllocatedRevision: Long,
    override val snapshot: PersistenceExecutorSnapshot,
  ) : ReplaceCheckpointExecutionResult

  data class Failed(
    val cause: Throwable,
    override val snapshot: PersistenceExecutorSnapshot,
  ) : ReplaceCheckpointExecutionResult
}

sealed interface ConsolidateNoteExecutionResult {
  val snapshot: PersistenceExecutorSnapshot

  data class Consolidated(
    val note: Note,
    val clearedDraftId: String?,
    override val snapshot: PersistenceExecutorSnapshot,
  ) : ConsolidateNoteExecutionResult

  data class Conflict(
    val error: NoteConflictException,
    override val snapshot: PersistenceExecutorSnapshot,
  ) : ConsolidateNoteExecutionResult

  data class Failed(
    val cause: Throwable,
    override val snapshot: PersistenceExecutorSnapshot,
  ) : ConsolidateNoteExecutionResult
}

sealed interface ReconcileCheckpointExecutionResult {
  val snapshot: PersistenceExecutorSnapshot

  data class Reconciled(
    val persistedDraft: NoteRecoveryDraft?,
    override val snapshot: PersistenceExecutorSnapshot,
  ) : ReconcileCheckpointExecutionResult

  data class Failed(
    val draftId: String,
    val cause: Throwable,
    override val snapshot: PersistenceExecutorSnapshot,
  ) : ReconcileCheckpointExecutionResult
}

/**
 * Executor single-flight exclusivo da sessão para todas as operações de persistência no Room.
 *
 * Separa rigorosamente:
 * - [mutex]: serialização estrita em fila FIFO entre chamadas suspensas aos repositórios;
 * - [stateLock]: monitor instantâneo sem I/O nem callbacks externos para leitura/atualização do
 *   estado interno em memória (`checkpointPresence`, `confirmedCheckpointRevision`,
 *   `highestAllocatedRevision`, `hasInFlightOperation`).
 */
class SingleFlightRoomPersistenceExecutor(
  private val draftRepository: NoteRecoveryDraftRepository,
  private val noteRepository: NoteRepository,
  initialIdentity: NoteEditorSessionIdentity,
  initialPresence: CheckpointPresence = CheckpointPresence.ABSENT,
  initialConfirmedRevision: Long = 0L,
  initialHighestAllocatedRevision: Long = initialConfirmedRevision,
  initialLastCheckpointAt: Long? = null,
) {
  private val mutex = Mutex()
  private val stateLock = Any()

  private var trackedIdentity: NoteEditorSessionIdentity = initialIdentity
  private var checkpointPresence: CheckpointPresence = initialPresence
  private var confirmedCheckpointRevision: Long = initialConfirmedRevision
  private var highestAllocatedRevision: Long = initialHighestAllocatedRevision
  private var lastCheckpointAt: Long? = initialLastCheckpointAt
  private var hasInFlightOperation: Boolean = false
  private var pendingReplacement: PendingReplacementState? = null

  init {
    require(initialConfirmedRevision >= 0L) {
      "Revisão confirmada inicial inválida: $initialConfirmedRevision"
    }
    require(initialHighestAllocatedRevision >= initialConfirmedRevision) {
      "highestAllocatedRevision inicial ($initialHighestAllocatedRevision) não pode ser menor que confirmedCheckpointRevision ($initialConfirmedRevision)."
    }
    when (initialPresence) {
      CheckpointPresence.ABSENT -> {
        require(initialConfirmedRevision == 0L) {
          "Presença inicial ABSENT exige confirmedCheckpointRevision igual a 0."
        }
      }
      CheckpointPresence.PRESENT -> {
        require(initialConfirmedRevision > 0L) {
          "Presença inicial PRESENT exige confirmedCheckpointRevision positiva."
        }
      }
      CheckpointPresence.UNKNOWN -> Unit
    }
  }

  fun snapshot(): PersistenceExecutorSnapshot =
    synchronized(stateLock) { snapshotLocked() }

  suspend fun saveCheckpoint(payload: CheckpointSavePayload): SaveCheckpointExecutionResult =
    mutex.withLock {
      val currentIdentity = synchronized(stateLock) { trackedIdentity }
      require(payload.draftId == currentIdentity.draftId) {
        "draftId divergente da identidade ativa do executor: esperado='${currentIdentity.draftId}', recebido='${payload.draftId}'."
      }
      require(payload.sessionId == currentIdentity.sessionId) {
        "sessionId divergente da identidade ativa do executor: esperado='${currentIdentity.sessionId}', recebido='${payload.sessionId}'."
      }
      require(payload.targetNoteId == currentIdentity.targetNoteId) {
        "targetNoteId divergente da identidade ativa do executor: esperado=${currentIdentity.targetNoteId}, recebido=${payload.targetNoteId}."
      }

      synchronized(stateLock) {
        if (pendingReplacement != null) {
          return@withLock SaveCheckpointExecutionResult.Failed(
            cause =
              IllegalStateException(
                "Operação saveCheckpoint bloqueada: existe substituição de checkpoint em estado indeterminado."
              ),
            snapshot = snapshotLocked(),
          )
        }
        if (tryComputeNextCandidateRevisionLocked() == null) {
          return@withLock SaveCheckpointExecutionResult.RevisionOverflow(
            draftId = payload.draftId,
            currentHighestAllocatedRevision = highestAllocatedRevision,
            snapshot = snapshotLocked(),
          )
        }
        hasInFlightOperation = true
      }

      try {
        val currentPresence = synchronized(stateLock) { checkpointPresence }
        if (currentPresence == CheckpointPresence.UNKNOWN) {
          val persisted =
            try {
              draftRepository.getCheckpointById(payload.draftId)
            } catch (e: CancellationException) {
              throw e
            } catch (e: Throwable) {
              val snap = synchronized(stateLock) {
                checkpointPresence = CheckpointPresence.UNKNOWN
                hasInFlightOperation = false
                snapshotLocked()
              }
              return@withLock SaveCheckpointExecutionResult.Failed(e, snap)
            }

          if (persisted == null) {
            synchronized(stateLock) {
              checkpointPresence = CheckpointPresence.ABSENT
              confirmedCheckpointRevision = 0L
              lastCheckpointAt = null
            }
          } else {
            if (
              persisted.draftId != payload.draftId ||
                persisted.sessionId != payload.sessionId ||
                persisted.targetNoteId != payload.targetNoteId
            ) {
              val error =
                InvalidRecoveryDraftException(
                  "Checkpoint persistido '${persisted.draftId}' diverge da identidade esperada da sessão."
                )
              val snap = synchronized(stateLock) {
                checkpointPresence = CheckpointPresence.UNKNOWN
                hasInFlightOperation = false
                snapshotLocked()
              }
              return@withLock SaveCheckpointExecutionResult.Failed(error, snap)
            }

            val staleByUnknownDb =
              synchronized(stateLock) {
                checkpointPresence = CheckpointPresence.PRESENT
                confirmedCheckpointRevision =
                  maxOf(confirmedCheckpointRevision, persisted.checkpointRevision)
                lastCheckpointAt = persisted.checkpointAt

                if (persisted.checkpointRevision > highestAllocatedRevision) {
                  val previousAttemptLimit = highestAllocatedRevision
                  highestAllocatedRevision = persisted.checkpointRevision
                  hasInFlightOperation = false
                  SaveCheckpointExecutionResult.IgnoredStaleRevision(
                    draftId = payload.draftId,
                    persistedCheckpointRevision = persisted.checkpointRevision,
                    attemptedCheckpointRevision = previousAttemptLimit + 1L,
                    snapshot = snapshotLocked(),
                  )
                } else {
                  null
                }
              }

            if (staleByUnknownDb != null) {
              return@withLock staleByUnknownDb
            }
          }
        }

        val allocatedRevision =
          synchronized(stateLock) {
            val candidate = tryComputeNextCandidateRevisionLocked()
            if (candidate == null) {
              hasInFlightOperation = false
              return@withLock SaveCheckpointExecutionResult.RevisionOverflow(
                draftId = payload.draftId,
                currentHighestAllocatedRevision = highestAllocatedRevision,
                snapshot = snapshotLocked(),
              )
            }
            highestAllocatedRevision = candidate
            candidate
          }

        val input = payload.toDraftInput(allocatedRevision)
        when (val result = draftRepository.saveCheckpoint(input)) {
          is SaveCheckpointResult.Saved -> {
            val savedDraft = result.draft
            val snap =
              synchronized(stateLock) {
                checkpointPresence = CheckpointPresence.PRESENT
                confirmedCheckpointRevision =
                  maxOf(confirmedCheckpointRevision, savedDraft.checkpointRevision)
                highestAllocatedRevision =
                  maxOf(highestAllocatedRevision, savedDraft.checkpointRevision)
                lastCheckpointAt = savedDraft.checkpointAt
                hasInFlightOperation = false
                snapshotLocked()
              }
            SaveCheckpointExecutionResult.Saved(
              draft = savedDraft,
              allocatedRevision = allocatedRevision,
              snapshot = snap,
            )
          }

          is SaveCheckpointResult.IgnoredStaleRevision -> {
            val snap =
              synchronized(stateLock) {
                checkpointPresence = CheckpointPresence.PRESENT
                confirmedCheckpointRevision =
                  maxOf(confirmedCheckpointRevision, result.persistedCheckpointRevision)
                highestAllocatedRevision =
                  maxOf(highestAllocatedRevision, result.persistedCheckpointRevision)
                hasInFlightOperation = false
                snapshotLocked()
              }
            SaveCheckpointExecutionResult.IgnoredStaleRevision(
              draftId = result.draftId,
              persistedCheckpointRevision = result.persistedCheckpointRevision,
              attemptedCheckpointRevision = result.attemptedCheckpointRevision,
              snapshot = snap,
            )
          }
        }
      } catch (e: CancellationException) {
        synchronized(stateLock) {
          checkpointPresence = CheckpointPresence.UNKNOWN
          hasInFlightOperation = false
        }
        throw e
      } catch (e: Throwable) {
        val snap =
          synchronized(stateLock) {
            checkpointPresence = CheckpointPresence.UNKNOWN
            hasInFlightOperation = false
            snapshotLocked()
          }
        SaveCheckpointExecutionResult.Failed(cause = e, snapshot = snap)
      } finally {
        synchronized(stateLock) {
          hasInFlightOperation = false
        }
      }
    }

  /**
   * Exclui exclusivamente o checkpoint pertencente à sessão pelo seu `draftId` exato.
   * Nunca aciona exclusão ampla por `noteId` (`deleteCheckpointsForNote`).
   */
  suspend fun deleteCheckpoint(draftId: String): DeleteCheckpointExecutionResult =
    mutex.withLock {
      NoteRecoveryDraftKeys.validateDraftId(draftId)
      val cleanDraftId = draftId.trim()
      val currentIdentity = synchronized(stateLock) { trackedIdentity }
      require(cleanDraftId == currentIdentity.draftId) {
        "Exclusão recusada: draftId '$cleanDraftId' não pertence à identidade ativa '${currentIdentity.draftId}'."
      }

      synchronized(stateLock) {
        if (pendingReplacement != null) {
          return@withLock DeleteCheckpointExecutionResult.Failed(
            draftId = cleanDraftId,
            cause =
              IllegalStateException(
                "Operação deleteCheckpoint bloqueada: existe substituição de checkpoint em estado indeterminado."
              ),
            snapshot = snapshotLocked(),
          )
        }
        hasInFlightOperation = true
      }

      try {
        draftRepository.deleteCheckpoint(cleanDraftId)
        val snap =
          synchronized(stateLock) {
            checkpointPresence = CheckpointPresence.ABSENT
            confirmedCheckpointRevision = 0L
            lastCheckpointAt = null
            hasInFlightOperation = false
            snapshotLocked()
          }
        DeleteCheckpointExecutionResult.Deleted(draftId = cleanDraftId, snapshot = snap)
      } catch (e: CancellationException) {
        synchronized(stateLock) {
          checkpointPresence = CheckpointPresence.UNKNOWN
          hasInFlightOperation = false
        }
        throw e
      } catch (e: Throwable) {
        val snap =
          synchronized(stateLock) {
            checkpointPresence = CheckpointPresence.UNKNOWN
            hasInFlightOperation = false
            snapshotLocked()
          }
        DeleteCheckpointExecutionResult.Failed(
          draftId = cleanDraftId,
          cause = e,
          snapshot = snap,
        )
      } finally {
        synchronized(stateLock) {
          hasInFlightOperation = false
        }
      }
    }

  suspend fun replaceCheckpointWithNewNote(
    oldDraftId: String,
    expectedOldSessionId: String,
    expectedOldTargetNoteId: Long,
    newPayload: CheckpointSavePayload,
    newDraftRevision: Long = 1L,
  ): ReplaceCheckpointExecutionResult =
    mutex.withLock {
      val (currentIdentity, existingPending) =
        synchronized(stateLock) { trackedIdentity to pendingReplacement }

      val executionPending: PendingReplacementState =
        if (existingPending != null) {
          val matchesPending =
            oldDraftId.trim() == existingPending.oldDraftId &&
              expectedOldSessionId.trim() == existingPending.expectedOldSessionId &&
              expectedOldTargetNoteId == existingPending.expectedOldTargetNoteId &&
              currentIdentity == existingPending.oldIdentity &&
              newPayload == existingPending.newPayload &&
              newDraftRevision == existingPending.newDraftRevision
          if (!matchesPending) {
            val snap = synchronized(stateLock) { snapshotLocked() }
            return@withLock ReplaceCheckpointExecutionResult.Failed(
              cause =
                InvalidRecoveryDraftException(
                  "Substituição pendente em estado indeterminado exige repetição integralmente idêntica de todos os parâmetros e do payload."
                ),
              snapshot = snap,
            )
          }
          synchronized(stateLock) {
            hasInFlightOperation = true
          }
          existingPending
        } else {
          require(expectedOldTargetNoteId > 0L) {
            "expectedOldTargetNoteId deve ser positivo: $expectedOldTargetNoteId"
          }
          val (cleanOldDraftId, cleanOldSessionId) =
            NoteRecoveryDraftKeys.assertConsistentKeys(
              draftId = oldDraftId,
              sessionId = expectedOldSessionId,
              targetNoteId = expectedOldTargetNoteId,
            )
          require(cleanOldDraftId == currentIdentity.draftId) {
            "oldDraftId divergente da identidade ativa do executor: esperado='${currentIdentity.draftId}', recebido='$cleanOldDraftId'."
          }
          require(cleanOldSessionId == currentIdentity.sessionId) {
            "expectedOldSessionId divergente da identidade ativa do executor: esperado='${currentIdentity.sessionId}', recebido='$cleanOldSessionId'."
          }
          require(expectedOldTargetNoteId == currentIdentity.targetNoteId) {
            "expectedOldTargetNoteId divergente da identidade ativa do executor: esperado=${currentIdentity.targetNoteId}, recebido=$expectedOldTargetNoteId."
          }
          require(newPayload.targetNoteId == null) {
            "Substituição para nova nota exige newPayload.targetNoteId nulo."
          }
          require(newPayload.draftId != cleanOldDraftId) {
            "Substituição exige novo draftId distinto de '$cleanOldDraftId'."
          }
          require(newPayload.sessionId != cleanOldSessionId) {
            "Substituição exige novo sessionId distinto de '$cleanOldSessionId'."
          }
          require(newDraftRevision > 0L) {
            "A revisão inicial do checkpoint substituto deve ser positiva: $newDraftRevision"
          }

          if (newDraftRevision >= Long.MAX_VALUE) {
            val snap = snapshot()
            return@withLock ReplaceCheckpointExecutionResult.RevisionOverflow(
              draftId = newPayload.draftId,
              currentHighestAllocatedRevision = snap.highestAllocatedRevision,
              snapshot = snap,
            )
          }

          val createdPending =
            PendingReplacementState(
              oldDraftId = cleanOldDraftId,
              expectedOldSessionId = cleanOldSessionId,
              expectedOldTargetNoteId = expectedOldTargetNoteId,
              oldIdentity = currentIdentity,
              newPayload = newPayload,
              newDraftRevision = newDraftRevision,
            )
          synchronized(stateLock) {
            pendingReplacement = createdPending
            hasInFlightOperation = true
          }
          createdPending
        }

      val newInput =
        executionPending.newPayload.toDraftInput(
          checkpointRevision = executionPending.newDraftRevision
        )
      try {
        val result =
          try {
            draftRepository.replaceCheckpointWithNewNote(
              oldDraftId = executionPending.oldDraftId,
              expectedOldSessionId = executionPending.expectedOldSessionId,
              expectedOldTargetNoteId = executionPending.expectedOldTargetNoteId,
              newInput = newInput,
            )
          } catch (e: InvalidRecoveryDraftException) {
            val snap =
              synchronized(stateLock) {
                if (existingPending == null) {
                  // Rejeição transacional determinística na primeira tentativa: o repositório
                  // executa dentro de transação única com rollback integral garantido, não havendo
                  // substituição pendente. Mantemos presence=UNKNOWN para exigir reconciliação
                  // antes de confirmar sincronização.
                  pendingReplacement = null
                }
                checkpointPresence = CheckpointPresence.UNKNOWN
                hasInFlightOperation = false
                snapshotLocked()
              }
            return@withLock ReplaceCheckpointExecutionResult.Failed(cause = e, snapshot = snap)
          }

        when (result) {
          is ReplaceCheckpointWithNewNoteResult.Replaced -> {
            val draft = result.draft
            if (
              draft.draftId != executionPending.newPayload.draftId ||
                draft.sessionId != executionPending.newPayload.sessionId ||
                draft.targetNoteId != null
            ) {
              throw InvalidRecoveryDraftException(
                "Checkpoint retornado após substituição diverge da nova identidade esperada."
              )
            }
            val snap =
              synchronized(stateLock) {
                trackedIdentity =
                  NoteEditorSessionIdentity(
                    draftId = draft.draftId,
                    sessionId = draft.sessionId,
                    targetNoteId = null,
                  )
                checkpointPresence = CheckpointPresence.PRESENT
                confirmedCheckpointRevision = draft.checkpointRevision
                highestAllocatedRevision = draft.checkpointRevision
                lastCheckpointAt = draft.checkpointAt
                pendingReplacement = null
                hasInFlightOperation = false
                snapshotLocked()
              }
            ReplaceCheckpointExecutionResult.Replaced(draft = draft, snapshot = snap)
          }

          is ReplaceCheckpointWithNewNoteResult.AlreadyReplaced -> {
            val draft = result.draft
            if (
              draft.draftId != executionPending.newPayload.draftId ||
                draft.sessionId != executionPending.newPayload.sessionId ||
                draft.targetNoteId != null
            ) {
              throw InvalidRecoveryDraftException(
                "Checkpoint retornado na repetição idempotente diverge da nova identidade esperada."
              )
            }
            val snap =
              synchronized(stateLock) {
                trackedIdentity =
                  NoteEditorSessionIdentity(
                    draftId = draft.draftId,
                    sessionId = draft.sessionId,
                    targetNoteId = null,
                  )
                checkpointPresence = CheckpointPresence.PRESENT
                confirmedCheckpointRevision = draft.checkpointRevision
                highestAllocatedRevision = draft.checkpointRevision
                lastCheckpointAt = draft.checkpointAt
                pendingReplacement = null
                hasInFlightOperation = false
                snapshotLocked()
              }
            ReplaceCheckpointExecutionResult.AlreadyReplaced(draft = draft, snapshot = snap)
          }
        }
      } catch (e: CancellationException) {
        synchronized(stateLock) {
          checkpointPresence = CheckpointPresence.UNKNOWN
          hasInFlightOperation = false
        }
        throw e
      } catch (e: Throwable) {
        val snap =
          synchronized(stateLock) {
            checkpointPresence = CheckpointPresence.UNKNOWN
            hasInFlightOperation = false
            snapshotLocked()
          }
        ReplaceCheckpointExecutionResult.Failed(cause = e, snapshot = snap)
      } finally {
        synchronized(stateLock) {
          hasInFlightOperation = false
        }
      }
    }

  suspend fun consolidateCreateNote(
    title: String,
    content: NoteContentDocument,
    folderId: Long?,
    isPinned: Boolean,
    paperStyle: NotePaperStyle?,
  ): ConsolidateNoteExecutionResult =
    mutex.withLock {
      val (currentIdentity, blockedByPending) =
        synchronized(stateLock) {
          val blocked =
            if (pendingReplacement != null) {
              ConsolidateNoteExecutionResult.Failed(
                cause =
                  IllegalStateException(
                    "Operação consolidateCreateNote bloqueada: existe substituição de checkpoint em estado indeterminado."
                  ),
                snapshot = snapshotLocked(),
              )
            } else {
              null
            }
          trackedIdentity to blocked
        }
      if (blockedByPending != null) {
        return@withLock blockedByPending
      }
      require(currentIdentity.targetNoteId == null) {
        "consolidateCreateNote exige sessão de nova nota, mas targetNoteId=${currentIdentity.targetNoteId}."
      }

      synchronized(stateLock) {
        hasInFlightOperation = true
      }

      var resolvedClearDraftId: String? = null
      try {
        resolvedClearDraftId = resolveClearDraftIdLockedViaRepo(currentIdentity)
        val created =
          noteRepository.createNote(
            title = title,
            content = content,
            folderId = folderId,
            isPinned = isPinned,
            paperStyle = paperStyle,
            clearDraftId = resolvedClearDraftId,
          )
        val snap =
          synchronized(stateLock) {
            if (resolvedClearDraftId != null) {
              checkpointPresence = CheckpointPresence.ABSENT
              confirmedCheckpointRevision = 0L
              lastCheckpointAt = null
            }
            hasInFlightOperation = false
            snapshotLocked()
          }
        ConsolidateNoteExecutionResult.Consolidated(
          note = created,
          clearedDraftId = resolvedClearDraftId,
          snapshot = snap,
        )
      } catch (e: CancellationException) {
        synchronized(stateLock) {
          if (resolvedClearDraftId != null) {
            checkpointPresence = CheckpointPresence.UNKNOWN
          }
          hasInFlightOperation = false
        }
        throw e
      } catch (e: Throwable) {
        val snap =
          synchronized(stateLock) {
            if (resolvedClearDraftId != null) {
              checkpointPresence = CheckpointPresence.UNKNOWN
            }
            hasInFlightOperation = false
            snapshotLocked()
          }
        ConsolidateNoteExecutionResult.Failed(cause = e, snapshot = snap)
      } finally {
        synchronized(stateLock) {
          hasInFlightOperation = false
        }
      }
    }

  suspend fun consolidateUpdateNote(
    noteId: Long,
    title: String,
    content: NoteContentDocument,
    folderId: Long?,
    paperStyle: NotePaperStyle?,
    expectedBaseRevision: Long?,
  ): ConsolidateNoteExecutionResult =
    mutex.withLock {
      val currentIdentity = synchronized(stateLock) { trackedIdentity }
      require(currentIdentity.targetNoteId == noteId) {
        "consolidateUpdateNote divergente da identidade ativa: esperado=${currentIdentity.targetNoteId}, recebido=$noteId."
      }

      synchronized(stateLock) {
        if (pendingReplacement != null) {
          return@withLock ConsolidateNoteExecutionResult.Failed(
            cause =
              IllegalStateException(
                "Operação consolidateUpdateNote bloqueada: existe substituição de checkpoint em estado indeterminado."
              ),
            snapshot = snapshotLocked(),
          )
        }
        hasInFlightOperation = true
      }

      var resolvedClearDraftId: String? = null
      try {
        resolvedClearDraftId = resolveClearDraftIdLockedViaRepo(currentIdentity)
        val updated =
          noteRepository.updateNote(
            noteId = noteId,
            title = title,
            content = content,
            folderId = folderId,
            paperStyle = paperStyle,
            expectedBaseRevision = expectedBaseRevision,
            clearDraftId = resolvedClearDraftId,
          )
        val snap =
          synchronized(stateLock) {
            if (resolvedClearDraftId != null) {
              checkpointPresence = CheckpointPresence.ABSENT
              confirmedCheckpointRevision = 0L
              lastCheckpointAt = null
            }
            hasInFlightOperation = false
            snapshotLocked()
          }
        ConsolidateNoteExecutionResult.Consolidated(
          note = updated,
          clearedDraftId = resolvedClearDraftId,
          snapshot = snap,
        )
      } catch (e: NoteConflictException) {
        // NoteRepositoryImpl valida expectedBaseRevision antes de remover o rascunho e faz rollback
        // integral da transação; se o rascunho existia antes, ele permanece PRESENT.
        val snap =
          synchronized(stateLock) {
            checkpointPresence =
              if (resolvedClearDraftId != null) CheckpointPresence.PRESENT
              else CheckpointPresence.ABSENT
            hasInFlightOperation = false
            snapshotLocked()
          }
        ConsolidateNoteExecutionResult.Conflict(error = e, snapshot = snap)
      } catch (e: CancellationException) {
        synchronized(stateLock) {
          if (resolvedClearDraftId != null) {
            checkpointPresence = CheckpointPresence.UNKNOWN
          }
          hasInFlightOperation = false
        }
        throw e
      } catch (e: Throwable) {
        val snap =
          synchronized(stateLock) {
            if (resolvedClearDraftId != null) {
              checkpointPresence = CheckpointPresence.UNKNOWN
            }
            hasInFlightOperation = false
            snapshotLocked()
          }
        ConsolidateNoteExecutionResult.Failed(cause = e, snapshot = snap)
      } finally {
        synchronized(stateLock) {
          hasInFlightOperation = false
        }
      }
    }

  suspend fun reconcileCheckpointPresence(draftId: String): ReconcileCheckpointExecutionResult =
    mutex.withLock {
      NoteRecoveryDraftKeys.validateDraftId(draftId)
      val cleanDraftId = draftId.trim()
      val currentIdentity = synchronized(stateLock) { trackedIdentity }
      require(cleanDraftId == currentIdentity.draftId) {
        "Reconciliação recusada: draftId '$cleanDraftId' não pertence à identidade ativa '${currentIdentity.draftId}'."
      }

      synchronized(stateLock) {
        if (pendingReplacement != null) {
          return@withLock ReconcileCheckpointExecutionResult.Failed(
            draftId = cleanDraftId,
            cause =
              IllegalStateException(
                "Operação reconcileCheckpointPresence bloqueada: existe substituição de checkpoint em estado indeterminado."
              ),
            snapshot = snapshotLocked(),
          )
        }
        hasInFlightOperation = true
      }

      try {
        val persisted = draftRepository.getCheckpointById(cleanDraftId)
        if (persisted == null) {
          val snap =
            synchronized(stateLock) {
              checkpointPresence = CheckpointPresence.ABSENT
              confirmedCheckpointRevision = 0L
              lastCheckpointAt = null
              hasInFlightOperation = false
              snapshotLocked()
            }
          ReconcileCheckpointExecutionResult.Reconciled(persistedDraft = null, snapshot = snap)
        } else {
          if (
            persisted.draftId != currentIdentity.draftId ||
              persisted.sessionId != currentIdentity.sessionId ||
              persisted.targetNoteId != currentIdentity.targetNoteId
          ) {
            throw InvalidRecoveryDraftException(
              "Checkpoint persistido '${persisted.draftId}' diverge da identidade ativa da sessão."
            )
          }
          val snap =
            synchronized(stateLock) {
              checkpointPresence = CheckpointPresence.PRESENT
              confirmedCheckpointRevision =
                maxOf(confirmedCheckpointRevision, persisted.checkpointRevision)
              highestAllocatedRevision =
                maxOf(highestAllocatedRevision, persisted.checkpointRevision)
              lastCheckpointAt = persisted.checkpointAt
              hasInFlightOperation = false
              snapshotLocked()
            }
          ReconcileCheckpointExecutionResult.Reconciled(
            persistedDraft = persisted,
            snapshot = snap,
          )
        }
      } catch (e: CancellationException) {
        synchronized(stateLock) {
          checkpointPresence = CheckpointPresence.UNKNOWN
          hasInFlightOperation = false
        }
        throw e
      } catch (e: Throwable) {
        val snap =
          synchronized(stateLock) {
            checkpointPresence = CheckpointPresence.UNKNOWN
            hasInFlightOperation = false
            snapshotLocked()
          }
        ReconcileCheckpointExecutionResult.Failed(
          draftId = cleanDraftId,
          cause = e,
          snapshot = snap,
        )
      } finally {
        synchronized(stateLock) {
          hasInFlightOperation = false
        }
      }
    }

  private suspend fun resolveClearDraftIdLockedViaRepo(
    currentIdentity: NoteEditorSessionIdentity
  ): String? {
    return when (synchronized(stateLock) { checkpointPresence }) {
      CheckpointPresence.PRESENT -> currentIdentity.draftId
      CheckpointPresence.ABSENT -> null
      CheckpointPresence.UNKNOWN -> {
        val persisted = draftRepository.getCheckpointById(currentIdentity.draftId)
        if (persisted == null) {
          synchronized(stateLock) {
            checkpointPresence = CheckpointPresence.ABSENT
            confirmedCheckpointRevision = 0L
            lastCheckpointAt = null
          }
          null
        } else {
          if (
            persisted.draftId != currentIdentity.draftId ||
              persisted.sessionId != currentIdentity.sessionId ||
              persisted.targetNoteId != currentIdentity.targetNoteId
          ) {
            throw InvalidRecoveryDraftException(
              "Checkpoint persistido '${persisted.draftId}' diverge da identidade ativa durante a consolidação."
            )
          }
          synchronized(stateLock) {
            checkpointPresence = CheckpointPresence.PRESENT
            confirmedCheckpointRevision =
              maxOf(confirmedCheckpointRevision, persisted.checkpointRevision)
            highestAllocatedRevision =
              maxOf(highestAllocatedRevision, persisted.checkpointRevision)
            lastCheckpointAt = persisted.checkpointAt
          }
          currentIdentity.draftId
        }
      }
    }
  }

  private fun tryComputeNextCandidateRevisionLocked(): Long? {
    val current = highestAllocatedRevision
    if (current < 0L || current >= Long.MAX_VALUE - 1L) {
      return null
    }
    val candidate = current + 1L
    return if (candidate <= 0L || candidate >= Long.MAX_VALUE) null else candidate
  }

  private fun snapshotLocked(): PersistenceExecutorSnapshot =
    PersistenceExecutorSnapshot(
      draftId = trackedIdentity.draftId,
      sessionId = trackedIdentity.sessionId,
      targetNoteId = trackedIdentity.targetNoteId,
      checkpointPresence = checkpointPresence,
      confirmedCheckpointRevision = confirmedCheckpointRevision,
      highestAllocatedRevision = highestAllocatedRevision,
      lastCheckpointAt = lastCheckpointAt,
      hasInFlightOperation = hasInFlightOperation,
      pendingReplacement = pendingReplacement,
    )
}
