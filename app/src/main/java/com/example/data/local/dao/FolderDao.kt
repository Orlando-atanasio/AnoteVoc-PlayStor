package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.FolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
  @Query("SELECT * FROM folders WHERE is_deleted = 0 ORDER BY name COLLATE NOCASE ASC, id ASC")
  fun observeActiveFolders(): Flow<List<FolderEntity>>

  @Query("SELECT * FROM folders WHERE is_deleted = 1 ORDER BY deleted_at DESC, id DESC")
  fun observeDeletedFolders(): Flow<List<FolderEntity>>

  @Query("SELECT * FROM folders WHERE id = :folderId LIMIT 1")
  suspend fun getById(folderId: Long): FolderEntity?

  @Query("SELECT * FROM folders WHERE active_name_key = :normalizedKey LIMIT 1")
  suspend fun findActiveByNormalizedKey(normalizedKey: String): FolderEntity?

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(folder: FolderEntity): Long

  @Query(
    """
    UPDATE folders
    SET name = :name,
        normalized_name = :normalizedName,
        active_name_key = :activeNameKey,
        updated_at = :updatedAt
    WHERE id = :folderId
    """
  )
  suspend fun updateName(
    folderId: Long,
    name: String,
    normalizedName: String,
    activeNameKey: String?,
    updatedAt: Long,
  ): Int

  @Query(
    """
    UPDATE folders
    SET is_deleted = 1,
        deleted_at = :deletedAt,
        active_name_key = NULL,
        updated_at = :updatedAt
    WHERE id = :folderId
    """
  )
  suspend fun softDeleteFolder(folderId: Long, deletedAt: Long, updatedAt: Long): Int

  @Query(
    """
    UPDATE folders
    SET is_deleted = 0,
        deleted_at = NULL,
        active_name_key = :activeNameKey,
        updated_at = :updatedAt
    WHERE id = :folderId
    """
  )
  suspend fun restoreFolder(folderId: Long, activeNameKey: String, updatedAt: Long): Int

  @Query("DELETE FROM folders WHERE id = :folderId")
  suspend fun deleteById(folderId: Long): Int
}
