package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.editor.ConflictResolutionOption
import com.example.domain.editor.ConsolidationReadiness
import com.example.domain.editor.NoteEditorOpenRequest
import com.example.domain.model.BulletListItemBlock
import com.example.domain.model.ChecklistItemBlock
import com.example.domain.model.Folder
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.NumberedListItemBlock
import com.example.domain.model.ParagraphBlock
import com.example.domain.model.TextFormatStyle
import com.example.domain.repository.FolderRepository
import com.example.domain.repository.NoteRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface NoteEditorUiEvent {
  data object NavigateBack : NoteEditorUiEvent

  data class ShareNote(val subject: String, val text: String) : NoteEditorUiEvent

  data class ShowMessage(val message: String) : NoteEditorUiEvent

  data class RequestFocusAndKeyboard(val blockId: String?, val offset: Int) : NoteEditorUiEvent
}

class NoteEditorViewModel(
  private val openRequest: NoteEditorOpenRequest,
  coordinatorFactory: (NoteEditorOpenRequest, CoroutineScope) -> EditorSessionCoordinator,
  folderRepository: FolderRepository,
  private val noteRepository: NoteRepository,
) : ViewModel() {

  private val coordinator: EditorSessionCoordinator =
    coordinatorFactory(openRequest, viewModelScope)

  val uiState: StateFlow<NoteEditorUiState> = coordinator.uiState

  val activeFolders: StateFlow<List<Folder>> =
    folderRepository
      .observeActiveFolders()
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = emptyList(),
      )

  val isRecoveredDraftSession: Boolean = openRequest is NoteEditorOpenRequest.RecoverDraft

  private val initialReadOnly =
    when (openRequest) {
      is NoteEditorOpenRequest.CreateNew -> false
      is NoteEditorOpenRequest.OpenExisting -> true
      is NoteEditorOpenRequest.RecoverDraft -> true
    }

  private val _isReadOnlyMode = MutableStateFlow(initialReadOnly)
  val isReadOnlyMode: StateFlow<Boolean> = _isReadOnlyMode.asStateFlow()

  private val _showFolderPicker = MutableStateFlow(false)
  val showFolderPicker: StateFlow<Boolean> = _showFolderPicker.asStateFlow()

  private val _showPaperPicker = MutableStateFlow(false)
  val showPaperPicker: StateFlow<Boolean> = _showPaperPicker.asStateFlow()

  private val _showDeleteConfirmDialog = MutableStateFlow(false)
  val showDeleteConfirmDialog: StateFlow<Boolean> = _showDeleteConfirmDialog.asStateFlow()

  private val _showFallbackConfirmDialog = MutableStateFlow(false)
  val showFallbackConfirmDialog: StateFlow<Boolean> = _showFallbackConfirmDialog.asStateFlow()

  private val _isBusyTransition = MutableStateFlow(false)
  val isBusyTransition: StateFlow<Boolean> = _isBusyTransition.asStateFlow()

  private val _events = MutableSharedFlow<NoteEditorUiEvent>(extraBufferCapacity = 16)
  val events: SharedFlow<NoteEditorUiEvent> = _events.asSharedFlow()

  private var didRequestInitialFocus = false

  init {
    coordinator.start()
    viewModelScope.launch {
      val opened = runCatching { coordinator.awaitInitialized() }.getOrNull() ?: return@launch
      if (opened.phase == NoteEditorSessionPhase.EDITING && !opened.isSessionTerminated) {
        if (openRequest is NoteEditorOpenRequest.CreateNew && !didRequestInitialFocus) {
          didRequestInitialFocus = true
          _isReadOnlyMode.value = false
          val targetBlockId = opened.editorState.selection?.blockId
          _events.tryEmit(NoteEditorUiEvent.RequestFocusAndKeyboard(targetBlockId, 0))
        } else if (
          openRequest is NoteEditorOpenRequest.RecoverDraft &&
            !opened.isExistingNote &&
            opened.activeConflict == null
        ) {
          _isReadOnlyMode.value = false
        }
        folderRepository.observeActiveFolders().collect {
          val state = uiState.value
          if (state.phase != NoteEditorSessionPhase.EDITING || state.isSessionTerminated) {
            return@collect
          }
          val trackedIds =
            setOfNotNull(
              state.effectiveFolderId,
              state.fallbackSourceFolderId,
              state.intendedUserFolderId,
            )
          if (trackedIds.isEmpty()) return@collect
          val updates = folderRepository.getFoldersByIds(trackedIds)
          if (updates.isNotEmpty()) {
            coordinator.submit(SessionCommand.FolderAvailabilityObserved(updates))
          }
        }
      }
    }
  }

  fun enterEditMode(blockId: String? = null, offset: Int? = null) {
    val state = uiState.value
    if (!state.canEdit || state.activeConflict != null) return

    val docBlocks = state.editorState.document.blocks
    if (docBlocks.isEmpty()) {
      _isReadOnlyMode.value = false
      _events.tryEmit(NoteEditorUiEvent.RequestFocusAndKeyboard(blockId = null, offset = 0))
      return
    }

    val targetBlock =
      if (blockId != null) {
        docBlocks.firstOrNull { it.id == blockId } ?: return
      } else {
        docBlocks.last()
      }

    val resolvedBlockId = targetBlock.id
    val resolvedOffset = (offset ?: targetBlock.text.length).coerceIn(0, targetBlock.text.length)

    val ack = CompletableDeferred<SessionCommandOutcome>()
    val submitted =
      coordinator.submit(
        SessionCommand.SelectBlock(resolvedBlockId, resolvedOffset, resolvedOffset, ack)
      )
    when (submitted) {
      is SubmitCommandResult.Accepted -> {
        viewModelScope.launch {
          val outcome =
            try {
              ack.await()
            } catch (c: CancellationException) {
              throw c
            } catch (t: Throwable) {
              _events.emit(
                NoteEditorUiEvent.ShowMessage(
                  t.message ?: "Não foi possível entrar no modo de edição."
                )
              )
              return@launch
            }

          when (outcome) {
            SessionCommandOutcome.Completed -> {
              val latest = uiState.value
              if (latest.canEdit && latest.activeConflict == null) {
                _isReadOnlyMode.value = false
                _events.emit(
                  NoteEditorUiEvent.RequestFocusAndKeyboard(resolvedBlockId, resolvedOffset)
                )
              }
            }
            is SessionCommandOutcome.Failed -> {
              _events.emit(NoteEditorUiEvent.ShowMessage(outcome.message))
            }
            is SessionCommandOutcome.Rejected,
            SessionCommandOutcome.Cancelled -> Unit
          }
        }
      }
      is SubmitCommandResult.Rejected -> {
        if (!uiState.value.isSessionTerminated) {
          _events.tryEmit(
            NoteEditorUiEvent.ShowMessage("Não foi possível entrar no modo de edição.")
          )
        }
      }
    }
  }

  fun onTitleChanged(newTitle: String) {
    if (uiState.value.canEdit) {
      coordinator.submit(SessionCommand.EditTitle(newTitle))
    }
  }

  fun onEmptyDocumentTextChanged(newText: String) {
    if (uiState.value.canEdit && newText.isNotEmpty()) {
      coordinator.submit(SessionCommand.InsertText(newText))
    }
  }

  fun onBlockTextChanged(
    blockId: String,
    newText: String,
    selectionStart: Int,
    selectionEnd: Int,
  ) {
    if (uiState.value.canEdit) {
      coordinator.submit(
        SessionCommand.ApplyTextChange(
          blockId = blockId,
          newText = newText,
          selectionStart = selectionStart.coerceAtLeast(0),
          selectionEnd = selectionEnd.coerceAtLeast(0),
        )
      )
    }
  }

  fun onBlockSelectionChanged(blockId: String, start: Int, end: Int) {
    if (uiState.value.canEdit && blockId.isNotBlank()) {
      val s = minOf(start, end).coerceAtLeast(0)
      val e = maxOf(start, end).coerceAtLeast(s)
      coordinator.submit(SessionCommand.SelectBlock(blockId, s, e))
    }
  }

  fun onEnterPressed() {
    if (uiState.value.canEdit) {
      coordinator.submit(SessionCommand.PressEnter)
    }
  }

  fun onBackspacePressed() {
    if (uiState.value.canEdit) {
      coordinator.submit(SessionCommand.PressBackspace)
    }
  }

  fun onToggleFormat(style: TextFormatStyle) {
    if (uiState.value.canEdit) {
      coordinator.submit(SessionCommand.ToggleFormat(style))
    }
  }

  fun onToggleBulletList() {
    if (uiState.value.canEdit) {
      coordinator.submit(SessionCommand.ToggleBulletList)
    }
  }

  fun onToggleNumberedList() {
    if (uiState.value.canEdit) {
      coordinator.submit(SessionCommand.ToggleNumberedList)
    }
  }

  fun onToggleChecklist() {
    if (uiState.value.canEdit) {
      coordinator.submit(SessionCommand.ToggleChecklist)
    }
  }

  fun onToggleChecklistItem(blockId: String) {
    if (uiState.value.canEdit && uiState.value.activeConflict == null) {
      coordinator.submit(SessionCommand.ToggleChecklistItem(blockId))
    }
  }

  fun onUndo() {
    if (uiState.value.canEdit && uiState.value.editorState.canUndo) {
      coordinator.submit(SessionCommand.Undo)
    }
  }

  fun onRedo() {
    if (uiState.value.canEdit && uiState.value.editorState.canRedo) {
      coordinator.submit(SessionCommand.Redo)
    }
  }

  fun onOpenFolderPicker() {
    if (!uiState.value.isExistingNote && uiState.value.canEdit) {
      _showFolderPicker.value = true
    }
  }

  fun onDismissFolderPicker() {
    _showFolderPicker.value = false
  }

  fun onSelectDestinationFolder(folderId: Long?) {
    _showFolderPicker.value = false
    if (!uiState.value.isExistingNote && uiState.value.canEdit) {
      coordinator.submit(SessionCommand.ChangeFolder(folderId))
    }
  }

  fun onConfirmFallbackToRootInline() {
    if (uiState.value.canEdit) {
      coordinator.submit(SessionCommand.ConfirmFallbackToRoot)
    }
  }

  fun onOpenPaperPicker() {
    if (uiState.value.canEdit) {
      _showPaperPicker.value = true
    }
  }

  fun onDismissPaperPicker() {
    _showPaperPicker.value = false
  }

  fun onSelectPaperStyle(paperStyle: NotePaperStyle) {
    _showPaperPicker.value = false
    if (uiState.value.canEdit) {
      coordinator.submit(SessionCommand.ChangePaperStyle(paperStyle))
    }
  }

  fun onTogglePin() {
    val state = uiState.value
    if (!state.canEdit || state.activeConflict != null) return

    if (!state.isExistingNote) {
      coordinator.submit(SessionCommand.TogglePinned)
      return
    }

    viewModelScope.launch {
      val ack = CompletableDeferred<SessionCommandOutcome>()
      val submitRes =
        coordinator.submit(
          SessionCommand.PersistExistingNotePin(isPinned = !state.isPinned, ackDeferred = ack)
        )
      if (submitRes is SubmitCommandResult.Accepted) {
        val outcome = ack.await()
        if (outcome is SessionCommandOutcome.Failed && uiState.value.activeConflict == null) {
          _events.emit(NoteEditorUiEvent.ShowMessage(outcome.message))
        }
      }
    }
  }

  fun onShareClicked() {
    val state = uiState.value
    if (state.phase != NoteEditorSessionPhase.EDITING) return
    val shareText = formatNoteForSharing(state)
    _events.tryEmit(NoteEditorUiEvent.ShareNote(subject = state.title, text = shareText))
  }

  fun onDeleteClicked() {
    if (uiState.value.phase == NoteEditorSessionPhase.EDITING && !_isBusyTransition.value) {
      _showDeleteConfirmDialog.value = true
    }
  }

  fun onDismissDeleteDialog() {
    _showDeleteConfirmDialog.value = false
  }

  fun onConfirmDelete() {
    if (_isBusyTransition.value) return
    _showDeleteConfirmDialog.value = false

    viewModelScope.launch {
      _isBusyTransition.value = true
      try {
        val state = uiState.value
        val existingId = state.sessionIdentity?.targetNoteId

        if (existingId != null) {
          val discardAck = CompletableDeferred<SessionCommandOutcome>()
          val submitted = coordinator.submit(SessionCommand.DiscardDraftAndClose(discardAck))
          when (submitted) {
            is SubmitCommandResult.Rejected -> {
              _events.emit(
                NoteEditorUiEvent.ShowMessage(
                  "Não foi possível excluir a nota no estado atual da sessão. Tente novamente."
                )
              )
            }
            is SubmitCommandResult.Accepted -> {
              when (val outcome = discardAck.await()) {
                SessionCommandOutcome.Completed -> {
                  noteRepository.softDeleteNote(existingId)
                  _events.emit(NoteEditorUiEvent.NavigateBack)
                }
                is SessionCommandOutcome.Failed -> {
                  _events.emit(NoteEditorUiEvent.ShowMessage(outcome.message))
                }
                is SessionCommandOutcome.Rejected -> {
                  _events.emit(
                    NoteEditorUiEvent.ShowMessage(
                      "A sessão rejeitou a preparação para excluir a nota. Tente novamente."
                    )
                  )
                }
                SessionCommandOutcome.Cancelled -> {
                  _events.emit(
                    NoteEditorUiEvent.ShowMessage(
                      "A exclusão da nota foi cancelada antes da conclusão."
                    )
                  )
                }
              }
            }
          }
          return@launch
        }

        // Nova nota ainda não consolidada e completamente vazia: descarta rascunho somente após confirmação
        if (state.isCompletelyEmptyNote()) {
          val discardAck = CompletableDeferred<SessionCommandOutcome>()
          val submitted = coordinator.submit(SessionCommand.DiscardDraftAndClose(discardAck))
          when (submitted) {
            is SubmitCommandResult.Rejected -> {
              _events.emit(
                NoteEditorUiEvent.ShowMessage(
                  "Não foi possível descartar a nota vazia no momento. Tente novamente."
                )
              )
            }
            is SubmitCommandResult.Accepted -> {
              when (val outcome = discardAck.await()) {
                SessionCommandOutcome.Completed -> {
                  _events.emit(NoteEditorUiEvent.NavigateBack)
                }
                is SessionCommandOutcome.Failed -> {
                  _events.emit(NoteEditorUiEvent.ShowMessage(outcome.message))
                }
                is SessionCommandOutcome.Rejected -> {
                  _events.emit(
                    NoteEditorUiEvent.ShowMessage(
                      "Não foi possível descartar a nota vazia no estado atual da sessão."
                    )
                  )
                }
                SessionCommandOutcome.Cancelled -> {
                  _events.emit(
                    NoteEditorUiEvent.ShowMessage(
                      "O descarte da nota vazia foi cancelado antes da conclusão."
                    )
                  )
                }
              }
            }
          }
          return@launch
        }

        // Nova nota com conteúdo: consolida primeiro e, após encerramento seguro, move para Apagados
        val allowFallbackForDelete =
          when (val readiness = state.consolidationReadiness) {
            is ConsolidationReadiness.Ready -> false
            is ConsolidationReadiness.RequiresExplicitFolderConfirmation -> true
            is ConsolidationReadiness.BlockedByUnknownFolderState -> {
              _events.emit(
                NoteEditorUiEvent.ShowMessage(
                  readiness.reason ?: "Verificando disponibilidade da pasta. Tente novamente."
                )
              )
              return@launch
            }
            is ConsolidationReadiness.BlockedByConflict -> {
              _events.emit(
                NoteEditorUiEvent.ShowMessage("Resolva o conflito ativo antes de excluir a nota.")
              )
              return@launch
            }
            is ConsolidationReadiness.BlockedBySessionState,
            null -> {
              _events.emit(
                NoteEditorUiEvent.ShowMessage(
                  "Não é possível excluir a nota no estado atual da sessão."
                )
              )
              return@launch
            }
          }

        val consolidateAck = CompletableDeferred<SessionCommandOutcome>()
        val consolidatedSubmitted =
          coordinator.submit(
            SessionCommand.ConsolidateNote(
              allowFallbackToRoot = allowFallbackForDelete,
              ackDeferred = consolidateAck,
            )
          )
        when (consolidatedSubmitted) {
          is SubmitCommandResult.Rejected -> {
            _events.emit(
              NoteEditorUiEvent.ShowMessage(
                "Não foi possível preparar a exclusão da nota no momento. Tente novamente."
              )
            )
          }
          is SubmitCommandResult.Accepted -> {
            when (val outcome = consolidateAck.await()) {
              SessionCommandOutcome.Completed -> {
                val createdId = uiState.value.sessionIdentity?.targetNoteId
                if (createdId == null) {
                  _events.emit(
                    NoteEditorUiEvent.ShowMessage(
                      "Não foi possível identificar a nota consolidada para exclusão."
                    )
                  )
                  return@launch
                }
                val closeAck = CompletableDeferred<SessionCommandOutcome>()
                when (coordinator.submit(SessionCommand.CloseSession(closeAck))) {
                  is SubmitCommandResult.Rejected -> {
                    _events.emit(
                      NoteEditorUiEvent.ShowMessage(
                        "Não foi possível encerrar a sessão com segurança para excluir a nota."
                      )
                    )
                  }
                  is SubmitCommandResult.Accepted -> {
                    when (val closeOutcome = closeAck.await()) {
                      SessionCommandOutcome.Completed -> {
                        noteRepository.softDeleteNote(createdId)
                        _events.emit(NoteEditorUiEvent.NavigateBack)
                      }
                      is SessionCommandOutcome.Failed -> {
                        _events.emit(NoteEditorUiEvent.ShowMessage(closeOutcome.message))
                      }
                      is SessionCommandOutcome.Rejected -> {
                        _events.emit(
                          NoteEditorUiEvent.ShowMessage(
                            "A sessão rejeitou o encerramento antes da exclusão."
                          )
                        )
                      }
                      SessionCommandOutcome.Cancelled -> {
                        _events.emit(
                          NoteEditorUiEvent.ShowMessage(
                            "O encerramento da sessão foi cancelado antes da exclusão."
                          )
                        )
                      }
                    }
                  }
                }
              }
              is SessionCommandOutcome.Failed -> {
                _events.emit(NoteEditorUiEvent.ShowMessage(outcome.message))
              }
              is SessionCommandOutcome.Rejected -> {
                _events.emit(
                  NoteEditorUiEvent.ShowMessage("A sessão rejeitou a operação de exclusão.")
                )
              }
              SessionCommandOutcome.Cancelled -> {
                _events.emit(
                  NoteEditorUiEvent.ShowMessage("A exclusão da nota foi cancelada.")
                )
              }
            }
          }
        }
      } catch (c: CancellationException) {
        throw c
      } catch (t: Throwable) {
        _events.emit(NoteEditorUiEvent.ShowMessage(t.message ?: "Não foi possível excluir a nota."))
      } finally {
        _isBusyTransition.value = false
      }
    }
  }

  fun onResolveConflict(option: ConflictResolutionOption) {
    if (_isBusyTransition.value) return
    viewModelScope.launch {
      _isBusyTransition.value = true
      try {
        val ack = CompletableDeferred<SessionCommandOutcome>()
        val submitRes = coordinator.submit(SessionCommand.ResolveConflict(option, ack))
        if (submitRes is SubmitCommandResult.Accepted) {
          val outcome = ack.await()
          if (outcome is SessionCommandOutcome.Failed) {
            _events.emit(NoteEditorUiEvent.ShowMessage(outcome.message))
          }
        }
      } finally {
        _isBusyTransition.value = false
      }
    }
  }

  fun onConfirmFallbackSaveToRootAndExit() {
    _showFallbackConfirmDialog.value = false
    if (_isBusyTransition.value) return
    viewModelScope.launch {
      _isBusyTransition.value = true
      try {
        val consolidateAck = CompletableDeferred<SessionCommandOutcome>()
        val sub =
          coordinator.submit(
            SessionCommand.ConsolidateNote(allowFallbackToRoot = true, ackDeferred = consolidateAck)
          )
        if (sub is SubmitCommandResult.Accepted) {
          when (val outcome = consolidateAck.await()) {
            SessionCommandOutcome.Completed -> closeAndNavigateBack()
            is SessionCommandOutcome.Failed -> _events.emit(NoteEditorUiEvent.ShowMessage(outcome.message))
            else -> Unit
          }
        }
      } finally {
        _isBusyTransition.value = false
      }
    }
  }

  fun onChooseFolderFromFallbackDialog() {
    _showFallbackConfirmDialog.value = false
    _showFolderPicker.value = true
  }

  fun onDismissFallbackDialog() {
    _showFallbackConfirmDialog.value = false
  }

  fun onAppBackgrounded() {
    val state = uiState.value
    if (
      state.phase == NoteEditorSessionPhase.EDITING &&
        !state.isSessionTerminated &&
        state.activeConflict == null
    ) {
      coordinator.submit(SessionCommand.FlushAutosave())
    }
  }

  fun onExitRequested() {
    if (_showDeleteConfirmDialog.value) {
      _showDeleteConfirmDialog.value = false
      return
    }
    if (_showFallbackConfirmDialog.value) {
      _showFallbackConfirmDialog.value = false
      return
    }
    if (_showPaperPicker.value) {
      _showPaperPicker.value = false
      return
    }
    if (_showFolderPicker.value) {
      _showFolderPicker.value = false
      return
    }
    if (_isBusyTransition.value) return

    viewModelScope.launch {
      _isBusyTransition.value = true
      try {
        val state = uiState.value
        if (
          state.isSessionTerminated ||
            state.phase == NoteEditorSessionPhase.BLOCKED ||
            state.phase == NoteEditorSessionPhase.ERROR ||
            state.phase == NoteEditorSessionPhase.LOADING
        ) {
          coordinator.closeAndAbort()
          _events.emit(NoteEditorUiEvent.NavigateBack)
          return@launch
        }

        if (state.activeConflict != null) {
          // Não descarta silenciosamente edições em conflito; preserva o usuário na escolha explícita.
          return@launch
        }

        if (!state.isExistingNote && state.isCompletelyEmptyNote()) {
          val discardAck = CompletableDeferred<SessionCommandOutcome>()
          when (coordinator.submit(SessionCommand.DiscardDraftAndClose(discardAck))) {
            is SubmitCommandResult.Rejected -> {
              _events.emit(
                NoteEditorUiEvent.ShowMessage("Não foi possível descartar a nota vazia no momento.")
              )
            }
            is SubmitCommandResult.Accepted -> {
              when (val outcome = discardAck.await()) {
                SessionCommandOutcome.Completed -> _events.emit(NoteEditorUiEvent.NavigateBack)
                is SessionCommandOutcome.Failed ->
                  _events.emit(NoteEditorUiEvent.ShowMessage(outcome.message))
                is SessionCommandOutcome.Rejected ->
                  _events.emit(
                    NoteEditorUiEvent.ShowMessage(
                      "Não foi possível descartar a nota vazia no estado atual da sessão."
                    )
                  )
                SessionCommandOutcome.Cancelled ->
                  _events.emit(
                    NoteEditorUiEvent.ShowMessage("O descarte da nota vazia foi cancelado.")
                  )
              }
            }
          }
          return@launch
        }

        if (!state.hasUnsavedChanges && !state.hasPendingFolderFallback && !state.isLegacyIndeterminate) {
          closeAndNavigateBack()
          return@launch
        }

        when (val readiness = state.consolidationReadiness) {
          is ConsolidationReadiness.Ready -> {
            val consolidateAck = CompletableDeferred<SessionCommandOutcome>()
            val submitted =
              coordinator.submit(
                SessionCommand.ConsolidateNote(
                  allowFallbackToRoot = false,
                  ackDeferred = consolidateAck,
                )
              )
            if (submitted is SubmitCommandResult.Accepted) {
              when (val outcome = consolidateAck.await()) {
                SessionCommandOutcome.Completed -> closeAndNavigateBack()
                is SessionCommandOutcome.Failed -> {
                  val latest = uiState.value
                  if (latest.consolidationReadiness is ConsolidationReadiness.RequiresExplicitFolderConfirmation) {
                    _showFallbackConfirmDialog.value = true
                  } else if (latest.activeConflict == null) {
                    _events.emit(NoteEditorUiEvent.ShowMessage(outcome.message))
                  }
                }
                else -> Unit
              }
            }
          }
          is ConsolidationReadiness.RequiresExplicitFolderConfirmation -> {
            _showFallbackConfirmDialog.value = true
          }
          is ConsolidationReadiness.BlockedByUnknownFolderState -> {
            coordinator.submit(SessionCommand.FlushAutosave())
            _events.emit(
              NoteEditorUiEvent.ShowMessage(
                readiness.reason ?: "Verificando disponibilidade da pasta de destino."
              )
            )
          }
          is ConsolidationReadiness.BlockedByConflict -> Unit
          is ConsolidationReadiness.BlockedBySessionState,
          null -> {
            coordinator.closeAndAbort()
            _events.emit(NoteEditorUiEvent.NavigateBack)
          }
        }
      } finally {
        _isBusyTransition.value = false
      }
    }
  }

  private suspend fun closeAndNavigateBack() {
    val closeAck = CompletableDeferred<SessionCommandOutcome>()
    val submitted = coordinator.submit(SessionCommand.CloseSession(closeAck))
    if (submitted is SubmitCommandResult.Accepted) {
      closeAck.await()
    } else {
      coordinator.closeAndAbort()
    }
    _events.emit(NoteEditorUiEvent.NavigateBack)
  }

  private fun NoteEditorUiState.isCompletelyEmptyNote(): Boolean =
    title.isBlank() && editorState.document.blocks.all { it.text.isBlank() }

  companion object {
    fun formatNoteForSharing(state: NoteEditorUiState): String {
      val lines = mutableListOf<String>()
      val cleanTitle = state.title.trim()
      if (cleanTitle.isNotEmpty()) {
        lines += cleanTitle
      }
      val bodyLines =
        state.editorState.document.blocks.mapNotNull { block ->
          val text = block.text
          when (block) {
            is ParagraphBlock -> text.takeIf { it.isNotBlank() }
            is BulletListItemBlock -> "• $text"
            is NumberedListItemBlock -> {
              val number = state.editorState.numberForBlock(block.id) ?: 1
              "$number. $text"
            }
            is ChecklistItemBlock -> {
              val marker = if (block.isChecked) "[x]" else "[ ]"
              "$marker $text"
            }
          }
        }
      if (bodyLines.isNotEmpty()) {
        if (lines.isNotEmpty()) {
          lines += ""
        }
        lines.addAll(bodyLines)
      }
      return lines.joinToString(separator = "\n")
    }
  }
}
