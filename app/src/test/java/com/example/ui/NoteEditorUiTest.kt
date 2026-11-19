package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performTextInputSelection
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.coroutines.DispatcherProvider
import com.example.core.time.ClockProvider
import com.example.data.local.database.AnoteVoceDatabase
import com.example.data.repository.FolderRepositoryImpl
import com.example.data.repository.NoteRecoveryDraftRepositoryImpl
import com.example.data.repository.NoteRepositoryImpl
import com.example.data.repository.PreferencesRepositoryImpl
import com.example.domain.model.AppThemeMode
import com.example.domain.model.BulletListItemBlock
import com.example.domain.model.ChecklistItemBlock
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.NoteRecoveryDraftInput
import com.example.domain.model.NoteRecoveryDraftKeys
import com.example.domain.model.NumberedListItemBlock
import com.example.domain.model.ParagraphBlock
import com.example.domain.model.TextFormatStyle
import com.example.ui.components.LocalEditorImeVisibility
import com.example.ui.viewmodel.AnoteVoceViewModelFactory
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class NoteEditorUiTest {

  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var context: Context
  private lateinit var database: AnoteVoceDatabase
  private lateinit var folderRepository: FolderRepositoryImpl
  private lateinit var noteRepository: NoteRepositoryImpl
  private lateinit var draftRepository: NoteRecoveryDraftRepositoryImpl
  private lateinit var preferencesRepository: PreferencesRepositoryImpl
  private lateinit var factory: AnoteVoceViewModelFactory

  private val fakeTime = AtomicLong(1_700_600_000_000L)
  private val clock =
    object : ClockProvider {
      override fun nowMillis(): Long = fakeTime.incrementAndGet()
    }

  private val dispatchers =
    object : DispatcherProvider {
      override val main: CoroutineDispatcher = Dispatchers.Main.immediate
      override val io: CoroutineDispatcher = Dispatchers.IO
      override val default: CoroutineDispatcher = Dispatchers.Default
    }

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    database =
      Room.inMemoryDatabaseBuilder(context, AnoteVoceDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor { it.run() }
        .setTransactionExecutor { it.run() }
        .build()

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
    draftRepository =
      NoteRecoveryDraftRepositoryImpl(
        database = database,
        draftDao = database.noteRecoveryDraftDao(),
        dispatchers = dispatchers,
        clock = clock,
        noteDao = database.noteDao(),
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
        noteRecoveryDraftRepository = draftRepository,
        dispatchers = dispatchers,
        clock = clock,
        autosaveDebounceMillis = 50L,
      )
  }

  @After
  fun tearDown() {
    database.close()
  }

  private fun waitForTag(tag: String) {
    composeTestRule.waitUntil(timeoutMillis = 5_000L) {
      composeTestRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun waitForTagGone(tag: String) {
    composeTestRule.waitUntil(timeoutMillis = 5_000L) {
      composeTestRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
    }
  }

  @Test
  fun `create note from Home footer saves note, empty new note creates none, and reopen starts in reading mode`() {
    runBlocking {
      composeTestRule.setContent {
        CompositionLocalProvider(LocalEditorImeVisibility provides true) {
          AnoteVoceApp(viewModelFactory = factory)
        }
      }

      // 1. Abrir e sair sem digitar nada -> nenhuma nota criada
      composeTestRule.onNodeWithTag("home_create_note_button").assertIsDisplayed().performClick()
      waitForTag("note_editor_screen")
      waitForTag("editor_empty_content_field")
      composeTestRule.onNodeWithTag("editor_back_button").performClick()

      waitForTag("home_create_note_button")
      assertTrue(noteRepository.observeAllActiveNotes().first().isEmpty())

      // 2. Criar nota com título e conteúdo
      composeTestRule.onNodeWithTag("home_create_note_button").performClick()
      waitForTag("editor_title_field")
      composeTestRule.onNodeWithTag("editor_title_field").performTextInput("Compras da semana")
      composeTestRule.onNodeWithTag("editor_empty_content_field").performTextInput("Leite e café")
      waitForTag("editor_block_field_0")

      composeTestRule.onNodeWithTag("editor_back_button").performClick()
      waitForTag("home_create_note_button")

      val created = noteRepository.observeAllActiveNotes().first().single()
      assertEquals("Compras da semana", created.title)
      assertEquals("Leite e café", created.plainTextPreview)
      assertNull(created.folderId)

      waitForTag("note_card_${created.id}")
      composeTestRule.onNodeWithTag("note_card_${created.id}").performClick()
      waitForTag("note_editor_screen")

      // 3. Nota existente abre em modo leitura (toolbar oculta mesmo com IME=true)
      waitForTag("editor_block_readonly_0")
      composeTestRule.onAllNodesWithTag("editor_formatting_toolbar").assertCountEquals(0)
      composeTestRule.onAllNodesWithTag("editor_save_in_chip").assertCountEquals(0)
      composeTestRule.onNodeWithTag("editor_existing_folder_badge").assertIsDisplayed()

      // 4. Tocar no conteúdo entra em edição e exibe toolbar
      composeTestRule.onNodeWithTag("editor_block_readonly_0").performClick()
      waitForTag("editor_block_field_0")
      waitForTag("editor_formatting_toolbar")

      composeTestRule
        .onNodeWithTag("editor_block_field_0")
        .performTextReplacement("Leite, café e pão")
      composeTestRule.onNodeWithTag("editor_back_button").performClick()
      waitForTag("home_create_note_button")

      val updated = noteRepository.getNoteById(created.id)!!
      assertEquals("Leite, café e pão", updated.plainTextPreview)
    }
  }

  @Test
  fun `untitled note saves cleanly and displays first line preview on Home`() {
    runBlocking {
      composeTestRule.setContent { AnoteVoceApp(viewModelFactory = factory) }

      composeTestRule.onNodeWithTag("home_create_note_button").performClick()
      waitForTag("editor_empty_content_field")
      composeTestRule.onNodeWithTag("editor_empty_content_field").performTextInput("Primeira linha livre")
      waitForTag("editor_block_field_0")

      composeTestRule.onNodeWithTag("editor_back_button").performClick()
      waitForTag("home_create_note_button")

      val saved = noteRepository.observeAllActiveNotes().first().single()
      assertEquals("", saved.title)
      assertEquals("Primeira linha livre", saved.plainTextPreview)
      composeTestRule.onNodeWithTag("note_card_${saved.id}").assertIsDisplayed()
      composeTestRule.onNodeWithText("Primeira linha livre").assertIsDisplayed()
    }
  }

  @Test
  fun `formatting toolbar reacts to IME visibility and applies Bold, Italic, Underline spans`() {
    runBlocking {
      var imeVisible by mutableStateOf(false)
      composeTestRule.setContent {
        CompositionLocalProvider(LocalEditorImeVisibility provides imeVisible) {
          AnoteVoceApp(viewModelFactory = factory)
        }
      }

      composeTestRule.onNodeWithTag("home_create_note_button").performClick()
      waitForTag("editor_empty_content_field")

      // Sem teclado -> toolbar oculta
      composeTestRule.onAllNodesWithTag("editor_formatting_toolbar").assertCountEquals(0)

      // Com teclado -> toolbar visível
      imeVisible = true
      waitForTag("editor_formatting_toolbar")

      composeTestRule.onNodeWithTag("editor_empty_content_field").performTextInput("Texto rico")
      waitForTag("editor_block_field_0")

      // Seleciona "Texto" (0..5) e aplica Negrito + Itálico + Sublinhado
      composeTestRule
        .onNodeWithTag("editor_block_field_0")
        .performTextInputSelection(TextRange(0, 5))
      composeTestRule.onNodeWithTag("format_bold_button").performClick()
      composeTestRule.onNodeWithTag("format_italic_button").performClick()
      composeTestRule.onNodeWithTag("format_underline_button").performClick()
      composeTestRule.waitForIdle()

      composeTestRule.onNodeWithTag("editor_back_button").performClick()
      waitForTag("home_create_note_button")

      val saved = noteRepository.observeAllActiveNotes().first().single()
      val spans = saved.contentOrNull!!.blocks.single().spans
      assertTrue(spans.any { it.style == TextFormatStyle.BOLD && it.start == 0 && it.end == 5 })
      assertTrue(spans.any { it.style == TextFormatStyle.ITALIC && it.start == 0 && it.end == 5 })
      assertTrue(spans.any { it.style == TextFormatStyle.UNDERLINE && it.start == 0 && it.end == 5 })
    }
  }

  @Test
  fun `lists, checklists, enter exit, checkbox toggle, and undo-redo work end to end`() {
    runBlocking {
      composeTestRule.setContent {
        CompositionLocalProvider(LocalEditorImeVisibility provides true) {
          AnoteVoceApp(viewModelFactory = factory)
        }
      }

      composeTestRule.onNodeWithTag("home_create_note_button").performClick()
      waitForTag("editor_empty_content_field")

      // Converter para checklist e digitar dois itens
      composeTestRule.onNodeWithTag("format_checklist_button").performClick()
      waitForTag("editor_block_field_0")
      composeTestRule.onNodeWithTag("editor_block_field_0").performTextInput("Item A\nItem B")
      waitForTag("editor_block_field_1")

      // Enter em linha vazia sai da lista para parágrafo
      composeTestRule.onNodeWithTag("editor_block_field_1").performTextInput("\n\n")
      waitForTag("editor_block_field_2")

      // Undo & Redo
      composeTestRule.onNodeWithTag("editor_undo_button").assertIsEnabled().performClick()
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("editor_redo_button").assertIsEnabled().performClick()
      composeTestRule.waitForIdle()

      // Marcar primeiro checklist -> vai para o final da sequência contígua
      composeTestRule.onNodeWithTag("editor_checklist_checkbox_0").performClick()
      composeTestRule.waitForIdle()

      // Converter último parágrafo em lista numerada e depois marcador
      composeTestRule.onNodeWithTag("editor_block_field_2").performClick()
      composeTestRule.onNodeWithTag("editor_block_field_2").performTextInput("Passo")
      composeTestRule.onNodeWithTag("format_numbered_button").performClick()
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("format_bullet_button").performClick()
      composeTestRule.waitForIdle()

      composeTestRule.onNodeWithTag("editor_back_button").performClick()
      waitForTag("home_create_note_button")

      val saved = noteRepository.observeAllActiveNotes().first().single()
      val blocks = saved.contentOrNull!!.blocks
      val checklists = blocks.filterIsInstance<ChecklistItemBlock>()
      assertEquals(2, checklists.size)
      assertFalse(checklists[0].isChecked)
      assertEquals("Item B", checklists[0].text)
      assertTrue(checklists[1].isChecked)
      assertEquals("Item A", checklists[1].text)
      assertTrue(blocks.last() is BulletListItemBlock)
    }
  }

  @Test
  fun `origin folder is preserved on creation, changeable on new note, and pinned view uses root`() {
    runBlocking {
      val estudos = folderRepository.createFolder("Estudos")
      val trabalho = folderRepository.createFolder("Trabalho")

      composeTestRule.setContent { AnoteVoceApp(viewModelFactory = factory) }

      // Navega para pasta Estudos e cria nota -> "Salvar em: Estudos"
      composeTestRule.onNodeWithTag("drawer_menu_button").performClick()
      composeTestRule.onNodeWithTag("drawer_item_folder_${estudos.id}").performClick()
      composeTestRule.onNodeWithTag("home_create_note_button").performClick()
      waitForTag("editor_save_in_chip")
      composeTestRule.onNodeWithTag("editor_save_in_chip").assertTextContains("Salvar em: Estudos")

      // Altera destino para Trabalho no seletor
      composeTestRule.onNodeWithTag("editor_save_in_chip").performClick()
      waitForTag("folder_picker_item_${trabalho.id}")
      composeTestRule.onNodeWithTag("folder_picker_item_${trabalho.id}").performClick()
      composeTestRule.onNodeWithTag("editor_save_in_chip").assertTextContains("Salvar em: Trabalho")

      composeTestRule.onNodeWithTag("editor_empty_content_field").performTextInput("Nota movida na criação")
      composeTestRule.onNodeWithTag("editor_back_button").performClick()
      waitForTag("home_create_note_button")

      val createdInTrabalho = noteRepository.observeAllActiveNotes().first().single()
      assertEquals(trabalho.id, createdInTrabalho.folderId)

      // Criar nota a partir de Fixadas usa "Todas no meu phone"
      composeTestRule.onNodeWithTag("drawer_menu_button").performClick()
      composeTestRule.onNodeWithTag("drawer_item_pinned").performClick()
      composeTestRule.onNodeWithTag("home_create_note_button").performClick()
      waitForTag("editor_save_in_chip")
      composeTestRule
        .onNodeWithTag("editor_save_in_chip")
        .assertTextContains("Salvar em: Todas no meu phone")

      composeTestRule.onNodeWithTag("editor_back_button").performClick()
      waitForTag("home_create_note_button")

      // Na Lixeira o botão de criação fica oculto
      composeTestRule.onNodeWithTag("drawer_menu_button").performClick()
      composeTestRule.onNodeWithTag("drawer_item_trash").performClick()
      composeTestRule.onAllNodesWithTag("home_create_note_button").assertCountEquals(0)
    }
  }

  @Test
  fun `paper style respects default preference and changes visually without altering text`() {
    runBlocking {
      preferencesRepository.updateDefaultNotePaper(NotePaperStyle.PONTILHADO)

      composeTestRule.setContent { AnoteVoceApp(viewModelFactory = factory) }

      composeTestRule.onNodeWithTag("home_create_note_button").performClick()
      waitForTag("editor_paper_surface_PONTILHADO")

      composeTestRule.onNodeWithTag("editor_empty_content_field").performTextInput("Texto intacto")
      waitForTag("editor_block_field_0")

      composeTestRule.onNodeWithTag("editor_paper_button").performClick()
      waitForTag("paper_option_OCEAN_BREEZE")
      composeTestRule.onNodeWithTag("paper_option_OCEAN_BREEZE").performClick()
      waitForTag("editor_paper_surface_OCEAN_BREEZE")

      composeTestRule.onNodeWithTag("editor_back_button").performClick()
      waitForTag("home_create_note_button")

      val saved = noteRepository.observeAllActiveNotes().first().single()
      assertEquals(NotePaperStyle.OCEAN_BREEZE, saved.paperStyle)
      assertEquals("Texto intacto", saved.plainTextPreview)
    }
  }

  @Test
  fun `overflow menu supports exact 3 actions - Pin, Share Intent, and single-confirm Soft Delete`() {
    runBlocking {
      val note =
        noteRepository.createNote(
          title = "Nota Menu",
          content =
            NoteContentDocument(
              blocks =
                listOf(
                  NumberedListItemBlock(id = "n1", text = "Primeiro"),
                  ChecklistItemBlock(id = "c1", text = "Feito", isChecked = true),
                )
            ),
        )

      composeTestRule.setContent { AnoteVoceApp(viewModelFactory = factory) }

      waitForTag("note_card_${note.id}")
      composeTestRule.onNodeWithTag("note_card_${note.id}").performClick()
      waitForTag("editor_more_button")

      // 1. Abrir menu -> exatamente 3 ações e fixar nota
      composeTestRule.onNodeWithTag("editor_more_button").performClick()
      composeTestRule.onNodeWithTag("editor_menu_share").assertIsDisplayed()
      composeTestRule.onNodeWithTag("editor_menu_delete").assertIsDisplayed()
      composeTestRule.onNodeWithTag("editor_menu_pin").assertIsDisplayed().performClick()

      waitForTag("editor_pinned_badge")
      assertTrue(noteRepository.getNoteById(note.id)!!.isPinned)

      // 2. Compartilhar emite Intent.ACTION_SEND via chooser
      composeTestRule.onNodeWithTag("editor_more_button").performClick()
      composeTestRule.onNodeWithTag("editor_menu_share").performClick()
      composeTestRule.waitForIdle()

      val app = ApplicationProvider.getApplicationContext<Application>()
      val startedIntent = Shadows.shadowOf(app).nextStartedActivity
      assertNotNull(startedIntent)
      assertEquals(Intent.ACTION_CHOOSER, startedIntent.action)
      @Suppress("DEPRECATION")
      val innerIntent = startedIntent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
      assertNotNull(innerIntent)
      assertEquals(Intent.ACTION_SEND, innerIntent!!.action)
      assertEquals("Nota Menu", innerIntent.getStringExtra(Intent.EXTRA_SUBJECT))
      assertEquals(
        "Nota Menu\n\n1. Primeiro\n[x] Feito",
        innerIntent.getStringExtra(Intent.EXTRA_TEXT),
      )

      // 3. Excluir pede confirmação única -> vai para Apagados e volta à Home
      composeTestRule.onNodeWithTag("editor_more_button").performClick()
      composeTestRule.onNodeWithTag("editor_menu_delete").performClick()
      waitForTag("delete_confirm_button")
      composeTestRule.onNodeWithTag("delete_confirm_button").performClick()

      waitForTag("home_create_note_button")
      waitForTagGone("note_card_${note.id}")
      assertTrue(noteRepository.getNoteById(note.id)!!.isDeleted)
      assertEquals(1, noteRepository.observeDeletedNotes().first().size)
    }
  }

  @Test
  fun `pending recovery draft banner on AllNotes resumes draft safely`() {
    runBlocking {
      val sessionId = "crash-session-1"
      val draftId = NoteRecoveryDraftKeys.forNewNote(sessionId)
      draftRepository.saveCheckpoint(
        NoteRecoveryDraftInput(
          draftId = draftId,
          targetNoteId = null,
          sessionId = sessionId,
          folderId = null,
          fallbackSourceFolderId = null,
          isPinned = false,
          title = "Rascunho interrompido",
          content =
            NoteContentDocument(blocks = listOf(ParagraphBlock(id = "r1", text = "Recuperado com segurança"))),
          paperStyle = NotePaperStyle.LINHAS,
          baseRevision = 0L,
          checkpointRevision = 1L,
          baseNoteUpdatedAt = null,
        )
      )

      composeTestRule.setContent { AnoteVoceApp(viewModelFactory = factory) }

      waitForTag("home_pending_recovery_banner")
      composeTestRule.onNodeWithTag("home_recovery_resume_button").performClick()
      waitForTag("editor_recovered_badge")
      composeTestRule.onNodeWithText("Recuperado com segurança").assertIsDisplayed()

      composeTestRule.onNodeWithTag("editor_back_button").performClick()
      waitForTag("home_create_note_button")

      val saved = noteRepository.observeAllActiveNotes().first().single()
      assertEquals("Rascunho interrompido", saved.title)
      assertEquals("Recuperado com segurança", saved.plainTextPreview)
      assertTrue(draftRepository.getAllCheckpoints().isEmpty())
    }
  }

  @Test
  fun `compact screen 360x760 at fontScale 1_75f across all 4 themes preserves 48dp targets`() {
    runBlocking {
      preferencesRepository.updateThemeMode(AppThemeMode.MODO_NOITE)

      composeTestRule.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(
          LocalDensity provides Density(density.density, fontScale = 1.75f),
          LocalEditorImeVisibility provides true,
        ) {
          Box(modifier = Modifier.size(width = 360.dp, height = 760.dp)) {
            AnoteVoceApp(viewModelFactory = factory)
          }
        }
      }

      composeTestRule
        .onNodeWithTag("home_create_note_button")
        .assertIsDisplayed()
        .assertWidthIsAtLeast(48.dp)
        .assertHeightIsAtLeast(48.dp)
        .performClick()

      waitForTag("note_editor_screen")
      waitForTag("editor_formatting_toolbar")

      composeTestRule
        .onNodeWithTag("editor_back_button")
        .assertIsDisplayed()
        .assertWidthIsAtLeast(48.dp)
        .assertHeightIsAtLeast(48.dp)
      composeTestRule
        .onNodeWithTag("editor_undo_button")
        .assertIsDisplayed()
        .assertWidthIsAtLeast(48.dp)
        .assertHeightIsAtLeast(48.dp)
      composeTestRule
        .onNodeWithTag("editor_redo_button")
        .assertIsDisplayed()
        .assertWidthIsAtLeast(48.dp)
        .assertHeightIsAtLeast(48.dp)
      composeTestRule
        .onNodeWithTag("editor_paper_button")
        .assertIsDisplayed()
        .assertWidthIsAtLeast(48.dp)
        .assertHeightIsAtLeast(48.dp)
      composeTestRule
        .onNodeWithTag("editor_more_button")
        .assertIsDisplayed()
        .assertWidthIsAtLeast(48.dp)
        .assertHeightIsAtLeast(48.dp)
      composeTestRule
        .onNodeWithTag("format_bold_button")
        .assertIsDisplayed()
        .assertWidthIsAtLeast(48.dp)
        .assertHeightIsAtLeast(48.dp)
      composeTestRule
        .onNodeWithTag("editor_save_in_chip")
        .assertIsDisplayed()
        .assertHeightIsAtLeast(48.dp)

      preferencesRepository.updateThemeMode(AppThemeMode.MODO_NATURAL)
      composeTestRule.waitForIdle()
      preferencesRepository.updateThemeMode(AppThemeMode.MODO_CEU)
      composeTestRule.waitForIdle()
      preferencesRepository.updateThemeMode(AppThemeMode.MODO_DIA)
      composeTestRule.waitForIdle()

      composeTestRule.onNodeWithTag("editor_back_button").performClick()
      waitForTag("home_create_note_button")
    }
  }
}
