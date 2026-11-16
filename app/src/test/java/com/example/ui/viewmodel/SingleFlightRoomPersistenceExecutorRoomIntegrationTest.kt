package com.example.ui.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.coroutines.DispatcherProvider
import com.example.core.time.ClockProvider
import com.example.data.local.database.AnoteVoceDatabase
import com.example.data.repository.NoteRecoveryDraftRepositoryImpl
import com.example.data.repository.NoteRepositoryImpl
import com.example.domain.editor.NoteEditorSessionIdentity
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.NoteRecoveryDraftInput
import com.example.domain.model.NoteRecoveryDraftKeys
import com.example.domain.model.ParagraphBlock
import com.example.domain.repository.InvalidRecoveryDraftException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class SingleFlightRoomPersistenceExecutorRoomIntegrationTest {

  private lateinit var database: AnoteVoceDatabase
  private lateinit var noteRepository: NoteRepositoryImpl
  private lateinit var draftRepository: NoteRecoveryDraftRepositoryImpl

  private var fakeTimeMillis = 1_700_300_000_000L

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
  fun replaceCheckpointWithNewNote_realRoomDeterministicRejection_clearsPendingPreservesOldIdentityReconcilesAndResumesSaves() =
    runTest {
      val targetNote = noteRepository.createNote(title = "Nota alvo")
      val blockerNote = noteRepository.createNote(title = "Nota bloqueadora")

      val oldSessionId = "old-room-int"
      val oldIdentity =
        NoteEditorSessionIdentity(
          draftId = NoteRecoveryDraftKeys.forExistingNote(targetNote.id, oldSessionId),
          sessionId = oldSessionId,
          targetNoteId = targetNote.id,
        )

      val executor =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepository,
          noteRepository = noteRepository,
          initialIdentity = oldIdentity,
          initialPresence = CheckpointPresence.ABSENT,
        )

      val initialSave =
        executor.saveCheckpoint(
          CheckpointSavePayload.fromIdentity(
            identity = oldIdentity,
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Rascunho v1 no Room",
            content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Texto inicial"))),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = targetNote.revision,
            baseNoteUpdatedAt = targetNote.updatedAt,
          )
        )
      assertTrue(initialSave is SaveCheckpointExecutionResult.Saved)
      assertEquals(1L, (initialSave as SaveCheckpointExecutionResult.Saved).allocatedRevision)
      assertEquals(CheckpointPresence.PRESENT, initialSave.snapshot.checkpointPresence)

      // Semeia outro checkpoint no Room real ocupando o mesmo sessionId do destino pretendido
      // com draftId distinto, provocando rejeição transacional determinística (Caso C).
      val collidingSessionId = "colliding-room-int"
      val blockerDraftId = NoteRecoveryDraftKeys.forExistingNote(blockerNote.id, collidingSessionId)
      draftRepository.saveCheckpoint(
        NoteRecoveryDraftInput(
          draftId = blockerDraftId,
          targetNoteId = blockerNote.id,
          sessionId = collidingSessionId,
          folderId = null,
          initialFolderId = null,
          fallbackSourceFolderId = null,
          isPinned = false,
          title = "Checkpoint bloqueador",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "pb", text = "Bloqueio"))),
          paperStyle = NotePaperStyle.LINHAS,
          initialPaperStyle = null,
          baseRevision = blockerNote.revision,
          checkpointRevision = 1L,
          baseNoteUpdatedAt = blockerNote.updatedAt,
        )
      )

      val newIdentity =
        NoteEditorSessionIdentity(
          draftId = NoteRecoveryDraftKeys.forNewNote(collidingSessionId),
          sessionId = collidingSessionId,
          targetNoteId = null,
        )
      val newPayload =
        CheckpointSavePayload.fromIdentity(
          identity = newIdentity,
          folderId = null,
          initialFolderId = null,
          fallbackSourceFolderId = null,
          isPinned = false,
          title = "Conversão rejeitada no Room",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p2", text = "Novo texto"))),
          paperStyle = NotePaperStyle.PONTILHADO,
          initialPaperStyle = NotePaperStyle.PONTILHADO,
          baseRevision = 0L,
          baseNoteUpdatedAt = null,
        )

      val rejected =
        executor.replaceCheckpointWithNewNote(
          oldDraftId = oldIdentity.draftId,
          expectedOldSessionId = oldIdentity.sessionId,
          expectedOldTargetNoteId = targetNote.id,
          newPayload = newPayload,
          newDraftRevision = 1L,
        )

      assertTrue(rejected is ReplaceCheckpointExecutionResult.Failed)
      val failed = rejected as ReplaceCheckpointExecutionResult.Failed
      assertTrue(failed.cause is InvalidRecoveryDraftException)
      assertNull(failed.snapshot.pendingReplacement)
      assertFalse(failed.snapshot.hasPendingReplacement)
      assertFalse(failed.snapshot.hasInFlightOperation)
      assertEquals(oldIdentity.draftId, failed.snapshot.draftId)
      assertEquals(oldIdentity.sessionId, failed.snapshot.sessionId)
      assertEquals(targetNote.id, failed.snapshot.targetNoteId)
      assertEquals(CheckpointPresence.UNKNOWN, failed.snapshot.checkpointPresence)
      assertFalse(failed.snapshot.isSyncConfirmationAllowed)

      // Confirma no Room real que o checkpoint antigo permaneceu intacto e o novo não foi gravado.
      val persistedOld = database.noteRecoveryDraftDao().getByDraftId(oldIdentity.draftId)
      assertNotNull(persistedOld)
      assertEquals("Rascunho v1 no Room", persistedOld!!.title)
      assertEquals(1L, persistedOld.checkpointRevision)
      assertNull(database.noteRecoveryDraftDao().getByDraftId(newIdentity.draftId))
      assertNotNull(database.noteRecoveryDraftDao().getByDraftId(blockerDraftId))

      // Operações incompatíveis com a identidade preservada (ex.: destino não efetivado) falham sem tocar o banco.
      try {
        executor.deleteCheckpoint(newIdentity.draftId)
        fail("Esperava IllegalArgumentException para draftId não efetivado")
      } catch (_: IllegalArgumentException) {
        assertNotNull(database.noteRecoveryDraftDao().getByDraftId(oldIdentity.draftId))
      }

      // Reconciliação sobre a identidade antiga deve restaurar PRESENT e permitir confirmação de sync.
      val reconciled = executor.reconcileCheckpointPresence(oldIdentity.draftId)
      assertTrue(reconciled is ReconcileCheckpointExecutionResult.Reconciled)
      val reconciledResult = reconciled as ReconcileCheckpointExecutionResult.Reconciled
      assertNotNull(reconciledResult.persistedDraft)
      assertEquals(CheckpointPresence.PRESENT, reconciledResult.snapshot.checkpointPresence)
      assertEquals(1L, reconciledResult.snapshot.confirmedCheckpointRevision)
      assertFalse(reconciledResult.snapshot.hasPendingReplacement)
      assertFalse(reconciledResult.snapshot.hasInFlightOperation)
      assertTrue(reconciledResult.snapshot.isSyncConfirmationAllowed)

      // Retomada da gravação na identidade antiga persiste normalmente no Room real.
      val resumedSave =
        executor.saveCheckpoint(
          CheckpointSavePayload.fromIdentity(
            identity = oldIdentity,
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = true,
            title = "Rascunho v2 retomado no Room",
            content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Texto atualizado"))),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = targetNote.revision,
            baseNoteUpdatedAt = targetNote.updatedAt,
          )
        )
      assertTrue(resumedSave is SaveCheckpointExecutionResult.Saved)
      val savedAfterRecovery = resumedSave as SaveCheckpointExecutionResult.Saved
      assertEquals(2L, savedAfterRecovery.allocatedRevision)
      assertEquals(CheckpointPresence.PRESENT, savedAfterRecovery.snapshot.checkpointPresence)
      assertEquals(2L, savedAfterRecovery.snapshot.confirmedCheckpointRevision)
      assertTrue(savedAfterRecovery.snapshot.isSyncConfirmationAllowed)

      val updatedInRoom = database.noteRecoveryDraftDao().getByDraftId(oldIdentity.draftId)
      assertNotNull(updatedInRoom)
      assertEquals("Rascunho v2 retomado no Room", updatedInRoom!!.title)
      assertEquals(2L, updatedInRoom.checkpointRevision)
      assertTrue(updatedInRoom.isPinned)
    }

  @Test
  fun replaceCheckpointWithNewNote_realRoomPostInsertInvalidRecoveryDraftException_rollsBackClearsPendingReconcilesAndResumesSaves() =
    runTest {
      val targetNote = noteRepository.createNote(title = "Nota alvo post-insert rollback")
      val oldSessionId = "old-room-post-insert"
      val oldIdentity =
        NoteEditorSessionIdentity(
          draftId = NoteRecoveryDraftKeys.forExistingNote(targetNote.id, oldSessionId),
          sessionId = oldSessionId,
          targetNoteId = targetNote.id,
        )

      val executor =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepository,
          noteRepository = noteRepository,
          initialIdentity = oldIdentity,
          initialPresence = CheckpointPresence.ABSENT,
        )

      val initialSave =
        executor.saveCheckpoint(
          CheckpointSavePayload.fromIdentity(
            identity = oldIdentity,
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Rascunho antigo v1 post-insert",
            content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Texto preservado"))),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = targetNote.revision,
            baseNoteUpdatedAt = targetNote.updatedAt,
          )
        )
      assertTrue(initialSave is SaveCheckpointExecutionResult.Saved)
      assertEquals(1L, (initialSave as SaveCheckpointExecutionResult.Saved).allocatedRevision)

      val newSessionId = "new-room-post-insert"
      val newIdentity =
        NoteEditorSessionIdentity(
          draftId = NoteRecoveryDraftKeys.forNewNote(newSessionId),
          sessionId = newSessionId,
          targetNoteId = null,
        )
      val newPayload =
        CheckpointSavePayload.fromIdentity(
          identity = newIdentity,
          folderId = null,
          initialFolderId = null,
          fallbackSourceFolderId = null,
          isPinned = true,
          title = "Nova nota que sofre rollback post-insert",
          content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p2", text = "Texto novo"))),
          paperStyle = NotePaperStyle.TECH_DARK,
          initialPaperStyle = NotePaperStyle.TECH_DARK,
          baseRevision = 0L,
          baseNoteUpdatedAt = null,
        )

      // Trigger BEFORE DELETE com RAISE(IGNORE): permite insert(newEntity) dentro da transação
      // e faz deleteByDraftId(oldDraftId) retornar 0, levando o repositório a lançar InvalidRecoveryDraftException.
      val writableDb = database.openHelper.writableDatabase
      writableDb.execSQL(
        """
        CREATE TRIGGER ignore_delete_room_integration
        BEFORE DELETE ON note_recovery_drafts
        WHEN OLD.draft_id = '${oldIdentity.draftId}'
        BEGIN
          SELECT RAISE(IGNORE);
        END;
        """.trimIndent()
      )

      try {
        val rejected =
          executor.replaceCheckpointWithNewNote(
            oldDraftId = oldIdentity.draftId,
            expectedOldSessionId = oldIdentity.sessionId,
            expectedOldTargetNoteId = targetNote.id,
            newPayload = newPayload,
            newDraftRevision = 1L,
          )

        assertTrue(rejected is ReplaceCheckpointExecutionResult.Failed)
        val failed = rejected as ReplaceCheckpointExecutionResult.Failed
        assertTrue(failed.cause is InvalidRecoveryDraftException)
        assertNull(failed.snapshot.pendingReplacement)
        assertFalse(failed.snapshot.hasPendingReplacement)
        assertFalse(failed.snapshot.hasInFlightOperation)
        assertEquals(oldIdentity.draftId, failed.snapshot.draftId)
        assertEquals(oldIdentity.sessionId, failed.snapshot.sessionId)
        assertEquals(targetNote.id, failed.snapshot.targetNoteId)
        assertEquals(CheckpointPresence.UNKNOWN, failed.snapshot.checkpointPresence)
        assertFalse(failed.snapshot.isSyncConfirmationAllowed)

        // Rollback integral no Room real: novo registro desfeito e antigo preservado.
        assertNull(database.noteRecoveryDraftDao().getByDraftId(newIdentity.draftId))
        val intactOld = database.noteRecoveryDraftDao().getByDraftId(oldIdentity.draftId)
        assertNotNull(intactOld)
        assertEquals("Rascunho antigo v1 post-insert", intactOld!!.title)
        assertEquals(1L, intactOld.checkpointRevision)
        assertEquals(1, database.noteRecoveryDraftDao().getAllCheckpoints().size)
      } finally {
        writableDb.execSQL("DROP TRIGGER IF EXISTS ignore_delete_room_integration")
      }

      // Enquanto UNKNOWN, operações incompatíveis (identidade nova ou createNote na identidade antiga) são rejeitadas sem mutar banco.
      try {
        executor.saveCheckpoint(
          CheckpointSavePayload.fromIdentity(
            identity = newIdentity,
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Incompatível com identidade antiga",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = 0L,
            baseNoteUpdatedAt = null,
          )
        )
        fail("Esperava IllegalArgumentException para payload com newIdentity após rejeição")
      } catch (_: IllegalArgumentException) {
        assertNull(database.noteRecoveryDraftDao().getByDraftId(newIdentity.draftId))
        assertFalse(executor.snapshot().isSyncConfirmationAllowed)
      }

      try {
        executor.consolidateCreateNote(
          title = "Incompatível com nota existente",
          content = NoteContentDocument.empty(),
          folderId = null,
          isPinned = false,
          paperStyle = NotePaperStyle.LINHAS,
        )
        fail("Esperava IllegalArgumentException para consolidateCreateNote em sessão com targetNoteId != null")
      } catch (_: IllegalArgumentException) {
        assertFalse(executor.snapshot().isSyncConfirmationAllowed)
      }

      // Reconciliação restabelece estado real do banco (PRESENT) e confirmação de sync.
      val reconciled = executor.reconcileCheckpointPresence(oldIdentity.draftId)
      assertTrue(reconciled is ReconcileCheckpointExecutionResult.Reconciled)
      val reconciledSnap = reconciled.snapshot
      assertEquals(CheckpointPresence.PRESENT, reconciledSnap.checkpointPresence)
      assertEquals(1L, reconciledSnap.confirmedCheckpointRevision)
      assertEquals(1L, reconciledSnap.highestAllocatedRevision)
      assertFalse(reconciledSnap.hasPendingReplacement)
      assertTrue(reconciledSnap.isSyncConfirmationAllowed)

      // Retoma gravações na identidade antiga com revisão monotônica e sem duplicar checkpoints.
      val resumedSave =
        executor.saveCheckpoint(
          CheckpointSavePayload.fromIdentity(
            identity = oldIdentity,
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Rascunho v2 após rollback post-insert",
            content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Texto retomado"))),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = targetNote.revision,
            baseNoteUpdatedAt = targetNote.updatedAt,
          )
        )
      assertTrue(resumedSave is SaveCheckpointExecutionResult.Saved)
      val saved = resumedSave as SaveCheckpointExecutionResult.Saved
      assertEquals(2L, saved.allocatedRevision)
      assertEquals(CheckpointPresence.PRESENT, saved.snapshot.checkpointPresence)
      assertEquals(2L, saved.snapshot.confirmedCheckpointRevision)
      assertTrue(saved.snapshot.isSyncConfirmationAllowed)

      assertNull(database.noteRecoveryDraftDao().getByDraftId(newIdentity.draftId))
      assertEquals(1, database.noteRecoveryDraftDao().getAllCheckpoints().size)
      val updatedOld = database.noteRecoveryDraftDao().getByDraftId(oldIdentity.draftId)!!
      assertEquals("Rascunho v2 após rollback post-insert", updatedOld.title)
      assertEquals(2L, updatedOld.checkpointRevision)
    }

  @Test
  fun replaceCheckpointWithNewNote_realRoomDivergentExistingDraftCaseD_preservesBothReconcilesAndResumesOldIdentity() =
    runTest {
      val targetNote = noteRepository.createNote(title = "Nota alvo Caso D")
      val oldSessionId = "old-room-case-d"
      val oldIdentity =
        NoteEditorSessionIdentity(
          draftId = NoteRecoveryDraftKeys.forExistingNote(targetNote.id, oldSessionId),
          sessionId = oldSessionId,
          targetNoteId = targetNote.id,
        )

      val executor =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepository,
          noteRepository = noteRepository,
          initialIdentity = oldIdentity,
          initialPresence = CheckpointPresence.ABSENT,
        )

      assertTrue(
        executor.saveCheckpoint(
          CheckpointSavePayload.fromIdentity(
            identity = oldIdentity,
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Antigo Caso D v1",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = targetNote.revision,
            baseNoteUpdatedAt = targetNote.updatedAt,
          )
        ) is SaveCheckpointExecutionResult.Saved
      )

      val newSessionId = "new-room-case-d"
      val newIdentity =
        NoteEditorSessionIdentity(
          draftId = NoteRecoveryDraftKeys.forNewNote(newSessionId),
          sessionId = newSessionId,
          targetNoteId = null,
        )
      draftRepository.saveCheckpoint(
        NoteRecoveryDraftInput(
          draftId = newIdentity.draftId,
          targetNoteId = null,
          sessionId = newSessionId,
          folderId = null,
          initialFolderId = null,
          fallbackSourceFolderId = null,
          isPinned = false,
          title = "Conteúdo pré-existente divergente",
          content = NoteContentDocument.empty(),
          paperStyle = NotePaperStyle.LINHAS,
          initialPaperStyle = NotePaperStyle.LINHAS,
          baseRevision = 0L,
          checkpointRevision = 1L,
          baseNoteUpdatedAt = null,
        )
      )

      val newPayload =
        CheckpointSavePayload.fromIdentity(
          identity = newIdentity,
          folderId = null,
          initialFolderId = null,
          fallbackSourceFolderId = null,
          isPinned = false,
          title = "Payload divergente do executor",
          content = NoteContentDocument.empty(),
          paperStyle = NotePaperStyle.LINHAS,
          initialPaperStyle = NotePaperStyle.LINHAS,
          baseRevision = 0L,
          baseNoteUpdatedAt = null,
        )

      val failed =
        executor.replaceCheckpointWithNewNote(
          oldDraftId = oldIdentity.draftId,
          expectedOldSessionId = oldIdentity.sessionId,
          expectedOldTargetNoteId = targetNote.id,
          newPayload = newPayload,
          newDraftRevision = 1L,
        ) as ReplaceCheckpointExecutionResult.Failed

      assertTrue(failed.cause is InvalidRecoveryDraftException)
      assertNull(failed.snapshot.pendingReplacement)
      assertFalse(failed.snapshot.hasInFlightOperation)
      assertEquals(oldIdentity.draftId, failed.snapshot.draftId)
      assertEquals(CheckpointPresence.UNKNOWN, failed.snapshot.checkpointPresence)
      assertFalse(failed.snapshot.isSyncConfirmationAllowed)

      assertEquals("Antigo Caso D v1", database.noteRecoveryDraftDao().getByDraftId(oldIdentity.draftId)?.title)
      assertEquals(
        "Conteúdo pré-existente divergente",
        database.noteRecoveryDraftDao().getByDraftId(newIdentity.draftId)?.title,
      )

      val reconciled = executor.reconcileCheckpointPresence(oldIdentity.draftId)
      assertTrue(reconciled is ReconcileCheckpointExecutionResult.Reconciled)
      assertEquals(CheckpointPresence.PRESENT, reconciled.snapshot.checkpointPresence)
      assertTrue(reconciled.snapshot.isSyncConfirmationAllowed)

      val resumed =
        executor.saveCheckpoint(
          CheckpointSavePayload.fromIdentity(
            identity = oldIdentity,
            folderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Antigo Caso D v2",
            content = NoteContentDocument.empty(),
            paperStyle = NotePaperStyle.LINHAS,
            baseRevision = targetNote.revision,
            baseNoteUpdatedAt = targetNote.updatedAt,
          )
        ) as SaveCheckpointExecutionResult.Saved
      assertEquals(2L, resumed.allocatedRevision)
      assertEquals("Antigo Caso D v2", database.noteRecoveryDraftDao().getByDraftId(oldIdentity.draftId)?.title)
    }
}

