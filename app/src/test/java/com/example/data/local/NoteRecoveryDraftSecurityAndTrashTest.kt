package com.example.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.coroutines.DispatcherProvider
import com.example.core.time.ClockProvider
import com.example.data.local.database.AnoteVoceDatabase
import com.example.data.local.entity.NoteEntity
import com.example.data.local.entity.NoteRecoveryDraftEntity
import com.example.data.repository.FolderRepositoryImpl
import com.example.data.repository.NoteRecoveryDraftRepositoryImpl
import com.example.data.repository.NoteRepositoryImpl
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.NoteRecoveryDraftInput
import com.example.domain.model.NoteRecoveryDraftKeys
import com.example.domain.model.ParagraphBlock
import com.example.domain.model.SaveCheckpointResult
import com.example.domain.repository.FolderDeleteMode
import com.example.domain.repository.FolderDeletedException
import com.example.domain.repository.InvalidRecoveryDraftException
import com.example.domain.repository.NoteConflictException
import com.example.domain.repository.NoteDeletedException
import com.example.domain.repository.NoteNotFoundException
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
class NoteRecoveryDraftSecurityAndTrashTest {

  private lateinit var database: AnoteVoceDatabase
  private lateinit var folderRepository: FolderRepositoryImpl
  private lateinit var noteRepository: NoteRepositoryImpl
  private lateinit var draftRepository: NoteRecoveryDraftRepositoryImpl

  private var fakeTimeMillis = 1_800_000_000_000L

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
        noteDao = database.noteDao(),
      )
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun `two sessions editing same note keep independent checkpoints and consolidating one preserves the other`() =
    runTest {
      val note =
        noteRepository.createNote(
          title = "Nota compartilhada",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Base"))),
        )

      val session1 = "sessao-1"
      val session2 = "sessao-2"
      val draftId1 = NoteRecoveryDraftKeys.forExistingNote(note.id, session1)
      val draftId2 = NoteRecoveryDraftKeys.forExistingNote(note.id, session2)
      assertFalse(draftId1 == draftId2)

      val save1 =
        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = draftId1,
            targetNoteId = note.id,
            sessionId = session1,
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Edição Sessão 1",
            content =
              NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Texto S1"))),
            paperStyle = NotePaperStyle.OCEAN_BREEZE,
            baseRevision = note.revision,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = note.updatedAt,
          )
        )
      val save2 =
        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = draftId2,
            targetNoteId = note.id,
            sessionId = session2,
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Edição Sessão 2",
            content =
              NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Texto S2"))),
            paperStyle = NotePaperStyle.FOREST_MIST,
            baseRevision = note.revision,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = note.updatedAt,
          )
        )
      assertTrue(save1 is SaveCheckpointResult.Saved)
      assertTrue(save2 is SaveCheckpointResult.Saved)

      val allForNote = draftRepository.getCheckpointsForNote(note.id)
      assertEquals(2, allForNote.size)
      assertEquals(setOf(draftId1, draftId2), allForNote.map { it.draftId }.toSet())

      // Consolida apenas a sessão 1 removendo seu draftId atomicamente; sessão 2 deve permanecer intacta
      val consolidated =
        noteRepository.updateNote(
          noteId = note.id,
          title = "Edição Sessão 1",
          content =
            NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Texto S1"))),
          folderId = null,
          paperStyle = NotePaperStyle.OCEAN_BREEZE,
          expectedBaseRevision = note.revision,
          clearDraftId = draftId1,
        )

      assertEquals(note.revision + 1L, consolidated.revision)
      assertNull(draftRepository.getCheckpointById(draftId1))

      val survivingDraft2 = draftRepository.getCheckpointById(draftId2)
      assertNotNull(survivingDraft2)
      assertEquals("Texto S2", survivingDraft2!!.contentOrNull!!.blocks.first().text)
      assertTrue(survivingDraft2.hasConflictWith(consolidated))
    }

  @Test
  fun `session cannot hijack or overwrite another session checkpoint and dao aborts duplicate sessionId`() =
    runTest {
      val note = noteRepository.createNote(title = "Original")
      val sessionA = "sessao-A"
      val sessionB = "sessao-B"
      val draftIdA = NoteRecoveryDraftKeys.forExistingNote(note.id, sessionA)

      draftRepository.saveCheckpoint(
        NoteRecoveryDraftInput(
          draftId = draftIdA,
          targetNoteId = note.id,
          sessionId = sessionA,
          folderId = null,
          fallbackSourceFolderId = null,
          isPinned = false,
          title = "Rascunho A",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Conteúdo A"))),
          paperStyle = NotePaperStyle.LINHAS,
          baseRevision = note.revision,
          checkpointRevision = 1L,
          baseNoteUpdatedAt = note.updatedAt,
        )
      )

      // Sessão B tentando gravar usando explicitamente o draftId da Sessão A -> rejeitado
      try {
        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = draftIdA,
            targetNoteId = note.id,
            sessionId = sessionB,
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Sobrescrita por sessão B",
            content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Texto B"))),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = note.revision,
            checkpointRevision = 99L,
            baseNoteUpdatedAt = note.updatedAt,
          )
        )
        fail("Esperava InvalidRecoveryDraftException ao tentar usar draftId de outra sessão")
      } catch (_: InvalidRecoveryDraftException) {
        assertEquals("Rascunho A", draftRepository.getCheckpointById(draftIdA)?.title)
      }

      // Tenta reutilizar sessionA para nova nota com outro draftId -> rejeitado
      val newWithSessionA = NoteRecoveryDraftKeys.forNewNote(sessionA)
      try {
        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = newWithSessionA,
            targetNoteId = null,
            sessionId = sessionA,
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Sequestro de sessão",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = 0L,
            checkpointRevision = 5L,
            baseNoteUpdatedAt = null,
          )
        )
        fail("Esperava InvalidRecoveryDraftException ao reutilizar sessionId de outro checkpoint")
      } catch (_: InvalidRecoveryDraftException) {
        assertEquals("Rascunho A", draftRepository.getCheckpointById(draftIdA)?.title)
      }

      // Tentativa direta no DAO de inserir outro draft_id com mesmo session_id falha com ABORT (nunca REPLACE)
      try {
        database.noteRecoveryDraftDao().insert(
          NoteRecoveryDraftEntity(
            draftId = NoteRecoveryDraftKeys.forExistingNote(note.id, sessionB),
            targetNoteId = note.id,
            sessionId = sessionA,
            folderId = null,
            isPinned = false,
            title = "Colisão DAO",
            contentJson = """{"schemaVersion":1,"blocks":[]}""",
            paperStyle = NotePaperStyle.LINHAS.name,
            baseRevision = note.revision,
            checkpointRevision = 2L,
            baseNoteUpdatedAt = note.updatedAt,
            checkpointAt = 12345L,
          )
        )
        fail("Esperava SQLiteConstraintException com OnConflictStrategy.ABORT")
      } catch (_: SQLiteConstraintException) {
        assertEquals("Rascunho A", draftRepository.getCheckpointById(draftIdA)?.title)
      }
    }

  @Test
  fun `older or equal checkpointRevision within same session is ignored without altering persisted draft`() =
    runTest {
      val note = noteRepository.createNote(title = "Base")
      val sessionId = "sessao-seq"
      val draftId = NoteRecoveryDraftKeys.forExistingNote(note.id, sessionId)

      val r2 =
        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = draftId,
            targetNoteId = note.id,
            sessionId = sessionId,
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Título Rev 2",
            content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Texto Rev 2"))),
            paperStyle = NotePaperStyle.SUNSET_WARMTH,
            baseRevision = note.revision,
            checkpointRevision = 2L,
            baseNoteUpdatedAt = note.updatedAt,
          )
        )
      assertTrue(r2 is SaveCheckpointResult.Saved)

      val older =
        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = draftId,
            targetNoteId = note.id,
            sessionId = sessionId,
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Título Rev 1 atrasada",
            content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Atrasada"))),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = note.revision,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = note.updatedAt,
          )
        )
      assertTrue(older is SaveCheckpointResult.IgnoredStaleRevision)

      val equal =
        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = draftId,
            targetNoteId = note.id,
            sessionId = sessionId,
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Título Rev 2 duplicado",
            content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Duplicado"))),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = note.revision,
            checkpointRevision = 2L,
            baseNoteUpdatedAt = note.updatedAt,
          )
        )
      assertTrue(equal is SaveCheckpointResult.IgnoredStaleRevision)

      val persisted = draftRepository.getCheckpointById(draftId)!!
      assertEquals("Título Rev 2", persisted.title)
      assertEquals("Texto Rev 2", persisted.contentOrNull!!.blocks.first().text)
      assertEquals(NotePaperStyle.SUNSET_WARMTH, persisted.paperStyle)
      assertEquals(2L, persisted.checkpointRevision)
    }

  @Test
  fun `rejects incoherent draftId sessionId targetNoteId and invalid base references`() =
    runTest {
      val note = noteRepository.createNote(title = "Existente")
      val otherNote = noteRepository.createNote(title = "Outra")
      val session = "sessao-valida"

      val invalidCases =
        listOf(
          // Mistura nova nota com nota existente
          NoteRecoveryDraftInput(
            draftId = NoteRecoveryDraftKeys.forNewNote(session),
            targetNoteId = note.id,
            sessionId = session,
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Erro",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = note.revision,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = note.updatedAt,
          ),
          NoteRecoveryDraftInput(
            draftId = NoteRecoveryDraftKeys.forExistingNote(note.id, session),
            targetNoteId = null,
            sessionId = session,
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Erro",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = 0L,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = null,
          ),
          // Chave antiga determinística sem sessionId
          NoteRecoveryDraftInput(
            draftId = "note_${note.id}",
            targetNoteId = note.id,
            sessionId = session,
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Erro",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = note.revision,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = note.updatedAt,
          ),
          // draftId de outra nota
          NoteRecoveryDraftInput(
            draftId = NoteRecoveryDraftKeys.forExistingNote(otherNote.id, session),
            targetNoteId = note.id,
            sessionId = session,
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Erro",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = note.revision,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = note.updatedAt,
          ),
          // sessionId com espaço ou reservado
          NoteRecoveryDraftInput(
            draftId = "new_sessao com espaco",
            targetNoteId = null,
            sessionId = "sessao com espaco",
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Erro",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = 0L,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = null,
          ),
          NoteRecoveryDraftInput(
            draftId = "new_new",
            targetNoteId = null,
            sessionId = "NEW",
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Erro",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = 0L,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = null,
          ),
          // Dados-base inválidos para nota existente (0, negativo, futuro, timestamp ausente ou divergente)
          NoteRecoveryDraftInput(
            draftId = NoteRecoveryDraftKeys.forExistingNote(note.id, session),
            targetNoteId = note.id,
            sessionId = session,
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Base 0",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = 0L,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = note.updatedAt,
          ),
          NoteRecoveryDraftInput(
            draftId = NoteRecoveryDraftKeys.forExistingNote(note.id, session),
            targetNoteId = note.id,
            sessionId = session,
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Base negativa",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = -2L,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = note.updatedAt,
          ),
          NoteRecoveryDraftInput(
            draftId = NoteRecoveryDraftKeys.forExistingNote(note.id, session),
            targetNoteId = note.id,
            sessionId = session,
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Base futura",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = note.revision + 5L,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = note.updatedAt,
          ),
          NoteRecoveryDraftInput(
            draftId = NoteRecoveryDraftKeys.forExistingNote(note.id, session),
            targetNoteId = note.id,
            sessionId = session,
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Timestamp nulo",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = note.revision,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = null,
          ),
          NoteRecoveryDraftInput(
            draftId = NoteRecoveryDraftKeys.forExistingNote(note.id, session),
            targetNoteId = note.id,
            sessionId = session,
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Timestamp divergente",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = note.revision,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = note.updatedAt - 1L,
          ),
          // Dados-base inválidos para nota nova
          NoteRecoveryDraftInput(
            draftId = NoteRecoveryDraftKeys.forNewNote(session),
            targetNoteId = null,
            sessionId = session,
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Nova com baseRevision",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = 1L,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = null,
          ),
          NoteRecoveryDraftInput(
            draftId = NoteRecoveryDraftKeys.forNewNote(session),
            targetNoteId = null,
            sessionId = session,
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Nova com baseTimestamp",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = 0L,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = 500L,
          ),
        )

      for ((idx, invalidInput) in invalidCases.withIndex()) {
        try {
          draftRepository.saveCheckpoint(invalidInput)
          fail("Caso inválido #$idx deveria ter lançado InvalidRecoveryDraftException")
        } catch (_: InvalidRecoveryDraftException) {
          // esperado
        }
      }

      assertTrue(draftRepository.getAllCheckpoints().isEmpty())
    }

  @Test
  fun `missing or invalid base data on existing note draft is always treated as conflict`() =
    runTest {
      val note = noteRepository.createNote(title = "Alvo")
      val otherNote = noteRepository.createNote(title = "Alvo 2")

      val zeroBaseId = NoteRecoveryDraftKeys.forExistingNote(note.id, "legacy-zero")
      val nullTimestampId = NoteRecoveryDraftKeys.forExistingNote(note.id, "legacy-null-ts")

      database.noteRecoveryDraftDao().insert(
        NoteRecoveryDraftEntity(
          draftId = zeroBaseId,
          targetNoteId = note.id,
          sessionId = "legacy-zero",
          folderId = null,
          isPinned = false,
          title = "Sem revisão base",
          contentJson = """{"schemaVersion":1,"blocks":[]}""",
          paperStyle = NotePaperStyle.LINHAS.name,
          baseRevision = 0L,
          checkpointRevision = 1L,
          baseNoteUpdatedAt = note.updatedAt,
          checkpointAt = 100L,
        )
      )
      database.noteRecoveryDraftDao().insert(
        NoteRecoveryDraftEntity(
          draftId = nullTimestampId,
          targetNoteId = note.id,
          sessionId = "legacy-null-ts",
          folderId = null,
          isPinned = false,
          title = "Sem timestamp base",
          contentJson = """{"schemaVersion":1,"blocks":[]}""",
          paperStyle = NotePaperStyle.LINHAS.name,
          baseRevision = note.revision,
          checkpointRevision = 1L,
          baseNoteUpdatedAt = null,
          checkpointAt = 101L,
        )
      )

      val zeroBaseDraft = draftRepository.getCheckpointById(zeroBaseId)!!
      assertFalse(zeroBaseDraft.hasValidBaseReference)
      assertTrue(zeroBaseDraft.hasConflictWith(note))

      val nullTsDraft = draftRepository.getCheckpointById(nullTimestampId)!!
      assertFalse(nullTsDraft.hasValidBaseReference)
      assertTrue(nullTsDraft.hasConflictWith(note))

      // Draft de outra nota nunca é tratado como livre de conflito sobre otherNote
      val validForNote =
        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = NoteRecoveryDraftKeys.forExistingNote(note.id, "s-valid"),
            targetNoteId = note.id,
            sessionId = "s-valid",
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Válido para note",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = note.revision,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = note.updatedAt,
          )
        ) as SaveCheckpointResult.Saved
      assertFalse(validForNote.draft.hasConflictWith(note))
      assertTrue(validForNote.draft.hasConflictWith(otherNote))
    }

  @Test
  fun `rejects checkpoints for deleted, non-existent or unreadable notes and preserves original data on consolidation failure`() =
    runTest {
      val activeNote = noteRepository.createNote(title = "Ativa íntegra")
      val deletedNote = noteRepository.createNote(title = "Na lixeira")
      noteRepository.softDeleteNote(deletedNote.id)

      val deletedFolder = folderRepository.createFolder("Pasta apagada")
      folderRepository.deleteFolder(deletedFolder.id, FolderDeleteMode.FOLDER_ONLY)

      val futureId =
        database.noteDao().insert(
          NoteEntity(
            uid = "u-future-draft-block",
            title = "Futura",
            contentJson = """{"schemaVersion":99,"blocks":[]}""",
            plainTextPreview = "Futura",
            createdAt = 1L,
            updatedAt = 1L,
          )
        )

      try {
        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = NoteRecoveryDraftKeys.forExistingNote(deletedNote.id, "s-del"),
            targetNoteId = deletedNote.id,
            sessionId = "s-del",
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Tentativa em apagada",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = deletedNote.revision,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = deletedNote.updatedAt,
          )
        )
        fail("Esperava NoteDeletedException ao salvar checkpoint para nota em Apagados")
      } catch (_: NoteDeletedException) {
        // esperado
      }

      try {
        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = NoteRecoveryDraftKeys.forExistingNote(999_999L, "s-missing"),
            targetNoteId = 999_999L,
            sessionId = "s-missing",
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Inexistente",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = 1L,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = 100L,
          )
        )
        fail("Esperava NoteNotFoundException ao salvar checkpoint para nota inexistente")
      } catch (_: NoteNotFoundException) {
        // esperado
      }

      val futureNote = noteRepository.getNoteById(futureId)!!
      try {
        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = NoteRecoveryDraftKeys.forExistingNote(futureId, "s-future"),
            targetNoteId = futureId,
            sessionId = "s-future",
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Sobrescrita futura",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = futureNote.revision,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = futureNote.updatedAt,
          )
        )
        fail("Esperava UnreadableNoteContentException para nota RequiresHandling")
      } catch (_: UnreadableNoteContentException) {
        // esperado
      }

      // Falha transacional na consolidação (pasta destino apagada ou conflito) preserva nota e checkpoint
      val draftId = NoteRecoveryDraftKeys.forExistingNote(activeNote.id, "s-active")
      draftRepository.saveCheckpoint(
        NoteRecoveryDraftInput(
          draftId = draftId,
          targetNoteId = activeNote.id,
          sessionId = "s-active",
          folderId = deletedFolder.id,
          fallbackSourceFolderId = null,
          isPinned = false,
          title = "Novo título em rascunho",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Rascunho"))),
          paperStyle = NotePaperStyle.TECH_DARK,
          baseRevision = activeNote.revision,
          checkpointRevision = 1L,
          baseNoteUpdatedAt = activeNote.updatedAt,
        )
      )

      try {
        noteRepository.updateNote(
          noteId = activeNote.id,
          title = "Novo título em rascunho",
          content = NoteContentDocument.empty(),
          folderId = deletedFolder.id,
          expectedBaseRevision = activeNote.revision,
          clearDraftId = draftId,
        )
        fail("Esperava FolderDeletedException durante consolidação")
      } catch (_: FolderDeletedException) {
        val intactNote = noteRepository.getNoteById(activeNote.id)!!
        assertEquals("Ativa íntegra", intactNote.title)
        assertEquals(activeNote.revision, intactNote.revision)
        assertNotNull(draftRepository.getCheckpointById(draftId))
      }

      try {
        noteRepository.updateNote(
          noteId = activeNote.id,
          title = "Conflito",
          content = NoteContentDocument.empty(),
          folderId = null,
          expectedBaseRevision = activeNote.revision + 99L,
          clearDraftId = draftId,
        )
        fail("Esperava NoteConflictException")
      } catch (_: NoteConflictException) {
        assertEquals("Ativa íntegra", noteRepository.getNoteById(activeNote.id)?.title)
        assertNotNull(draftRepository.getCheckpointById(draftId))
      }
    }

  @Test
  fun `deleted note dao operations increment revision and updatedAt without silent FK nullification`() =
    runTest {
      // 1. Teste direto: repointDeletedNotesFolder
      val folderSrc = folderRepository.createFolder("Origem")
      val folderDst = folderRepository.createFolder("Destino")
      val nRepoint = noteRepository.createNote(title = "Repoint", folderId = folderSrc.id)
      noteRepository.softDeleteNote(nRepoint.id)
      val beforeRepoint = database.noteDao().getById(nRepoint.id)!!

      val repointTs = 5_000_000L
      val repointedCount =
        database.noteDao().repointDeletedNotesFolder(folderSrc.id, folderDst.id, repointTs)
      assertEquals(1, repointedCount)
      val afterRepoint = database.noteDao().getById(nRepoint.id)!!
      assertEquals(folderDst.id, afterRepoint.folderId)
      assertEquals(beforeRepoint.revision + 1L, afterRepoint.revision)
      assertEquals(repointTs, afterRepoint.updatedAt)

      // 2. Teste direto: clearDeletedWithFolderFlag e detachDeletedNotesFromFolder
      val folderDaoTest = folderRepository.createFolder("Pasta DAO")
      val nDao = noteRepository.createNote(title = "DeletedWithFolder", folderId = folderDaoTest.id)
      folderRepository.deleteFolder(folderDaoTest.id, FolderDeleteMode.FOLDER_AND_CONTENT)
      val beforeClear = database.noteDao().getById(nDao.id)!!
      assertTrue(beforeClear.deletedWithFolder)

      val clearTs = 5_000_100L
      assertEquals(1, database.noteDao().clearDeletedWithFolderFlag(folderDaoTest.id, clearTs))
      val afterClear = database.noteDao().getById(nDao.id)!!
      assertFalse(afterClear.deletedWithFolder)
      assertEquals(beforeClear.revision + 1L, afterClear.revision)
      assertEquals(clearTs, afterClear.updatedAt)

      val detachTs = 5_000_200L
      assertEquals(1, database.noteDao().detachDeletedNotesFromFolder(folderDaoTest.id, detachTs))
      val afterDetach = database.noteDao().getById(nDao.id)!!
      assertNull(afterDetach.folderId)
      assertEquals(afterClear.revision + 1L, afterDetach.revision)
      assertEquals(detachTs, afterDetach.updatedAt)

      // 3. Teste de repositório: permanentlyDeleteFolder(..., deleteLinkedNotes = true)
      val folderPermTrue = folderRepository.createFolder("Perm True")
      val prevDeleted = noteRepository.createNote(title = "Apagada antes", folderId = folderPermTrue.id)
      noteRepository.softDeleteNote(prevDeleted.id)
      val withFolder = noteRepository.createNote(title = "Com pasta", folderId = folderPermTrue.id)
      folderRepository.deleteFolder(folderPermTrue.id, FolderDeleteMode.FOLDER_AND_CONTENT)

      val prevDeletedBeforePerm = database.noteDao().getById(prevDeleted.id)!!
      folderRepository.permanentlyDeleteFolder(folderPermTrue.id, deleteLinkedNotes = true)
      assertNull(database.noteDao().getById(withFolder.id))
      val prevDeletedAfterPerm = database.noteDao().getById(prevDeleted.id)!!
      assertNull(prevDeletedAfterPerm.folderId)
      assertEquals(prevDeletedBeforePerm.revision + 1L, prevDeletedAfterPerm.revision)
      assertTrue(prevDeletedAfterPerm.updatedAt > prevDeletedBeforePerm.updatedAt)

      // 4. Teste de repositório: permanentlyDeleteFolder(..., deleteLinkedNotes = false)
      val folderPermFalse = folderRepository.createFolder("Perm False")
      val keptWithFolder = noteRepository.createNote(title = "Mantida", folderId = folderPermFalse.id)
      val keptIndividual = noteRepository.createNote(title = "Individual", folderId = folderPermFalse.id)
      noteRepository.softDeleteNote(keptIndividual.id)
      folderRepository.deleteFolder(folderPermFalse.id, FolderDeleteMode.FOLDER_AND_CONTENT)

      val keptWithBefore = database.noteDao().getById(keptWithFolder.id)!!
      val keptIndBefore = database.noteDao().getById(keptIndividual.id)!!
      folderRepository.permanentlyDeleteFolder(folderPermFalse.id, deleteLinkedNotes = false)

      val keptWithAfter = database.noteDao().getById(keptWithFolder.id)!!
      assertTrue(keptWithAfter.isDeleted)
      assertFalse(keptWithAfter.deletedWithFolder)
      assertNull(keptWithAfter.folderId)
      assertEquals(keptWithBefore.revision + 1L, keptWithAfter.revision)
      assertTrue(keptWithAfter.updatedAt > keptWithBefore.updatedAt)

      val keptIndAfter = database.noteDao().getById(keptIndividual.id)!!
      assertTrue(keptIndAfter.isDeleted)
      assertFalse(keptIndAfter.deletedWithFolder)
      assertNull(keptIndAfter.folderId)
      assertEquals(keptIndBefore.revision + 1L, keptIndAfter.revision)
      assertEquals(keptWithAfter.updatedAt, keptIndAfter.updatedAt)
      assertTrue(keptIndAfter.updatedAt > keptIndBefore.updatedAt)

      // 5. Teste de repositório: restoreFolder (merge) incrementa revisão na nota em Apagados repontada
      val mergeOrig = folderRepository.createFolder("Projetos")
      val indInMerge = noteRepository.createNote(title = "Ind Merge", folderId = mergeOrig.id)
      noteRepository.softDeleteNote(indInMerge.id)
      folderRepository.deleteFolder(mergeOrig.id, FolderDeleteMode.FOLDER_AND_CONTENT)
      val indMergeBefore = database.noteDao().getById(indInMerge.id)!!

      val mergeTarget = folderRepository.createFolder("projetos")
      val mergeResult = folderRepository.restoreFolder(mergeOrig.id)
      assertTrue(mergeResult is RestoreFolderResult.MergedIntoActive)
      val indMergeAfter = database.noteDao().getById(indInMerge.id)!!
      assertEquals(mergeTarget.id, indMergeAfter.folderId)
      assertEquals(indMergeBefore.revision + 1L, indMergeAfter.revision)
      assertTrue(indMergeAfter.updatedAt > indMergeBefore.updatedAt)
    }

  @Test
  fun `updating note A with draftId of note B or new-note draft fails preserving notes and drafts`() =
    runTest {
      val noteA =
        noteRepository.createNote(
          title = "Nota A original",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "a1", text = "Conteúdo A"))),
        )
      val noteB =
        noteRepository.createNote(
          title = "Nota B original",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "b1", text = "Conteúdo B"))),
        )

      val draftAId = NoteRecoveryDraftKeys.forExistingNote(noteA.id, "sessao-a")
      val draftBId = NoteRecoveryDraftKeys.forExistingNote(noteB.id, "sessao-b")
      val draftNewId = NoteRecoveryDraftKeys.forNewNote("sessao-nova")

      draftRepository.saveCheckpoint(
        NoteRecoveryDraftInput(
          draftId = draftAId,
          targetNoteId = noteA.id,
          sessionId = "sessao-a",
          folderId = null,
          fallbackSourceFolderId = null,
          isPinned = false,
          title = "Rascunho A",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "a1", text = "Editado A"))),
          paperStyle = NotePaperStyle.OCEAN_BREEZE,
          baseRevision = noteA.revision,
          checkpointRevision = 1L,
          baseNoteUpdatedAt = noteA.updatedAt,
        )
      )
      draftRepository.saveCheckpoint(
        NoteRecoveryDraftInput(
          draftId = draftBId,
          targetNoteId = noteB.id,
          sessionId = "sessao-b",
          folderId = null,
          fallbackSourceFolderId = null,
          isPinned = false,
          title = "Rascunho B",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "b1", text = "Editado B"))),
          paperStyle = NotePaperStyle.FOREST_MIST,
          baseRevision = noteB.revision,
          checkpointRevision = 1L,
          baseNoteUpdatedAt = noteB.updatedAt,
        )
      )
      draftRepository.saveCheckpoint(
        NoteRecoveryDraftInput(
          draftId = draftNewId,
          targetNoteId = null,
          sessionId = "sessao-nova",
          folderId = null,
          fallbackSourceFolderId = null,
          isPinned = false,
          title = "Rascunho Nova",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "n1", text = "Texto novo"))),
          paperStyle = NotePaperStyle.SUNSET_WARMTH,
          baseRevision = 0L,
          checkpointRevision = 1L,
          baseNoteUpdatedAt = null,
        )
      )

      // 1. Atualizar nota A informando draftId da nota B deve falhar e preservar tudo
      try {
        noteRepository.updateNote(
          noteId = noteA.id,
          title = "Nota A sobrescrita",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "a1", text = "Novo A"))),
          folderId = null,
          expectedBaseRevision = noteA.revision,
          clearDraftId = draftBId,
        )
        fail("Esperava InvalidRecoveryDraftException ao consolidar nota A com rascunho da nota B")
      } catch (_: InvalidRecoveryDraftException) {
        val loadedA = noteRepository.getNoteById(noteA.id)!!
        val loadedB = noteRepository.getNoteById(noteB.id)!!
        assertEquals("Nota A original", loadedA.title)
        assertEquals("Conteúdo A", loadedA.contentOrNull!!.blocks.first().text)
        assertEquals(noteA.revision, loadedA.revision)
        assertEquals("Nota B original", loadedB.title)
        assertEquals(noteB.revision, loadedB.revision)
        assertNotNull(draftRepository.getCheckpointById(draftAId))
        assertNotNull(draftRepository.getCheckpointById(draftBId))
        assertNotNull(draftRepository.getCheckpointById(draftNewId))
      }

      // 2. Atualizar nota A informando draftId de nota nova também deve falhar e preservar tudo
      try {
        noteRepository.updateNote(
          noteId = noteA.id,
          title = "Nota A sobrescrita",
          content = NoteContentDocument.empty(),
          folderId = null,
          expectedBaseRevision = noteA.revision,
          clearDraftId = draftNewId,
        )
        fail("Esperava InvalidRecoveryDraftException ao consolidar nota A com rascunho de nova nota")
      } catch (_: InvalidRecoveryDraftException) {
        assertEquals("Nota A original", noteRepository.getNoteById(noteA.id)?.title)
        assertEquals(noteA.revision, noteRepository.getNoteById(noteA.id)?.revision)
        assertNotNull(draftRepository.getCheckpointById(draftNewId))
        assertNotNull(draftRepository.getCheckpointById(draftAId))
      }

      // 3. Registro inconsistente no DAO (targetNoteId == noteA.id, mas draftId/sessionId incoerentes) é rejeitado
      val corruptedDraftId = "corrupted_draft_id"
      database.noteRecoveryDraftDao().insert(
        NoteRecoveryDraftEntity(
          draftId = corruptedDraftId,
          targetNoteId = noteA.id,
          sessionId = "sessao-corrompida",
          folderId = null,
          isPinned = false,
          title = "Corrompido",
          contentJson = """{"schemaVersion":1,"blocks":[]}""",
          paperStyle = NotePaperStyle.LINHAS.name,
          baseRevision = noteA.revision,
          checkpointRevision = 1L,
          baseNoteUpdatedAt = noteA.updatedAt,
          checkpointAt = 1234L,
        )
      )
      try {
        noteRepository.updateNote(
          noteId = noteA.id,
          title = "Nota A com draft incoerente",
          content = NoteContentDocument.empty(),
          folderId = null,
          expectedBaseRevision = noteA.revision,
          clearDraftId = corruptedDraftId,
        )
        fail("Esperava InvalidRecoveryDraftException para identificadores incoerentes")
      } catch (_: InvalidRecoveryDraftException) {
        assertEquals("Nota A original", noteRepository.getNoteById(noteA.id)?.title)
        assertNotNull(draftRepository.getCheckpointById(corruptedDraftId))
      }

      // 4. Atualizar nota A com o rascunho legítimo da própria nota funciona e remove apenas esse rascunho
      val updatedA =
        noteRepository.updateNote(
          noteId = noteA.id,
          title = "Rascunho A",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "a1", text = "Editado A"))),
          folderId = null,
          paperStyle = NotePaperStyle.OCEAN_BREEZE,
          expectedBaseRevision = noteA.revision,
          clearDraftId = draftAId,
        )
      assertEquals("Rascunho A", updatedA.title)
      assertEquals(noteA.revision + 1L, updatedA.revision)
      assertNull(draftRepository.getCheckpointById(draftAId))
      assertNotNull(draftRepository.getCheckpointById(draftBId))
      assertNotNull(draftRepository.getCheckpointById(draftNewId))
    }

  @Test
  fun `creating note validates new-note draft ownership and non-existent draftId fails safely`() =
    runTest {
      val existingNote =
        noteRepository.createNote(
          title = "Nota existente",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "e1", text = "Base"))),
        )
      val existingDraftId = NoteRecoveryDraftKeys.forExistingNote(existingNote.id, "sessao-ex")
      val newDraftId1 = NoteRecoveryDraftKeys.forNewNote("sessao-n1")
      val newDraftId2 = NoteRecoveryDraftKeys.forNewNote("sessao-n2")

      draftRepository.saveCheckpoint(
        NoteRecoveryDraftInput(
          draftId = existingDraftId,
          targetNoteId = existingNote.id,
          sessionId = "sessao-ex",
          folderId = null,
          fallbackSourceFolderId = null,
          isPinned = false,
          title = "Rascunho existente",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "e1", text = "Alterado"))),
          paperStyle = NotePaperStyle.MINIMALIST_PRO,
          baseRevision = existingNote.revision,
          checkpointRevision = 1L,
          baseNoteUpdatedAt = existingNote.updatedAt,
        )
      )
      draftRepository.saveCheckpoint(
        NoteRecoveryDraftInput(
          draftId = newDraftId1,
          targetNoteId = null,
          sessionId = "sessao-n1",
          folderId = null,
          fallbackSourceFolderId = null,
          isPinned = true,
          title = "Nova nota 1",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "n1", text = "Corpo 1"))),
          paperStyle = NotePaperStyle.TECH_DARK,
          baseRevision = 0L,
          checkpointRevision = 1L,
          baseNoteUpdatedAt = null,
        )
      )
      draftRepository.saveCheckpoint(
        NoteRecoveryDraftInput(
          draftId = newDraftId2,
          targetNoteId = null,
          sessionId = "sessao-n2",
          folderId = null,
          fallbackSourceFolderId = null,
          isPinned = false,
          title = "Nova nota 2",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "n2", text = "Corpo 2"))),
          paperStyle = NotePaperStyle.LINHAS,
          baseRevision = 0L,
          checkpointRevision = 1L,
          baseNoteUpdatedAt = null,
        )
      )

      // 1. Criar nota nova informando rascunho de nota existente deve falhar sem criar nota nem apagar rascunho
      try {
        noteRepository.createNote(
          title = "Criação indevida",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "x1", text = "Inválido"))),
          clearDraftId = existingDraftId,
        )
        fail("Esperava InvalidRecoveryDraftException ao criar nota informando rascunho de nota existente")
      } catch (_: InvalidRecoveryDraftException) {
        assertEquals(1, noteRepository.observeAllActiveNotes().first().size)
        assertNotNull(draftRepository.getCheckpointById(existingDraftId))
        assertNotNull(draftRepository.getCheckpointById(newDraftId1))
        assertNotNull(draftRepository.getCheckpointById(newDraftId2))
      }

      // 2. Informar draftId inexistente em createNote ou updateNote falha sem alterar notas nem apagar outros rascunhos
      val missingDraftId = NoteRecoveryDraftKeys.forNewNote("sessao-inexistente")
      try {
        noteRepository.createNote(
          title = "Com rascunho ausente",
          content = NoteContentDocument.empty(),
          clearDraftId = missingDraftId,
        )
        fail("Esperava InvalidRecoveryDraftException para clearDraftId inexistente em createNote")
      } catch (_: InvalidRecoveryDraftException) {
        assertEquals(1, noteRepository.observeAllActiveNotes().first().size)
        assertEquals(3, draftRepository.getAllCheckpoints().size)
      }

      try {
        noteRepository.updateNote(
          noteId = existingNote.id,
          title = "Update com rascunho ausente",
          content = NoteContentDocument.empty(),
          folderId = null,
          expectedBaseRevision = existingNote.revision,
          clearDraftId = NoteRecoveryDraftKeys.forExistingNote(existingNote.id, "nao-existe"),
        )
        fail("Esperava InvalidRecoveryDraftException para clearDraftId inexistente em updateNote")
      } catch (_: InvalidRecoveryDraftException) {
        val reloaded = noteRepository.getNoteById(existingNote.id)!!
        assertEquals("Nota existente", reloaded.title)
        assertEquals(existingNote.revision, reloaded.revision)
        assertEquals(3, draftRepository.getAllCheckpoints().size)
      }

      // 3. Criar nota nova com rascunho legítimo de nova nota funciona e remove apenas esse rascunho
      val createdNote =
        noteRepository.createNote(
          title = "Nova nota 1",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "n1", text = "Corpo 1"))),
          isPinned = true,
          paperStyle = NotePaperStyle.TECH_DARK,
          clearDraftId = newDraftId1,
        )
      assertEquals("Nova nota 1", createdNote.title)
      assertEquals(NotePaperStyle.TECH_DARK, createdNote.paperStyle)
      assertTrue(createdNote.isPinned)
      assertNull(draftRepository.getCheckpointById(newDraftId1))
      assertNotNull(draftRepository.getCheckpointById(existingDraftId))
      assertNotNull(draftRepository.getCheckpointById(newDraftId2))
    }

  @Test
  fun `null empty and blank clearDraftId succeed in createNote and updateNote preserving existing drafts`() =
    runTest {
      val baseNote =
        noteRepository.createNote(
          title = "Nota base",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "b1", text = "Texto base"))),
        )
      val existingDraftId = NoteRecoveryDraftKeys.forExistingNote(baseNote.id, "sessao-base")
      val newDraftId = NoteRecoveryDraftKeys.forNewNote("sessao-new")

      draftRepository.saveCheckpoint(
        NoteRecoveryDraftInput(
          draftId = existingDraftId,
          targetNoteId = baseNote.id,
          sessionId = "sessao-base",
          folderId = null,
          fallbackSourceFolderId = null,
          isPinned = false,
          title = "Rascunho nota base",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "b1", text = "Editado"))),
          paperStyle = NotePaperStyle.OCEAN_BREEZE,
          baseRevision = baseNote.revision,
          checkpointRevision = 1L,
          baseNoteUpdatedAt = baseNote.updatedAt,
        )
      )
      draftRepository.saveCheckpoint(
        NoteRecoveryDraftInput(
          draftId = newDraftId,
          targetNoteId = null,
          sessionId = "sessao-new",
          folderId = null,
          fallbackSourceFolderId = null,
          isPinned = false,
          title = "Rascunho nova nota",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "n1", text = "Novo"))),
          paperStyle = NotePaperStyle.LINHAS,
          baseRevision = 0L,
          checkpointRevision = 1L,
          baseNoteUpdatedAt = null,
        )
      )

      // createNote aceita null, "" e "   " sem tentar excluir nenhum rascunho
      val createdWithNull =
        noteRepository.createNote(title = "Criada null", clearDraftId = null)
      val createdWithEmpty =
        noteRepository.createNote(title = "Criada vazia", clearDraftId = "")
      val createdWithBlank =
        noteRepository.createNote(title = "Criada espaços", clearDraftId = "   ")

      assertEquals("Criada null", createdWithNull.title)
      assertEquals("Criada vazia", createdWithEmpty.title)
      assertEquals("Criada espaços", createdWithBlank.title)
      assertNotNull(draftRepository.getCheckpointById(existingDraftId))
      assertNotNull(draftRepository.getCheckpointById(newDraftId))

      // updateNote aceita null, "" e "   " sem tentar excluir nenhum rascunho
      val afterNull =
        noteRepository.updateNote(
          noteId = baseNote.id,
          title = "Atualizada null",
          content = NoteContentDocument.empty(),
          folderId = null,
          clearDraftId = null,
        )
      val afterEmpty =
        noteRepository.updateNote(
          noteId = baseNote.id,
          title = "Atualizada vazia",
          content = NoteContentDocument.empty(),
          folderId = null,
          clearDraftId = "",
        )
      val afterBlank =
        noteRepository.updateNote(
          noteId = baseNote.id,
          title = "Atualizada espaços",
          content = NoteContentDocument.empty(),
          folderId = null,
          clearDraftId = "   ",
        )

      assertEquals("Atualizada null", afterNull.title)
      assertEquals("Atualizada vazia", afterEmpty.title)
      assertEquals("Atualizada espaços", afterBlank.title)
      assertEquals(baseNote.revision + 3L, afterBlank.revision)
      assertNotNull(draftRepository.getCheckpointById(existingDraftId))
      assertNotNull(draftRepository.getCheckpointById(newDraftId))
      assertEquals(2, draftRepository.getAllCheckpoints().size)
    }

  @Test
  fun `existing checkpoint rejects changing targetNoteId or sessionId and preserves all original fields`() =
    runTest {
      val folderInitial = folderRepository.createFolder("Origem Identidade")
      val folderFallback = folderRepository.createFolder("Fallback Identidade")
      val noteA =
        noteRepository.createNote(
          title = "Nota A protegida",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "na1", text = "Base A"))),
        )
      val noteB =
        noteRepository.createNote(
          title = "Nota B protegida",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "nb1", text = "Base B"))),
        )

      // 1. Checkpoint de nova nota tentando mudar targetNoteId para nota existente ou mudar sessionId
      val newSessionId = "sessao-ident-nova"
      val newDraftId = NoteRecoveryDraftKeys.forNewNote(newSessionId)
      draftRepository.saveCheckpoint(
        NoteRecoveryDraftInput(
          draftId = newDraftId,
          targetNoteId = null,
          sessionId = newSessionId,
          folderId = null,
          initialFolderId = folderInitial.id,
          fallbackSourceFolderId = folderFallback.id,
          isPinned = true,
          title = "Nova protegida",
          content =
            NoteContentDocument(blocks = listOf(ParagraphBlock(id = "np1", text = "Corpo nova protegido"))),
          paperStyle = NotePaperStyle.SUNSET_WARMTH,
          initialPaperStyle = NotePaperStyle.FOREST_MIST,
          baseRevision = 0L,
          checkpointRevision = 3L,
          baseNoteUpdatedAt = null,
        )
      )

      val newAttempts =
        listOf(
          // Mesmo draftId de nova nota tentando trocar targetNoteId para noteA.id e burlar iniciais
          NoteRecoveryDraftInput(
            draftId = newDraftId,
            targetNoteId = noteA.id,
            sessionId = newSessionId,
            folderId = null,
            initialFolderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Sequestro para nota existente",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            initialPaperStyle = null,
            baseRevision = noteA.revision,
            checkpointRevision = 4L,
            baseNoteUpdatedAt = noteA.updatedAt,
          ),
          // Chave forExistingNote reutilizando sessionId da nova nota
          NoteRecoveryDraftInput(
            draftId = NoteRecoveryDraftKeys.forExistingNote(noteA.id, newSessionId),
            targetNoteId = noteA.id,
            sessionId = newSessionId,
            folderId = null,
            initialFolderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Reuso de sessionId como nota existente",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            initialPaperStyle = null,
            baseRevision = noteA.revision,
            checkpointRevision = 4L,
            baseNoteUpdatedAt = noteA.updatedAt,
          ),
          // Mesmo draftId tentando trocar sessionId
          NoteRecoveryDraftInput(
            draftId = newDraftId,
            targetNoteId = null,
            sessionId = "sessao-ident-alterada",
            folderId = null,
            initialFolderId = folderInitial.id,
            fallbackSourceFolderId = folderFallback.id,
            isPinned = false,
            title = "Troca de sessionId",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            initialPaperStyle = NotePaperStyle.FOREST_MIST,
            baseRevision = 0L,
            checkpointRevision = 4L,
            baseNoteUpdatedAt = null,
          ),
        )

      for ((idx, attempt) in newAttempts.withIndex()) {
        try {
          draftRepository.saveCheckpoint(attempt)
          fail("Tentativa #$idx deveria ter lançado InvalidRecoveryDraftException")
        } catch (_: InvalidRecoveryDraftException) {
          val reloaded = draftRepository.getCheckpointById(newDraftId)!!
          assertEquals(newDraftId, reloaded.draftId)
          assertEquals(newSessionId, reloaded.sessionId)
          assertNull(reloaded.targetNoteId)
          assertEquals(folderInitial.id, reloaded.initialFolderId)
          assertEquals(NotePaperStyle.FOREST_MIST, reloaded.initialPaperStyle)
          assertEquals(folderFallback.id, reloaded.fallbackSourceFolderId)
          assertNull(reloaded.folderId)
          assertTrue(reloaded.isPinned)
          assertEquals("Nova protegida", reloaded.title)
          assertEquals("Corpo nova protegido", reloaded.contentOrNull!!.blocks.first().text)
          assertEquals(NotePaperStyle.SUNSET_WARMTH, reloaded.paperStyle)
          assertEquals(3L, reloaded.checkpointRevision)
        }
      }

      // 2. Checkpoint de nota existente tentando mudar targetNoteId para null, para noteB ou mudar sessionId
      val existingSessionId = "sessao-ident-ex"
      val existingDraftId = NoteRecoveryDraftKeys.forExistingNote(noteA.id, existingSessionId)
      draftRepository.saveCheckpoint(
        NoteRecoveryDraftInput(
          draftId = existingDraftId,
          targetNoteId = noteA.id,
          sessionId = existingSessionId,
          folderId = null,
          initialFolderId = null,
          fallbackSourceFolderId = folderFallback.id,
          isPinned = false,
          title = "Editando Nota A",
          content =
            NoteContentDocument(blocks = listOf(ParagraphBlock(id = "na1", text = "Corpo A protegido"))),
          paperStyle = NotePaperStyle.OCEAN_BREEZE,
          initialPaperStyle = null,
          baseRevision = noteA.revision,
          checkpointRevision = 4L,
          baseNoteUpdatedAt = noteA.updatedAt,
        )
      )

      val existingAttempts =
        listOf(
          // Nota existente tentando mudar targetNoteId para null mantendo draftId
          NoteRecoveryDraftInput(
            draftId = existingDraftId,
            targetNoteId = null,
            sessionId = existingSessionId,
            folderId = null,
            initialFolderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Downgrade para nova nota",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            initialPaperStyle = null,
            baseRevision = 0L,
            checkpointRevision = 5L,
            baseNoteUpdatedAt = null,
          ),
          // Chave forNewNote reutilizando sessionId da nota existente
          NoteRecoveryDraftInput(
            draftId = NoteRecoveryDraftKeys.forNewNote(existingSessionId),
            targetNoteId = null,
            sessionId = existingSessionId,
            folderId = null,
            initialFolderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Reuso de sessionId como nova nota",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            initialPaperStyle = null,
            baseRevision = 0L,
            checkpointRevision = 5L,
            baseNoteUpdatedAt = null,
          ),
          // Nota existente tentando mudar targetNoteId para noteB mantendo draftId
          NoteRecoveryDraftInput(
            draftId = existingDraftId,
            targetNoteId = noteB.id,
            sessionId = existingSessionId,
            folderId = null,
            initialFolderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Troca para nota B",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            initialPaperStyle = null,
            baseRevision = noteB.revision,
            checkpointRevision = 5L,
            baseNoteUpdatedAt = noteB.updatedAt,
          ),
          // Chave forExistingNote(noteB) reutilizando sessionId de noteA
          NoteRecoveryDraftInput(
            draftId = NoteRecoveryDraftKeys.forExistingNote(noteB.id, existingSessionId),
            targetNoteId = noteB.id,
            sessionId = existingSessionId,
            folderId = null,
            initialFolderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Reuso de sessionId em noteB",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            initialPaperStyle = null,
            baseRevision = noteB.revision,
            checkpointRevision = 5L,
            baseNoteUpdatedAt = noteB.updatedAt,
          ),
          // Mesmo draftId tentando trocar sessionId
          NoteRecoveryDraftInput(
            draftId = existingDraftId,
            targetNoteId = noteA.id,
            sessionId = "sessao-sequestrada",
            folderId = null,
            initialFolderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Troca de sessionId em existente",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            initialPaperStyle = null,
            baseRevision = noteA.revision,
            checkpointRevision = 5L,
            baseNoteUpdatedAt = noteA.updatedAt,
          ),
        )

      for ((idx, attempt) in existingAttempts.withIndex()) {
        try {
          draftRepository.saveCheckpoint(attempt)
          fail("Tentativa existente #$idx deveria ter lançado InvalidRecoveryDraftException")
        } catch (_: InvalidRecoveryDraftException) {
          val reloaded = draftRepository.getCheckpointById(existingDraftId)!!
          assertEquals(existingDraftId, reloaded.draftId)
          assertEquals(existingSessionId, reloaded.sessionId)
          assertEquals(noteA.id, reloaded.targetNoteId)
          assertNull(reloaded.initialFolderId)
          assertNull(reloaded.initialPaperStyle)
          assertEquals(folderFallback.id, reloaded.fallbackSourceFolderId)
          assertNull(reloaded.folderId)
          assertEquals("Editando Nota A", reloaded.title)
          assertEquals("Corpo A protegido", reloaded.contentOrNull!!.blocks.first().text)
          assertEquals(NotePaperStyle.OCEAN_BREEZE, reloaded.paperStyle)
          assertEquals(4L, reloaded.checkpointRevision)
        }
      }
    }

  @Test
  fun `transactional identity guards reject updating mismatched persisted entity inserted via dao`() =
    runTest {
      val folderInitial = folderRepository.createFolder("Origem DAO")
      val folderFallback = folderRepository.createFolder("Fallback DAO")
      val targetNote =
        noteRepository.createNote(
          title = "Nota alvo DAO",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "d1", text = "Nota DAO"))),
        )

      val validContentJson =
        """{"schemaVersion":1,"blocks":[{"type":"paragraph","id":"dao1","text":"Texto original DAO","spans":[]}]}"""

      // Caso 1: Registro gravado via DAO com draftId compatível com note_<id>_<session>, porém targetNoteId = null.
      // O input do atacante passa em assertConsistentKeys, mas deve falhar dentro de withTransaction (targetNoteId != input.targetNoteId).
      val mismatchTargetSession = "dao-mismatch-target"
      val mismatchTargetDraftId =
        NoteRecoveryDraftKeys.forExistingNote(targetNote.id, mismatchTargetSession)
      database.noteRecoveryDraftDao().insert(
        NoteRecoveryDraftEntity(
          draftId = mismatchTargetDraftId,
          targetNoteId = null,
          sessionId = mismatchTargetSession,
          folderId = null,
          initialFolderId = folderInitial.id,
          fallbackSourceFolderId = folderFallback.id,
          isPinned = true,
          title = "Original DAO target nulo",
          contentJson = validContentJson,
          paperStyle = NotePaperStyle.FOREST_MIST.name,
          initialPaperStyle = NotePaperStyle.OCEAN_BREEZE.name,
          baseRevision = 0L,
          checkpointRevision = 2L,
          baseNoteUpdatedAt = null,
          checkpointAt = 10_000L,
        )
      )

      try {
        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = mismatchTargetDraftId,
            targetNoteId = targetNote.id,
            sessionId = mismatchTargetSession,
            folderId = null,
            initialFolderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Contorno via DAO targetNoteId",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            initialPaperStyle = null,
            baseRevision = targetNote.revision,
            checkpointRevision = 3L,
            baseNoteUpdatedAt = targetNote.updatedAt,
          )
        )
        fail("Esperava InvalidRecoveryDraftException pela trava transacional de targetNoteId")
      } catch (_: InvalidRecoveryDraftException) {
        val intact = draftRepository.getCheckpointById(mismatchTargetDraftId)!!
        assertEquals(mismatchTargetDraftId, intact.draftId)
        assertEquals(mismatchTargetSession, intact.sessionId)
        assertNull(intact.targetNoteId)
        assertEquals(folderInitial.id, intact.initialFolderId)
        assertEquals(NotePaperStyle.OCEAN_BREEZE, intact.initialPaperStyle)
        assertEquals(folderFallback.id, intact.fallbackSourceFolderId)
        assertEquals("Original DAO target nulo", intact.title)
        assertEquals("Texto original DAO", intact.contentOrNull!!.blocks.first().text)
        assertEquals(2L, intact.checkpointRevision)
      }

      // Caso 2: Registro gravado via DAO com draftId = new_<atacante>, porém sessionId = "dao-original-owner".
      // O input do atacante passa em assertConsistentKeys, mas deve falhar dentro de withTransaction (sessionId != cleanSessionId).
      val attackerSession = "dao-attacker-session"
      val originalOwnerSession = "dao-original-owner"
      val mismatchSessionDraftId = NoteRecoveryDraftKeys.forNewNote(attackerSession)
      database.noteRecoveryDraftDao().insert(
        NoteRecoveryDraftEntity(
          draftId = mismatchSessionDraftId,
          targetNoteId = null,
          sessionId = originalOwnerSession,
          folderId = null,
          initialFolderId = folderInitial.id,
          fallbackSourceFolderId = folderFallback.id,
          isPinned = false,
          title = "Original DAO dono",
          contentJson = validContentJson,
          paperStyle = NotePaperStyle.TECH_DARK.name,
          initialPaperStyle = NotePaperStyle.LINHAS.name,
          baseRevision = 0L,
          checkpointRevision = 2L,
          baseNoteUpdatedAt = null,
          checkpointAt = 10_001L,
        )
      )

      try {
        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = mismatchSessionDraftId,
            targetNoteId = null,
            sessionId = attackerSession,
            folderId = null,
            initialFolderId = folderInitial.id,
            fallbackSourceFolderId = null,
            isPinned = true,
            title = "Contorno via DAO sessionId",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            initialPaperStyle = NotePaperStyle.LINHAS,
            baseRevision = 0L,
            checkpointRevision = 3L,
            baseNoteUpdatedAt = null,
          )
        )
        fail("Esperava InvalidRecoveryDraftException pela trava transacional de sessionId")
      } catch (_: InvalidRecoveryDraftException) {
        val intact = draftRepository.getCheckpointById(mismatchSessionDraftId)!!
        assertEquals(mismatchSessionDraftId, intact.draftId)
        assertEquals(originalOwnerSession, intact.sessionId)
        assertNull(intact.targetNoteId)
        assertEquals(folderInitial.id, intact.initialFolderId)
        assertEquals(NotePaperStyle.LINHAS, intact.initialPaperStyle)
        assertEquals(folderFallback.id, intact.fallbackSourceFolderId)
        assertEquals("Original DAO dono", intact.title)
        assertEquals("Texto original DAO", intact.contentOrNull!!.blocks.first().text)
        assertEquals(2L, intact.checkpointRevision)
      }
    }
}
