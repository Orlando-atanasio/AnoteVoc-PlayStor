package com.example.ui

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.coroutines.DispatcherProvider
import com.example.core.time.ClockProvider
import com.example.data.local.database.AnoteVoceDatabase
import com.example.data.local.entity.NoteEntity
import com.example.data.repository.FolderRepositoryImpl
import com.example.data.repository.NoteRepositoryImpl
import com.example.data.repository.PreferencesRepositoryImpl
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.ParagraphBlock
import com.example.domain.repository.FolderDeleteMode
import com.example.ui.viewmodel.AnoteVoceViewModelFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class HomeAndEmptyStatesUiTest {

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
  fun `displays approved empty states across AllNotes, Pinned, Trash without duplicate title, and Folder`() {
    runBlocking {
      val emptyFolder = folderRepository.createFolder("Estudos")

      composeTestRule.setContent { AnoteVoceApp(viewModelFactory = factory) }

      // Todas no meu phone vazio
      composeTestRule
        .onNodeWithTag("top_bar_title")
        .assertIsDisplayed()
        .assertTextEquals("Todas no meu phone")
      composeTestRule.onNodeWithText("Que bom ter você por aqui!").assertIsDisplayed()
      composeTestRule.onNodeWithText("Nenhuma nota por aqui").assertIsDisplayed()
      composeTestRule.onAllNodesWithText("Criar minha primeira nota").assertCountEquals(0)

      // Fixadas vazio
      composeTestRule.onNodeWithTag("drawer_menu_button").performClick()
      composeTestRule.onNodeWithTag("drawer_item_pinned").performClick()
      composeTestRule
        .onNodeWithTag("top_bar_title")
        .assertIsDisplayed()
        .assertTextEquals("Fixadas")
      composeTestRule.onNodeWithText("Nenhuma nota fixada ainda").assertIsDisplayed()

      // Apagados vazio: ícone + título "Apagados" apenas na TopAppBar (sem duplicação e sem texto extra)
      composeTestRule.onNodeWithTag("drawer_menu_button").performClick()
      composeTestRule.onNodeWithTag("drawer_item_trash").performClick()
      composeTestRule
        .onNodeWithTag("top_bar_title")
        .assertIsDisplayed()
        .assertTextEquals("Apagados")
      composeTestRule
        .onAllNodes(hasText("Apagados") and hasAnyAncestor(hasTestTag("notes_area_root")))
        .assertCountEquals(0)
      composeTestRule.onNodeWithTag("empty_trash_icon").assertIsDisplayed()
      composeTestRule.onNodeWithTag("empty_state_text").assertDoesNotExist()

      // Pasta personalizada vazia
      composeTestRule.onNodeWithTag("drawer_menu_button").performClick()
      composeTestRule.onNodeWithTag("drawer_item_folder_${emptyFolder.id}").performClick()
      composeTestRule
        .onNodeWithTag("top_bar_title")
        .assertIsDisplayed()
        .assertTextEquals("Estudos")
      composeTestRule.onNodeWithText("Esta pasta ainda não tem notas").assertIsDisplayed()
    }
  }

  @Test
  fun `displays active notes respecting pinned-first order and protects RequiresHandling content`() {
    runBlocking {
      val recentUnpinned =
        noteRepository.createNote(
          title = "Recente comum",
          content =
            NoteContentDocument(
              blocks = listOf(ParagraphBlock(id = "p1", text = "Texto comum"))
            ),
          isPinned = false,
        )
      val olderPinned =
        noteRepository.createNote(
          title = "Fixada antiga",
          content =
            NoteContentDocument(
              blocks = listOf(ParagraphBlock(id = "p2", text = "Texto fixado"))
            ),
          isPinned = true,
        )

      val futureRaw =
        """{"schemaVersion":99,"blocks":[{"type":"paragraph","id":"f1","text":"Futuro","spans":[]}]}"""
      val corruptedRaw = "{json-corrompido"
      val unknownRaw = """{"schemaVersion":1,"blocks":[{"type":"grafico_v2","id":"u1"}]}"""

      val futureId =
        database.noteDao().insert(
          NoteEntity(
            uid = "u-future",
            title = "Nota futura",
            contentJson = futureRaw,
            plainTextPreview = "",
            createdAt = 10L,
            updatedAt = 10L,
          )
        )
      val corruptedId =
        database.noteDao().insert(
          NoteEntity(
            uid = "u-corrupted",
            title = "Nota corrompida",
            contentJson = corruptedRaw,
            plainTextPreview = "",
            createdAt = 11L,
            updatedAt = 11L,
          )
        )
      val unknownId =
        database.noteDao().insert(
          NoteEntity(
            uid = "u-unknown",
            title = "Nota bloco novo",
            contentJson = unknownRaw,
            plainTextPreview = "",
            createdAt = 12L,
            updatedAt = 12L,
          )
        )

      composeTestRule.setContent { AnoteVoceApp(viewModelFactory = factory) }

      // Verifica exibição dos cards válidos e ordenação (fixada no topo)
      composeTestRule.onNodeWithTag("note_card_${olderPinned.id}").assertIsDisplayed()
      composeTestRule
        .onNodeWithTag("note_pinned_badge_${olderPinned.id}", useUnmergedTree = true)
        .assertIsDisplayed()
      composeTestRule.onNodeWithTag("note_card_${recentUnpinned.id}").assertIsDisplayed()

      val pinnedBounds =
        composeTestRule.onNodeWithTag("note_card_${olderPinned.id}").fetchSemanticsNode().boundsInRoot
      val unpinnedBounds =
        composeTestRule.onNodeWithTag("note_card_${recentUnpinned.id}").fetchSemanticsNode().boundsInRoot
      assert(pinnedBounds.top < unpinnedBounds.top) {
        "Nota fixada deveria aparecer acima da nota não fixada mais recente"
      }

      // Verifica exibição segura sem quebra para os 3 casos de RequiresHandling e preservação intacta no banco
      composeTestRule
        .onNodeWithTag("notes_layout_list")
        .performScrollToNode(hasTestTag("note_card_$futureId"))
      composeTestRule
        .onNodeWithTag("note_unreadable_notice_$futureId", useUnmergedTree = true)
        .assertIsDisplayed()

      composeTestRule
        .onNodeWithTag("notes_layout_list")
        .performScrollToNode(hasTestTag("note_card_$corruptedId"))
      composeTestRule
        .onNodeWithTag("note_unreadable_notice_$corruptedId", useUnmergedTree = true)
        .assertIsDisplayed()

      composeTestRule
        .onNodeWithTag("notes_layout_list")
        .performScrollToNode(hasTestTag("note_card_$unknownId"))
      composeTestRule
        .onNodeWithTag("note_unreadable_notice_$unknownId", useUnmergedTree = true)
        .assertIsDisplayed()

      assertEquals(futureRaw, database.noteDao().getById(futureId)?.contentJson)
      assertEquals(corruptedRaw, database.noteDao().getById(corruptedId)?.contentJson)
      assertEquals(unknownRaw, database.noteDao().getById(unknownId)?.contentJson)
    }
  }

  @Test
  fun `deleted open folder shows unavailable state without crashing and preserves navigation`() {
    runBlocking {
      val tempFolder = folderRepository.createFolder("Temporária")
      noteRepository.createNote(
        title = "Nota da pasta temporária",
        folderId = tempFolder.id,
      )

      composeTestRule.setContent { AnoteVoceApp(viewModelFactory = factory) }

      // Navega para a pasta personalizada e confirma exibição
      composeTestRule.onNodeWithTag("drawer_menu_button").performClick()
      composeTestRule.onNodeWithTag("drawer_item_folder_${tempFolder.id}").performClick()
      composeTestRule
        .onNodeWithTag("top_bar_title")
        .assertIsDisplayed()
        .assertTextEquals("Temporária")
      composeTestRule.onNodeWithText("Nota da pasta temporária").assertIsDisplayed()

      // Exclui a pasta via repositório enquanto ela está aberta na UI
      folderRepository.deleteFolder(tempFolder.id, FolderDeleteMode.FOLDER_AND_CONTENT)

      composeTestRule.waitUntil(timeoutMillis = 3_000L) {
        composeTestRule.onAllNodesWithText("Pasta indisponível").fetchSemanticsNodes().isNotEmpty()
      }

      composeTestRule
        .onNodeWithTag("top_bar_title")
        .assertIsDisplayed()
        .assertTextEquals("Pasta")
      composeTestRule.onNodeWithText("Pasta indisponível").assertIsDisplayed()

      // Confirma que a navegação continua funcional e a pasta removida sumiu do menu lateral
      composeTestRule.onNodeWithTag("drawer_menu_button").performClick()
      composeTestRule
        .onAllNodesWithTag("drawer_item_folder_${tempFolder.id}")
        .assertCountEquals(0)
      composeTestRule.onNodeWithTag("drawer_item_all_notes").performClick()

      composeTestRule
        .onNodeWithTag("top_bar_title")
        .assertIsDisplayed()
        .assertTextEquals("Todas no meu phone")
      composeTestRule.onNodeWithText("Que bom ter você por aqui!").assertIsDisplayed()
    }
  }
}
