package com.example.ui.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.coroutines.DispatcherProvider
import com.example.core.time.ClockProvider
import com.example.data.local.database.AnoteVoceDatabase
import com.example.data.repository.FolderRepositoryImpl
import com.example.data.repository.NoteRecoveryDraftRepositoryImpl
import com.example.data.repository.NoteRepositoryImpl
import com.example.data.repository.PreferencesRepositoryImpl
import com.example.domain.editor.ConflictResolutionOption
import com.example.domain.editor.ConsolidationReadiness
import com.example.domain.editor.NoteEditorOpenRequest
import com.example.domain.editor.RecoveryTargetExpectation
import com.example.domain.model.BulletListItemBlock
import com.example.domain.model.ChecklistItemBlock
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.NoteRecoveryDraftInput
import com.example.domain.model.NoteRecoveryDraftKeys
import com.example.domain.model.NumberedListItemBlock
import com.example.domain.model.ParagraphBlock
import com.example.domain.model.ReplaceCheckpointWithNewNoteResult
import com.example.domain.model.SaveCheckpointResult
import com.example.domain.model.TextFormatStyle
import com.example.domain.repository.FolderDeleteMode
import com.example.domain.repository.NoteRecoveryDraftRepository
import com.example.domain.repository.NoteRepository
import com.example.domain.repository.PinNoteResult
import com.example.domain.editor.NoteEditorBaseline
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NoteEditorViewModelTest {

  private lateinit var context: Context
  private lateinit var database: AnoteVoceDatabase
  private lateinit var folderRepository: FolderRepositoryImpl
  private lateinit var noteRepository: NoteRepositoryImpl
  private lateinit var draftRepository: NoteRecoveryDraftRepositoryImpl
  private lateinit var preferencesRepository: PreferencesRepositoryImpl
  private lateinit var factory: AnoteVoceViewModelFactory

  private val fakeTime = AtomicLong(1_700_500_000_000L)
  private val clock =
    object : ClockProvider {
      override fun nowMillis(): Long = fakeTime.incrementAndGet()
    }

  private val ioDispatchers =
    object : DispatcherProvider {
      override val main: CoroutineDispatcher = Dispatchers.Default
      override val io: CoroutineDispatcher = Dispatchers.IO
      override val default: CoroutineDispatcher = Dispatchers.Default
    }

  @Before
  fun setUp() {
    Dispatchers.setMain(Dispatchers.Default)
    context = ApplicationProvider.getApplicationContext()
    database =
      Room.inMemoryDatabaseBuilder(context, AnoteVoceDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    folderRepository =
      FolderRepositoryImpl(
        database = database,
        folderDao = database.folderDao(),
        noteDao = database.noteDao(),
        dispatchers = ioDispatchers,
        clock = clock,
      )
    noteRepository =
      NoteRepositoryImpl(
        database = database,
        noteDao = database.noteDao(),
        folderDao = database.folderDao(),
        dispatchers = ioDispatchers,
        clock = clock,
      )
    draftRepository =
      NoteRecoveryDraftRepositoryImpl(
        database = database,
        draftDao = database.noteRecoveryDraftDao(),
        dispatchers = ioDispatchers,
        clock = clock,
        noteDao = database.noteDao(),
      )
    preferencesRepository =
      PreferencesRepositoryImpl(
        database = database,
        appPreferencesDao = database.appPreferencesDao(),
        dispatchers = ioDispatchers,
        clock = clock,
      )
    factory =
      AnoteVoceViewModelFactory(
        folderRepository = folderRepository,
        noteRepository = noteRepository,
        preferencesRepository = preferencesRepository,
        noteRecoveryDraftRepository = draftRepository,
        dispatchers = ioDispatchers,
        clock = clock,
        autosaveDebounceMillis = 40L,
      )
  }

  @After
  fun tearDown() {
    database.close()
    Dispatchers.resetMain()
  }

  private fun createViewModel(request: NoteEditorOpenRequest): NoteEditorViewModel =
    factory.editorFactory(request).create(NoteEditorViewModel::class.java)

  private suspend fun awaitEditing(vm: NoteEditorViewModel): NoteEditorUiState {
    withTimeout(3_000L) {
      vm.uiState.first { it.phase != NoteEditorSessionPhase.LOADING }
    }
    return vm.uiState.value
  }

  private suspend fun waitUntil(timeoutMs: Long = 3_000L, predicate: suspend () -> Boolean) {
    withTimeout(timeoutMs) {
      while (!predicate()) {
        delay(10L)
      }
    }
  }

  @Test
  fun `new note opens in edit mode, discards cleanly when empty, and creates zero notes`() {
    runBlocking {
      val vm = createViewModel(NoteEditorOpenRequest.CreateNew())
      val initialState = awaitEditing(vm)
      assertEquals(NoteEditorSessionPhase.EDITING, initialState.phase)
      assertFalse(vm.isReadOnlyMode.value)

      val navDeferred = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeout(3_000L) { vm.events.first { it is NoteEditorUiEvent.NavigateBack } }
      }
      vm.onExitRequested()
      navDeferred.await()

      assertTrue(noteRepository.observeAllActiveNotes().first().isEmpty())
      assertTrue(draftRepository.getAllCheckpoints().isEmpty())
    }
  }

  @Test
  fun `new note autosaves draft on background and consolidates into notes on exit`() {
    runBlocking {
      val folder = folderRepository.createFolder("Trabalho")
      val vm =
        createViewModel(
          NoteEditorOpenRequest.CreateNew(initialFolderId = folder.id, sessionId = "vm-create-1")
        )
      awaitEditing(vm)

      vm.onTitleChanged("Ata semanal")
      vm.onEmptyDocumentTextChanged("Definir prioridades")
      waitUntil { vm.uiState.value.title == "Ata semanal" && vm.uiState.value.editorState.document.blocks.isNotEmpty() }

      vm.onAppBackgrounded()
      waitUntil { draftRepository.getAllCheckpoints().isNotEmpty() }

      val checkpoints = draftRepository.getAllCheckpoints()
      assertEquals(1, checkpoints.size)
      assertEquals(folder.id, checkpoints.first().folderId)

      val navDeferred = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeout(3_000L) { vm.events.first { it is NoteEditorUiEvent.NavigateBack } }
      }
      vm.onExitRequested()
      navDeferred.await()

      val notes = noteRepository.observeAllActiveNotes().first()
      assertEquals(1, notes.size)
      assertEquals("Ata semanal", notes.first().title)
      assertEquals(folder.id, notes.first().folderId)
      assertEquals("Definir prioridades", notes.first().plainTextPreview)
      assertTrue(draftRepository.getAllCheckpoints().isEmpty())
    }
  }

  @Test
  fun `existing note opens in read-only mode and enterEditMode preserves exact tapped offset`() {
    runBlocking {
      val note =
        noteRepository.createNote(
          title = "Leitura",
          content =
            NoteContentDocument(
              blocks =
                listOf(
                  ParagraphBlock(id = "b1", text = "Primeiro bloco"),
                  ParagraphBlock(id = "b2", text = "Segundo bloco aqui"),
                )
            ),
        )

      val vm = createViewModel(NoteEditorOpenRequest.OpenExisting(note.id))
      awaitEditing(vm)
      assertTrue(vm.isReadOnlyMode.value)

      val focusDeferred = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeout(3_000L) { vm.events.first { it is NoteEditorUiEvent.RequestFocusAndKeyboard } }
      }
      vm.enterEditMode(blockId = "b2", offset = 7)
      val event = focusDeferred.await() as NoteEditorUiEvent.RequestFocusAndKeyboard

      assertFalse(vm.isReadOnlyMode.value)
      assertEquals("b2", event.blockId)
      assertEquals(7, event.offset)
      assertEquals("b2", vm.uiState.value.editorState.selection?.blockId)
      assertEquals(7, vm.uiState.value.editorState.selection?.start)
      assertEquals(7, vm.uiState.value.editorState.selection?.end)

      val navDeferred = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeout(3_000L) { vm.events.first { it is NoteEditorUiEvent.NavigateBack } }
      }
      vm.onExitRequested()
      navDeferred.await()
    }
  }

  @Test
  fun `PersistExistingNotePin updates DB revision without causing false conflict on exit consolidation`() {
    runBlocking {
      val note =
        noteRepository.createNote(
          title = "Nota fixa",
          content =
            NoteContentDocument(
              blocks = listOf(ParagraphBlock(id = "p1", text = "Conteúdo inicial"))
            ),
          isPinned = false,
        )

      val vm = createViewModel(NoteEditorOpenRequest.OpenExisting(note.id))
      awaitEditing(vm)

      vm.onTogglePin()
      waitUntil { vm.uiState.value.isPinned }

      val afterPin = noteRepository.getNoteById(note.id)!!
      assertTrue(afterPin.isPinned)
      assertTrue(afterPin.revision > note.revision)

      vm.enterEditMode(blockId = "p1", offset = 16)
      waitUntil { !vm.isReadOnlyMode.value }

      vm.onBlockTextChanged("p1", "Conteúdo atualizado", 19, 19)
      waitUntil {
        vm.uiState.value.editorState.document.blocks.first().text == "Conteúdo atualizado"
      }

      val navDeferred = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeout(3_000L) { vm.events.first { it is NoteEditorUiEvent.NavigateBack } }
      }
      vm.onExitRequested()
      navDeferred.await()

      val finalNote = noteRepository.getNoteById(note.id)!!
      assertTrue(finalNote.isPinned)
      assertEquals("Conteúdo atualizado", finalNote.plainTextPreview)
      assertNull(vm.uiState.value.activeConflict)
    }
  }

  @Test
  fun `PersistExistingNotePin detects real external modification conflict`() {
    runBlocking {
      val note =
        noteRepository.createNote(
          title = "Concorrente",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Base"))),
        )

      val vm = createViewModel(NoteEditorOpenRequest.OpenExisting(note.id))
      awaitEditing(vm)

      // Modificação externa direta no repositório eleva revision/updatedAt
      val loaded = noteRepository.getNoteById(note.id)!!
      noteRepository.updateNote(
        noteId = note.id,
        title = "Alterada fora",
        content = loaded.contentOrNull!!,
        folderId = loaded.folderId,
        paperStyle = loaded.paperStyle,
        expectedBaseRevision = loaded.revision,
      )

      vm.onTogglePin()
      waitUntil { vm.uiState.value.activeConflict != null }

      assertNotNull(vm.uiState.value.activeConflict)
      assertFalse(noteRepository.getNoteById(note.id)!!.isPinned)

      vm.onResolveConflict(ConflictResolutionOption.DISCARD_DRAFT_USE_CURRENT_NOTE)
      waitUntil { vm.uiState.value.activeConflict == null }
      assertEquals("Alterada fora", vm.uiState.value.title)

      val navDeferred = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeout(3_000L) { vm.events.first { it is NoteEditorUiEvent.NavigateBack } }
      }
      vm.onExitRequested()
      navDeferred.await()
    }
  }

  @Test
  fun `delete existing note releases session and moves note to Trash`() {
    runBlocking {
      val note = noteRepository.createNote(title = "Para apagar")
      val vm = createViewModel(NoteEditorOpenRequest.OpenExisting(note.id))
      awaitEditing(vm)

      val navDeferred = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeout(3_000L) { vm.events.first { it is NoteEditorUiEvent.NavigateBack } }
      }
      vm.onConfirmDelete()
      navDeferred.await()

      val deleted = noteRepository.getNoteById(note.id)!!
      assertTrue(deleted.isDeleted)
      assertTrue(noteRepository.observeAllActiveNotes().first().isEmpty())
      assertEquals(1, noteRepository.observeDeletedNotes().first().size)
    }
  }

  @Test
  fun `deleted origin folder triggers fallback confirmation and saves to root when confirmed`() {
    runBlocking {
      val folder = folderRepository.createFolder("Temporária")
      val vm =
        createViewModel(
          NoteEditorOpenRequest.CreateNew(initialFolderId = folder.id, sessionId = "fallback-vm")
        )
      awaitEditing(vm)

      vm.onTitleChanged("Nota sem pasta")
      vm.onEmptyDocumentTextChanged("Conteúdo salvo na raiz")
      waitUntil { vm.uiState.value.title == "Nota sem pasta" }

      folderRepository.deleteFolder(folder.id, FolderDeleteMode.FOLDER_AND_CONTENT)
      waitUntil {
        vm.uiState.value.consolidationReadiness is ConsolidationReadiness.RequiresExplicitFolderConfirmation
      }

      vm.onExitRequested()
      waitUntil { vm.showFallbackConfirmDialog.value }

      val navDeferred = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeout(3_000L) { vm.events.first { it is NoteEditorUiEvent.NavigateBack } }
      }
      vm.onConfirmFallbackSaveToRootAndExit()
      navDeferred.await()

      val saved = noteRepository.observeAllActiveNotes().first().single()
      assertEquals("Nota sem pasta", saved.title)
      assertNull(saved.folderId)
    }
  }

  @Test
  fun `formatNoteForSharing renders title, paragraphs, bullets, numbers, and checklists cleanly`() {
    val engine =
      com.example.domain.editor.NoteEditorEngine(
        initialDocument =
          NoteContentDocument(
            blocks =
              listOf(
                ParagraphBlock(id = "b1", text = "Resumo do dia"),
                BulletListItemBlock(id = "b2", text = "Item marcador"),
                NumberedListItemBlock(id = "b3", text = "Primeiro passo"),
                NumberedListItemBlock(id = "b4", text = "Segundo passo"),
                ChecklistItemBlock(id = "b5", text = "Pendente", isChecked = false),
                ChecklistItemBlock(id = "b6", text = "Concluído", isChecked = true),
              )
          )
      )
    val state =
      NoteEditorUiState.error("ignored").copy(
        phase = NoteEditorSessionPhase.EDITING,
        title = "Minha Lista",
        editorState = engine.state,
        errorMessage = null,
      )

    val formatted = NoteEditorViewModel.formatNoteForSharing(state)
    assertEquals(
      """
      Minha Lista

      Resumo do dia
      • Item marcador
      1. Primeiro passo
      2. Segundo passo
      [ ] Pendente
      [x] Concluído
      """.trimIndent(),
      formatted,
    )
  }

  @Test
  fun `recover draft conflict supports SaveDraftAsNewNote cleanly`() {
    runBlocking {
      val existing =
        noteRepository.createNote(
          title = "Original",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "o1", text = "Versão 1"))),
        )
      val sessionId = "conflict-save-new"
      val draftId = NoteRecoveryDraftKeys.forExistingNote(existing.id, sessionId)
      draftRepository.saveCheckpoint(
        NoteRecoveryDraftInput(
          draftId = draftId,
          targetNoteId = existing.id,
          sessionId = sessionId,
          folderId = null,
          fallbackSourceFolderId = null,
          isPinned = false,
          title = "Rascunho em conflito",
          content =
            NoteContentDocument(blocks = listOf(ParagraphBlock(id = "d1", text = "Texto do rascunho"))),
          paperStyle = NotePaperStyle.LINHAS,
          baseRevision = existing.revision,
          checkpointRevision = 1L,
          baseNoteUpdatedAt = existing.updatedAt,
        )
      )
      // Avança a nota no DB para disparar conflito na recuperação
      noteRepository.updateNote(
        noteId = existing.id,
        title = "Atualizada externamente",
        content = existing.contentOrNull!!,
        folderId = existing.folderId,
        paperStyle = existing.paperStyle,
        expectedBaseRevision = existing.revision,
      )

      val vm =
        createViewModel(
          NoteEditorOpenRequest.RecoverDraft(
            draftId = draftId,
            expectedTarget = RecoveryTargetExpectation.ExistingNote(existing.id),
          )
        )
      awaitEditing(vm)
      assertNotNull(vm.uiState.value.activeConflict)

      vm.onResolveConflict(ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE)
      waitUntil { vm.uiState.value.activeConflict == null }

      val navDeferred = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeout(3_000L) { vm.events.first { it is NoteEditorUiEvent.NavigateBack } }
      }
      vm.onExitRequested()
      navDeferred.await()

      val activeNotes = noteRepository.observeAllActiveNotes().first()
      assertEquals(2, activeNotes.size)
      assertTrue(activeNotes.any { it.title == "Atualizada externamente" })
      assertTrue(activeNotes.any { it.title == "Rascunho em conflito" })
    }
  }

  @Test
  fun `enterEditMode stays read-only and emits no focus when SelectBlock submission is rejected`() {
    runBlocking {
      val note =
        noteRepository.createNote(
          title = "Leitura rejeitada",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "b1", text = "Texto"))),
        )
      val openReq = NoteEditorOpenRequest.OpenExisting(note.id)
      val delegateFactory = { req: NoteEditorOpenRequest, scope: CoroutineScope ->
        createRealCoordinator(req, scope)
      }
      var vm: NoteEditorViewModel? = null
      val interceptingCoordinator =
        InterceptingCoordinator(
          delegate = delegateFactory(openReq, kotlinx.coroutines.CoroutineScope(Dispatchers.Default)),
          onSubmit = { cmd ->
            if (cmd is SessionCommand.SelectBlock) {
              SubmitCommandResult.Rejected(SubmitRejectionReason.MAILBOX_CLOSED)
            } else {
              null
            }
          },
        )
      vm =
        NoteEditorViewModel(
          openRequest = openReq,
          coordinatorFactory = { _, _ -> interceptingCoordinator },
          folderRepository = folderRepository,
          noteRepository = noteRepository,
        )
      awaitEditing(vm)
      assertTrue(vm.isReadOnlyMode.value)

      val recordedEvents = mutableListOf<NoteEditorUiEvent>()
      val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
        vm.events.collect { recordedEvents.add(it) }
      }

      vm.enterEditMode(blockId = "b1", offset = 2)
      delay(80L)

      assertTrue(vm.isReadOnlyMode.value)
      assertFalse(recordedEvents.any { it is NoteEditorUiEvent.RequestFocusAndKeyboard })
      assertTrue(recordedEvents.any { it is NoteEditorUiEvent.ShowMessage })

      collectJob.cancel()
      interceptingCoordinator.closeAndAbort()
    }
  }

  @Test
  fun `enterEditMode stays read-only and emits message when SelectBlock fails or is cancelled`() {
    runBlocking {
      val note =
        noteRepository.createNote(
          title = "Leitura falha",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "b1", text = "Texto"))),
        )
      val openReq = NoteEditorOpenRequest.OpenExisting(note.id)
      var mode = "FAIL"
      val realCoord = createRealCoordinator(openReq, CoroutineScope(Dispatchers.Default))
      val intercepting =
        InterceptingCoordinator(
          delegate = realCoord,
          onSubmit = { cmd ->
            if (cmd is SessionCommand.SelectBlock) {
              when (mode) {
                "FAIL" -> {
                  cmd.ackDeferred?.complete(SessionCommandOutcome.Failed("Falha ao selecionar bloco"))
                  SubmitCommandResult.Accepted(null)
                }
                "CANCEL_OUTCOME" -> {
                  cmd.ackDeferred?.complete(SessionCommandOutcome.Cancelled)
                  SubmitCommandResult.Accepted(null)
                }
                "CANCEL_DEFERRED" -> {
                  cmd.ackDeferred?.cancel()
                  SubmitCommandResult.Accepted(null)
                }
                else -> null
              }
            } else {
              null
            }
          },
        )
      val vm =
        NoteEditorViewModel(
          openRequest = openReq,
          coordinatorFactory = { _, _ -> intercepting },
          folderRepository = folderRepository,
          noteRepository = noteRepository,
        )
      awaitEditing(vm)

      val recordedEvents = mutableListOf<NoteEditorUiEvent>()
      val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
        vm.events.collect { recordedEvents.add(it) }
      }

      vm.enterEditMode(blockId = "b1", offset = 1)
      waitUntil { recordedEvents.any { it is NoteEditorUiEvent.ShowMessage } }
      assertTrue(vm.isReadOnlyMode.value)
      assertFalse(recordedEvents.any { it is NoteEditorUiEvent.RequestFocusAndKeyboard })

      mode = "CANCEL_OUTCOME"
      vm.enterEditMode(blockId = "b1", offset = 1)
      delay(60L)
      assertTrue(vm.isReadOnlyMode.value)
      assertFalse(recordedEvents.any { it is NoteEditorUiEvent.RequestFocusAndKeyboard })

      mode = "CANCEL_DEFERRED"
      vm.enterEditMode(blockId = "b1", offset = 1)
      delay(60L)
      assertTrue(vm.isReadOnlyMode.value)
      assertFalse(recordedEvents.any { it is NoteEditorUiEvent.RequestFocusAndKeyboard })

      collectJob.cancel()
      intercepting.closeAndAbort()
    }
  }

  @Test
  fun `onConfirmDelete on existing note does not abort or soft-delete when DiscardDraftAndClose is rejected and allows retry`() {
    runBlocking {
      val note = noteRepository.createNote(title = "Preservada na rejeição")
      val openReq = NoteEditorOpenRequest.OpenExisting(note.id)
      val rejectDiscard = AtomicBoolean(true)
      val realCoord = createRealCoordinator(openReq, CoroutineScope(Dispatchers.Default))
      val intercepting =
        InterceptingCoordinator(
          delegate = realCoord,
          onSubmit = { cmd ->
            if (cmd is SessionCommand.DiscardDraftAndClose && rejectDiscard.get()) {
              SubmitCommandResult.Rejected(SubmitRejectionReason.MAILBOX_CLOSED)
            } else {
              null
            }
          },
        )
      val vm =
        NoteEditorViewModel(
          openRequest = openReq,
          coordinatorFactory = { _, _ -> intercepting },
          folderRepository = folderRepository,
          noteRepository = noteRepository,
        )
      awaitEditing(vm)

      val recordedEvents = mutableListOf<NoteEditorUiEvent>()
      val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
        vm.events.collect { recordedEvents.add(it) }
      }

      vm.onConfirmDelete()
      waitUntil { !vm.isBusyTransition.value && recordedEvents.any { it is NoteEditorUiEvent.ShowMessage } }

      assertEquals(0, intercepting.closeAndAbortCalls.get())
      assertFalse(noteRepository.getNoteById(note.id)!!.isDeleted)
      assertEquals(1, noteRepository.observeAllActiveNotes().first().size)
      assertFalse(recordedEvents.any { it is NoteEditorUiEvent.NavigateBack })

      // Nova tentativa sem reabrir diálogo adicional e após cessar a rejeição deve concluir normalmente
      rejectDiscard.set(false)
      vm.onConfirmDelete()
      waitUntil { recordedEvents.any { it is NoteEditorUiEvent.NavigateBack } }

      assertTrue(noteRepository.getNoteById(note.id)!!.isDeleted)
      assertTrue(noteRepository.observeAllActiveNotes().first().isEmpty())
      collectJob.cancel()
    }
  }

  @Test
  fun `onConfirmDelete on existing note keeps note intact on structural DiscardDraftAndClose failure and allows retry`() {
    runBlocking {
      val note = noteRepository.createNote(title = "Falha estrutural no descarte")
      val failDelete = AtomicBoolean(true)
      val faultyDraftRepo =
        object : NoteRecoveryDraftRepository by draftRepository {
          override suspend fun deleteCheckpoint(draftId: String) {
            if (failDelete.get()) {
              throw IOException("Erro simulado ao limpar rascunho")
            }
            draftRepository.deleteCheckpoint(draftId)
          }
        }
      val customFactory =
        AnoteVoceViewModelFactory(
          folderRepository = folderRepository,
          noteRepository = noteRepository,
          preferencesRepository = preferencesRepository,
          noteRecoveryDraftRepository = faultyDraftRepo,
          dispatchers = ioDispatchers,
          clock = clock,
          autosaveDebounceMillis = 40L,
        )
      val vm =
        customFactory
          .editorFactory(NoteEditorOpenRequest.OpenExisting(note.id))
          .create(NoteEditorViewModel::class.java)
      awaitEditing(vm)

      val recordedEvents = mutableListOf<NoteEditorUiEvent>()
      val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
        vm.events.collect { recordedEvents.add(it) }
      }

      vm.onConfirmDelete()
      waitUntil { !vm.isBusyTransition.value && recordedEvents.any { it is NoteEditorUiEvent.ShowMessage } }

      assertFalse(noteRepository.getNoteById(note.id)!!.isDeleted)
      assertEquals(1, noteRepository.observeAllActiveNotes().first().size)
      assertFalse(recordedEvents.any { it is NoteEditorUiEvent.NavigateBack })

      failDelete.set(false)
      vm.onConfirmDelete()
      waitUntil { recordedEvents.any { it is NoteEditorUiEvent.NavigateBack } }

      assertTrue(noteRepository.getNoteById(note.id)!!.isDeleted)
      collectJob.cancel()
    }
  }

  @Test
  fun `onConfirmDelete handles empty new note failure without navigating back and handles new note with content`() {
    runBlocking {
      // Caso 1: nova nota vazia com falha estrutural ao descartar -> não navega de volta e exibe erro
      val failDelete = AtomicBoolean(true)
      val faultyDraftRepo =
        object : NoteRecoveryDraftRepository by draftRepository {
          override suspend fun deleteCheckpoint(draftId: String) {
            if (failDelete.get()) throw IOException("Falha ao descartar rascunho vazio")
            draftRepository.deleteCheckpoint(draftId)
          }
        }
      val faultyFactory =
        AnoteVoceViewModelFactory(
          folderRepository = folderRepository,
          noteRepository = noteRepository,
          preferencesRepository = preferencesRepository,
          noteRecoveryDraftRepository = faultyDraftRepo,
          dispatchers = ioDispatchers,
          clock = clock,
          autosaveDebounceMillis = 40L,
        )
      val emptyVm =
        faultyFactory
          .editorFactory(NoteEditorOpenRequest.CreateNew(sessionId = "empty-fail"))
          .create(NoteEditorViewModel::class.java)
      awaitEditing(emptyVm)

      val emptyEvents = mutableListOf<NoteEditorUiEvent>()
      val emptyCollect = launch(start = CoroutineStart.UNDISPATCHED) {
        emptyVm.events.collect { emptyEvents.add(it) }
      }
      emptyVm.onConfirmDelete()
      waitUntil { !emptyVm.isBusyTransition.value && emptyEvents.any { it is NoteEditorUiEvent.ShowMessage } }
      assertFalse(emptyEvents.any { it is NoteEditorUiEvent.NavigateBack })

      failDelete.set(false)
      emptyVm.onConfirmDelete()
      waitUntil { emptyEvents.any { it is NoteEditorUiEvent.NavigateBack } }
      emptyCollect.cancel()

      // Caso 2: nova nota com conteúdo consolida e move para Apagados após confirmação de CloseSession
      val contentVm = createViewModel(NoteEditorOpenRequest.CreateNew(sessionId = "new-content-del"))
      awaitEditing(contentVm)
      contentVm.onTitleChanged("Nova para lixeira")
      contentVm.onEmptyDocumentTextChanged("Conteúdo temporário")
      waitUntil { contentVm.uiState.value.title == "Nova para lixeira" }

      val navDeferred = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeout(3_000L) { contentVm.events.first { it is NoteEditorUiEvent.NavigateBack } }
      }
      contentVm.onConfirmDelete()
      navDeferred.await()

      assertTrue(noteRepository.observeAllActiveNotes().first().isEmpty())
      val deletedNotes = noteRepository.observeDeletedNotes().first()
      assertEquals(1, deletedNotes.size)
      assertEquals("Nova para lixeira", deletedNotes.single().title)
    }
  }

  @Test
  fun `onConfirmDelete on new note with content does not delete or navigate when CloseSession is rejected`() {
    runBlocking {
      val openReq = NoteEditorOpenRequest.CreateNew(sessionId = "new-close-rej")
      val rejectClose = AtomicBoolean(true)
      val realCoord = createRealCoordinator(openReq, CoroutineScope(Dispatchers.Default))
      val intercepting =
        InterceptingCoordinator(
          delegate = realCoord,
          onSubmit = { cmd ->
            if (cmd is SessionCommand.CloseSession && rejectClose.get()) {
              SubmitCommandResult.Rejected(SubmitRejectionReason.MAILBOX_CLOSED)
            } else {
              null
            }
          },
        )
      val vm =
        NoteEditorViewModel(
          openRequest = openReq,
          coordinatorFactory = { _, _ -> intercepting },
          folderRepository = folderRepository,
          noteRepository = noteRepository,
        )
      awaitEditing(vm)
      vm.onTitleChanged("Nota criada antes do CloseSession")
      waitUntil { vm.uiState.value.title == "Nota criada antes do CloseSession" }

      val events = mutableListOf<NoteEditorUiEvent>()
      val job = launch(start = CoroutineStart.UNDISPATCHED) { vm.events.collect { events.add(it) } }

      vm.onConfirmDelete()
      waitUntil { !vm.isBusyTransition.value && events.any { it is NoteEditorUiEvent.ShowMessage } }

      assertEquals(0, intercepting.closeAndAbortCalls.get())
      assertFalse(events.any { it is NoteEditorUiEvent.NavigateBack })
      val activeNotes = noteRepository.observeAllActiveNotes().first()
      assertEquals(1, activeNotes.size)
      assertFalse(activeNotes.single().isDeleted)

      job.cancel()
      intercepting.closeAndAbort()
    }
  }

  @Test
  fun `PersistExistingNotePin detects concurrent update between read and pin update without advancing baseline`() {
    runBlocking {
      val initial =
        noteRepository.createNote(
          title = "Título base",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Texto base"))),
          isPinned = false,
        )

      val injectConcurrentUpdate = AtomicBoolean(true)
      val racingRepo =
        object : NoteRepository by noteRepository {
          override suspend fun setNotePinnedIfRevision(
            noteId: Long,
            pinned: Boolean,
            expectedBaseRevision: Long,
          ): PinNoteResult {
            if (injectConcurrentUpdate.compareAndSet(true, false)) {
              noteRepository.updateNote(
                noteId = noteId,
                title = "Título concorrente",
                content =
                  NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Texto concorrente"))),
                folderId = null,
                paperStyle = NotePaperStyle.LINHAS,
                expectedBaseRevision = expectedBaseRevision,
              )
            }
            return noteRepository.setNotePinnedIfRevision(noteId, pinned, expectedBaseRevision)
          }
        }

      val racingFactory =
        AnoteVoceViewModelFactory(
          folderRepository = folderRepository,
          noteRepository = racingRepo,
          preferencesRepository = preferencesRepository,
          noteRecoveryDraftRepository = draftRepository,
          dispatchers = ioDispatchers,
          clock = clock,
          autosaveDebounceMillis = 40L,
        )
      val vm =
        racingFactory
          .editorFactory(NoteEditorOpenRequest.OpenExisting(initial.id))
          .create(NoteEditorViewModel::class.java)
      awaitEditing(vm)

      vm.enterEditMode(blockId = "p1", offset = 10)
      waitUntil { !vm.isReadOnlyMode.value }
      vm.onBlockTextChanged("p1", "Edição local em memória", 23, 23)
      waitUntil { vm.uiState.value.editorState.document.blocks.first().text == "Edição local em memória" }

      vm.onTogglePin()
      waitUntil { vm.uiState.value.activeConflict != null }

      val state = vm.uiState.value
      assertNotNull(state.activeConflict)
      assertEquals(initial.revision, (state.baseline as NoteEditorBaseline.ExistingNote).baseRevision)
      assertFalse(state.isPinned)
      assertEquals("Edição local em memória", state.editorState.document.blocks.first().text)

      val stored = noteRepository.getNoteById(initial.id)!!
      assertFalse(stored.isPinned)
      assertEquals(initial.revision + 1L, stored.revision)
      assertEquals("Título concorrente", stored.title)

      vm.onResolveConflict(ConflictResolutionOption.DISCARD_DRAFT_USE_CURRENT_NOTE)
      waitUntil { vm.uiState.value.activeConflict == null }
      val navDeferred = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeout(3_000L) { vm.events.first { it is NoteEditorUiEvent.NavigateBack } }
      }
      vm.onExitRequested()
      navDeferred.await()
    }
  }

  private fun createRealCoordinator(
    request: NoteEditorOpenRequest,
    parentScope: CoroutineScope,
  ): NoteEditorSessionCoordinator {
    val registry = ActiveSessionRegistry()
    val handoff = ReplacementHandoffTracker(clock = clock)
    return NoteEditorSessionCoordinator(
      openRequest = request,
      sessionOpenLoader =
        SessionOpenLoader(
          noteRepository = noteRepository,
          folderRepository = folderRepository,
          draftRepository = draftRepository,
          preferencesRepository = preferencesRepository,
          activeSessionRegistry = registry,
          handoffTracker = handoff,
        ),
      replacementReconciler =
        PendingReplacementReconciler(
          noteRepository = noteRepository,
          draftRepository = draftRepository,
          activeSessionRegistry = registry,
          handoffTracker = handoff,
        ),
      handoffTracker = handoff,
      activeSessionRegistry = registry,
      draftRepository = draftRepository,
      noteRepository = noteRepository,
      folderRepository = folderRepository,
      dispatchers = ioDispatchers,
      clock = clock,
      parentScope = parentScope,
      autosaveDebounceMillis = 40L,
    )
  }

  private class InterceptingCoordinator(
    private val delegate: EditorSessionCoordinator,
    private val onSubmit: (SessionCommand) -> SubmitCommandResult?,
  ) : EditorSessionCoordinator {
    val closeAndAbortCalls = AtomicInteger(0)

    override val uiState: StateFlow<NoteEditorUiState>
      get() = delegate.uiState

    override fun start(): Job = delegate.start()

    override fun submit(command: SessionCommand): SubmitCommandResult =
      onSubmit(command) ?: delegate.submit(command)

    override suspend fun awaitInitialized(): NoteEditorUiState = delegate.awaitInitialized()

    override suspend fun closeAndAbort() {
      closeAndAbortCalls.incrementAndGet()
      delegate.closeAndAbort()
    }
  }
}
