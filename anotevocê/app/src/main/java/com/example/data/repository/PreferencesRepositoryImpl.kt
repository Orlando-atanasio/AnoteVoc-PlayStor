package com.example.data.repository

import androidx.room.withTransaction
import com.example.core.coroutines.DispatcherProvider
import com.example.core.time.ClockProvider
import com.example.data.local.dao.AppPreferencesDao
import com.example.data.local.database.AnoteVoceDatabase
import com.example.data.local.mapper.toDomain
import com.example.data.local.mapper.toEntity
import com.example.domain.model.AppPreferences
import com.example.domain.model.AppThemeMode
import com.example.domain.model.HomeViewMode
import com.example.domain.model.NotePaperStyle
import com.example.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Repositório canônico de preferências no Room (tema da interface, papel padrão das notas
 * e visualização da Home), garantindo consistência transacional em backup e restauração
 * sem duplicação no DataStore.
 */
class PreferencesRepositoryImpl(
  private val database: AnoteVoceDatabase,
  private val appPreferencesDao: AppPreferencesDao,
  private val dispatchers: DispatcherProvider,
  private val clock: ClockProvider,
) : PreferencesRepository {

  override fun observePreferences(): Flow<AppPreferences> =
    appPreferencesDao
      .observePreferences()
      .map { entity -> entity?.toDomain() ?: AppPreferences() }
      .flowOn(dispatchers.io)

  override suspend fun getPreferences(): AppPreferences =
    withContext(dispatchers.io) {
      appPreferencesDao.getPreferences()?.toDomain() ?: AppPreferences()
    }

  override suspend fun updateThemeMode(themeMode: AppThemeMode) {
    updateInTransaction { current -> current.copy(themeMode = themeMode) }
  }

  override suspend fun updateDefaultNotePaper(paperStyle: NotePaperStyle) {
    updateInTransaction { current -> current.copy(defaultNotePaper = paperStyle) }
  }

  override suspend fun updateHomeViewMode(homeViewMode: HomeViewMode) {
    updateInTransaction { current -> current.copy(homeViewMode = homeViewMode) }
  }

  private suspend fun updateInTransaction(transform: (AppPreferences) -> AppPreferences) {
    withContext(dispatchers.io) {
      database.withTransaction {
        val current = appPreferencesDao.getPreferences()?.toDomain() ?: AppPreferences()
        val updated = transform(current).copy(updatedAt = clock.nowMillis())
        appPreferencesDao.upsert(updated.toEntity())
      }
    }
  }
}
