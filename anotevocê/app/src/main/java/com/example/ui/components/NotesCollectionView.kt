package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.HomeViewMode
import com.example.domain.model.Note
import com.example.domain.model.NoteContentState
import com.example.ui.theme.LocalAnoteVoceSurfaceTokens

@Composable
fun NotesCollectionView(
  notes: List<Note>,
  viewMode: HomeViewMode,
  folderNamesById: Map<Long, String>,
  showFolderBadge: Boolean,
  modifier: Modifier = Modifier,
  headerContent: (@Composable () -> Unit)? = null,
) {
  when (viewMode) {
    HomeViewMode.LISTA -> {
      LazyColumn(
        modifier = modifier.fillMaxSize().testTag("notes_layout_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        if (headerContent != null) {
          item(key = "area_header") {
            Box(modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp)) {
              headerContent()
            }
          }
        }
        items(items = notes, key = { it.id }) { note ->
          NotePreviewCard(
            note = note,
            folderName = if (showFolderBadge) note.folderId?.let { folderNamesById[it] } else null,
            compact = false,
            maxPreviewLines = 4,
            modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp),
          )
        }
      }
    }

    HomeViewMode.GRADE -> {
      LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 172.dp),
        modifier = modifier.fillMaxSize().testTag("notes_layout_grid"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (headerContent != null) {
          item(key = "area_header", span = { GridItemSpan(maxLineSpan) }) {
            headerContent()
          }
        }
        items(items = notes, key = { it.id }) { note ->
          NotePreviewCard(
            note = note,
            folderName = if (showFolderBadge) note.folderId?.let { folderNamesById[it] } else null,
            compact = false,
            maxPreviewLines = 5,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    }

    HomeViewMode.LISTA_COMPACTA -> {
      LazyColumn(
        modifier = modifier.fillMaxSize().testTag("notes_layout_compact"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        if (headerContent != null) {
          item(key = "area_header") {
            Box(modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp)) {
              headerContent()
            }
          }
        }
        items(items = notes, key = { it.id }) { note ->
          NotePreviewCard(
            note = note,
            folderName = if (showFolderBadge) note.folderId?.let { folderNamesById[it] } else null,
            compact = true,
            maxPreviewLines = 1,
            modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp),
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotePreviewCard(
  note: Note,
  folderName: String?,
  compact: Boolean,
  maxPreviewLines: Int,
  modifier: Modifier = Modifier,
) {
  val surfaceTokens = LocalAnoteVoceSurfaceTokens.current
  val displayTitle = note.title.ifBlank { stringResource(R.string.note_untitled) }

  Card(
    shape = if (compact) MaterialTheme.shapes.small else MaterialTheme.shapes.medium,
    colors = CardDefaults.cardColors(containerColor = surfaceTokens.cardContainer),
    border = BorderStroke(1.dp, surfaceTokens.cardBorder),
    elevation = CardDefaults.cardElevation(defaultElevation = if (compact) 0.dp else 1.dp),
    modifier =
      modifier
        .heightIn(min = if (compact) 56.dp else 88.dp)
        .semantics(mergeDescendants = true) {}
        .testTag("note_card_${note.id}"),
  ) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .padding(
            horizontal = if (compact) 14.dp else 16.dp,
            vertical = if (compact) 10.dp else 14.dp,
          ),
      verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = displayTitle,
          style =
            if (compact) MaterialTheme.typography.titleSmall
            else MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = if (compact) 1 else 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        if (note.isPinned) {
          PinnedBadge(compact = compact, noteId = note.id)
        }
      }

      when (val contentState = note.contentState) {
        is NoteContentState.Valid -> {
          val preview = note.plainTextPreview.trim()
          if (preview.isNotEmpty()) {
            Text(
              text = preview,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = maxPreviewLines,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        is NoteContentState.RequiresHandling -> {
          UnreadableContentNotice(
            noteId = note.id,
            contentState = contentState,
          )
        }
      }

      if (folderName != null) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Surface(
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = stringResource(R.string.cd_folder_icon, folderName),
                modifier = Modifier.size(14.dp),
              )
              Text(
                text = folderName,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun PinnedBadge(compact: Boolean, noteId: Long) {
  Surface(
    shape = MaterialTheme.shapes.extraSmall,
    color = MaterialTheme.colorScheme.tertiaryContainer,
    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    modifier = Modifier.testTag("note_pinned_badge_$noteId"),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Icon(
        imageVector = Icons.Filled.PushPin,
        contentDescription = stringResource(R.string.cd_pinned_note_icon),
        modifier = Modifier.size(14.dp),
      )
      if (!compact) {
        Text(
          text = stringResource(R.string.note_pinned_badge),
          style = MaterialTheme.typography.labelMedium,
        )
      }
    }
  }
}

@Composable
private fun UnreadableContentNotice(
  noteId: Long,
  contentState: NoteContentState.RequiresHandling,
) {
  val message =
    when (contentState) {
      is NoteContentState.RequiresHandling.UnsupportedFutureVersion ->
        stringResource(R.string.note_unreadable_future_version)
      is NoteContentState.RequiresHandling.UnknownBlockType ->
        stringResource(R.string.note_unreadable_unknown_block)
      is NoteContentState.RequiresHandling.CorruptedJson ->
        stringResource(R.string.note_unreadable_corrupted)
    }

  Surface(
    shape = MaterialTheme.shapes.small,
    color = MaterialTheme.colorScheme.surfaceVariant,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier.fillMaxWidth().testTag("note_unreadable_notice_$noteId"),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.Outlined.Lock,
        contentDescription = stringResource(R.string.cd_unreadable_note_icon),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(16.dp),
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
