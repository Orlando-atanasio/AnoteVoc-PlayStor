package com.example.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.example.data.local.LocalStorageSpec
import com.example.data.local.dao.AppPreferencesDao
import com.example.data.local.dao.FolderDao
import com.example.data.local.dao.NoteDao
import com.example.data.local.dao.NoteRecoveryDraftDao
import com.example.data.local.entity.AppPreferencesEntity
import com.example.data.local.entity.FolderEntity
import com.example.data.local.entity.NoteEntity
import com.example.data.local.entity.NoteRecoveryDraftEntity

@Database(
  entities =
    [
      FolderEntity::class,
      NoteEntity::class,
      AppPreferencesEntity::class,
      NoteRecoveryDraftEntity::class,
    ],
  version = LocalStorageSpec.DATABASE_VERSION,
  exportSchema = true,
)
abstract class AnoteVoceDatabase : RoomDatabase() {
  abstract fun folderDao(): FolderDao
  abstract fun noteDao(): NoteDao
  abstract fun appPreferencesDao(): AppPreferencesDao
  abstract fun noteRecoveryDraftDao(): NoteRecoveryDraftDao

  companion object {
    /**
     * Lista de migrações explícitas (v1 -> v2 -> v3 -> v4),
     * evitando perda de dados e proibindo fallbackToDestructiveMigration().
     */
    val ALL_MIGRATIONS: Array<Migration> =
      arrayOf(
        AnoteVoceMigrations.MIGRATION_1_2,
        AnoteVoceMigrations.MIGRATION_2_3,
        AnoteVoceMigrations.MIGRATION_3_4,
      )

    fun build(context: Context): AnoteVoceDatabase =
      Room.databaseBuilder(
          context.applicationContext,
          AnoteVoceDatabase::class.java,
          LocalStorageSpec.DATABASE_NAME,
        )
        .addMigrations(*ALL_MIGRATIONS)
        .build()
  }
}
