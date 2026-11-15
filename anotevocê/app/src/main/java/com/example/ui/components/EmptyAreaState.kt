package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.navigation.AnoteVoceDestination
import com.example.ui.theme.LocalAnoteVoceSurfaceTokens

@Composable
fun EmptyAreaState(
  destination: AnoteVoceDestination,
  isFolderMissingOrDeleted: Boolean,
  modifier: Modifier = Modifier,
) {
  val surfaceTokens = LocalAnoteVoceSurfaceTokens.current

  val icon: ImageVector
  val iconContentDescription: String?
  val messageText: String?

  when (destination) {
    AnoteVoceDestination.AllNotes -> {
      icon = Icons.Outlined.EditNote
      iconContentDescription = stringResource(R.string.cd_empty_all_notes_icon)
      messageText = stringResource(R.string.empty_all_notes)
    }
    AnoteVoceDestination.Pinned -> {
      icon = Icons.Outlined.PushPin
      iconContentDescription = stringResource(R.string.cd_empty_pinned_icon)
      messageText = stringResource(R.string.empty_pinned_notes)
    }
    AnoteVoceDestination.Trash -> {
      // A barra superior já exibe o título "Apagados"; evitamos duplicação e qualquer texto adicional.
      icon = Icons.Outlined.DeleteOutline
      iconContentDescription = null
      messageText = null
    }
    is AnoteVoceDestination.Folder -> {
      if (isFolderMissingOrDeleted) {
        icon = Icons.Outlined.FolderOff
        iconContentDescription = null
        messageText = stringResource(R.string.folder_unavailable_message)
      } else {
        icon = Icons.Outlined.FolderOpen
        iconContentDescription = stringResource(R.string.cd_empty_folder_icon)
        messageText = stringResource(R.string.empty_folder_notes)
      }
    }
  }

  Box(
    modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier = Modifier.widthIn(max = 420.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        border = BorderStroke(1.dp, surfaceTokens.cardBorder),
        modifier =
          Modifier.size(84.dp)
            .testTag(
              if (destination == AnoteVoceDestination.Trash) "empty_trash_icon"
              else "empty_state_icon"
            ),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = icon,
            contentDescription = iconContentDescription,
            modifier = Modifier.size(38.dp),
          )
        }
      }

      if (messageText != null) {
        Spacer(modifier = Modifier.height(20.dp))
        Text(
          text = messageText,
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onBackground,
          textAlign = TextAlign.Center,
          modifier = Modifier.semantics { heading() }.testTag("empty_state_text"),
        )
      }
    }
  }
}
