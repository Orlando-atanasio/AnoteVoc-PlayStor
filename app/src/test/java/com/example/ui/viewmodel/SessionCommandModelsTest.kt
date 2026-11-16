package com.example.ui.viewmodel

import com.example.domain.editor.ConflictResolutionOption
import com.example.domain.editor.FolderAvailability
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.TextFormatStyle
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCommandModelsTest {

  @Test
  fun persistableCommands_allDeclareIsPersistableMutationTrue() {
    val persistableCommands: List<SessionCommand> =
      listOf(
        SessionCommand.EditTitle("Título"),
        SessionCommand.InsertText("abc"),
        SessionCommand.ApplyTextChange(blockId = "b1", newText = "novo", selectionStart = 4),
        SessionCommand.PressEnter,
        SessionCommand.PressBackspace,
        SessionCommand.ToggleFormat(TextFormatStyle.BOLD),
        SessionCommand.ApplyFormat(TextFormatStyle.ITALIC),
        SessionCommand.RemoveFormat(TextFormatStyle.UNDERLINE),
        SessionCommand.ToggleBulletList,
        SessionCommand.ToggleNumberedList,
        SessionCommand.ToggleChecklist,
        SessionCommand.ToggleChecklistItem("c1"),
        SessionCommand.Undo,
        SessionCommand.Redo,
        SessionCommand.ChangeFolder(folderId = 10L),
        SessionCommand.ChangeFolder(folderId = null),
        SessionCommand.ConfirmFallbackToRoot,
        SessionCommand.ChangePaperStyle(NotePaperStyle.PONTILHADO),
        SessionCommand.SetPinned(true),
        SessionCommand.TogglePinned,
      )

    persistableCommands.forEach { command ->
      assertTrue("Esperava isPersistableMutation=true para $command", command.isPersistableMutation)
      assertNull(command.ackDeferred)
    }
  }

  @Test
  fun selectionQueryLifecycleAndBarrierCommands_declareIsPersistableMutationFalse() {
    val ack = CompletableDeferred<SessionCommandOutcome>()
    val nonPersistableCommands: List<SessionCommand> =
      listOf(
        SessionCommand.SelectBlock(blockId = "b1", start = 0, end = 2),
        SessionCommand.FolderAvailabilityObserved(mapOf(1L to FolderAvailability.NotFound(1L))),
        SessionCommand.DebounceTimeout(expectedGeneration = 3L),
        SessionCommand.FlushAutosave(ackDeferred = ack),
        SessionCommand.ResolveConflict(ConflictResolutionOption.KEEP_DRAFT_OVER_CURRENT_NOTE, ack),
        SessionCommand.ConsolidateNote(allowFallbackToRoot = true, ackDeferred = ack),
        SessionCommand.DiscardDraftAndClose(ackDeferred = ack),
        SessionCommand.CloseSession(ackDeferred = ack),
      )

    nonPersistableCommands.forEach { command ->
      assertFalse(
        "Esperava isPersistableMutation=false para $command",
        command.isPersistableMutation,
      )
    }
  }

  @Test
  fun queuedCommandAndDefensiveMaps_enforceInvariantsAndImmutability() {
    val queuedPersistable =
      QueuedSessionCommand(command = SessionCommand.InsertText("x"), mutationSequence = 1L)
    assertEquals(1L, queuedPersistable.mutationSequence)

    val queuedSelection =
      QueuedSessionCommand(
        command = SessionCommand.SelectBlock(blockId = "p1", start = 1),
        mutationSequence = null,
      )
    assertNull(queuedSelection.mutationSequence)

    assertThrows(IllegalArgumentException::class.java) {
      QueuedSessionCommand(command = SessionCommand.InsertText("x"), mutationSequence = null)
    }
    assertThrows(IllegalArgumentException::class.java) {
      QueuedSessionCommand(command = SessionCommand.InsertText("x"), mutationSequence = 0L)
    }
    assertThrows(IllegalArgumentException::class.java) {
      QueuedSessionCommand(
        command = SessionCommand.SelectBlock(blockId = "p1", start = 0),
        mutationSequence = 1L,
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      SessionCommand.SelectBlock(blockId = " ", start = 0)
    }
    assertThrows(IllegalArgumentException::class.java) {
      SessionCommand.SelectBlock(blockId = "p1", start = 5, end = 2)
    }
    assertThrows(IllegalArgumentException::class.java) {
      SessionCommand.ChangeFolder(folderId = 0L)
    }

    val mutableMap = mutableMapOf<Long, FolderAvailability>(7L to FolderAvailability.NotFound(7L))
    val observed = SessionCommand.FolderAvailabilityObserved(mutableMap)
    val completion =
      PersistenceCompletion.FolderAvailabilityLookup(operationId = 9L, availabilityById = mutableMap)
    mutableMap.clear()

    assertEquals(1, observed.availabilityById.size)
    assertEquals(1, completion.availabilityById.size)
    assertThrows(UnsupportedOperationException::class.java) {
      @Suppress("UNCHECKED_CAST")
      (observed.availabilityById as MutableMap<Long, FolderAvailability>).clear()
    }
    assertThrows(UnsupportedOperationException::class.java) {
      @Suppress("UNCHECKED_CAST")
      (completion.availabilityById as MutableMap<Long, FolderAvailability>).clear()
    }
  }

  @Test
  fun structuralRetryRequested_preservesOperationIdAndExactBarrierToken() {
    val token = BarrierToken(id = 77L, reason = BarrierReason.CONSOLIDATION)
    val retry = PersistenceCompletion.StructuralRetryRequested(operationId = 15L, barrierToken = token)
    assertEquals(15L, retry.operationId)
    assertEquals(token, retry.barrierToken)
    assertEquals(BarrierReason.CONSOLIDATION, retry.barrierToken.reason)
  }
}
