package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
  @Query(
    """
    SELECT * FROM notes
    WHERE is_deleted = 0
    ORDER BY is_pinned DESC, updated_at DESC, id DESC
    """
  )
  fun observeAllActiveNotes(): Flow<List<NoteEntity>>

  @Query(
    """
    SELECT * FROM notes
    WHERE is_deleted = 0 AND is_pinned = 1
    ORDER BY pinned_at DESC, updated_at DESC, id DESC
    """
  )
  fun observePinnedActiveNotes(): Flow<List<NoteEntity>>

  @Query(
    """
    SELECT * FROM notes
    WHERE is_deleted = 0 AND folder_id = :folderId
    ORDER BY is_pinned DESC, updated_at DESC, id DESC
    """
  )
  fun observeActiveNotesByFolder(folderId: Long): Flow<List<NoteEntity>>

  @Query(
    """
    SELECT * FROM notes
    WHERE is_deleted = 1
    ORDER BY deleted_at DESC, id DESC
    """
  )
  fun observeDeletedNotes(): Flow<List<NoteEntity>>

  @Query("SELECT * FROM notes WHERE id = :noteId LIMIT 1")
  fun observeById(noteId: Long): Flow<NoteEntity?>

  @Query("SELECT * FROM notes WHERE id = :noteId LIMIT 1")
  suspend fun getById(noteId: Long): NoteEntity?

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(note: NoteEntity): Long

  @Query(
    """
    UPDATE notes
    SET title = :title,
        content_json = :contentJson,
        plain_text_preview = :plainTextPreview,
        folder_id = :folderId,
        paper_style = :paperStyle,
        revision = :revision,
        updated_at = :updatedAt
    WHERE id = :noteId
    """
  )
  suspend fun updateNote(
    noteId: Long,
    title: String,
    contentJson: String,
    plainTextPreview: String,
    folderId: Long?,
    paperStyle: String,
    revision: Long,
    updatedAt: Long,
  ): Int

  @Query(
    """
    UPDATE notes
    SET paper_style = :paperStyle,
        revision = :revision,
        updated_at = :updatedAt
    WHERE id = :noteId
    """
  )
  suspend fun updatePaperStyle(
    noteId: Long,
    paperStyle: String,
    revision: Long,
    updatedAt: Long,
  ): Int

  @Query(
    """
    UPDATE notes
    SET is_pinned = :isPinned,
        pinned_at = :pinnedAt,
        revision = revision + 1,
        updated_at = :updatedAt
    WHERE id = :noteId
    """
  )
  suspend fun updatePinned(
    noteId: Long,
    isPinned: Boolean,
    pinnedAt: Long?,
    updatedAt: Long,
  ): Int

  @Query(
    """
    UPDATE notes
    SET is_pinned = :isPinned,
        pinned_at = :pinnedAt,
        revision = revision + 1,
        updated_at = :updatedAt
    WHERE id = :noteId AND is_deleted = 0 AND revision = :expectedRevision
    """
  )
  suspend fun updatePinnedIfRevision(
    noteId: Long,
    isPinned: Boolean,
    pinnedAt: Long?,
    updatedAt: Long,
    expectedRevision: Long,
  ): Int

  @Query(
    """
    UPDATE notes
    SET folder_id = :folderId,
        revision = revision + 1,
        updated_at = :updatedAt
    WHERE id = :noteId
    """
  )
  suspend fun updateFolderId(noteId: Long, folderId: Long?, updatedAt: Long): Int

  @Query(
    """
    UPDATE notes
    SET is_deleted = 1,
        deleted_at = :deletedAt,
        deleted_with_folder = 0,
        revision = revision + 1,
        updated_at = :updatedAt
    WHERE id = :noteId
    """
  )
  suspend fun softDeleteNote(noteId: Long, deletedAt: Long, updatedAt: Long): Int

  @Query(
    """
    UPDATE notes
    SET is_deleted = 0,
        deleted_at = NULL,
        deleted_with_folder = 0,
        folder_id = :resolvedFolderId,
        revision = revision + 1,
        updated_at = :updatedAt
    WHERE id = :noteId
    """
  )
  suspend fun restoreNote(
    noteId: Long,
    resolvedFolderId: Long?,
    updatedAt: Long,
  ): Int

  /**
   * "Excluir somente a pasta": apenas notas ativas perdem o vínculo;
   * notas que já estavam em Apagados preservam folder_id original.
   */
  @Query(
    """
    UPDATE notes
    SET folder_id = NULL,
        revision = revision + 1,
        updated_at = :updatedAt
    WHERE folder_id = :folderId AND is_deleted = 0
    """
  )
  suspend fun detachActiveNotesFromFolder(folderId: Long, updatedAt: Long): Int

  /**
   * "Excluir pasta e todo o conteúdo": envia notas ativas da pasta para Apagados
   * preservando folder_id, is_pinned e pinned_at; notas já apagadas permanecem intactas.
   */
  @Query(
    """
    UPDATE notes
    SET is_deleted = 1,
        deleted_at = :deletedAt,
        deleted_with_folder = 1,
        revision = revision + 1,
        updated_at = :updatedAt
    WHERE folder_id = :folderId AND is_deleted = 0
    """
  )
  suspend fun softDeleteActiveNotesInFolder(
    folderId: Long,
    deletedAt: Long,
    updatedAt: Long,
  ): Int

  @Query(
    """
    UPDATE notes
    SET is_deleted = 0,
        deleted_at = NULL,
        deleted_with_folder = 0,
        revision = revision + 1,
        updated_at = :updatedAt
    WHERE folder_id = :folderId AND is_deleted = 1 AND deleted_with_folder = 1
    """
  )
  suspend fun restoreNotesWithFolder(folderId: Long, updatedAt: Long): Int

  @Query(
    """
    UPDATE notes
    SET folder_id = :targetFolderId,
        is_deleted = 0,
        deleted_at = NULL,
        deleted_with_folder = 0,
        revision = revision + 1,
        updated_at = :updatedAt
    WHERE folder_id = :sourceFolderId AND is_deleted = 1 AND deleted_with_folder = 1
    """
  )
  suspend fun restoreNotesFromDeletedFolderIntoTarget(
    sourceFolderId: Long,
    targetFolderId: Long,
    updatedAt: Long,
  ): Int

  @Query(
    """
    UPDATE notes
    SET folder_id = :targetFolderId,
        revision = revision + 1,
        updated_at = :updatedAt
    WHERE folder_id = :sourceFolderId AND is_deleted = 1
    """
  )
  suspend fun repointDeletedNotesFolder(
    sourceFolderId: Long,
    targetFolderId: Long,
    updatedAt: Long,
  ): Int

  @Query("DELETE FROM notes WHERE id = :noteId")
  suspend fun deleteById(noteId: Long): Int

  @Query("DELETE FROM notes WHERE folder_id = :folderId AND is_deleted = 1 AND deleted_with_folder = 1")
  suspend fun deleteNotesDeletedWithFolder(folderId: Long): Int

  @Query(
    """
    UPDATE notes
    SET deleted_with_folder = 0,
        revision = revision + 1,
        updated_at = :updatedAt
    WHERE folder_id = :folderId AND is_deleted = 1 AND deleted_with_folder = 1
    """
  )
  suspend fun clearDeletedWithFolderFlag(folderId: Long, updatedAt: Long): Int

  @Query(
    """
    UPDATE notes
    SET folder_id = NULL,
        deleted_with_folder = 0,
        revision = revision + 1,
        updated_at = :updatedAt
    WHERE folder_id = :folderId AND is_deleted = 1
    """
  )
  suspend fun detachDeletedNotesFromFolder(folderId: Long, updatedAt: Long): Int
}
