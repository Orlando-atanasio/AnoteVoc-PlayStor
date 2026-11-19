package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.preferences.LocalDevicePreferencesDataSource
import com.example.domain.model.AppThemeMode
import com.example.domain.model.Folder
import com.example.domain.model.HomeViewMode
import com.example.domain.model.NoteRecoveryDraft
import com.example.domain.repository.FolderRepository
import com.example.domain.repository.NoteRecoveryDraftRepository
import com.example.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ShellUiState(
  val versionName: String = BuildConfig.VERSION_NAME,
  val themeMode: AppThemeMode = AppThemeMode.MODO_DIA,
  val homeViewMode: HomeViewMode = HomeViewMode.LISTA,
  val activeFolders: List<Folder> = emptyList(),
  val sidebarExpandedPreference: Boolean? = null,
  val pendingRecoveryDrafts: List<NoteRecoveryDraft> = emptyList(),
)

/**
 * ViewModel principal da casca do aplicativo:
 * - Observa tema e modo de visualização persistidos no Room ([PreferencesRepository]);
 * - Observa pastas personalizadas ativas ([FolderRepository]);
 * - Observa checkpoints de recuperação pendentes ([NoteRecoveryDraftRepository]);
 * - Persiste preferência estritamente local de expansão do menu lateral ([LocalDevicePreferencesDataSource]).
 */
class ShellViewModel(
  preferencesRepository: PreferencesRepository,
  folderRepository: FolderRepository,
  private val localDevicePreferences: LocalDevicePreferencesDataSource? = null,
  private val noteRecoveryDraftRepository: NoteRecoveryDraftRepository? = null,
) : ViewModel() {

  private val inMemorySidebarExpanded = MutableStateFlow<Boolean?>(null)

  private val sidebarPreferenceFlow: Flow<Boolean?> =
    localDevicePreferences?.observeSidebarExpanded() ?: inMemorySidebarExpanded

  private val draftsFlow: Flow<List<NoteRecoveryDraft>> =
    noteRecoveryDraftRepository?.observeAllCheckpoints() ?: flowOf(emptyList())

  val uiState: StateFlow<ShellUiState> =
    combine(
        preferencesRepository.observePreferences(),
        folderRepository.observeActiveFolders(),
        sidebarPreferenceFlow,
        draftsFlow,
      ) { preferences, folders, sidebarExpanded, drafts ->
        ShellUiState(
          versionName = BuildConfig.VERSION_NAME,
          themeMode = preferences.themeMode,
          homeViewMode = preferences.homeViewMode,
          activeFolders = folders,
          sidebarExpandedPreference = sidebarExpanded,
          pendingRecoveryDrafts = drafts,
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ShellUiState(),
      )

  fun setSidebarExpanded(expanded: Boolean) {
    inMemorySidebarExpanded.value = expanded
    val dataSource = localDevicePreferences ?: return
    viewModelScope.launch { dataSource.setSidebarExpanded(expanded) }
  }

  fun discardPendingDraft(draftId: String) {
    val repo = noteRecoveryDraftRepository ?: return
    viewModelScope.launch {
      runCatching { repo.deleteCheckpoint(draftId) }
    }
  }
}
