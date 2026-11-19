package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.core.coroutines.DefaultDispatcherProvider
import com.example.core.coroutines.DispatcherProvider
import com.example.core.di.AppContainer
import com.example.core.time.ClockProvider
import com.example.core.time.SystemClockProvider
import com.example.data.preferences.LocalDevicePreferencesDataSource
import com.example.domain.editor.NoteEditorOpenRequest
import com.example.domain.repository.FolderRepository
import com.example.domain.repository.NoteRecoveryDraftRepository
import com.example.domain.repository.NoteRepository
import com.example.domain.repository.PreferencesRepository
import com.example.ui.navigation.AnoteVoceDestination
import kotlinx.coroutines.CoroutineScope

/**
 * Fábrica manual leve de ViewModels usando [AppContainer],
 * evitando introdução de frameworks externos de injeção de dependência.
 */
class AnoteVoceViewModelFactory(
  private val folderRepository: FolderRepository,
  private val noteRepository: NoteRepository,
  private val preferencesRepository: PreferencesRepository,
  private val localDevicePreferences: LocalDevicePreferencesDataSource? = null,
  private val noteRecoveryDraftRepository: NoteRecoveryDraftRepository? = null,
  private val dispatchers: DispatcherProvider = DefaultDispatcherProvider(),
  private val clock: ClockProvider = SystemClockProvider(),
  private val sessionCoordinatorFactory: ((NoteEditorOpenRequest, CoroutineScope) -> NoteEditorSessionCoordinator)? =
    null,
  private val autosaveDebounceMillis: Long =
    NoteEditorSessionCoordinator.DEFAULT_AUTOSAVE_DEBOUNCE_MILLIS,
) {
  constructor(
    container: AppContainer
  ) : this(
    folderRepository = container.folderRepository,
    noteRepository = container.noteRepository,
    preferencesRepository = container.preferencesRepository,
    localDevicePreferences = container.localDevicePreferences,
    noteRecoveryDraftRepository = container.noteRecoveryDraftRepository,
    dispatchers = container.dispatchers,
    clock = container.clock,
    sessionCoordinatorFactory = { request, scope ->
      container.createNoteEditorSessionCoordinator(openRequest = request, parentScope = scope)
    },
  )

  private val fallbackActiveSessionRegistry by lazy { ActiveSessionRegistry() }
  private val fallbackHandoffTracker by lazy { ReplacementHandoffTracker(clock = clock) }

  private fun resolveCoordinatorFactory():
    (NoteEditorOpenRequest, CoroutineScope) -> NoteEditorSessionCoordinator {
    sessionCoordinatorFactory?.let { return it }
    val draftRepo =
      noteRecoveryDraftRepository
        ?: throw IllegalStateException(
          "NoteRecoveryDraftRepository é obrigatório para abrir uma sessão do editor."
        )
    val openLoader =
      SessionOpenLoader(
        noteRepository = noteRepository,
        folderRepository = folderRepository,
        draftRepository = draftRepo,
        preferencesRepository = preferencesRepository,
        activeSessionRegistry = fallbackActiveSessionRegistry,
        handoffTracker = fallbackHandoffTracker,
      )
    val reconciler =
      PendingReplacementReconciler(
        noteRepository = noteRepository,
        draftRepository = draftRepo,
        activeSessionRegistry = fallbackActiveSessionRegistry,
        handoffTracker = fallbackHandoffTracker,
      )
    return { request, parentScope ->
      NoteEditorSessionCoordinator(
        openRequest = request,
        sessionOpenLoader = openLoader,
        replacementReconciler = reconciler,
        handoffTracker = fallbackHandoffTracker,
        activeSessionRegistry = fallbackActiveSessionRegistry,
        draftRepository = draftRepo,
        noteRepository = noteRepository,
        folderRepository = folderRepository,
        dispatchers = dispatchers,
        clock = clock,
        parentScope = parentScope,
        autosaveDebounceMillis = autosaveDebounceMillis,
      )
    }
  }

  fun shellFactory(): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ShellViewModel::class.java)) {
          return ShellViewModel(
            preferencesRepository = preferencesRepository,
            folderRepository = folderRepository,
            localDevicePreferences = localDevicePreferences,
            noteRecoveryDraftRepository = noteRecoveryDraftRepository,
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

  fun editorFactory(openRequest: NoteEditorOpenRequest): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(NoteEditorViewModel::class.java)) {
          return NoteEditorViewModel(
            openRequest = openRequest,
            coordinatorFactory = resolveCoordinatorFactory(),
            folderRepository = folderRepository,
            noteRepository = noteRepository,
          )
            as T
        }
        throw IllegalArgumentException("ViewModel desconhecido: ${modelClass.name}")
      }
    }
}
