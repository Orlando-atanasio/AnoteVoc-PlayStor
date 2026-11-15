package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.core.di.AppContainer
import com.example.data.preferences.LocalDevicePreferencesDataSource
import com.example.domain.repository.FolderRepository
import com.example.domain.repository.NoteRepository
import com.example.domain.repository.PreferencesRepository
import com.example.ui.navigation.AnoteVoceDestination

/**
 * Fábrica manual leve de ViewModels usando [AppContainer],
 * evitando introdução de frameworks externos de injeção de dependência.
 */
class AnoteVoceViewModelFactory(
  private val folderRepository: FolderRepository,
  private val noteRepository: NoteRepository,
  private val preferencesRepository: PreferencesRepository,
  private val localDevicePreferences: LocalDevicePreferencesDataSource? = null,
) {
  constructor(
    container: AppContainer
  ) : this(
    folderRepository = container.folderRepository,
    noteRepository = container.noteRepository,
    preferencesRepository = container.preferencesRepository,
    localDevicePreferences = container.localDevicePreferences,
  )

  fun shellFactory(): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ShellViewModel::class.java)) {
          return ShellViewModel(
            preferencesRepository = preferencesRepository,
            folderRepository = folderRepository,
            localDevicePreferences = localDevicePreferences,
          )
            as T
        }
        throw IllegalArgumentException("ViewModel desconhecido: ${modelClass.name}")
      }
    }

  fun areaFactory(destination: AnoteVoceDestination): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeAreaViewModel::class.java)) {
          return HomeAreaViewModel(
            destination = destination,
            noteRepository = noteRepository,
            folderRepository = folderRepository,
            preferencesRepository = preferencesRepository,
          )
            as T
        }
        throw IllegalArgumentException("ViewModel desconhecido: ${modelClass.name}")
      }
    }
}
