package com.example.ui.viewmodel

import com.example.domain.editor.ConflictResolutionOption
import com.example.domain.editor.FolderAvailability
import com.example.domain.editor.NoteEditorBaseline
import com.example.domain.editor.NoteEditorEditableSnapshot
import com.example.domain.editor.NoteEditorEngine
import com.example.domain.editor.NoteEditorSessionEvaluationInput
import com.example.domain.editor.NoteEditorSessionEvaluator
import com.example.domain.editor.NoteEditorSessionIdentity
import com.example.domain.editor.RecoveryConflict
import com.example.domain.editor.ResolvedDraftFolderContext
import com.example.domain.editor.SessionLifecycleStage
import com.example.domain.model.Note
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.NoteRecoveryDraft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

internal typealias NoteEditorSessionInputAlias = NoteEditorSessionEvaluationInput

internal data class SessionRuntimeState(
  var phase: NoteEditorSessionPhase,
  var lease: SessionLeaseToken,
  var identity: NoteEditorSessionIdentity,
  var baseline: NoteEditorBaseline,
  var snapshot: NoteEditorEditableSnapshot,
  var folderContext: ResolvedDraftFolderContext,
  var effectiveFolderName: String?,
  var activeConflict: RecoveryConflict?,
  var persistedInitialFolderId: Long?,
  var persistedInitialPaperStyle: NotePaperStyle?,
  var knownFolderAvailability: MutableMap<Long, FolderAvailability>,
  var stateGeneration: Long,
  var lastSyncedGeneration: Long,
  var lastCheckpointAt: Long?,
  var appliedMutationSequence: Long,
  var autosaveStatus: AutosaveStatus,
  var errorMessage: String?,
  var isConsolidating: Boolean,
  var isSessionTerminated: Boolean,
  var needsInitialPersistOnOpen: Boolean,
) {
  fun evaluate(): com.example.domain.editor.NoteEditorSessionEvaluation =
    NoteEditorSessionEvaluator.evaluateSession(
      NoteEditorSessionInputAlias(
        lifecycleStage =
          when (phase) {
            NoteEditorSessionPhase.LOADING -> SessionLifecycleStage.LOADING
            NoteEditorSessionPhase.EDITING -> SessionLifecycleStage.EDITING
            NoteEditorSessionPhase.BLOCKED -> SessionLifecycleStage.BLOCKED
            NoteEditorSessionPhase.ERROR -> SessionLifecycleStage.ERROR
          },
        baseline = baseline,
        snapshot = snapshot,
        folderContext = folderContext,
        activeConflict = activeConflict,
        blockReason = null,
      )
    )

  fun buildCheckpointPayload(): CheckpointSavePayload {
    val (baseRev, baseUpdatedAt) =
      when (val b = baseline) {
        is NoteEditorBaseline.NewNote -> 0L to null
        is NoteEditorBaseline.LegacyIndeterminate -> 0L to null
        is NoteEditorBaseline.ExistingNote -> b.baseRevision to b.baseNoteUpdatedAt
      }
    return CheckpointSavePayload.fromIdentity(
      identity = identity,
      folderId = snapshot.effectiveFolderId,
      initialFolderId = if (identity.targetNoteId == null) persistedInitialFolderId else null,
      fallbackSourceFolderId = snapshot.fallbackSourceFolderId,
      isPinned = snapshot.isPinned,
      title = snapshot.title,
      content = snapshot.document,
      paperStyle = snapshot.paperStyle,
      initialPaperStyle =
        if (identity.targetNoteId == null) persistedInitialPaperStyle else null,
      baseRevision = baseRev,
      baseNoteUpdatedAt = baseUpdatedAt,
    )
  }

  fun toPublication(
    evaluation: com.example.domain.editor.NoteEditorSessionEvaluation,
    editorState: com.example.domain.editor.NoteEditorState,
    hasInFlightPersistence: Boolean,
  ): LinearizedStatePublication {
    val hasInFlightWrite = isConsolidating || hasInFlightPersistence
    val resolvedStatus =
      when {
        autosaveStatus is AutosaveStatus.Error -> autosaveStatus
        activeConflict != null -> AutosaveStatus.HeldPendingResolution
        errorMessage != null ->
          AutosaveStatus.Error(message = errorMessage ?: "Erro na sessão.", isTerminal = false)
        hasInFlightWrite -> AutosaveStatus.Saving
        stateGeneration == lastSyncedGeneration && !evaluation.needsDraftRetention ->
          AutosaveStatus.Idle
        stateGeneration == lastSyncedGeneration && evaluation.needsDraftRetention ->
          if (needsInitialPersistOnOpen) {
            AutosaveStatus.PendingDebounce
          } else {
            AutosaveStatus.Saved(syncedGeneration = lastSyncedGeneration, checkpointAt = lastCheckpointAt)
          }
        else -> AutosaveStatus.PendingDebounce
      }

    val candidateState =
      NoteEditorUiState.fromEvaluation(
        phase = phase,
        sessionIdentity = identity,
        baseline = baseline,
        snapshot = snapshot,
        editorState = editorState,
        evaluation = evaluation,
        effectiveFolderName = effectiveFolderName,
        stateGeneration = stateGeneration,
        lastSyncedGeneration = lastSyncedGeneration,
        autosaveStatus = resolvedStatus,
        activeConflict = activeConflict,
        blockReason = null,
        errorMessage = errorMessage,
        isConsolidating = isConsolidating,
        isSessionTerminated = isSessionTerminated,
      )

    return LinearizedStatePublication(
      candidateState = candidateState,
      appliedMutationSequence = appliedMutationSequence,
      hasInFlightWrite = hasInFlightWrite,
      hasPendingError = errorMessage != null || resolvedStatus is AutosaveStatus.Error,
      hasUnresolvedConflict = activeConflict != null,
      needsDraftRetention = evaluation.needsDraftRetention || needsInitialPersistOnOpen,
    )
  }
}

internal enum class InFlightKind {
  AUTOSAVE_UPSERT,
  CLEAN_DELETE,
  STRUCTURAL,
}

internal data class InFlightOperation(
  val operationId: Long,
  val kind: InFlightKind,
  val targetGeneration: Long,
  val barrierToken: BarrierToken?,
  val job: Job,
)

internal data class PendingFlushWaiter(
  val targetGeneration: Long,
  val ackDeferred: CompletableDeferred<SessionCommandOutcome>,
)

internal data class PendingStructuralBarrier(
  val token: BarrierToken,
  val queuedCommand: QueuedSessionCommand,
  var dispatched: Boolean = false,
)

internal sealed interface StructuralOutcomeData {
  data class ConsolidatedExisting(
    val updatedNote: Note,
    val resolvedFolderAvailability: Map<Long, FolderAvailability>,
  ) : StructuralOutcomeData

  data class ConsolidatedNew(
    val createdNote: Note,
    val newIdentity: NoteEditorSessionIdentity,
    val newLease: SessionLeaseToken,
    val newExecutor: SingleFlightRoomPersistenceExecutor,
    val resolvedFolderAvailability: Map<Long, FolderAvailability>,
  ) : StructuralOutcomeData

  data class ConsolidationConflictDetected(
    val conflict: RecoveryConflict,
    val latestNote: Note?,
  ) : StructuralOutcomeData

  data class KeptDraftOverCurrentNote(
    val latestNote: Note,
    val checkpointAt: Long?,
    val resolvedFolderAvailability: Map<Long, FolderAvailability>,
    val deletedCleanDraft: Boolean,
    val rebasedSnapshot: com.example.domain.editor.NoteEditorEditableSnapshot,
    val rebasedFolderContext: com.example.domain.editor.ResolvedDraftFolderContext,
  ) : StructuralOutcomeData

  data class DiscardedDraftToCurrentNote(
    val latestNote: Note,
    val resolvedFolderAvailability: Map<Long, FolderAvailability>,
  ) : StructuralOutcomeData

  data class SavedDraftAsNewNote(
    val newIdentity: NoteEditorSessionIdentity,
    val newLease: SessionLeaseToken,
    val newDraft: NoteRecoveryDraft,
    val targetBaseline: NoteEditorBaseline.NewNote,
  ) : StructuralOutcomeData

  data class ReconciledIndeterminate(
    val result: ReplacementReconciliationResult,
    val resolvedFolderAvailability: Map<Long, FolderAvailability> = emptyMap(),
  ) : StructuralOutcomeData

  data class ExistingNotePinUpdated(
    val updatedNote: Note,
  ) : StructuralOutcomeData

  data object DiscardedAndClosed : StructuralOutcomeData
}

internal object SessionMutationApplier {
  data class MutationOutcome(
    val snapshotChanged: Boolean,
    val selectionChanged: Boolean,
    val requestedFolderLookupId: Long?,
  )

  fun applyEditableCommand(
    runtime: SessionRuntimeState,
    engine: NoteEditorEngine,
    command: SessionCommand,
  ): MutationOutcome {
    val previousSnapshot = runtime.snapshot
    val previousEditorState = engine.state
    var requestedFolderLookupId: Long? = null

    when (command) {
      is SessionCommand.EditTitle -> {
        runtime.snapshot = previousSnapshot.copy(title = command.title)
      }
      is SessionCommand.InsertText -> {
        val next = engine.insertText(command.text)
        runtime.snapshot = previousSnapshot.copy(document = next.document)
      }
      is SessionCommand.ApplyTextChange -> {
        val next =
          engine.applyTextChange(
            blockId = command.blockId,
            newText = command.newText,
            selectionStart = command.selectionStart,
            selectionEnd = command.selectionEnd,
          )
        runtime.snapshot = previousSnapshot.copy(document = next.document)
      }
      SessionCommand.PressEnter -> {
        val next = engine.pressEnter()
        runtime.snapshot = previousSnapshot.copy(document = next.document)
      }
      SessionCommand.PressBackspace -> {
        val next = engine.pressBackspace()
        runtime.snapshot = previousSnapshot.copy(document = next.document)
      }
      is SessionCommand.ToggleFormat -> {
        val next = engine.toggleFormat(command.style)
        runtime.snapshot = previousSnapshot.copy(document = next.document)
      }
      is SessionCommand.ApplyFormat -> {
        val next = engine.applyFormat(command.style)
        runtime.snapshot = previousSnapshot.copy(document = next.document)
      }
      is SessionCommand.RemoveFormat -> {
        val next = engine.removeFormat(command.style)
        runtime.snapshot = previousSnapshot.copy(document = next.document)
      }
      SessionCommand.ToggleBulletList -> {
        val next = engine.toggleBulletList()
        runtime.snapshot = previousSnapshot.copy(document = next.document)
      }
      SessionCommand.ToggleNumberedList -> {
        val next = engine.toggleNumberedList()
        runtime.snapshot = previousSnapshot.copy(document = next.document)
      }
      SessionCommand.ToggleChecklist -> {
        val next = engine.toggleChecklist()
        runtime.snapshot = previousSnapshot.copy(document = next.document)
      }
      is SessionCommand.ToggleChecklistItem -> {
        val next = engine.toggleChecklistItem(command.blockId)
        runtime.snapshot = previousSnapshot.copy(document = next.document)
      }
      SessionCommand.Undo -> {
        val next = engine.undo()
        runtime.snapshot = previousSnapshot.copy(document = next.document)
      }
      SessionCommand.Redo -> {
        val next = engine.redo()
        runtime.snapshot = previousSnapshot.copy(document = next.document)
      }
      is SessionCommand.ChangeFolder -> {
        val targetId = command.folderId
        if (targetId == null) {
          runtime.snapshot =
            previousSnapshot.copy(effectiveFolderId = null, fallbackSourceFolderId = null)
          runtime.folderContext =
            runtime.folderContext.copy(
              effectiveTarget = FolderAvailability.Root,
              fallbackSource = null,
            )
          runtime.effectiveFolderName = null
        } else {
          val known = runtime.knownFolderAvailability[targetId]
          if (known != null && known.isDefinitelyUnavailable) {
            runtime.snapshot =
              previousSnapshot.copy(effectiveFolderId = null, fallbackSourceFolderId = targetId)
            runtime.folderContext =
              runtime.folderContext.copy(
                effectiveTarget = FolderAvailability.Root,
                fallbackSource = known,
              )
            runtime.effectiveFolderName = null
          } else {
            val availability = known ?: FolderAvailability.Unknown(targetId)
            runtime.snapshot =
              previousSnapshot.copy(effectiveFolderId = targetId, fallbackSourceFolderId = null)
            runtime.folderContext =
              runtime.folderContext.copy(
                effectiveTarget = availability,
                fallbackSource = null,
              )
            runtime.effectiveFolderName = (availability as? FolderAvailability.Active)?.folder?.name
          }
          requestedFolderLookupId = targetId
        }
      }
      SessionCommand.ConfirmFallbackToRoot -> {
        if (previousSnapshot.fallbackSourceFolderId != null) {
          runtime.snapshot =
            previousSnapshot.copy(effectiveFolderId = null, fallbackSourceFolderId = null)
          runtime.folderContext =
            runtime.folderContext.copy(
              effectiveTarget = FolderAvailability.Root,
              fallbackSource = null,
            )
          runtime.effectiveFolderName = null
        }
      }
      is SessionCommand.ChangePaperStyle -> {
        runtime.snapshot = previousSnapshot.copy(paperStyle = command.paperStyle)
      }
      is SessionCommand.SetPinned -> {
        runtime.snapshot = previousSnapshot.copy(isPinned = command.isPinned)
      }
      SessionCommand.TogglePinned -> {
        runtime.snapshot = previousSnapshot.copy(isPinned = !previousSnapshot.isPinned)
      }
      is SessionCommand.SelectBlock -> {
        engine.selectBlock(command.blockId, command.start, command.end)
      }
      is SessionCommand.FolderAvailabilityObserved,
      is SessionCommand.DebounceTimeout,
      is SessionCommand.FlushAutosave,
      is SessionCommand.ResolveConflict,
      is SessionCommand.ConsolidateNote,
      is SessionCommand.DiscardDraftAndClose,
      is SessionCommand.CloseSession,
      is SessionCommand.PersistExistingNotePin -> Unit
    }

    val snapshotChanged =
      runtime.snapshot != previousSnapshot ||
        (command is SessionCommand.ChangeFolder &&
          command.folderId != previousSnapshot.intendedUserFolderId)
    val selectionChanged = engine.state != previousEditorState
    return MutationOutcome(
      snapshotChanged = snapshotChanged,
      selectionChanged = selectionChanged,
      requestedFolderLookupId = requestedFolderLookupId,
    )
  }
}
