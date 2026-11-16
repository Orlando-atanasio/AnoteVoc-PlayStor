package com.example.data.repository

import androidx.room.withTransaction
import com.example.core.coroutines.DispatcherProvider
import com.example.core.time.ClockProvider
import com.example.data.local.dao.NoteDao
import com.example.data.local.dao.NoteRecoveryDraftDao
import com.example.data.local.database.AnoteVoceDatabase
import com.example.data.local.entity.NoteRecoveryDraftEntity
import com.example.data.local.mapper.NoteContentCodec
import com.example.data.local.mapper.enumValueOrNull
import com.example.data.local.mapper.toDomain
import com.example.domain.model.NoteContentState
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.NoteRecoveryDraft
import com.example.domain.model.NoteRecoveryDraftInput
import com.example.domain.model.NoteRecoveryDraftKeys
import com.example.domain.model.ReplaceCheckpointWithNewNoteResult
import com.example.domain.model.SaveCheckpointResult
import com.example.domain.policy.NoteContentPolicy
import com.example.domain.repository.InvalidRecoveryDraftException
import com.example.domain.repository.NoteDeletedException
import com.example.domain.repository.NoteNotFoundException
import com.example.domain.repository.NoteRecoveryDraftRepository
import com.example.domain.repository.UnreadableNoteContentException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class NoteRecoveryDraftRepositoryImpl(
  private val database: AnoteVoceDatabase,
  private val draftDao: NoteRecoveryDraftDao,
  private val dispatchers: DispatcherProvider,
  private val clock: ClockProvider,
  private val noteDao: NoteDao = database.noteDao(),
) : NoteRecoveryDraftRepository {

  override fun observeAllCheckpoints(): Flow<List<NoteRecoveryDraft>> =
    draftDao.observeAllCheckpoints().map { list -> list.map { it.toDomain() } }.flowOn(dispatchers.io)

  override suspend fun getAllCheckpoints(): List<NoteRecoveryDraft> =
    withContext(dispatchers.io) { draftDao.getAllCheckpoints().map { it.toDomain() } }

  override suspend fun getCheckpointById(draftId: String): NoteRecoveryDraft? =
    withContext(dispatchers.io) {
      val cleanId = draftId.trim()
      if (cleanId.isEmpty()) return@withContext null
      draftDao.getByDraftId(cleanId)?.toDomain()
    }

  override suspend fun getCheckpointBySessionId(sessionId: String): NoteRecoveryDraft? =
    withContext(dispatchers.io) {
      val cleanSessionId = sessionId.trim()
      if (cleanSessionId.isEmpty()) return@withContext null
      draftDao.getBySessionId(cleanSessionId)?.toDomain()
    }

  override suspend fun getCheckpointsForNote(noteId: Long): List<NoteRecoveryDraft> =
    withContext(dispatchers.io) { draftDao.getByTargetNoteId(noteId).map { it.toDomain() } }

  override suspend fun saveCheckpoint(input: NoteRecoveryDraftInput): SaveCheckpointResult =
    withContext(dispatchers.io) {
      val (cleanDraftId, cleanSessionId) = validateCheckpointInput(input)

      database.withTransaction {
        if (input.targetNoteId != null) {
          validateExistingTargetNoteReferenceInTransaction(input)
        }

        val existingByDraftId = draftDao.getByDraftId(cleanDraftId)
        if (existingByDraftId != null) {
          if (existingByDraftId.sessionId != cleanSessionId) {
            throw InvalidRecoveryDraftException(
              "O checkpoint '$cleanDraftId' pertence a outra sessão e não pode ser substituído."
            )
          }
          if (existingByDraftId.targetNoteId != input.targetNoteId) {
            throw InvalidRecoveryDraftException(
              "Não é permitido alterar o targetNoteId de um checkpoint existente."
            )
          }
        }

        val existingBySessionId = draftDao.getBySessionId(cleanSessionId)
        if (existingBySessionId != null && existingBySessionId.draftId != cleanDraftId) {
          throw InvalidRecoveryDraftException(
            "A sessão '$cleanSessionId' já pertence a outro checkpoint ('${existingBySessionId.draftId}')."
          )
        }

        if (
          existingByDraftId != null &&
            input.checkpointRevision <= existingByDraftId.checkpointRevision
        ) {
          return@withTransaction SaveCheckpointResult.IgnoredStaleRevision(
            draftId = cleanDraftId,
            persistedCheckpointRevision = existingByDraftId.checkpointRevision,
            attemptedCheckpointRevision = input.checkpointRevision,
          )
        }

        val contentJson = encodeNormalizedContent(input)
        val now = clock.nowMillis()
        val (resolvedInitialFolderId, resolvedInitialPaperStyle) =
          resolveInitialMetadataInTransaction(input, existingByDraftId)

        val entity =
          buildCheckpointEntity(
            input = input,
            cleanDraftId = cleanDraftId,
            cleanSessionId = cleanSessionId,
            resolvedInitialFolderId = resolvedInitialFolderId,
            resolvedInitialPaperStyle = resolvedInitialPaperStyle,
            contentJson = contentJson,
            checkpointAt = now,
          )

        if (existingByDraftId == null) {
          draftDao.insert(entity)
        } else {
          val updatedRows = draftDao.update(entity)
          if (updatedRows != 1) {
            throw InvalidRecoveryDraftException(
              "Falha ao atualizar o checkpoint existente '$cleanDraftId'."
            )
          }
        }
        SaveCheckpointResult.Saved(entity.toDomain())
      }
    }

  override suspend fun replaceCheckpointWithNewNote(
    oldDraftId: String,
    expectedOldSessionId: String,
    expectedOldTargetNoteId: Long,
    newInput: NoteRecoveryDraftInput,
  ): ReplaceCheckpointWithNewNoteResult =
    withContext(dispatchers.io) {
      database.withTransaction {
        // 1. Validar os parâmetros esperados e a consistência da nova identidade.
        val (cleanOldDraftId, cleanOldSessionId) =
          validateOldDraftExpectation(
            oldDraftId = oldDraftId,
            expectedOldSessionId = expectedOldSessionId,
            expectedOldTargetNoteId = expectedOldTargetNoteId,
          )

        val (cleanNewDraftId, cleanNewSessionId) = validateCheckpointInput(newInput)

        if (newInput.targetNoteId != null) {
          throw InvalidRecoveryDraftException(
            "Substituição para nova nota exige targetNoteId nulo, recebido: ${newInput.targetNoteId}."
          )
        }

        if (cleanOldDraftId == cleanNewDraftId) {
          throw InvalidRecoveryDraftException(
            "O novo rascunho '$cleanNewDraftId' deve possuir identificador distinto do rascunho substituído."
          )
        }

        if (cleanOldSessionId == cleanNewSessionId) {
          throw InvalidRecoveryDraftException(
            "A conversão para nova nota exige um novo identificador de sessão distinto de '$cleanOldSessionId'."
          )
        }

        val expectedContentJson = encodeNormalizedContent(newInput)

        // 2. Consultar oldDraftId e validar correspondência exata antes de qualquer escrita.
        val oldDraft = draftDao.getByDraftId(cleanOldDraftId)
        if (oldDraft != null) {
          if (
            oldDraft.sessionId != cleanOldSessionId ||
              oldDraft.targetNoteId != expectedOldTargetNoteId
          ) {
            throw InvalidRecoveryDraftException(
              "O checkpoint '$cleanOldDraftId' pertence a sessão ou nota divergente da esperada " +
                "(esperado sessionId='$cleanOldSessionId', targetNoteId=$expectedOldTargetNoteId; " +
                "persistido sessionId='${oldDraft.sessionId}', targetNoteId=${oldDraft.targetNoteId})."
            )
          }
        }

        val existingNewByDraftId = draftDao.getByDraftId(cleanNewDraftId)
        val existingNewBySessionId = draftDao.getBySessionId(cleanNewSessionId)

        // Caso C: colisão com outra sessão / identidade pertencente a outro rascunho.
        if (existingNewBySessionId != null && existingNewBySessionId.draftId != cleanNewDraftId) {
          throw InvalidRecoveryDraftException(
            "A sessão '$cleanNewSessionId' já pertence a outro checkpoint ('${existingNewBySessionId.draftId}')."
          )
        }

        if (existingNewByDraftId != null) {
          if (existingNewByDraftId.sessionId != cleanNewSessionId) {
            throw InvalidRecoveryDraftException(
              "O checkpoint '$cleanNewDraftId' pertence a outra sessão e não pode ser substituído."
            )
          }
          if (existingNewByDraftId.targetNoteId != null) {
            throw InvalidRecoveryDraftException(
              "O checkpoint '$cleanNewDraftId' pertence a uma nota existente e não pode ser reutilizado como nova nota."
            )
          }

          val matches =
            matchesExpectedNewNoteDraft(
              existing = existingNewByDraftId,
              input = newInput,
              cleanNewDraftId = cleanNewDraftId,
              cleanNewSessionId = cleanNewSessionId,
              expectedContentJson = expectedContentJson,
            )

          // Caso D: mesma identidade, mas dados diferentes -> rejeita preservando ambos.
          if (!matches) {
            throw InvalidRecoveryDraftException(
              "O checkpoint '$cleanNewDraftId' já existe com dados divergentes; substituição rejeitada."
            )
          }

          // Caso E: rascunho antigo e novo checkpoint coexistem antes da chamada -> inconsistência.
          if (oldDraft != null) {
            throw InvalidRecoveryDraftException(
              "Inconsistência detectada: rascunho anterior '$cleanOldDraftId' e novo checkpoint '$cleanNewDraftId' coexistem."
            )
          }

          // Caso B: repetição idempotente da mesma operação já concluída.
          return@withTransaction ReplaceCheckpointWithNewNoteResult.AlreadyReplaced(
            existingNewByDraftId.toDomain()
          )
        }

        // Caso A: primeira execução -> persiste novo checkpoint e exclui exclusivamente oldDraftId.
        val now = clock.nowMillis()
        val (resolvedInitialFolderId, resolvedInitialPaperStyle) =
          resolveInitialMetadataInTransaction(newInput, existingByDraftId = null)

        val newEntity =
          buildCheckpointEntity(
            input = newInput,
            cleanDraftId = cleanNewDraftId,
            cleanSessionId = cleanNewSessionId,
            resolvedInitialFolderId = resolvedInitialFolderId,
            resolvedInitialPaperStyle = resolvedInitialPaperStyle,
            contentJson = expectedContentJson,
            checkpointAt = now,
          )

        draftDao.insert(newEntity)

        if (oldDraft != null) {
          val deletedRows = draftDao.deleteByDraftId(cleanOldDraftId)
          if (deletedRows != 1) {
            throw InvalidRecoveryDraftException(
              "Falha ao excluir o rascunho anterior '$cleanOldDraftId' durante a substituição."
            )
          }
        }

        ReplaceCheckpointWithNewNoteResult.Replaced(newEntity.toDomain())
      }
    }

  override suspend fun deleteCheckpoint(draftId: String) {
    withContext(dispatchers.io) {
      val cleanId = draftId.trim()
      if (cleanId.isNotEmpty()) {
        draftDao.deleteByDraftId(cleanId)
      }
    }
  }

  override suspend fun deleteCheckpointsForNote(noteId: Long) {
    withContext(dispatchers.io) {
      draftDao.deleteByTargetNoteId(noteId)
    }
  }

  private fun validateOldDraftExpectation(
    oldDraftId: String,
    expectedOldSessionId: String,
    expectedOldTargetNoteId: Long,
  ): Pair<String, String> {
    if (expectedOldTargetNoteId <= 0L) {
      throw InvalidRecoveryDraftException(
        "Identificador esperado da nota alvo anterior inválido: $expectedOldTargetNoteId."
      )
    }
    return try {
      NoteRecoveryDraftKeys.assertConsistentKeys(
        draftId = oldDraftId,
        sessionId = expectedOldSessionId,
        targetNoteId = expectedOldTargetNoteId,
      )
    } catch (e: IllegalArgumentException) {
      throw InvalidRecoveryDraftException(
        e.message ?: "Identidade esperada do rascunho anterior inválida."
      )
    }
  }

  private fun validateCheckpointInput(input: NoteRecoveryDraftInput): Pair<String, String> {
    val cleanKeys =
      try {
        NoteRecoveryDraftKeys.assertConsistentKeys(
          draftId = input.draftId,
          sessionId = input.sessionId,
          targetNoteId = input.targetNoteId,
        )
      } catch (e: IllegalArgumentException) {
        throw InvalidRecoveryDraftException(e.message ?: "Identificador de rascunho inválido.")
      }

    if (input.checkpointRevision <= 0L) {
      throw InvalidRecoveryDraftException("A revisão do checkpoint deve ser positiva.")
    }

    if (input.fallbackSourceFolderId != null && input.fallbackSourceFolderId <= 0L) {
      throw InvalidRecoveryDraftException(
        "Identificador fallbackSourceFolderId inválido: ${input.fallbackSourceFolderId}."
      )
    }

    if (input.folderId != null && input.fallbackSourceFolderId != null) {
      throw InvalidRecoveryDraftException(
        "folderId e fallbackSourceFolderId são mutuamente exclusivos e não podem coexistir no checkpoint."
      )
    }

    if (input.targetNoteId == null) {
      if (input.baseRevision != 0L) {
        throw InvalidRecoveryDraftException(
          "Rascunho de nova nota deve possuir baseRevision igual a 0."
        )
      }
      if (input.baseNoteUpdatedAt != null) {
        throw InvalidRecoveryDraftException(
          "Rascunho de nova nota não deve possuir baseNoteUpdatedAt."
        )
      }
    } else {
      if (input.baseRevision <= 0L) {
        throw InvalidRecoveryDraftException(
          "Rascunho de nota existente exige baseRevision positiva."
        )
      }
      val baseUpdatedAt = input.baseNoteUpdatedAt
      if (baseUpdatedAt == null || baseUpdatedAt <= 0L) {
        throw InvalidRecoveryDraftException(
          "Rascunho de nota existente exige baseNoteUpdatedAt positivo."
        )
      }
    }

    return cleanKeys
  }

  private suspend fun validateExistingTargetNoteReferenceInTransaction(input: NoteRecoveryDraftInput) {
    val targetNoteId = input.targetNoteId ?: return
    val targetNote =
      noteDao.getById(targetNoteId) ?: throw NoteNotFoundException(targetNoteId)
    if (targetNote.isDeleted) {
      throw NoteDeletedException(targetNoteId)
    }
    val noteContentState = NoteContentCodec.decode(targetNote.contentJson)
    if (noteContentState is NoteContentState.RequiresHandling) {
      throw UnreadableNoteContentException(targetNoteId, noteContentState)
    }
    if (input.baseRevision > targetNote.revision) {
      throw InvalidRecoveryDraftException(
        "baseRevision (${input.baseRevision}) excede a revisão atual da nota (${targetNote.revision})."
      )
    }
    val baseUpdatedAt = input.baseNoteUpdatedAt!!
    if (baseUpdatedAt > targetNote.updatedAt) {
      throw InvalidRecoveryDraftException(
        "baseNoteUpdatedAt ($baseUpdatedAt) excede updatedAt atual da nota (${targetNote.updatedAt})."
      )
    }
    if (input.baseRevision == targetNote.revision && baseUpdatedAt != targetNote.updatedAt) {
      throw InvalidRecoveryDraftException(
        "baseNoteUpdatedAt ($baseUpdatedAt) diverge de updatedAt atual da nota (${targetNote.updatedAt})."
      )
    }
  }

  private fun encodeNormalizedContent(input: NoteRecoveryDraftInput): String {
    val normalizedContent = NoteContentPolicy.normalizeDocument(input.content)
    return NoteContentCodec.encode(normalizedContent)
  }

  private fun resolveInitialMetadataInTransaction(
    input: NoteRecoveryDraftInput,
    existingByDraftId: NoteRecoveryDraftEntity?,
  ): Pair<Long?, String?> {
    if (input.targetNoteId != null) {
      return null to null
    }
    if (existingByDraftId == null) {
      return input.initialFolderId to input.initialPaperStyle?.name
    }
    if (input.initialFolderId != existingByDraftId.initialFolderId) {
      throw InvalidRecoveryDraftException(
        "Não é permitido alterar initialFolderId de um checkpoint existente."
      )
    }
    val existingRawPaper = existingByDraftId.initialPaperStyle
    val existingKnownPaper = enumValueOrNull<NotePaperStyle>(existingRawPaper)
    val resolvedPaper =
      if (existingRawPaper == null) {
        if (input.initialPaperStyle != null) {
          throw InvalidRecoveryDraftException(
            "Não é permitido alterar initialPaperStyle de um checkpoint legado existente."
          )
        }
        null
      } else if (existingKnownPaper != null) {
        if (input.initialPaperStyle != existingKnownPaper) {
          throw InvalidRecoveryDraftException(
            "Não é permitido alterar initialPaperStyle de um checkpoint existente."
          )
        }
        existingRawPaper
      } else {
        if (input.initialPaperStyle != null) {
          throw InvalidRecoveryDraftException(
            "Não é permitido alterar initialPaperStyle de um checkpoint existente."
          )
        }
        existingRawPaper
      }
    return existingByDraftId.initialFolderId to resolvedPaper
  }

  private fun buildCheckpointEntity(
    input: NoteRecoveryDraftInput,
    cleanDraftId: String,
    cleanSessionId: String,
    resolvedInitialFolderId: Long?,
    resolvedInitialPaperStyle: String?,
    contentJson: String,
    checkpointAt: Long,
  ): NoteRecoveryDraftEntity =
    NoteRecoveryDraftEntity(
      draftId = cleanDraftId,
      targetNoteId = input.targetNoteId,
      sessionId = cleanSessionId,
      folderId = input.folderId,
      initialFolderId = resolvedInitialFolderId,
      fallbackSourceFolderId = input.fallbackSourceFolderId,
      isPinned = input.isPinned,
      title = input.title,
      contentJson = contentJson,
      paperStyle = input.paperStyle.name,
      initialPaperStyle = resolvedInitialPaperStyle,
      baseRevision = input.baseRevision,
      checkpointRevision = input.checkpointRevision,
      baseNoteUpdatedAt = input.baseNoteUpdatedAt,
      checkpointAt = checkpointAt,
    )

  private fun matchesExpectedNewNoteDraft(
    existing: NoteRecoveryDraftEntity,
    input: NoteRecoveryDraftInput,
    cleanNewDraftId: String,
    cleanNewSessionId: String,
    expectedContentJson: String,
  ): Boolean =
    existing.draftId == cleanNewDraftId &&
      existing.sessionId == cleanNewSessionId &&
      existing.targetNoteId == null &&
      existing.folderId == input.folderId &&
      existing.initialFolderId == input.initialFolderId &&
      existing.fallbackSourceFolderId == input.fallbackSourceFolderId &&
      existing.isPinned == input.isPinned &&
      existing.title == input.title &&
      existing.contentJson == expectedContentJson &&
      existing.paperStyle == input.paperStyle.name &&
      existing.initialPaperStyle == input.initialPaperStyle?.name &&
      existing.baseRevision == 0L &&
      existing.baseNoteUpdatedAt == null &&
      existing.checkpointRevision == input.checkpointRevision
}
