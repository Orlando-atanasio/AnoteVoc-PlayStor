package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "folders",
  indices =
    [
      Index(value = ["uid"], unique = true),
      Index(value = ["active_name_key"], unique = true),
      Index(value = ["is_deleted", "updated_at"]),
    ],
)
data class FolderEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  @ColumnInfo(name = "uid") val uid: String,
  @ColumnInfo(name = "name") val name: String,
  @ColumnInfo(name = "normalized_name") val normalizedName: String,
  /**
   * Igual a [normalizedName] enquanto a pasta está ativa; `null` quando em Apagados.
   * Garante unicidade exclusiva entre pastas ativas diretamente pelo índice UNIQUE do SQLite.
   */
  @ColumnInfo(name = "active_name_key") val activeNameKey: String?,
  @ColumnInfo(name = "is_deleted", defaultValue = "0") val isDeleted: Boolean = false,
  @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
  @ColumnInfo(name = "created_at") val createdAt: Long,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
