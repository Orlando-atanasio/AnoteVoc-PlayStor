package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.editor.ConflictResolutionOption
import com.example.domain.model.Folder
import com.example.domain.model.NotePaperStyle

@Composable
fun DestinationFolderPickerDialog(
  selectedFolderId: Long?,
  activeFolders: List<Folder>,
  onSelectFolder: (Long?) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(text = stringResource(R.string.editor_folder_picker_title)) },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        PickerOptionRow(
          label = stringResource(R.string.editor_save_in_root),
          selected = selectedFolderId == null,
          leadingContent = {
            Icon(
              imageVector = Icons.AutoMirrored.Outlined.Notes,
              contentDescription = null,
            )
          },
          onClick = { onSelectFolder(null) },
          modifier = Modifier.testTag("folder_picker_root"),
        )
        activeFolders.forEach { folder ->
          PickerOptionRow(
            label = folder.name,
            selected = selectedFolderId == folder.id,
            leadingContent = {
              Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
              )
            },
            onClick = { onSelectFolder(folder.id) },
            modifier = Modifier.testTag("folder_picker_item_${folder.id}"),
          )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(text = stringResource(R.string.action_close))
      }
    },
  )
}

@Composable
fun NotePaperPickerDialog(
  currentPaperStyle: NotePaperStyle,
  onSelectPaper: (NotePaperStyle) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(text = stringResource(R.string.editor_paper_picker_title)) },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        NotePaperStyle.entries.forEach { style ->
          PickerOptionRow(
            label = style.labelPtBr,
            selected = currentPaperStyle == style,
            leadingContent = { NotePaperSwatch(paperStyle = style) },
            onClick = { onSelectPaper(style) },
            modifier = Modifier.testTag("paper_option_${style.name}"),
          )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(text = stringResource(R.string.action_close))
      }
    },
  )
}

@Composable
fun DeleteNoteConfirmDialog(
  isEmptyNewNote: Boolean,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(text = stringResource(R.string.editor_delete_confirm_title)) },
    text = {
      Text(
        text =
          stringResource(
            if (isEmptyNewNote) R.string.editor_delete_empty_confirm_message
            else R.string.editor_delete_confirm_message
          )
      )
    },
    confirmButton = {
      Button(
        onClick = onConfirm,
        modifier = Modifier.heightIn(min = 48.dp).testTag("delete_confirm_button"),
      ) {
        Text(text = stringResource(R.string.editor_delete_confirm_action))
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.heightIn(min = 48.dp).testTag("delete_cancel_button"),
      ) {
        Text(text = stringResource(R.string.action_cancel))
      }
    },
  )
}

@Composable
fun FolderFallbackConfirmDialog(
  onConfirmSaveInRoot: () -> Unit,
  onChooseAnotherFolder: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(text = stringResource(R.string.editor_fallback_confirm_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = stringResource(R.string.editor_fallback_confirm_message))
        Button(
          onClick = onConfirmSaveInRoot,
          modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("fallback_save_root_button"),
        ) {
          Text(text = stringResource(R.string.action_save_in_all_notes))
        }
        OutlinedButton(
          onClick = onChooseAnotherFolder,
          modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("fallback_choose_folder_button"),
        ) {
          Text(text = stringResource(R.string.action_choose_folder))
        }
      }
    },
    confirmButton = {},
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.heightIn(min = 48.dp),
      ) {
        Text(text = stringResource(R.string.action_cancel))
      }
    },
  )
}

@Composable
fun RecoveryConflictDialog(
  onResolve: (ConflictResolutionOption) -> Unit,
) {
  AlertDialog(
    onDismissRequest = {},
    title = { Text(text = stringResource(R.string.editor_conflict_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = stringResource(R.string.editor_conflict_message))
        Button(
          onClick = { onResolve(ConflictResolutionOption.KEEP_DRAFT_OVER_CURRENT_NOTE) },
          modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("conflict_keep_draft_button"),
        ) {
          Text(text = stringResource(R.string.editor_conflict_keep_draft))
        }
        OutlinedButton(
          onClick = { onResolve(ConflictResolutionOption.SAVE_DRAFT_AS_NEW_NOTE) },
          modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("conflict_save_new_button"),
        ) {
          Text(text = stringResource(R.string.editor_conflict_save_as_new))
        }
        OutlinedButton(
          onClick = { onResolve(ConflictResolutionOption.DISCARD_DRAFT_USE_CURRENT_NOTE) },
          modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("conflict_discard_draft_button"),
        ) {
          Text(text = stringResource(R.string.editor_conflict_discard_draft))
        }
      }
    },
    confirmButton = {},
  )
}

@Composable
private fun PickerOptionRow(
  label: String,
  selected: Boolean,
  leadingContent: @Composable () -> Unit,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    shape = MaterialTheme.shapes.medium,
    color =
      if (selected) MaterialTheme.colorScheme.primaryContainer
      else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    contentColor =
      if (selected) MaterialTheme.colorScheme.onPrimaryContainer
      else MaterialTheme.colorScheme.onSurface,
    border =
      if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
      else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier =
      modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
        .clickable(onClick = onClick)
        .semantics { this.selected = selected },
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      leadingContent()
      Spacer(modifier = Modifier.width(12.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      if (selected) {
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
          imageVector = Icons.Filled.Check,
          contentDescription = stringResource(R.string.state_selected),
        )
      }
    }
  }
}
