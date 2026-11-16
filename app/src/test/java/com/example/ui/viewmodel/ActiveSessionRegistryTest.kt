package com.example.ui.viewmodel

import com.example.domain.editor.NoteEditorSessionIdentity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveSessionRegistryTest {

  @Test
  fun reserve_validSessionWithoutDraftId_reservesOnlySessionAndPreservesNullDraft() {
    val registry = ActiveSessionRegistry()
    val identity = RegisteredSessionIdentity.withoutCheckpoint(sessionId = " sess-1 ", targetNoteId = null)

    assertEquals("sess-1", identity.sessionId)
    assertEquals(null, identity.draftId)

    val result = registry.reserve(identity)
    assertTrue(result is SessionReservationResult.Reserved)
    val lease = (result as SessionReservationResult.Reserved).lease

    assertTrue(registry.isLeaseActive(lease))
    assertTrue(registry.isSessionReserved("sess-1"))
    assertFalse(registry.isDraftReserved("new_sess-1"))
    assertEquals(setOf("sess-1"), registry.snapshot().reservedSessionIds)
    assertTrue(registry.snapshot().reservedDraftIds.isEmpty())
  }

  @Test
  fun reserve_validSessionWithCheckpoint_reservesSessionAndDraftAtomically() {
    val registry = ActiveSessionRegistry()
    val sessionIdentity =
      NoteEditorSessionIdentity(
        draftId = "note_42_sess-42",
        sessionId = "sess-42",
        targetNoteId = 42L,
      )

    val result = registry.reserve(sessionIdentity)
    assertTrue(result is SessionReservationResult.Reserved)
    val lease = (result as SessionReservationResult.Reserved).lease

    assertTrue(registry.isLeaseActive(lease))
    assertTrue(registry.isSessionReserved("sess-42"))
    assertTrue(registry.isDraftReserved("note_42_sess-42"))
  }

  @Test
  fun registeredSessionIdentity_rejectsInvalidOrInconsistentKeysAccordingToDomainRules() {
    assertThrows(IllegalArgumentException::class.java) {
      RegisteredSessionIdentity.withoutCheckpoint(sessionId = "   ")
    }
    assertThrows(IllegalArgumentException::class.java) {
      RegisteredSessionIdentity.withoutCheckpoint(sessionId = "NEW")
    }
    assertThrows(IllegalArgumentException::class.java) {
      RegisteredSessionIdentity(sessionId = "sess-1", draftId = "note_99_sess-1", targetNoteId = 10L)
    }
    assertThrows(IllegalArgumentException::class.java) {
      RegisteredSessionIdentity.withoutCheckpoint(sessionId = "sess-1", targetNoteId = 0L)
    }
  }

  @Test
  fun reserve_sessionIdCollisionWithFreeDraftId_rejectsWithoutPartialReservation() {
    val registry = ActiveSessionRegistry()
    val first =
      registry.reserve(
        RegisteredSessionIdentity.withoutCheckpoint(sessionId = "sess-dup", targetNoteId = null)
      ) as SessionReservationResult.Reserved

    val conflicting =
      registry.reserve(
        NoteEditorSessionIdentity(
          draftId = "new_sess-dup",
          sessionId = "sess-dup",
          targetNoteId = null,
        )
      )

    assertTrue(conflicting is SessionReservationResult.Conflict)
    val conflict = conflicting as SessionReservationResult.Conflict
    assertTrue(conflict.sessionIdConflict)
    assertFalse(conflict.draftIdConflict)
    // Garante nenhuma reserva parcial de draftId após conflito em sessionId.
    assertFalse(registry.isDraftReserved("new_sess-dup"))
    assertTrue(registry.isLeaseActive(first.lease))
  }

  @Test
  fun reserve_draftIdCollisionIsRejectedEvenIfSessionLookupDiffers() {
    val registry = ActiveSessionRegistry()
    registry.reserve(
      NoteEditorSessionIdentity(
        draftId = "note_7_alpha",
        sessionId = "alpha",
        targetNoteId = 7L,
      )
    )

    val second =
      registry.reserve(
        NoteEditorSessionIdentity(
          draftId = "note_7_alpha",
          sessionId = "alpha",
          targetNoteId = 7L,
        )
      )

    assertTrue(second is SessionReservationResult.Conflict)
    val conflict = second as SessionReservationResult.Conflict
    assertTrue(conflict.sessionIdConflict)
    assertTrue(conflict.draftIdConflict)
  }

  @Test
  fun reserve_sameIdentityBlockedAndReleasedAfterLeaseRelease() {
    val registry = ActiveSessionRegistry()
    val identity =
      NoteEditorSessionIdentity(
        draftId = "new_s1",
        sessionId = "s1",
        targetNoteId = null,
      )

    val first = registry.reserve(identity) as SessionReservationResult.Reserved
    assertTrue(registry.reserve(identity) is SessionReservationResult.Conflict)

    assertTrue(registry.release(first.lease))
    assertFalse(registry.isLeaseActive(first.lease))
    assertFalse(registry.isSessionReserved("s1"))
    assertFalse(registry.isDraftReserved("new_s1"))

    val reReserved = registry.reserve(identity)
    assertTrue(reReserved is SessionReservationResult.Reserved)
  }

  @Test
  fun release_rejectsInactiveDuplicateOrForeignRegistryLease() {
    val registryA = ActiveSessionRegistry()
    val registryB = ActiveSessionRegistry()

    val leaseA =
      (registryA.reserve(
        RegisteredSessionIdentity.withoutCheckpoint("sess-a")
      ) as SessionReservationResult.Reserved).lease

    assertFalse(registryB.release(leaseA))
    assertFalse(registryB.isLeaseActive(leaseA))
    assertTrue(registryA.isLeaseActive(leaseA))

    assertTrue(registryA.release(leaseA))
    assertFalse(registryA.release(leaseA))
  }

  @Test
  fun reserveIdentityTransfer_upgradeWithoutCheckpointToCheckpointSharingSessionId_succeedsAndCommits() {
    val registry = ActiveSessionRegistry()
    val sourceIdentity = RegisteredSessionIdentity.withoutCheckpoint(sessionId = "s-up", targetNoteId = 15L)
    val targetIdentity = RegisteredSessionIdentity(sessionId = "s-up", draftId = "note_15_s-up", targetNoteId = 15L)

    val sourceLease =
      (registry.reserve(sourceIdentity) as SessionReservationResult.Reserved).lease

    val transferResult = registry.reserveIdentityTransfer(sourceLease, targetIdentity)
    assertTrue(transferResult is ReserveTransferResult.Reserved)
    val token = (transferResult as ReserveTransferResult.Reserved).transferToken

    // Durante reserva provisória, source permanece ativo e target já está reservado.
    assertTrue(registry.isLeaseActive(sourceLease))
    assertTrue(registry.isSessionReserved("s-up"))
    assertTrue(registry.isDraftReserved("note_15_s-up"))
    assertEquals(1, registry.snapshot().pendingTransferCount)

    // Outra sessão não pode tomar nem sessionId nem draftId provisório.
    assertTrue(registry.reserve(targetIdentity) is SessionReservationResult.Conflict)

    val commitResult = registry.commitIdentityTransfer(token)
    assertTrue(commitResult is CommitTransferResult.Committed)
    val committedLease = (commitResult as CommitTransferResult.Committed).newLease

    assertFalse(registry.isLeaseActive(sourceLease))
    assertTrue(registry.isLeaseActive(committedLease))
    assertTrue(registry.isSessionReserved("s-up"))
    assertTrue(registry.isDraftReserved("note_15_s-up"))
    assertEquals(0, registry.snapshot().pendingTransferCount)
  }

  @Test
  fun reserveAndCommitIdentityTransfer_disjointIdentities_swapsAtomicallyWithoutWindowOfInconsistency() {
    val registry = ActiveSessionRegistry()
    val oldIdentity =
      RegisteredSessionIdentity(sessionId = "old-s", draftId = "note_5_old-s", targetNoteId = 5L)
    val newIdentity =
      RegisteredSessionIdentity(sessionId = "new-s", draftId = "new_new-s", targetNoteId = null)

    val sourceLease = (registry.reserve(oldIdentity) as SessionReservationResult.Reserved).lease
    val token =
      (registry.reserveIdentityTransfer(sourceLease, newIdentity) as ReserveTransferResult.Reserved)
        .transferToken

    // Enquanto pendente, ambas as identidades permanecem protegidas contra terceiros.
    assertTrue(registry.isSessionReserved("old-s"))
    assertTrue(registry.isDraftReserved("note_5_old-s"))
    assertTrue(registry.isSessionReserved("new-s"))
    assertTrue(registry.isDraftReserved("new_new-s"))

    val committed =
      (registry.commitIdentityTransfer(token) as CommitTransferResult.Committed).newLease

    assertFalse(registry.isLeaseActive(sourceLease))
    assertTrue(registry.isLeaseActive(committed))
    assertFalse(registry.isSessionReserved("old-s"))
    assertFalse(registry.isDraftReserved("note_5_old-s"))
    assertTrue(registry.isSessionReserved("new-s"))
    assertTrue(registry.isDraftReserved("new_new-s"))

    // Identidade antiga pode ser reservada novamente apenas depois do commit.
    assertTrue(registry.reserve(oldIdentity) is SessionReservationResult.Reserved)
  }

  @Test
  fun reserveIdentityTransfer_rejectsWhenTargetSessionOrDraftAlreadyOwnedByAnotherLease() {
    val registry = ActiveSessionRegistry()
    val lease1 =
      (registry.reserve(
        RegisteredSessionIdentity(sessionId = "s1", draftId = "note_1_s1", targetNoteId = 1L)
      ) as SessionReservationResult.Reserved).lease
    registry.reserve(
      RegisteredSessionIdentity(sessionId = "s2", draftId = "new_s2", targetNoteId = null)
    )

    val conflictResult =
      registry.reserveIdentityTransfer(
        currentLease = lease1,
        targetIdentity =
          RegisteredSessionIdentity(sessionId = "s2", draftId = "new_s2", targetNoteId = null),
      )

    assertTrue(conflictResult is ReserveTransferResult.TargetConflict)
    val conflict = conflictResult as ReserveTransferResult.TargetConflict
    assertTrue(conflict.sessionIdConflict)
    assertTrue(conflict.draftIdConflict)
    assertTrue(registry.isLeaseActive(lease1))
    assertEquals(0, registry.snapshot().pendingTransferCount)
  }

  @Test
  fun reserveIdentityTransfer_blocksConcurrentSecondTransferFromSameSourceLease() {
    val registry = ActiveSessionRegistry()
    val lease =
      (registry.reserve(
        RegisteredSessionIdentity(sessionId = "s1", draftId = "note_1_s1", targetNoteId = 1L)
      ) as SessionReservationResult.Reserved).lease

    val first =
      registry.reserveIdentityTransfer(
        lease,
        RegisteredSessionIdentity(sessionId = "s2", draftId = "new_s2", targetNoteId = null),
      )
    assertTrue(first is ReserveTransferResult.Reserved)

    val second =
      registry.reserveIdentityTransfer(
        lease,
        RegisteredSessionIdentity(sessionId = "s3", draftId = "new_s3", targetNoteId = null),
      )
    assertEquals(ReserveTransferResult.SourceTransferAlreadyPending, second)
    assertFalse(registry.isSessionReserved("s3"))
    assertFalse(registry.isDraftReserved("new_s3"))
  }

  @Test
  fun reserveIdentityTransfer_rejectsInactiveOrForeignSourceLease() {
    val registryA = ActiveSessionRegistry()
    val registryB = ActiveSessionRegistry()

    val lease =
      (registryA.reserve(RegisteredSessionIdentity.withoutCheckpoint("s1")) as SessionReservationResult.Reserved)
        .lease

    assertEquals(
      ReserveTransferResult.SourceLeaseNotActive,
      registryB.reserveIdentityTransfer(lease, RegisteredSessionIdentity.withoutCheckpoint("s2")),
    )

    assertTrue(registryA.release(lease))
    assertEquals(
      ReserveTransferResult.SourceLeaseNotActive,
      registryA.reserveIdentityTransfer(lease, RegisteredSessionIdentity.withoutCheckpoint("s2")),
    )
  }

  @Test
  fun rollbackIdentityTransfer_releasesOnlyTargetAndPreservesOriginalLease() {
    val registry = ActiveSessionRegistry()
    val oldIdentity =
      RegisteredSessionIdentity(sessionId = "orig", draftId = "note_9_orig", targetNoteId = 9L)
    val newIdentity =
      RegisteredSessionIdentity(sessionId = "conv", draftId = "new_conv", targetNoteId = null)

    val sourceLease = (registry.reserve(oldIdentity) as SessionReservationResult.Reserved).lease
    val token =
      (registry.reserveIdentityTransfer(sourceLease, newIdentity) as ReserveTransferResult.Reserved)
        .transferToken

    val rollback = registry.rollbackIdentityTransfer(token)
    assertTrue(rollback is RollbackTransferResult.RolledBack)
    assertEquals(sourceLease, (rollback as RollbackTransferResult.RolledBack).preservedSourceLease)

    assertTrue(registry.isLeaseActive(sourceLease))
    assertTrue(registry.isSessionReserved("orig"))
    assertTrue(registry.isDraftReserved("note_9_orig"))
    assertFalse(registry.isSessionReserved("conv"))
    assertFalse(registry.isDraftReserved("new_conv"))
    assertEquals(0, registry.snapshot().pendingTransferCount)

    // Destino pode ser reservado por outro chamador após rollback.
    assertTrue(registry.reserve(newIdentity) is SessionReservationResult.Reserved)
  }

  @Test
  fun rollbackIdentityTransfer_whenUpgradingSameSessionId_preservesSourceSessionAndFreesOnlyNewDraft() {
    val registry = ActiveSessionRegistry()
    val sourceIdentity = RegisteredSessionIdentity.withoutCheckpoint("shared-s", targetNoteId = 3L)
    val targetIdentity = RegisteredSessionIdentity("shared-s", draftId = "note_3_shared-s", targetNoteId = 3L)

    val sourceLease = (registry.reserve(sourceIdentity) as SessionReservationResult.Reserved).lease
    val token =
      (registry.reserveIdentityTransfer(sourceLease, targetIdentity) as ReserveTransferResult.Reserved)
        .transferToken

    val rollback = registry.rollbackIdentityTransfer(token)
    assertTrue(rollback is RollbackTransferResult.RolledBack)
    assertTrue(registry.isLeaseActive(sourceLease))
    assertTrue(registry.isSessionReserved("shared-s"))
    assertFalse(registry.isDraftReserved("note_3_shared-s"))
  }

  @Test
  fun commitAndRollback_areIdempotentAndMutuallyExclusive() {
    val registry = ActiveSessionRegistry()
    val s1 = (registry.reserve(RegisteredSessionIdentity.withoutCheckpoint("a1")) as SessionReservationResult.Reserved).lease
    val t1 =
      (registry.reserveIdentityTransfer(s1, RegisteredSessionIdentity.withoutCheckpoint("a2"))
        as ReserveTransferResult.Reserved)
        .transferToken

    val firstCommit = registry.commitIdentityTransfer(t1) as CommitTransferResult.Committed
    val repeatedCommit = registry.commitIdentityTransfer(t1)
    assertEquals(CommitTransferResult.AlreadyCommitted(firstCommit.newLease), repeatedCommit)
    assertEquals(RollbackTransferResult.RejectedAlreadyCommitted, registry.rollbackIdentityTransfer(t1))

    val s2 = (registry.reserve(RegisteredSessionIdentity.withoutCheckpoint("b1")) as SessionReservationResult.Reserved).lease
    val t2 =
      (registry.reserveIdentityTransfer(s2, RegisteredSessionIdentity.withoutCheckpoint("b2"))
        as ReserveTransferResult.Reserved)
        .transferToken

    assertTrue(registry.rollbackIdentityTransfer(t2) is RollbackTransferResult.RolledBack)
    assertEquals(RollbackTransferResult.AlreadyRolledBack, registry.rollbackIdentityTransfer(t2))
    assertEquals(CommitTransferResult.RejectedAlreadyRolledBack, registry.commitIdentityTransfer(t2))
  }

  @Test
  fun releaseSourceLeaseWithPendingTransfer_cleansUpProvisionalTargetAndMarksTokenRolledBack() {
    val registry = ActiveSessionRegistry()
    val s1 = (registry.reserve(RegisteredSessionIdentity.withoutCheckpoint("s1")) as SessionReservationResult.Reserved).lease
    val target = RegisteredSessionIdentity(sessionId = "s2", draftId = "new_s2", targetNoteId = null)
    val token =
      (registry.reserveIdentityTransfer(s1, target) as ReserveTransferResult.Reserved)
        .transferToken

    assertTrue(registry.release(s1))
    assertFalse(registry.isSessionReserved("s1"))
    assertFalse(registry.isSessionReserved("s2"))
    assertFalse(registry.isDraftReserved("new_s2"))
    assertEquals(0, registry.snapshot().pendingTransferCount)
    assertEquals(CommitTransferResult.RejectedAlreadyRolledBack, registry.commitIdentityTransfer(token))
  }

  @Test
  fun commitAndRollback_rejectForeignRegistryTokens() {
    val registryA = ActiveSessionRegistry()
    val registryB = ActiveSessionRegistry()

    val lease =
      (registryA.reserve(RegisteredSessionIdentity.withoutCheckpoint("x1")) as SessionReservationResult.Reserved)
        .lease
    val token =
      (registryA.reserveIdentityTransfer(lease, RegisteredSessionIdentity.withoutCheckpoint("x2"))
        as ReserveTransferResult.Reserved)
        .transferToken

    assertEquals(CommitTransferResult.InvalidOrObsoleteToken, registryB.commitIdentityTransfer(token))
    assertEquals(RollbackTransferResult.InvalidOrObsoleteToken, registryB.rollbackIdentityTransfer(token))
  }

  @Test
  fun concurrentReserveAndTransfer_allowsExactlyOneWinnerPerIdentityAcrossThreads() {
    val registry = ActiveSessionRegistry()
    val threadCount = 16
    val barrier = CyclicBarrier(threadCount)
    val doneLatch = CountDownLatch(threadCount)
    val successes = AtomicInteger(0)
    val conflicts = AtomicInteger(0)
    val target =
      NoteEditorSessionIdentity(
        draftId = "note_100_race",
        sessionId = "race",
        targetNoteId = 100L,
      )

    repeat(threadCount) {
      thread {
        try {
          barrier.await(5, TimeUnit.SECONDS)
          when (registry.reserve(target)) {
            is SessionReservationResult.Reserved -> successes.incrementAndGet()
            is SessionReservationResult.Conflict -> conflicts.incrementAndGet()
          }
        } finally {
          doneLatch.countDown()
        }
      }
    }

    assertTrue(doneLatch.await(10, TimeUnit.SECONDS))
    assertEquals(1, successes.get())
    assertEquals(threadCount - 1, conflicts.get())
    assertEquals(setOf("race"), registry.snapshot().reservedSessionIds)
    assertEquals(setOf("note_100_race"), registry.snapshot().reservedDraftIds)
  }

  @Test
  fun concurrentTransfersTargetingSameNewIdentity_allowOnlySingleProvisionalOwner() {
    val registry = ActiveSessionRegistry()
    val contenderCount = 8
    val leases =
      (0 until contenderCount).map { idx ->
        (registry.reserve(
          RegisteredSessionIdentity(
            sessionId = "src-$idx",
            draftId = "note_${idx + 1}_src-$idx",
            targetNoteId = (idx + 1).toLong(),
          )
        ) as SessionReservationResult.Reserved).lease
      }
    val sharedTarget = RegisteredSessionIdentity(sessionId = "shared-new", draftId = "new_shared-new")
    val startBarrier = CyclicBarrier(contenderCount)
    val done = CountDownLatch(contenderCount)
    val winners = AtomicInteger(0)
    val winnerTokens = java.util.concurrent.CopyOnWriteArrayList<IdentityTransferToken>()

    leases.forEach { lease ->
      thread {
        try {
          startBarrier.await(5, TimeUnit.SECONDS)
          when (val result = registry.reserveIdentityTransfer(lease, sharedTarget)) {
            is ReserveTransferResult.Reserved -> {
              winners.incrementAndGet()
              winnerTokens.add(result.transferToken)
            }
            is ReserveTransferResult.TargetConflict -> Unit
            ReserveTransferResult.SourceLeaseNotActive,
            ReserveTransferResult.SourceTransferAlreadyPending -> Unit
          }
        } finally {
          done.countDown()
        }
      }
    }

    assertTrue(done.await(10, TimeUnit.SECONDS))
    assertEquals(1, winners.get())
    assertEquals(1, winnerTokens.size)
    assertEquals(1, registry.snapshot().pendingTransferCount)

    val commit = registry.commitIdentityTransfer(winnerTokens.first())
    assertTrue(commit is CommitTransferResult.Committed)
    assertEquals(0, registry.snapshot().pendingTransferCount)
  }

  @Test
  fun rollbackThenNewTransferWithSameIdentities_rejectsLateOldTokenWithoutAffectingSecondTransfer() {
    val registry = ActiveSessionRegistry()
    val sourceIdentity =
      RegisteredSessionIdentity(sessionId = "orig-s", draftId = "note_14_orig-s", targetNoteId = 14L)
    val targetIdentity =
      RegisteredSessionIdentity(sessionId = "conv-s", draftId = "new_conv-s", targetNoteId = null)

    // 1. Reservar identidade de origem e obter lease.
    val sourceLease =
      (registry.reserve(sourceIdentity) as SessionReservationResult.Reserved).lease
    assertTrue(registry.isLeaseActive(sourceLease))

    // 2. Criar transferência provisória T1.
    val tokenT1 =
      (registry.reserveIdentityTransfer(sourceLease, targetIdentity) as ReserveTransferResult.Reserved)
        .transferToken
    assertEquals(1, registry.snapshot().pendingTransferCount)

    // 3. Executar rollback de T1.
    val rollbackT1 = registry.rollbackIdentityTransfer(tokenT1)
    assertTrue(rollbackT1 is RollbackTransferResult.RolledBack)
    assertEquals(sourceLease, (rollbackT1 as RollbackTransferResult.RolledBack).preservedSourceLease)
    assertTrue(registry.isLeaseActive(sourceLease))
    assertFalse(registry.isSessionReserved("conv-s"))
    assertFalse(registry.isDraftReserved("new_conv-s"))
    assertEquals(0, registry.snapshot().pendingTransferCount)

    // 4. Criar nova transferência T2 com mesma identidade de origem e mesmo destino pretendido.
    val tokenT2 =
      (registry.reserveIdentityTransfer(sourceLease, targetIdentity) as ReserveTransferResult.Reserved)
        .transferToken
    assertTrue(tokenT1.id != tokenT2.id)
    assertTrue(tokenT1 != tokenT2)
    assertEquals(1, registry.snapshot().pendingTransferCount)
    assertTrue(registry.isSessionReserved("conv-s"))
    assertTrue(registry.isDraftReserved("new_conv-s"))

    // 5. Tentar executar commit tardio (e rollback repetido) de T1.
    assertEquals(
      CommitTransferResult.RejectedAlreadyRolledBack,
      registry.commitIdentityTransfer(tokenT1),
    )
    assertEquals(
      RollbackTransferResult.AlreadyRolledBack,
      registry.rollbackIdentityTransfer(tokenT1),
    )

    // 6. Comprovar que token antigo T1 não interferiu em T2 nem nas proteções pendentes.
    assertEquals(1, registry.snapshot().pendingTransferCount)
    assertTrue(registry.isLeaseActive(sourceLease))
    assertTrue(registry.isSessionReserved("orig-s"))
    assertTrue(registry.isDraftReserved("note_14_orig-s"))
    assertTrue(registry.isSessionReserved("conv-s"))
    assertTrue(registry.isDraftReserved("new_conv-s"))
    assertTrue(registry.reserve(targetIdentity) is SessionReservationResult.Conflict)

    // 7. Comprovar que T2 continua válida e pode ser efetivada corretamente.
    val commitT2 = registry.commitIdentityTransfer(tokenT2)
    assertTrue(commitT2 is CommitTransferResult.Committed)
    val committedLease = (commitT2 as CommitTransferResult.Committed).newLease

    // 8. Verificar coerência final de reservas, leases, índices e idempotência isolada entre T1 e T2.
    assertFalse(registry.isLeaseActive(sourceLease))
    assertTrue(registry.isLeaseActive(committedLease))
    assertEquals(targetIdentity, committedLease.identity)
    assertFalse(registry.isSessionReserved("orig-s"))
    assertFalse(registry.isDraftReserved("note_14_orig-s"))
    assertTrue(registry.isSessionReserved("conv-s"))
    assertTrue(registry.isDraftReserved("new_conv-s"))
    assertEquals(
      ActiveRegistrySnapshot(
        reservedSessionIds = setOf("conv-s"),
        reservedDraftIds = setOf("new_conv-s"),
        pendingTransferCount = 0,
      ),
      registry.snapshot(),
    )

    // Repetições tardias de T1 após commit de T2 seguem rejeitadas e não afetam T2 nem o novo lease.
    assertEquals(
      CommitTransferResult.RejectedAlreadyRolledBack,
      registry.commitIdentityTransfer(tokenT1),
    )
    assertEquals(
      RollbackTransferResult.AlreadyRolledBack,
      registry.rollbackIdentityTransfer(tokenT1),
    )
    assertEquals(
      CommitTransferResult.AlreadyCommitted(committedLease),
      registry.commitIdentityTransfer(tokenT2),
    )
    assertEquals(
      RollbackTransferResult.RejectedAlreadyCommitted,
      registry.rollbackIdentityTransfer(tokenT2),
    )
    assertTrue(registry.isLeaseActive(committedLease))
    assertTrue(registry.reserve(targetIdentity) is SessionReservationResult.Conflict)
    assertTrue(registry.reserve(sourceIdentity) is SessionReservationResult.Reserved)
  }

  @Test
  fun rollbackIdentityTransfer_rejectsTokenWithSameIdButDivergentContentWithoutWeakeningIdempotency() {
    val registry = ActiveSessionRegistry()
    val sourceIdentity =
      RegisteredSessionIdentity(sessionId = "orig-rb", draftId = "note_21_orig-rb", targetNoteId = 21L)
    val targetIdentity =
      RegisteredSessionIdentity(sessionId = "conv-rb", draftId = "new_conv-rb", targetNoteId = null)

    val sourceLease =
      (registry.reserve(sourceIdentity) as SessionReservationResult.Reserved).lease
    val originalToken =
      (registry.reserveIdentityTransfer(sourceLease, targetIdentity) as ReserveTransferResult.Reserved)
        .transferToken

    val rollback = registry.rollbackIdentityTransfer(originalToken)
    assertTrue(rollback is RollbackTransferResult.RolledBack)

    val sameIdDivergentSourceIdentity =
      IdentityTransferToken(
        registryInstanceId = originalToken.registryInstanceId,
        id = originalToken.id,
        sourceLease =
          SessionLeaseToken(
            registryInstanceId = originalToken.sourceLease.registryInstanceId,
            id = originalToken.sourceLease.id,
            identity =
              RegisteredSessionIdentity(
                sessionId = "outra-origem",
                draftId = "note_21_outra-origem",
                targetNoteId = 21L,
              ),
          ),
        targetLease = originalToken.targetLease,
      )

    val sameIdDivergentTargetIdentity =
      IdentityTransferToken(
        registryInstanceId = originalToken.registryInstanceId,
        id = originalToken.id,
        sourceLease = originalToken.sourceLease,
        targetLease =
          SessionLeaseToken(
            registryInstanceId = originalToken.targetLease.registryInstanceId,
            id = originalToken.targetLease.id,
            identity =
              RegisteredSessionIdentity(
                sessionId = "outro-destino",
                draftId = "new_outro-destino",
                targetNoteId = null,
              ),
          ),
      )

    val sameIdDivergentLeaseId =
      IdentityTransferToken(
        registryInstanceId = originalToken.registryInstanceId,
        id = originalToken.id,
        sourceLease = originalToken.sourceLease,
        targetLease =
          SessionLeaseToken(
            registryInstanceId = originalToken.targetLease.registryInstanceId,
            id = originalToken.targetLease.id + 99L,
            identity = originalToken.targetIdentity,
          ),
      )

    val forgedTokens =
      listOf(sameIdDivergentSourceIdentity, sameIdDivergentTargetIdentity, sameIdDivergentLeaseId)
    for (forged in forgedTokens) {
      assertEquals(CommitTransferResult.InvalidOrObsoleteToken, registry.commitIdentityTransfer(forged))
      assertEquals(RollbackTransferResult.InvalidOrObsoleteToken, registry.rollbackIdentityTransfer(forged))
    }

    // Token original genuíno mantém integralmente sua idempotência após rejeições divergentes.
    assertEquals(CommitTransferResult.RejectedAlreadyRolledBack, registry.commitIdentityTransfer(originalToken))
    assertEquals(RollbackTransferResult.AlreadyRolledBack, registry.rollbackIdentityTransfer(originalToken))
  }

  @Test
  fun releaseSourceLeaseWithPendingTransfer_preservesOriginalTokenForIdempotencyAndRejectsDivergentSameId() {
    val registry = ActiveSessionRegistry()
    val sourceLease =
      (registry.reserve(RegisteredSessionIdentity.withoutCheckpoint("rel-src")) as SessionReservationResult.Reserved)
        .lease
    val originalToken =
      (registry.reserveIdentityTransfer(
        sourceLease,
        RegisteredSessionIdentity(sessionId = "rel-dst", draftId = "new_rel-dst", targetNoteId = null),
      ) as ReserveTransferResult.Reserved).transferToken

    assertTrue(registry.release(sourceLease))

    val divergentWithSameId =
      IdentityTransferToken(
        registryInstanceId = originalToken.registryInstanceId,
        id = originalToken.id,
        sourceLease = originalToken.sourceLease,
        targetLease =
          SessionLeaseToken(
            registryInstanceId = originalToken.targetLease.registryInstanceId,
            id = originalToken.targetLease.id,
            identity =
              RegisteredSessionIdentity(
                sessionId = "rel-dst-alterado",
                draftId = "new_rel-dst-alterado",
                targetNoteId = null,
              ),
          ),
      )

    assertEquals(CommitTransferResult.InvalidOrObsoleteToken, registry.commitIdentityTransfer(divergentWithSameId))
    assertEquals(RollbackTransferResult.InvalidOrObsoleteToken, registry.rollbackIdentityTransfer(divergentWithSameId))

    assertEquals(CommitTransferResult.RejectedAlreadyRolledBack, registry.commitIdentityTransfer(originalToken))
    assertEquals(RollbackTransferResult.AlreadyRolledBack, registry.rollbackIdentityTransfer(originalToken))
  }

  @Test
  fun reserveAndCommitIdentityTransfer_downgradeCheckpointToWithoutCheckpointSameSession_freesDraftOnly() {
    val registry = ActiveSessionRegistry()
    val sourceIdentity =
      RegisteredSessionIdentity(sessionId = "shared-down", draftId = "note_8_shared-down", targetNoteId = 8L)
    val targetIdentity =
      RegisteredSessionIdentity.withoutCheckpoint(sessionId = "shared-down", targetNoteId = 8L)

    val sourceLease =
      (registry.reserve(sourceIdentity) as SessionReservationResult.Reserved).lease
    val token =
      (registry.reserveIdentityTransfer(sourceLease, targetIdentity) as ReserveTransferResult.Reserved)
        .transferToken

    assertTrue(registry.isSessionReserved("shared-down"))
    assertTrue(registry.isDraftReserved("note_8_shared-down"))

    val commit = registry.commitIdentityTransfer(token) as CommitTransferResult.Committed
    assertFalse(registry.isLeaseActive(sourceLease))
    assertTrue(registry.isLeaseActive(commit.newLease))
    assertTrue(registry.isSessionReserved("shared-down"))
    assertFalse(registry.isDraftReserved("note_8_shared-down"))
    assertEquals(0, registry.snapshot().pendingTransferCount)
  }

  @Test
  fun concurrentCommitAndRollbackOnSameToken_enforcesSingleWinnerAndConsistentIdempotency() {
    repeat(20) { iteration ->
      val registry = ActiveSessionRegistry()
      val sourceIdentity =
        RegisteredSessionIdentity(
          sessionId = "race-src-$iteration",
          draftId = "note_${iteration + 1}_race-src-$iteration",
          targetNoteId = (iteration + 1).toLong(),
        )
      val targetIdentity =
        RegisteredSessionIdentity(
          sessionId = "race-dst-$iteration",
          draftId = "new_race-dst-$iteration",
          targetNoteId = null,
        )

      val sourceLease =
        (registry.reserve(sourceIdentity) as SessionReservationResult.Reserved).lease
      val token =
        (registry.reserveIdentityTransfer(sourceLease, targetIdentity) as ReserveTransferResult.Reserved)
          .transferToken

      val workers = 8
      val barrier = CyclicBarrier(workers)
      val done = CountDownLatch(workers)
      val committedWins = AtomicInteger(0)
      val alreadyCommitted = AtomicInteger(0)
      val rejectedAlreadyRolledBack = AtomicInteger(0)
      val rolledBackWins = AtomicInteger(0)
      val alreadyRolledBack = AtomicInteger(0)
      val rejectedAlreadyCommitted = AtomicInteger(0)
      val unexpected = AtomicInteger(0)

      repeat(workers) { idx ->
        thread {
          try {
            barrier.await(5, TimeUnit.SECONDS)
            if (idx % 2 == 0) {
              when (registry.commitIdentityTransfer(token)) {
                is CommitTransferResult.Committed -> committedWins.incrementAndGet()
                is CommitTransferResult.AlreadyCommitted -> alreadyCommitted.incrementAndGet()
                CommitTransferResult.RejectedAlreadyRolledBack -> rejectedAlreadyRolledBack.incrementAndGet()
                CommitTransferResult.InvalidOrObsoleteToken -> unexpected.incrementAndGet()
              }
            } else {
              when (registry.rollbackIdentityTransfer(token)) {
                is RollbackTransferResult.RolledBack -> rolledBackWins.incrementAndGet()
                RollbackTransferResult.AlreadyRolledBack -> alreadyRolledBack.incrementAndGet()
                RollbackTransferResult.RejectedAlreadyCommitted -> rejectedAlreadyCommitted.incrementAndGet()
                RollbackTransferResult.InvalidOrObsoleteToken -> unexpected.incrementAndGet()
              }
            }
          } finally {
            done.countDown()
          }
        }
      }

      assertTrue(done.await(10, TimeUnit.SECONDS))
      assertEquals(0, unexpected.get())
      assertEquals(0, registry.snapshot().pendingTransferCount)
      assertEquals(1, committedWins.get() + rolledBackWins.get())

      if (committedWins.get() == 1) {
        assertEquals(workers / 2 - 1, alreadyCommitted.get())
        assertEquals(0, rejectedAlreadyRolledBack.get())
        assertEquals(0, rolledBackWins.get())
        assertEquals(0, alreadyRolledBack.get())
        assertEquals(workers / 2, rejectedAlreadyCommitted.get())

        assertFalse(registry.isLeaseActive(sourceLease))
        assertFalse(registry.isSessionReserved(sourceIdentity.sessionId))
        assertFalse(registry.isDraftReserved(sourceIdentity.draftId!!))
        assertTrue(registry.isSessionReserved(targetIdentity.sessionId))
        assertTrue(registry.isDraftReserved(targetIdentity.draftId!!))
      } else {
        assertEquals(1, rolledBackWins.get())
        assertEquals(workers / 2 - 1, alreadyRolledBack.get())
        assertEquals(0, rejectedAlreadyCommitted.get())
        assertEquals(0, committedWins.get())
        assertEquals(0, alreadyCommitted.get())
        assertEquals(workers / 2, rejectedAlreadyRolledBack.get())

        assertTrue(registry.isLeaseActive(sourceLease))
        assertTrue(registry.isSessionReserved(sourceIdentity.sessionId))
        assertTrue(registry.isDraftReserved(sourceIdentity.draftId!!))
        assertFalse(registry.isSessionReserved(targetIdentity.sessionId))
        assertFalse(registry.isDraftReserved(targetIdentity.draftId!!))
      }
    }
  }

  @Test
  fun concurrentReleaseSourceAndCommitTransfer_neverLeavesOrphanedKeysOrInconsistentLeases() {
    repeat(20) { iteration ->
      val registry = ActiveSessionRegistry()
      val sourceIdentity =
        RegisteredSessionIdentity(
          sessionId = "rel-race-src-$iteration",
          draftId = "note_${iteration + 100}_rel-race-src-$iteration",
          targetNoteId = (iteration + 100).toLong(),
        )
      val targetIdentity =
        RegisteredSessionIdentity(
          sessionId = "rel-race-dst-$iteration",
          draftId = "new_rel-race-dst-$iteration",
          targetNoteId = null,
        )

      val sourceLease =
        (registry.reserve(sourceIdentity) as SessionReservationResult.Reserved).lease
      val token =
        (registry.reserveIdentityTransfer(sourceLease, targetIdentity) as ReserveTransferResult.Reserved)
          .transferToken

      val barrier = CyclicBarrier(2)
      val done = CountDownLatch(2)
      val releaseWon = AtomicBoolean(false)
      val commitOutcome = AtomicReference<CommitTransferResult?>(null)

      thread {
        try {
          barrier.await(5, TimeUnit.SECONDS)
          releaseWon.set(registry.release(sourceLease))
        } finally {
          done.countDown()
        }
      }
      thread {
        try {
          barrier.await(5, TimeUnit.SECONDS)
          commitOutcome.set(registry.commitIdentityTransfer(token))
        } finally {
          done.countDown()
        }
      }

      assertTrue(done.await(10, TimeUnit.SECONDS))
      assertEquals(0, registry.snapshot().pendingTransferCount)
      assertFalse(registry.isLeaseActive(sourceLease))
      assertFalse(registry.isSessionReserved(sourceIdentity.sessionId))
      assertFalse(registry.isDraftReserved(sourceIdentity.draftId!!))

      val finalCommit = commitOutcome.get()
      if (releaseWon.get()) {
        assertEquals(CommitTransferResult.RejectedAlreadyRolledBack, finalCommit)
        assertFalse(registry.isSessionReserved(targetIdentity.sessionId))
        assertFalse(registry.isDraftReserved(targetIdentity.draftId!!))
      } else {
        assertTrue(finalCommit is CommitTransferResult.Committed)
        val newLease = (finalCommit as CommitTransferResult.Committed).newLease
        assertTrue(registry.isLeaseActive(newLease))
        assertTrue(registry.isSessionReserved(targetIdentity.sessionId))
        assertTrue(registry.isDraftReserved(targetIdentity.draftId!!))
      }
    }
  }
}
