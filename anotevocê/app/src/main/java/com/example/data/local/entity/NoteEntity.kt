package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.domain.model.NotePaperStyle

@Entity(
  tableName = "notes",
  foreignKeys =
    [
      ForeignKey(
        entity = FolderEntity::class,
        parentColumns = ["id"],
        childColumns = ["folder_id"],
        onDelete = ForeignKey.SET_NULL,
      )
    ],
  indices =
    [
      Index(value = ["uid"], unique = true),
      Index(value = ["folder_id"]),
      Index(value = ["is_deleted", "is_pinned", "updated_at"]),
      Index(value = ["is_deleted", "folder_id", "updated_at"]),
    ],
)
data class NoteEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  @ColumnInfo(name = "uid") val uid: String,
  @ColumnInfo(name = "folder_id") val folderId: Long? = null,
  @ColumnInfo(name = "title") val title: String,
  @ColumnInfo(name = "content_json") val contentJson: String,
  @ColumnInfo(name = "plain_text_preview") val plainTextPreview: String,
  @ColumnInfo(name = "paper_style", defaultValue = "LINHAS")
  val paperStyle: String = NotePaperStyle.LINHAS.name,
  @ColumnInfo(name = "revision", defaultValue = "1") val revision: Long = 1L,
  @ColumnInfo(name = "is_pinned", defaultValue = "0") val isPinned: Boolean = false,
  @ColumnInfo(name = "pinned_at") val pinnedAt: Long? = null,
  @ColumnInfo(name = "is_deleted", defaultValue = "0") val isDeleted: Boolean = false,
  @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
  @ColumnInfo(name = "deleted_with_folder", defaultValue = "0")
  val deletedWithFolder: Boolean = false,
  @ColumnInfo(name = "created_at") val createdAt: Long,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
