package com.example.ui.viewmodel

import com.example.domain.editor.ConflictResolutionOption
import com.example.domain.editor.FolderAvailability
import com.example.domain.editor.NoteEditorSessionIdentity
import com.example.domain.editor.RecoveryConflict
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.NoteRecoveryDraftKeys
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoalescingCommandMailboxTest {

  @Test
  fun acceptedMutationSequence_incrementsMonotonicallyOnlyForPersistableCommands() {
    val mailbox = CoalescingCommandMailbox()
    assertEquals(0L, mailbox.acceptedMutationSequence)

    val r1 = mailbox.submit(SessionCommand.EditTitle("Titulo 1"))
    val r2 = mailbox.submit(SessionCommand.InsertText("Corpo"))
    val r3 = mailbox.submit(SessionCommand.PressEnter)
    val r4 = mailbox.submit(SessionCommand.ChangePaperStyle(NotePaperStyle.PONTILHADO))
    val r5 = mailbox.submit(SessionCommand.TogglePinned)

    assertEquals(SubmitCommandResult.Accepted(1L), r1)
    assertEquals(SubmitCommandResult.Accepted(2L), r2)
    assertEquals(SubmitCommandResult.Accepted(3L), r3)
    assertEquals(SubmitCommandResult.Accepted(4L), r4)
    assertEquals(SubmitCommandResult.Accepted(5L), r5)
    assertEquals(5L, mailbox.acceptedMutationSequence)
  }

  @Test
  fun nonPersistableCommands_neverIncrementAcceptedMutationSequence() {
    val mailbox = CoalescingCommandMailbox()
    assertEquals(SubmitCommandResult.Accepted(1L), mailbox.submit(SessionCommand.EditTitle("A")))

    val nonPersistables =
      listOf(
        SessionCommand.SelectBlock("b1", 0, 1),
        SessionCommand.FolderAvailabilityObserved(mapOf(1L to FolderAvailability.NotFound(1L))),
        SessionCommand.DebounceTimeout(1L),
        SessionCommand.FlushAutosave(),
        SessionCommand.ResolveConflict(ConflictResolutionOption.KEEP_DRAFT_OVER_CURRENT_NOTE),
        SessionCommand.ConsolidateNote(),
        SessionCommand.DiscardDraftAndClose(),
        SessionCommand.CloseSession(),
      )

    nonPersistables.forEach { cmd ->
      assertEquals(SubmitCommandResult.Accepted(null), mailbox.submit(cmd))
    }

    assertEquals(1L, mailbox.acceptedMutationSequence)
    assertEquals(9, mailbox.fifoQueueSize)
  }

  @Test
  fun fifoQueue_preservesExactSubmissionOrderAndAssignedSequences() {
    val mailbox = CoalescingCommandMailbox()
    val cmd1 = SessionCommand.EditTitle("A")
    val cmd2 = SessionCommand.SelectBlock("b1", 1, 1)
    val cmd3 = SessionCommand.InsertText("B")
    val cmd4 = SessionCommand.PressBackspace

    mailbox.submit(cmd1)
    mailbox.submit(cmd2)
    mailbox.submit(cmd3)
    mailbox.submit(cmd4)

    val polled = listOf(mailbox.pollNext(), mailbox.pollNext(), mailbox.pollNext(), mailbox.pollNext())
    assertEquals(
      listOf(
        MailboxItem.Command(QueuedSessionCommand(cmd1, 1L)),
        MailboxItem.Command(QueuedSessionCommand(cmd2, null)),
        MailboxItem.Command(QueuedSessionCommand(cmd3, 2L)),
        MailboxItem.Command(QueuedSessionCommand(cmd4, 3L)),
      ),
      polled,
    )
    assertNull(mailbox.pollNext())
  }

  @Test
  fun barrierActive_prioritizesPendingCompletionsRetainsFifoAndRejectsWrongReleaseToken() {
    val mailbox = CoalescingCommandMailbox()
    mailbox.submit(SessionCommand.InsertText("retida-1"))
    mailbox.submit(SessionCommand.InsertText("retida-2"))

    val token = mailbox.enterBarrier(BarrierReason.CONSOLIDATION)
    assertTrue(mailbox.barrierActive)

    // Impede dupla barreira simultanea
    assertNull(mailbox.tryEnterBarrier(BarrierReason.DISCARD_DRAFT))
    assertThrows(IllegalStateException::class.java) {
      mailbox.enterBarrier(BarrierReason.CONFLICT_RESOLUTION)
    }

    // Enquanto barreira ativa e sem conclusoes pendentes, fifoQueue fica estritamente retida
    assertNull(mailbox.pollNext())
    assertEquals(2, mailbox.fifoQueueSize)

    // Conclusao enfileirada eh retirada primeiro mesmo com fifoQueue nao vazia
    val completion =
      PersistenceCompletion.StructuralOperation(
        operationId = 10L,
        barrierToken = token,
        succeeded = false,
        terminal = false,
      )
    mailbox.enqueueCompletion(completion)

    assertEquals(MailboxItem.Completion(completion), mailbox.pollNext())
    assertNull(mailbox.pollNext())
    assertEquals(2, mailbox.fifoQueueSize)

    // Token divergente nao libera barreira
    val forgedToken = BarrierToken(id = token.id + 99L, reason = BarrierReason.CONSOLIDATION)
    assertFalse(mailbox.releaseBarrier(forgedToken))
    assertTrue(mailbox.barrierActive)
    assertNull(mailbox.pollNext())

    // Token legitimo libera barreira e FIFO retida passa a ser entregue na ordem original
    assertTrue(mailbox.releaseBarrier(token))
    assertFalse(mailbox.barrierActive)
    assertEquals(
      SessionCommand.InsertText("retida-1"),
      (mailbox.pollNext() as MailboxItem.Command).queued.command,
    )
    assertEquals(
      SessionCommand.InsertText("retida-2"),
      (mailbox.pollNext() as MailboxItem.Command).queued.command,
    )
    assertNull(mailbox.pollNext())
  }

  @Test
  fun barrierInactive_prioritizesFifoBeforePendingCompletions() {
    val mailbox = CoalescingCommandMailbox()
    val g1Completion =
      PersistenceCompletion.CheckpointUpsert(
        operationId = 1L,
        targetGeneration = 1L,
        attemptedCheckpointRevision = 1L,
        persistedCheckpointRevision = 1L,
        checkpointAt = 1000L,
      )
    val g2Command = SessionCommand.InsertText("G2")

    // Mesmo com conclusao enfileirada antes, sem barreira a FIFO eh priorizada
    mailbox.enqueueCompletion(g1Completion)
    mailbox.submit(g2Command)

    val first = mailbox.pollNext()
    val second = mailbox.pollNext()

    assertEquals(MailboxItem.Command(QueuedSessionCommand(g2Command, 1L)), first)
    assertEquals(MailboxItem.Completion(g1Completion), second)
    assertNull(mailbox.pollNext())
  }

  @Test
  fun fairnessCounter_preventsCompletionStarvationBeyond32FifoItemsAndNeverExposesSyncedState() {
    val mailbox =
      CoalescingCommandMailbox(
        initialUiState =
          NoteEditorUiState(
            phase = NoteEditorSessionPhase.EDITING,
            stateGeneration = 0L,
            lastSyncedGeneration = 0L,
            autosaveStatus = AutosaveStatus.Idle,
          ),
      )

    repeat(70) { index ->
      assertEquals(
        SubmitCommandResult.Accepted((index + 1).toLong()),
        mailbox.submit(SessionCommand.InsertText("k$index")),
      )
    }

    val completion =
      PersistenceCompletion.CheckpointUpsert(
        operationId = 1L,
        targetGeneration = 1L,
        attemptedCheckpointRevision = 1L,
        persistedCheckpointRevision = 1L,
        checkpointAt = 5000L,
      )
    mailbox.enqueueCompletion(completion)

    val delivered = mutableListOf<MailboxItem>()
    for (step in 1..32) {
      val item = mailbox.pollNext()
      assertTrue("Passo $step deveria vir da FIFO", item is MailboxItem.Command)
      delivered.add(item!!)
    }
    assertEquals(32, mailbox.consecutiveFifoPolls)

    // No 33o poll, com 38 comandos ainda na FIFO, a justica libera a conclusao pendente
    val fairnessItem = mailbox.pollNext()
    assertEquals(MailboxItem.Completion(completion), fairnessItem)
    assertEquals(0, mailbox.consecutiveFifoPolls)

    // Se o consumidor publicar logo apos tratar a conclusao por justica, o estado NAO pode virar Saved
    val publishedAfterFairnessCompletion =
      mailbox.publishState(
        candidateState =
          NoteEditorUiState(
            phase = NoteEditorSessionPhase.EDITING,
            stateGeneration = 32L,
            lastSyncedGeneration = 32L,
            autosaveStatus = AutosaveStatus.Saved(syncedGeneration = 32L, checkpointAt = 5000L),
          ),
        appliedMutationSequence = 32L,
      )
    assertEquals(AutosaveStatus.PendingDebounce, publishedAfterFairnessCompletion.autosaveStatus)
    assertFalse(publishedAfterFairnessCompletion.isSessionFullySynced)

    // Demais 38 itens FIFO continuam disponiveis em ordem
    var remainingCommands = 0
    while (true) {
      val next = mailbox.pollNext() ?: break
      assertTrue(next is MailboxItem.Command)
      remainingCommands += 1
    }
    assertEquals(38, remainingCommands)
    assertEquals(70, delivered.size + remainingCommands)
  }

  @Test
  fun persistableMutationAccepted_immediatelyInvalidatesSavedAndIdleToPendingDebounce() {
    val mailbox = CoalescingCommandMailbox(initialUiState = syncedSavedState(generation = 3L))
    mailbox.submitAndConsumeAsSynced(generation = 3L, seqCount = 3)
    assertTrue(mailbox.uiState.value.isSessionFullySynced)
    assertTrue(mailbox.uiState.value.autosaveStatus is AutosaveStatus.Saved)

    val submitResult = mailbox.submit(SessionCommand.EditTitle("Novo titulo"))
    assertEquals(SubmitCommandResult.Accepted(4L), submitResult)

    // Antes mesmo de qualquer pollNext(), uiState ja foi invalidado atomicamente
    assertEquals(AutosaveStatus.PendingDebounce, mailbox.uiState.value.autosaveStatus)
    assertFalse(mailbox.uiState.value.isSessionFullySynced)

    // Tambem invalida Idle quando sem barreira ativa
    val idleMailbox =
      CoalescingCommandMailbox(
        initialUiState =
          NoteEditorUiState(
            phase = NoteEditorSessionPhase.EDITING,
            stateGeneration = 0L,
            lastSyncedGeneration = 0L,
            autosaveStatus = AutosaveStatus.Idle,
          )
      )
    assertTrue(idleMailbox.uiState.value.isSessionFullySynced)
    idleMailbox.submit(SessionCommand.InsertText("a"))
    assertEquals(AutosaveStatus.PendingDebounce, idleMailbox.uiState.value.autosaveStatus)
    assertFalse(idleMailbox.uiState.value.isSessionFullySynced)
  }

  @Test
  fun publishState_blocksSavedAndIdleWheneverPendingMutationsOrInconsistencyExist() {
    val mailbox = CoalescingCommandMailbox()

    // Caso 1: mutacao aceita (#1) ainda nao aplicada (applied = 0)
    assertEquals(SubmitCommandResult.Accepted(1L), mailbox.submit(SessionCommand.InsertText("x")))

    val blockedSavedForUnapplied =
      mailbox.publishState(
        candidateState = syncedSavedState(generation = 1L),
        appliedMutationSequence = 0L,
      )
    assertEquals(AutosaveStatus.PendingDebounce, blockedSavedForUnapplied.autosaveStatus)
    assertFalse(blockedSavedForUnapplied.isSessionFullySynced)

    val blockedIdleForUnapplied =
      mailbox.publishState(
        candidateState =
          NoteEditorUiState(
            phase = NoteEditorSessionPhase.EDITING,
            stateGeneration = 0L,
            lastSyncedGeneration = 0L,
            autosaveStatus = AutosaveStatus.Idle,
          ),
        appliedMutationSequence = 0L,
      )
    assertEquals(AutosaveStatus.PendingDebounce, blockedIdleForUnapplied.autosaveStatus)
    assertFalse(blockedIdleForUnapplied.isSessionFullySynced)

    // Consome a mutacao #1 e agora tenta publicar com condicoes variadas de inconsistencia
    assertNotNull(mailbox.pollNext())

    // Caso 2: stateGeneration != lastSyncedGeneration
    val blockedGenerationMismatch =
      mailbox.publishState(
        candidateState =
          syncedSavedState(generation = 1L).copy(stateGeneration = 2L, lastSyncedGeneration = 1L),
        appliedMutationSequence = 1L,
      )
    assertEquals(AutosaveStatus.PendingDebounce, blockedGenerationMismatch.autosaveStatus)
    assertFalse(blockedGenerationMismatch.isSessionFullySynced)

    // Caso 3: gravacao em andamento
    val blockedInFlightWrite =
      mailbox.publishState(
        candidateState = syncedSavedState(generation = 1L),
        appliedMutationSequence = 1L,
        hasInFlightWrite = true,
      )
    assertEquals(AutosaveStatus.Saving, blockedInFlightWrite.autosaveStatus)
    assertFalse(blockedInFlightWrite.isSessionFullySynced)

    // Caso 4: erro pendente
    val blockedPendingError =
      mailbox.publishState(
        candidateState = syncedSavedState(generation = 1L),
        appliedMutationSequence = 1L,
        hasPendingError = true,
      )
    assertTrue(blockedPendingError.autosaveStatus is AutosaveStatus.Error)
    assertFalse(blockedPendingError.isSessionFullySynced)

    // Caso 5: conflito pendente
    val conflict =
      RecoveryConflict.NoteDiverged(
        draftId = NoteRecoveryDraftKeys.forExistingNote(10L, "s1"),
        targetNoteId = 10L,
        draftBaseRevision = 1L,
        currentNoteRevision = 2L,
        draftBaseUpdatedAt = 100L,
        currentNoteUpdatedAt = 200L,
      )
    val blockedConflict =
      mailbox.publishState(
        candidateState = syncedSavedState(generation = 1L).copy(activeConflict = conflict),
        appliedMutationSequence = 1L,
        hasUnresolvedConflict = true,
      )
    assertEquals(AutosaveStatus.HeldPendingResolution, blockedConflict.autosaveStatus)
    assertFalse(blockedConflict.isSessionFullySynced)

    // Caso 6: Idle com retencao obrigatoria nao pode ser exposto como sincronizado
    val blockedIdleWithRetention =
      mailbox.publishState(
        candidateState =
          NoteEditorUiState(
            phase = NoteEditorSessionPhase.EDITING,
            stateGeneration = 1L,
            lastSyncedGeneration = 1L,
            autosaveStatus = AutosaveStatus.Idle,
          ),
        appliedMutationSequence = 1L,
        needsDraftRetention = true,
      )
    assertEquals(AutosaveStatus.PendingDebounce, blockedIdleWithRetention.autosaveStatus)
    assertFalse(blockedIdleWithRetention.isSessionFullySynced)

    // Caso 7: Tudo alinhado -> Saved(1) eh publicado normalmente
    val validSaved =
      mailbox.publishState(
        candidateState = syncedSavedState(generation = 1L),
        appliedMutationSequence = 1L,
        needsDraftRetention = true,
      )
    assertEquals(AutosaveStatus.Saved(syncedGeneration = 1L, checkpointAt = 999L), validSaved.autosaveStatus)
    assertTrue(validSaved.isSessionFullySynced)
  }

  @Test
  fun selectionAndFlushCommands_doNotInvalidateSavedStatus() {
    val mailbox = CoalescingCommandMailbox()
    mailbox.submitAndConsumeAsSynced(generation = 2L, seqCount = 2)
    assertTrue(mailbox.uiState.value.isSessionFullySynced)

    val selectResult = mailbox.submit(SessionCommand.SelectBlock(blockId = "b1", start = 1, end = 4))
    val flushResult = mailbox.submit(SessionCommand.FlushAutosave())

    assertEquals(SubmitCommandResult.Accepted(null), selectResult)
    assertEquals(SubmitCommandResult.Accepted(null), flushResult)
    assertEquals(2L, mailbox.acceptedMutationSequence)
    assertEquals(
      AutosaveStatus.Saved(syncedGeneration = 2L, checkpointAt = 999L),
      mailbox.uiState.value.autosaveStatus,
    )
    assertTrue(mailbox.uiState.value.isSessionFullySynced)
  }

  @Test
  fun close_rejectsNewCommandsWithAckFailureWhilePreservingAlreadyAcceptedItems() = runTest {
    val mailbox = CoalescingCommandMailbox()
    assertEquals(SubmitCommandResult.Accepted(1L), mailbox.submit(SessionCommand.EditTitle("Antes")))
    assertEquals(SubmitCommandResult.Accepted(null), mailbox.submit(SessionCommand.SelectBlock("b1", 0)))

    mailbox.close()
    assertTrue(mailbox.isClosed)

    val rejectedAck = CompletableDeferred<SessionCommandOutcome>()
    val rejectedEdit = mailbox.submit(SessionCommand.InsertText("Depois"))
    val rejectedFlush = mailbox.submit(SessionCommand.FlushAutosave(ackDeferred = rejectedAck))

    assertEquals(SubmitCommandResult.Rejected(SubmitRejectionReason.MAILBOX_CLOSED), rejectedEdit)
    assertEquals(SubmitCommandResult.Rejected(SubmitRejectionReason.MAILBOX_CLOSED), rejectedFlush)
    assertEquals(
      SessionCommandOutcome.Rejected(SubmitRejectionReason.MAILBOX_CLOSED),
      rejectedAck.await(),
    )
    assertEquals(1L, mailbox.acceptedMutationSequence)

    // Itens ja aceitos continuam entregaveis via receiveNext() e apos dreno receiveNext() retorna null
    val item1 = mailbox.receiveNext()
    val item2 = mailbox.receiveNext()
    val terminal = mailbox.receiveNext()

    assertEquals(SessionCommand.EditTitle("Antes"), (item1 as MailboxItem.Command).queued.command)
    assertEquals(SessionCommand.SelectBlock("b1", 0), (item2 as MailboxItem.Command).queued.command)
    assertNull(terminal)
  }

  @Test
  fun consumerCancellationDuringReceiveNext_neverLosesQueuedOrSubsequentCommands() = runBlocking {
    val mailbox = CoalescingCommandMailbox()
    val waiterStarted = CompletableDeferred<Unit>()

    val consumerJob =
      launch(Dispatchers.Default) {
        waiterStarted.complete(Unit)
        mailbox.receiveNext()
      }
    waiterStarted.await()
    delay(25L)
    consumerJob.cancelAndJoin()

    // Comandos submetidos apos cancelamento sao entregues intactos a um novo consumidor
    mailbox.submit(SessionCommand.EditTitle("Preservado"))
    val received = mailbox.receiveNext()
    assertEquals(
      SessionCommand.EditTitle("Preservado"),
      (received as MailboxItem.Command).queued.command,
    )

    // Cancelamento durante barreira ativa com FIFO retida preserva toda a FIFO intacta
    mailbox.submit(SessionCommand.InsertText("retido-cancelamento"))
    val token = mailbox.enterBarrier(BarrierReason.DISCARD_DRAFT)
    val barrierWaitStarted = CompletableDeferred<Unit>()
    val barrierJob =
      launch(Dispatchers.Default) {
        barrierWaitStarted.complete(Unit)
        mailbox.receiveNext()
      }
    barrierWaitStarted.await()
    delay(25L)
    barrierJob.cancelAndJoin()

    assertEquals(1, mailbox.fifoQueueSize)
    assertTrue(mailbox.releaseBarrier(token))
    val afterBarrier = mailbox.receiveNext()
    assertEquals(
      SessionCommand.InsertText("retido-cancelamento"),
      (afterBarrier as MailboxItem.Command).queued.command,
    )
  }

  @Test
  fun concurrentProducers_preserveMonotonicUniqueSequencesWithoutLosses() = runBlocking {
    val mailbox = CoalescingCommandMailbox()
    val producerCount = 8
    val persistablesPerProducer = 100
    val selectionsPerProducer = 25
    val totalPersistables = producerCount * persistablesPerProducer
    val totalExpectedItems = totalPersistables + (producerCount * selectionsPerProducer)

    val consumedItems = mutableListOf<MailboxItem>()
    val consumer =
      async(Dispatchers.Default) {
        while (true) {
          val next = mailbox.receiveNext() ?: break
          consumedItems.add(next)
        }
      }

    coroutineScope {
      repeat(producerCount) { pIndex ->
        launch(Dispatchers.Default) {
          var lastSeq = 0L
          repeat(persistablesPerProducer) { i ->
            val res = mailbox.submit(SessionCommand.InsertText("p$pIndex-$i"))
            val accepted = res as SubmitCommandResult.Accepted
            val seq = accepted.assignedMutationSequence!!
            assertTrue("Sequencia deve ser estritamente crescente por produtor", seq > lastSeq)
            lastSeq = seq
            if (i < selectionsPerProducer) {
              assertEquals(
                SubmitCommandResult.Accepted(null),
                mailbox.submit(SessionCommand.SelectBlock("b$pIndex", i, i)),
              )
            }
          }
        }
      }
    }

    mailbox.close()
    consumer.await()

    assertEquals(totalExpectedItems, consumedItems.size)
    assertEquals(totalPersistables.toLong(), mailbox.acceptedMutationSequence)

    val sequences =
      consumedItems
        .mapNotNull { (it as MailboxItem.Command).queued.mutationSequence }
        .sorted()
    assertEquals((1L..totalPersistables.toLong()).toList(), sequences)
  }

  @Test
  fun sequenceOverflowProtection_preventsNegativeWrapAroundOrReuse() {
    val mailbox =
      CoalescingCommandMailbox(initialAcceptedMutationSequence = Long.MAX_VALUE - 1L)

    val atLimit = mailbox.submit(SessionCommand.InsertText("ultimo-valido"))
    assertEquals(SubmitCommandResult.Accepted(Long.MAX_VALUE), atLimit)
    assertEquals(Long.MAX_VALUE, mailbox.acceptedMutationSequence)
    assertEquals(1, mailbox.fifoQueueSize)

    // Nova mutacao persistivel eh rejeitada sem wrap-around negativo nem inclusao na fila
    val overflowed = mailbox.submit(SessionCommand.EditTitle("alem-do-limite"))
    assertEquals(SubmitCommandResult.Rejected(SubmitRejectionReason.SEQUENCE_OVERFLOW), overflowed)
    assertEquals(Long.MAX_VALUE, mailbox.acceptedMutationSequence)
    assertEquals(1, mailbox.fifoQueueSize)

    // Comandos nao persistiveis continuam aceitos para permitir flush/fechamento seguro
    val closeCmd = mailbox.submit(SessionCommand.CloseSession())
    assertEquals(SubmitCommandResult.Accepted(null), closeCmd)
    assertEquals(2, mailbox.fifoQueueSize)
  }

  @Test
  fun linearizationPoint_betweenSubmitAndPublishState_neverExposesFalseSyncedState() {
    // 1) Teste deterministico da janela de corrida: uma mutacao persistivel chega antes da conclusao de publishState
    val mailbox = CoalescingCommandMailbox()
    mailbox.submitAndConsumeAsSynced(generation = 1L, seqCount = 1)
    assertTrue(mailbox.uiState.value.isSessionFullySynced)

    // Outro produtor submete mutacao #2 exatamente antes de publishState tentar gravar Saved(1)
    val submitThreadEntered = CountDownLatch(1)
    val submitCompleted = CountDownLatch(1)
    val producerThread =
      thread(name = "concurrent-submit") {
        submitThreadEntered.countDown()
        mailbox.submit(SessionCommand.InsertText("corrida"))
        submitCompleted.countDown()
      }
    assertTrue(submitThreadEntered.await(2, TimeUnit.SECONDS))
    assertTrue(submitCompleted.await(2, TimeUnit.SECONDS))
    producerThread.join(2_000L)

    val publishedAfterRace =
      mailbox.publishState { snapshot ->
        assertTrue(snapshot.hasUnappliedPersistableMutations(forAppliedSequence = 1L))
        LinearizedStatePublication(
          candidateState = syncedSavedState(generation = 1L),
          appliedMutationSequence = 1L,
        )
      }
    assertEquals(AutosaveStatus.PendingDebounce, publishedAfterRace.autosaveStatus)
    assertFalse(publishedAfterRace.isSessionFullySynced)

    // 2) Estresse concorrente continuo: a cada publicacao, se accepted > applied no ponto de linearizacao,
    // o estado exposto nunca pode ficar isSessionFullySynced == true.
    val stressMailbox = CoalescingCommandMailbox()
    val stopSignal = java.util.concurrent.atomic.AtomicBoolean(false)
    val falseSyncObserved = java.util.concurrent.atomic.AtomicBoolean(false)

    val writerThread =
      thread(name = "stress-submitter") {
        repeat(400) { idx ->
          stressMailbox.submit(SessionCommand.InsertText("s$idx"))
          if (idx % 7 == 0) {
            Thread.yield()
          }
        }
        stopSignal.set(true)
      }

    var localApplied = 0L
    while (!stopSignal.get() || stressMailbox.fifoQueueSize > 0) {
      val item = stressMailbox.pollNext()
      if (item is MailboxItem.Command && item.queued.mutationSequence != null) {
        localApplied = item.queued.mutationSequence
      }
      stressMailbox.publishState { snapshot ->
        val candidate = syncedSavedState(generation = localApplied)
        val result =
          LinearizedStatePublication(
            candidateState = candidate,
            appliedMutationSequence = localApplied,
          )
        result
      }
      // Leitura atomica sob o proprio monitor confirma invariante linearizado
      val atomicSnapshot = stressMailbox.linearizationSnapshot()
      if (
        atomicSnapshot.acceptedMutationSequence > atomicSnapshot.appliedMutationSequence &&
          atomicSnapshot.currentUiState.isSessionFullySynced
      ) {
        falseSyncObserved.set(true)
      }
    }

    writerThread.join(5_000L)
    assertFalse("Estado falsamente sincronizado nao pode ocorrer", falseSyncObserved.get())
    assertEquals(400L, stressMailbox.acceptedMutationSequence)
    assertEquals(400L, stressMailbox.appliedMutationSequence)
    assertTrue(stressMailbox.uiState.value.isSessionFullySynced)

    // 3) Corrida simultanea com barreira de sincronizacao: em ambas as ordenacoes possiveis do lock,
    // o estado final observavel jamais permanece como Saved/Idle apos submit persistivel.
    repeat(300) {
      val raceMailbox = CoalescingCommandMailbox()
      val barrier = java.util.concurrent.CyclicBarrier(2)
      val tSubmit =
        thread(name = "race-submit") {
          barrier.await(2, TimeUnit.SECONDS)
          raceMailbox.submit(SessionCommand.InsertText("race"))
        }
      val tPublish =
        thread(name = "race-publish") {
          barrier.await(2, TimeUnit.SECONDS)
          raceMailbox.publishState(
            candidateState = syncedSavedState(generation = 0L),
            appliedMutationSequence = 0L,
          )
        }
      tSubmit.join(2_000L)
      tPublish.join(2_000L)

      assertEquals(1L, raceMailbox.acceptedMutationSequence)
      assertEquals(0L, raceMailbox.appliedMutationSequence)
      assertEquals(AutosaveStatus.PendingDebounce, raceMailbox.uiState.value.autosaveStatus)
      assertFalse(raceMailbox.uiState.value.isSessionFullySynced)
    }
  }

  @Test
  fun receiveNext_raceBetweenFirstAttemptAndSubmitBeforeWaiterRegistration_deliversItemWithoutLoss() =
    runBlocking {
      val mailbox = CoalescingCommandMailbox()
      val cmd1 = SessionCommand.EditTitle("Corrida-1")
      val cmd2 = SessionCommand.InsertText("Corrida-2")
      val enteredHook = CountDownLatch(1)
      val submitFinished = CountDownLatch(1)

      val producer =
        thread(name = "race-submit-before-waiter") {
          assertTrue(enteredHook.await(2, TimeUnit.SECONDS))
          assertEquals(SubmitCommandResult.Accepted(1L), mailbox.submit(cmd1))
          assertEquals(SubmitCommandResult.Accepted(2L), mailbox.submit(cmd2))
          submitFinished.countDown()
        }

      mailbox.beforeSuspendRegistrationHook = {
        mailbox.beforeSuspendRegistrationHook = null
        enteredHook.countDown()
        assertTrue(submitFinished.await(2, TimeUnit.SECONDS))
        assertEquals(2, mailbox.fifoQueueSize)
      }

      // Primeira tentativa vê fila vazia; produtor insere cmd1/cmd2 antes da segunda tentativa atômica.
      val firstReceived = withTimeout(2_000L) { mailbox.receiveNext() }
      producer.join(2_000L)

      assertEquals(MailboxItem.Command(QueuedSessionCommand(cmd1, 1L)), firstReceived)
      assertEquals(1, mailbox.fifoQueueSize)

      val secondReceived = withTimeout(2_000L) { mailbox.receiveNext() }
      assertEquals(MailboxItem.Command(QueuedSessionCommand(cmd2, 2L)), secondReceived)
      assertEquals(0, mailbox.fifoQueueSize)
    }

  @Test
  fun receiveNext_raceBetweenFirstAttemptAndCloseBeforeWaiterRegistration_returnsNullWithoutHanging() =
    runBlocking {
      val mailbox = CoalescingCommandMailbox()
      val enteredHook = CountDownLatch(1)
      val closeFinished = CountDownLatch(1)

      val closer =
        thread(name = "race-close-before-waiter") {
          assertTrue(enteredHook.await(2, TimeUnit.SECONDS))
          mailbox.close()
          closeFinished.countDown()
        }

      mailbox.beforeSuspendRegistrationHook = {
        mailbox.beforeSuspendRegistrationHook = null
        enteredHook.countDown()
        assertTrue(closeFinished.await(2, TimeUnit.SECONDS))
      }

      // Primeira tentativa vê aberto e vazio; close() ocorre antes da segunda tentativa -> TerminalEmpty.
      val item = withTimeout(2_000L) { mailbox.receiveNext() }
      closer.join(2_000L)

      assertNull(item)
      assertTrue(mailbox.isClosed)
    }

  @Test
  fun receiveNext_raceWithCompletionBeforeWaiterRegistration_deliversCompletionWithoutLoss() =
    runBlocking {
      val mailbox = CoalescingCommandMailbox()
      val completion =
        PersistenceCompletion.CheckpointUpsert(
          operationId = 42L,
          targetGeneration = 3L,
          attemptedCheckpointRevision = 3L,
          persistedCheckpointRevision = 3L,
          checkpointAt = 1234L,
        )
      val enteredHook = CountDownLatch(1)
      val enqueueFinished = CountDownLatch(1)

      val worker =
        thread(name = "race-completion-before-waiter") {
          assertTrue(enteredHook.await(2, TimeUnit.SECONDS))
          mailbox.enqueueCompletion(completion)
          enqueueFinished.countDown()
        }

      mailbox.beforeSuspendRegistrationHook = {
        mailbox.beforeSuspendRegistrationHook = null
        enteredHook.countDown()
        assertTrue(enqueueFinished.await(2, TimeUnit.SECONDS))
      }

      val received = withTimeout(2_000L) { mailbox.receiveNext() }
      worker.join(2_000L)

      assertEquals(MailboxItem.Completion(completion), received)
      assertEquals(0, mailbox.pendingCompletionsSize)
    }

  @Test
  fun close_withActiveBarrierAndRetainedFifo_awaitsControlledReleasePreservesOrderAndTerminates() =
    runBlocking {
      val mailbox = CoalescingCommandMailbox()
      val token = mailbox.enterBarrier(BarrierReason.CONSOLIDATION)
      val cmd1 = SessionCommand.EditTitle("Retido-1")
      val cmd2 = SessionCommand.InsertText("Retido-2")
      val cmd3 = SessionCommand.TogglePinned

      assertEquals(SubmitCommandResult.Accepted(1L), mailbox.submit(cmd1))
      assertEquals(SubmitCommandResult.Accepted(2L), mailbox.submit(cmd2))
      assertEquals(SubmitCommandResult.Accepted(3L), mailbox.submit(cmd3))
      assertEquals(3, mailbox.fifoQueueSize)
      assertEquals(0, mailbox.pendingCompletionsSize)

      mailbox.close()
      assertTrue(mailbox.isClosed)
      assertTrue(mailbox.barrierActive)

      // Novos comandos após close() são rejeitados mesmo com barreira ativa
      val rejectedAck = CompletableDeferred<SessionCommandOutcome>()
      assertEquals(
        SubmitCommandResult.Rejected(SubmitRejectionReason.MAILBOX_CLOSED),
        mailbox.submit(SessionCommand.InsertText("Apos-Close")),
      )
      assertEquals(
        SubmitCommandResult.Rejected(SubmitRejectionReason.MAILBOX_CLOSED),
        mailbox.submit(SessionCommand.FlushAutosave(ackDeferred = rejectedAck)),
      )
      assertEquals(
        SessionCommandOutcome.Rejected(SubmitRejectionReason.MAILBOX_CLOSED),
        withTimeout(2_000L) { rejectedAck.await() },
      )
      assertEquals(3L, mailbox.acceptedMutationSequence)

      // Token inválido não desbloqueia a barreira durante o encerramento
      val forgedToken = BarrierToken(id = token.id + 100L, reason = BarrierReason.CONSOLIDATION)
      assertFalse(mailbox.releaseBarrier(forgedToken))
      assertFalse(mailbox.closeAndReleaseBarrier(forgedToken))
      assertTrue(mailbox.barrierActive)

      val delivered = mutableListOf<MailboxItem>()
      val waiterRegistered = CountDownLatch(1)
      val completionDelivered = CompletableDeferred<Unit>()
      val consumer =
        async(Dispatchers.Default) {
          mailbox.beforeSuspendRegistrationHook = {
            mailbox.beforeSuspendRegistrationHook = null
            waiterRegistered.countDown()
          }
          while (true) {
            val next = mailbox.receiveNext() ?: break
            delivered.add(next)
            if (next is MailboxItem.Completion) {
              completionDelivered.complete(Unit)
            }
          }
        }

      // Garante que o consumidor entrou em espera controlada enquanto a barreira está ativa
      assertTrue(waiterRegistered.await(2, TimeUnit.SECONDS))
      assertEquals(3, mailbox.fifoQueueSize)
      assertTrue(delivered.isEmpty())

      // A operação estrutural em curso ainda conclui após close() e é entregue antes da FIFO
      val structuralCompletion =
        PersistenceCompletion.StructuralOperation(
          operationId = 77L,
          barrierToken = token,
          succeeded = true,
          terminal = false,
        )
      mailbox.enqueueCompletion(structuralCompletion)
      withTimeout(2_000L) { completionDelivered.await() }
      assertEquals(3, mailbox.fifoQueueSize)
      assertTrue(mailbox.barrierActive)

      // Liberação controlada com o token legítimo destrava a FIFO retida e conclui sem deadlock
      assertTrue(mailbox.releaseBarrier(token))
      assertFalse(mailbox.barrierActive)

      withTimeout(2_000L) { consumer.await() }

      assertEquals(4, delivered.size)
      assertEquals(MailboxItem.Completion(structuralCompletion), delivered[0])
      assertEquals(MailboxItem.Command(QueuedSessionCommand(cmd1, 1L)), delivered[1])
      assertEquals(MailboxItem.Command(QueuedSessionCommand(cmd2, 2L)), delivered[2])
      assertEquals(MailboxItem.Command(QueuedSessionCommand(cmd3, 3L)), delivered[3])
      assertEquals(0, mailbox.fifoQueueSize)
      assertEquals(0, mailbox.pendingCompletionsSize)
    }

  @Test
  fun closeAndReleaseBarrier_withActiveBarrierAndRetainedFifo_unlocksDrainsAndTerminatesWithoutDeadlock() =
    runBlocking {
      val mailbox = CoalescingCommandMailbox()
      val token = mailbox.enterBarrier(BarrierReason.DISCARD_DRAFT)
      val cmd1 = SessionCommand.InsertText("Antes-1")
      val cmd2 = SessionCommand.InsertText("Antes-2")
      assertEquals(SubmitCommandResult.Accepted(1L), mailbox.submit(cmd1))
      assertEquals(SubmitCommandResult.Accepted(2L), mailbox.submit(cmd2))

      val delivered = mutableListOf<MailboxItem>()
      val consumerWaiting = CountDownLatch(1)
      val consumer =
        async(Dispatchers.Default) {
          mailbox.beforeSuspendRegistrationHook = {
            mailbox.beforeSuspendRegistrationHook = null
            consumerWaiting.countDown()
          }
          while (true) {
            val next = mailbox.receiveNext() ?: break
            delivered.add(next)
          }
        }

      assertTrue(consumerWaiting.await(2, TimeUnit.SECONDS))

      // Token divergente fecha a aceitação, mas NÃO libera a barreira indevidamente
      val wrongToken = BarrierToken(id = token.id + 50L, reason = BarrierReason.DISCARD_DRAFT)
      assertFalse(mailbox.closeAndReleaseBarrier(wrongToken))
      assertTrue(mailbox.isClosed)
      assertTrue(mailbox.barrierActive)
      assertEquals(2, mailbox.fifoQueueSize)

      // Token legítimo libera atomicamente a barreira e acorda o consumidor para drenar FIFO e encerrar
      assertTrue(mailbox.closeAndReleaseBarrier(token))
      assertFalse(mailbox.barrierActive)
      withTimeout(2_000L) { consumer.await() }

      assertEquals(
        listOf(
          MailboxItem.Command(QueuedSessionCommand(cmd1, 1L)),
          MailboxItem.Command(QueuedSessionCommand(cmd2, 2L)),
        ),
        delivered,
      )
      assertEquals(
        SubmitCommandResult.Rejected(SubmitRejectionReason.MAILBOX_CLOSED),
        mailbox.submit(SessionCommand.EditTitle("Rejeitado")),
      )
    }

  @Test
  fun close_withActiveBarrierAndEmptyQueues_waitsForStructuralCompletionBeforeReturningNull() =
    runBlocking {
      val mailbox = CoalescingCommandMailbox()
      val token = mailbox.enterBarrier(BarrierReason.CONFLICT_RESOLUTION)
      mailbox.close()

      val consumerWaiting = CountDownLatch(1)
      val received = mutableListOf<MailboxItem>()
      val consumer =
        async(Dispatchers.Default) {
          mailbox.beforeSuspendRegistrationHook = {
            mailbox.beforeSuspendRegistrationHook = null
            consumerWaiting.countDown()
          }
          while (true) {
            val item = mailbox.receiveNext() ?: break
            received.add(item)
          }
        }

      assertTrue(consumerWaiting.await(2, TimeUnit.SECONDS))
      assertTrue(consumer.isActive)

      val completion =
        PersistenceCompletion.StructuralOperation(
          operationId = 88L,
          barrierToken = token,
          succeeded = true,
          terminal = true,
        )
      mailbox.enqueueCompletion(completion)
      assertTrue(mailbox.releaseBarrier(token))

      withTimeout(2_000L) { consumer.await() }
      assertEquals(listOf(MailboxItem.Completion(completion)), received)
    }

  @Test
  fun receiveNext_concurrentCancellationDuringSecondAttempt_requeuesItemWithoutLoss() =
    runBlocking {
      val mailbox = CoalescingCommandMailbox()
      val cmd1 = SessionCommand.EditTitle("Preservado-no-cancelamento")
      val cmd2 = SessionCommand.InsertText("Posterior")
      val hookEntered = CountDownLatch(1)
      val allowHookExit = CountDownLatch(1)

      mailbox.beforeSuspendRegistrationHook = {
        mailbox.beforeSuspendRegistrationHook = null
        hookEntered.countDown()
        assertTrue(allowHookExit.await(2, TimeUnit.SECONDS))
      }

      val cancelledConsumer =
        launch(Dispatchers.Default) {
          mailbox.receiveNext()
        }

      assertTrue(hookEntered.await(2, TimeUnit.SECONDS))
      assertEquals(SubmitCommandResult.Accepted(1L), mailbox.submit(cmd1))
      assertEquals(SubmitCommandResult.Accepted(2L), mailbox.submit(cmd2))
      cancelledConsumer.cancel()
      allowHookExit.countDown()
      withTimeout(2_000L) { cancelledConsumer.join() }

      // Mesmo cancelada durante a janela da segunda tentativa, cmd1 e cmd2 permanecem intactos na ordem exata
      assertEquals(2, mailbox.fifoQueueSize)
      val first = withTimeout(2_000L) { mailbox.receiveNext() }
      val second = withTimeout(2_000L) { mailbox.receiveNext() }
      assertEquals(MailboxItem.Command(QueuedSessionCommand(cmd1, 1L)), first)
      assertEquals(MailboxItem.Command(QueuedSessionCommand(cmd2, 2L)), second)
      assertEquals(0, mailbox.fifoQueueSize)
    }

  @Test
  fun receiveNext_cancellationAfterImmediateResumeBeforeDelivery_triggersOnCancellationAndRequeuesWithoutLossOrDuplication() =
    runBlocking {
      val mailbox = CoalescingCommandMailbox()
      val cmd1 = SessionCommand.EditTitle("Item-1-Requeue")
      val cmd2 = SessionCommand.InsertText("Item-2-Ordem")
      val beforeSuspendEntered = CountDownLatch(1)
      val allowBeforeSuspendExit = CountDownLatch(1)
      val afterResumeEntered = CountDownLatch(1)
      val allowAfterResumeExit = CountDownLatch(1)

      var deliveredToOriginal: MailboxItem? = null
      var cancellationObserved = false

      mailbox.beforeSuspendRegistrationHook = {
        mailbox.beforeSuspendRegistrationHook = null
        beforeSuspendEntered.countDown()
        assertTrue(allowBeforeSuspendExit.await(2, TimeUnit.SECONDS))
      }
      mailbox.afterImmediateResumeHook = {
        mailbox.afterImmediateResumeHook = null
        afterResumeEntered.countDown()
        assertTrue(allowAfterResumeExit.await(2, TimeUnit.SECONDS))
      }

      val originalConsumer =
        launch(Dispatchers.Default) {
          try {
            deliveredToOriginal = mailbox.receiveNext()
          } catch (cancelled: CancellationException) {
            cancellationObserved = true
            throw cancelled
          }
        }

      // 1. Aguarda a primeira tentativa ver fila vazia e pausar antes do registro em waiters
      assertTrue(beforeSuspendEntered.await(2, TimeUnit.SECONDS))
      assertEquals(SubmitCommandResult.Accepted(1L), mailbox.submit(cmd1))
      assertEquals(SubmitCommandResult.Accepted(2L), mailbox.submit(cmd2))
      assertEquals(2, mailbox.fifoQueueSize)

      // 2. Libera para executar a segunda tentativa, retirar cmd1 e chamar cont.resume(resolved) ativo
      allowBeforeSuspendExit.countDown()
      assertTrue(afterResumeEntered.await(2, TimeUnit.SECONDS))

      // Nesse ponto exato: cmd1 já foi retirado da fila (fifoQueueSize == 1), cont.resume(resolved)
      // já foi invocado, mas suspendCancellableCoroutine ainda não entregou o valor ao consumidor.
      assertEquals(1, mailbox.fifoQueueSize)

      // 3. Cancela o consumidor nessa janela e libera o retorno da continuação
      originalConsumer.cancel()
      allowAfterResumeExit.countDown()
      withTimeout(2_000L) { originalConsumer.join() }

      // Verifica cancelamento efetivo, ausência de entrega ao consumidor cancelado e requeue via onCancellation
      assertTrue(originalConsumer.isCancelled)
      assertTrue(cancellationObserved)
      assertNull(deliveredToOriginal)
      assertEquals(2, mailbox.fifoQueueSize)

      // 4. Novo consumidor recebe cmd1 primeiro, depois cmd2, sem perda nem duplicação
      val firstReceived = withTimeout(2_000L) { mailbox.receiveNext() }
      val secondReceived = withTimeout(2_000L) { mailbox.receiveNext() }
      mailbox.close()
      val terminal = withTimeout(2_000L) { mailbox.receiveNext() }

      assertEquals(MailboxItem.Command(QueuedSessionCommand(cmd1, 1L)), firstReceived)
      assertEquals(MailboxItem.Command(QueuedSessionCommand(cmd2, 2L)), secondReceived)
      assertNull(terminal)
      assertEquals(0, mailbox.fifoQueueSize)
    }

  @Test
  fun closeAndReleaseBarrier_onlyAfterDefinitiveStructuralCompletion_preservesOperationAndDrainsFifo() =
    runBlocking {
      val mailbox = CoalescingCommandMailbox()
      val token = mailbox.enterBarrier(BarrierReason.CONSOLIDATION)
      val retainedCmd = SessionCommand.InsertText("Edicao-pendente")
      assertEquals(SubmitCommandResult.Accepted(1L), mailbox.submit(retainedCmd))

      val delivered = mutableListOf<MailboxItem>()
      val structuralCompletedSignal = CompletableDeferred<Unit>()
      val waiterRegistered = CountDownLatch(1)

      val consumer =
        async(Dispatchers.Default) {
          mailbox.beforeSuspendRegistrationHook = {
            mailbox.beforeSuspendRegistrationHook = null
            waiterRegistered.countDown()
          }
          while (true) {
            val item = mailbox.receiveNext() ?: break
            delivered.add(item)
            if (item is MailboxItem.Completion) {
              structuralCompletedSignal.complete(Unit)
            }
          }
        }

      assertTrue(waiterRegistered.await(2, TimeUnit.SECONDS))

      // Enquanto a operação estrutural não concluiu definitivamente, close() NÃO quebra a barreira
      mailbox.close()
      assertTrue(mailbox.isClosed)
      assertTrue(mailbox.barrierActive)
      assertEquals(1, mailbox.fifoQueueSize)

      // Operação estrutural conclui definitivamente -> consumidor recebe a conclusão com prioridade
      val completion =
        PersistenceCompletion.StructuralOperation(
          operationId = 99L,
          barrierToken = token,
          succeeded = true,
          terminal = false,
        )
      mailbox.enqueueCompletion(completion)
      withTimeout(2_000L) { structuralCompletedSignal.await() }
      assertTrue(mailbox.barrierActive)

      // Somente após o término definitivo da operação estrutural o detentor chama closeAndReleaseBarrier(token)
      assertTrue(mailbox.closeAndReleaseBarrier(token))
      assertFalse(mailbox.barrierActive)

      withTimeout(2_000L) { consumer.await() }
      assertEquals(
        listOf(
          MailboxItem.Completion(completion),
          MailboxItem.Command(QueuedSessionCommand(retainedCmd, 1L)),
        ),
        delivered,
      )
    }

  private fun CoalescingCommandMailbox.submitAndConsumeAsSynced(generation: Long, seqCount: Int) {
    val startApplied = appliedMutationSequence
    repeat(seqCount) { idx ->
      submit(SessionCommand.InsertText("init-$idx"))
      pollNext()
    }
    publishState(
      candidateState = syncedSavedState(generation = generation),
      appliedMutationSequence = startApplied + seqCount,
    )
  }

  private fun syncedSavedState(generation: Long): NoteEditorUiState =
    NoteEditorUiState(
      phase = NoteEditorSessionPhase.EDITING,
      sessionIdentity =
        NoteEditorSessionIdentity(
          draftId = NoteRecoveryDraftKeys.forNewNote("mailbox-test"),
          sessionId = "mailbox-test",
          targetNoteId = null,
        ),
      stateGeneration = generation,
      lastSyncedGeneration = generation,
      autosaveStatus = AutosaveStatus.Saved(syncedGeneration = generation, checkpointAt = 999L),
      isConsolidating = false,
      isSessionTerminated = false,
    )
}
