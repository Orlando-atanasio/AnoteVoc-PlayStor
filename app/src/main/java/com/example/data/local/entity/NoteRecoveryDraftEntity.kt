package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Armazena checkpoints persistentes de recuperação em `note_recovery_drafts`.
 * Sem chaves estrangeiras ou exclusão em cascata para evitar perda silenciosa
 * de rascunhos antes da política explícita de recuperação pelo usuário.
 */
@Entity(
  tableName = "note_recovery_drafts",
  indices =
    [
      Index(value = ["session_id"], unique = true),
      Index(value = ["target_note_id"]),
      Index(value = ["checkpoint_at"]),
    ],
)
data class NoteRecoveryDraftEntity(
  @PrimaryKey @ColumnInfo(name = "draft_id") val draftId: String,
  @ColumnInfo(name = "target_note_id") val targetNoteId: Long? = null,
  @ColumnInfo(name = "session_id") val sessionId: String,
  @ColumnInfo(name = "folder_id") val folderId: Long? = null,
  @ColumnInfo(name = "initial_folder_id") val initialFolderId: Long? = null,
  @ColumnInfo(name = "fallback_source_folder_id") val fallbackSourceFolderId: Long? = null,
  @ColumnInfo(name = "is_pinned", defaultValue = "0") val isPinned: Boolean = false,
  @ColumnInfo(name = "title") val title: String,
  @ColumnInfo(name = "content_json") val contentJson: String,
  @ColumnInfo(name = "paper_style") val paperStyle: String,
  @ColumnInfo(name = "initial_paper_style") val initialPaperStyle: String? = null,
  @ColumnInfo(name = "base_revision", defaultValue = "0") val baseRevision: Long = 0L,
  @ColumnInfo(name = "checkpoint_revision", defaultValue = "1") val checkpointRevision: Long = 1L,
  @ColumnInfo(name = "base_note_updated_at") val baseNoteUpdatedAt: Long? = null,
  @ColumnInfo(name = "checkpoint_at") val checkpointAt: Long,
)
