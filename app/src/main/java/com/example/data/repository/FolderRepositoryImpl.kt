package com.example.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.example.core.coroutines.DispatcherProvider
import com.example.core.time.ClockProvider
import com.example.data.local.dao.FolderDao
import com.example.data.local.dao.NoteDao
import com.example.data.local.database.AnoteVoceDatabase
import com.example.data.local.entity.FolderEntity
import com.example.data.local.mapper.toDomain
import com.example.domain.model.Folder
import com.example.domain.policy.FolderNameNormalizer
import com.example.domain.repository.DuplicateFolderNameException
import com.example.domain.repository.FolderDeleteMode
import com.example.domain.repository.FolderDeletedException
import com.example.domain.repository.FolderNotDeletedException
import com.example.domain.repository.FolderNotFoundException
import com.example.domain.repository.FolderRepository
import com.example.domain.repository.InvalidFolderNameException
import com.example.domain.repository.RestoreFolderResult
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class FolderRepositoryImpl(
  private val database: AnoteVoceDatabase,
  private val folderDao: FolderDao,
  private val noteDao: NoteDao,
  private val dispatchers: DispatcherProvider,
  private val clock: ClockProvider,
) : FolderRepository {

  override fun observeActiveFolders(): Flow<List<Folder>> =
    folderDao
      .observeActiveFolders()
      .map { list -> list.map { it.toDomain() } }
      .flowOn(dispatchers.io)

  override fun observeDeletedFolders(): Flow<List<Folder>> =
    folderDao
      .observeDeletedFolders()
      .map { list -> list.map { it.toDomain() } }
      .flowOn(dispatchers.io)

  override suspend fun getFolderById(folderId: Long): Folder? =
    withContext(dispatchers.io) { folderDao.getById(folderId)?.toDomain() }

  override suspend fun createFolder(name: String): Folder =
    withContext(dispatchers.io) {
      val displayName = FolderNameNormalizer.sanitizeDisplayName(name)
      val normalizedKey = FolderNameNormalizer.normalizeKey(displayName)
      if (normalizedKey.isEmpty()) throw InvalidFolderNameException()

      database.withTransaction {
        val existing = folderDao.findActiveByNormalizedKey(normalizedKey)
        if (existing != null) throw DuplicateFolderNameException(displayName)

        val now = clock.nowMillis()
        val entity =
          FolderEntity(
            uid = UUID.randomUUID().toString(),
            name = displayName,
            normalizedName = normalizedKey,
            activeNameKey = normalizedKey,
            isDeleted = false,
            deletedAt = null,
            createdAt = now,
            updatedAt = now,
          )

        val insertedId =
          try {
            folderDao.insert(entity)
          } catch (_: SQLiteConstraintException) {
            throw DuplicateFolderNameException(displayName)
          }

        entity.copy(id = insertedId).toDomain()
      }
    }

  override suspend fun renameFolder(folderId: Long, newName: String): Folder =
    withContext(dispatchers.io) {
      val displayName = FolderNameNormalizer.sanitizeDisplayName(newName)
      val normalizedKey = FolderNameNormalizer.normalizeKey(displayName)
      if (normalizedKey.isEmpty()) throw InvalidFolderNameException()

      database.withTransaction {
        val current = folderDao.getById(folderId) ?: throw FolderNotFoundException(folderId)
        if (current.isDeleted) throw FolderDeletedException(folderId)

        val conflict = folderDao.findActiveByNormalizedKey(normalizedKey)
        if (conflict != null && conflict.id != folderId) {
          throw DuplicateFolderNameException(displayName)
        }

        val now = clock.nowMillis()
        try {
          folderDao.updateName(
            folderId = folderId,
            name = displayName,
            normalizedName = normalizedKey,
            activeNameKey = normalizedKey,
            updatedAt = now,
          )
        } catch (_: SQLiteConstraintException) {
          throw DuplicateFolderNameException(displayName)
        }

        current
          .copy(
            name = displayName,
            normalizedName = normalizedKey,
            activeNameKey = normalizedKey,
            updatedAt = now,
          )
          .toDomain()
      }
    }

  override suspend fun deleteFolder(folderId: Long, mode: FolderDeleteMode) {
    withContext(dispatchers.io) {
      database.withTransaction {
        val folder = folderDao.getById(folderId) ?: throw FolderNotFoundException(folderId)
        if (folder.isDeleted) return@withTransaction

        val now = clock.nowMillis()
        when (mode) {
          FolderDeleteMode.FOLDER_ONLY -> {
            // Somente notas ativas ficam com folder_id = null; notas já apagadas preservam vínculo.
            noteDao.detachActiveNotesFromFolder(folderId = folderId, updatedAt = now)
            folderDao.softDeleteFolder(folderId = folderId, deletedAt = now, updatedAt = now)
          }
          FolderDeleteMode.FOLDER_AND_CONTENT -> {
            // Pasta e notas ativas vão para Apagados mantendo folder_id, is_pinned e pinned_at.
            // Notas já anteriormente em Apagados preservam seus metadados originais (deleted_with_folder = 0).
            folderDao.softDeleteFolder(folderId = folderId, deletedAt = now, updatedAt = now)
            noteDao.softDeleteActiveNotesInFolder(
              folderId = folderId,
              deletedAt = now,
              updatedAt = now,
            )
          }
        }
      }
    }
  }

  override suspend fun restoreFolder(folderId: Long): RestoreFolderResult =
    withContext(dispatchers.io) {
      database.withTransaction {
        val deletedFolder = folderDao.getById(folderId) ?: throw FolderNotFoundException(folderId)
        if (!deletedFolder.isDeleted) {
          return@withTransaction RestoreFolderResult.RestoredOriginal(deletedFolder.id)
        }

        val now = clock.nowMillis()
        val existingActive = folderDao.findActiveByNormalizedKey(deletedFolder.normalizedName)
        if (existingActive != null) {
          // Incorpora as notas apagadas com a pasta na pasta ativa existente, sem sobrescrever dados.
          noteDao.restoreNotesFromDeletedFolderIntoTarget(
            sourceFolderId = deletedFolder.id,
            targetFolderId = existingActive.id,
            updatedAt = now,
          )
          // Preserva o vínculo das notas que ainda permanecem em Apagados antes de remover a pasta duplicada.
          noteDao.repointDeletedNotesFolder(
            sourceFolderId = deletedFolder.id,
            targetFolderId = existingActive.id,
            updatedAt = now,
          )
          folderDao.deleteById(deletedFolder.id)
          RestoreFolderResult.MergedIntoActive(
            targetFolderId = existingActive.id,
            removedFolderId = deletedFolder.id,
          )
        } else {
          folderDao.restoreFolder(
            folderId = deletedFolder.id,
            activeNameKey = deletedFolder.normalizedName,
            updatedAt = now,
          )
          noteDao.restoreNotesWithFolder(folderId = deletedFolder.id, updatedAt = now)
          RestoreFolderResult.RestoredOriginal(deletedFolder.id)
        }
      }
    }

  override suspend fun permanentlyDeleteFolder(folderId: Long, deleteLinkedNotes: Boolean) {
    withContext(dispatchers.io) {
      database.withTransaction {
        val folder = folderDao.getById(folderId) ?: throw FolderNotFoundException(folderId)
        if (!folder.isDeleted) throw FolderNotDeletedException(folderId)

        val now = clock.nowMillis()
        if (deleteLinkedNotes) {
          // Elimina somente as notas enviadas para Apagados junto com esta pasta;
          // notas apagadas individualmente antes (deleted_with_folder = 0) são preservadas
          // e têm folder_id desvinculado explicitamente com incremento único de revisão antes do DELETE.
          noteDao.deleteNotesDeletedWithFolder(folderId)
        }
        // Quando deleteLinkedNotes = false (ou para notas individuais restantes no modo true),
        // uma única instrução remove folder_id, limpa deleted_with_folder e incrementa revision uma só vez.
        noteDao.detachDeletedNotesFromFolder(folderId = folderId, updatedAt = now)
        folderDao.deleteById(folderId)
      }
    }
  }
}
