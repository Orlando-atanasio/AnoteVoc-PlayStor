package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.coroutines.DispatcherProvider
import com.example.core.time.ClockProvider
import com.example.data.local.database.AnoteVoceDatabase
import com.example.data.local.entity.NoteEntity
import com.example.data.repository.FolderRepositoryImpl
import com.example.data.repository.NoteRepositoryImpl
import com.example.data.repository.PreferencesRepositoryImpl
import com.example.domain.model.AppThemeMode
import com.example.domain.model.ChecklistItemBlock
import com.example.domain.model.FormatSpan
import com.example.domain.model.HomeViewMode
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NoteContentState
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.ParagraphBlock
import com.example.domain.model.TextFormatStyle
import com.example.domain.repository.DuplicateFolderNameException
import com.example.domain.repository.FolderDeleteMode
import com.example.domain.repository.FolderDeletedException
import com.example.domain.repository.FolderNotDeletedException
import com.example.domain.repository.FolderNotFoundException
import com.example.domain.repository.InvalidFolderNameException
import com.example.domain.repository.NoteDeletedException
import com.example.domain.repository.NoteNotDeletedException
import com.example.domain.repository.RestoreFolderResult
import com.example.domain.repository.UnreadableNoteContentException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AnoteVoceRoomPersistenceTest {

  private lateinit var database: AnoteVoceDatabase
  private lateinit var folderRepository: FolderRepositoryImpl
  private lateinit var noteRepository: NoteRepositoryImpl
  private lateinit var preferencesRepository: PreferencesRepositoryImpl

  private var fakeTimeMillis = 1_700_000_000_000L

  private val testClock =
    object : ClockProvider {
      override fun nowMillis(): Long = ++fakeTimeMillis
    }

  private val testDispatcher = UnconfinedTestDispatcher()
  private val testDispatchers =
    object : DispatcherProvider {
      override val main: CoroutineDispatcher = testDispatcher
      override val io: CoroutineDispatcher = testDispatcher
      override val default: CoroutineDispatcher = testDispatcher
    }

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database =
      Room.inMemoryDatabaseBuilder(context, AnoteVoceDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    folderRepository =
      FolderRepositoryImpl(
        database = database,
        folderDao = database.folderDao(),
        noteDao = database.noteDao(),
        dispatchers = testDispatchers,
        clock = testClock,
      )
    noteRepository =
      NoteRepositoryImpl(
        database = database,
        noteDao = database.noteDao(),
        folderDao = database.folderDao(),
        dispatchers = testDispatchers,
        clock = testClock,
      )
    preferencesRepository =
      PreferencesRepositoryImpl(
        database = database,
        appPreferencesDao = database.appPreferencesDao(),
        dispatchers = testDispatchers,
        clock = testClock,
      )
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun `room schema v1 json is exported and accessible in test resources`() {
    val schemaPath = "${AnoteVoceDatabase::class.java.name}/1.json"
    val schemaStream =
      requireNotNull(javaClass.classLoader?.getResourceAsStream(schemaPath)) {
        "Schema JSON não encontrado em $schemaPath"
      }
    val schemaJson = schemaStream.bufferedReader().use { it.readText() }
    assertTrue(schemaJson.contains("\"tableName\": \"folders\""))
    assertTrue(schemaJson.contains("\"tableName\": \"notes\""))
    assertTrue(schemaJson.contains("\"tableName\": \"app_preferences\""))
    assertNotNull(androidx.room.testing.MigrationTestHelper::class.java)
  }

  @Test
  fun `folder uniqueness ignores case and all spaces while preserving accents and original name`() =
    runTest {
      val created = folderRepository.createFolder("  Meu Trabalho  ")
      assertEquals("Meu Trabalho", created.name)

      for (
        duplicate in
          listOf(
            "meutrabalho",
            "MEU   TRABALHO",
            "m e u t r a b a l h o",
            "Meu\u00A0Trabalho",
            "\u00A0meu\ttra\nbalho\u2003",
          )
      ) {
        try {
          folderRepository.createFolder(duplicate)
          fail("Esperava DuplicateFolderNameException para '$duplicate'")
        } catch (_: DuplicateFolderNameException) {
          // esperado
        }
      }

      // Preserva acentos na comparação
      val accented = folderRepository.createFolder("Anotação")
      val unaccented = folderRepository.createFolder("Anotacao")
      assertNotEquals(accented.id, unaccented.id)

      try {
        folderRepository.createFolder("ANOTAÇÃO")
        fail("Esperava DuplicateFolderNameException para 'ANOTAÇÃO'")
      } catch (_: DuplicateFolderNameException) {
        // esperado
      }

      for (blankName in listOf("   ", "\t\r\n", "\u00A0\u202F\u2003\u3000")) {
        try {
          folderRepository.createFolder(blankName)
          fail("Esperava InvalidFolderNameException para nome somente com espaços")
        } catch (_: InvalidFolderNameException) {
          // esperado
        }
      }
    }

  @Test
  fun `renameFolder validates duplicates, blank names, trash state and preserves data on failure`() =
    runTest {
      val f1 = folderRepository.createFolder("Finanças")
      val f2 = folderRepository.createFolder("Estudos")

      val renamed = folderRepository.renameFolder(f1.id, "  Finanças Pessoais  ")
      assertEquals("Finanças Pessoais", renamed.name)

      // Conflito com outra pasta ativa
      try {
        folderRepository.renameFolder(f1.id, "estudos")
        fail("Esperava DuplicateFolderNameException ao renomear para nome já existente")
      } catch (_: DuplicateFolderNameException) {
        assertEquals("Finanças Pessoais", folderRepository.getFolderById(f1.id)?.name)
      }

      // Nome inválido
      try {
        folderRepository.renameFolder(f1.id, "   ")
        fail("Esperava InvalidFolderNameException")
      } catch (_: InvalidFolderNameException) {
        assertEquals("Finanças Pessoais", folderRepository.getFolderById(f1.id)?.name)
      }

      // Pasta apagada não pode ser renomeada sem restauração prévia
      folderRepository.deleteFolder(f2.id, FolderDeleteMode.FOLDER_ONLY)
      try {
        folderRepository.renameFolder(f2.id, "Novo Nome")
        fail("Esperava FolderDeletedException ao renomear pasta em Apagados")
      } catch (_: FolderDeletedException) {
        assertEquals("Estudos", folderRepository.getFolderById(f2.id)?.name)
      }
    }

  @Test
  fun `empty folder deletion is logical and allows restoration or merging on name conflict`() =
    runTest {
      val folder = folderRepository.createFolder("Projetos")
      folderRepository.deleteFolder(folder.id, FolderDeleteMode.FOLDER_ONLY)

      assertTrue(folderRepository.observeActiveFolders().first().isEmpty())
      val deleted = folderRepository.observeDeletedFolders().first()
      assertEquals(1, deleted.size)
      assertTrue(deleted.first().isDeleted)
      assertNotNull(deleted.first().deletedAt)

      // Pode criar nova pasta ativa com mesmo nome enquanto a anterior está em Apagados
      val newActive = folderRepository.createFolder("projetos")
      assertEquals("projetos", newActive.name)

      // Restaurar pasta original quando já existe ativa com mesmo nome incorpora e remove duplicata
      val mergeResult = folderRepository.restoreFolder(folder.id)
      assertTrue(mergeResult is RestoreFolderResult.MergedIntoActive)
      assertEquals(1, folderRepository.observeActiveFolders().first().size)
      assertTrue(folderRepository.observeDeletedFolders().first().isEmpty())
    }

  @Test
  fun `folder and content soft delete preserves note pin state and original folder relationship`() =
    runTest {
      val folder = folderRepository.createFolder("Estudos")
      val pinnedNote =
        noteRepository.createNote(
          title = "Resumo Kotlin",
          content =
            NoteContentDocument(
              blocks = listOf(ChecklistItemBlock(id = "c1", text = "Ler Room", isChecked = false))
            ),
          folderId = folder.id,
          isPinned = true,
        )
      val normalNote =
        noteRepository.createNote(title = "Rascunho", folderId = folder.id, isPinned = false)

      folderRepository.deleteFolder(folder.id, FolderDeleteMode.FOLDER_AND_CONTENT)

      assertTrue(noteRepository.observeAllActiveNotes().first().isEmpty())
      assertTrue(noteRepository.observePinnedActiveNotes().first().isEmpty())
      assertTrue(folderRepository.observeActiveFolders().first().isEmpty())

      val deletedNotes = noteRepository.observeDeletedNotes().first()
      assertEquals(2, deletedNotes.size)
      val deletedPinned = deletedNotes.first { it.id == pinnedNote.id }
      assertEquals(folder.id, deletedPinned.folderId)
      assertTrue(deletedPinned.isPinned)
      assertNotNull(deletedPinned.pinnedAt)
      assertTrue(deletedPinned.deletedWithFolder)

      val result = folderRepository.restoreFolder(folder.id)
      assertEquals(RestoreFolderResult.RestoredOriginal(folder.id), result)

      val restoredPinned = noteRepository.observePinnedActiveNotes().first()
      assertEquals(1, restoredPinned.size)
      assertEquals(pinnedNote.id, restoredPinned.first().id)
      assertEquals(folder.id, restoredPinned.first().folderId)
      assertEquals(2, noteRepository.observeActiveNotesByFolder(folder.id).first().size)
      assertEquals(normalNote.id, noteRepository.getNoteById(normalNote.id)?.id)
    }

  @Test
  fun `delete folder only detaches active notes and preserves already deleted note folder link`() =
    runTest {
      val folder = folderRepository.createFolder("Casa")
      val activeNote = noteRepository.createNote(title = "Ativa", folderId = folder.id)
      val previouslyDeletedNote =
        noteRepository.createNote(title = "Já apagada", folderId = folder.id)
      noteRepository.softDeleteNote(previouslyDeletedNote.id)

      folderRepository.deleteFolder(folder.id, FolderDeleteMode.FOLDER_ONLY)

      val loadedActive = noteRepository.getNoteById(activeNote.id)!!
      assertFalse(loadedActive.isDeleted)
      assertNull(loadedActive.folderId)

      val loadedDeleted = noteRepository.getNoteById(previouslyDeletedNote.id)!!
      assertTrue(loadedDeleted.isDeleted)
      assertEquals(folder.id, loadedDeleted.folderId)

      folderRepository.restoreFolder(folder.id)
      val afterRestore = noteRepository.getNoteById(previouslyDeletedNote.id)!!
      assertTrue(afterRestore.isDeleted)
      assertEquals(folder.id, afterRestore.folderId)
    }

  @Test
  fun `restoring individual note whose folder is in Apagados restores without folder and keeps folder deleted`() =
    runTest {
      val folder = folderRepository.createFolder("Viagem")
      val note =
        noteRepository.createNote(
          title = "Roteiro",
          folderId = folder.id,
          isPinned = true,
        )

      folderRepository.deleteFolder(folder.id, FolderDeleteMode.FOLDER_AND_CONTENT)

      val restoredNote = noteRepository.restoreNote(note.id)

      assertFalse(restoredNote.isDeleted)
      assertNull(restoredNote.deletedAt)
      assertNull(restoredNote.folderId)
      assertTrue(restoredNote.isPinned)

      val deletedFolder = folderRepository.getFolderById(folder.id)!!
      assertTrue(deletedFolder.isDeleted)
      assertEquals(1, folderRepository.observeDeletedFolders().first().size)

      assertEquals(1, noteRepository.observeAllActiveNotes().first().size)
      assertEquals(1, noteRepository.observePinnedActiveNotes().first().size)
    }

  @Test
  fun `restoring folder merges into active folder with same normalized name without overwriting data`() =
    runTest {
      val originalFolder = folderRepository.createFolder("Trabalho")
      val oldNote = noteRepository.createNote(title = "Relatório antigo", folderId = originalFolder.id)

      folderRepository.deleteFolder(originalFolder.id, FolderDeleteMode.FOLDER_AND_CONTENT)

      val newActiveFolder = folderRepository.createFolder("trabalho")
      val currentNote =
        noteRepository.createNote(title = "Meta atual", folderId = newActiveFolder.id)

      val result = folderRepository.restoreFolder(originalFolder.id)
      assertEquals(
        RestoreFolderResult.MergedIntoActive(
          targetFolderId = newActiveFolder.id,
          removedFolderId = originalFolder.id,
        ),
        result,
      )

      val notesInTarget = noteRepository.observeActiveNotesByFolder(newActiveFolder.id).first()
      assertEquals(setOf(oldNote.id, currentNote.id), notesInTarget.map { it.id }.toSet())
      assertNull(folderRepository.getFolderById(originalFolder.id))
      assertEquals("trabalho", folderRepository.getFolderById(newActiveFolder.id)?.name)
    }

  @Test
  fun `deleted note cannot be updated, pinned or moved before restore and target folder is always validated`() =
    runTest {
      val activeFolder = folderRepository.createFolder("Ativa")
      val deletedFolder = folderRepository.createFolder("Arquivada")
      folderRepository.deleteFolder(deletedFolder.id, FolderDeleteMode.FOLDER_ONLY)

      val note = noteRepository.createNote(title = "Original", folderId = activeFolder.id)
      noteRepository.softDeleteNote(note.id)

      try {
        noteRepository.updateNote(note.id, "Editada", NoteContentDocument.empty(), activeFolder.id)
        fail("Esperava NoteDeletedException ao editar nota em Apagados")
      } catch (_: NoteDeletedException) {
        assertEquals("Original", noteRepository.getNoteById(note.id)?.title)
      }

      try {
        noteRepository.setNotePinned(note.id, true)
        fail("Esperava NoteDeletedException ao fixar nota em Apagados")
      } catch (_: NoteDeletedException) {
        assertFalse(noteRepository.getNoteById(note.id)!!.isPinned)
      }

      try {
        noteRepository.moveNoteToFolder(note.id, null)
        fail("Esperava NoteDeletedException ao mover nota em Apagados")
      } catch (_: NoteDeletedException) {
        assertEquals(activeFolder.id, noteRepository.getNoteById(note.id)?.folderId)
      }

      // Valida destino apagado/inexistente na criação e movimentação
      try {
        noteRepository.createNote(title = "Inválida", folderId = deletedFolder.id)
        fail("Esperava FolderDeletedException ao criar nota em pasta apagada")
      } catch (_: FolderDeletedException) {
        // esperado
      }

      val restored = noteRepository.restoreNote(note.id)
      try {
        noteRepository.moveNoteToFolder(restored.id, 999_999L)
        fail("Esperava FolderNotFoundException para pasta inexistente")
      } catch (_: FolderNotFoundException) {
        assertEquals(activeFolder.id, noteRepository.getNoteById(restored.id)?.folderId)
      }
    }

  @Test
  fun `active note cannot be moved to a folder in Apagados and keeps current folder unchanged`() =
    runTest {
      val activeFolder = folderRepository.createFolder("Origem")
      val deletedFolder = folderRepository.createFolder("Pasta Apagada")
      folderRepository.deleteFolder(deletedFolder.id, FolderDeleteMode.FOLDER_ONLY)

      val noteInFolder =
        noteRepository.createNote(title = "Nota na pasta", folderId = activeFolder.id)
      val standaloneNote = noteRepository.createNote(title = "Nota solta", folderId = null)

      val initialInFolder = database.noteDao().getById(noteInFolder.id)!!
      val initialStandalone = database.noteDao().getById(standaloneNote.id)!!

      try {
        noteRepository.moveNoteToFolder(noteInFolder.id, deletedFolder.id)
        fail("Esperava FolderDeletedException ao mover nota ativa para pasta em Apagados")
      } catch (_: FolderDeletedException) {
        val afterFailure = database.noteDao().getById(noteInFolder.id)!!
        assertFalse(afterFailure.isDeleted)
        assertEquals(activeFolder.id, afterFailure.folderId)
        assertEquals(initialInFolder.title, afterFailure.title)
        assertEquals(initialInFolder.updatedAt, afterFailure.updatedAt)
      }

      try {
        noteRepository.moveNoteToFolder(standaloneNote.id, deletedFolder.id)
        fail("Esperava FolderDeletedException ao mover nota sem pasta para pasta em Apagados")
      } catch (_: FolderDeletedException) {
        val afterFailure = database.noteDao().getById(standaloneNote.id)!!
        assertFalse(afterFailure.isDeleted)
        assertNull(afterFailure.folderId)
        assertEquals(initialStandalone.updatedAt, afterFailure.updatedAt)
      }
    }

  @Test
  fun `restoring or merging folder restores notes deleted with folder and keeps individually deleted notes in Apagados`() =
    runTest {
      // Cenário 1: restauração direta da pasta original
      val folderA = folderRepository.createFolder("Leituras")
      val activeNoteA = noteRepository.createNote(title = "Livro ativo", folderId = folderA.id)
      val individualDeletedA =
        noteRepository.createNote(title = "Anotação descartada", folderId = folderA.id)
      noteRepository.softDeleteNote(individualDeletedA.id)
      val individualDeletedAtA = database.noteDao().getById(individualDeletedA.id)!!.deletedAt

      folderRepository.deleteFolder(folderA.id, FolderDeleteMode.FOLDER_AND_CONTENT)

      assertTrue(database.noteDao().getById(activeNoteA.id)!!.deletedWithFolder)
      assertFalse(database.noteDao().getById(individualDeletedA.id)!!.deletedWithFolder)

      val restoreResult = folderRepository.restoreFolder(folderA.id)
      assertEquals(RestoreFolderResult.RestoredOriginal(folderA.id), restoreResult)

      val restoredActiveA = noteRepository.getNoteById(activeNoteA.id)!!
      assertFalse(restoredActiveA.isDeleted)
      assertNull(restoredActiveA.deletedAt)
      assertFalse(restoredActiveA.deletedWithFolder)
      assertEquals(folderA.id, restoredActiveA.folderId)

      val stillDeletedA = noteRepository.getNoteById(individualDeletedA.id)!!
      assertTrue(stillDeletedA.isDeleted)
      assertEquals(individualDeletedAtA, stillDeletedA.deletedAt)
      assertFalse(stillDeletedA.deletedWithFolder)
      assertEquals(folderA.id, stillDeletedA.folderId)

      // Cenário 2: mesclagem ao restaurar pasta quando já existe pasta ativa com mesmo nome normalizado
      val folderB = folderRepository.createFolder("Finanças")
      val activeNoteB = noteRepository.createNote(title = "Planilha mensal", folderId = folderB.id)
      val individualDeletedB =
        noteRepository.createNote(title = "Recibo antigo", folderId = folderB.id)
      noteRepository.softDeleteNote(individualDeletedB.id)
      val individualDeletedAtB = database.noteDao().getById(individualDeletedB.id)!!.deletedAt

      folderRepository.deleteFolder(folderB.id, FolderDeleteMode.FOLDER_AND_CONTENT)

      val existingActiveB = folderRepository.createFolder("finanças")
      val currentNoteInActiveB =
        noteRepository.createNote(title = "Orçamento atual", folderId = existingActiveB.id)

      val mergeResult = folderRepository.restoreFolder(folderB.id)
      assertEquals(
        RestoreFolderResult.MergedIntoActive(
          targetFolderId = existingActiveB.id,
          removedFolderId = folderB.id,
        ),
        mergeResult,
      )

      val mergedActiveB = noteRepository.getNoteById(activeNoteB.id)!!
      assertFalse(mergedActiveB.isDeleted)
      assertNull(mergedActiveB.deletedAt)
      assertFalse(mergedActiveB.deletedWithFolder)
      assertEquals(existingActiveB.id, mergedActiveB.folderId)

      val untouchedCurrentB = noteRepository.getNoteById(currentNoteInActiveB.id)!!
      assertFalse(untouchedCurrentB.isDeleted)
      assertEquals(existingActiveB.id, untouchedCurrentB.folderId)

      val stillDeletedB = noteRepository.getNoteById(individualDeletedB.id)!!
      assertTrue(stillDeletedB.isDeleted)
      assertEquals(individualDeletedAtB, stillDeletedB.deletedAt)
      assertFalse(stillDeletedB.deletedWithFolder)
      assertEquals(existingActiveB.id, stillDeletedB.folderId)
      assertNull(folderRepository.getFolderById(folderB.id))
    }

  @Test
  fun `permanent folder deletion preserves individually deleted notes and rejects active folders`() =
    runTest {
      val folder = folderRepository.createFolder("Temporária")
      val previouslyDeletedNote =
        noteRepository.createNote(title = "Apagada antes", folderId = folder.id)
      noteRepository.softDeleteNote(previouslyDeletedNote.id)

      val noteDeletedWithFolder =
        noteRepository.createNote(title = "Apagada com pasta", folderId = folder.id)

      // Pasta ativa não pode ser excluída permanentemente diretamente
      try {
        folderRepository.permanentlyDeleteFolder(folder.id, deleteLinkedNotes = true)
        fail("Esperava FolderNotDeletedException para pasta ativa")
      } catch (_: FolderNotDeletedException) {
        assertNotNull(folderRepository.getFolderById(folder.id))
      }

      folderRepository.deleteFolder(folder.id, FolderDeleteMode.FOLDER_AND_CONTENT)
      folderRepository.permanentlyDeleteFolder(folder.id, deleteLinkedNotes = true)

      // Pasta e nota apagada junto com ela foram removidas
      assertNull(folderRepository.getFolderById(folder.id))
      assertNull(noteRepository.getNoteById(noteDeletedWithFolder.id))

      // Nota apagada individualmente antes permanece em Apagados com folderId = null (ON DELETE SET NULL)
      val preserved = noteRepository.getNoteById(previouslyDeletedNote.id)
      assertNotNull(preserved)
      assertTrue(preserved!!.isDeleted)
      assertNull(preserved.folderId)
    }

  @Test
  fun `permanent note deletion rejects active notes and removes soft deleted notes`() = runTest {
    val note = noteRepository.createNote(title = "Importante")

    try {
      noteRepository.permanentlyDeleteNote(note.id)
      fail("Esperava NoteNotDeletedException para nota ativa")
    } catch (_: NoteNotDeletedException) {
      assertNotNull(noteRepository.getNoteById(note.id))
    }

    noteRepository.softDeleteNote(note.id)
    noteRepository.permanentlyDeleteNote(note.id)
    assertNull(noteRepository.getNoteById(note.id))
  }

  @Test
  fun `sanitizes invalid spans on persistence and keeps listing Flow resilient to unreadable json`() =
    runTest {
      val validNote =
        noteRepository.createNote(
          title = "Com spans inválidos",
          content =
            NoteContentDocument(
              blocks =
                listOf(
                  ParagraphBlock(
                    id = "p1",
                    text = "Olá",
                    spans =
                      listOf(
                        FormatSpan(-2, 10, TextFormatStyle.BOLD),
                        FormatSpan(3, 1, TextFormatStyle.ITALIC),
                      ),
                  )
                )
            ),
        )
      val loadedValid = validNote.contentOrNull!!
      assertEquals("Olá", loadedValid.blocks.first().text)
      assertEquals(listOf(FormatSpan(0, 3, TextFormatStyle.BOLD)), loadedValid.blocks.first().spans)

      // Insere diretamente no DAO registros simulando JSON corrompido, versão futura e bloco desconhecido
      val corruptedRaw = "{invalido"
      val futureRaw = """{"schemaVersion":99,"blocks":[{"type":"paragraph","id":"1","text":"x","spans":[]}]}"""
      val unknownRaw = """{"schemaVersion":1,"blocks":[{"type":"tabela_v2","id":"1"}]}"""

      val corruptedId =
        database.noteDao().insert(
          NoteEntity(
            uid = "u-corrupted",
            title = "Corrompida",
            contentJson = corruptedRaw,
            plainTextPreview = "Prévia antiga",
            createdAt = 1L,
            updatedAt = 1L,
          )
        )
      val futureId =
        database.noteDao().insert(
          NoteEntity(
            uid = "u-future",
            title = "Versão futura",
            contentJson = futureRaw,
            plainTextPreview = "Prévia futura",
            createdAt = 2L,
            updatedAt = 2L,
          )
        )
      val unknownId =
        database.noteDao().insert(
          NoteEntity(
            uid = "u-unknown",
            title = "Bloco novo",
            contentJson = unknownRaw,
            plainTextPreview = "Prévia bloco",
            createdAt = 3L,
            updatedAt = 3L,
          )
        )

      // O Flow da listagem emite todas as notas sem falhar
      val allNotes = noteRepository.observeAllActiveNotes().first()
      assertEquals(4, allNotes.size)

      val corrupted = allNotes.first { it.id == corruptedId }
      assertTrue(corrupted.requiresContentHandling)
      assertTrue(corrupted.contentState is NoteContentState.RequiresHandling.CorruptedJson)
      assertEquals(
        corruptedRaw,
        (corrupted.contentState as NoteContentState.RequiresHandling).rawJson,
      )

      val future = allNotes.first { it.id == futureId }
      assertTrue(future.contentState is NoteContentState.RequiresHandling.UnsupportedFutureVersion)
      assertEquals(futureRaw, (future.contentState as NoteContentState.RequiresHandling).rawJson)

      val unknown = allNotes.first { it.id == unknownId }
      assertTrue(unknown.contentState is NoteContentState.RequiresHandling.UnknownBlockType)
      assertEquals(unknownRaw, (unknown.contentState as NoteContentState.RequiresHandling).rawJson)

      // Impede sobrescrever silenciosamente conteúdo não compreendido pela versão atual em todos os 3 casos
      for (
        (id, expectedTitle, expectedRaw, expectedPreview, expectedUpdatedAt) in
          listOf(
            Tuple5(corruptedId, "Corrompida", corruptedRaw, "Prévia antiga", 1L),
            Tuple5(futureId, "Versão futura", futureRaw, "Prévia futura", 2L),
            Tuple5(unknownId, "Bloco novo", unknownRaw, "Prévia bloco", 3L),
          )
      ) {
        try {
          noteRepository.updateNote(id, "Título sobrescrito", NoteContentDocument.empty(), null)
          fail("Esperava UnreadableNoteContentException para nota $id ($expectedTitle)")
        } catch (_: UnreadableNoteContentException) {
          val stored = database.noteDao().getById(id)!!
          assertEquals(expectedTitle, stored.title)
          assertEquals(expectedRaw, stored.contentJson)
          assertEquals(expectedPreview, stored.plainTextPreview)
          assertEquals(expectedUpdatedAt, stored.updatedAt)
        }
      }
    }

  @Test
  fun `canonical room preferences persist all approved themes, papers and home views`() = runTest {
    val initial = preferencesRepository.observePreferences().first()
    assertEquals(AppThemeMode.MODO_DIA, initial.themeMode)
    assertEquals(NotePaperStyle.LINHAS, initial.defaultNotePaper)
    assertEquals(HomeViewMode.LISTA, initial.homeViewMode)

    preferencesRepository.updateThemeMode(AppThemeMode.MODO_NATURAL)
    preferencesRepository.updateDefaultNotePaper(NotePaperStyle.ELEGANCE_NOIR)
    preferencesRepository.updateHomeViewMode(HomeViewMode.LISTA_COMPACTA)

    val updated = preferencesRepository.getPreferences()
    assertEquals(AppThemeMode.MODO_NATURAL, updated.themeMode)
    assertEquals(NotePaperStyle.ELEGANCE_NOIR, updated.defaultNotePaper)
    assertEquals(HomeViewMode.LISTA_COMPACTA, updated.homeViewMode)
    assertTrue(updated.updatedAt > 0L)
  }

  @Test
  fun `setNotePinnedIfRevision detects stale revision and updates only when revision matches`() =
    runTest {
      val initial =
        noteRepository.createNote(
          title = "Nota fixa",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "b1", text = "Texto"))),
          isPinned = false,
        )

      val concurrent =
        noteRepository.updateNote(
          noteId = initial.id,
          title = "Alterada concorrentemente",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "b1", text = "Novo texto"))),
          folderId = null,
          expectedBaseRevision = initial.revision,
        )

      val conflictRes =
        noteRepository.setNotePinnedIfRevision(
          noteId = initial.id,
          pinned = true,
          expectedBaseRevision = initial.revision,
        )
      assertTrue(conflictRes is com.example.domain.repository.PinNoteResult.Conflict)
      val conflict = conflictRes as com.example.domain.repository.PinNoteResult.Conflict
      assertEquals(initial.revision, conflict.expectedRevision)
      assertEquals(concurrent.revision, conflict.latestNote.revision)
      assertFalse(noteRepository.getNoteById(initial.id)!!.isPinned)

      val okRes =
        noteRepository.setNotePinnedIfRevision(
          noteId = initial.id,
          pinned = true,
          expectedBaseRevision = concurrent.revision,
        )
      assertTrue(okRes is com.example.domain.repository.PinNoteResult.Updated)
      val updated = (okRes as com.example.domain.repository.PinNoteResult.Updated).note
      assertTrue(updated.isPinned)
      assertEquals(concurrent.revision + 1L, updated.revision)
    }

  private fun assertNotEquals(a: Long, b: Long) {
    org.junit.Assert.assertNotEquals(a, b)
  }

  private data class Tuple5<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
  )
}
