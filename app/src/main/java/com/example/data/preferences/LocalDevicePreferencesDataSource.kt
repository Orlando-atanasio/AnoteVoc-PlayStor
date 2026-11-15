package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.localDeviceDataStore: DataStore<Preferences> by
  preferencesDataStore(name = PreferencesStorageSpec.DATASTORE_NAME)

/**
 * Fonte de dados DataStore reservada exclusivamente para preferências ou metadados
 * estritamente locais do aparelho que NÃO participam do backup/restauração.
 *
 * Tema, papel padrão das notas e visualização da Home possuem armazenamento
 * canônico exclusivo no Room (tabela `app_preferences`).
 */
class LocalDevicePreferencesDataSource(context: Context) {
  val dataStore: DataStore<Preferences> = context.applicationContext.localDeviceDataStore

  fun observeSidebarExpanded(): Flow<Boolean?> =
    dataStore.data
      .catch { exception ->
        if (exception is IOException) {
          emit(emptyPreferences())
        } else {
          throw exception
        }
      }
      .map { preferences -> preferences[KEY_SIDEBAR_EXPANDED] }

  suspend fun setSidebarExpanded(expanded: Boolean) {
    dataStore.edit { preferences -> preferences[KEY_SIDEBAR_EXPANDED] = expanded }
  }

  private companion object {
    val KEY_SIDEBAR_EXPANDED = booleanPreferencesKey("sidebar_expanded")
  }
}
