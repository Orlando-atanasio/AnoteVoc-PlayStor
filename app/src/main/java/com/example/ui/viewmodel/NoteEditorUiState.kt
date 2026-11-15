package com.example.ui.viewmodel

import com.example.domain.editor.ConsolidationReadiness
import com.example.domain.editor.NoteEditorBaseline
import com.example.domain.editor.NoteEditorEditableSnapshot
import com.example.domain.editor.NoteEditorSessionEvaluation
import com.example.domain.editor.NoteEditorSessionIdentity
import com.example.domain.editor.NoteEditorState
import com.example.domain.editor.RecoveryConflict
import com.example.domain.editor.SessionBlockReason
import com.example.domain.editor.SessionLifecycleStage
import com.example.domain.editor.SessionWarning
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NotePaperStyle

/**
 * Fase principal do ciclo de vida da sessão do editor exposta à interface.
 */
enum class NoteEditorSessionPhase {
  LOADING,
  EDITING,
  BLOCKED,
  ERROR,
  ;

  fun toLifecycleStage(): SessionLifecycleStage =
    when (this) {
      LOADING -> SessionLifecycleStage.LOADING
      EDITING -> SessionLifecycleStage.EDITING
      BLOCKED -> SessionLifecycleStage.BLOCKED
      ERROR -> SessionLifecycleStage.ERROR
    }
}

/**
 * Estado imutável do autosave/sincronização do checkpoint da sessão.
 */
sealed interface AutosaveStatus {
  data object Idle : AutosaveStatus

  data object PendingDebounce : AutosaveStatus

  data object Saving : AutosaveStatus

  data class Saved(
    val syncedGeneration: Long,
    val checkpointAt: Long? = null,
  ) : AutosaveStatus

  data object HeldPendingResolution : AutosaveStatus

  data class Error(
    val message: String,
    val isTerminal: Boolean = false,
  ) : AutosaveStatus
}

/**
 * Estado imutável da interface do editor de notas, preparado para receber coordenação
 * nas Subetapas 4.3.3 e 4.3.4 sem lógica de persistência embutida nesta camada.
 */
data class NoteEditorUiState(
  val phase: NoteEditorSessionPhase = NoteEditorSessionPhase.LOADING,
  val sessionIdentity: NoteEditorSessionIdentity? = null,
  val baseline: NoteEditorBaseline? = null,
  val title: String = "",
  val editorState: NoteEditorState =
    NoteEditorState(document = NoteContentDocument.empty(), selection = null),
  val effectiveFolderId: Long? = null,
  val effectiveFolderName: String? = null,
  val fallbackSourceFolderId: Long? = null,
  val intendedUserFolderId: Long? = null,
  val paperStyle: NotePaperStyle = NotePaperStyle.LINHAS,
  val isPinned: Boolean = false,
  val isExistingNote: Boolean = false,
  val hasUnsavedChanges: Boolean = false,
  val hasPendingFolderFallback: Boolean = false,
  val isLegacyIndeterminate: Boolean = false,
  val stateGeneration: Long = 0L,
  val lastSyncedGeneration: Long = 0L,
  val autosaveStatus: AutosaveStatus = AutosaveStatus.Idle,
  val consolidationReadiness: ConsolidationReadiness? = null,
  val activeConflict: RecoveryConflict? = null,
  val blockReason: SessionBlockReason? = null,
  val warnings: List<SessionWarning> = emptyList(),
  val errorMessage: String? = null,
  val isConsolidating: Boolean = false,
  val isSessionTerminated: Boolean = false,
) {
  val isLoading: Boolean
    get() = phase == NoteEditorSessionPhase.LOADING

  val isEditing: Boolean
    get() = phase == NoteEditorSessionPhase.EDITING

  val isBlocked: Boolean
    get() = phase == NoteEditorSessionPhase.BLOCKED

  val isError: Boolean
    get() = phase == NoteEditorSessionPhase.ERROR

  /**
   * Em `LOADING`, `BLOCKED` e `ERROR`, nenhuma alteração editável pendente é exposta como ativa.
   */
  val hasEditableUnsavedChanges: Boolean
    get() = phase == NoteEditorSessionPhase.EDITING && hasUnsavedChanges

  val canEdit: Boolean
    get() = phase == NoteEditorSessionPhase.EDITING && !isConsolidating && !isSessionTerminated

  val canConsolidateNow: Boolean
    get() =
      phase == NoteEditorSessionPhase.EDITING &&
        !isConsolidating &&
        !isSessionTerminated &&
        consolidationReadiness is ConsolidationReadiness.Ready

  val needsExplicitFolderConfirmation: Boolean
    get() =
      phase == NoteEditorSessionPhase.EDITING &&
        consolidationReadiness is ConsolidationReadiness.RequiresExplicitFolderConfirmation

  val isSessionFullySynced: Boolean
    get() =
      phase == NoteEditorSessionPhase.EDITING &&
        stateGeneration == lastSyncedGeneration &&
        autosaveStatus !is AutosaveStatus.PendingDebounce &&
        autosaveStatus !is AutosaveStatus.Saving &&
        autosaveStatus !is AutosaveStatus.Error

  companion object {
    fun loading(): NoteEditorUiState = NoteEditorUiState(phase = NoteEditorSessionPhase.LOADING)

    fun blocked(
      reason: SessionBlockReason,
      warnings: List<SessionWarning> = emptyList(),
    ): NoteEditorUiState =
      NoteEditorUiState(
        phase = NoteEditorSessionPhase.BLOCKED,
        hasUnsavedChanges = false,
        blockReason = reason,
        consolidationReadiness = ConsolidationReadiness.BlockedBySessionState(reason),
        warnings = warnings,
      )

    fun error(
      message: String,
      sessionIdentity: NoteEditorSessionIdentity? = null,
    ): NoteEditorUiState =
      NoteEditorUiState(
        phase = NoteEditorSessionPhase.ERROR,
        sessionIdentity = sessionIdentity,
        hasUnsavedChanges = false,
        errorMessage = message,
      )

    fun fromEvaluation(
      phase: NoteEditorSessionPhase,
      sessionIdentity: NoteEditorSessionIdentity?,
      baseline: NoteEditorBaseline?,
      snapshot: NoteEditorEditableSnapshot,
      editorState: NoteEditorState,
      evaluation: NoteEditorSessionEvaluation,
      effectiveFolderName: String? = null,
      stateGeneration: Long = 0L,
      lastSyncedGeneration: Long = 0L,
      autosaveStatus: AutosaveStatus = AutosaveStatus.Idle,
      activeConflict: RecoveryConflict? = null,
      blockReason: SessionBlockReason? = null,
      errorMessage: String? = null,
      isConsolidating: Boolean = false,
      isSessionTerminated: Boolean = false,
    ): NoteEditorUiState {
      val editableOnlyHasChanges =
        if (phase == NoteEditorSessionPhase.EDITING) evaluation.hasUnsavedChanges else false
      return NoteEditorUiState(
        phase = phase,
        sessionIdentity = sessionIdentity,
        baseline = baseline,
        title = snapshot.title,
        editorState = editorState,
        effectiveFolderId = evaluation.effectiveFolderId,
        effectiveFolderName = effectiveFolderName,
        fallbackSourceFolderId = evaluation.fallbackSourceFolderId,
        intendedUserFolderId = evaluation.intendedUserFolderId,
        paperStyle = snapshot.paperStyle,
        isPinned = snapshot.isPinned,
        isExistingNote = sessionIdentity?.targetNoteId != null,
        hasUnsavedChanges = editableOnlyHasChanges,
        hasPendingFolderFallback = evaluation.hasPendingFolderFallback,
        isLegacyIndeterminate = baseline is NoteEditorBaseline.LegacyIndeterminate,
        stateGeneration = stateGeneration,
        lastSyncedGeneration = lastSyncedGeneration,
        autosaveStatus = autosaveStatus,
        consolidationReadiness = evaluation.consolidationReadiness,
        activeConflict = activeConflict,
        blockReason = blockReason,
        warnings = evaluation.warnings,
        errorMessage = errorMessage,
        isConsolidating = isConsolidating,
        isSessionTerminated = isSessionTerminated,
      )
    }
  }
}
