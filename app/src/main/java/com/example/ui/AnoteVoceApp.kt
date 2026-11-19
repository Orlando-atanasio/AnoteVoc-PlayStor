package com.example.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
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
import com.example.domain.editor.NoteEditorOpenRequest
import com.example.domain.editor.RecoveryTargetExpectation
import com.example.domain.model.Note
import com.example.ui.components.AnoteVoceDrawerPanel
import com.example.ui.navigation.AnoteVoceDestination
import com.example.ui.navigation.NoteEditorRoute
import com.example.ui.screens.NoteEditorScreen
import com.example.ui.screens.NotesAreaScreen
import com.example.ui.theme.AnoteVoceTheme
import com.example.ui.theme.LocalAnoteVoceSurfaceTokens
import com.example.ui.viewmodel.AnoteVoceViewModelFactory
import com.example.ui.viewmodel.HomeAreaViewModel
import com.example.ui.viewmodel.NoteEditorViewModel
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
      onDiscardPendingDraft = { draftId ->
        shellViewModel.discardPendingDraft(draftId)
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
  onDiscardPendingDraft: (String) -> Unit,
) {
  val navController = rememberNavController()
  val navBackStackEntry by navController.currentBackStackEntryAsState()

  val isInEditor =
    remember(navBackStackEntry) {
      navBackStackEntry?.destination?.hasRoute<NoteEditorRoute>() == true
    }

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
        gesturesEnabled = !isInEditor,
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
          isInEditor = isInEditor,
          shellState = shellState,
          isCompact = true,
          onOpenModalDrawer = { scope.launch { modalDrawerState.open() } },
          onDiscardPendingDraft = onDiscardPendingDraft,
          navController = navController,
          viewModelFactory = viewModelFactory,
        )

        // Prioridade máxima quando o menu modal está aberto: entra na composição no topo da pilha LIFO.
        if (!isInEditor && (modalDrawerState.isOpen || modalDrawerState.targetValue == DrawerValue.Open)) {
          BackHandler(enabled = true) {
            scope.launch { modalDrawerState.close() }
          }
        }
      }
    } else {
      Row(modifier = Modifier.fillMaxSize()) {
        if (!isInEditor) {
          AnoteVoceDrawerPanel(
            currentDestination = currentDestination,
            activeFolders = shellState.activeFolders,
            expanded = persistentSidebarExpanded,
            showCollapseToggle = true,
            onToggleExpand = { onTogglePersistentSidebar(persistentSidebarExpanded) },
            onSelectDestination = navigateTo,
          )
          VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        ShellScaffoldContent(
          currentDestination = currentDestination,
          isInEditor = isInEditor,
          shellState = shellState,
          isCompact = false,
          onOpenModalDrawer = {},
          onDiscardPendingDraft = onDiscardPendingDraft,
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
  isInEditor: Boolean,
  shellState: ShellUiState,
  isCompact: Boolean,
  onOpenModalDrawer: () -> Unit,
  onDiscardPendingDraft: (String) -> Unit,
  navController: androidx.navigation.NavHostController,
  viewModelFactory: AnoteVoceViewModelFactory,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }
  val trashReadOnlyMessage = stringResource(R.string.home_trash_read_only_message)

  val topBarTitle =
    when (currentDestination) {
      AnoteVoceDestination.AllNotes -> stringResource(R.string.nav_all_notes)
      AnoteVoceDestination.Pinned -> stringResource(R.string.nav_pinned)
      AnoteVoceDestination.Trash -> stringResource(R.string.nav_trash)
      is AnoteVoceDestination.Folder ->
        shellState.activeFolders.firstOrNull { it.id == currentDestination.folderId }?.name
          ?: stringResource(R.string.nav_folder_fallback_title)
    }

  val openCreateNote: () -> Unit = {
    val originFolderId = (currentDestination as? AnoteVoceDestination.Folder)?.folderId
    navController.navigate(
      NoteEditorRoute(
        noteId = null,
        initialFolderId = originFolderId,
        initialPinned = false,
        recoveryDraftId = null,
      )
    ) {
      launchSingleTop = true
    }
  }

  val openExistingNote: (Note) -> Unit = { note ->
    if (currentDestination == AnoteVoceDestination.Trash || note.isDeleted) {
      scope.launch { snackbarHostState.showSnackbar(trashReadOnlyMessage) }
    } else {
      val matchingDraft =
        shellState.pendingRecoveryDrafts.firstOrNull { it.targetNoteId == note.id }
      navController.navigate(
        NoteEditorRoute(
          noteId = note.id,
          initialFolderId = null,
          initialPinned = false,
          recoveryDraftId = matchingDraft?.draftId,
        )
      ) {
        launchSingleTop = true
      }
    }
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
    contentWindowInsets =
      if (isInEditor) WindowInsets(0, 0, 0, 0) else WindowInsets.safeDrawing,
    snackbarHost = {
      if (!isInEditor) {
        SnackbarHost(hostState = snackbarHostState)
      }
    },
    topBar = {
      if (!isInEditor) {
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
      }
    },
    bottomBar = {
      if (!isInEditor && currentDestination != AnoteVoceDestination.Trash) {
        HomeCreateNoteFooter(onCreateNoteClick = openCreateNote)
      }
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
        NotesAreaScreen(
          uiState = uiState,
          onNoteClick = openExistingNote,
          pendingRecoveryDrafts = shellState.pendingRecoveryDrafts,
          onResumePendingDraft = { draft ->
            navController.navigate(
              NoteEditorRoute(
                noteId = draft.targetNoteId,
                recoveryDraftId = draft.draftId,
              )
            ) {
              launchSingleTop = true
            }
          },
          onDiscardPendingDraft = { draft -> onDiscardPendingDraft(draft.draftId) },
        )
      }

      composable<AnoteVoceDestination.Pinned> {
        val areaViewModel: HomeAreaViewModel =
          viewModel(
            key = "area_pinned",
            factory = viewModelFactory.areaFactory(AnoteVoceDestination.Pinned),
          )
        val uiState by areaViewModel.uiState.collectAsStateWithLifecycle()
        NotesAreaScreen(
          uiState = uiState,
          onNoteClick = openExistingNote,
        )
      }

      composable<AnoteVoceDestination.Trash> {
        val areaViewModel: HomeAreaViewModel =
          viewModel(
            key = "area_trash",
            factory = viewModelFactory.areaFactory(AnoteVoceDestination.Trash),
          )
        val uiState by areaViewModel.uiState.collectAsStateWithLifecycle()
        NotesAreaScreen(
          uiState = uiState,
          onNoteClick = openExistingNote,
        )
      }

      composable<AnoteVoceDestination.Folder> { backStackEntry ->
        val folderRoute = backStackEntry.toRoute<AnoteVoceDestination.Folder>()
        val areaViewModel: HomeAreaViewModel =
          viewModel(
            key = "area_folder_${folderRoute.folderId}",
            factory = viewModelFactory.areaFactory(folderRoute),
          )
        val uiState by areaViewModel.uiState.collectAsStateWithLifecycle()
        NotesAreaScreen(
          uiState = uiState,
          onNoteClick = openExistingNote,
        )
      }

      composable<NoteEditorRoute> { backStackEntry ->
        val route = backStackEntry.toRoute<NoteEditorRoute>()
        val openRequest =
          remember(route) {
            when {
              route.recoveryDraftId != null ->
                NoteEditorOpenRequest.RecoverDraft(
                  draftId = route.recoveryDraftId,
                  expectedTarget =
                    route.noteId?.let { RecoveryTargetExpectation.ExistingNote(it) }
                      ?: RecoveryTargetExpectation.Any,
                )
              route.noteId != null -> NoteEditorOpenRequest.OpenExisting(noteId = route.noteId)
              else ->
                NoteEditorOpenRequest.CreateNew(
                  initialFolderId = route.initialFolderId,
                  initialPinned = route.initialPinned,
                )
            }
          }
        val editorViewModel: NoteEditorViewModel =
          viewModel(factory = viewModelFactory.editorFactory(openRequest))
        NoteEditorScreen(
          viewModel = editorViewModel,
          onNavigateBack = { navController.popBackStack() },
        )
      }
    }
  }
}

/**
 * Área de rodapé da Home contendo o botão único de criação de nota posicionado
 * no canto inferior direito, sem utilizar FloatingActionButton sobreposto ao conteúdo.
 */
@Composable
private fun HomeCreateNoteFooter(
  onCreateNoteClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val surfaceTokens = LocalAnoteVoceSurfaceTokens.current

  Surface(
    color = MaterialTheme.colorScheme.background,
    contentColor = MaterialTheme.colorScheme.onBackground,
    modifier = modifier.fillMaxWidth().testTag("home_footer_bar"),
  ) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
          ),
    ) {
      HorizontalDivider(color = surfaceTokens.cardBorder)
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Surface(
          onClick = onCreateNoteClick,
          shape = MaterialTheme.shapes.medium,
          color = MaterialTheme.colorScheme.primaryContainer,
          contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          border = BorderStroke(1.dp, surfaceTokens.selectedIndicator.copy(alpha = 0.35f)),
          modifier = Modifier.size(48.dp).testTag("home_create_note_button"),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Outlined.Edit,
              contentDescription = stringResource(R.string.cd_create_note),
              modifier = Modifier.size(22.dp),
            )
          }
        }
      }
    }
  }
}
