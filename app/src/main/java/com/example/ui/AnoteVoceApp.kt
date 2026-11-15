package com.example.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.R
import com.example.core.di.AppContainer
import com.example.ui.components.AnoteVoceDrawerPanel
import com.example.ui.navigation.AnoteVoceDestination
import com.example.ui.screens.NotesAreaScreen
import com.example.ui.theme.AnoteVoceTheme
import com.example.ui.theme.LocalAnoteVoceSurfaceTokens
import com.example.ui.viewmodel.AnoteVoceViewModelFactory
import com.example.ui.viewmodel.HomeAreaViewModel
import com.example.ui.viewmodel.ShellUiState
import com.example.ui.viewmodel.ShellViewModel
import kotlinx.coroutines.launch

@Composable
fun AnoteVoceApp(container: AppContainer) {
  val factory = remember(container) { AnoteVoceViewModelFactory(container) }
  AnoteVoceApp(viewModelFactory = factory)
}

@Composable
fun AnoteVoceApp(viewModelFactory: AnoteVoceViewModelFactory) {
  val shellViewModel: ShellViewModel = viewModel(factory = viewModelFactory.shellFactory())
  val shellState by shellViewModel.uiState.collectAsStateWithLifecycle()

  AnoteVoceTheme(themeMode = shellState.themeMode) {
    SyncSystemBarAppearance()
    AnoteVoceMainShell(
      shellState = shellState,
      viewModelFactory = viewModelFactory,
      onTogglePersistentSidebar = { currentExpanded ->
        shellViewModel.setSidebarExpanded(!currentExpanded)
      },
    )
  }
}

@Composable
private fun SyncSystemBarAppearance() {
  val view = LocalView.current
  val isDark = LocalAnoteVoceSurfaceTokens.current.isDark
  if (!view.isInEditMode) {
    SideEffect {
      val activity = view.context as? Activity ?: return@SideEffect
      val controller = WindowCompat.getInsetsController(activity.window, view)
      controller.isAppearanceLightStatusBars = !isDark
      controller.isAppearanceLightNavigationBars = !isDark
    }
  }
}

@Composable
private fun AnoteVoceMainShell(
  shellState: ShellUiState,
  viewModelFactory: AnoteVoceViewModelFactory,
  onTogglePersistentSidebar: (currentExpanded: Boolean) -> Unit,
) {
  val navController = rememberNavController()
  val navBackStackEntry by navController.currentBackStackEntryAsState()

  val currentDestination: AnoteVoceDestination =
    remember(navBackStackEntry) {
      val dest = navBackStackEntry?.destination
      when {
        dest == null -> AnoteVoceDestination.AllNotes
        dest.hasRoute<AnoteVoceDestination.AllNotes>() -> AnoteVoceDestination.AllNotes
        dest.hasRoute<AnoteVoceDestination.Pinned>() -> AnoteVoceDestination.Pinned
        dest.hasRoute<AnoteVoceDestination.Trash>() -> AnoteVoceDestination.Trash
        dest.hasRoute<AnoteVoceDestination.Folder>() ->
          navBackStackEntry?.toRoute<AnoteVoceDestination.Folder>() ?: AnoteVoceDestination.AllNotes
        else -> AnoteVoceDestination.AllNotes
      }
    }

  val scope = rememberCoroutineScope()
  val modalDrawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val isCompact = maxWidth < 600.dp
    val defaultSidebarExpanded = maxWidth >= 840.dp
    val persistentSidebarExpanded = shellState.sidebarExpandedPreference ?: defaultSidebarExpanded

    val navigateTo: (AnoteVoceDestination) -> Unit = { target ->
      if (isCompact) {
        scope.launch { modalDrawerState.close() }
      }
      if (target != currentDestination) {
        navController.navigate(target) {
          popUpTo<AnoteVoceDestination.AllNotes> { inclusive = false }
          launchSingleTop = true
        }
      }
    }

    if (isCompact) {
      ModalNavigationDrawer(
        drawerState = modalDrawerState,
        drawerContent = {
          ModalDrawerSheet(
            drawerContainerColor = LocalAnoteVoceSurfaceTokens.current.drawerContainer,
            windowInsets = WindowInsets(0, 0, 0, 0),
          ) {
            AnoteVoceDrawerPanel(
              currentDestination = currentDestination,
              activeFolders = shellState.activeFolders,
              expanded = true,
              showCollapseToggle = false,
              onToggleExpand = {},
              onSelectDestination = navigateTo,
            )
          }
        },
      ) {
        ShellScaffoldContent(
          currentDestination = currentDestination,
          shellState = shellState,
          isCompact = true,
          onOpenModalDrawer = { scope.launch { modalDrawerState.open() } },
          navController = navController,
          viewModelFactory = viewModelFactory,
        )

        // Prioridade máxima quando o menu modal está aberto: entra na composição no topo da pilha LIFO.
        if (modalDrawerState.isOpen || modalDrawerState.targetValue == DrawerValue.Open) {
          BackHandler(enabled = true) {
            scope.launch { modalDrawerState.close() }
          }
        }
      }
    } else {
      Row(modifier = Modifier.fillMaxSize()) {
        AnoteVoceDrawerPanel(
          currentDestination = currentDestination,
          activeFolders = shellState.activeFolders,
          expanded = persistentSidebarExpanded,
          showCollapseToggle = true,
          onToggleExpand = { onTogglePersistentSidebar(persistentSidebarExpanded) },
          onSelectDestination = navigateTo,
        )
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ShellScaffoldContent(
          currentDestination = currentDestination,
          shellState = shellState,
          isCompact = false,
          onOpenModalDrawer = {},
          navController = navController,
          viewModelFactory = viewModelFactory,
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShellScaffoldContent(
  currentDestination: AnoteVoceDestination,
  shellState: ShellUiState,
  isCompact: Boolean,
  onOpenModalDrawer: () -> Unit,
  navController: androidx.navigation.NavHostController,
  viewModelFactory: AnoteVoceViewModelFactory,
  modifier: Modifier = Modifier,
) {
  val topBarTitle =
    when (currentDestination) {
      AnoteVoceDestination.AllNotes -> stringResource(R.string.nav_all_notes)
      AnoteVoceDestination.Pinned -> stringResource(R.string.nav_pinned)
      AnoteVoceDestination.Trash -> stringResource(R.string.nav_trash)
      is AnoteVoceDestination.Folder ->
        shellState.activeFolders.firstOrNull { it.id == currentDestination.folderId }?.name
          ?: stringResource(R.string.nav_folder_fallback_title)
    }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
    contentWindowInsets = WindowInsets.safeDrawing,
    topBar = {
      TopAppBar(
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
          ),
        navigationIcon = {
          if (isCompact) {
            IconButton(
              onClick = onOpenModalDrawer,
              modifier = Modifier.size(48.dp).testTag("drawer_menu_button"),
            ) {
              Icon(
                imageVector = Icons.Outlined.Menu,
                contentDescription = stringResource(R.string.cd_open_drawer),
              )
            }
          }
        },
        title = {
          Text(
            text = topBarTitle,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() }.testTag("top_bar_title"),
          )
        },
      )
    },
  ) { innerPadding ->
    NavHost(
      navController = navController,
      startDestination = AnoteVoceDestination.AllNotes,
      modifier = Modifier.fillMaxSize().padding(innerPadding),
    ) {
      composable<AnoteVoceDestination.AllNotes> {
        val areaViewModel: HomeAreaViewModel =
          viewModel(
            key = "area_all_notes",
            factory = viewModelFactory.areaFactory(AnoteVoceDestination.AllNotes),
          )
        val uiState by areaViewModel.uiState.collectAsStateWithLifecycle()
        NotesAreaScreen(uiState = uiState)
      }

      composable<AnoteVoceDestination.Pinned> {
        val areaViewModel: HomeAreaViewModel =
          viewModel(
            key = "area_pinned",
            factory = viewModelFactory.areaFactory(AnoteVoceDestination.Pinned),
          )
        val uiState by areaViewModel.uiState.collectAsStateWithLifecycle()
        NotesAreaScreen(uiState = uiState)
      }

      composable<AnoteVoceDestination.Trash> {
        val areaViewModel: HomeAreaViewModel =
          viewModel(
            key = "area_trash",
            factory = viewModelFactory.areaFactory(AnoteVoceDestination.Trash),
          )
        val uiState by areaViewModel.uiState.collectAsStateWithLifecycle()
        NotesAreaScreen(uiState = uiState)
      }

      composable<AnoteVoceDestination.Folder> { backStackEntry ->
        val folderRoute = backStackEntry.toRoute<AnoteVoceDestination.Folder>()
        val areaViewModel: HomeAreaViewModel =
          viewModel(
            key = "area_folder_${folderRoute.folderId}",
            factory = viewModelFactory.areaFactory(folderRoute),
          )
        val uiState by areaViewModel.uiState.collectAsStateWithLifecycle()
        NotesAreaScreen(uiState = uiState)
      }
    }
  }
}
