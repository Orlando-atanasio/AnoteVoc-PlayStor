package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.NoteRecoveryDraftEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteRecoveryDraftDao {
  @Query("SELECT * FROM note_recovery_drafts ORDER BY checkpoint_at DESC")
  fun observeAllCheckpoints(): Flow<List<NoteRecoveryDraftEntity>>

  @Query("SELECT * FROM note_recovery_drafts ORDER BY checkpoint_at DESC")
  suspend fun getAllCheckpoints(): List<NoteRecoveryDraftEntity>

  @Query("SELECT * FROM note_recovery_drafts WHERE draft_id = :draftId LIMIT 1")
  suspend fun getByDraftId(draftId: String): NoteRecoveryDraftEntity?

  @Query(
    "SELECT * FROM note_recovery_drafts WHERE target_note_id = :noteId ORDER BY checkpoint_at DESC"
  )
  suspend fun getByTargetNoteId(noteId: Long): List<NoteRecoveryDraftEntity>

  @Query("SELECT * FROM note_recovery_drafts WHERE session_id = :sessionId LIMIT 1")
  suspend fun getBySessionId(sessionId: String): NoteRecoveryDraftEntity?

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(entity: NoteRecoveryDraftEntity)

  @Update
  suspend fun update(entity: NoteRecoveryDraftEntity): Int

  @Query("DELETE FROM note_recovery_drafts WHERE draft_id = :draftId")
  suspend fun deleteByDraftId(draftId: String): Int

  @Query("DELETE FROM note_recovery_drafts WHERE target_note_id = :noteId")
  suspend fun deleteByTargetNoteId(noteId: Long): Int
}
