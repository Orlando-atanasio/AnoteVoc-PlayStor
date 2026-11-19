package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.BulletListItemBlock
import com.example.domain.model.ChecklistItemBlock
import com.example.domain.model.ContentBlock
import com.example.domain.model.NumberedListItemBlock
import com.example.domain.model.TextFormatStyle

/**
 * Permite que testes Robolectric controlem explicitamente a visibilidade simulada do teclado IME.
 * Em execução real (`null`), a visibilidade segue estritamente `WindowInsets.ime`.
 */
val LocalEditorImeVisibility = compositionLocalOf<Boolean?> { null }

@Composable
fun EditorFormattingToolbar(
  activeStyles: Set<TextFormatStyle>,
  focusedBlock: ContentBlock?,
  enabled: Boolean,
  onToggleFormat: (TextFormatStyle) -> Unit,
  onToggleBulletList: () -> Unit,
  onToggleNumberedList: () -> Unit,
  onToggleChecklist: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val isBulletActive = focusedBlock is BulletListItemBlock
  val isNumberedActive = focusedBlock is NumberedListItemBlock
  val isChecklistActive = focusedBlock is ChecklistItemBlock

  Surface(
    color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor = MaterialTheme.colorScheme.onSurface,
    tonalElevation = 3.dp,
    modifier = modifier.fillMaxWidth().testTag("editor_formatting_toolbar"),
  ) {
    androidx.compose.foundation.layout.Column {
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        FormatToolbarButton(
          icon = Icons.Filled.FormatBold,
          contentDescription = stringResource(R.string.cd_format_bold),
          selected = TextFormatStyle.BOLD in activeStyles,
          enabled = enabled,
          onClick = { onToggleFormat(TextFormatStyle.BOLD) },
          modifier = Modifier.testTag("format_bold_button"),
        )
        FormatToolbarButton(
          icon = Icons.Filled.FormatItalic,
          contentDescription = stringResource(R.string.cd_format_italic),
          selected = TextFormatStyle.ITALIC in activeStyles,
          enabled = enabled,
          onClick = { onToggleFormat(TextFormatStyle.ITALIC) },
          modifier = Modifier.testTag("format_italic_button"),
        )
        FormatToolbarButton(
          icon = Icons.Filled.FormatUnderlined,
          contentDescription = stringResource(R.string.cd_format_underline),
          selected = TextFormatStyle.UNDERLINE in activeStyles,
          enabled = enabled,
          onClick = { onToggleFormat(TextFormatStyle.UNDERLINE) },
          modifier = Modifier.testTag("format_underline_button"),
        )
        FormatToolbarButton(
          icon = Icons.AutoMirrored.Filled.FormatListBulleted,
          contentDescription = stringResource(R.string.cd_format_bullet_list),
          selected = isBulletActive,
          enabled = enabled,
          onClick = onToggleBulletList,
          modifier = Modifier.testTag("format_bullet_button"),
        )
        FormatToolbarButton(
          icon = Icons.Filled.FormatListNumbered,
          contentDescription = stringResource(R.string.cd_format_numbered_list),
          selected = isNumberedActive,
          enabled = enabled,
          onClick = onToggleNumberedList,
          modifier = Modifier.testTag("format_numbered_button"),
        )
        FormatToolbarButton(
          icon = Icons.Filled.CheckBox,
          contentDescription = stringResource(R.string.cd_format_checklist),
          selected = isChecklistActive,
          enabled = enabled,
          onClick = onToggleChecklist,
          modifier = Modifier.testTag("format_checklist_button"),
        )
      }
    }
  }
}

@Composable
private fun FormatToolbarButton(
  icon: ImageVector,
  contentDescription: String,
  selected: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val selectedText = stringResource(R.string.state_selected)
  val notSelectedText = stringResource(R.string.state_not_selected)

  val containerColor =
    when {
      !enabled -> Color.Transparent
      selected -> MaterialTheme.colorScheme.primaryContainer
      else -> Color.Transparent
    }
  val contentColor =
    when {
      !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
      selected -> MaterialTheme.colorScheme.onPrimaryContainer
      else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
  val border =
    if (selected && enabled) {
      BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    } else {
      null
    }

  Surface(
    onClick = onClick,
    enabled = enabled,
    shape = MaterialTheme.shapes.small,
    color = containerColor,
    contentColor = contentColor,
    border = border,
    modifier =
      modifier.size(48.dp).semantics {
        this.selected = selected
        this.stateDescription = if (selected) selectedText else notSelectedText
      },
  ) {
    androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
      Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = Modifier.size(22.dp),
      )
    }
  }
}
