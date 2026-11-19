package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.WavingHand
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.Note
import com.example.domain.model.NoteRecoveryDraft
import com.example.domain.policy.NoteContentPolicy
import com.example.ui.components.AppSurfaceBackground
import com.example.ui.components.EmptyAreaState
import com.example.ui.components.NotesCollectionView
import com.example.ui.navigation.AnoteVoceDestination
import com.example.ui.theme.LocalAnoteVoceSurfaceTokens
import com.example.ui.viewmodel.HomeAreaContentState
import com.example.ui.viewmodel.HomeAreaUiState

@Composable
fun NotesAreaScreen(
  uiState: HomeAreaUiState,
  modifier: Modifier = Modifier,
  onNoteClick: (Note) -> Unit = {},
  pendingRecoveryDrafts: List<NoteRecoveryDraft> = emptyList(),
  onResumePendingDraft: (NoteRecoveryDraft) -> Unit = {},
  onDiscardPendingDraft: (NoteRecoveryDraft) -> Unit = {},
) {
  val isAllNotes = uiState.destination == AnoteVoceDestination.AllNotes
  val showFolderBadge = uiState.destination !is AnoteVoceDestination.Folder
  val latestPendingDraft = if (isAllNotes) pendingRecoveryDrafts.firstOrNull() else null

  AppSurfaceBackground(modifier = modifier.testTag("notes_area_root")) {
    when (val content = uiState.contentState) {
      HomeAreaContentState.Loading -> {
        val loadingLabel = stringResource(R.string.loading_notes)
        Box(
          modifier =
            Modifier.fillMaxSize().semantics { contentDescription = loadingLabel },
          contentAlignment = Alignment.Center,
        ) {
          CircularProgressIndicator()
        }
      }

      HomeAreaContentState.Empty -> {
        Column(
          modifier =
            Modifier.fillMaxSize()
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 16.dp, vertical = 16.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          if (isAllNotes) {
            HomeGreetingBanner(modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp))
            if (latestPendingDraft != null) {
              Spacer(modifier = Modifier.height(12.dp))
              PendingRecoveryDraftBanner(
                draft = latestPendingDraft,
                onResume = { onResumePendingDraft(latestPendingDraft) },
                onDiscard = { onDiscardPendingDraft(latestPendingDraft) },
                modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp),
              )
            }
          }
          Box(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = true),
            contentAlignment = Alignment.Center,
          ) {
            EmptyAreaState(
              destination = uiState.destination,
              isFolderMissingOrDeleted = uiState.isFolderMissingOrDeleted,
            )
          }
        }
      }

      is HomeAreaContentState.Content -> {
        NotesCollectionView(
          notes = content.notes,
          viewMode = uiState.viewMode,
          folderNamesById = uiState.folderNamesById,
          showFolderBadge = showFolderBadge,
          onNoteClick = onNoteClick,
          headerContent =
            if (isAllNotes) {
              {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                  HomeGreetingBanner(modifier = Modifier.fillMaxWidth())
                  if (latestPendingDraft != null) {
                    PendingRecoveryDraftBanner(
                      draft = latestPendingDraft,
                      onResume = { onResumePendingDraft(latestPendingDraft) },
                      onDiscard = { onDiscardPendingDraft(latestPendingDraft) },
                      modifier = Modifier.fillMaxWidth(),
                    )
                  }
                }
              }
            } else {
              null
            },
        )
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PendingRecoveryDraftBanner(
  draft: NoteRecoveryDraft,
  onResume: () -> Unit,
  onDiscard: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val surfaceTokens = LocalAnoteVoceSurfaceTokens.current
  val previewSnippet =
    draft.title.ifBlank {
      draft.contentOrNull?.let { doc ->
        NoteContentPolicy.extractPlainTextPreview(doc)
          .lineSequence()
          .firstOrNull { it.isNotBlank() }
          ?.trim()
      }
    }

  Surface(
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    border = BorderStroke(1.dp, surfaceTokens.cardBorder),
    modifier = modifier.testTag("home_pending_recovery_banner"),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector = Icons.Outlined.Restore,
          contentDescription = null,
          modifier = Modifier.size(20.dp),
        )
        Text(
          text = stringResource(R.string.home_pending_recovery_title),
          style = MaterialTheme.typography.titleSmall,
        )
      }
      Text(
        text =
          if (!previewSnippet.isNullOrBlank()) previewSnippet
          else stringResource(R.string.home_pending_recovery_subtitle_single),
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = onResume,
          modifier = Modifier.heightIn(min = 48.dp).testTag("home_recovery_resume_button"),
        ) {
          Text(text = stringResource(R.string.home_pending_recovery_resume))
        }
        TextButton(
          onClick = onDiscard,
          modifier = Modifier.heightIn(min = 48.dp).testTag("home_recovery_discard_button"),
        ) {
          Text(text = stringResource(R.string.home_pending_recovery_discard))
        }
      }
    }
  }
}

@Composable
private fun HomeGreetingBanner(modifier: Modifier = Modifier) {
  val surfaceTokens = LocalAnoteVoceSurfaceTokens.current

  Surface(
    shape = MaterialTheme.shapes.large,
    color = surfaceTokens.greetingBannerContainer,
    contentColor = surfaceTokens.greetingBannerContent,
    border = BorderStroke(1.dp, surfaceTokens.cardBorder),
    modifier = modifier.testTag("home_greeting_banner"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Icon(
        imageVector = Icons.Outlined.WavingHand,
        contentDescription = null,
        modifier = Modifier.size(22.dp),
      )
      Text(
        text = stringResource(R.string.home_greeting),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() }.testTag("home_greeting_text"),
      )
    }
  }
}
