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
import com.example.domain.editor.toImmutableSnapshot
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NotePaperStyle
import java.util.Collections

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
  /**
   * Estado inicial de sessão recém-aberta (ou reconciliada na baseline limpa), sem operação
   * de checkpoint pendente ou em andamento. Quando `phase == EDITING`, `!isConsolidating` e
   * `stateGeneration == lastSyncedGeneration`, indica ausência de trabalho pendente de autosave.
   */
  data object Idle : AutosaveStatus

  data object PendingDebounce : AutosaveStatus

  data object Saving : AutosaveStatus

  /**
   * Última gravação bem-sucedida de checkpoint concluída até [syncedGeneration].
   * Só caracteriza sessão totalmente sincronizada quando `syncedGeneration == lastSyncedGeneration`
   * e `lastSyncedGeneration == stateGeneration`.
   */
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
 *
 * Aplica cópia defensiva para coleções de [editorState] e [warnings] na construção e em [copy].
 */
class NoteEditorUiState private constructor(
  val phase: NoteEditorSessionPhase,
  val sessionIdentity: NoteEditorSessionIdentity?,
  val baseline: NoteEditorBaseline?,
  val title: String,
  val editorState: NoteEditorState,
  val effectiveFolderId: Long?,
  val effectiveFolderName: String?,
  val fallbackSourceFolderId: Long?,
  val intendedUserFolderId: Long?,
  val paperStyle: NotePaperStyle,
  val isPinned: Boolean,
  val isExistingNote: Boolean,
  val hasUnsavedChanges: Boolean,
  val hasPendingFolderFallback: Boolean,
  val isLegacyIndeterminate: Boolean,
  val stateGeneration: Long,
  val lastSyncedGeneration: Long,
  val autosaveStatus: AutosaveStatus,
  val consolidationReadiness: ConsolidationReadiness?,
  val activeConflict: RecoveryConflict?,
  val blockReason: SessionBlockReason?,
  val warnings: List<SessionWarning>,
  val errorMessage: String?,
  val isConsolidating: Boolean,
  val isSessionTerminated: Boolean,
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

  /**
   * Indica que não há edições não sincronizadas nem operação de persistência/consolidação
   * pendente, retida ou em erro, exigindo coerência entre [stateGeneration],
   * [lastSyncedGeneration] e [AutosaveStatus.Saved.syncedGeneration].
   */
  val isSessionFullySynced: Boolean
    get() {
      if (phase != NoteEditorSessionPhase.EDITING) return false
      if (isConsolidating) return false
      if (stateGeneration != lastSyncedGeneration) return false
      return when (val status = autosaveStatus) {
        AutosaveStatus.Idle -> true
        is AutosaveStatus.Saved -> status.syncedGeneration == lastSyncedGeneration
        AutosaveStatus.PendingDebounce,
        AutosaveStatus.Saving,
        AutosaveStatus.HeldPendingResolution,
        is AutosaveStatus.Error -> false
      }
    }

  fun copy(
    phase: NoteEditorSessionPhase = this.phase,
    sessionIdentity: NoteEditorSessionIdentity? = this.sessionIdentity,
    baseline: NoteEditorBaseline? = this.baseline,
    title: String = this.title,
    editorState: NoteEditorState = this.editorState,
    effectiveFolderId: Long? = this.effectiveFolderId,
    effectiveFolderName: String? = this.effectiveFolderName,
    fallbackSourceFolderId: Long? = this.fallbackSourceFolderId,
    intendedUserFolderId: Long? = this.intendedUserFolderId,
    paperStyle: NotePaperStyle = this.paperStyle,
    isPinned: Boolean = this.isPinned,
    isExistingNote: Boolean = this.isExistingNote,
    hasUnsavedChanges: Boolean = this.hasUnsavedChanges,
    hasPendingFolderFallback: Boolean = this.hasPendingFolderFallback,
    isLegacyIndeterminate: Boolean = this.isLegacyIndeterminate,
    stateGeneration: Long = this.stateGeneration,
    lastSyncedGeneration: Long = this.lastSyncedGeneration,
    autosaveStatus: AutosaveStatus = this.autosaveStatus,
    consolidationReadiness: ConsolidationReadiness? = this.consolidationReadiness,
    activeConflict: RecoveryConflict? = this.activeConflict,
    blockReason: SessionBlockReason? = this.blockReason,
    warnings: List<SessionWarning> = this.warnings,
    errorMessage: String? = this.errorMessage,
    isConsolidating: Boolean = this.isConsolidating,
    isSessionTerminated: Boolean = this.isSessionTerminated,
  ): NoteEditorUiState =
    invoke(
      phase = phase,
      sessionIdentity = sessionIdentity,
      baseline = baseline,
      title = title,
      editorState = editorState,
      effectiveFolderId = effectiveFolderId,
      effectiveFolderName = effectiveFolderName,
      fallbackSourceFolderId = fallbackSourceFolderId,
      intendedUserFolderId = intendedUserFolderId,
      paperStyle = paperStyle,
      isPinned = isPinned,
      isExistingNote = isExistingNote,
      hasUnsavedChanges = hasUnsavedChanges,
      hasPendingFolderFallback = hasPendingFolderFallback,
      isLegacyIndeterminate = isLegacyIndeterminate,
      stateGeneration = stateGeneration,
      lastSyncedGeneration = lastSyncedGeneration,
      autosaveStatus = autosaveStatus,
      consolidationReadiness = consolidationReadiness,
      activeConflict = activeConflict,
      blockReason = blockReason,
      warnings = warnings,
      errorMessage = errorMessage,
      isConsolidating = isConsolidating,
      isSessionTerminated = isSessionTerminated,
    )

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is NoteEditorUiState) return false
    return phase == other.phase &&
      sessionIdentity == other.sessionIdentity &&
      baseline == other.baseline &&
      title == other.title &&
      editorState == other.editorState &&
      effectiveFolderId == other.effectiveFolderId &&
      effectiveFolderName == other.effectiveFolderName &&
      fallbackSourceFolderId == other.fallbackSourceFolderId &&
      intendedUserFolderId == other.intendedUserFolderId &&
      paperStyle == other.paperStyle &&
      isPinned == other.isPinned &&
      isExistingNote == other.isExistingNote &&
      hasUnsavedChanges == other.hasUnsavedChanges &&
      hasPendingFolderFallback == other.hasPendingFolderFallback &&
      isLegacyIndeterminate == other.isLegacyIndeterminate &&
      stateGeneration == other.stateGeneration &&
      lastSyncedGeneration == other.lastSyncedGeneration &&
      autosaveStatus == other.autosaveStatus &&
      consolidationReadiness == other.consolidationReadiness &&
      activeConflict == other.activeConflict &&
      blockReason == other.blockReason &&
      warnings == other.warnings &&
      errorMessage == other.errorMessage &&
      isConsolidating == other.isConsolidating &&
      isSessionTerminated == other.isSessionTerminated
  }

  override fun hashCode(): Int {
    var result = phase.hashCode()
    result = 31 * result + (sessionIdentity?.hashCode() ?: 0)
    result = 31 * result + (baseline?.hashCode() ?: 0)
    result = 31 * result + title.hashCode()
    result = 31 * result + editorState.hashCode()
    result = 31 * result + (effectiveFolderId?.hashCode() ?: 0)
    result = 31 * result + (effectiveFolderName?.hashCode() ?: 0)
    result = 31 * result + (fallbackSourceFolderId?.hashCode() ?: 0)
    result = 31 * result + (intendedUserFolderId?.hashCode() ?: 0)
    result = 31 * result + paperStyle.hashCode()
    result = 31 * result + isPinned.hashCode()
    result = 31 * result + isExistingNote.hashCode()
    result = 31 * result + hasUnsavedChanges.hashCode()
    result = 31 * result + hasPendingFolderFallback.hashCode()
    result = 31 * result + isLegacyIndeterminate.hashCode()
    result = 31 * result + stateGeneration.hashCode()
    result = 31 * result + lastSyncedGeneration.hashCode()
    result = 31 * result + autosaveStatus.hashCode()
    result = 31 * result + (consolidationReadiness?.hashCode() ?: 0)
    result = 31 * result + (activeConflict?.hashCode() ?: 0)
    result = 31 * result + (blockReason?.hashCode() ?: 0)
    result = 31 * result + warnings.hashCode()
    result = 31 * result + (errorMessage?.hashCode() ?: 0)
    result = 31 * result + isConsolidating.hashCode()
    result = 31 * result + isSessionTerminated.hashCode()
    return result
  }

  override fun toString(): String =
    "NoteEditorUiState(phase=$phase, sessionIdentity=$sessionIdentity, isExistingNote=$isExistingNote, " +
      "hasUnsavedChanges=$hasUnsavedChanges, stateGeneration=$stateGeneration, " +
      "lastSyncedGeneration=$lastSyncedGeneration, autosaveStatus=$autosaveStatus, " +
      "consolidationReadiness=$consolidationReadiness, isConsolidating=$isConsolidating, " +
      "isSessionTerminated=$isSessionTerminated)"

  companion object {
    operator fun invoke(
      phase: NoteEditorSessionPhase = NoteEditorSessionPhase.LOADING,
      sessionIdentity: NoteEditorSessionIdentity? = null,
      baseline: NoteEditorBaseline? = null,
      title: String = "",
      editorState: NoteEditorState =
        NoteEditorState(document = NoteContentDocument.empty(), selection = null),
      effectiveFolderId: Long? = null,
      effectiveFolderName: String? = null,
      fallbackSourceFolderId: Long? = null,
      intendedUserFolderId: Long? = null,
      paperStyle: NotePaperStyle = NotePaperStyle.LINHAS,
      isPinned: Boolean = false,
      isExistingNote: Boolean = false,
      hasUnsavedChanges: Boolean = false,
      hasPendingFolderFallback: Boolean = false,
      isLegacyIndeterminate: Boolean = false,
      stateGeneration: Long = 0L,
      lastSyncedGeneration: Long = 0L,
      autosaveStatus: AutosaveStatus = AutosaveStatus.Idle,
      consolidationReadiness: ConsolidationReadiness? = null,
      activeConflict: RecoveryConflict? = null,
      blockReason: SessionBlockReason? = null,
      warnings: List<SessionWarning> = emptyList(),
      errorMessage: String? = null,
      isConsolidating: Boolean = false,
      isSessionTerminated: Boolean = false,
    ): NoteEditorUiState =
      NoteEditorUiState(
        phase = phase,
        sessionIdentity = sessionIdentity,
        baseline = baseline,
        title = title,
        editorState = editorState.toImmutableSnapshot(),
        effectiveFolderId = effectiveFolderId,
        effectiveFolderName = effectiveFolderName,
        fallbackSourceFolderId = fallbackSourceFolderId,
        intendedUserFolderId = intendedUserFolderId,
        paperStyle = paperStyle,
        isPinned = isPinned,
        isExistingNote = isExistingNote,
        hasUnsavedChanges = hasUnsavedChanges,
        hasPendingFolderFallback = hasPendingFolderFallback,
        isLegacyIndeterminate = isLegacyIndeterminate,
        stateGeneration = stateGeneration,
        lastSyncedGeneration = lastSyncedGeneration,
        autosaveStatus = autosaveStatus,
        consolidationReadiness = consolidationReadiness,
        activeConflict = activeConflict,
        blockReason = blockReason,
        warnings = Collections.unmodifiableList(warnings.toList()),
        errorMessage = errorMessage,
        isConsolidating = isConsolidating,
        isSessionTerminated = isSessionTerminated,
      )

    fun loading(): NoteEditorUiState = invoke(phase = NoteEditorSessionPhase.LOADING)

    fun blocked(
      reason: SessionBlockReason,
      warnings: List<SessionWarning> = emptyList(),
    ): NoteEditorUiState =
      invoke(
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
      invoke(
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
      return invoke(
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
