package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_preferences")
data class AppPreferencesEntity(
  @PrimaryKey val id: Int = SINGLETON_ID,
  @ColumnInfo(name = "theme_mode") val themeMode: String,
  @ColumnInfo(name = "default_note_paper") val defaultNotePaper: String,
  @ColumnInfo(name = "home_view_mode") val homeViewMode: String,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
  companion object {
    const val SINGLETON_ID: Int = 1
  }
}
