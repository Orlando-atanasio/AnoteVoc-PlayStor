package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.coroutines.DispatcherProvider
import com.example.core.time.ClockProvider
import com.example.data.local.database.AnoteVoceDatabase
import com.example.data.local.entity.NoteRecoveryDraftEntity
import com.example.data.repository.FolderRepositoryImpl
import com.example.data.repository.NoteRecoveryDraftRepositoryImpl
import com.example.data.repository.NoteRepositoryImpl
import com.example.domain.model.ChecklistItemBlock
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NoteContentState
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.NoteRecoveryDraftInput
import com.example.domain.model.NoteRecoveryDraftKeys
import com.example.domain.model.ParagraphBlock
import com.example.domain.model.SaveCheckpointResult
import com.example.domain.repository.FolderDeleteMode
import com.example.domain.repository.InvalidRecoveryDraftException
import com.example.domain.repository.NoteConflictException
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
class NoteRecoveryDraftPersistenceTest {

  private lateinit var database: AnoteVoceDatabase
  private lateinit var folderRepository: FolderRepositoryImpl
  private lateinit var noteRepository: NoteRepositoryImpl
  private lateinit var draftRepository: NoteRecoveryDraftRepositoryImpl

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
        appPreferencesDao = database.appPreferencesDao(),
        noteRecoveryDraftDao = database.noteRecoveryDraftDao(),
      )
    draftRepository =
      NoteRecoveryDraftRepositoryImpl(
        database = database,
        draftDao = database.noteRecoveryDraftDao(),
        dispatchers = testDispatchers,
        clock = testClock,
      )
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun `inserts, reads and updates checkpoints for unsaved new note sessions without collision`() =
    runTest {
      val sessionA = "session-a-uuid"
      val sessionB = "session-b-uuid"
      val draftIdA = NoteRecoveryDraftKeys.forNewNote(sessionA)
      val draftIdB = NoteRecoveryDraftKeys.forNewNote(sessionB)

      assertFalse(draftIdA == "new")
      assertFalse(draftIdA == draftIdB)

      val docA1 =
        NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Rascunho A v1")))
      val resultA1 =
        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = draftIdA,
            targetNoteId = null,
            sessionId = sessionA,
            folderId = null,
            isPinned = true,
            title = "Nova A",
            content = docA1,
            paperStyle = NotePaperStyle.FOREST_MIST,
            baseRevision = 0L,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = null,
          )
        )
      assertTrue(resultA1 is SaveCheckpointResult.Saved)

      val docB =
        NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Rascunho B")))
      val resultB =
        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = draftIdB,
            targetNoteId = null,
            sessionId = sessionB,
            folderId = null,
            isPinned = false,
            title = "Nova B",
            content = docB,
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = 0L,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = null,
          )
        )
      assertTrue(resultB is SaveCheckpointResult.Saved)

      // Atualiza sessão A com revisão mais alta
      val docA2 =
        NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Rascunho A v2")))
      val resultA2 =
        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = draftIdA,
            targetNoteId = null,
            sessionId = sessionA,
            folderId = null,
            isPinned = true,
            title = "Nova A editada",
            content = docA2,
            paperStyle = NotePaperStyle.FOREST_MIST,
            baseRevision = 0L,
            checkpointRevision = 2L,
            baseNoteUpdatedAt = null,
          )
        )
      assertTrue(resultA2 is SaveCheckpointResult.Saved)

      // Tentativa de gravação atrasada (revisão menor) é ignorada sem sobrescrever a mais recente
      val staleResult =
        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = draftIdA,
            targetNoteId = null,
            sessionId = sessionA,
            folderId = null,
            isPinned = true,
            title = "Nova A obsoleta",
            content = docA1,
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = 0L,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = null,
          )
        )
      assertTrue(staleResult is SaveCheckpointResult.IgnoredStaleRevision)

      // Tentativa repetida com revisão igual também é ignorada sem sobrescrever
      val duplicateRevResult =
        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = draftIdA,
            targetNoteId = null,
            sessionId = sessionA,
            folderId = null,
            isPinned = true,
            title = "Nova A repetida",
            content = docA1,
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = 0L,
            checkpointRevision = 2L,
            baseNoteUpdatedAt = null,
          )
        )
      assertTrue(duplicateRevResult is SaveCheckpointResult.IgnoredStaleRevision)

      val loadedA = draftRepository.getCheckpointById(draftIdA)!!
      assertEquals("Nova A editada", loadedA.title)
      assertEquals(2L, loadedA.checkpointRevision)
      assertEquals("Rascunho A v2", loadedA.contentOrNull!!.blocks.first().text)
      assertTrue(loadedA.isPinned)
      assertEquals(NotePaperStyle.FOREST_MIST, loadedA.paperStyle)

      val allDrafts = draftRepository.observeAllCheckpoints().first()
      assertEquals(2, allDrafts.size)

      // Rejeita chave genérica "new" ou sessão em branco
      try {
        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = "new",
            targetNoteId = null,
            sessionId = "s3",
            folderId = null,
            isPinned = false,
            title = "",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = 0L,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = null,
          )
        )
        fail("Esperava InvalidRecoveryDraftException para draftId = 'new'")
      } catch (_: InvalidRecoveryDraftException) {
        // esperado
      }
    }

  @Test
  fun `associates checkpoint with existing note, detects conflicts and preserves draft on soft delete`() =
    runTest {
      val folder = folderRepository.createFolder("Trabalho")
      val note =
        noteRepository.createNote(
          title = "Plano inicial",
          content =
            NoteContentDocument(
              blocks = listOf(ChecklistItemBlock(id = "c1", text = "Item 1", isChecked = false))
            ),
          folderId = folder.id,
          paperStyle = NotePaperStyle.PONTILHADO,
        )

      val sessionId = "edit-session-1"
      val draftId = NoteRecoveryDraftKeys.forExistingNote(note.id, sessionId)
      draftRepository.saveCheckpoint(
        NoteRecoveryDraftInput(
          draftId = draftId,
          targetNoteId = note.id,
          sessionId = sessionId,
          folderId = folder.id,
          isPinned = false,
          title = "Plano rascunho",
          content =
            NoteContentDocument(
              blocks = listOf(ChecklistItemBlock(id = "c1", text = "Item 1 editado", isChecked = false))
            ),
          paperStyle = NotePaperStyle.MINIMALIST_PRO,
          baseRevision = note.revision,
          checkpointRevision = 1L,
          baseNoteUpdatedAt = note.updatedAt,
        )
      )

      val savedDraft = draftRepository.getCheckpointsForNote(note.id).single()
      assertEquals(note.revision, savedDraft.baseRevision)
      assertEquals(note.updatedAt, savedDraft.baseNoteUpdatedAt)
      assertTrue(savedDraft.hasValidBaseReference)
      assertFalse(savedDraft.hasConflictWith(note))

      // Exclusão lógica da nota ou da pasta NÃO apaga o checkpoint em cascata
      folderRepository.deleteFolder(folder.id, FolderDeleteMode.FOLDER_AND_CONTENT)
      assertNotNull(draftRepository.getCheckpointById(draftId))
      assertTrue(savedDraft.hasConflictWith(noteRepository.getNoteById(note.id)!!))

      folderRepository.restoreFolder(folder.id)
      val noteAfterRestore = noteRepository.getNoteById(note.id)!!
      // Como a nota mudou (revisão e updatedAt avançaram), conflito é detectado
      assertTrue(savedDraft.hasConflictWith(noteAfterRestore))

      // Atualização com baseRevision desatualizada falha com NoteConflictException sem sobrescrever
      try {
        noteRepository.updateNote(
          noteId = note.id,
          title = "Sobrescrita indevida",
          content = NoteContentDocument.empty(),
          folderId = folder.id,
          expectedBaseRevision = savedDraft.baseRevision,
          clearDraftId = draftId,
        )
        fail("Esperava NoteConflictException para revisão-base desatualizada")
      } catch (_: NoteConflictException) {
        assertEquals("Plano inicial", noteRepository.getNoteById(note.id)?.title)
        assertNotNull(draftRepository.getCheckpointById(draftId))
      }

      // Atualização com revisão atual e clearDraftId consolida nota e limpa checkpoint na mesma transação
      val updated =
        noteRepository.updateNote(
          noteId = note.id,
          title = "Plano consolidado",
          content = savedDraft.contentOrNull!!,
          folderId = folder.id,
          paperStyle = savedDraft.paperStyle,
          expectedBaseRevision = noteAfterRestore.revision,
          clearDraftId = draftId,
        )
      assertEquals("Plano consolidado", updated.title)
      assertEquals(NotePaperStyle.MINIMALIST_PRO, updated.paperStyle)
      assertNull(draftRepository.getCheckpointById(draftId))
    }

  @Test
  fun `corrupted checkpoint json decodes safely as RequiresHandling without affecting main note`() =
    runTest {
      val mainNote =
        noteRepository.createNote(
          title = "Versão íntegra",
          content =
            NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Texto salvo"))),
        )

      val brokenSessionId = "broken-session"
      val brokenDraftId = NoteRecoveryDraftKeys.forExistingNote(mainNote.id, brokenSessionId)
      database.noteRecoveryDraftDao().insert(
        NoteRecoveryDraftEntity(
          draftId = brokenDraftId,
          targetNoteId = mainNote.id,
          sessionId = brokenSessionId,
          folderId = null,
          isPinned = false,
          title = "Rascunho corrompido",
          contentJson = "{json-truncado",
          paperStyle = NotePaperStyle.LINHAS.name,
          baseRevision = mainNote.revision,
          checkpointRevision = 3L,
          baseNoteUpdatedAt = mainNote.updatedAt,
          checkpointAt = 9999L,
        )
      )

      val loadedDraft = draftRepository.getCheckpointById(brokenDraftId)!!
      assertTrue(loadedDraft.requiresContentHandling)
      assertTrue(loadedDraft.contentState is NoteContentState.RequiresHandling.CorruptedJson)
      assertTrue(loadedDraft.hasConflictWith(mainNote))
      assertEquals("Texto salvo", noteRepository.getNoteById(mainNote.id)!!.contentOrNull!!.blocks.first().text)
    }
}
