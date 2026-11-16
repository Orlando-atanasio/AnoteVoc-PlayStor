package com.example.ui.viewmodel

import com.example.core.time.ClockProvider
import com.example.domain.editor.NoteEditorSessionIdentity
import com.example.domain.model.FormatSpan
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NoteContentState
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.NoteRecoveryDraft
import com.example.domain.model.NoteRecoveryDraftKeys
import com.example.domain.model.ParagraphBlock
import com.example.domain.model.TextFormatStyle
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplacementHandoffTrackerTest {

  private class MutableClock(initialMillis: Long = 1_700_000_000_000L) : ClockProvider {
    private val current = AtomicLong(initialMillis)

    override fun nowMillis(): Long = current.incrementAndGet()
  }

  private fun oldIdentity(
    noteId: Long = 42L,
    sessionId: String = "old-sess-1",
  ): NoteEditorSessionIdentity =
    NoteEditorSessionIdentity(
      draftId = NoteRecoveryDraftKeys.forExistingNote(noteId, sessionId),
      sessionId = sessionId,
      targetNoteId = noteId,
    )

  private fun newIdentity(sessionId: String = "new-sess-1"): NoteEditorSessionIdentity =
    NoteEditorSessionIdentity(
      draftId = NoteRecoveryDraftKeys.forNewNote(sessionId),
      sessionId = sessionId,
      targetNoteId = null,
    )

  private fun sampleDoc(text: String = "Conteúdo convertido"): NoteContentDocument =
    NoteContentDocument(
      blocks =
        listOf(
          ParagraphBlock(
            id = "b1",
            text = text,
            spans = listOf(FormatSpan(start = 0, end = text.length.coerceAtLeast(1).coerceAtMost(8), style = TextFormatStyle.BOLD)),
          )
        )
    )

  private fun newPayload(
    identity: NoteEditorSessionIdentity = newIdentity(),
    title: String = "Título salvo como nova",
    content: NoteContentDocument = sampleDoc(),
    folderId: Long? = 10L,
    initialFolderId: Long? = 10L,
    fallbackSourceFolderId: Long? = null,
    paperStyle: NotePaperStyle = NotePaperStyle.SEM_LINHAS,
    initialPaperStyle: NotePaperStyle? = NotePaperStyle.SEM_LINHAS,
    isPinned: Boolean = true,
  ): CheckpointSavePayload =
    CheckpointSavePayload.fromIdentity(
      identity = identity,
      folderId = folderId,
      initialFolderId = initialFolderId,
      fallbackSourceFolderId = fallbackSourceFolderId,
      isPinned = isPinned,
      title = title,
      content = content,
      paperStyle = paperStyle,
      initialPaperStyle = initialPaperStyle,
      baseRevision = 0L,
      baseNoteUpdatedAt = null,
    )

  private fun draftFromPayload(
    payload: CheckpointSavePayload,
    revision: Long = 1L,
    checkpointAt: Long = 1_700_000_123_456L,
  ): NoteRecoveryDraft =
    NoteRecoveryDraft(
      draftId = payload.draftId,
      targetNoteId = payload.targetNoteId,
      sessionId = payload.sessionId,
      folderId = payload.folderId,
      initialFolderId = payload.initialFolderId,
      fallbackSourceFolderId = payload.fallbackSourceFolderId,
      isPinned = payload.isPinned,
      title = payload.title,
      contentState = NoteContentState.Valid(payload.content),
      paperStyle = payload.paperStyle,
      initialPaperStyle = payload.initialPaperStyle,
      baseRevision = payload.baseRevision,
      checkpointRevision = revision,
      checkpointAt = checkpointAt,
      baseNoteUpdatedAt = payload.baseNoteUpdatedAt,
    )

  @Test
  fun registerPending_recordsExpectedFieldsDigestAndSnapshotCounts() {
    val tracker = ReplacementHandoffTracker(MutableClock())
    val oldId = oldIdentity()
    val payload = newPayload()

    val record = tracker.registerPending(oldId, payload, newDraftRevision = 1L)

    assertEquals(oldId.draftId, record.oldDraftId)
    assertEquals(oldId.sessionId, record.oldSessionId)
    assertEquals(42L, record.oldTargetNoteId)
    assertEquals(payload.draftId, record.candidateNewDraftId)
    assertEquals(payload.sessionId, record.candidateNewSessionId)
    assertEquals(1L, record.expectedCheckpointRevision)
    assertEquals(ReplacementHandoffStatus.PENDING, record.status)
    assertEquals(ReplacementHandoffTracker.computeContentDigest(payload.content), record.expectedContentDigest)

    val found = tracker.findActiveCandidate("  ${oldId.draftId}  ")
    assertEquals(record, found)

    val snap = tracker.snapshot()
    assertEquals(1, snap.activeCount)
    assertEquals(1, snap.pendingCount)
    assertEquals(0, snap.resolvedCount)
  }

  @Test
  fun registerPending_rejectsInvalidPreconditionsAndConflictingDuplicates() {
    val tracker = ReplacementHandoffTracker(MutableClock())
    val oldId = oldIdentity()
    val payload = newPayload()

    assertThrows(IllegalArgumentException::class.java) {
      tracker.registerPending(newIdentity("source-new"), payload, newDraftRevision = 1L)
    }
    assertThrows(IllegalArgumentException::class.java) {
      val invalidTargetPayload =
        CheckpointSavePayload.fromIdentity(
          identity = oldIdentity(noteId = 99L, sessionId = "dest-existing"),
          folderId = null,
          fallbackSourceFolderId = null,
          isPinned = false,
          title = "X",
          content = sampleDoc(),
          paperStyle = NotePaperStyle.LINHAS,
          baseRevision = 1L,
          baseNoteUpdatedAt = 100L,
        )
      tracker.registerPending(oldId, invalidTargetPayload, newDraftRevision = 1L)
    }
    assertThrows(IllegalArgumentException::class.java) {
      tracker.registerPending(oldId, payload, newDraftRevision = 0L)
    }

    val first = tracker.registerPending(oldId, payload, newDraftRevision = 1L)
    val idempotent = tracker.registerPending(oldId, payload, newDraftRevision = 1L)
    assertEquals(first, idempotent)

    assertThrows(IllegalStateException::class.java) {
      tracker.registerPending(oldId, payload.copyTitle("Outro título"), newDraftRevision = 1L)
    }
    assertThrows(IllegalStateException::class.java) {
      val secondOld = oldIdentity(noteId = 77L, sessionId = "old-sess-2")
      tracker.registerPending(secondOld, payload, newDraftRevision = 1L)
    }
  }

  @Test
  fun clearPreserveAndMarkResolved_respectExactHandoffIdAndStatusTransitions() {
    val tracker = ReplacementHandoffTracker(MutableClock())
    val oldId = oldIdentity()
    val payload = newPayload()
    val record = tracker.registerPending(oldId, payload, newDraftRevision = 1L)

    assertFalse(tracker.preservePending(oldId.draftId, record.handoffId + 99L))
    assertTrue(tracker.preservePending(oldId.draftId, record.handoffId))
    assertEquals(ReplacementHandoffStatus.PENDING, tracker.findActiveCandidate(oldId.draftId)?.status)

    assertFalse(tracker.markResolved(oldId.draftId, record.handoffId + 99L))
    assertTrue(tracker.markResolved(oldId.draftId, record.handoffId))
    assertEquals(ReplacementHandoffStatus.RESOLVED_TO_NEW, tracker.findActiveCandidate(oldId.draftId)?.status)
    assertFalse(tracker.preservePending(oldId.draftId, record.handoffId))

    assertFalse(tracker.clearByHandoffId(oldId.draftId, record.handoffId + 1L))
    assertNotNull(tracker.findActiveCandidate(oldId.draftId))
    assertTrue(tracker.clearByHandoffId(oldId.draftId, record.handoffId))
    assertNull(tracker.findActiveCandidate(oldId.draftId))
    assertEquals(0, tracker.snapshot().activeCount)
  }

  @Test
  fun verifyAndResolveCandidate_acceptsEquivalentCanonicalContentAndEvictsMissingOrDivergent() {
    val tracker = ReplacementHandoffTracker(MutableClock())
    val oldId = oldIdentity()
    // Documento com spans adjacentes para validar canonicalização idêntica ao NoteEditorSessionEvaluator.
    val rawDoc =
      NoteContentDocument(
        blocks =
          listOf(
            ParagraphBlock(
              id = "p1",
              text = "ABCDEF",
              spans =
                listOf(
                  FormatSpan(0, 3, TextFormatStyle.BOLD),
                  FormatSpan(3, 6, TextFormatStyle.BOLD),
                ),
            )
          )
      )
    val payload = newPayload(content = rawDoc)
    val record = tracker.registerPending(oldId, payload, newDraftRevision = 1L)

    val matchingDraft =
      draftFromPayload(
        payload =
          newPayload(
            content = NoteEditorSessionEvaluatorHelper.canonical(rawDoc)
          ),
        revision = 1L,
      )
    val verified = tracker.verifyAndResolveCandidate(record, matchingDraft)
    assertTrue(verified is RedirectVerificationResult.Verified)
    assertEquals(ReplacementHandoffStatus.RESOLVED_TO_NEW, tracker.findActiveCandidate(oldId.draftId)?.status)

    // Novo ciclo: ausente é rejeitado e removido.
    tracker.clearByHandoffId(oldId.draftId, record.handoffId)
    val recMissing = tracker.registerPending(oldId, payload, newDraftRevision = 1L)
    val missingResult = tracker.verifyAndResolveCandidate(recMissing, null)
    assertTrue(missingResult is RedirectVerificationResult.RejectedMissing)
    assertNull(tracker.findActiveCandidate(oldId.draftId))

    // Divergência em título, conteúdo, pasta, papel, pin, revisão ou baseNoteUpdatedAt é rejeitada e removida.
    val divergentCases =
      listOf(
        matchingDraft.copy(title = "Alterado"),
        matchingDraft.copy(
          contentState =
            NoteContentState.Valid(
              NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Alterado")))
            )
        ),
        matchingDraft.copy(folderId = null, fallbackSourceFolderId = 10L),
        matchingDraft.copy(paperStyle = NotePaperStyle.PONTILHADO),
        matchingDraft.copy(isPinned = false),
        matchingDraft.copy(checkpointRevision = 2L),
        matchingDraft.copy(targetNoteId = 42L),
        matchingDraft.copy(baseRevision = 1L),
        matchingDraft.copy(baseNoteUpdatedAt = 500L),
      )

    for ((idx, divergentDraft) in divergentCases.withIndex()) {
      val rec =
        tracker.registerPending(
          oldIdentity(noteId = 100L + idx, sessionId = "old-div-$idx"),
          newPayload(identity = newIdentity("new-div-$idx"), content = rawDoc),
          newDraftRevision = 1L,
        )
      val res = tracker.verifyAndResolveCandidate(rec, divergentDraft.copy(draftId = rec.candidateNewDraftId, sessionId = rec.candidateNewSessionId))
      assertTrue("Caso divergente $idx deveria ser rejeitado", res is RedirectVerificationResult.RejectedDivergent)
      assertNull("Associação divergente $idx deveria ser removida", tracker.findActiveCandidate(rec.oldDraftId))
    }
  }

  @Test
  fun invalidateCandidateDraft_removesEntrySoStaleRedirectCannotBeReusedAfterSubsequentMutations() {
    val tracker = ReplacementHandoffTracker(MutableClock())
    val oldId = oldIdentity()
    val payload = newPayload()
    tracker.registerPending(oldId, payload, newDraftRevision = 1L)

    assertTrue(tracker.invalidateCandidateDraft(payload.draftId))
    assertFalse(tracker.invalidateCandidateDraft(payload.draftId))
    assertNull(tracker.findActiveCandidate(oldId.draftId))
    assertEquals(0, tracker.snapshot().activeCount)
  }

  @Test
  fun concurrentOperationsAcrossThreadsMaintainInvariantWithoutCorruption() {
    val tracker = ReplacementHandoffTracker(MutableClock())
    val workerCount = 12
    val iterationsPerWorker = 40
    val barrier = CyclicBarrier(workerCount)
    val errors = ConcurrentLinkedQueue<Throwable>()

    val threads =
      (0 until workerCount).map { workerIdx ->
        thread(start = true) {
          try {
            barrier.await(5, TimeUnit.SECONDS)
            repeat(iterationsPerWorker) { iter ->
              val oldId = oldIdentity(noteId = (workerIdx + 1).toLong(), sessionId = "w-$workerIdx-$iter")
              val newId = newIdentity(sessionId = "nw-$workerIdx-$iter")
              val payload = newPayload(identity = newId, title = "Nota $workerIdx-$iter")
              val rec = tracker.registerPending(oldId, payload, newDraftRevision = 1L)
              val active = tracker.findActiveCandidate(oldId.draftId)
              if (active == null) {
                throw AssertionError("Registro recém-inserido ausente para ${oldId.draftId}")
              }
              if (iter % 3 == 0) {
                tracker.verifyAndResolveCandidate(rec, draftFromPayload(payload, 1L))
              } else if (iter % 3 == 1) {
                tracker.preservePending(oldId.draftId, rec.handoffId)
                tracker.invalidateCandidateDraft(payload.draftId)
              } else {
                tracker.clearByHandoffId(oldId.draftId, rec.handoffId)
              }
              val snap = tracker.snapshot()
              if (snap.activeCount != snap.pendingCount + snap.resolvedCount) {
                throw AssertionError("Invariante de snapshot violado: $snap")
              }
            }
          } catch (t: Throwable) {
            errors.add(t)
          }
        }
      }

    threads.forEach { it.join(10_000) }
    assertTrue("Erros concorrentes encontrados: $errors", errors.isEmpty())
    val finalSnap = tracker.snapshot()
    assertEquals(finalSnap.activeCount, finalSnap.pendingCount + finalSnap.resolvedCount)
    assertEquals(0, finalSnap.pendingCount)
  }

  private fun CheckpointSavePayload.copyTitle(newTitle: String): CheckpointSavePayload =
    CheckpointSavePayload.fromIdentity(
      identity =
        NoteEditorSessionIdentity(
          draftId = draftId,
          sessionId = sessionId,
          targetNoteId = targetNoteId,
        ),
      folderId = folderId,
      initialFolderId = initialFolderId,
      fallbackSourceFolderId = fallbackSourceFolderId,
      isPinned = isPinned,
      title = newTitle,
      content = content,
      paperStyle = paperStyle,
      initialPaperStyle = initialPaperStyle,
      baseRevision = baseRevision,
      baseNoteUpdatedAt = baseNoteUpdatedAt,
    )

  private object NoteEditorSessionEvaluatorHelper {
    fun canonical(doc: NoteContentDocument): NoteContentDocument =
      com.example.domain.editor.NoteEditorSessionEvaluator.canonicalizeDocument(doc)
  }
}
