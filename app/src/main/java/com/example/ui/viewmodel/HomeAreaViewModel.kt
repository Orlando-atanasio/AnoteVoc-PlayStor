package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.model.HomeViewMode
import com.example.domain.model.Note
import com.example.domain.repository.FolderRepository
import com.example.domain.repository.NoteRepository
import com.example.domain.repository.PreferencesRepository
import com.example.ui.navigation.AnoteVoceDestination
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface HomeAreaContentState {
  data object Loading : HomeAreaContentState

  data object Empty : HomeAreaContentState

  data class Content(val notes: List<Note>) : HomeAreaContentState
}

data class HomeAreaUiState(
  val destination: AnoteVoceDestination,
  val viewMode: HomeViewMode = HomeViewMode.LISTA,
  val folderName: String? = null,
  val isFolderMissingOrDeleted: Boolean = false,
  val folderNamesById: Map<Long, String> = emptyMap(),
  val contentState: HomeAreaContentState = HomeAreaContentState.Loading,
)

class HomeAreaViewModel(
  private val destination: AnoteVoceDestination,
  noteRepository: NoteRepository,
  folderRepository: FolderRepository,
  preferencesRepository: PreferencesRepository,
) : ViewModel() {

  private val notesFlow: Flow<List<Note>> =
    when (destination) {
      AnoteVoceDestination.AllNotes -> noteRepository.observeAllActiveNotes()
      AnoteVoceDestination.Pinned -> noteRepository.observePinnedActiveNotes()
      AnoteVoceDestination.Trash -> noteRepository.observeDeletedNotes()
      is AnoteVoceDestination.Folder -> noteRepository.observeActiveNotesByFolder(destination.folderId)
    }

  val uiState: StateFlow<HomeAreaUiState> =
    combine(
        notesFlow,
        folderRepository.observeActiveFolders(),
        folderRepository.observeDeletedFolders(),
        preferencesRepository.observePreferences(),
      ) { notes, activeFolders, deletedFolders, preferences ->
        val folderNames =
          buildMap<Long, String> {
            deletedFolders.forEach { put(it.id, it.name) }
            activeFolders.forEach { put(it.id, it.name) }
          }

        val currentFolder =
          (destination as? AnoteVoceDestination.Folder)?.let { route ->
            activeFolders.firstOrNull { it.id == route.folderId }
          }

        val isFolderMissing =
          destination is AnoteVoceDestination.Folder && currentFolder == null

        val contentState =
          if (notes.isEmpty()) {
            HomeAreaContentState.Empty
          } else {
            HomeAreaContentState.Content(notes)
          }

        HomeAreaUiState(
          destination = destination,
          viewMode = preferences.homeViewMode,
          folderName = currentFolder?.name,
          isFolderMissingOrDeleted = isFolderMissing,
          folderNamesById = folderNames,
          contentState = contentState,
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = HomeAreaUiState(destination = destination),
      )
}
