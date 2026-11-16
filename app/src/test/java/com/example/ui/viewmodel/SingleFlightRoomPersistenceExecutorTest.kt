package com.example.ui.viewmodel

import com.example.domain.editor.NoteEditorSessionIdentity
import com.example.domain.model.Note
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NoteContentState
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.NoteRecoveryDraft
import com.example.domain.model.NoteRecoveryDraftInput
import com.example.domain.model.NoteRecoveryDraftKeys
import com.example.domain.model.ParagraphBlock
import com.example.domain.model.ReplaceCheckpointWithNewNoteResult
import com.example.domain.model.SaveCheckpointResult
import com.example.domain.policy.NoteContentPolicy
import com.example.domain.repository.InvalidRecoveryDraftException
import com.example.domain.repository.NoteConflictException
import com.example.domain.repository.NoteRecoveryDraftRepository
import com.example.domain.repository.NoteRepository
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleFlightRoomPersistenceExecutorTest {

  private fun newNoteIdentity(sessionId: String = "s1") =
    NoteEditorSessionIdentity(
      draftId = NoteRecoveryDraftKeys.forNewNote(sessionId),
      sessionId = sessionId,
      targetNoteId = null,
    )

  private fun existingNoteIdentity(noteId: Long = 10L, sessionId: String = "s10") =
    NoteEditorSessionIdentity(
      draftId = NoteRecoveryDraftKeys.forExistingNote(noteId, sessionId),
      sessionId = sessionId,
      targetNoteId = noteId,
    )

  private fun sampleDocument(text: String): NoteContentDocument =
    NoteContentDocument(blocks = listOf(ParagraphBlock(id = "b1", text = text)))

  private fun payloadFor(
    identity: NoteEditorSessionIdentity,
    title: String = "Titulo",
    text: String = "Texto",
    baseRevision: Long = if (identity.targetNoteId == null) 0L else 3L,
    baseNoteUpdatedAt: Long? = if (identity.targetNoteId == null) null else 3_000L,
  ): CheckpointSavePayload =
    CheckpointSavePayload.fromIdentity(
      identity = identity,
      folderId = null,
      initialFolderId = null,
      fallbackSourceFolderId = null,
      isPinned = false,
      title = title,
      content = sampleDocument(text),
      paperStyle = NotePaperStyle.LINHAS,
      initialPaperStyle = NotePaperStyle.LINHAS,
      baseRevision = baseRevision,
      baseNoteUpdatedAt = baseNoteUpdatedAt,
    )

  @Test
  fun saveCheckpoint_initialAbsent_allocatesMonotonicRevisionsAndUpdatesPresenceToPresent() =
    runBlocking {
      val draftRepo = FakeDraftRepository()
      val noteRepo = FakeNoteRepository()
      val identity = newNoteIdentity("mono")
      val executor =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepo,
          noteRepository = noteRepo,
          initialIdentity = identity,
          initialPresence = CheckpointPresence.ABSENT,
        )

      val r1 = executor.saveCheckpoint(payloadFor(identity, title = "v1"))
      assertTrue(r1 is SaveCheckpointExecutionResult.Saved)
      val s1 = r1 as SaveCheckpointExecutionResult.Saved
      assertEquals(1L, s1.allocatedRevision)
      assertEquals(CheckpointPresence.PRESENT, s1.snapshot.checkpointPresence)
      assertEquals(1L, s1.snapshot.confirmedCheckpointRevision)
      assertEquals(1L, s1.snapshot.highestAllocatedRevision)
      assertTrue(s1.snapshot.isSyncConfirmationAllowed)

      val r2 = executor.saveCheckpoint(payloadFor(identity, title = "v2"))
      assertTrue(r2 is SaveCheckpointExecutionResult.Saved)
      val s2 = r2 as SaveCheckpointExecutionResult.Saved
      assertEquals(2L, s2.allocatedRevision)
      assertEquals(2L, s2.snapshot.confirmedCheckpointRevision)
      assertEquals(2L, s2.snapshot.highestAllocatedRevision)
      assertEquals(listOf(1L, 2L), draftRepo.savedRevisions)
    }

  @Test
  fun concurrentOperations_serializeInStrictFifoOrderWithMaxSingleInFlightCall() = runBlocking {
    val draftRepo = FakeDraftRepository()
    val noteRepo = FakeNoteRepository()
    val identity = newNoteIdentity("fifo")
    val executor =
      SingleFlightRoomPersistenceExecutor(
        draftRepository = draftRepo,
        noteRepository = noteRepo,
        initialIdentity = identity,
      )

    val op1Entered = CompletableDeferred<Unit>()
    val op1Release = CompletableDeferred<Unit>()
    draftRepo.onSaveHook = { input ->
      if (input.title == "op1") {
        op1Entered.complete(Unit)
        op1Release.await()
      }
    }

    val job1 =
      async(Dispatchers.Default) {
        executor.saveCheckpoint(payloadFor(identity, title = "op1"))
      }

    withTimeout(5_000) { op1Entered.await() }
    assertTrue(executor.snapshot().hasInFlightOperation)
    assertFalse(executor.snapshot().isSyncConfirmationAllowed)

    // UNDISPATCHED garante registro determinístico em FIFO no mutex antes de retornar.
    val job2 =
      async(start = CoroutineStart.UNDISPATCHED) {
        executor.saveCheckpoint(payloadFor(identity, title = "op2"))
      }
    val job3 =
      async(start = CoroutineStart.UNDISPATCHED) {
        executor.deleteCheckpoint(identity.draftId)
      }

    // Garante que job2 e job3 aguardam antes de tocar o repositório enquanto op1 está em voo.
    assertEquals(1, draftRepo.maxConcurrentCalls.get())
    assertEquals(listOf("saveCheckpoint:op1"), draftRepo.callLog)

    op1Release.complete(Unit)

    val res1 = withTimeout(5_000) { job1.await() }
    val res2 = withTimeout(5_000) { job2.await() }
    val res3 = withTimeout(5_000) { job3.await() }

    assertTrue(res1 is SaveCheckpointExecutionResult.Saved)
    assertTrue(res2 is SaveCheckpointExecutionResult.Saved)
    assertTrue(res3 is DeleteCheckpointExecutionResult.Deleted)
    assertEquals(1, draftRepo.maxConcurrentCalls.get())
    assertEquals(
      listOf(
        "saveCheckpoint:op1",
        "saveCheckpoint:op2",
        "deleteCheckpoint:${identity.draftId}",
      ),
      draftRepo.callLog,
    )
    assertFalse(executor.snapshot().hasInFlightOperation)
    assertEquals(CheckpointPresence.ABSENT, executor.snapshot().checkpointPresence)
  }

  @Test
  fun saveCheckpoint_failureMarksPresenceUnknownAndKeepsConfirmedRevisionWithoutReusingAttemptedRevision() =
    runBlocking {
      val draftRepo = FakeDraftRepository()
      val noteRepo = FakeNoteRepository()
      val identity = newNoteIdentity("fail-save")
      val executor =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepo,
          noteRepository = noteRepo,
          initialIdentity = identity,
        )

      assertTrue(executor.saveCheckpoint(payloadFor(identity, title = "v1")) is SaveCheckpointExecutionResult.Saved)

      draftRepo.onSaveHook = { throw IOException("disk busy") }
      val failed = executor.saveCheckpoint(payloadFor(identity, title = "v2"))
      assertTrue(failed is SaveCheckpointExecutionResult.Failed)
      val snapAfterFail = failed.snapshot
      assertEquals(CheckpointPresence.UNKNOWN, snapAfterFail.checkpointPresence)
      assertEquals(1L, snapAfterFail.confirmedCheckpointRevision)
      assertEquals(2L, snapAfterFail.highestAllocatedRevision)
      assertFalse(snapAfterFail.isSyncConfirmationAllowed)

      // Recuperação seguinte consulta getCheckpointById e aloca revisão 3 (nunca reutiliza 2).
      draftRepo.onSaveHook = null
      val recovered = executor.saveCheckpoint(payloadFor(identity, title = "v3"))
      assertTrue(recovered is SaveCheckpointExecutionResult.Saved)
      val saved = recovered as SaveCheckpointExecutionResult.Saved
      assertEquals(3L, saved.allocatedRevision)
      assertEquals(3L, saved.snapshot.confirmedCheckpointRevision)
      assertEquals(CheckpointPresence.PRESENT, saved.snapshot.checkpointPresence)
      assertTrue(draftRepo.callLog.contains("getCheckpointById:${identity.draftId}"))
    }

  @Test
  fun saveCheckpoint_whenPresenceUnknownAndPersistedRevisionIsNewerThanAllocated_refusesToOverwriteAndReturnsIgnoredStaleRevision() =
    runBlocking {
      val draftRepo = FakeDraftRepository()
      val noteRepo = FakeNoteRepository()
      val identity = newNoteIdentity("unknown-newer")
      draftRepo.seedDraft(
        draftId = identity.draftId,
        sessionId = identity.sessionId,
        targetNoteId = null,
        title = "mais-recente-no-banco",
        checkpointRevision = 9L,
      )

      val executor =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepo,
          noteRepository = noteRepo,
          initialIdentity = identity,
          initialPresence = CheckpointPresence.UNKNOWN,
          initialConfirmedRevision = 1L,
          initialHighestAllocatedRevision = 2L,
        )

      val result = executor.saveCheckpoint(payloadFor(identity, title = "tentativa-antiga"))
      assertTrue(result is SaveCheckpointExecutionResult.IgnoredStaleRevision)
      val ignored = result as SaveCheckpointExecutionResult.IgnoredStaleRevision
      assertEquals(9L, ignored.persistedCheckpointRevision)
      assertEquals(CheckpointPresence.PRESENT, ignored.snapshot.checkpointPresence)
      assertEquals(9L, ignored.snapshot.confirmedCheckpointRevision)
      assertEquals(9L, ignored.snapshot.highestAllocatedRevision)
      // Confirma que saveCheckpoint do repositório NÃO foi chamado sobrescrevendo o registro 9.
      assertFalse(draftRepo.callLog.any { it.startsWith("saveCheckpoint:") })
      assertEquals("mais-recente-no-banco", draftRepo.getCheckpointById(identity.draftId)?.title)
    }

  @Test
  fun saveCheckpoint_whenRepositoryReturnsIgnoredStaleRevision_updatesPresenceAndRevisionMonotonically() =
    runBlocking {
      val draftRepo = FakeDraftRepository()
      val noteRepo = FakeNoteRepository()
      val identity = newNoteIdentity("repo-stale")
      draftRepo.seedDraft(
        draftId = identity.draftId,
        sessionId = identity.sessionId,
        targetNoteId = null,
        title = "db-rev-5",
        checkpointRevision = 5L,
      )

      val executor =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepo,
          noteRepository = noteRepo,
          initialIdentity = identity,
          initialPresence = CheckpointPresence.PRESENT,
          initialConfirmedRevision = 1L,
          initialHighestAllocatedRevision = 1L,
        )

      val result = executor.saveCheckpoint(payloadFor(identity, title = "v2"))
      assertTrue(result is SaveCheckpointExecutionResult.IgnoredStaleRevision)
      val stale = result as SaveCheckpointExecutionResult.IgnoredStaleRevision
      assertEquals(5L, stale.persistedCheckpointRevision)
      assertEquals(2L, stale.attemptedCheckpointRevision)
      assertEquals(5L, stale.snapshot.confirmedCheckpointRevision)
      assertEquals(5L, stale.snapshot.highestAllocatedRevision)
      assertEquals(CheckpointPresence.PRESENT, stale.snapshot.checkpointPresence)
    }

  @Test
  fun saveCheckpoint_whenHighestAllocatedIsMaxMinusTwo_allocatesMaxMinusOneThenRejectsMaxWithoutIo() =
    runBlocking {
      val draftRepo = FakeDraftRepository()
      val noteRepo = FakeNoteRepository()
      val identity = newNoteIdentity("max-minus-2")
      val maxMinusTwo = Long.MAX_VALUE - 2L
      val maxMinusOne = Long.MAX_VALUE - 1L
      val executor =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepo,
          noteRepository = noteRepo,
          initialIdentity = identity,
          initialPresence = CheckpointPresence.PRESENT,
          initialConfirmedRevision = maxMinusTwo,
          initialHighestAllocatedRevision = maxMinusTwo,
        )

      val first = executor.saveCheckpoint(payloadFor(identity, title = "ultima-valida"))
      assertTrue(first is SaveCheckpointExecutionResult.Saved)
      val saved = first as SaveCheckpointExecutionResult.Saved
      assertEquals(maxMinusOne, saved.allocatedRevision)
      assertEquals(maxMinusOne, saved.snapshot.confirmedCheckpointRevision)
      assertEquals(maxMinusOne, saved.snapshot.highestAllocatedRevision)
      assertEquals(listOf(maxMinusOne), draftRepo.savedRevisions)

      val beforeOverflow = executor.snapshot()
      val second = executor.saveCheckpoint(payloadFor(identity, title = "excede-max"))
      assertTrue(second is SaveCheckpointExecutionResult.RevisionOverflow)
      val overflow = second as SaveCheckpointExecutionResult.RevisionOverflow
      assertEquals(maxMinusOne, overflow.currentHighestAllocatedRevision)
      assertEquals(beforeOverflow, overflow.snapshot)
      assertEquals(1, draftRepo.callLog.size)
    }

  @Test
  fun saveCheckpoint_whenHighestAllocatedIsMaxMinusOne_evenWithUnknownPresence_rejectsBeforeAnyRepositoryIo() =
    runBlocking {
      val draftRepo = FakeDraftRepository()
      val noteRepo = FakeNoteRepository()
      val identity = newNoteIdentity("max-minus-1-unknown")
      val maxMinusOne = Long.MAX_VALUE - 1L
      val executor =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepo,
          noteRepository = noteRepo,
          initialIdentity = identity,
          initialPresence = CheckpointPresence.UNKNOWN,
          initialConfirmedRevision = 10L,
          initialHighestAllocatedRevision = maxMinusOne,
        )

      val before = executor.snapshot()
      val result = executor.saveCheckpoint(payloadFor(identity, title = "nao-toca-repo"))
      assertTrue(result is SaveCheckpointExecutionResult.RevisionOverflow)
      val overflow = result as SaveCheckpointExecutionResult.RevisionOverflow
      assertEquals(maxMinusOne, overflow.currentHighestAllocatedRevision)
      assertEquals(before, overflow.snapshot)
      assertEquals(CheckpointPresence.UNKNOWN, overflow.snapshot.checkpointPresence)
      assertFalse(overflow.snapshot.hasInFlightOperation)
      assertTrue(draftRepo.callLog.isEmpty())
    }

  @Test
  fun saveCheckpoint_whenRevisionAtLongMaxValue_returnsOverflowWithoutStateMutationOrIo() =
    runBlocking {
      val draftRepo = FakeDraftRepository()
      val noteRepo = FakeNoteRepository()
      val identity = newNoteIdentity("overflow")
      val executor =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepo,
          noteRepository = noteRepo,
          initialIdentity = identity,
          initialPresence = CheckpointPresence.PRESENT,
          initialConfirmedRevision = Long.MAX_VALUE,
          initialHighestAllocatedRevision = Long.MAX_VALUE,
        )

      val before = executor.snapshot()
      val result = executor.saveCheckpoint(payloadFor(identity, title = "nao-deve-gravar"))
      assertTrue(result is SaveCheckpointExecutionResult.RevisionOverflow)
      val overflow = result as SaveCheckpointExecutionResult.RevisionOverflow
      assertEquals(Long.MAX_VALUE, overflow.currentHighestAllocatedRevision)
      assertEquals(before, overflow.snapshot)
      assertTrue(draftRepo.callLog.isEmpty())
    }

  @Test
  fun executorConstructorAndPayload_rejectNegativeOrSaturatedRevisionsWithoutIo() {
    val draftRepo = FakeDraftRepository()
    val noteRepo = FakeNoteRepository()
    val identity = newNoteIdentity("invalid-rev")

    assertThrows(IllegalArgumentException::class.java) {
      SingleFlightRoomPersistenceExecutor(
        draftRepository = draftRepo,
        noteRepository = noteRepo,
        initialIdentity = identity,
        initialPresence = CheckpointPresence.UNKNOWN,
        initialConfirmedRevision = -1L,
        initialHighestAllocatedRevision = 0L,
      )
    }

    assertThrows(IllegalArgumentException::class.java) {
      SingleFlightRoomPersistenceExecutor(
        draftRepository = draftRepo,
        noteRepository = noteRepo,
        initialIdentity = identity,
        initialPresence = CheckpointPresence.ABSENT,
        initialConfirmedRevision = 0L,
        initialHighestAllocatedRevision = -5L,
      )
    }

    assertThrows(IllegalArgumentException::class.java) {
      SingleFlightRoomPersistenceExecutor(
        draftRepository = draftRepo,
        noteRepository = noteRepo,
        initialIdentity = identity,
        initialPresence = CheckpointPresence.PRESENT,
        initialConfirmedRevision = 0L,
      )
    }

    val payload = payloadFor(identity, title = "p")
    assertThrows(IllegalArgumentException::class.java) { payload.toDraftInput(0L) }
    assertThrows(IllegalArgumentException::class.java) { payload.toDraftInput(-1L) }
    assertThrows(IllegalArgumentException::class.java) { payload.toDraftInput(Long.MIN_VALUE) }
    assertThrows(IllegalArgumentException::class.java) { payload.toDraftInput(Long.MAX_VALUE) }
    assertTrue(draftRepo.callLog.isEmpty())
  }

  @Test
  fun deleteCheckpoint_callsOnlyDeleteByDraftIdAndNeverDeleteCheckpointsForNote() = runBlocking {
    val draftRepo = FakeDraftRepository()
    val noteRepo = FakeNoteRepository()
    val identity = existingNoteIdentity(noteId = 33L, sessionId = "s33")
    draftRepo.seedDraft(
      draftId = identity.draftId,
      sessionId = identity.sessionId,
      targetNoteId = 33L,
      title = "sessao-ativa",
      checkpointRevision = 2L,
    )
    val executor =
      SingleFlightRoomPersistenceExecutor(
        draftRepository = draftRepo,
        noteRepository = noteRepo,
        initialIdentity = identity,
        initialPresence = CheckpointPresence.PRESENT,
        initialConfirmedRevision = 2L,
      )

    val result = executor.deleteCheckpoint(identity.draftId)
    assertTrue(result is DeleteCheckpointExecutionResult.Deleted)
    val snap = result.snapshot
    assertEquals(CheckpointPresence.ABSENT, snap.checkpointPresence)
    assertEquals(0L, snap.confirmedCheckpointRevision)
    // highestAllocatedRevision permanece monotônico após exclusão.
    assertEquals(2L, snap.highestAllocatedRevision)
    assertTrue(snap.isSyncConfirmationAllowed)
    assertEquals(listOf("deleteCheckpoint:${identity.draftId}"), draftRepo.callLog)
    assertEquals(0, draftRepo.deleteCheckpointsForNoteCalls.get())
  }

  @Test
  fun deleteCheckpoint_rejectsForeignDraftIdWithoutCallingRepository() {
    runBlocking {
      val draftRepo = FakeDraftRepository()
      val noteRepo = FakeNoteRepository()
      val identity = existingNoteIdentity(noteId = 44L, sessionId = "s44")
      val executor =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepo,
          noteRepository = noteRepo,
          initialIdentity = identity,
          initialPresence = CheckpointPresence.PRESENT,
          initialConfirmedRevision = 1L,
        )

      var thrown: Throwable? = null
      try {
        executor.deleteCheckpoint("note_44_outra_sessao")
      } catch (e: Throwable) {
        thrown = e
      }
      assertTrue(thrown is IllegalArgumentException)
      assertTrue(draftRepo.callLog.isEmpty())
      assertEquals(CheckpointPresence.PRESENT, executor.snapshot().checkpointPresence)
    }
  }

  @Test
  fun deleteCheckpoint_failureMarksPresenceUnknownAndBlocksSyncConfirmation() = runBlocking {
    val draftRepo = FakeDraftRepository()
    val noteRepo = FakeNoteRepository()
    val identity = newNoteIdentity("del-fail")
    val executor =
      SingleFlightRoomPersistenceExecutor(
        draftRepository = draftRepo,
        noteRepository = noteRepo,
        initialIdentity = identity,
        initialPresence = CheckpointPresence.PRESENT,
        initialConfirmedRevision = 3L,
      )

    draftRepo.onDeleteHook = { throw IOException("delete failed") }
    val failed = executor.deleteCheckpoint(identity.draftId)
    assertTrue(failed is DeleteCheckpointExecutionResult.Failed)
    assertEquals(CheckpointPresence.UNKNOWN, failed.snapshot.checkpointPresence)
    assertFalse(failed.snapshot.isSyncConfirmationAllowed)
    assertEquals(3L, failed.snapshot.confirmedCheckpointRevision)
    assertEquals(3L, failed.snapshot.highestAllocatedRevision)

    // Regravação posterior ainda consulta getCheckpointById e aloca revisão 4 (sem regredir nem reutilizar).
    draftRepo.onDeleteHook = null
    draftRepo.seedDraft(identity.draftId, identity.sessionId, null, "ainda-existe", 3L)
    val reSave = executor.saveCheckpoint(payloadFor(identity, title = "regravado"))
    assertTrue(reSave is SaveCheckpointExecutionResult.Saved)
    assertEquals(4L, (reSave as SaveCheckpointExecutionResult.Saved).allocatedRevision)
  }

  @Test
  fun cancellationDuringInFlightSaveOrDelete_setsPresenceUnknownClearsInFlightAndRethrowsCancellation() =
    runBlocking {
      val draftRepo = FakeDraftRepository()
      val noteRepo = FakeNoteRepository()
      val identity = newNoteIdentity("cancel-in-flight")
      val executor =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepo,
          noteRepository = noteRepo,
          initialIdentity = identity,
          initialPresence = CheckpointPresence.PRESENT,
          initialConfirmedRevision = 1L,
        )

      val entered = CompletableDeferred<Unit>()
      val allowProceed = CompletableDeferred<Unit>()
      draftRepo.onDeleteHook = {
        entered.complete(Unit)
        allowProceed.await()
      }

      val job =
        launch(Dispatchers.Default) {
          executor.deleteCheckpoint(identity.draftId)
        }

      withTimeout(5_000) { entered.await() }
      assertTrue(executor.snapshot().hasInFlightOperation)

      job.cancelAndJoin()
      assertTrue(job.isCancelled)

      val snap = executor.snapshot()
      assertEquals(CheckpointPresence.UNKNOWN, snap.checkpointPresence)
      assertFalse(snap.hasInFlightOperation)
      assertFalse(snap.isSyncConfirmationAllowed)
    }

  @Test
  fun cancellationBeforeMutexAcquisition_preservesCheckpointPresenceAndDoesNotTouchRepository() =
    runBlocking {
      val draftRepo = FakeDraftRepository()
      val noteRepo = FakeNoteRepository()
      val identity = newNoteIdentity("cancel-before-mutex")
      val executor =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepo,
          noteRepository = noteRepo,
          initialIdentity = identity,
          initialPresence = CheckpointPresence.ABSENT,
        )

      val op1Entered = CompletableDeferred<Unit>()
      val op1Release = CompletableDeferred<Unit>()
      draftRepo.onSaveHook = { input ->
        if (input.title == "op1") {
          op1Entered.complete(Unit)
          op1Release.await()
        }
      }

      val holderJob =
        async(Dispatchers.Default) {
          executor.saveCheckpoint(payloadFor(identity, title = "op1"))
        }

      withTimeout(5_000) { op1Entered.await() }
      assertTrue(executor.snapshot().hasInFlightOperation)

      // Submete operação 2 que suspende na fila do mutex antes de iniciar qualquer I/O.
      val queuedJob =
        launch(start = CoroutineStart.UNDISPATCHED) {
          executor.saveCheckpoint(payloadFor(identity, title = "op2-cancelada"))
        }

      queuedJob.cancelAndJoin()
      assertTrue(queuedJob.isCancelled)

      // Libera op1 e verifica que op2 nunca iniciou I/O e presença permaneceu PRESENT (não UNKNOWN).
      op1Release.complete(Unit)
      val op1Result = withTimeout(5_000) { holderJob.await() }
      assertTrue(op1Result is SaveCheckpointExecutionResult.Saved)

      val finalSnap = executor.snapshot()
      assertEquals(CheckpointPresence.PRESENT, finalSnap.checkpointPresence)
      assertEquals(1L, finalSnap.confirmedCheckpointRevision)
      assertEquals(1L, finalSnap.highestAllocatedRevision)
      assertFalse(finalSnap.hasInFlightOperation)
      assertEquals(listOf("saveCheckpoint:op1"), draftRepo.callLog)
    }

  @Test
  fun replaceCheckpointWithNewNote_switchesTrackedIdentityAndIsIdempotentOnRetry() = runBlocking {
    val draftRepo = FakeDraftRepository()
    val noteRepo = FakeNoteRepository()
    val oldIdentity = existingNoteIdentity(noteId = 12L, sessionId = "old-12")
    val newIdentity = newNoteIdentity("new-from-12")

    draftRepo.seedDraft(
      draftId = oldIdentity.draftId,
      sessionId = oldIdentity.sessionId,
      targetNoteId = 12L,
      title = "conflito",
      checkpointRevision = 4L,
    )

    val executor =
      SingleFlightRoomPersistenceExecutor(
        draftRepository = draftRepo,
        noteRepository = noteRepo,
        initialIdentity = oldIdentity,
        initialPresence = CheckpointPresence.PRESENT,
        initialConfirmedRevision = 4L,
      )

    val newPayload =
      CheckpointSavePayload.fromIdentity(
        identity = newIdentity,
        folderId = null,
        fallbackSourceFolderId = null,
        isPinned = false,
        title = "Minha copia",
        content = sampleDocument("conteudo"),
        paperStyle = NotePaperStyle.PONTILHADO,
        initialPaperStyle = NotePaperStyle.PONTILHADO,
        baseRevision = 0L,
        baseNoteUpdatedAt = null,
      )

    val replaced =
      executor.replaceCheckpointWithNewNote(
        oldDraftId = oldIdentity.draftId,
        expectedOldSessionId = oldIdentity.sessionId,
        expectedOldTargetNoteId = 12L,
        newPayload = newPayload,
      )
    assertTrue(replaced is ReplaceCheckpointExecutionResult.Replaced)
    val snap = replaced.snapshot
    assertEquals(newIdentity.draftId, snap.draftId)
    assertEquals(newIdentity.sessionId, snap.sessionId)
    assertNull(snap.targetNoteId)
    assertEquals(CheckpointPresence.PRESENT, snap.checkpointPresence)
    assertEquals(1L, snap.confirmedCheckpointRevision)
    assertEquals(1L, snap.highestAllocatedRevision)
    assertFalse(snap.hasPendingReplacement)
    assertTrue(snap.isSyncConfirmationAllowed)

    // Após conversão bem-sucedida, edições subsequentes usam a nova identidade, preservam os
    // metadados iniciais do checkpoint convertido e incrementam a revisão a partir de 2.
    val nextSave =
      executor.saveCheckpoint(
        CheckpointSavePayload.fromIdentity(
          identity = newIdentity,
          folderId = null,
          initialFolderId = null,
          fallbackSourceFolderId = null,
          isPinned = false,
          title = "editado",
          content = sampleDocument("Texto"),
          paperStyle = NotePaperStyle.LINHAS,
          initialPaperStyle = NotePaperStyle.PONTILHADO,
          baseRevision = 0L,
          baseNoteUpdatedAt = null,
        )
      )
    assertTrue(nextSave is SaveCheckpointExecutionResult.Saved)
    assertEquals(2L, (nextSave as SaveCheckpointExecutionResult.Saved).allocatedRevision)
  }

  @Test
  fun replaceCheckpointWithNewNote_afterIndeterminateFailure_retryReturnsAlreadyReplacedAndUpdatesIdentity() =
    runBlocking {
      val draftRepo = FakeDraftRepository()
      val noteRepo = FakeNoteRepository()
      val oldIdentity = existingNoteIdentity(noteId = 20L, sessionId = "old-20")
      val newIdentity = newNoteIdentity("conv-20")
      draftRepo.seedDraft(oldIdentity.draftId, oldIdentity.sessionId, 20L, "titulo", 2L)

      val executor =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepo,
          noteRepository = noteRepo,
          initialIdentity = oldIdentity,
          initialPresence = CheckpointPresence.PRESENT,
          initialConfirmedRevision = 2L,
        )

      val newPayload = payloadFor(newIdentity, title = "titulo", baseRevision = 0L, baseNoteUpdatedAt = null)

      // Simula cenário em que o banco efetivou a substituição mas a chamada lançou erro no retorno.
      draftRepo.failAfterReplaceCommit = true
      val failed =
        executor.replaceCheckpointWithNewNote(
          oldDraftId = oldIdentity.draftId,
          expectedOldSessionId = oldIdentity.sessionId,
          expectedOldTargetNoteId = 20L,
          newPayload = newPayload,
        )
      assertTrue(failed is ReplaceCheckpointExecutionResult.Failed)
      assertEquals(CheckpointPresence.UNKNOWN, failed.snapshot.checkpointPresence)
      assertEquals(oldIdentity.draftId, failed.snapshot.draftId)
      assertTrue(failed.snapshot.hasPendingReplacement)
      assertFalse(failed.snapshot.isSyncConfirmationAllowed)

      draftRepo.failAfterReplaceCommit = false
      val retry =
        executor.replaceCheckpointWithNewNote(
          oldDraftId = oldIdentity.draftId,
          expectedOldSessionId = oldIdentity.sessionId,
          expectedOldTargetNoteId = 20L,
          newPayload = newPayload,
        )
      assertTrue(retry is ReplaceCheckpointExecutionResult.AlreadyReplaced)
      assertEquals(newIdentity.draftId, retry.snapshot.draftId)
      assertEquals(CheckpointPresence.PRESENT, retry.snapshot.checkpointPresence)
      assertEquals(1L, retry.snapshot.confirmedCheckpointRevision)
      assertFalse(retry.snapshot.hasPendingReplacement)
      assertNull(retry.snapshot.pendingReplacement)
      assertTrue(retry.snapshot.isSyncConfirmationAllowed)
    }

  @Test
  fun replaceCheckpointWithNewNote_failureBeforeCommit_preservesPendingStateBlocksIncompatibleOpsAndResolvesWithReplaced() =
    runBlocking {
      val draftRepo = FakeDraftRepository()
      val noteRepo = FakeNoteRepository()
      val oldIdentity = existingNoteIdentity(noteId = 41L, sessionId = "old-41")
      val newIdentity = newNoteIdentity("new-41")
      draftRepo.seedDraft(oldIdentity.draftId, oldIdentity.sessionId, 41L, "original-41", 3L)

      val executor =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepo,
          noteRepository = noteRepo,
          initialIdentity = oldIdentity,
          initialPresence = CheckpointPresence.PRESENT,
          initialConfirmedRevision = 3L,
        )

      val newPayload = payloadFor(newIdentity, title = "copia-41", baseRevision = 0L, baseNoteUpdatedAt = null)
      draftRepo.onReplaceHook = { throw IOException("transient I/O error before commit") }

      val firstFailure =
        executor.replaceCheckpointWithNewNote(
          oldDraftId = oldIdentity.draftId,
          expectedOldSessionId = oldIdentity.sessionId,
          expectedOldTargetNoteId = 41L,
          newPayload = newPayload,
          newDraftRevision = 1L,
        )
      assertTrue(firstFailure is ReplaceCheckpointExecutionResult.Failed)
      val snapAfterFail = firstFailure.snapshot
      assertEquals(CheckpointPresence.UNKNOWN, snapAfterFail.checkpointPresence)
      assertTrue(snapAfterFail.hasPendingReplacement)
      assertFalse(snapAfterFail.isSyncConfirmationAllowed)
      assertEquals(
        PendingReplacementState(
          oldDraftId = oldIdentity.draftId,
          expectedOldSessionId = oldIdentity.sessionId,
          expectedOldTargetNoteId = 41L,
          oldIdentity = oldIdentity,
          newPayload = newPayload,
          newDraftRevision = 1L,
        ),
        snapAfterFail.pendingReplacement,
      )

      // Todas as operações incompatíveis devem falhar de forma controlada sem gerar I/O adicional.
      val callsBeforeBlocked = draftRepo.callLog.size
      val blockedSave = executor.saveCheckpoint(payloadFor(oldIdentity, title = "nao-deve-gravar"))
      assertTrue(blockedSave is SaveCheckpointExecutionResult.Failed)
      assertFalse(blockedSave.snapshot.isSyncConfirmationAllowed)
      assertTrue(blockedSave.snapshot.hasPendingReplacement)

      val blockedDelete = executor.deleteCheckpoint(oldIdentity.draftId)
      assertTrue(blockedDelete is DeleteCheckpointExecutionResult.Failed)
      assertTrue(blockedDelete.snapshot.hasPendingReplacement)

      val blockedConsolidateCreate =
        executor.consolidateCreateNote(
          title = "Nova",
          content = sampleDocument("c"),
          folderId = null,
          isPinned = false,
          paperStyle = NotePaperStyle.LINHAS,
        )
      assertTrue(blockedConsolidateCreate is ConsolidateNoteExecutionResult.Failed)
      assertTrue(blockedConsolidateCreate.snapshot.hasPendingReplacement)

      val blockedConsolidateUpdate =
        executor.consolidateUpdateNote(
          noteId = 41L,
          title = "Atualiza",
          content = sampleDocument("c"),
          folderId = null,
          paperStyle = NotePaperStyle.LINHAS,
          expectedBaseRevision = 3L,
        )
      assertTrue(blockedConsolidateUpdate is ConsolidateNoteExecutionResult.Failed)
      assertTrue(blockedConsolidateUpdate.snapshot.hasPendingReplacement)

      val blockedReconcile = executor.reconcileCheckpointPresence(oldIdentity.draftId)
      assertTrue(blockedReconcile is ReconcileCheckpointExecutionResult.Failed)
      assertTrue(blockedReconcile.snapshot.hasPendingReplacement)

      assertEquals(callsBeforeBlocked, draftRepo.callLog.size)

      // Repetição idêntica após remoção da falha conclui normalmente com Replaced.
      draftRepo.onReplaceHook = null
      val resolved =
        executor.replaceCheckpointWithNewNote(
          oldDraftId = oldIdentity.draftId,
          expectedOldSessionId = oldIdentity.sessionId,
          expectedOldTargetNoteId = 41L,
          newPayload = newPayload,
          newDraftRevision = 1L,
        )
      assertTrue(resolved is ReplaceCheckpointExecutionResult.Replaced)
      val snapResolved = resolved.snapshot
      assertEquals(newIdentity.draftId, snapResolved.draftId)
      assertEquals(newIdentity.sessionId, snapResolved.sessionId)
      assertNull(snapResolved.targetNoteId)
      assertFalse(snapResolved.hasPendingReplacement)
      assertNull(snapResolved.pendingReplacement)
      assertEquals(CheckpointPresence.PRESENT, snapResolved.checkpointPresence)
      assertTrue(snapResolved.isSyncConfirmationAllowed)

      // Após resolução, operações normais na nova identidade funcionam.
      val postSave = executor.saveCheckpoint(payloadFor(newIdentity, title = "pos-substituicao"))
      assertTrue(postSave is SaveCheckpointExecutionResult.Saved)
      assertEquals(2L, (postSave as SaveCheckpointExecutionResult.Saved).allocatedRevision)
    }

  @Test
  fun replaceCheckpointWithNewNote_failureAfterCommitAndSecondRetryFailure_neverRecreatesOldDraftAndResolvesOnThirdAttempt() =
    runBlocking {
      val draftRepo = FakeDraftRepository()
      val noteRepo = FakeNoteRepository()
      val oldIdentity = existingNoteIdentity(noteId = 42L, sessionId = "old-42")
      val newIdentity = newNoteIdentity("new-42")
      draftRepo.seedDraft(oldIdentity.draftId, oldIdentity.sessionId, 42L, "original-42", 2L)

      val executor =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepo,
          noteRepository = noteRepo,
          initialIdentity = oldIdentity,
          initialPresence = CheckpointPresence.PRESENT,
          initialConfirmedRevision = 2L,
        )

      val newPayload = payloadFor(newIdentity, title = "copia-42", baseRevision = 0L, baseNoteUpdatedAt = null)
      draftRepo.failAfterReplaceCommit = true

      val indeterminate =
        executor.replaceCheckpointWithNewNote(
          oldDraftId = oldIdentity.draftId,
          expectedOldSessionId = oldIdentity.sessionId,
          expectedOldTargetNoteId = 42L,
          newPayload = newPayload,
        )
      assertTrue(indeterminate is ReplaceCheckpointExecutionResult.Failed)
      assertTrue(indeterminate.snapshot.hasPendingReplacement)
      assertFalse(indeterminate.snapshot.isSyncConfirmationAllowed)
      // Confirma que no banco o checkpoint antigo foi removido e apenas o novo existe.
      assertNull(draftRepo.getCheckpointById(oldIdentity.draftId))
      val persistedNewBeforeBlocked = draftRepo.getCheckpointById(newIdentity.draftId)
      assertEquals("copia-42", persistedNewBeforeBlocked?.title)

      val callsBeforeBlockedSave = draftRepo.callLog.size
      // Chamada posterior de saveCheckpoint com a identidade antiga NÃO pode consultar ou recriar o rascunho antigo.
      val blockedOldSave = executor.saveCheckpoint(payloadFor(oldIdentity, title = "recriacao-indevida"))
      assertTrue(blockedOldSave is SaveCheckpointExecutionResult.Failed)
      assertEquals(callsBeforeBlockedSave, draftRepo.callLog.size)
      assertNull(draftRepo.getCheckpointById(oldIdentity.draftId))

      // Segunda tentativa também falha: estado pendente e bloqueio de sincronização devem permanecer.
      draftRepo.failAfterReplaceCommit = false
      draftRepo.onReplaceHook = { throw IOException("second attempt also failed") }
      val secondFailure =
        executor.replaceCheckpointWithNewNote(
          oldDraftId = oldIdentity.draftId,
          expectedOldSessionId = oldIdentity.sessionId,
          expectedOldTargetNoteId = 42L,
          newPayload = newPayload,
        )
      assertTrue(secondFailure is ReplaceCheckpointExecutionResult.Failed)
      assertTrue(secondFailure.snapshot.hasPendingReplacement)
      assertFalse(secondFailure.snapshot.isSyncConfirmationAllowed)
      assertEquals(oldIdentity.draftId, secondFailure.snapshot.draftId)

      // Terceira tentativa idêntica reconcilia via AlreadyReplaced e libera a sessão na nova identidade.
      draftRepo.onReplaceHook = null
      val resolved =
        executor.replaceCheckpointWithNewNote(
          oldDraftId = oldIdentity.draftId,
          expectedOldSessionId = oldIdentity.sessionId,
          expectedOldTargetNoteId = 42L,
          newPayload = newPayload,
        )
      assertTrue(resolved is ReplaceCheckpointExecutionResult.AlreadyReplaced)
      assertFalse(resolved.snapshot.hasPendingReplacement)
      assertEquals(newIdentity.draftId, resolved.snapshot.draftId)
      assertTrue(resolved.snapshot.isSyncConfirmationAllowed)
      assertNull(draftRepo.getCheckpointById(oldIdentity.draftId))
    }

  @Test
  fun replaceCheckpointWithNewNote_cancellationDuringInFlightOperation_preservesPendingStateAndSyncBlockUntilIdenticalRetry() =
    runBlocking {
      val draftRepo = FakeDraftRepository()
      val noteRepo = FakeNoteRepository()
      val oldIdentity = existingNoteIdentity(noteId = 43L, sessionId = "old-43")
      val newIdentity = newNoteIdentity("new-43")
      draftRepo.seedDraft(oldIdentity.draftId, oldIdentity.sessionId, 43L, "original-43", 2L)

      val executor =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepo,
          noteRepository = noteRepo,
          initialIdentity = oldIdentity,
          initialPresence = CheckpointPresence.PRESENT,
          initialConfirmedRevision = 2L,
        )

      val newPayload = payloadFor(newIdentity, title = "copia-43", baseRevision = 0L, baseNoteUpdatedAt = null)
      val entered = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      draftRepo.onReplaceHook = {
        entered.complete(Unit)
        release.await()
      }

      val job =
        launch(Dispatchers.Default) {
          executor.replaceCheckpointWithNewNote(
            oldDraftId = oldIdentity.draftId,
            expectedOldSessionId = oldIdentity.sessionId,
            expectedOldTargetNoteId = 43L,
            newPayload = newPayload,
          )
        }

      withTimeout(5_000) { entered.await() }
      val inFlightSnap = executor.snapshot()
      assertTrue(inFlightSnap.hasInFlightOperation)
      assertTrue(inFlightSnap.hasPendingReplacement)
      assertFalse(inFlightSnap.isSyncConfirmationAllowed)

      job.cancelAndJoin()
      assertTrue(job.isCancelled)

      val cancelledSnap = executor.snapshot()
      assertFalse(cancelledSnap.hasInFlightOperation)
      assertEquals(CheckpointPresence.UNKNOWN, cancelledSnap.checkpointPresence)
      assertTrue(cancelledSnap.hasPendingReplacement)
      assertFalse(cancelledSnap.isSyncConfirmationAllowed)
      assertEquals(oldIdentity.draftId, cancelledSnap.draftId)

      // Operação antiga bloqueada sem I/O enquanto pendente após cancelamento.
      val callsAfterCancel = draftRepo.callLog.size
      val blockedSave = executor.saveCheckpoint(payloadFor(oldIdentity, title = "pos-cancelamento"))
      assertTrue(blockedSave is SaveCheckpointExecutionResult.Failed)
      assertEquals(callsAfterCancel, draftRepo.callLog.size)
      assertFalse(executor.snapshot().isSyncConfirmationAllowed)

      // Repetição idêntica conclui a substituição e restabelece a sincronização permitida.
      draftRepo.onReplaceHook = null
      val retry =
        executor.replaceCheckpointWithNewNote(
          oldDraftId = oldIdentity.draftId,
          expectedOldSessionId = oldIdentity.sessionId,
          expectedOldTargetNoteId = 43L,
          newPayload = newPayload,
        )
      assertTrue(retry is ReplaceCheckpointExecutionResult.Replaced)
      assertFalse(retry.snapshot.hasPendingReplacement)
      assertEquals(newIdentity.draftId, retry.snapshot.draftId)
      assertTrue(retry.snapshot.isSyncConfirmationAllowed)
    }

  @Test
  fun replaceCheckpointWithNewNote_deterministicRepositoryRejection_clearsPendingStatePreservesOldIdentityAndAllowsReconcile() =
    runBlocking {
      val draftRepo = FakeDraftRepository()
      val noteRepo = FakeNoteRepository()
      val oldIdentity = existingNoteIdentity(noteId = 51L, sessionId = "old-51")
      val collidingIdentity = newNoteIdentity("colliding-51")
      val newIdentity = newNoteIdentity("colliding-51")

      draftRepo.seedDraft(
        draftId = oldIdentity.draftId,
        sessionId = oldIdentity.sessionId,
        targetNoteId = 51L,
        title = "rascunho-antigo-51",
        checkpointRevision = 3L,
      )
      // Outro rascunho já ocupa sessionId = "colliding-51" com draftId diferente simulando colisão transacional Case C.
      // Para forçar colisão pura de sessionId em draftId distinto, semeamos um rascunho de nota existente com mesmo sessionId.
      val blockerNoteIdentity = existingNoteIdentity(noteId = 99L, sessionId = collidingIdentity.sessionId)
      draftRepo.seedDraft(
        draftId = blockerNoteIdentity.draftId,
        sessionId = blockerNoteIdentity.sessionId,
        targetNoteId = 99L,
        title = "bloqueador-51",
        checkpointRevision = 1L,
      )

      val executor =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepo,
          noteRepository = noteRepo,
          initialIdentity = oldIdentity,
          initialPresence = CheckpointPresence.PRESENT,
          initialConfirmedRevision = 3L,
        )

      val newPayload = payloadFor(newIdentity, title = "conversao-rejeitada", baseRevision = 0L, baseNoteUpdatedAt = null)
      val rejected =
        executor.replaceCheckpointWithNewNote(
          oldDraftId = oldIdentity.draftId,
          expectedOldSessionId = oldIdentity.sessionId,
          expectedOldTargetNoteId = 51L,
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
      assertEquals(51L, failed.snapshot.targetNoteId)
      assertEquals(CheckpointPresence.UNKNOWN, failed.snapshot.checkpointPresence)
      assertFalse(failed.snapshot.isSyncConfirmationAllowed)

      // Banco permanece integralmente revertido.
      assertEquals("rascunho-antigo-51", draftRepo.getCheckpointById(oldIdentity.draftId)?.title)
      assertNull(draftRepo.getCheckpointById(newIdentity.draftId))
      assertEquals("bloqueador-51", draftRepo.getCheckpointById(blockerNoteIdentity.draftId)?.title)

      // Estado posterior pode ser recuperado via reconciliação sem ficar preso em pendência.
      val reconciled = executor.reconcileCheckpointPresence(oldIdentity.draftId)
      assertTrue(reconciled is ReconcileCheckpointExecutionResult.Reconciled)
      val reconciledSnap = reconciled.snapshot
      assertEquals(CheckpointPresence.PRESENT, reconciledSnap.checkpointPresence)
      assertEquals(3L, reconciledSnap.confirmedCheckpointRevision)
      assertFalse(reconciledSnap.hasPendingReplacement)
      assertTrue(reconciledSnap.isSyncConfirmationAllowed)

      // Nova gravação na identidade antiga volta a operar normalmente.
      val postSave = executor.saveCheckpoint(payloadFor(oldIdentity, title = "pos-rejeicao-reconciliada"))
      assertTrue(postSave is SaveCheckpointExecutionResult.Saved)
      assertEquals(4L, (postSave as SaveCheckpointExecutionResult.Saved).allocatedRevision)
    }

  @Test
  fun replaceCheckpointWithNewNote_postCommitIdentityMismatch_preservesPendingStateAndBlocksIncompatibleOpsUntilValidRetry() =
    runBlocking {
      val draftRepo = FakeDraftRepository()
      val noteRepo = FakeNoteRepository()
      val oldIdentity = existingNoteIdentity(noteId = 52L, sessionId = "old-52")
      val newIdentity = newNoteIdentity("new-52")
      draftRepo.seedDraft(oldIdentity.draftId, oldIdentity.sessionId, 52L, "original-52", 2L)

      val executor =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepo,
          noteRepository = noteRepo,
          initialIdentity = oldIdentity,
          initialPresence = CheckpointPresence.PRESENT,
          initialConfirmedRevision = 2L,
        )

      val newPayload = payloadFor(newIdentity, title = "copia-52", baseRevision = 0L, baseNoteUpdatedAt = null)

      // Simula cenário em que o repositório efetivou a transação, mas retornou um draft com identidade divergente.
      draftRepo.replaceResultTransform = { result ->
        val committedDraft = (result as ReplaceCheckpointWithNewNoteResult.Replaced).draft
        ReplaceCheckpointWithNewNoteResult.Replaced(
          committedDraft.copy(
            draftId = "new_divergente",
            sessionId = "divergente",
          )
        )
      }

      val mismatchFailure =
        executor.replaceCheckpointWithNewNote(
          oldDraftId = oldIdentity.draftId,
          expectedOldSessionId = oldIdentity.sessionId,
          expectedOldTargetNoteId = 52L,
          newPayload = newPayload,
          newDraftRevision = 1L,
        )

      assertTrue(mismatchFailure is ReplaceCheckpointExecutionResult.Failed)
      val failed = mismatchFailure as ReplaceCheckpointExecutionResult.Failed
      assertTrue(failed.cause is InvalidRecoveryDraftException)
      assertTrue(failed.snapshot.hasPendingReplacement)
      assertTrue(failed.snapshot.pendingReplacement != null)
      assertFalse(failed.snapshot.hasInFlightOperation)
      assertEquals(oldIdentity.draftId, failed.snapshot.draftId)
      assertEquals(CheckpointPresence.UNKNOWN, failed.snapshot.checkpointPresence)
      assertFalse(failed.snapshot.isSyncConfirmationAllowed)

      // Confirma que a transação do repositório já havia excluído o antigo e persistido o novo.
      assertNull(draftRepo.getCheckpointById(oldIdentity.draftId))
      assertEquals("copia-52", draftRepo.getCheckpointById(newIdentity.draftId)?.title)

      // Operações incompatíveis continuam bloqueadas sem gerar I/O.
      val callsBeforeBlocked = draftRepo.callLog.size
      assertTrue(
        executor.saveCheckpoint(payloadFor(oldIdentity, title = "bloqueado")) is SaveCheckpointExecutionResult.Failed
      )
      assertTrue(executor.deleteCheckpoint(oldIdentity.draftId) is DeleteCheckpointExecutionResult.Failed)
      assertTrue(
        executor.consolidateCreateNote(
          title = "N",
          content = sampleDocument("c"),
          folderId = null,
          isPinned = false,
          paperStyle = NotePaperStyle.LINHAS,
        ) is ConsolidateNoteExecutionResult.Failed
      )
      assertTrue(
        executor.consolidateUpdateNote(
          noteId = 52L,
          title = "U",
          content = sampleDocument("c"),
          folderId = null,
          paperStyle = NotePaperStyle.LINHAS,
          expectedBaseRevision = 2L,
        ) is ConsolidateNoteExecutionResult.Failed
      )
      assertTrue(executor.reconcileCheckpointPresence(oldIdentity.draftId) is ReconcileCheckpointExecutionResult.Failed)
      assertEquals(callsBeforeBlocked, draftRepo.callLog.size)
      assertNull(draftRepo.getCheckpointById(oldIdentity.draftId))

      // Repetição idêntica após correção da anomalia resolve via AlreadyReplaced e restaura sincronização permitida.
      draftRepo.replaceResultTransform = null
      val resolved =
        executor.replaceCheckpointWithNewNote(
          oldDraftId = oldIdentity.draftId,
          expectedOldSessionId = oldIdentity.sessionId,
          expectedOldTargetNoteId = 52L,
          newPayload = newPayload,
          newDraftRevision = 1L,
        )
      assertTrue(resolved is ReplaceCheckpointExecutionResult.AlreadyReplaced)
      assertEquals(newIdentity.draftId, resolved.snapshot.draftId)
      assertEquals(CheckpointPresence.PRESENT, resolved.snapshot.checkpointPresence)
      assertFalse(resolved.snapshot.hasPendingReplacement)
      assertNull(resolved.snapshot.pendingReplacement)
      assertTrue(resolved.snapshot.isSyncConfirmationAllowed)
    }

  @Test
  fun replaceCheckpointWithNewNote_repositoryInvalidDraftDuringRetryAfterIndeterminateFailure_keepsPendingStateIntact() =
    runBlocking {
      val draftRepo = FakeDraftRepository()
      val noteRepo = FakeNoteRepository()
      val oldIdentity = existingNoteIdentity(noteId = 53L, sessionId = "old-53")
      val newIdentity = newNoteIdentity("new-53")
      draftRepo.seedDraft(oldIdentity.draftId, oldIdentity.sessionId, 53L, "original-53", 2L)

      val executor =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepo,
          noteRepository = noteRepo,
          initialIdentity = oldIdentity,
          initialPresence = CheckpointPresence.PRESENT,
          initialConfirmedRevision = 2L,
        )

      val newPayload = payloadFor(newIdentity, title = "copia-53", baseRevision = 0L, baseNoteUpdatedAt = null)

      // 1. Falha indeterminada após commit estabelece pendingReplacement.
      draftRepo.failAfterReplaceCommit = true
      val firstFailure =
        executor.replaceCheckpointWithNewNote(
          oldDraftId = oldIdentity.draftId,
          expectedOldSessionId = oldIdentity.sessionId,
          expectedOldTargetNoteId = 53L,
          newPayload = newPayload,
        )
      assertTrue(firstFailure is ReplaceCheckpointExecutionResult.Failed)
      assertTrue(firstFailure.snapshot.hasPendingReplacement)

      // 2. Retry encontra InvalidRecoveryDraftException no repositório enquanto pendingReplacement já estava ativo:
      // como uma tentativa anterior pode já ter efetivado commit, a pendência NÃO pode ser limpa.
      draftRepo.failAfterReplaceCommit = false
      draftRepo.onReplaceHook = { throw InvalidRecoveryDraftException("conflito inesperado na repetição") }
      val retryFailure =
        executor.replaceCheckpointWithNewNote(
          oldDraftId = oldIdentity.draftId,
          expectedOldSessionId = oldIdentity.sessionId,
          expectedOldTargetNoteId = 53L,
          newPayload = newPayload,
        )
      assertTrue(retryFailure is ReplaceCheckpointExecutionResult.Failed)
      assertTrue((retryFailure as ReplaceCheckpointExecutionResult.Failed).cause is InvalidRecoveryDraftException)
      assertTrue(retryFailure.snapshot.hasPendingReplacement)
      assertFalse(retryFailure.snapshot.isSyncConfirmationAllowed)
      assertEquals(oldIdentity.draftId, retryFailure.snapshot.draftId)

      val callsBeforeBlocked = draftRepo.callLog.size
      assertTrue(
        executor.saveCheckpoint(payloadFor(oldIdentity, title = "nao-recria")) is SaveCheckpointExecutionResult.Failed
      )
      assertEquals(callsBeforeBlocked, draftRepo.callLog.size)

      // 3. Repetição idêntica limpa conclui via AlreadyReplaced.
      draftRepo.onReplaceHook = null
      val resolved =
        executor.replaceCheckpointWithNewNote(
          oldDraftId = oldIdentity.draftId,
          expectedOldSessionId = oldIdentity.sessionId,
          expectedOldTargetNoteId = 53L,
          newPayload = newPayload,
        )
      assertTrue(resolved is ReplaceCheckpointExecutionResult.AlreadyReplaced)
      assertFalse(resolved.snapshot.hasPendingReplacement)
      assertEquals(newIdentity.draftId, resolved.snapshot.draftId)
      assertTrue(resolved.snapshot.isSyncConfirmationAllowed)
    }

  @Test
  fun consolidateCreateNote_passesClearDraftIdOnlyWhenCheckpointPresentOrFoundViaUnknownReconciliation() =
    runBlocking {
      val draftRepo = FakeDraftRepository()
      val noteRepo = FakeNoteRepository()

      // Caso 1: ABSENT -> clearDraftId = null (impede rollback em NoteRepositoryImpl)
      val idAbsent = newNoteIdentity("cons-absent")
      val execAbsent =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepo,
          noteRepository = noteRepo,
          initialIdentity = idAbsent,
          initialPresence = CheckpointPresence.ABSENT,
        )
      val resAbsent =
        execAbsent.consolidateCreateNote(
          title = "Nova",
          content = sampleDocument("corpo"),
          folderId = null,
          isPinned = false,
          paperStyle = NotePaperStyle.LINHAS,
        )
      assertTrue(resAbsent is ConsolidateNoteExecutionResult.Consolidated)
      assertNull((resAbsent as ConsolidateNoteExecutionResult.Consolidated).clearedDraftId)
      assertEquals(null, noteRepo.lastClearDraftId)

      // Caso 2: UNKNOWN mas rascunho existe no banco -> reconcilia, passa clearDraftId e zera presença para ABSENT
      val idUnknownFound = newNoteIdentity("cons-unknown-found")
      draftRepo.seedDraft(idUnknownFound.draftId, idUnknownFound.sessionId, null, "Rascunho", 2L)
      val execUnknownFound =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepo,
          noteRepository = noteRepo,
          initialIdentity = idUnknownFound,
          initialPresence = CheckpointPresence.UNKNOWN,
          initialConfirmedRevision = 1L,
          initialHighestAllocatedRevision = 2L,
        )
      val resFound =
        execUnknownFound.consolidateCreateNote(
          title = "Nova",
          content = sampleDocument("corpo"),
          folderId = null,
          isPinned = false,
          paperStyle = NotePaperStyle.LINHAS,
        )
      assertTrue(resFound is ConsolidateNoteExecutionResult.Consolidated)
      val consolidatedFound = resFound as ConsolidateNoteExecutionResult.Consolidated
      assertEquals(idUnknownFound.draftId, consolidatedFound.clearedDraftId)
      assertEquals(CheckpointPresence.ABSENT, consolidatedFound.snapshot.checkpointPresence)
      assertEquals(0L, consolidatedFound.snapshot.confirmedCheckpointRevision)
      assertEquals(2L, consolidatedFound.snapshot.highestAllocatedRevision)
    }

  @Test
  fun consolidateUpdateNote_onConflict_preservesCheckpointPresencePresentWhenDraftExists() =
    runBlocking {
      val draftRepo = FakeDraftRepository()
      val noteRepo = FakeNoteRepository()
      val identity = existingNoteIdentity(noteId = 88L, sessionId = "cons-conflict")
      draftRepo.seedDraft(identity.draftId, identity.sessionId, 88L, "Edicao", 2L)

      val executor =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepo,
          noteRepository = noteRepo,
          initialIdentity = identity,
          initialPresence = CheckpointPresence.PRESENT,
          initialConfirmedRevision = 2L,
        )

      noteRepo.nextUpdateException = NoteConflictException(noteId = 88L, expectedRevision = 1L, actualRevision = 3L)
      val result =
        executor.consolidateUpdateNote(
          noteId = 88L,
          title = "Edicao",
          content = sampleDocument("texto"),
          folderId = null,
          paperStyle = NotePaperStyle.LINHAS,
          expectedBaseRevision = 1L,
        )

      assertTrue(result is ConsolidateNoteExecutionResult.Conflict)
      val conflict = result as ConsolidateNoteExecutionResult.Conflict
      assertEquals(CheckpointPresence.PRESENT, conflict.snapshot.checkpointPresence)
      assertEquals(2L, conflict.snapshot.confirmedCheckpointRevision)
    }

  @Test
  fun consolidateUpdateNote_onUnexpectedErrorWithClearDraftId_marksPresenceUnknown() = runBlocking {
    val draftRepo = FakeDraftRepository()
    val noteRepo = FakeNoteRepository()
    val identity = existingNoteIdentity(noteId = 89L, sessionId = "cons-err")
    val executor =
      SingleFlightRoomPersistenceExecutor(
        draftRepository = draftRepo,
        noteRepository = noteRepo,
        initialIdentity = identity,
        initialPresence = CheckpointPresence.PRESENT,
        initialConfirmedRevision = 2L,
      )

    noteRepo.nextUpdateException = IOException("database locked")
    val result =
      executor.consolidateUpdateNote(
        noteId = 89L,
        title = "Edicao",
        content = sampleDocument("texto"),
        folderId = null,
        paperStyle = NotePaperStyle.LINHAS,
        expectedBaseRevision = 1L,
      )

    assertTrue(result is ConsolidateNoteExecutionResult.Failed)
    assertEquals(CheckpointPresence.UNKNOWN, result.snapshot.checkpointPresence)
    assertFalse(result.snapshot.isSyncConfirmationAllowed)
  }

  @Test
  fun consolidateCreateNote_whenClearDraftIdPassedAndRepositoryFails_marksPresenceUnknown() =
    runBlocking {
      val draftRepo = FakeDraftRepository()
      val noteRepo = FakeNoteRepository()
      val identity = newNoteIdentity("cons-create-err")
      val executor =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepo,
          noteRepository = noteRepo,
          initialIdentity = identity,
          initialPresence = CheckpointPresence.PRESENT,
          initialConfirmedRevision = 1L,
        )

      noteRepo.nextCreateException = IOException("disk error during create")
      val result =
        executor.consolidateCreateNote(
          title = "Nova",
          content = sampleDocument("corpo"),
          folderId = null,
          isPinned = false,
          paperStyle = NotePaperStyle.LINHAS,
        )

      assertTrue(result is ConsolidateNoteExecutionResult.Failed)
      assertEquals(identity.draftId, noteRepo.lastClearDraftId)
      assertEquals(CheckpointPresence.UNKNOWN, result.snapshot.checkpointPresence)
      assertFalse(result.snapshot.isSyncConfirmationAllowed)
    }

  @Test
  fun replaceCheckpointWithNewNote_whenRevisionAtLongMaxValue_returnsOverflowWithoutIo() =
    runBlocking {
      val draftRepo = FakeDraftRepository()
      val noteRepo = FakeNoteRepository()
      val oldIdentity = existingNoteIdentity(noteId = 25L, sessionId = "old-25")
      val newIdentity = newNoteIdentity("new-25")
      val executor =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepo,
          noteRepository = noteRepo,
          initialIdentity = oldIdentity,
          initialPresence = CheckpointPresence.PRESENT,
          initialConfirmedRevision = 1L,
        )

      val newPayload = payloadFor(newIdentity, title = "copia", baseRevision = 0L, baseNoteUpdatedAt = null)
      val result =
        executor.replaceCheckpointWithNewNote(
          oldDraftId = oldIdentity.draftId,
          expectedOldSessionId = oldIdentity.sessionId,
          expectedOldTargetNoteId = 25L,
          newPayload = newPayload,
          newDraftRevision = Long.MAX_VALUE,
        )

      assertTrue(result is ReplaceCheckpointExecutionResult.RevisionOverflow)
      assertTrue(draftRepo.callLog.isEmpty())
      assertEquals(oldIdentity.draftId, result.snapshot.draftId)
    }

  @Test
  fun reconcileCheckpointPresence_discoversPersistedOrAbsentStateSafely() = runBlocking {
    val draftRepo = FakeDraftRepository()
    val noteRepo = FakeNoteRepository()
    val identity = newNoteIdentity("reconcile")
    val executor =
      SingleFlightRoomPersistenceExecutor(
        draftRepository = draftRepo,
        noteRepository = noteRepo,
        initialIdentity = identity,
        initialPresence = CheckpointPresence.UNKNOWN,
      )

    val absentResult = executor.reconcileCheckpointPresence(identity.draftId)
    assertTrue(absentResult is ReconcileCheckpointExecutionResult.Reconciled)
    assertNull((absentResult as ReconcileCheckpointExecutionResult.Reconciled).persistedDraft)
    assertEquals(CheckpointPresence.ABSENT, absentResult.snapshot.checkpointPresence)

    draftRepo.seedDraft(identity.draftId, identity.sessionId, null, "recuperado", 7L)
    val presentResult = executor.reconcileCheckpointPresence(identity.draftId)
    assertTrue(presentResult is ReconcileCheckpointExecutionResult.Reconciled)
    assertEquals(7L, presentResult.snapshot.confirmedCheckpointRevision)
    assertEquals(7L, presentResult.snapshot.highestAllocatedRevision)
    assertEquals(CheckpointPresence.PRESENT, presentResult.snapshot.checkpointPresence)
  }

  @Test
  fun checkpointSavePayload_enforcesConsistentKeysAndValidBaseReferences() {
    assertThrows(IllegalArgumentException::class.java) {
      CheckpointSavePayload(
        draftId = "note_1_s1",
        sessionId = "s1",
        targetNoteId = null,
        folderId = null,
        fallbackSourceFolderId = null,
        isPinned = false,
        title = "A",
        content = sampleDocument("A"),
        paperStyle = NotePaperStyle.LINHAS,
        baseRevision = 0L,
        baseNoteUpdatedAt = null,
      )
    }

    assertThrows(IllegalArgumentException::class.java) {
      CheckpointSavePayload(
        draftId = "new_s1",
        sessionId = "s1",
        targetNoteId = null,
        folderId = 1L,
        fallbackSourceFolderId = 2L,
        isPinned = false,
        title = "A",
        content = sampleDocument("A"),
        paperStyle = NotePaperStyle.LINHAS,
        baseRevision = 0L,
        baseNoteUpdatedAt = null,
      )
    }

    assertThrows(IllegalArgumentException::class.java) {
      CheckpointSavePayload(
        draftId = "note_10_s1",
        sessionId = "s1",
        targetNoteId = 10L,
        folderId = null,
        fallbackSourceFolderId = null,
        isPinned = false,
        title = "A",
        content = sampleDocument("A"),
        paperStyle = NotePaperStyle.LINHAS,
        baseRevision = 0L,
        baseNoteUpdatedAt = 100L,
      )
    }
  }

  @Test
  fun saveCheckpoint_preservesInitialMetadata_includingLegitimateNullsAndImmutableNonNullValues() =
    runBlocking {
      val draftRepo = FakeDraftRepository()
      val noteRepo = FakeNoteRepository()

      // 1, 2 e 3: primeira gravação com metadados iniciais nulos, atualização com os mesmos nulos
      // (alterando apenas campos mutáveis) e rejeição ao tentar substituir nulos por preenchidos.
      val nullMetaIdentity = newNoteIdentity("init-nulls")
      val execNulls =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepo,
          noteRepository = noteRepo,
          initialIdentity = nullMetaIdentity,
          initialPresence = CheckpointPresence.ABSENT,
        )

      val firstNulls =
        execNulls.saveCheckpoint(
          CheckpointSavePayload.fromIdentity(
            identity = nullMetaIdentity,
            folderId = null,
            initialFolderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "v1-nulls",
            content = sampleDocument("c1"),
            paperStyle = NotePaperStyle.LINHAS,
            initialPaperStyle = null,
            baseRevision = 0L,
            baseNoteUpdatedAt = null,
          )
        )
      assertTrue(firstNulls is SaveCheckpointExecutionResult.Saved)
      val firstNullDraft = (firstNulls as SaveCheckpointExecutionResult.Saved).draft
      assertNull(firstNullDraft.initialFolderId)
      assertNull(firstNullDraft.initialPaperStyle)

      // 2. Atualização mantendo mesmos nulos iniciais e alterando campos mutáveis.
      val validNullUpdate =
        execNulls.saveCheckpoint(
          CheckpointSavePayload.fromIdentity(
            identity = nullMetaIdentity,
            folderId = 25L,
            initialFolderId = null,
            fallbackSourceFolderId = null,
            isPinned = true,
            title = "v2-mesmos-nulos",
            content = sampleDocument("c2"),
            paperStyle = NotePaperStyle.PONTILHADO,
            initialPaperStyle = null,
            baseRevision = 0L,
            baseNoteUpdatedAt = null,
          )
        )
      assertTrue(validNullUpdate is SaveCheckpointExecutionResult.Saved)
      val validNullDraft = (validNullUpdate as SaveCheckpointExecutionResult.Saved).draft
      assertNull(validNullDraft.initialFolderId)
      assertNull(validNullDraft.initialPaperStyle)
      assertEquals(25L, validNullDraft.folderId)
      assertEquals(NotePaperStyle.PONTILHADO, validNullDraft.paperStyle)

      // 3a. Rejeita tentativa de preencher initialFolderId antes nulo.
      val attemptOverwriteNullFolder =
        execNulls.saveCheckpoint(
          CheckpointSavePayload.fromIdentity(
            identity = nullMetaIdentity,
            folderId = 25L,
            initialFolderId = 25L,
            fallbackSourceFolderId = null,
            isPinned = true,
            title = "v3-tenta-alterar-initialFolderId",
            content = sampleDocument("c3"),
            paperStyle = NotePaperStyle.PONTILHADO,
            initialPaperStyle = null,
            baseRevision = 0L,
            baseNoteUpdatedAt = null,
          )
        )
      assertTrue(attemptOverwriteNullFolder is SaveCheckpointExecutionResult.Failed)
      assertTrue((attemptOverwriteNullFolder as SaveCheckpointExecutionResult.Failed).cause is InvalidRecoveryDraftException)

      // 3b. Rejeita tentativa de preencher initialPaperStyle antes nulo.
      val attemptOverwriteNullPaper =
        execNulls.saveCheckpoint(
          CheckpointSavePayload.fromIdentity(
            identity = nullMetaIdentity,
            folderId = 25L,
            initialFolderId = null,
            fallbackSourceFolderId = null,
            isPinned = true,
            title = "v4-tenta-alterar-initialPaperStyle",
            content = sampleDocument("c4"),
            paperStyle = NotePaperStyle.PONTILHADO,
            initialPaperStyle = NotePaperStyle.PONTILHADO,
            baseRevision = 0L,
            baseNoteUpdatedAt = null,
          )
        )
      assertTrue(attemptOverwriteNullPaper is SaveCheckpointExecutionResult.Failed)
      assertTrue((attemptOverwriteNullPaper as SaveCheckpointExecutionResult.Failed).cause is InvalidRecoveryDraftException)

      // Confirma que os metadados nulos persistidos não foram alterados.
      val persistedNulls = draftRepo.getCheckpointById(nullMetaIdentity.draftId)!!
      assertNull(persistedNulls.initialFolderId)
      assertNull(persistedNulls.initialPaperStyle)

      // 4, 5, 6 e 7: metadados iniciais não nulos aceitos, imutáveis e rejeitando alterações/apagamento.
      val nonNullIdentity = newNoteIdentity("init-non-nulls")
      val execNonNulls =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepo,
          noteRepository = noteRepo,
          initialIdentity = nonNullIdentity,
          initialPresence = CheckpointPresence.ABSENT,
        )

      val firstNonNull =
        execNonNulls.saveCheckpoint(
          CheckpointSavePayload.fromIdentity(
            identity = nonNullIdentity,
            folderId = 10L,
            initialFolderId = 10L,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "v1-com-iniciais",
            content = sampleDocument("a1"),
            paperStyle = NotePaperStyle.FOREST_MIST,
            initialPaperStyle = NotePaperStyle.FOREST_MIST,
            baseRevision = 0L,
            baseNoteUpdatedAt = null,
          )
        )
      assertTrue(firstNonNull is SaveCheckpointExecutionResult.Saved)
      val firstNonNullDraft = (firstNonNull as SaveCheckpointExecutionResult.Saved).draft
      assertEquals(10L, firstNonNullDraft.initialFolderId)
      assertEquals(NotePaperStyle.FOREST_MIST, firstNonNullDraft.initialPaperStyle)

      // 5 e 7. Atualiza campos mutáveis preservando os mesmos metadados iniciais não nulos.
      val validUpdateWithMutableChanges =
        execNonNulls.saveCheckpoint(
          CheckpointSavePayload.fromIdentity(
            identity = nonNullIdentity,
            folderId = 99L,
            initialFolderId = 10L,
            fallbackSourceFolderId = null,
            isPinned = true,
            title = "v2-campos-mutaveis-alterados",
            content = sampleDocument("a2"),
            paperStyle = NotePaperStyle.TECH_DARK,
            initialPaperStyle = NotePaperStyle.FOREST_MIST,
            baseRevision = 0L,
            baseNoteUpdatedAt = null,
          )
        )
      assertTrue(validUpdateWithMutableChanges is SaveCheckpointExecutionResult.Saved)
      val updatedDraft = (validUpdateWithMutableChanges as SaveCheckpointExecutionResult.Saved).draft
      assertEquals(10L, updatedDraft.initialFolderId)
      assertEquals(NotePaperStyle.FOREST_MIST, updatedDraft.initialPaperStyle)
      assertEquals(99L, updatedDraft.folderId)
      assertEquals(NotePaperStyle.TECH_DARK, updatedDraft.paperStyle)

      // 6a. Rejeita alteração de initialFolderId não nulo para outro valor.
      val attemptChangeFolder =
        execNonNulls.saveCheckpoint(
          CheckpointSavePayload.fromIdentity(
            identity = nonNullIdentity,
            folderId = 99L,
            initialFolderId = 99L,
            fallbackSourceFolderId = null,
            isPinned = true,
            title = "v3-altera-initialFolderId",
            content = sampleDocument("a3"),
            paperStyle = NotePaperStyle.TECH_DARK,
            initialPaperStyle = NotePaperStyle.FOREST_MIST,
            baseRevision = 0L,
            baseNoteUpdatedAt = null,
          )
        )
      assertTrue(attemptChangeFolder is SaveCheckpointExecutionResult.Failed)
      assertTrue((attemptChangeFolder as SaveCheckpointExecutionResult.Failed).cause is InvalidRecoveryDraftException)

      // 6b. Rejeita alteração de initialPaperStyle não nulo para outro valor.
      val attemptChangePaper =
        execNonNulls.saveCheckpoint(
          CheckpointSavePayload.fromIdentity(
            identity = nonNullIdentity,
            folderId = 99L,
            initialFolderId = 10L,
            fallbackSourceFolderId = null,
            isPinned = true,
            title = "v4-altera-initialPaperStyle",
            content = sampleDocument("a4"),
            paperStyle = NotePaperStyle.TECH_DARK,
            initialPaperStyle = NotePaperStyle.TECH_DARK,
            baseRevision = 0L,
            baseNoteUpdatedAt = null,
          )
        )
      assertTrue(attemptChangePaper is SaveCheckpointExecutionResult.Failed)
      assertTrue((attemptChangePaper as SaveCheckpointExecutionResult.Failed).cause is InvalidRecoveryDraftException)

      // 6c. Rejeita apagamento (envio de null) para initialFolderId ou initialPaperStyle antes não nulos.
      val attemptClearFolder =
        execNonNulls.saveCheckpoint(
          CheckpointSavePayload.fromIdentity(
            identity = nonNullIdentity,
            folderId = null,
            initialFolderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "v5-apaga-initialFolderId",
            content = sampleDocument("a5"),
            paperStyle = NotePaperStyle.LINHAS,
            initialPaperStyle = NotePaperStyle.FOREST_MIST,
            baseRevision = 0L,
            baseNoteUpdatedAt = null,
          )
        )
      assertTrue(attemptClearFolder is SaveCheckpointExecutionResult.Failed)
      assertTrue((attemptClearFolder as SaveCheckpointExecutionResult.Failed).cause is InvalidRecoveryDraftException)

      val attemptClearPaper =
        execNonNulls.saveCheckpoint(
          CheckpointSavePayload.fromIdentity(
            identity = nonNullIdentity,
            folderId = null,
            initialFolderId = 10L,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "v6-apaga-initialPaperStyle",
            content = sampleDocument("a6"),
            paperStyle = NotePaperStyle.LINHAS,
            initialPaperStyle = null,
            baseRevision = 0L,
            baseNoteUpdatedAt = null,
          )
        )
      assertTrue(attemptClearPaper is SaveCheckpointExecutionResult.Failed)
      assertTrue((attemptClearPaper as SaveCheckpointExecutionResult.Failed).cause is InvalidRecoveryDraftException)

      val persistedNonNulls = draftRepo.getCheckpointById(nonNullIdentity.draftId)!!
      assertEquals(10L, persistedNonNulls.initialFolderId)
      assertEquals(NotePaperStyle.FOREST_MIST, persistedNonNulls.initialPaperStyle)
    }

  @Test
  fun saveCheckpoint_staleRevisionReturnsIgnoredStaleRevisionBeforeInitialMetadataValidation() =
    runBlocking {
      val draftRepo = FakeDraftRepository()
      val noteRepo = FakeNoteRepository()
      val identity = newNoteIdentity("stale-order")
      draftRepo.seedDraft(
        draftId = identity.draftId,
        sessionId = identity.sessionId,
        targetNoteId = null,
        title = "persisted-rev-5",
        checkpointRevision = 5L,
        initialFolderId = 10L,
        initialPaperStyle = NotePaperStyle.FOREST_MIST,
      )

      val executor =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepo,
          noteRepository = noteRepo,
          initialIdentity = identity,
          initialPresence = CheckpointPresence.PRESENT,
          initialConfirmedRevision = 1L,
          initialHighestAllocatedRevision = 1L,
        )

      val staleWithDifferentInitialMetadata =
        CheckpointSavePayload.fromIdentity(
          identity = identity,
          folderId = 77L,
          initialFolderId = 77L,
          fallbackSourceFolderId = null,
          isPinned = false,
          title = "obsoleta",
          content = sampleDocument("obsoleta"),
          paperStyle = NotePaperStyle.TECH_DARK,
          initialPaperStyle = NotePaperStyle.TECH_DARK,
          baseRevision = 0L,
          baseNoteUpdatedAt = null,
        )

      val result = executor.saveCheckpoint(staleWithDifferentInitialMetadata)
      assertTrue(result is SaveCheckpointExecutionResult.IgnoredStaleRevision)
      val ignored = result as SaveCheckpointExecutionResult.IgnoredStaleRevision
      assertEquals(5L, ignored.persistedCheckpointRevision)
      assertEquals(2L, ignored.attemptedCheckpointRevision)
      assertEquals(5L, ignored.snapshot.confirmedCheckpointRevision)
      assertEquals(5L, ignored.snapshot.highestAllocatedRevision)
      assertEquals(CheckpointPresence.PRESENT, ignored.snapshot.checkpointPresence)

      val persisted = draftRepo.getCheckpointById(identity.draftId)!!
      assertEquals(10L, persisted.initialFolderId)
      assertEquals(NotePaperStyle.FOREST_MIST, persisted.initialPaperStyle)
    }

  @Test
  fun replaceCheckpointWithNewNote_retryWithDivergentPayload_rejectsInsteadOfReturningAlreadyReplaced() =
    runBlocking {
      val draftRepo = FakeDraftRepository()
      val noteRepo = FakeNoteRepository()
      val oldIdentity = existingNoteIdentity(noteId = 31L, sessionId = "old-31")
      val newIdentity = newNoteIdentity("new-31")
      draftRepo.seedDraft(oldIdentity.draftId, oldIdentity.sessionId, 31L, "original", 2L)

      val executor =
        SingleFlightRoomPersistenceExecutor(
          draftRepository = draftRepo,
          noteRepository = noteRepo,
          initialIdentity = oldIdentity,
          initialPresence = CheckpointPresence.PRESENT,
          initialConfirmedRevision = 2L,
        )

      val expectedPayload =
        CheckpointSavePayload.fromIdentity(
          identity = newIdentity,
          folderId = 10L,
          initialFolderId = 10L,
          fallbackSourceFolderId = null,
          isPinned = false,
          title = "Copia exata",
          content = sampleDocument("conteudo exato"),
          paperStyle = NotePaperStyle.LINHAS,
          initialPaperStyle = NotePaperStyle.LINHAS,
          baseRevision = 0L,
          baseNoteUpdatedAt = null,
        )

      // Simula falha após commit para manter oldIdentity ativa no retry seguinte.
      draftRepo.failAfterReplaceCommit = true
      val indeterminate =
        executor.replaceCheckpointWithNewNote(
          oldDraftId = oldIdentity.draftId,
          expectedOldSessionId = oldIdentity.sessionId,
          expectedOldTargetNoteId = 31L,
          newPayload = expectedPayload,
          newDraftRevision = 1L,
        )
      assertTrue(indeterminate is ReplaceCheckpointExecutionResult.Failed)
      assertEquals(oldIdentity.draftId, indeterminate.snapshot.draftId)

      draftRepo.failAfterReplaceCommit = false
      val divergentPayloads =
        listOf(
          expectedPayload to 2L,
          CheckpointSavePayload.fromIdentity(
            identity = newIdentity,
            folderId = 10L,
            initialFolderId = 10L,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Titulo divergente",
            content = sampleDocument("conteudo exato"),
            paperStyle = NotePaperStyle.LINHAS,
            initialPaperStyle = NotePaperStyle.LINHAS,
            baseRevision = 0L,
            baseNoteUpdatedAt = null,
          ) to 1L,
          CheckpointSavePayload.fromIdentity(
            identity = newIdentity,
            folderId = 10L,
            initialFolderId = 10L,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Copia exata",
            content = sampleDocument("conteudo divergente"),
            paperStyle = NotePaperStyle.LINHAS,
            initialPaperStyle = NotePaperStyle.LINHAS,
            baseRevision = 0L,
            baseNoteUpdatedAt = null,
          ) to 1L,
          CheckpointSavePayload.fromIdentity(
            identity = newIdentity,
            folderId = 20L,
            initialFolderId = 10L,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Copia exata",
            content = sampleDocument("conteudo exato"),
            paperStyle = NotePaperStyle.LINHAS,
            initialPaperStyle = NotePaperStyle.LINHAS,
            baseRevision = 0L,
            baseNoteUpdatedAt = null,
          ) to 1L,
          CheckpointSavePayload.fromIdentity(
            identity = newIdentity,
            folderId = 10L,
            initialFolderId = 20L,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Copia exata",
            content = sampleDocument("conteudo exato"),
            paperStyle = NotePaperStyle.LINHAS,
            initialPaperStyle = NotePaperStyle.LINHAS,
            baseRevision = 0L,
            baseNoteUpdatedAt = null,
          ) to 1L,
          CheckpointSavePayload.fromIdentity(
            identity = newIdentity,
            folderId = null,
            initialFolderId = 10L,
            fallbackSourceFolderId = 10L,
            isPinned = false,
            title = "Copia exata",
            content = sampleDocument("conteudo exato"),
            paperStyle = NotePaperStyle.LINHAS,
            initialPaperStyle = NotePaperStyle.LINHAS,
            baseRevision = 0L,
            baseNoteUpdatedAt = null,
          ) to 1L,
          CheckpointSavePayload.fromIdentity(
            identity = newIdentity,
            folderId = 10L,
            initialFolderId = 10L,
            fallbackSourceFolderId = null,
            isPinned = true,
            title = "Copia exata",
            content = sampleDocument("conteudo exato"),
            paperStyle = NotePaperStyle.LINHAS,
            initialPaperStyle = NotePaperStyle.LINHAS,
            baseRevision = 0L,
            baseNoteUpdatedAt = null,
          ) to 1L,
          CheckpointSavePayload.fromIdentity(
            identity = newIdentity,
            folderId = 10L,
            initialFolderId = 10L,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Copia exata",
            content = sampleDocument("conteudo exato"),
            paperStyle = NotePaperStyle.PONTILHADO,
            initialPaperStyle = NotePaperStyle.LINHAS,
            baseRevision = 0L,
            baseNoteUpdatedAt = null,
          ) to 1L,
          CheckpointSavePayload.fromIdentity(
            identity = newIdentity,
            folderId = 10L,
            initialFolderId = 10L,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Copia exata",
            content = sampleDocument("conteudo exato"),
            paperStyle = NotePaperStyle.LINHAS,
            initialPaperStyle = NotePaperStyle.PONTILHADO,
            baseRevision = 0L,
            baseNoteUpdatedAt = null,
          ) to 1L,
        )

      val callCountBeforeDivergent = draftRepo.callLog.size
      val pendingBeforeDivergent = indeterminate.snapshot.pendingReplacement
      assertTrue(pendingBeforeDivergent != null)

      for ((divergentPayload, rev) in divergentPayloads) {
        val rejected =
          executor.replaceCheckpointWithNewNote(
            oldDraftId = oldIdentity.draftId,
            expectedOldSessionId = oldIdentity.sessionId,
            expectedOldTargetNoteId = 31L,
            newPayload = divergentPayload,
            newDraftRevision = rev,
          )
        assertTrue(rejected is ReplaceCheckpointExecutionResult.Failed)
        val failed = rejected as ReplaceCheckpointExecutionResult.Failed
        assertTrue(failed.cause is InvalidRecoveryDraftException)
        assertEquals(oldIdentity.draftId, failed.snapshot.draftId)
        assertEquals(CheckpointPresence.UNKNOWN, failed.snapshot.checkpointPresence)
        assertEquals(pendingBeforeDivergent, failed.snapshot.pendingReplacement)
        assertFalse(failed.snapshot.isSyncConfirmationAllowed)
      }

      // Também rejeita parâmetros divergentes da identidade antiga sem tocar o repositório.
      val rejectedDivergentOld =
        executor.replaceCheckpointWithNewNote(
          oldDraftId = existingNoteIdentity(noteId = 99L, sessionId = "outro").draftId,
          expectedOldSessionId = "outro",
          expectedOldTargetNoteId = 99L,
          newPayload = expectedPayload,
          newDraftRevision = 1L,
        )
      assertTrue(rejectedDivergentOld is ReplaceCheckpointExecutionResult.Failed)
      assertTrue((rejectedDivergentOld as ReplaceCheckpointExecutionResult.Failed).cause is InvalidRecoveryDraftException)
      assertEquals(pendingBeforeDivergent, rejectedDivergentOld.snapshot.pendingReplacement)

      // Nenhuma chamada ao repositório deve ocorrer durante tentativas divergentes.
      assertEquals(callCountBeforeDivergent, draftRepo.callLog.size)

      // Somente o retry idêntico com todos os campos equivalentes conclui com AlreadyReplaced.
      val identicalRetry =
        executor.replaceCheckpointWithNewNote(
          oldDraftId = oldIdentity.draftId,
          expectedOldSessionId = oldIdentity.sessionId,
          expectedOldTargetNoteId = 31L,
          newPayload = expectedPayload,
          newDraftRevision = 1L,
        )
      assertTrue(identicalRetry is ReplaceCheckpointExecutionResult.AlreadyReplaced)
      assertEquals(newIdentity.draftId, identicalRetry.snapshot.draftId)
      assertEquals(CheckpointPresence.PRESENT, identicalRetry.snapshot.checkpointPresence)
      assertFalse(identicalRetry.snapshot.hasPendingReplacement)
      assertNull(identicalRetry.snapshot.pendingReplacement)
      assertTrue(identicalRetry.snapshot.isSyncConfirmationAllowed)
    }

  private class FakeDraftRepository : NoteRecoveryDraftRepository {
    private val store = LinkedHashMap<String, NoteRecoveryDraft>()
    private val concurrentCalls = AtomicInteger(0)
    val maxConcurrentCalls = AtomicInteger(0)
    val callLog = CopyOnWriteArrayList<String>()
    val savedRevisions = CopyOnWriteArrayList<Long>()
    val deleteCheckpointsForNoteCalls = AtomicInteger(0)

    var onSaveHook: (suspend (NoteRecoveryDraftInput) -> Unit)? = null
    var onDeleteHook: (suspend (String) -> Unit)? = null
    var onReplaceHook: (suspend () -> Unit)? = null
    var failAfterReplaceCommit: Boolean = false
    var replaceResultTransform:
      ((ReplaceCheckpointWithNewNoteResult) -> ReplaceCheckpointWithNewNoteResult)? =
      null

    private inline fun <T> trackConcurrency(block: () -> T): T {
      val current = concurrentCalls.incrementAndGet()
      while (true) {
        val prev = maxConcurrentCalls.get()
        if (current <= prev || maxConcurrentCalls.compareAndSet(prev, current)) break
      }
      return try {
        block()
      } finally {
        concurrentCalls.decrementAndGet()
      }
    }

    fun seedDraft(
      draftId: String,
      sessionId: String,
      targetNoteId: Long?,
      title: String,
      checkpointRevision: Long,
      checkpointAt: Long = 1_000L + checkpointRevision,
      initialFolderId: Long? = null,
      initialPaperStyle: NotePaperStyle? = NotePaperStyle.LINHAS,
    ) {
      store[draftId] =
        NoteRecoveryDraft(
          draftId = draftId,
          targetNoteId = targetNoteId,
          sessionId = sessionId,
          folderId = initialFolderId,
          initialFolderId = initialFolderId,
          fallbackSourceFolderId = null,
          isPinned = false,
          title = title,
          contentState = NoteContentState.Valid(NoteContentDocument(blocks = listOf(ParagraphBlock("b1", title)))),
          paperStyle = initialPaperStyle ?: NotePaperStyle.LINHAS,
          initialPaperStyle = initialPaperStyle,
          baseRevision = if (targetNoteId == null) 0L else 1L,
          checkpointRevision = checkpointRevision,
          baseNoteUpdatedAt = if (targetNoteId == null) null else 1_000L,
          checkpointAt = checkpointAt,
        )
    }

    override fun observeAllCheckpoints(): Flow<List<NoteRecoveryDraft>> = emptyFlow()

    override suspend fun getAllCheckpoints(): List<NoteRecoveryDraft> = store.values.toList()

    override suspend fun getCheckpointById(draftId: String): NoteRecoveryDraft? =
      trackConcurrency {
        callLog.add("getCheckpointById:$draftId")
        store[draftId]
      }

    override suspend fun getCheckpointBySessionId(sessionId: String): NoteRecoveryDraft? =
      store.values.firstOrNull { it.sessionId == sessionId }

    override suspend fun getCheckpointsForNote(noteId: Long): List<NoteRecoveryDraft> =
      store.values.filter { it.targetNoteId == noteId }

    override suspend fun saveCheckpoint(input: NoteRecoveryDraftInput): SaveCheckpointResult =
      trackConcurrency {
        callLog.add("saveCheckpoint:${input.title}")
        onSaveHook?.invoke(input)

        val (cleanDraftId, cleanSessionId) =
          try {
            NoteRecoveryDraftKeys.assertConsistentKeys(
              draftId = input.draftId,
              sessionId = input.sessionId,
              targetNoteId = input.targetNoteId,
            )
          } catch (e: IllegalArgumentException) {
            throw InvalidRecoveryDraftException(e.message ?: "Identificador de rascunho inválido.")
          }
        if (input.checkpointRevision <= 0L) {
          throw InvalidRecoveryDraftException("A revisão do checkpoint deve ser positiva.")
        }
        if (input.fallbackSourceFolderId != null && input.fallbackSourceFolderId <= 0L) {
          throw InvalidRecoveryDraftException(
            "Identificador fallbackSourceFolderId inválido: ${input.fallbackSourceFolderId}."
          )
        }
        if (input.folderId != null && input.fallbackSourceFolderId != null) {
          throw InvalidRecoveryDraftException(
            "folderId e fallbackSourceFolderId são mutuamente exclusivos e não podem coexistir no checkpoint."
          )
        }
        if (input.targetNoteId == null) {
          if (input.baseRevision != 0L || input.baseNoteUpdatedAt != null) {
            throw InvalidRecoveryDraftException(
              "Rascunho de nova nota deve possuir baseRevision=0 e baseNoteUpdatedAt nulo."
            )
          }
        } else {
          if (input.baseRevision <= 0L || input.baseNoteUpdatedAt == null || input.baseNoteUpdatedAt <= 0L) {
            throw InvalidRecoveryDraftException(
              "Rascunho de nota existente exige baseRevision e baseNoteUpdatedAt positivos."
            )
          }
        }

        val existing = store[cleanDraftId]
        if (existing != null) {
          if (existing.sessionId != cleanSessionId) {
            throw InvalidRecoveryDraftException(
              "O checkpoint '$cleanDraftId' pertence a outra sessão e não pode ser substituído."
            )
          }
          if (existing.targetNoteId != input.targetNoteId) {
            throw InvalidRecoveryDraftException(
              "Não é permitido alterar o targetNoteId de um checkpoint existente."
            )
          }
        }
        val existingBySessionId = store.values.firstOrNull { it.sessionId == cleanSessionId }
        if (existingBySessionId != null && existingBySessionId.draftId != cleanDraftId) {
          throw InvalidRecoveryDraftException(
            "A sessão '$cleanSessionId' já pertence a outro checkpoint ('${existingBySessionId.draftId}')."
          )
        }

        // Ordem real do Room: revisão obsoleta retorna IgnoredStaleRevision antes da validação de metadados iniciais.
        if (existing != null && input.checkpointRevision <= existing.checkpointRevision) {
          return@trackConcurrency SaveCheckpointResult.IgnoredStaleRevision(
            draftId = cleanDraftId,
            persistedCheckpointRevision = existing.checkpointRevision,
            attemptedCheckpointRevision = input.checkpointRevision,
          )
        }

        val (resolvedInitialFolderId, resolvedInitialPaperStyle) =
          if (input.targetNoteId != null) {
            null to null
          } else if (existing == null) {
            input.initialFolderId to input.initialPaperStyle
          } else {
            if (input.initialFolderId != existing.initialFolderId) {
              throw InvalidRecoveryDraftException(
                "Não é permitido alterar initialFolderId de um checkpoint existente."
              )
            }
            if (input.initialPaperStyle != existing.initialPaperStyle) {
              throw InvalidRecoveryDraftException(
                "Não é permitido alterar initialPaperStyle de um checkpoint existente."
              )
            }
            existing.initialFolderId to existing.initialPaperStyle
          }

        savedRevisions.add(input.checkpointRevision)
        val saved =
          NoteRecoveryDraft(
            draftId = cleanDraftId,
            targetNoteId = input.targetNoteId,
            sessionId = cleanSessionId,
            folderId = input.folderId,
            initialFolderId = resolvedInitialFolderId,
            fallbackSourceFolderId = input.fallbackSourceFolderId,
            isPinned = input.isPinned,
            title = input.title,
            contentState = NoteContentState.Valid(NoteContentPolicy.normalizeDocument(input.content)),
            paperStyle = input.paperStyle,
            initialPaperStyle = resolvedInitialPaperStyle,
            baseRevision = input.baseRevision,
            checkpointRevision = input.checkpointRevision,
            baseNoteUpdatedAt = input.baseNoteUpdatedAt,
            checkpointAt = 10_000L + input.checkpointRevision,
          )
        store[cleanDraftId] = saved
        SaveCheckpointResult.Saved(saved)
      }

    override suspend fun replaceCheckpointWithNewNote(
      oldDraftId: String,
      expectedOldSessionId: String,
      expectedOldTargetNoteId: Long,
      newInput: NoteRecoveryDraftInput,
    ): ReplaceCheckpointWithNewNoteResult =
      trackConcurrency {
        callLog.add("replaceCheckpoint:$oldDraftId->${newInput.draftId}")
        onReplaceHook?.invoke()

        if (expectedOldTargetNoteId <= 0L) {
          throw InvalidRecoveryDraftException(
            "Identificador esperado da nota alvo anterior inválido: $expectedOldTargetNoteId."
          )
        }
        val (cleanOldDraftId, cleanOldSessionId) =
          try {
            NoteRecoveryDraftKeys.assertConsistentKeys(
              draftId = oldDraftId,
              sessionId = expectedOldSessionId,
              targetNoteId = expectedOldTargetNoteId,
            )
          } catch (e: IllegalArgumentException) {
            throw InvalidRecoveryDraftException(
              e.message ?: "Identidade esperada do rascunho anterior inválida."
            )
          }

        val (cleanNewDraftId, cleanNewSessionId) =
          try {
            NoteRecoveryDraftKeys.assertConsistentKeys(
              draftId = newInput.draftId,
              sessionId = newInput.sessionId,
              targetNoteId = newInput.targetNoteId,
            )
          } catch (e: IllegalArgumentException) {
            throw InvalidRecoveryDraftException(e.message ?: "Identificador de rascunho inválido.")
          }

        if (newInput.targetNoteId != null) {
          throw InvalidRecoveryDraftException(
            "Substituição para nova nota exige targetNoteId nulo, recebido: ${newInput.targetNoteId}."
          )
        }
        if (cleanOldDraftId == cleanNewDraftId) {
          throw InvalidRecoveryDraftException(
            "O novo rascunho '$cleanNewDraftId' deve possuir identificador distinto do rascunho substituído."
          )
        }
        if (cleanOldSessionId == cleanNewSessionId) {
          throw InvalidRecoveryDraftException(
            "A conversão para nova nota exige um novo identificador de sessão distinto de '$cleanOldSessionId'."
          )
        }
        if (newInput.checkpointRevision <= 0L) {
          throw InvalidRecoveryDraftException("A revisão do checkpoint deve ser positiva.")
        }
        if (newInput.fallbackSourceFolderId != null && newInput.fallbackSourceFolderId <= 0L) {
          throw InvalidRecoveryDraftException(
            "Identificador fallbackSourceFolderId inválido: ${newInput.fallbackSourceFolderId}."
          )
        }
        if (newInput.folderId != null && newInput.fallbackSourceFolderId != null) {
          throw InvalidRecoveryDraftException(
            "folderId e fallbackSourceFolderId são mutuamente exclusivos."
          )
        }
        if (newInput.baseRevision != 0L || newInput.baseNoteUpdatedAt != null) {
          throw InvalidRecoveryDraftException(
            "Rascunho de nova nota deve possuir baseRevision=0 e baseNoteUpdatedAt nulo."
          )
        }

        val normalizedContent = NoteContentPolicy.normalizeDocument(newInput.content)
        val existingOld = store[cleanOldDraftId]
        if (existingOld != null) {
          if (
            existingOld.sessionId != cleanOldSessionId ||
              existingOld.targetNoteId != expectedOldTargetNoteId
          ) {
            throw InvalidRecoveryDraftException(
              "O checkpoint '$cleanOldDraftId' pertence a sessão ou nota divergente da esperada."
            )
          }
        }

        val existingByNewSession = store.values.firstOrNull { it.sessionId == cleanNewSessionId }
        if (existingByNewSession != null && existingByNewSession.draftId != cleanNewDraftId) {
          throw InvalidRecoveryDraftException(
            "A sessão '$cleanNewSessionId' já pertence a outro checkpoint ('${existingByNewSession.draftId}')."
          )
        }

        val existingNew = store[cleanNewDraftId]
        if (existingNew != null) {
          if (existingNew.sessionId != cleanNewSessionId) {
            throw InvalidRecoveryDraftException(
              "O checkpoint '$cleanNewDraftId' pertence a outra sessão."
            )
          }
          if (existingNew.targetNoteId != null) {
            throw InvalidRecoveryDraftException(
              "O checkpoint '$cleanNewDraftId' pertence a uma nota existente."
            )
          }

          val existingNormalizedContent =
            existingNew.contentOrNull?.let { NoteContentPolicy.normalizeDocument(it) }
          val matches =
            existingNew.draftId == cleanNewDraftId &&
              existingNew.sessionId == cleanNewSessionId &&
              existingNew.folderId == newInput.folderId &&
              existingNew.initialFolderId == newInput.initialFolderId &&
              existingNew.fallbackSourceFolderId == newInput.fallbackSourceFolderId &&
              existingNew.isPinned == newInput.isPinned &&
              existingNew.title == newInput.title &&
              existingNew.contentState is NoteContentState.Valid &&
              existingNormalizedContent == normalizedContent &&
              existingNew.paperStyle == newInput.paperStyle &&
              existingNew.initialPaperStyle == newInput.initialPaperStyle &&
              existingNew.baseRevision == 0L &&
              existingNew.checkpointRevision == newInput.checkpointRevision &&
              existingNew.baseNoteUpdatedAt == null

          if (!matches) {
            throw InvalidRecoveryDraftException(
              "O checkpoint '$cleanNewDraftId' já existe com dados divergentes; substituição rejeitada."
            )
          }
          if (existingOld != null) {
            throw InvalidRecoveryDraftException(
              "Inconsistência detectada: rascunho anterior '$cleanOldDraftId' e novo checkpoint '$cleanNewDraftId' coexistem."
            )
          }
          val baseResult = ReplaceCheckpointWithNewNoteResult.AlreadyReplaced(existingNew)
          return@trackConcurrency replaceResultTransform?.invoke(baseResult) ?: baseResult
        }

        val created =
          NoteRecoveryDraft(
            draftId = cleanNewDraftId,
            targetNoteId = null,
            sessionId = cleanNewSessionId,
            folderId = newInput.folderId,
            initialFolderId = newInput.initialFolderId,
            fallbackSourceFolderId = newInput.fallbackSourceFolderId,
            isPinned = newInput.isPinned,
            title = newInput.title,
            contentState = NoteContentState.Valid(normalizedContent),
            paperStyle = newInput.paperStyle,
            initialPaperStyle = newInput.initialPaperStyle,
            baseRevision = 0L,
            checkpointRevision = newInput.checkpointRevision,
            baseNoteUpdatedAt = null,
            checkpointAt = 20_000L,
          )
        store.remove(cleanOldDraftId)
        store[cleanNewDraftId] = created
        if (failAfterReplaceCommit) {
          throw IOException("simulated crash right after commit")
        }
        val baseResult = ReplaceCheckpointWithNewNoteResult.Replaced(created)
        replaceResultTransform?.invoke(baseResult) ?: baseResult
      }

    override suspend fun deleteCheckpoint(draftId: String) {
      trackConcurrency {
        callLog.add("deleteCheckpoint:$draftId")
        onDeleteHook?.invoke(draftId)
        store.remove(draftId)
      }
    }

    override suspend fun deleteCheckpointsForNote(noteId: Long) {
      deleteCheckpointsForNoteCalls.incrementAndGet()
      callLog.add("deleteCheckpointsForNote:$noteId")
      store.entries.removeIf { it.value.targetNoteId == noteId }
    }
  }

  private class FakeNoteRepository : NoteRepository {
    var lastClearDraftId: String? = "UNSET"
    var nextCreateException: Throwable? = null
    var nextUpdateException: Throwable? = null

    override fun observeAllActiveNotes(): Flow<List<Note>> = emptyFlow()

    override fun observePinnedActiveNotes(): Flow<List<Note>> = emptyFlow()

    override fun observeActiveNotesByFolder(folderId: Long): Flow<List<Note>> = emptyFlow()

    override fun observeDeletedNotes(): Flow<List<Note>> = emptyFlow()

    override fun observeNoteById(noteId: Long): Flow<Note?> = emptyFlow()

    override suspend fun getNoteById(noteId: Long): Note? = null

    override suspend fun createNote(
      title: String,
      content: NoteContentDocument,
      folderId: Long?,
      isPinned: Boolean,
      paperStyle: NotePaperStyle?,
      clearDraftId: String?,
    ): Note {
      lastClearDraftId = clearDraftId
      nextCreateException?.let {
        nextCreateException = null
        throw it
      }
      return Note(
        id = 100L,
        uid = "uid-100",
        folderId = folderId,
        title = title,
        contentState = NoteContentState.Valid(content),
        plainTextPreview = "",
        paperStyle = paperStyle ?: NotePaperStyle.LINHAS,
        revision = 1L,
        isPinned = isPinned,
        pinnedAt = if (isPinned) 1_000L else null,
        isDeleted = false,
        deletedAt = null,
        deletedWithFolder = false,
        createdAt = 1_000L,
        updatedAt = 1_000L,
      )
    }

    override suspend fun updateNote(
      noteId: Long,
      title: String,
      content: NoteContentDocument,
      folderId: Long?,
      paperStyle: NotePaperStyle?,
      expectedBaseRevision: Long?,
      clearDraftId: String?,
    ): Note {
      lastClearDraftId = clearDraftId
      nextUpdateException?.let {
        nextUpdateException = null
        throw it
      }
      return Note(
        id = noteId,
        uid = "uid-$noteId",
        folderId = folderId,
        title = title,
        contentState = NoteContentState.Valid(content),
        plainTextPreview = "",
        paperStyle = paperStyle ?: NotePaperStyle.LINHAS,
        revision = (expectedBaseRevision ?: 1L) + 1L,
        isPinned = false,
        pinnedAt = null,
        isDeleted = false,
        deletedAt = null,
        deletedWithFolder = false,
        createdAt = 1_000L,
        updatedAt = 2_000L,
      )
    }

    override suspend fun updateNotePaperStyle(noteId: Long, paperStyle: NotePaperStyle): Note =
      throw UnsupportedOperationException()

    override suspend fun setNotePinned(noteId: Long, pinned: Boolean) =
      throw UnsupportedOperationException()

    override suspend fun moveNoteToFolder(noteId: Long, folderId: Long?) =
      throw UnsupportedOperationException()

    override suspend fun softDeleteNote(noteId: Long) =
      throw UnsupportedOperationException()

    override suspend fun restoreNote(noteId: Long): Note =
      throw UnsupportedOperationException()

    override suspend fun permanentlyDeleteNote(noteId: Long) =
      throw UnsupportedOperationException()
  }
}
