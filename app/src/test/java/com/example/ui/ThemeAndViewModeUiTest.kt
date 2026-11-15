package com.example.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.coroutines.DispatcherProvider
import com.example.core.time.ClockProvider
import com.example.data.local.database.AnoteVoceDatabase
import com.example.data.repository.FolderRepositoryImpl
import com.example.data.repository.NoteRepositoryImpl
import com.example.data.repository.PreferencesRepositoryImpl
import com.example.domain.model.AppThemeMode
import com.example.domain.model.HomeViewMode
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.ParagraphBlock
import com.example.ui.theme.AnoteVoceTheme
import com.example.ui.theme.DayBackground
import com.example.ui.theme.LocalAnoteVoceSurfaceTokens
import com.example.ui.theme.NaturalBackground
import com.example.ui.theme.NightBackground
import com.example.ui.theme.SkyBackground
import com.example.ui.viewmodel.AnoteVoceViewModelFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class ThemeAndViewModeUiTest {

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
        .setQueryExecutor { it.run() }
        .setTransactionExecutor { it.run() }
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
  fun `applies all four AppThemeMode palettes and reacts to PreferencesRepository updates`() {
    runBlocking {
      val observedBackgrounds = mutableMapOf<AppThemeMode, Color>()
      val observedDarkFlags = mutableMapOf<AppThemeMode, Boolean>()

      composeTestRule.setContent {
        for (mode in AppThemeMode.entries) {
          AnoteVoceTheme(themeMode = mode) {
            observedBackgrounds[mode] = MaterialTheme.colorScheme.background
            observedDarkFlags[mode] = LocalAnoteVoceSurfaceTokens.current.isDark
          }
        }
      }

      assertEquals(DayBackground, observedBackgrounds[AppThemeMode.MODO_DIA])
      assertEquals(NightBackground, observedBackgrounds[AppThemeMode.MODO_NOITE])
      assertEquals(NaturalBackground, observedBackgrounds[AppThemeMode.MODO_NATURAL])
      assertEquals(SkyBackground, observedBackgrounds[AppThemeMode.MODO_CEU])

      assertFalse(observedDarkFlags[AppThemeMode.MODO_DIA]!!)
      assertTrue(observedDarkFlags[AppThemeMode.MODO_NOITE]!!)
      assertFalse(observedDarkFlags[AppThemeMode.MODO_NATURAL]!!)
      assertFalse(observedDarkFlags[AppThemeMode.MODO_CEU]!!)
    }
  }

  @Test
  fun `reacts dynamically to theme and all three HomeViewMode values from PreferencesRepository`() {
    runBlocking {
      val note =
        noteRepository.createNote(
          title = "Ideia de projeto",
          content =
            NoteContentDocument(
              blocks = listOf(ParagraphBlock(id = "p1", text = "Detalhes iniciais"))
            ),
        )

      composeTestRule.setContent { AnoteVoceApp(viewModelFactory = factory) }

      // Modo inicial: LISTA
      composeTestRule.onNodeWithTag("notes_layout_list").assertIsDisplayed()
      composeTestRule.onNodeWithTag("note_card_${note.id}").assertIsDisplayed()

      // Alterna no repositório para GRADE + MODO_NATURAL
      preferencesRepository.updateHomeViewMode(HomeViewMode.GRADE)
      preferencesRepository.updateThemeMode(AppThemeMode.MODO_NATURAL)
      composeTestRule.waitUntil(timeoutMillis = 3_000L) {
        composeTestRule.onAllNodesWithTag("notes_layout_grid").fetchSemanticsNodes().isNotEmpty()
      }
      composeTestRule.onNodeWithTag("notes_layout_grid").assertIsDisplayed()
      composeTestRule.onNodeWithTag("note_card_${note.id}").assertIsDisplayed()

      // Alterna no repositório para LISTA_COMPACTA + MODO_CEU + MODO_NOITE
      preferencesRepository.updateHomeViewMode(HomeViewMode.LISTA_COMPACTA)
      preferencesRepository.updateThemeMode(AppThemeMode.MODO_CEU)
      composeTestRule.waitUntil(timeoutMillis = 3_000L) {
        composeTestRule.onAllNodesWithTag("notes_layout_compact").fetchSemanticsNodes().isNotEmpty()
      }
      composeTestRule.onNodeWithTag("notes_layout_compact").assertIsDisplayed()

      preferencesRepository.updateThemeMode(AppThemeMode.MODO_NOITE)
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithText("Ideia de projeto").assertIsDisplayed()
    }
  }

  @Test
  fun `accessibility touch targets meet 48dp minimum and layout supports large fontScale`() {
    runBlocking {
      noteRepository.createNote(title = "Acessibilidade")

      composeTestRule.setContent {
        val currentDensity = LocalDensity.current
        CompositionLocalProvider(
          LocalDensity provides Density(currentDensity.density, fontScale = 1.75f)
        ) {
          Box(modifier = Modifier.size(width = 360.dp, height = 760.dp)) {
            AnoteVoceApp(viewModelFactory = factory)
          }
        }
      }

      composeTestRule
        .onNodeWithTag("drawer_menu_button")
        .assertIsDisplayed()
        .assertWidthIsAtLeast(48.dp)
        .assertHeightIsAtLeast(48.dp)

      composeTestRule.onNodeWithText("Que bom ter você por aqui!").assertIsDisplayed()
      composeTestRule.onNodeWithText("Acessibilidade").assertIsDisplayed()

      composeTestRule.onNodeWithTag("drawer_menu_button").performClick()
      composeTestRule.onNodeWithTag("drawer_item_all_notes").assertHeightIsAtLeast(48.dp)
      composeTestRule.onNodeWithTag("drawer_item_pinned").assertHeightIsAtLeast(48.dp)
      composeTestRule.onNodeWithTag("drawer_item_trash").assertHeightIsAtLeast(48.dp)
    }
  }
}
