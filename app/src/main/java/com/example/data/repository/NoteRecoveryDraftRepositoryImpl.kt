package com.example.data.repository

import androidx.room.withTransaction
import com.example.core.coroutines.DispatcherProvider
import com.example.core.time.ClockProvider
import com.example.data.local.dao.NoteDao
import com.example.data.local.dao.NoteRecoveryDraftDao
import com.example.data.local.database.AnoteVoceDatabase
import com.example.data.local.entity.NoteRecoveryDraftEntity
import com.example.data.local.mapper.NoteContentCodec
import com.example.data.local.mapper.toDomain
import com.example.domain.model.NoteContentState
import com.example.domain.model.NoteRecoveryDraft
import com.example.domain.model.NoteRecoveryDraftInput
import com.example.domain.model.NoteRecoveryDraftKeys
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

  override suspend fun getCheckpointsForNote(noteId: Long): List<NoteRecoveryDraft> =
    withContext(dispatchers.io) { draftDao.getByTargetNoteId(noteId).map { it.toDomain() } }

  override suspend fun saveCheckpoint(input: NoteRecoveryDraftInput): SaveCheckpointResult =
    withContext(dispatchers.io) {
      val (cleanDraftId, cleanSessionId) =
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

      database.withTransaction {
        if (input.targetNoteId != null) {
          val targetNote =
            noteDao.getById(input.targetNoteId) ?: throw NoteNotFoundException(input.targetNoteId)
          if (targetNote.isDeleted) {
            throw NoteDeletedException(input.targetNoteId)
          }
          val noteContentState = NoteContentCodec.decode(targetNote.contentJson)
          if (noteContentState is NoteContentState.RequiresHandling) {
            throw UnreadableNoteContentException(input.targetNoteId, noteContentState)
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

        val normalizedContent = NoteContentPolicy.normalizeDocument(input.content)
        val contentJson = NoteContentCodec.encode(normalizedContent)
        val now = clock.nowMillis()

        val resolvedInitialFolderId =
          if (input.targetNoteId == null) input.initialFolderId else null
        val resolvedInitialPaperStyle =
          if (input.targetNoteId == null) input.initialPaperStyle?.name else null

        val entity =
          NoteRecoveryDraftEntity(
            draftId = cleanDraftId,
            targetNoteId = input.targetNoteId,
            sessionId = cleanSessionId,
            folderId = input.folderId,
            initialFolderId = resolvedInitialFolderId,
            isPinned = input.isPinned,
            title = input.title,
            contentJson = contentJson,
            paperStyle = input.paperStyle.name,
            initialPaperStyle = resolvedInitialPaperStyle,
            baseRevision = input.baseRevision,
            checkpointRevision = input.checkpointRevision,
            baseNoteUpdatedAt = input.baseNoteUpdatedAt,
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
}
