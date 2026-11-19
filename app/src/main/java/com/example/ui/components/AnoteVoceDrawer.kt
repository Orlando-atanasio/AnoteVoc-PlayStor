package com.example.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuOpen
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.Folder
import com.example.ui.navigation.AnoteVoceDestination
import com.example.ui.theme.LocalAnoteVoceSurfaceTokens

@Composable
fun AnoteVoceDrawerPanel(
  currentDestination: AnoteVoceDestination,
  activeFolders: List<Folder>,
  expanded: Boolean,
  showCollapseToggle: Boolean,
  onToggleExpand: () -> Unit,
  onSelectDestination: (AnoteVoceDestination) -> Unit,
  modifier: Modifier = Modifier,
) {
  val surfaceTokens = LocalAnoteVoceSurfaceTokens.current
  val panelWidth = if (expanded) 280.dp else 80.dp

  Surface(
    color = surfaceTokens.drawerContainer,
    contentColor = MaterialTheme.colorScheme.onSurface,
    tonalElevation = 1.dp,
    modifier =
      modifier
        .width(panelWidth)
        .fillMaxHeight()
        .animateContentSize()
        .testTag(if (expanded) "drawer_panel_expanded" else "drawer_panel_collapsed"),
  ) {
    Column(
      modifier =
        Modifier.fillMaxHeight()
          .windowInsetsPadding(WindowInsets.safeDrawing)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 12.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      DrawerBrandHeader(
        expanded = expanded,
        showCollapseToggle = showCollapseToggle,
        onToggleExpand = onToggleExpand,
      )

      Spacer(modifier = Modifier.height(6.dp))

      DrawerDestinationItem(
        label = stringResource(R.string.nav_all_notes),
        selectedIcon = Icons.Filled.Notes,
        unselectedIcon = Icons.Outlined.Notes,
        selected = currentDestination == AnoteVoceDestination.AllNotes,
        expanded = expanded,
        onClick = { onSelectDestination(AnoteVoceDestination.AllNotes) },
        modifier = Modifier.testTag("drawer_item_all_notes"),
      )

      DrawerDestinationItem(
        label = stringResource(R.string.nav_pinned),
        selectedIcon = Icons.Filled.PushPin,
        unselectedIcon = Icons.Outlined.PushPin,
        selected = currentDestination == AnoteVoceDestination.Pinned,
        expanded = expanded,
        onClick = { onSelectDestination(AnoteVoceDestination.Pinned) },
        modifier = Modifier.testTag("drawer_item_pinned"),
      )

      DrawerDestinationItem(
        label = stringResource(R.string.nav_trash),
        selectedIcon = Icons.Filled.Delete,
        unselectedIcon = Icons.Outlined.DeleteOutline,
        selected = currentDestination == AnoteVoceDestination.Trash,
        expanded = expanded,
        onClick = { onSelectDestination(AnoteVoceDestination.Trash) },
        modifier = Modifier.testTag("drawer_item_trash"),
      )

      HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
      )

      if (expanded) {
        Text(
          text = stringResource(R.string.nav_folders_section),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier =
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
              .semantics { heading() }
              .testTag("drawer_folders_header"),
        )
      }

      if (activeFolders.isEmpty()) {
        if (expanded) {
          Text(
            text = stringResource(R.string.nav_folders_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
              Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("drawer_folders_empty_label"),
          )
        }
      } else {
        activeFolders.forEach { folder ->
          val isFolderSelected =
            currentDestination is AnoteVoceDestination.Folder &&
              currentDestination.folderId == folder.id
          DrawerDestinationItem(
            label = folder.name,
            selectedIcon = Icons.Filled.Folder,
            unselectedIcon = Icons.Outlined.Folder,
            selected = isFolderSelected,
            expanded = expanded,
            onClick = { onSelectDestination(AnoteVoceDestination.Folder(folder.id)) },
            modifier = Modifier.testTag("drawer_item_folder_${folder.id}"),
          )
        }
      }
    }
  }
}

@Composable
private fun DrawerBrandHeader(
  expanded: Boolean,
  showCollapseToggle: Boolean,
  onToggleExpand: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement =
      if (expanded) Arrangement.SpaceBetween else Arrangement.Center,
  ) {
    if (expanded) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.weight(1f),
      ) {
        Image(
          painter = painterResource(id = R.drawable.ic_brand_anotevoce_emblem),
          contentDescription = stringResource(R.string.cd_app_emblem),
          contentScale = ContentScale.Fit,
          modifier =
            Modifier.size(40.dp)
              .clip(MaterialTheme.shapes.small)
              .testTag("drawer_brand_emblem"),
        )
        Column {
          Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = stringResource(R.string.app_tagline_offline),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }

    if (showCollapseToggle) {
      IconButton(
        onClick = onToggleExpand,
        modifier = Modifier.size(48.dp).testTag("drawer_collapse_toggle"),
      ) {
        Icon(
          imageVector =
            if (expanded) Icons.AutoMirrored.Outlined.MenuOpen else Icons.Outlined.Menu,
          contentDescription =
            stringResource(
              if (expanded) R.string.cd_collapse_drawer else R.string.cd_expand_drawer
            ),
        )
      }
    }
  }
}

@Composable
private fun DrawerDestinationItem(
  label: String,
  selectedIcon: ImageVector,
  unselectedIcon: ImageVector,
  selected: Boolean,
  expanded: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val surfaceTokens = LocalAnoteVoceSurfaceTokens.current
  val selectedText = stringResource(R.string.state_selected)
  val notSelectedText = stringResource(R.string.state_not_selected)

  val containerColor =
    if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
  val contentColor =
    if (selected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface
  val border =
    if (selected) BorderStroke(1.dp, surfaceTokens.selectedIndicator.copy(alpha = 0.45f))
    else null

  Surface(
    shape = MaterialTheme.shapes.medium,
    color = containerColor,
    contentColor = contentColor,
    border = border,
    modifier =
      modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
        .minimumInteractiveComponentSize()
        .clip(MaterialTheme.shapes.medium)
        .clickable(onClick = onClick)
        .semantics {
          this.selected = selected
          this.stateDescription = if (selected) selectedText else notSelectedText
        },
  ) {
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .padding(horizontal = if (expanded) 12.dp else 8.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
    ) {
      if (selected && expanded) {
        Box(
          modifier =
            Modifier.width(4.dp)
              .height(20.dp)
              .clip(MaterialTheme.shapes.extraSmall)
              .background(surfaceTokens.selectedIndicator)
        )
        Spacer(modifier = Modifier.width(10.dp))
      }
      Icon(
        imageVector = if (selected) selectedIcon else unselectedIcon,
        contentDescription = if (expanded) null else label,
        modifier = Modifier.size(22.dp),
      )
      if (expanded) {
        Spacer(modifier = Modifier.width(12.dp))
        Text(
          text = label,
          style =
            MaterialTheme.typography.labelLarge.copy(
              fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            ),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}
