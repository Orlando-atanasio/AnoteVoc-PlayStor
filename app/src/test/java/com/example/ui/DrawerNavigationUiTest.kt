package com.example.ui

import android.content.Context
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.AnoteVoceApplication
import com.example.core.coroutines.DispatcherProvider
import com.example.core.time.ClockProvider
import com.example.data.local.database.AnoteVoceDatabase
import com.example.data.repository.FolderRepositoryImpl
import com.example.data.repository.NoteRepositoryImpl
import com.example.data.repository.PreferencesRepositoryImpl
import com.example.ui.viewmodel.AnoteVoceViewModelFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w960dp-h800dp")
class DrawerNavigationUiTest {

  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var database: AnoteVoceDatabase
  private lateinit var folderRepository: FolderRepositoryImpl
  private lateinit var noteRepository: NoteRepositoryImpl
  private lateinit var preferencesRepository: PreferencesRepositoryImpl
  private lateinit var factory: AnoteVoceViewModelFactory

  private var fakeTime = 1_700_000_000_000L

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database =
      Room.inMemoryDatabaseBuilder(context, AnoteVoceDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    val dispatcher = UnconfinedTestDispatcher()
    val dispatchers =
      object : DispatcherProvider {
        override val main: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
      }
    val clock =
      object : ClockProvider {
        override fun nowMillis(): Long = ++fakeTime
      }

    folderRepository =
      FolderRepositoryImpl(
        database = database,
        folderDao = database.folderDao(),
        noteDao = database.noteDao(),
        dispatchers = dispatchers,
        clock = clock,
      )
    noteRepository =
      NoteRepositoryImpl(
        database = database,
        noteDao = database.noteDao(),
        folderDao = database.folderDao(),
        dispatchers = dispatchers,
        clock = clock,
      )
    preferencesRepository =
      PreferencesRepositoryImpl(
        database = database,
        appPreferencesDao = database.appPreferencesDao(),
        dispatchers = dispatchers,
        clock = clock,
      )

    factory =
      AnoteVoceViewModelFactory(
        folderRepository = folderRepository,
        noteRepository = noteRepository,
        preferencesRepository = preferencesRepository,
      )
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun `compact screen uses modal drawer, shows empty folders message and Back closes open drawer`() {
    var backDispatcher: androidx.activity.OnBackPressedDispatcher? = null

    composeTestRule.setContent {
      backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
      Box(modifier = Modifier.size(width = 360.dp, height = 760.dp)) {
        AnoteVoceApp(viewModelFactory = factory)
      }
    }

    composeTestRule.onNodeWithTag("drawer_menu_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("drawer_menu_button").performClick()

    composeTestRule.onNodeWithTag("drawer_folders_header").assertIsDisplayed()
    composeTestRule.onNodeWithText("Pastas").assertIsDisplayed()
    composeTestRule.onNodeWithTag("drawer_folders_empty_label").assertIsDisplayed()
    composeTestRule.onNodeWithText("Nenhuma pasta criada").assertIsDisplayed()
    composeTestRule.onNodeWithTag("drawer_item_all_notes").assertIsSelected()

    // Navega para Fixadas e valida alteração semântica
    composeTestRule.onNodeWithTag("drawer_item_pinned").performClick()
    composeTestRule.onNodeWithTag("top_bar_title").assert(
      SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString("Fixadas")))
    )

    // Abre o drawer e pressiona Voltar -> fecha o menu sem sair de Fixadas
    composeTestRule.onNodeWithTag("drawer_menu_button").performClick()
    composeTestRule.onNodeWithTag("drawer_item_pinned").assertIsSelected()

    composeTestRule.runOnIdle { backDispatcher?.onBackPressed() }
    composeTestRule.waitForIdle()

    composeTestRule
      .onNodeWithTag("top_bar_title")
      .assertIsDisplayed()
      .assertTextEquals("Fixadas")
    composeTestRule.onNodeWithText("Nenhuma nota fixada ainda").assertIsDisplayed()
  }

  @Test
  fun `expanded screen shows collapsible persistent sidebar and navigates to custom folders`() {
    runBlocking {
      val app = ApplicationProvider.getApplicationContext<Context>() as AnoteVoceApplication
      val sharedLocalPrefs = app.container.localDevicePreferences
      sharedLocalPrefs.setSidebarExpanded(true)
      assertEquals(true, sharedLocalPrefs.observeSidebarExpanded().first { it == true })

      val workFolder = folderRepository.createFolder("Trabalho")
      val studyFolder = folderRepository.createFolder("Estudos")
      noteRepository.createNote(title = "Pauta semanal", folderId = workFolder.id)

      val testFactory =
        AnoteVoceViewModelFactory(
          folderRepository = folderRepository,
          noteRepository = noteRepository,
          preferencesRepository = preferencesRepository,
          localDevicePreferences = sharedLocalPrefs,
        )

      composeTestRule.setContent {
        Box(modifier = Modifier.size(width = 900.dp, height = 760.dp)) {
          AnoteVoceApp(viewModelFactory = testFactory)
        }
      }

      // Painel persistente inicia expandido em 900.dp e exibe pastas reais
      composeTestRule.waitUntil(timeoutMillis = 3_000L) {
        composeTestRule.onAllNodesWithTag("drawer_panel_expanded").fetchSemanticsNodes().isNotEmpty()
      }
      composeTestRule.onNodeWithTag("drawer_panel_expanded").assertIsDisplayed()
      composeTestRule.onNodeWithTag("drawer_item_folder_${workFolder.id}").assertIsDisplayed()
      composeTestRule.onNodeWithTag("drawer_item_folder_${studyFolder.id}").assertIsDisplayed()

      // Recolhe e expande o painel lateral
      composeTestRule.onNodeWithTag("drawer_collapse_toggle").performClick()
      composeTestRule.waitUntil(timeoutMillis = 3_000L) {
        composeTestRule.onAllNodesWithTag("drawer_panel_collapsed").fetchSemanticsNodes().isNotEmpty()
      }
      composeTestRule.onNodeWithTag("drawer_panel_collapsed").assertIsDisplayed()

      composeTestRule.onNodeWithTag("drawer_collapse_toggle").performClick()
      composeTestRule.waitUntil(timeoutMillis = 3_000L) {
        composeTestRule.onAllNodesWithTag("drawer_panel_expanded").fetchSemanticsNodes().isNotEmpty()
      }
      composeTestRule.onNodeWithTag("drawer_panel_expanded").assertIsDisplayed()

      // Navega para pasta com nota e depois para pasta vazia
      composeTestRule.onNodeWithTag("drawer_item_folder_${workFolder.id}").performClick()
      composeTestRule.onNodeWithTag("drawer_item_folder_${workFolder.id}").assertIsSelected()
      composeTestRule.onNodeWithText("Pauta semanal").assertIsDisplayed()

      composeTestRule.onNodeWithTag("drawer_item_folder_${studyFolder.id}").performClick()
      composeTestRule.onNodeWithText("Esta pasta ainda não tem notas").assertIsDisplayed()
    }
  }

  @Test
  fun `persists sidebar expanded preference in shared LocalDevicePreferencesDataSource across ViewModel recreation`() {
    runBlocking {
      val app = ApplicationProvider.getApplicationContext<Context>() as AnoteVoceApplication
      val sharedLocalPrefs = app.container.localDevicePreferences
      assertSame(sharedLocalPrefs, app.container.localDevicePreferences)

      sharedLocalPrefs.setSidebarExpanded(true)
      assertEquals(true, sharedLocalPrefs.observeSidebarExpanded().first { it == true })

      var sessionKey by mutableIntStateOf(0)

      composeTestRule.setContent {
        key(sessionKey) {
          val owner = remember(sessionKey) {
            object : ViewModelStoreOwner {
              override val viewModelStore = ViewModelStore()
            }
          }
          DisposableEffect(owner) {
            onDispose { owner.viewModelStore.clear() }
          }
          val sessionFactory =
            remember(sessionKey) {
              AnoteVoceViewModelFactory(
                folderRepository = folderRepository,
                noteRepository = noteRepository,
                preferencesRepository = preferencesRepository,
                localDevicePreferences = app.container.localDevicePreferences,
              )
            }
          CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
            Box(modifier = Modifier.size(width = 900.dp, height = 760.dp)) {
              AnoteVoceApp(viewModelFactory = sessionFactory)
            }
          }
        }
      }

      // Com preferência persistida como true, o painel inicia expandido
      composeTestRule.onNodeWithTag("drawer_panel_expanded").assertIsDisplayed()

      // Recolhe o painel lateral e confirma persistência real no DataStore
      composeTestRule.onNodeWithTag("drawer_collapse_toggle").performClick()
      composeTestRule.waitUntil(timeoutMillis = 3_000L) {
        composeTestRule.onAllNodesWithTag("drawer_panel_collapsed").fetchSemanticsNodes().isNotEmpty()
      }
      assertEquals(false, sharedLocalPrefs.observeSidebarExpanded().first { it == false })

      // Recria a interface e o ViewModel usando o mesmo armazenamento compartilhado
      composeTestRule.runOnIdle { sessionKey++ }
      composeTestRule.waitUntil(timeoutMillis = 3_000L) {
        composeTestRule.onAllNodesWithTag("drawer_panel_collapsed").fetchSemanticsNodes().isNotEmpty()
      }
      composeTestRule.onNodeWithTag("drawer_panel_collapsed").assertIsDisplayed()
    }
  }
}
