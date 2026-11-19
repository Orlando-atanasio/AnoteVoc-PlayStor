package com.example.data.repository

import androidx.room.withTransaction
import com.example.core.coroutines.DispatcherProvider
import com.example.core.time.ClockProvider
import com.example.data.local.dao.AppPreferencesDao
import com.example.data.local.dao.FolderDao
import com.example.data.local.dao.NoteDao
import com.example.data.local.dao.NoteRecoveryDraftDao
import com.example.data.local.database.AnoteVoceDatabase
import com.example.data.local.entity.NoteEntity
import com.example.data.local.mapper.NoteContentCodec
import com.example.data.local.mapper.enumValueOrDefault
import com.example.data.local.mapper.toDomain
import com.example.domain.model.Note
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NoteContentState
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.NoteRecoveryDraftKeys
import com.example.domain.policy.NoteContentPolicy
import com.example.domain.repository.FolderDeletedException
import com.example.domain.repository.FolderNotFoundException
import com.example.domain.repository.InvalidRecoveryDraftException
import com.example.domain.repository.NoteConflictException
import com.example.domain.repository.NoteDeletedException
import com.example.domain.repository.NoteNotDeletedException
import com.example.domain.repository.NoteNotFoundException
import com.example.domain.repository.NoteRepository
import com.example.domain.repository.PinNoteResult
import com.example.domain.repository.UnreadableNoteContentException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class NoteRepositoryImpl(
  private val database: AnoteVoceDatabase,
  private val noteDao: NoteDao,
  private val folderDao: FolderDao,
  private val dispatchers: DispatcherProvider,
  private val clock: ClockProvider,
  private val appPreferencesDao: AppPreferencesDao = database.appPreferencesDao(),
  private val noteRecoveryDraftDao: NoteRecoveryDraftDao = database.noteRecoveryDraftDao(),
) : NoteRepository {

  override fun observeAllActiveNotes(): Flow<List<Note>> =
    noteDao.observeAllActiveNotes().map { list -> list.map { it.toDomain() } }.flowOn(dispatchers.io)

  override fun observePinnedActiveNotes(): Flow<List<Note>> =
    noteDao
      .observePinnedActiveNotes()
      .map { list -> list.map { it.toDomain() } }
      .flowOn(dispatchers.io)

  override fun observeActiveNotesByFolder(folderId: Long): Flow<List<Note>> =
    noteDao
      .observeActiveNotesByFolder(folderId)
      .map { list -> list.map { it.toDomain() } }
      .flowOn(dispatchers.io)

  override fun observeDeletedNotes(): Flow<List<Note>> =
    noteDao.observeDeletedNotes().map { list -> list.map { it.toDomain() } }.flowOn(dispatchers.io)

  override fun observeNoteById(noteId: Long): Flow<Note?> =
    noteDao.observeById(noteId).map { entity -> entity?.toDomain() }.flowOn(dispatchers.io)

  override suspend fun getNoteById(noteId: Long): Note? =
    withContext(dispatchers.io) { noteDao.getById(noteId)?.toDomain() }

  override suspend fun createNote(
    title: String,
    content: NoteContentDocument,
    folderId: Long?,
    isPinned: Boolean,
    paperStyle: NotePaperStyle?,
    clearDraftId: String?,
  ): Note =
    withContext(dispatchers.io) {
      database.withTransaction {
        validateActiveTargetFolder(folderId)
        val validatedDraftId = validateDraftForConsolidation(clearDraftId, expectedTargetNoteId = null)

        val resolvedPaper = paperStyle ?: resolveCurrentGlobalPaper()
        val now = clock.nowMillis()
        val normalizedContent = NoteContentPolicy.normalizeDocument(content)
        val contentJson = NoteContentCodec.encode(normalizedContent)
        val preview = NoteContentPolicy.extractPlainTextPreview(normalizedContent)

        val entity =
          NoteEntity(
            uid = UUID.randomUUID().toString(),
            folderId = folderId,
            title = title,
            contentJson = contentJson,
            plainTextPreview = preview,
            paperStyle = resolvedPaper.name,
            revision = 1L,
            isPinned = isPinned,
            pinnedAt = if (isPinned) now else null,
            isDeleted = false,
            deletedAt = null,
            deletedWithFolder = false,
            createdAt = now,
            updatedAt = now,
          )

        val insertedId = noteDao.insert(entity)
        if (validatedDraftId != null) {
          val deletedRows = noteRecoveryDraftDao.deleteByDraftId(validatedDraftId)
          if (deletedRows != 1) {
            throw InvalidRecoveryDraftException(
              "Falha ao excluir o rascunho '$validatedDraftId' durante a criação da nota."
            )
          }
        }
        entity.copy(id = insertedId).toDomain()
      }
    }

  override suspend fun updateNote(
    noteId: Long,
    title: String,
    content: NoteContentDocument,
    folderId: Long?,
    paperStyle: NotePaperStyle?,
    expectedBaseRevision: Long?,
    clearDraftId: String?,
  ): Note =
    withContext(dispatchers.io) {
      database.withTransaction {
        val current = noteDao.getById(noteId) ?: throw NoteNotFoundException(noteId)
        if (current.isDeleted) throw NoteDeletedException(noteId)

        val currentContentState = NoteContentCodec.decode(current.contentJson)
        if (currentContentState is NoteContentState.RequiresHandling) {
          throw UnreadableNoteContentException(noteId, currentContentState)
        }

        if (expectedBaseRevision != null && current.revision != expectedBaseRevision) {
          throw NoteConflictException(
            noteId = noteId,
            expectedRevision = expectedBaseRevision,
            actualRevision = current.revision,
          )
        }

        validateActiveTargetFolder(folderId)
        val validatedDraftId = validateDraftForConsolidation(clearDraftId, expectedTargetNoteId = noteId)

        val now = clock.nowMillis()
        val normalizedContent = NoteContentPolicy.normalizeDocument(content)
        val contentJson = NoteContentCodec.encode(normalizedContent)
        val preview = NoteContentPolicy.extractPlainTextPreview(normalizedContent)
        val resolvedPaperName =
          paperStyle?.name
            ?: enumValueOrDefault(current.paperStyle, NotePaperStyle.LINHAS).name
        val nextRevision = current.revision + 1L

        noteDao.updateNote(
          noteId = noteId,
          title = title,
          contentJson = contentJson,
          plainTextPreview = preview,
          folderId = folderId,
          paperStyle = resolvedPaperName,
          revision = nextRevision,
          updatedAt = now,
        )

        if (validatedDraftId != null) {
          val deletedRows = noteRecoveryDraftDao.deleteByDraftId(validatedDraftId)
          if (deletedRows != 1) {
            throw InvalidRecoveryDraftException(
              "Falha ao excluir o rascunho '$validatedDraftId' durante a atualização da nota $noteId."
            )
          }
        }

        current
          .copy(
            title = title,
            contentJson = contentJson,
            plainTextPreview = preview,
            folderId = folderId,
            paperStyle = resolvedPaperName,
            revision = nextRevision,
            updatedAt = now,
          )
          .toDomain()
      }
    }

  override suspend fun updateNotePaperStyle(noteId: Long, paperStyle: NotePaperStyle): Note =
    withContext(dispatchers.io) {
      database.withTransaction {
        val current = noteDao.getById(noteId) ?: throw NoteNotFoundException(noteId)
        if (current.isDeleted) throw NoteDeletedException(noteId)

        val currentContentState = NoteContentCodec.decode(current.contentJson)
        if (currentContentState is NoteContentState.RequiresHandling) {
          throw UnreadableNoteContentException(noteId, currentContentState)
        }

        val now = clock.nowMillis()
        val nextRevision = current.revision + 1L

        noteDao.updatePaperStyle(
          noteId = noteId,
          paperStyle = paperStyle.name,
          revision = nextRevision,
          updatedAt = now,
        )

        current
          .copy(
            paperStyle = paperStyle.name,
            revision = nextRevision,
            updatedAt = now,
          )
          .toDomain()
      }
    }

  override suspend fun setNotePinned(noteId: Long, pinned: Boolean) {
    withContext(dispatchers.io) {
      database.withTransaction {
        val current = noteDao.getById(noteId) ?: throw NoteNotFoundException(noteId)
        if (current.isDeleted) throw NoteDeletedException(noteId)

        val now = clock.nowMillis()
        noteDao.updatePinned(
          noteId = noteId,
          isPinned = pinned,
          pinnedAt = if (pinned) now else null,
          updatedAt = now,
        )
      }
    }
  }

  override suspend fun setNotePinnedIfRevision(
    noteId: Long,
    pinned: Boolean,
    expectedBaseRevision: Long,
  ): PinNoteResult =
    withContext(dispatchers.io) {
      database.withTransaction {
        val current = noteDao.getById(noteId) ?: throw NoteNotFoundException(noteId)
        if (current.isDeleted) throw NoteDeletedException(noteId)
        if (current.revision != expectedBaseRevision) {
          return@withTransaction PinNoteResult.Conflict(
            expectedRevision = expectedBaseRevision,
            latestNote = current.toDomain(),
          )
        }
        if (current.isPinned == pinned) {
          return@withTransaction PinNoteResult.Unchanged(current.toDomain())
        }

        val now = clock.nowMillis()
        val pinnedAt = if (pinned) now else null
        val affected =
          noteDao.updatePinnedIfRevision(
            noteId = noteId,
            isPinned = pinned,
            pinnedAt = pinnedAt,
            updatedAt = now,
            expectedRevision = expectedBaseRevision,
          )
        if (affected != 1) {
          val latest = noteDao.getById(noteId) ?: throw NoteNotFoundException(noteId)
          if (latest.isDeleted) throw NoteDeletedException(noteId)
          return@withTransaction PinNoteResult.Conflict(
            expectedRevision = expectedBaseRevision,
            latestNote = latest.toDomain(),
          )
        }

        val updated = noteDao.getById(noteId)?.toDomain() ?: throw NoteNotFoundException(noteId)
        if (updated.revision != expectedBaseRevision + 1L) {
          return@withTransaction PinNoteResult.Conflict(
            expectedRevision = expectedBaseRevision,
            latestNote = updated,
          )
        }
        PinNoteResult.Updated(updated)
      }
    }

  override suspend fun moveNoteToFolder(noteId: Long, folderId: Long?) {
    withContext(dispatchers.io) {
      database.withTransaction {
        val current = noteDao.getById(noteId) ?: throw NoteNotFoundException(noteId)
        if (current.isDeleted) throw NoteDeletedException(noteId)

        validateActiveTargetFolder(folderId)
        noteDao.updateFolderId(noteId = noteId, folderId = folderId, updatedAt = clock.nowMillis())
      }
    }
  }

  override suspend fun softDeleteNote(noteId: Long) {
    withContext(dispatchers.io) {
      database.withTransaction {
        val note = noteDao.getById(noteId) ?: throw NoteNotFoundException(noteId)
        if (note.isDeleted) return@withTransaction
        val now = clock.nowMillis()
        // Preserva is_pinned, pinned_at, folder_id e eventuais checkpoints em note_recovery_drafts.
        noteDao.softDeleteNote(noteId = noteId, deletedAt = now, updatedAt = now)
      }
    }
  }

  override suspend fun restoreNote(noteId: Long): Note =
    withContext(dispatchers.io) {
      database.withTransaction {
        val note = noteDao.getById(noteId) ?: throw NoteNotFoundException(noteId)
        if (!note.isDeleted) return@withTransaction note.toDomain()

        val parentFolder = note.folderId?.let { folderDao.getById(it) }
        // Se a pasta original estiver em Apagados (ou não existir), restaura sem pasta (folder_id = null)
        // mantendo a pasta original em Apagados intacta.
        val resolvedFolderId =
          if (parentFolder != null && !parentFolder.isDeleted) parentFolder.id else null

        val now = clock.nowMillis()
        noteDao.restoreNote(
          noteId = noteId,
          resolvedFolderId = resolvedFolderId,
          updatedAt = now,
        )

        note
          .copy(
            isDeleted = false,
            deletedAt = null,
            deletedWithFolder = false,
            folderId = resolvedFolderId,
            revision = note.revision + 1L,
            updatedAt = now,
          )
          .toDomain()
      }
    }

  override suspend fun permanentlyDeleteNote(noteId: Long) {
    withContext(dispatchers.io) {
      database.withTransaction {
        val note = noteDao.getById(noteId) ?: throw NoteNotFoundException(noteId)
        if (!note.isDeleted) throw NoteNotDeletedException(noteId)
        noteDao.deleteById(noteId)
      }
    }
  }

  private suspend fun resolveCurrentGlobalPaper(): NotePaperStyle {
    val rawPaper = appPreferencesDao.getPreferences()?.defaultNotePaper
    return if (rawPaper == null) {
      NotePaperStyle.LINHAS
    } else {
      enumValueOrDefault(rawPaper, NotePaperStyle.LINHAS)
    }
  }

  private suspend fun validateActiveTargetFolder(folderId: Long?) {
    if (folderId == null) return
    val folder = folderDao.getById(folderId) ?: throw FolderNotFoundException(folderId)
    if (folder.isDeleted) throw FolderDeletedException(folderId)
  }

  private suspend fun validateDraftForConsolidation(
    clearDraftId: String?,
    expectedTargetNoteId: Long?,
  ): String? {
    if (clearDraftId.isNullOrBlank()) return null

    val cleanDraftId = clearDraftId.trim()
    try {
      NoteRecoveryDraftKeys.validateDraftId(cleanDraftId)
    } catch (e: IllegalArgumentException) {
      throw InvalidRecoveryDraftException(e.message ?: "Identificador de rascunho inválido.")
    }

    val draft =
      noteRecoveryDraftDao.getByDraftId(cleanDraftId)
        ?: throw InvalidRecoveryDraftException(
          "Rascunho '$cleanDraftId' não encontrado para consolidação."
        )

    if (expectedTargetNoteId == null) {
      if (draft.targetNoteId != null) {
        throw InvalidRecoveryDraftException(
          "O rascunho '$cleanDraftId' pertence à nota existente ${draft.targetNoteId} e não pode ser excluído na criação de uma nova nota."
        )
      }
    } else if (draft.targetNoteId != expectedTargetNoteId) {
      throw InvalidRecoveryDraftException(
        "O rascunho '$cleanDraftId' (targetNoteId=${draft.targetNoteId}) não pertence à nota $expectedTargetNoteId."
      )
    }

    val (consistentDraftId, _) =
      try {
        NoteRecoveryDraftKeys.assertConsistentKeys(
          draftId = draft.draftId,
          sessionId = draft.sessionId,
          targetNoteId = expectedTargetNoteId,
        )
      } catch (e: IllegalArgumentException) {
        throw InvalidRecoveryDraftException(
          e.message ?: "Identificadores do rascunho '$cleanDraftId' são incoerentes."
        )
      }

    if (consistentDraftId != cleanDraftId) {
      throw InvalidRecoveryDraftException(
        "Identificador de rascunho '$cleanDraftId' diverge do registro persistido '$consistentDraftId'."
      )
    }

    return cleanDraftId
  }
}
