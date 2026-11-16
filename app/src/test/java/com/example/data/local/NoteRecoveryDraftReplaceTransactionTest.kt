package com.example.data.local

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.coroutines.DispatcherProvider
import com.example.core.time.ClockProvider
import com.example.data.local.database.AnoteVoceDatabase
import com.example.data.local.entity.NoteRecoveryDraftEntity
import com.example.data.local.mapper.NoteContentCodec
import com.example.data.repository.FolderRepositoryImpl
import com.example.data.repository.NoteRecoveryDraftRepositoryImpl
import com.example.data.repository.NoteRepositoryImpl
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.NoteRecoveryDraftInput
import com.example.domain.model.NoteRecoveryDraftKeys
import com.example.domain.model.ParagraphBlock
import com.example.domain.model.ReplaceCheckpointWithNewNoteResult
import com.example.domain.model.SaveCheckpointResult
import com.example.domain.policy.NoteContentPolicy
import com.example.domain.repository.InvalidRecoveryDraftException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
class NoteRecoveryDraftReplaceTransactionTest {

  private lateinit var database: AnoteVoceDatabase
  private lateinit var folderRepository: FolderRepositoryImpl
  private lateinit var noteRepository: NoteRepositoryImpl
  private lateinit var draftRepository: NoteRecoveryDraftRepositoryImpl

  private var fakeTimeMillis = 1_700_200_000_000L

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
  fun `getCheckpointBySessionId trims whitespace, returns null for blank or missing, and loads new or existing drafts`() =
    runTest {
      assertNull(draftRepository.getCheckpointBySessionId("   "))
      assertNull(draftRepository.getCheckpointBySessionId("sessao-inexistente"))

      val newSessionId = "session-new-lookup"
      val newDraftId = NoteRecoveryDraftKeys.forNewNote(newSessionId)
      draftRepository.saveCheckpoint(
        newNoteInput(
          draftId = newDraftId,
          sessionId = newSessionId,
          title = "Nova por sessão",
          checkpointRevision = 2L,
        )
      )

      val note = noteRepository.createNote(title = "Base existente")
      val existingSessionId = "session-existing-lookup"
      val existingDraftId = NoteRecoveryDraftKeys.forExistingNote(note.id, existingSessionId)
      draftRepository.saveCheckpoint(
        existingNoteInput(
          draftId = existingDraftId,
          noteId = note.id,
          sessionId = existingSessionId,
          title = "Edição existente",
          baseRevision = note.revision,
          baseNoteUpdatedAt = note.updatedAt,
          checkpointRevision = 3L,
        )
      )

      val loadedNew = draftRepository.getCheckpointBySessionId("  $newSessionId  ")
      assertNotNull(loadedNew)
      assertEquals(newDraftId, loadedNew!!.draftId)
      assertEquals(null, loadedNew.targetNoteId)

      val loadedExisting = draftRepository.getCheckpointBySessionId("  $existingSessionId\t")
      assertNotNull(loadedExisting)
      assertEquals(existingDraftId, loadedExisting!!.draftId)
      assertEquals(note.id, loadedExisting.targetNoteId)
    }

  @Test
  fun `case A inserts new-note checkpoint and deletes exclusively oldDraftId`() = runTest {
    val folder = folderRepository.createFolder("Projetos")
    val note = noteRepository.createNote(title = "Original", folderId = folder.id)

    val oldSessionId = "old-session-a"
    val oldDraftId = NoteRecoveryDraftKeys.forExistingNote(note.id, oldSessionId)
    draftRepository.saveCheckpoint(
      existingNoteInput(
        draftId = oldDraftId,
        noteId = note.id,
        sessionId = oldSessionId,
        folderId = folder.id,
        title = "Versão conflitante",
        baseRevision = note.revision,
        baseNoteUpdatedAt = note.updatedAt,
        checkpointRevision = 4L,
      )
    )

    val newSessionId = "new-session-a"
    val newDraftId = NoteRecoveryDraftKeys.forNewNote(newSessionId)
    val expectedInput =
      newNoteInput(
        draftId = newDraftId,
        sessionId = newSessionId,
        folderId = null,
        initialFolderId = folder.id,
        fallbackSourceFolderId = folder.id,
        isPinned = true,
        title = "Convertida em nova nota",
        content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "b1", text = "Corpo"))),
        paperStyle = NotePaperStyle.FOREST_MIST,
        initialPaperStyle = NotePaperStyle.LINHAS,
        checkpointRevision = 1L,
      )

    val result =
      draftRepository.replaceCheckpointWithNewNote(
        oldDraftId = oldDraftId,
        expectedOldSessionId = oldSessionId,
        expectedOldTargetNoteId = note.id,
        newInput = expectedInput,
      )

    assertTrue(result is ReplaceCheckpointWithNewNoteResult.Replaced)
    val replacedDraft = (result as ReplaceCheckpointWithNewNoteResult.Replaced).draft
    assertEquals(newDraftId, replacedDraft.draftId)
    assertEquals(newSessionId, replacedDraft.sessionId)
    assertNull(replacedDraft.targetNoteId)
    assertEquals(folder.id, replacedDraft.initialFolderId)
    assertEquals(folder.id, replacedDraft.fallbackSourceFolderId)
    assertTrue(replacedDraft.isPinned)
    assertEquals(NotePaperStyle.FOREST_MIST, replacedDraft.paperStyle)
    assertEquals(NotePaperStyle.LINHAS, replacedDraft.initialPaperStyle)

    assertNull(database.noteRecoveryDraftDao().getByDraftId(oldDraftId))
    val persistedNew = database.noteRecoveryDraftDao().getByDraftId(newDraftId)
    assertNotNull(persistedNew)
    assertEquals("Convertida em nova nota", persistedNew!!.title)
    assertEquals(1, database.noteRecoveryDraftDao().getAllCheckpoints().size)
  }

  @Test
  fun `case A with absent oldDraftId still inserts new-note checkpoint and returns Replaced`() =
    runTest {
      val note = noteRepository.createNote(title = "Base")
      val absentOldDraftId = NoteRecoveryDraftKeys.forExistingNote(note.id, "never-autosaved")
      val newSessionId = "new-session-no-old"
      val newDraftId = NoteRecoveryDraftKeys.forNewNote(newSessionId)

      val result =
        draftRepository.replaceCheckpointWithNewNote(
          oldDraftId = absentOldDraftId,
          expectedOldSessionId = "never-autosaved",
          expectedOldTargetNoteId = note.id,
          newInput =
            newNoteInput(
              draftId = newDraftId,
              sessionId = newSessionId,
              title = "Salva sem checkpoint prévio",
            ),
        )

      assertTrue(result is ReplaceCheckpointWithNewNoteResult.Replaced)
      assertNotNull(database.noteRecoveryDraftDao().getByDraftId(newDraftId))
      assertNull(database.noteRecoveryDraftDao().getByDraftId(absentOldDraftId))
    }

  @Test
  fun `case B repeated identical call when oldDraftId is gone returns AlreadyReplaced idempotently`() =
    runTest {
      val folder = folderRepository.createFolder("Estudos")
      val note = noteRepository.createNote(title = "Original", folderId = folder.id)
      val oldDraftId = NoteRecoveryDraftKeys.forExistingNote(note.id, "old-session-b")
      draftRepository.saveCheckpoint(
        existingNoteInput(
          draftId = oldDraftId,
          noteId = note.id,
          sessionId = "old-session-b",
          folderId = folder.id,
          title = "Antigo",
          baseRevision = note.revision,
          baseNoteUpdatedAt = note.updatedAt,
          checkpointRevision = 2L,
        )
      )

      val newSessionId = "new-session-b"
      val newDraftId = NoteRecoveryDraftKeys.forNewNote(newSessionId)
      val input =
        newNoteInput(
          draftId = newDraftId,
          sessionId = newSessionId,
          folderId = folder.id,
          initialFolderId = folder.id,
          fallbackSourceFolderId = null,
          isPinned = false,
          title = "Mesma nota",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Texto"))),
          paperStyle = NotePaperStyle.PONTILHADO,
          initialPaperStyle = NotePaperStyle.PONTILHADO,
          checkpointRevision = 1L,
        )

      val first =
        draftRepository.replaceCheckpointWithNewNote(
          oldDraftId = oldDraftId,
          expectedOldSessionId = "old-session-b",
          expectedOldTargetNoteId = note.id,
          newInput = input,
        )
      assertTrue(first is ReplaceCheckpointWithNewNoteResult.Replaced)
      val initialCheckpointAt = (first as ReplaceCheckpointWithNewNoteResult.Replaced).draft.checkpointAt

      val second =
        draftRepository.replaceCheckpointWithNewNote(
          oldDraftId = oldDraftId,
          expectedOldSessionId = "old-session-b",
          expectedOldTargetNoteId = note.id,
          newInput = input,
        )
      assertTrue(second is ReplaceCheckpointWithNewNoteResult.AlreadyReplaced)
      val alreadyDraft = (second as ReplaceCheckpointWithNewNoteResult.AlreadyReplaced).draft
      assertEquals(initialCheckpointAt, alreadyDraft.checkpointAt)
      assertEquals(1, database.noteRecoveryDraftDao().getAllCheckpoints().size)
      assertNull(database.noteRecoveryDraftDao().getByDraftId(oldDraftId))
    }

  @Test
  fun `case C rejects sessionId collision with existing or third draft and preserves database intact`() =
    runTest {
      val note = noteRepository.createNote(title = "Nota 1")
      val sharedSessionId = "colliding-session"
      val oldDraftId = NoteRecoveryDraftKeys.forExistingNote(note.id, sharedSessionId)
      draftRepository.saveCheckpoint(
        existingNoteInput(
          draftId = oldDraftId,
          noteId = note.id,
          sessionId = sharedSessionId,
          title = "Old",
          baseRevision = note.revision,
          baseNoteUpdatedAt = note.updatedAt,
          checkpointRevision = 2L,
        )
      )

      val thirdSessionId = "third-session"
      val thirdDraftId = NoteRecoveryDraftKeys.forNewNote(thirdSessionId)
      draftRepository.saveCheckpoint(
        newNoteInput(
          draftId = thirdDraftId,
          sessionId = thirdSessionId,
          title = "Terceiro rascunho",
        )
      )

      // Tentativa reutilizando o mesmo sessionId do rascunho antigo: falha mantendo oldDraftId intacto
      val collidingNewDraftId = NoteRecoveryDraftKeys.forNewNote(sharedSessionId)
      try {
        draftRepository.replaceCheckpointWithNewNote(
          oldDraftId = oldDraftId,
          expectedOldSessionId = sharedSessionId,
          expectedOldTargetNoteId = note.id,
          newInput =
            newNoteInput(
              draftId = collidingNewDraftId,
              sessionId = sharedSessionId,
              title = "Colisão",
            ),
        )
        fail("Esperava InvalidRecoveryDraftException na colisão com oldDraftId")
      } catch (_: InvalidRecoveryDraftException) {
        assertNotNull(database.noteRecoveryDraftDao().getByDraftId(oldDraftId))
        assertNull(database.noteRecoveryDraftDao().getByDraftId(collidingNewDraftId))
      }

      // Tentativa colidindo sessionId com um terceiro rascunho já persistido: preserva ambos intactos
      val collidingWithThirdDraftId = NoteRecoveryDraftKeys.forNewNote(thirdSessionId)
      try {
        draftRepository.replaceCheckpointWithNewNote(
          oldDraftId = oldDraftId,
          expectedOldSessionId = sharedSessionId,
          expectedOldTargetNoteId = note.id,
          newInput =
            newNoteInput(
              draftId = collidingWithThirdDraftId,
              sessionId = thirdSessionId,
              title = "Tentativa sobrescrita do terceiro rascunho",
            ),
        )
        fail("Esperava InvalidRecoveryDraftException na colisão com terceiro rascunho")
      } catch (_: InvalidRecoveryDraftException) {
        assertNotNull(database.noteRecoveryDraftDao().getByDraftId(oldDraftId))
        assertEquals(
          "Terceiro rascunho",
          database.noteRecoveryDraftDao().getByDraftId(thirdDraftId)?.title,
        )
      }

      // Inserindo via DAO um registro inconsistente para garantir rejeição por targetNoteId != null no destino
      val foreignSessionDraftId = NoteRecoveryDraftKeys.forNewNote("target-conflict-draft")
      database.noteRecoveryDraftDao().insert(
        NoteRecoveryDraftEntity(
          draftId = foreignSessionDraftId,
          targetNoteId = note.id,
          sessionId = "target-conflict-draft",
          folderId = null,
          isPinned = false,
          title = "Existente indevido",
          contentJson = NoteContentCodec.encode(NoteContentDocument.empty()),
          paperStyle = NotePaperStyle.LINHAS.name,
          baseRevision = note.revision,
          checkpointRevision = 1L,
          baseNoteUpdatedAt = note.updatedAt,
          checkpointAt = 1234L,
        )
      )
      try {
        draftRepository.replaceCheckpointWithNewNote(
          oldDraftId = oldDraftId,
          expectedOldSessionId = sharedSessionId,
          expectedOldTargetNoteId = note.id,
          newInput =
            newNoteInput(
              draftId = foreignSessionDraftId,
              sessionId = "target-conflict-draft",
              title = "Conflito de targetNoteId",
            ),
        )
        fail("Esperava InvalidRecoveryDraftException para checkpoint existente com targetNoteId != null")
      } catch (_: InvalidRecoveryDraftException) {
        assertNotNull(database.noteRecoveryDraftDao().getByDraftId(oldDraftId))
        assertEquals(
          note.id,
          database.noteRecoveryDraftDao().getByDraftId(foreignSessionDraftId)?.targetNoteId,
        )
      }

      // Verificação adicional de inputs inválidos sem tocar no banco
      try {
        draftRepository.replaceCheckpointWithNewNote(
          oldDraftId = "   ",
          expectedOldSessionId = sharedSessionId,
          expectedOldTargetNoteId = note.id,
          newInput =
            newNoteInput(
              draftId = NoteRecoveryDraftKeys.forNewNote("valid-new"),
              sessionId = "valid-new",
            ),
        )
        fail("Esperava InvalidRecoveryDraftException para oldDraftId em branco")
      } catch (_: InvalidRecoveryDraftException) {
        assertNotNull(database.noteRecoveryDraftDao().getByDraftId(oldDraftId))
      }

      try {
        draftRepository.replaceCheckpointWithNewNote(
          oldDraftId = oldDraftId,
          expectedOldSessionId = sharedSessionId,
          expectedOldTargetNoteId = 0L,
          newInput =
            newNoteInput(
              draftId = NoteRecoveryDraftKeys.forNewNote("valid-new-2"),
              sessionId = "valid-new-2",
            ),
        )
        fail("Esperava InvalidRecoveryDraftException para expectedOldTargetNoteId <= 0")
      } catch (_: InvalidRecoveryDraftException) {
        assertNotNull(database.noteRecoveryDraftDao().getByDraftId(oldDraftId))
      }
    }

  @Test
  fun `case D rejects existing newDraftId with divergent payload preserving both drafts untouched`() =
    runTest {
      val folderA = folderRepository.createFolder("Pasta A")
      val folderB = folderRepository.createFolder("Pasta B")
      val note = noteRepository.createNote(title = "Original", folderId = folderA.id)

      val oldSessionId = "old-session-d"
      val oldDraftId = NoteRecoveryDraftKeys.forExistingNote(note.id, oldSessionId)
      draftRepository.saveCheckpoint(
        existingNoteInput(
          draftId = oldDraftId,
          noteId = note.id,
          sessionId = oldSessionId,
          title = "Rascunho antigo D",
          baseRevision = note.revision,
          baseNoteUpdatedAt = note.updatedAt,
          checkpointRevision = 2L,
        )
      )

      val newSessionId = "new-session-d"
      val newDraftId = NoteRecoveryDraftKeys.forNewNote(newSessionId)
      val baseInput =
        newNoteInput(
          draftId = newDraftId,
          sessionId = newSessionId,
          folderId = folderA.id,
          initialFolderId = folderA.id,
          fallbackSourceFolderId = null,
          isPinned = false,
          title = "Título persistido",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "1", text = "Conteúdo A"))),
          paperStyle = NotePaperStyle.LINHAS,
          initialPaperStyle = NotePaperStyle.LINHAS,
          checkpointRevision = 1L,
        )
      val savedExisting =
        (draftRepository.saveCheckpoint(baseInput) as SaveCheckpointResult.Saved).draft

      val divergentInputs =
        listOf(
          baseInput.copy(title = "Título diferente"),
          baseInput.copy(
            content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "1", text = "Conteúdo B")))
          ),
          baseInput.copy(paperStyle = NotePaperStyle.SEM_LINHAS),
          baseInput.copy(initialPaperStyle = NotePaperStyle.PONTILHADO),
          baseInput.copy(folderId = folderB.id),
          baseInput.copy(initialFolderId = folderB.id),
          baseInput.copy(folderId = null, fallbackSourceFolderId = folderA.id),
          baseInput.copy(isPinned = true),
          baseInput.copy(checkpointRevision = 2L),
        )

      for (divergent in divergentInputs) {
        try {
          draftRepository.replaceCheckpointWithNewNote(
            oldDraftId = oldDraftId,
            expectedOldSessionId = oldSessionId,
            expectedOldTargetNoteId = note.id,
            newInput = divergent,
          )
          fail("Esperava InvalidRecoveryDraftException para divergência em $divergent")
        } catch (_: InvalidRecoveryDraftException) {
          val currentOld = database.noteRecoveryDraftDao().getByDraftId(oldDraftId)
          val currentNew = database.noteRecoveryDraftDao().getByDraftId(newDraftId)
          assertNotNull(currentOld)
          assertNotNull(currentNew)
          assertEquals("Título persistido", currentNew!!.title)
          assertEquals(savedExisting.checkpointAt, currentNew.checkpointAt)
          assertEquals(1L, currentNew.checkpointRevision)
        }
      }
    }

  @Test
  fun `case E rejects when oldDraftId and identical newDraftId both coexist prior to call`() =
    runTest {
      val note = noteRepository.createNote(title = "Nota E")
      val oldSessionId = "old-session-e"
      val oldDraftId = NoteRecoveryDraftKeys.forExistingNote(note.id, oldSessionId)
      draftRepository.saveCheckpoint(
        existingNoteInput(
          draftId = oldDraftId,
          noteId = note.id,
          sessionId = oldSessionId,
          title = "Antigo E",
          baseRevision = note.revision,
          baseNoteUpdatedAt = note.updatedAt,
          checkpointRevision = 1L,
        )
      )

      val newSessionId = "new-session-e"
      val newDraftId = NoteRecoveryDraftKeys.forNewNote(newSessionId)
      val sharedNewInput =
        newNoteInput(
          draftId = newDraftId,
          sessionId = newSessionId,
          title = "Já existente antes",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Corpo E"))),
          paperStyle = NotePaperStyle.MINIMALIST_PRO,
          initialPaperStyle = NotePaperStyle.LINHAS,
          checkpointRevision = 1L,
        )
      draftRepository.saveCheckpoint(sharedNewInput)

      try {
        draftRepository.replaceCheckpointWithNewNote(
          oldDraftId = oldDraftId,
          expectedOldSessionId = oldSessionId,
          expectedOldTargetNoteId = note.id,
          newInput = sharedNewInput,
        )
        fail("Esperava InvalidRecoveryDraftException no Caso E")
      } catch (_: InvalidRecoveryDraftException) {
        assertNotNull(database.noteRecoveryDraftDao().getByDraftId(oldDraftId))
        assertNotNull(database.noteRecoveryDraftDao().getByDraftId(newDraftId))
      }
    }

  @Test
  fun `replacement preserves drafts from other sessions pointing to the same targetNoteId`() =
    runTest {
      val note = noteRepository.createNote(title = "Nota compartilhada")
      val sessionOne = "session-one"
      val draftOne = NoteRecoveryDraftKeys.forExistingNote(note.id, sessionOne)
      val sessionTwo = "session-two"
      val draftTwo = NoteRecoveryDraftKeys.forExistingNote(note.id, sessionTwo)

      draftRepository.saveCheckpoint(
        existingNoteInput(
          draftId = draftOne,
          noteId = note.id,
          sessionId = sessionOne,
          title = "Sessão 1",
          baseRevision = note.revision,
          baseNoteUpdatedAt = note.updatedAt,
          checkpointRevision = 1L,
        )
      )
      draftRepository.saveCheckpoint(
        existingNoteInput(
          draftId = draftTwo,
          noteId = note.id,
          sessionId = sessionTwo,
          title = "Sessão 2",
          baseRevision = note.revision,
          baseNoteUpdatedAt = note.updatedAt,
          checkpointRevision = 1L,
        )
      )

      val newSessionId = "converted-from-one"
      val newDraftId = NoteRecoveryDraftKeys.forNewNote(newSessionId)
      val result =
        draftRepository.replaceCheckpointWithNewNote(
          oldDraftId = draftOne,
          expectedOldSessionId = sessionOne,
          expectedOldTargetNoteId = note.id,
          newInput =
            newNoteInput(
              draftId = newDraftId,
              sessionId = newSessionId,
              title = "Convertido da sessão 1",
            ),
        )

      assertTrue(result is ReplaceCheckpointWithNewNoteResult.Replaced)
      assertNull(database.noteRecoveryDraftDao().getByDraftId(draftOne))
      assertNotNull(database.noteRecoveryDraftDao().getByDraftId(newDraftId))
      val survivingSibling = database.noteRecoveryDraftDao().getByDraftId(draftTwo)
      assertNotNull(survivingSibling)
      assertEquals("Sessão 2", survivingSibling!!.title)
    }

  @Test
  fun `rolls back entire Room SQLite transaction when deleting oldDraftId fails after inserting new draft`() =
    runTest {
      val note = noteRepository.createNote(title = "Original rollback")
      val oldSessionId = "old-session-rollback"
      val oldDraftId = NoteRecoveryDraftKeys.forExistingNote(note.id, oldSessionId)
      draftRepository.saveCheckpoint(
        existingNoteInput(
          draftId = oldDraftId,
          noteId = note.id,
          sessionId = oldSessionId,
          title = "Rascunho protegido",
          baseRevision = note.revision,
          baseNoteUpdatedAt = note.updatedAt,
          checkpointRevision = 5L,
        )
      )

      val writableDb = database.openHelper.writableDatabase
      writableDb.execSQL(
        """
        CREATE TRIGGER fail_delete_old_draft
        BEFORE DELETE ON note_recovery_drafts
        WHEN OLD.draft_id = '$oldDraftId'
        BEGIN
          SELECT RAISE(ABORT, 'Falha SQLite simulada ao excluir rascunho anterior');
        END;
        """.trimIndent()
      )

      val newSessionId = "new-session-rollback"
      val newDraftId = NoteRecoveryDraftKeys.forNewNote(newSessionId)
      try {
        draftRepository.replaceCheckpointWithNewNote(
          oldDraftId = oldDraftId,
          expectedOldSessionId = oldSessionId,
          expectedOldTargetNoteId = note.id,
          newInput =
            newNoteInput(
              draftId = newDraftId,
              sessionId = newSessionId,
              title = "Não deve persistir após rollback",
              content =
                NoteContentPolicy.normalizeDocument(
                  NoteContentDocument(blocks = listOf(ParagraphBlock(id = "r1", text = "Rollback")))
                ),
            ),
        )
        fail("Esperava SQLiteException disparada pelo trigger ao excluir oldDraftId")
      } catch (_: SQLiteException) {
        // O INSERT do novo rascunho já tinha ocorrido dentro de withTransaction antes do DELETE;
        // o rollback real do SQLite precisa ter desfeito o INSERT e preservado o oldDraftId intacto.
        assertNull(database.noteRecoveryDraftDao().getByDraftId(newDraftId))
        val intactOld = database.noteRecoveryDraftDao().getByDraftId(oldDraftId)
        assertNotNull(intactOld)
        assertEquals("Rascunho protegido", intactOld!!.title)
        assertEquals(5L, intactOld.checkpointRevision)
      } finally {
        writableDb.execSQL("DROP TRIGGER IF EXISTS fail_delete_old_draft")
      }
    }

  @Test
  fun `rejects oldDraftId belonging to another session of the same note without deleting it or inserting new draft`() =
    runTest {
      val note = noteRepository.createNote(title = "Nota com múltiplas sessões")
      val expectedSessionId = "sessao-convertida"
      val expectedOldDraftId = NoteRecoveryDraftKeys.forExistingNote(note.id, expectedSessionId)
      val foreignSessionId = "sessao-alheia-mesma-nota"
      val foreignOldDraftId = NoteRecoveryDraftKeys.forExistingNote(note.id, foreignSessionId)

      draftRepository.saveCheckpoint(
        existingNoteInput(
          draftId = expectedOldDraftId,
          noteId = note.id,
          sessionId = expectedSessionId,
          title = "Rascunho sessão A",
          baseRevision = note.revision,
          baseNoteUpdatedAt = note.updatedAt,
          checkpointRevision = 1L,
        )
      )
      draftRepository.saveCheckpoint(
        existingNoteInput(
          draftId = foreignOldDraftId,
          noteId = note.id,
          sessionId = foreignSessionId,
          title = "Rascunho sessão B protegido",
          baseRevision = note.revision,
          baseNoteUpdatedAt = note.updatedAt,
          checkpointRevision = 2L,
        )
      )

      val newSessionId = "nova-nota-apos-falha-cruzada"
      val newDraftId = NoteRecoveryDraftKeys.forNewNote(newSessionId)
      try {
        draftRepository.replaceCheckpointWithNewNote(
          oldDraftId = foreignOldDraftId,
          expectedOldSessionId = expectedSessionId,
          expectedOldTargetNoteId = note.id,
          newInput =
            newNoteInput(
              draftId = newDraftId,
              sessionId = newSessionId,
              title = "Tentativa cruzada",
            ),
        )
        fail("Esperava InvalidRecoveryDraftException para oldDraftId de outra sessão da mesma nota")
      } catch (_: InvalidRecoveryDraftException) {
        val foreignStillExists = database.noteRecoveryDraftDao().getByDraftId(foreignOldDraftId)
        assertNotNull(foreignStillExists)
        assertEquals("Rascunho sessão B protegido", foreignStillExists!!.title)
        assertNotNull(database.noteRecoveryDraftDao().getByDraftId(expectedOldDraftId))
        assertNull(database.noteRecoveryDraftDao().getByDraftId(newDraftId))
        assertEquals(2, database.noteRecoveryDraftDao().getAllCheckpoints().size)
      }
    }

  @Test
  fun `rejects oldDraftId belonging to another note without deleting it or inserting new draft`() =
    runTest {
      val noteA = noteRepository.createNote(title = "Nota A")
      val noteB = noteRepository.createNote(title = "Nota B")
      val sessionId = "sessao-nota-b"
      val draftOfNoteB = NoteRecoveryDraftKeys.forExistingNote(noteB.id, sessionId)

      draftRepository.saveCheckpoint(
        existingNoteInput(
          draftId = draftOfNoteB,
          noteId = noteB.id,
          sessionId = sessionId,
          title = "Rascunho da nota B",
          baseRevision = noteB.revision,
          baseNoteUpdatedAt = noteB.updatedAt,
          checkpointRevision = 3L,
        )
      )

      val newSessionId = "nova-nota-a"
      val newDraftId = NoteRecoveryDraftKeys.forNewNote(newSessionId)
      try {
        draftRepository.replaceCheckpointWithNewNote(
          oldDraftId = draftOfNoteB,
          expectedOldSessionId = sessionId,
          expectedOldTargetNoteId = noteA.id,
          newInput =
            newNoteInput(
              draftId = newDraftId,
              sessionId = newSessionId,
              title = "Não deve apagar rascunho da nota B",
            ),
        )
        fail("Esperava InvalidRecoveryDraftException para oldDraftId pertencente a outra nota")
      } catch (_: InvalidRecoveryDraftException) {
        val survivingNoteBDraft = database.noteRecoveryDraftDao().getByDraftId(draftOfNoteB)
        assertNotNull(survivingNoteBDraft)
        assertEquals(noteB.id, survivingNoteBDraft!!.targetNoteId)
        assertEquals("Rascunho da nota B", survivingNoteBDraft.title)
        assertNull(database.noteRecoveryDraftDao().getByDraftId(newDraftId))
        assertEquals(1, database.noteRecoveryDraftDao().getAllCheckpoints().size)
      }
    }

  @Test
  fun `rejects valid oldDraftId with divergent sessionId or targetNoteId in caller or persisted row`() =
    runTest {
      val note = noteRepository.createNote(title = "Nota validação banco")
      val otherNote = noteRepository.createNote(title = "Outra nota banco")

      // Caso 1: oldDraftId válido no banco, mas chamador informa expectedOldSessionId divergente.
      val realSessionId = "sessao-real"
      val validOldDraftId = NoteRecoveryDraftKeys.forExistingNote(note.id, realSessionId)
      draftRepository.saveCheckpoint(
        existingNoteInput(
          draftId = validOldDraftId,
          noteId = note.id,
          sessionId = realSessionId,
          title = "Rascunho real",
          baseRevision = note.revision,
          baseNoteUpdatedAt = note.updatedAt,
          checkpointRevision = 1L,
        )
      )

      val attemptedNewDraftId1 = NoteRecoveryDraftKeys.forNewNote("nova-tentativa-1")
      try {
        draftRepository.replaceCheckpointWithNewNote(
          oldDraftId = validOldDraftId,
          expectedOldSessionId = "sessao-divergente-chamador",
          expectedOldTargetNoteId = note.id,
          newInput =
            newNoteInput(
              draftId = attemptedNewDraftId1,
              sessionId = "nova-tentativa-1",
              title = "Bloqueado por sessionId divergente no chamador",
            ),
        )
        fail("Esperava InvalidRecoveryDraftException para sessionId divergente")
      } catch (_: InvalidRecoveryDraftException) {
        assertNotNull(database.noteRecoveryDraftDao().getByDraftId(validOldDraftId))
        assertNull(database.noteRecoveryDraftDao().getByDraftId(attemptedNewDraftId1))
      }

      // Caso 2: chave (oldDraftId, expectedOldSessionId, expectedOldTargetNoteId) coerente entre parâmetros,
      // mas registro persistido no SQLite possui sessionId divergente da chave -> verificação interna da transação bloqueia.
      val dbMismatchSessionExpected = "sessao-esperada-chave"
      val dbMismatchDraftId =
        NoteRecoveryDraftKeys.forExistingNote(note.id, dbMismatchSessionExpected)
      database.noteRecoveryDraftDao().insert(
        NoteRecoveryDraftEntity(
          draftId = dbMismatchDraftId,
          targetNoteId = note.id,
          sessionId = "sessao-divergente-persistida",
          folderId = null,
          isPinned = false,
          title = "Persistido com sessionId divergente",
          contentJson = NoteContentCodec.encode(NoteContentDocument.empty()),
          paperStyle = NotePaperStyle.LINHAS.name,
          baseRevision = note.revision,
          checkpointRevision = 1L,
          baseNoteUpdatedAt = note.updatedAt,
          checkpointAt = 2000L,
        )
      )

      val attemptedNewDraftId2 = NoteRecoveryDraftKeys.forNewNote("nova-tentativa-2")
      try {
        draftRepository.replaceCheckpointWithNewNote(
          oldDraftId = dbMismatchDraftId,
          expectedOldSessionId = dbMismatchSessionExpected,
          expectedOldTargetNoteId = note.id,
          newInput =
            newNoteInput(
              draftId = attemptedNewDraftId2,
              sessionId = "nova-tentativa-2",
              title = "Bloqueado por sessionId divergente no banco",
            ),
        )
        fail("Esperava InvalidRecoveryDraftException para sessionId persistido divergente")
      } catch (_: InvalidRecoveryDraftException) {
        val persisted = database.noteRecoveryDraftDao().getByDraftId(dbMismatchDraftId)
        assertNotNull(persisted)
        assertEquals("sessao-divergente-persistida", persisted!!.sessionId)
        assertNull(database.noteRecoveryDraftDao().getByDraftId(attemptedNewDraftId2))
      }

      // Caso 3: registro persistido com draftId e sessionId coerentes, mas targetNoteId divergente no banco.
      val dbNoteMismatchSession = "sessao-nota-divergente-banco"
      val dbNoteMismatchDraftId =
        NoteRecoveryDraftKeys.forExistingNote(note.id, dbNoteMismatchSession)
      database.noteRecoveryDraftDao().insert(
        NoteRecoveryDraftEntity(
          draftId = dbNoteMismatchDraftId,
          targetNoteId = otherNote.id,
          sessionId = dbNoteMismatchSession,
          folderId = null,
          isPinned = false,
          title = "Persistido apontando para outra nota",
          contentJson = NoteContentCodec.encode(NoteContentDocument.empty()),
          paperStyle = NotePaperStyle.LINHAS.name,
          baseRevision = otherNote.revision,
          checkpointRevision = 1L,
          baseNoteUpdatedAt = otherNote.updatedAt,
          checkpointAt = 3000L,
        )
      )

      val attemptedNewDraftId3 = NoteRecoveryDraftKeys.forNewNote("nova-tentativa-3")
      try {
        draftRepository.replaceCheckpointWithNewNote(
          oldDraftId = dbNoteMismatchDraftId,
          expectedOldSessionId = dbNoteMismatchSession,
          expectedOldTargetNoteId = note.id,
          newInput =
            newNoteInput(
              draftId = attemptedNewDraftId3,
              sessionId = "nova-tentativa-3",
              title = "Bloqueado por targetNoteId divergente no banco",
            ),
        )
        fail("Esperava InvalidRecoveryDraftException para targetNoteId persistido divergente")
      } catch (_: InvalidRecoveryDraftException) {
        val persisted = database.noteRecoveryDraftDao().getByDraftId(dbNoteMismatchDraftId)
        assertNotNull(persisted)
        assertEquals(otherNote.id, persisted!!.targetNoteId)
        assertNull(database.noteRecoveryDraftDao().getByDraftId(attemptedNewDraftId3))
      }
    }

  @Test
  fun `rolls back entire Room transaction when repository throws InvalidRecoveryDraftException after inserting new draft`() =
    runTest {
      val note = noteRepository.createNote(title = "Nota origem rollback lógico")
      val oldSessionId = "old-session-ignore-delete"
      val oldDraftId = NoteRecoveryDraftKeys.forExistingNote(note.id, oldSessionId)
      draftRepository.saveCheckpoint(
        existingNoteInput(
          draftId = oldDraftId,
          noteId = note.id,
          sessionId = oldSessionId,
          title = "Checkpoint antigo protegido",
          content =
            NoteContentDocument(
              blocks = listOf(ParagraphBlock(id = "p-old", text = "Conteúdo antigo preservado"))
            ),
          paperStyle = NotePaperStyle.PONTILHADO,
          baseRevision = note.revision,
          baseNoteUpdatedAt = note.updatedAt,
          checkpointRevision = 6L,
        )
      )
      val oldBefore = database.noteRecoveryDraftDao().getByDraftId(oldDraftId)!!

      // Segundo checkpoint da mesma nota para comprovar isolamento transacional integral.
      val siblingSessionId = "sibling-session-ignore-delete"
      val siblingDraftId = NoteRecoveryDraftKeys.forExistingNote(note.id, siblingSessionId)
      draftRepository.saveCheckpoint(
        existingNoteInput(
          draftId = siblingDraftId,
          noteId = note.id,
          sessionId = siblingSessionId,
          title = "Irmão intacto",
          baseRevision = note.revision,
          baseNoteUpdatedAt = note.updatedAt,
          checkpointRevision = 1L,
        )
      )

      // Trigger BEFORE DELETE com RAISE(IGNORE): o DELETE executa sem lançar SQLiteException,
      // mas afeta 0 linhas. Assim, dentro de withTransaction, draftDao.insert(newEntity) conclui
      // e a própria implementação de NoteRecoveryDraftRepositoryImpl lança InvalidRecoveryDraftException.
      val writableDb = database.openHelper.writableDatabase
      writableDb.execSQL(
        """
        CREATE TRIGGER ignore_delete_old_draft
        BEFORE DELETE ON note_recovery_drafts
        WHEN OLD.draft_id = '$oldDraftId'
        BEGIN
          SELECT RAISE(IGNORE);
        END;
        """.trimIndent()
      )

      val newSessionId = "new-session-ignore-delete"
      val newDraftId = NoteRecoveryDraftKeys.forNewNote(newSessionId)
      val replacementInput =
        newNoteInput(
          draftId = newDraftId,
          sessionId = newSessionId,
          title = "Novo checkpoint que deve sofrer rollback",
          content =
            NoteContentDocument(
              blocks = listOf(ParagraphBlock(id = "p-new", text = "Texto novo não commitado"))
            ),
          paperStyle = NotePaperStyle.FOREST_MIST,
          initialPaperStyle = NotePaperStyle.FOREST_MIST,
          checkpointRevision = 1L,
        )

      // DAO observador pass-through sobre o DAO real do Room para comprovar empiricamente que:
      // (a) insert(newEntity) executa e fica visível dentro da transação antes do DELETE, e
      // (b) deleteByDraftId(oldDraftId) realmente retorna 0 linhas afetadas sob RAISE(IGNORE).
      val realDao = database.noteRecoveryDraftDao()
      val insertedDraftIds = mutableListOf<String>()
      var sawNewDraftBeforeDeleteInTx = false
      val deleteRowCounts = mutableListOf<Pair<String, Int>>()
      val observedDao =
        object : com.example.data.local.dao.NoteRecoveryDraftDao {
          override fun observeAllCheckpoints() = realDao.observeAllCheckpoints()

          override suspend fun getAllCheckpoints() = realDao.getAllCheckpoints()

          override suspend fun getByDraftId(draftId: String) = realDao.getByDraftId(draftId)

          override suspend fun getByTargetNoteId(noteId: Long) = realDao.getByTargetNoteId(noteId)

          override suspend fun getBySessionId(sessionId: String) = realDao.getBySessionId(sessionId)

          override suspend fun insert(entity: NoteRecoveryDraftEntity) {
            realDao.insert(entity)
            insertedDraftIds.add(entity.draftId)
          }

          override suspend fun update(entity: NoteRecoveryDraftEntity) = realDao.update(entity)

          override suspend fun deleteByDraftId(draftId: String): Int {
            sawNewDraftBeforeDeleteInTx = realDao.getByDraftId(newDraftId) != null
            val affected = realDao.deleteByDraftId(draftId)
            deleteRowCounts.add(draftId to affected)
            return affected
          }

          override suspend fun deleteByTargetNoteId(noteId: Long) = realDao.deleteByTargetNoteId(noteId)
        }
      val observedDraftRepository =
        NoteRecoveryDraftRepositoryImpl(
          database = database,
          draftDao = observedDao,
          dispatchers = testDispatchers,
          clock = testClock,
        )

      try {
        observedDraftRepository.replaceCheckpointWithNewNote(
          oldDraftId = oldDraftId,
          expectedOldSessionId = oldSessionId,
          expectedOldTargetNoteId = note.id,
          newInput = replacementInput,
        )
        fail(
          "Esperava InvalidRecoveryDraftException após deleteByDraftId retornar 0 linhas com RAISE(IGNORE)"
        )
      } catch (e: InvalidRecoveryDraftException) {
        assertEquals(listOf(newDraftId), insertedDraftIds)
        assertTrue(sawNewDraftBeforeDeleteInTx)
        assertEquals(listOf(oldDraftId to 0), deleteRowCounts)
        assertTrue(e.message?.contains("Falha ao excluir o rascunho anterior") == true)
        assertTrue(e.message?.contains(oldDraftId) == true)

        // O INSERT já ocorrera antes do DELETE; o rollback do Room deve desfazer o novo registro.
        assertNull(realDao.getByDraftId(newDraftId))
        assertNull(realDao.getBySessionId(newSessionId))

        // O rascunho antigo e o irmão permanecem íntegros e sem coexistência indevida.
        val oldAfter = realDao.getByDraftId(oldDraftId)
        assertNotNull(oldAfter)
        assertEquals(oldBefore, oldAfter)

        val siblingAfter = realDao.getByDraftId(siblingDraftId)
        assertNotNull(siblingAfter)
        assertEquals("Irmão intacto", siblingAfter!!.title)

        assertEquals(2, realDao.getAllCheckpoints().size)
      } finally {
        writableDb.execSQL("DROP TRIGGER IF EXISTS ignore_delete_old_draft")
      }

      // Após remoção do trigger, uma nova tentativa idêntica substitui normalmente sem resíduos.
      val recoveryResult =
        draftRepository.replaceCheckpointWithNewNote(
          oldDraftId = oldDraftId,
          expectedOldSessionId = oldSessionId,
          expectedOldTargetNoteId = note.id,
          newInput = replacementInput,
        )
      assertTrue(recoveryResult is ReplaceCheckpointWithNewNoteResult.Replaced)
      assertNull(database.noteRecoveryDraftDao().getByDraftId(oldDraftId))
      assertNotNull(database.noteRecoveryDraftDao().getByDraftId(newDraftId))
      assertNotNull(database.noteRecoveryDraftDao().getByDraftId(siblingDraftId))
      assertEquals(2, database.noteRecoveryDraftDao().getAllCheckpoints().size)
    }

  @Test
  fun `rolls back transaction cleanly when SQLite fails during insertion of newEntity`() =
    runTest {
      val note = noteRepository.createNote(title = "Nota falha no INSERT")
      val oldSessionId = "old-session-insert-fail"
      val oldDraftId = NoteRecoveryDraftKeys.forExistingNote(note.id, oldSessionId)
      draftRepository.saveCheckpoint(
        existingNoteInput(
          draftId = oldDraftId,
          noteId = note.id,
          sessionId = oldSessionId,
          title = "Antigo intacto no INSERT fail",
          baseRevision = note.revision,
          baseNoteUpdatedAt = note.updatedAt,
          checkpointRevision = 3L,
        )
      )

      val newSessionId = "new-session-insert-fail"
      val newDraftId = NoteRecoveryDraftKeys.forNewNote(newSessionId)
      val writableDb = database.openHelper.writableDatabase
      writableDb.execSQL(
        """
        CREATE TRIGGER fail_insert_new_draft
        BEFORE INSERT ON note_recovery_drafts
        WHEN NEW.draft_id = '$newDraftId'
        BEGIN
          SELECT RAISE(ABORT, 'Falha SQLite simulada ao inserir novo rascunho');
        END;
        """.trimIndent()
      )

      try {
        draftRepository.replaceCheckpointWithNewNote(
          oldDraftId = oldDraftId,
          expectedOldSessionId = oldSessionId,
          expectedOldTargetNoteId = note.id,
          newInput =
            newNoteInput(
              draftId = newDraftId,
              sessionId = newSessionId,
              title = "Falha no INSERT",
            ),
        )
        fail("Esperava SQLiteException no BEFORE INSERT")
      } catch (_: SQLiteException) {
        assertNull(database.noteRecoveryDraftDao().getByDraftId(newDraftId))
        val intactOld = database.noteRecoveryDraftDao().getByDraftId(oldDraftId)
        assertNotNull(intactOld)
        assertEquals("Antigo intacto no INSERT fail", intactOld!!.title)
        assertEquals(3L, intactOld.checkpointRevision)
        assertEquals(1, database.noteRecoveryDraftDao().getAllCheckpoints().size)
      } finally {
        writableDb.execSQL("DROP TRIGGER IF EXISTS fail_insert_new_draft")
      }
    }

  @Test
  fun `case B idempotency compares normalized content when caller repeats replacement`() = runTest {
    val note = noteRepository.createNote(title = "Nota normalização")
    val oldSessionId = "old-norm"
    val oldDraftId = NoteRecoveryDraftKeys.forExistingNote(note.id, oldSessionId)
    draftRepository.saveCheckpoint(
      existingNoteInput(
        draftId = oldDraftId,
        noteId = note.id,
        sessionId = oldSessionId,
        baseRevision = note.revision,
        baseNoteUpdatedAt = note.updatedAt,
      )
    )

    val newSessionId = "new-norm"
    val newDraftId = NoteRecoveryDraftKeys.forNewNote(newSessionId)
    val unnormalizedDoc =
      NoteContentDocument(
        blocks =
          listOf(
            ParagraphBlock(
              id = "p1",
              text = "abc",
              spans =
                listOf(
                  com.example.domain.model.FormatSpan(
                    start = -5,
                    end = 10,
                    style = com.example.domain.model.TextFormatStyle.BOLD,
                  )
                ),
            )
          )
      )
    val firstInput =
      newNoteInput(
        draftId = newDraftId,
        sessionId = newSessionId,
        title = "Normalizado",
        content = unnormalizedDoc,
      )

    val first =
      draftRepository.replaceCheckpointWithNewNote(
        oldDraftId = oldDraftId,
        expectedOldSessionId = oldSessionId,
        expectedOldTargetNoteId = note.id,
        newInput = firstInput,
      )
    assertTrue(first is ReplaceCheckpointWithNewNoteResult.Replaced)

    val normalizedCopyInput =
      firstInput.copy(content = NoteContentPolicy.normalizeDocument(unnormalizedDoc))
    val second =
      draftRepository.replaceCheckpointWithNewNote(
        oldDraftId = oldDraftId,
        expectedOldSessionId = oldSessionId,
        expectedOldTargetNoteId = note.id,
        newInput = normalizedCopyInput,
      )
    assertTrue(second is ReplaceCheckpointWithNewNoteResult.AlreadyReplaced)
    assertEquals(1, database.noteRecoveryDraftDao().getAllCheckpoints().size)
  }

  private fun newNoteInput(
    draftId: String,
    sessionId: String,
    folderId: Long? = null,
    initialFolderId: Long? = folderId,
    fallbackSourceFolderId: Long? = null,
    isPinned: Boolean = false,
    title: String = "Nova nota",
    content: NoteContentDocument = NoteContentDocument.empty(),
    paperStyle: NotePaperStyle = NotePaperStyle.LINHAS,
    initialPaperStyle: NotePaperStyle? = paperStyle,
    checkpointRevision: Long = 1L,
  ): NoteRecoveryDraftInput =
    NoteRecoveryDraftInput(
      draftId = draftId,
      targetNoteId = null,
      sessionId = sessionId,
      folderId = folderId,
      initialFolderId = initialFolderId,
      fallbackSourceFolderId = fallbackSourceFolderId,
      isPinned = isPinned,
      title = title,
      content = content,
      paperStyle = paperStyle,
      initialPaperStyle = initialPaperStyle,
      baseRevision = 0L,
      checkpointRevision = checkpointRevision,
      baseNoteUpdatedAt = null,
    )

  private fun existingNoteInput(
    draftId: String,
    noteId: Long,
    sessionId: String,
    folderId: Long? = null,
    fallbackSourceFolderId: Long? = null,
    isPinned: Boolean = false,
    title: String = "Nota existente",
    content: NoteContentDocument = NoteContentDocument.empty(),
    paperStyle: NotePaperStyle = NotePaperStyle.LINHAS,
    baseRevision: Long,
    baseNoteUpdatedAt: Long,
    checkpointRevision: Long = 1L,
  ): NoteRecoveryDraftInput =
    NoteRecoveryDraftInput(
      draftId = draftId,
      targetNoteId = noteId,
      sessionId = sessionId,
      folderId = folderId,
      initialFolderId = null,
      fallbackSourceFolderId = fallbackSourceFolderId,
      isPinned = isPinned,
      title = title,
      content = content,
      paperStyle = paperStyle,
      initialPaperStyle = null,
      baseRevision = baseRevision,
      checkpointRevision = checkpointRevision,
      baseNoteUpdatedAt = baseNoteUpdatedAt,
    )
}
