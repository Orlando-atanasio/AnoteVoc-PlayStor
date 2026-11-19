package com.example.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.editor.SessionBlockReason
import com.example.ui.components.DeleteNoteConfirmDialog
import com.example.ui.components.DestinationFolderPickerDialog
import com.example.ui.components.EditorDocumentArea
import com.example.ui.components.EditorFormattingToolbar
import com.example.ui.components.FolderFallbackConfirmDialog
import com.example.ui.components.LocalEditorImeVisibility
import com.example.ui.components.NotePaperPickerDialog
import com.example.ui.components.NotePaperSurface
import com.example.ui.components.RecoveryConflictDialog
import com.example.ui.components.resolveNotePaperPalette
import com.example.ui.viewmodel.AutosaveStatus
import com.example.ui.viewmodel.NoteEditorSessionPhase
import com.example.ui.viewmodel.NoteEditorUiEvent
import com.example.ui.viewmodel.NoteEditorUiState
import com.example.ui.viewmodel.NoteEditorViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NoteEditorScreen(
  viewModel: NoteEditorViewModel,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val isReadOnlyMode by viewModel.isReadOnlyMode.collectAsStateWithLifecycle()
  val activeFolders by viewModel.activeFolders.collectAsStateWithLifecycle()
  val showFolderPicker by viewModel.showFolderPicker.collectAsStateWithLifecycle()
  val showPaperPicker by viewModel.showPaperPicker.collectAsStateWithLifecycle()
  val showDeleteConfirm by viewModel.showDeleteConfirmDialog.collectAsStateWithLifecycle()
  val showFallbackConfirm by viewModel.showFallbackConfirmDialog.collectAsStateWithLifecycle()

  val context = LocalContext.current
  val keyboardController = LocalSoftwareKeyboardController.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val snackbarHostState = remember { SnackbarHostState() }

  var showOverflowMenu by remember { mutableStateOf(false) }
  var focusRequestTrigger by remember { mutableLongStateOf(0L) }
  var focusRequestBlockId by remember { mutableStateOf<String?>(null) }
  var focusedKey by remember { mutableStateOf<String?>(null) }

  val shareChooserTitle = stringResource(R.string.editor_share_chooser_title)
  val shareUnavailableMsg = stringResource(R.string.editor_share_unavailable)

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_STOP) {
        viewModel.onAppBackgrounded()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  LaunchedEffect(viewModel) {
    viewModel.events.collect { event ->
      when (event) {
        NoteEditorUiEvent.NavigateBack -> {
          keyboardController?.hide()
          onNavigateBack()
        }
        is NoteEditorUiEvent.ShareNote -> {
          val sendIntent =
            Intent(Intent.ACTION_SEND).apply {
              type = "text/plain"
              if (event.subject.isNotBlank()) {
                putExtra(Intent.EXTRA_SUBJECT, event.subject)
              }
              putExtra(Intent.EXTRA_TEXT, event.text)
            }
          val chooser = Intent.createChooser(sendIntent, shareChooserTitle)
          runCatching { context.startActivity(chooser) }
            .onFailure { snackbarHostState.showSnackbar(shareUnavailableMsg) }
        }
        is NoteEditorUiEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
        is NoteEditorUiEvent.RequestFocusAndKeyboard -> {
          focusRequestBlockId = event.blockId
          focusRequestTrigger += 1L
          keyboardController?.show()
        }
      }
    }
  }

  BackHandler { viewModel.onExitRequested() }

  val density = LocalDensity.current
  val imeVisible = LocalEditorImeVisibility.current ?: (WindowInsets.ime.getBottom(density) > 0)
  val showFormattingToolbar =
    uiState.phase == NoteEditorSessionPhase.EDITING &&
      !isReadOnlyMode &&
      imeVisible &&
      uiState.activeConflict == null

  val isContentFocused = focusedKey != "__title__"

  Scaffold(
    modifier = modifier.fillMaxSize().testTag("note_editor_screen"),
    containerColor = MaterialTheme.colorScheme.background,
    contentWindowInsets = WindowInsets.safeDrawing,
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    topBar = {
      TopAppBar(
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
          ),
        navigationIcon = {
          IconButton(
            onClick = viewModel::onExitRequested,
            modifier = Modifier.size(48.dp).testTag("editor_back_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.cd_editor_back),
            )
          }
        },
        title = {
          AutosaveHeaderIndicator(uiState = uiState)
        },
        actions = {
          IconButton(
            onClick = viewModel::onUndo,
            enabled = uiState.canEdit && uiState.editorState.canUndo,
            modifier = Modifier.size(48.dp).testTag("editor_undo_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.Undo,
              contentDescription = stringResource(R.string.cd_editor_undo),
            )
          }
          IconButton(
            onClick = viewModel::onRedo,
            enabled = uiState.canEdit && uiState.editorState.canRedo,
            modifier = Modifier.size(48.dp).testTag("editor_redo_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.Redo,
              contentDescription = stringResource(R.string.cd_editor_redo),
            )
          }
          IconButton(
            onClick = viewModel::onOpenPaperPicker,
            enabled = uiState.canEdit,
            modifier = Modifier.size(48.dp).testTag("editor_paper_button"),
          ) {
            Icon(
              imageVector = Icons.Outlined.Palette,
              contentDescription = stringResource(R.string.cd_editor_paper),
            )
          }
          Box {
            IconButton(
              onClick = { showOverflowMenu = true },
              enabled = uiState.phase == NoteEditorSessionPhase.EDITING,
              modifier = Modifier.size(48.dp).testTag("editor_more_button"),
            ) {
              Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.cd_editor_more),
              )
            }
            DropdownMenu(
              expanded = showOverflowMenu,
              onDismissRequest = { showOverflowMenu = false },
            ) {
              DropdownMenuItem(
                text = { Text(text = stringResource(R.string.editor_menu_share)) },
                leadingIcon = {
                  Icon(imageVector = Icons.Outlined.Share, contentDescription = null)
                },
                onClick = {
                  showOverflowMenu = false
                  viewModel.onShareClicked()
                },
                modifier = Modifier.heightIn(min = 48.dp).testTag("editor_menu_share"),
              )
              DropdownMenuItem(
                text = { Text(text = stringResource(R.string.editor_menu_delete)) },
                leadingIcon = {
                  Icon(imageVector = Icons.Outlined.DeleteOutline, contentDescription = null)
                },
                onClick = {
                  showOverflowMenu = false
                  viewModel.onDeleteClicked()
                },
                modifier = Modifier.heightIn(min = 48.dp).testTag("editor_menu_delete"),
              )
              DropdownMenuItem(
                text = {
                  Text(
                    text =
                      stringResource(
                        if (uiState.isPinned) R.string.editor_menu_unpin
                        else R.string.editor_menu_pin
                      )
                  )
                },
                leadingIcon = {
                  Icon(
                    imageVector =
                      if (uiState.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = null,
                  )
                },
                onClick = {
                  showOverflowMenu = false
                  viewModel.onTogglePin()
                },
                modifier = Modifier.heightIn(min = 48.dp).testTag("editor_menu_pin"),
              )
            }
          }
        },
      )
    },
    bottomBar = {
      if (showFormattingToolbar) {
        EditorFormattingToolbar(
          activeStyles = uiState.editorState.activeTypingStyles,
          focusedBlock = uiState.editorState.focusedBlock,
          enabled = uiState.canEdit && isContentFocused,
          onToggleFormat = viewModel::onToggleFormat,
          onToggleBulletList = viewModel::onToggleBulletList,
          onToggleNumberedList = viewModel::onToggleNumberedList,
          onToggleChecklist = viewModel::onToggleChecklist,
          modifier = Modifier.imePadding().navigationBarsPadding(),
        )
      }
    },
  ) { innerPadding ->
    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
      when (uiState.phase) {
        NoteEditorSessionPhase.LOADING -> {
          val loadingDesc = stringResource(R.string.editor_loading_message)
          Column(
            modifier =
              Modifier.fillMaxSize()
                .semantics { contentDescription = loadingDesc }
                .testTag("editor_loading_view"),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(14.dp))
            Text(
              text = stringResource(R.string.editor_loading_message),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        NoteEditorSessionPhase.BLOCKED -> {
          EditorTerminalStatusCard(
            icon = Icons.Outlined.Lock,
            title = stringResource(R.string.editor_blocked_title),
            message = resolveBlockMessage(uiState.blockReason),
            onClose = viewModel::onExitRequested,
            modifier = Modifier.testTag("editor_blocked_card"),
          )
        }

        NoteEditorSessionPhase.ERROR -> {
          EditorTerminalStatusCard(
            icon = Icons.Outlined.ErrorOutline,
            title = stringResource(R.string.editor_error_title),
            message = uiState.errorMessage ?: stringResource(R.string.editor_error_title),
            onClose = viewModel::onExitRequested,
            modifier = Modifier.testTag("editor_error_card"),
          )
        }

        NoteEditorSessionPhase.EDITING -> {
          val paperPalette = resolveNotePaperPalette(uiState.paperStyle)
          NotePaperSurface(
            paperStyle = uiState.paperStyle,
            modifier = Modifier.fillMaxSize().testTag("editor_paper_surface_${uiState.paperStyle.name}"),
          ) {
            Column(
              modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              Column(modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp)) {
                EditorMetadataHeader(
                  uiState = uiState,
                  isRecoveredDraftSession = viewModel.isRecoveredDraftSession,
                  isReadOnlyMode = isReadOnlyMode,
                  paperPalette = paperPalette,
                  onOpenFolderPicker = viewModel::onOpenFolderPicker,
                  onConfirmFallbackToRoot = viewModel::onConfirmFallbackToRootInline,
                )
                HorizontalDivider(color = paperPalette.ruleColor.copy(alpha = 0.55f))
                EditorDocumentArea(
                  title = uiState.title,
                  editorState = uiState.editorState,
                  stateGeneration = uiState.stateGeneration,
                  isReadOnlyMode = isReadOnlyMode,
                  canEdit = uiState.canEdit,
                  paperPalette = paperPalette,
                  focusRequestTrigger = focusRequestTrigger,
                  focusRequestBlockId = focusRequestBlockId,
                  onTitleChanged = viewModel::onTitleChanged,
                  onEmptyDocumentTextChanged = viewModel::onEmptyDocumentTextChanged,
                  onBlockTextChanged = viewModel::onBlockTextChanged,
                  onBlockSelectionChanged = viewModel::onBlockSelectionChanged,
                  onBackspaceAtStart = viewModel::onBackspacePressed,
                  onToggleChecklistItem = viewModel::onToggleChecklistItem,
                  onTapReadOnlyContent = { blockId, offset ->
                    viewModel.enterEditMode(blockId = blockId, offset = offset)
                  },
                  onContentFocusChanged = { blockKey, focused ->
                    if (focused) {
                      focusedKey = blockKey
                    } else if (focusedKey == blockKey) {
                      focusedKey = null
                    }
                  },
                )
              }
            }
          }
        }
      }
    }
  }

  if (uiState.activeConflict != null) {
    RecoveryConflictDialog(onResolve = viewModel::onResolveConflict)
  }

  if (showFolderPicker) {
    DestinationFolderPickerDialog(
      selectedFolderId = uiState.effectiveFolderId,
      activeFolders = activeFolders,
      onSelectFolder = viewModel::onSelectDestinationFolder,
      onDismiss = viewModel::onDismissFolderPicker,
    )
  }

  if (showPaperPicker) {
    NotePaperPickerDialog(
      currentPaperStyle = uiState.paperStyle,
      onSelectPaper = viewModel::onSelectPaperStyle,
      onDismiss = viewModel::onDismissPaperPicker,
    )
  }

  if (showDeleteConfirm) {
    val isEmptyNew =
      !uiState.isExistingNote &&
        uiState.title.isBlank() &&
        uiState.editorState.document.blocks.all { it.text.isBlank() }
    DeleteNoteConfirmDialog(
      isEmptyNewNote = isEmptyNew,
      onConfirm = viewModel::onConfirmDelete,
      onDismiss = viewModel::onDismissDeleteDialog,
    )
  }

  if (showFallbackConfirm) {
    FolderFallbackConfirmDialog(
      onConfirmSaveInRoot = viewModel::onConfirmFallbackSaveToRootAndExit,
      onChooseAnotherFolder = viewModel::onChooseFolderFromFallbackDialog,
      onDismiss = viewModel::onDismissFallbackDialog,
    )
  }
}

@Composable
private fun AutosaveHeaderIndicator(uiState: NoteEditorUiState) {
  val statusText =
    when (uiState.autosaveStatus) {
      AutosaveStatus.Saving -> stringResource(R.string.autosave_saving)
      is AutosaveStatus.Saved -> stringResource(R.string.autosave_saved)
      is AutosaveStatus.Error -> stringResource(R.string.autosave_error)
      AutosaveStatus.Idle,
      AutosaveStatus.PendingDebounce,
      AutosaveStatus.HeldPendingResolution -> null
    }

  if (statusText != null) {
    Text(
      text = statusText,
      style = MaterialTheme.typography.labelMedium,
      color =
        if (uiState.autosaveStatus is AutosaveStatus.Error) {
          MaterialTheme.colorScheme.error
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.testTag("editor_autosave_status"),
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorMetadataHeader(
  uiState: NoteEditorUiState,
  isRecoveredDraftSession: Boolean,
  isReadOnlyMode: Boolean,
  paperPalette: com.example.ui.components.NotePaperPalette,
  onOpenFolderPicker: () -> Unit,
  onConfirmFallbackToRoot: () -> Unit,
) {
  val folderDisplayName =
    uiState.effectiveFolderName ?: stringResource(R.string.editor_save_in_root)

  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      if (!uiState.isExistingNote) {
        val selectDesc = stringResource(R.string.cd_editor_save_in_selector)
        Surface(
          shape = MaterialTheme.shapes.small,
          color = paperPalette.secondaryTintColor,
          contentColor = paperPalette.contentColor,
          border = BorderStroke(1.dp, paperPalette.borderColor),
          modifier =
            Modifier.heightIn(min = 48.dp)
              .clickable(enabled = uiState.canEdit, onClick = onOpenFolderPicker)
              .semantics { contentDescription = "$selectDesc: $folderDisplayName" }
              .testTag("editor_save_in_chip"),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(
              imageVector = Icons.Outlined.Folder,
              contentDescription = null,
              tint = paperPalette.accentColor,
              modifier = Modifier.size(16.dp),
            )
            Text(
              text = "${stringResource(R.string.editor_save_in_prefix)} $folderDisplayName",
              style = MaterialTheme.typography.labelLarge,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      } else {
        Surface(
          shape = MaterialTheme.shapes.small,
          color = paperPalette.secondaryTintColor.copy(alpha = 0.72f),
          contentColor = paperPalette.secondaryContentColor,
          border = BorderStroke(1.dp, paperPalette.borderColor),
          modifier = Modifier.testTag("editor_existing_folder_badge"),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(
              imageVector = Icons.Outlined.Folder,
              contentDescription = null,
              modifier = Modifier.size(15.dp),
            )
            Text(
              text = "${stringResource(R.string.editor_folder_label_prefix)} $folderDisplayName",
              style = MaterialTheme.typography.labelMedium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }

      if (uiState.isPinned) {
        Surface(
          shape = MaterialTheme.shapes.small,
          color = MaterialTheme.colorScheme.tertiaryContainer,
          contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
          modifier = Modifier.testTag("editor_pinned_badge"),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.PushPin,
              contentDescription = stringResource(R.string.cd_pinned_note_icon),
              modifier = Modifier.size(14.dp),
            )
            Text(
              text = stringResource(R.string.note_pinned_badge),
              style = MaterialTheme.typography.labelMedium,
            )
          }
        }
      }

      if (isRecoveredDraftSession) {
        Surface(
          shape = MaterialTheme.shapes.small,
          color = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
          modifier = Modifier.testTag("editor_recovered_badge"),
        ) {
          Text(
            text = stringResource(R.string.editor_recovered_draft_badge),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
          )
        }
      }

      if (isReadOnlyMode) {
        Text(
          text = stringResource(R.string.editor_reading_mode_hint),
          style = MaterialTheme.typography.labelMedium,
          color = paperPalette.secondaryContentColor,
          modifier = Modifier.align(Alignment.CenterVertically).testTag("editor_reading_mode_hint"),
        )
      }
    }

    if (uiState.hasPendingFolderFallback) {
      Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("editor_fallback_banner"),
      ) {
        Column(
          modifier = Modifier.padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = Icons.Outlined.WarningAmber,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Text(
              text = stringResource(R.string.editor_fallback_banner_message),
              style = MaterialTheme.typography.bodySmall,
            )
          }
          if (!uiState.isExistingNote) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              TextButton(
                onClick = onConfirmFallbackToRoot,
                modifier = Modifier.heightIn(min = 48.dp),
              ) {
                Text(text = stringResource(R.string.action_save_in_all_notes))
              }
              TextButton(
                onClick = onOpenFolderPicker,
                modifier = Modifier.heightIn(min = 48.dp),
              ) {
                Text(text = stringResource(R.string.action_choose_folder))
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun EditorTerminalStatusCard(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  title: String,
  message: String,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier.fillMaxSize().padding(24.dp),
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surfaceVariant,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      modifier = Modifier.widthIn(max = 460.dp),
    ) {
      Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(40.dp),
        )
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
          text = message,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
          onClick = onClose,
          modifier = Modifier.heightIn(min = 48.dp).testTag("editor_terminal_close_button"),
        ) {
          Text(text = stringResource(R.string.action_close))
        }
      }
    }
  }
}

private fun resolveBlockMessage(reason: SessionBlockReason?): String =
  when (reason) {
    is SessionBlockReason.TargetNoteInTrash ->
      "Esta nota está em Apagados e precisa ser restaurada antes de editar."
    is SessionBlockReason.TargetNoteNotFound ->
      "A nota solicitada não foi encontrada."
    is SessionBlockReason.UnreadableTargetNoteContent,
    is SessionBlockReason.UnreadableDraftContent ->
      "Este conteúdo possui formato incompatível ou protegido nesta versão."
    is SessionBlockReason.SessionAlreadyActive ->
      "Já existe uma sessão ativa para esta anotação."
    is SessionBlockReason.DraftNotFound ->
      "O rascunho de recuperação não foi encontrado."
    is SessionBlockReason.DraftIdentityMismatch,
    is SessionBlockReason.DraftTargetMismatch,
    is SessionBlockReason.InvalidDraftBaseReference,
    is SessionBlockReason.InvalidPersistedDraftKeys ->
      "Não foi possível validar com segurança a identidade deste rascunho."
    null -> "Edição bloqueada."
  }
