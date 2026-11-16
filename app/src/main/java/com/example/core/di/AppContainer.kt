package com.example.core.di

import android.content.Context
import com.example.core.coroutines.DefaultDispatcherProvider
import com.example.core.coroutines.DispatcherProvider
import com.example.core.time.ClockProvider
import com.example.core.time.SystemClockProvider
import com.example.data.local.database.AnoteVoceDatabase
import com.example.data.preferences.LocalDevicePreferencesDataSource
import com.example.data.repository.FolderRepositoryImpl
import com.example.data.repository.NoteRecoveryDraftRepositoryImpl
import com.example.data.repository.NoteRepositoryImpl
import com.example.data.repository.PreferencesRepositoryImpl
import com.example.domain.editor.NoteEditorOpenRequest
import com.example.domain.repository.FolderRepository
import com.example.domain.repository.NoteRecoveryDraftRepository
import com.example.domain.repository.NoteRepository
import com.example.domain.repository.PreferencesRepository
import com.example.ui.viewmodel.ActiveSessionRegistry
import com.example.ui.viewmodel.NoteEditorSessionCoordinator
import com.example.ui.viewmodel.PendingReplacementReconciler
import com.example.ui.viewmodel.ReplacementHandoffTracker
import com.example.ui.viewmodel.SessionOpenLoader
import kotlinx.coroutines.CoroutineScope

/**
 * Contêiner leve de dependências por injeção manual de construtor,
 * evitando sobrecarga desnecessária de frameworks na versão 1.0.
 */
class AppContainer(context: Context) {
  private val appContext = context.applicationContext

  val dispatchers: DispatcherProvider = DefaultDispatcherProvider()
  val clock: ClockProvider = SystemClockProvider()

  val database: AnoteVoceDatabase by lazy { AnoteVoceDatabase.build(appContext) }

  val localDevicePreferences: LocalDevicePreferencesDataSource by lazy {
    LocalDevicePreferencesDataSource(appContext)
  }

  val folderRepository: FolderRepository by lazy {
    FolderRepositoryImpl(
      database = database,
      folderDao = database.folderDao(),
      noteDao = database.noteDao(),
      dispatchers = dispatchers,
      clock = clock,
    )
  }

  val noteRepository: NoteRepository by lazy {
    NoteRepositoryImpl(
      database = database,
      noteDao = database.noteDao(),
      folderDao = database.folderDao(),
      dispatchers = dispatchers,
      clock = clock,
      appPreferencesDao = database.appPreferencesDao(),
      noteRecoveryDraftDao = database.noteRecoveryDraftDao(),
    )
  }

  val noteRecoveryDraftRepository: NoteRecoveryDraftRepository by lazy {
    NoteRecoveryDraftRepositoryImpl(
      database = database,
      draftDao = database.noteRecoveryDraftDao(),
      dispatchers = dispatchers,
      clock = clock,
      noteDao = database.noteDao(),
    )
  }

  val preferencesRepository: PreferencesRepository by lazy {
    PreferencesRepositoryImpl(
      database = database,
      appPreferencesDao = database.appPreferencesDao(),
      dispatchers = dispatchers,
      clock = clock,
    )
  }

  val activeSessionRegistry: ActiveSessionRegistry by lazy { ActiveSessionRegistry() }

  val replacementHandoffTracker: ReplacementHandoffTracker by lazy {
    ReplacementHandoffTracker(clock = clock)
  }

  val sessionOpenLoader: SessionOpenLoader by lazy {
    SessionOpenLoader(
      noteRepository = noteRepository,
      folderRepository = folderRepository,
      draftRepository = noteRecoveryDraftRepository,
      preferencesRepository = preferencesRepository,
      activeSessionRegistry = activeSessionRegistry,
      handoffTracker = replacementHandoffTracker,
    )
  }

  val pendingReplacementReconciler: PendingReplacementReconciler by lazy {
    PendingReplacementReconciler(
      noteRepository = noteRepository,
      draftRepository = noteRecoveryDraftRepository,
      activeSessionRegistry = activeSessionRegistry,
      handoffTracker = replacementHandoffTracker,
    )
  }

  fun createNoteEditorSessionCoordinator(
    openRequest: NoteEditorOpenRequest,
    parentScope: CoroutineScope,
    autosaveDebounceMillis: Long = NoteEditorSessionCoordinator.DEFAULT_AUTOSAVE_DEBOUNCE_MILLIS,
  ): NoteEditorSessionCoordinator =
    NoteEditorSessionCoordinator(
      openRequest = openRequest,
      sessionOpenLoader = sessionOpenLoader,
      replacementReconciler = pendingReplacementReconciler,
      handoffTracker = replacementHandoffTracker,
      activeSessionRegistry = activeSessionRegistry,
      draftRepository = noteRecoveryDraftRepository,
      noteRepository = noteRepository,
      folderRepository = folderRepository,
      dispatchers = dispatchers,
      clock = clock,
      parentScope = parentScope,
      autosaveDebounceMillis = autosaveDebounceMillis,
    )
}
