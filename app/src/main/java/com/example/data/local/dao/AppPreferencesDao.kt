package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.AppPreferencesEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppPreferencesDao {
  @Query("SELECT * FROM app_preferences WHERE id = :singletonId LIMIT 1")
  fun observePreferences(singletonId: Int = AppPreferencesEntity.SINGLETON_ID): Flow<AppPreferencesEntity?>

  @Query("SELECT * FROM app_preferences WHERE id = :singletonId LIMIT 1")
  suspend fun getPreferences(singletonId: Int = AppPreferencesEntity.SINGLETON_ID): AppPreferencesEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: AppPreferencesEntity)
}
