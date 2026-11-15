package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WavingHand
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.R
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
) {
  val isAllNotes = uiState.destination == AnoteVoceDestination.AllNotes
  val showFolderBadge = uiState.destination !is AnoteVoceDestination.Folder

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
          headerContent =
            if (isAllNotes) {
              { HomeGreetingBanner(modifier = Modifier.fillMaxWidth()) }
            } else {
              null
            },
        )
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
