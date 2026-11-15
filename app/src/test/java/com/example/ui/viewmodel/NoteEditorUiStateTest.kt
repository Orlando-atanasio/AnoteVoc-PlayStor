package com.example.ui.viewmodel

import com.example.domain.editor.ConsolidationReadiness
import com.example.domain.editor.DirtyChangeBreakdown
import com.example.domain.editor.FolderAvailability
import com.example.domain.editor.NoteEditorBaseline
import com.example.domain.editor.NoteEditorEditableSnapshot
import com.example.domain.editor.NoteEditorSessionEvaluation
import com.example.domain.editor.NoteEditorSessionIdentity
import com.example.domain.editor.NoteEditorState
import com.example.domain.editor.SessionBlockReason
import com.example.domain.editor.SessionWarning
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.NoteRecoveryDraftKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteEditorUiStateTest {

  @Test
  fun loadingBlockedAndErrorStates_neverExposeEditableUnsavedChangesOrConsolidation() {
    val loading = NoteEditorUiState.loading()
    assertTrue(loading.isLoading)
    assertFalse(loading.isEditing)
    assertFalse(loading.hasUnsavedChanges)
    assertFalse(loading.hasEditableUnsavedChanges)
    assertFalse(loading.canEdit)
    assertFalse(loading.canConsolidateNow)
    assertFalse(loading.needsExplicitFolderConfirmation)
    assertFalse(loading.isSessionFullySynced)

    val blocked =
      NoteEditorUiState.blocked(
        reason = SessionBlockReason.DraftNotFound("new_1"),
        warnings = listOf(SessionWarning.LegacyDraftIndeterminateBaseline("new_1")),
      )
    assertTrue(blocked.isBlocked)
    assertFalse(blocked.hasUnsavedChanges)
    assertFalse(blocked.hasEditableUnsavedChanges)
    assertFalse(blocked.canEdit)
    assertFalse(blocked.canConsolidateNow)
    assertTrue(blocked.consolidationReadiness is ConsolidationReadiness.BlockedBySessionState)
    assertEquals(1, blocked.warnings.size)

    val error = NoteEditorUiState.error("Falha ao carregar")
    assertTrue(error.isError)
    assertFalse(error.hasUnsavedChanges)
    assertFalse(error.hasEditableUnsavedChanges)
    assertFalse(error.canEdit)
    assertFalse(error.canConsolidateNow)
    assertEquals("Falha ao carregar", error.errorMessage)
  }

  @Test
  fun fromEvaluation_inNonEditingPhase_clampsHasUnsavedChangesToFalse() {
    val identity =
      NoteEditorSessionIdentity(
        draftId = NoteRecoveryDraftKeys.forNewNote("s-clamp"),
        sessionId = "s-clamp",
        targetNoteId = null,
      )
    val snapshot =
      NoteEditorEditableSnapshot(
        title = "Alterado",
        document = NoteContentDocument.empty(),
        effectiveFolderId = null,
        fallbackSourceFolderId = null,
        paperStyle = NotePaperStyle.LINHAS,
        isPinned = false,
      )
    val evalSayingDirty =
      NoteEditorSessionEvaluation(
        hasUnsavedChanges = true,
        dirtyBreakdown = DirtyChangeBreakdown(titleChanged = true),
        intendedUserFolderId = null,
        effectiveFolderId = null,
        fallbackSourceFolderId = null,
        hasPendingFolderFallback = false,
        needsDraftRetention = true,
        canSafelyReconcileToCleanBaseline = false,
        consolidationReadiness = ConsolidationReadiness.Ready(null),
        warnings = emptyList(),
      )

    val uiBlocked =
      NoteEditorUiState.fromEvaluation(
        phase = NoteEditorSessionPhase.BLOCKED,
        sessionIdentity = identity,
        baseline = null,
        snapshot = snapshot,
        editorState = NoteEditorState(document = NoteContentDocument.empty(), selection = null),
        evaluation = evalSayingDirty,
        blockReason = SessionBlockReason.TargetNoteInTrash(1L),
      )

    assertFalse(uiBlocked.hasUnsavedChanges)
    assertFalse(uiBlocked.hasEditableUnsavedChanges)
    assertFalse(uiBlocked.canEdit)
    assertFalse(uiBlocked.canConsolidateNow)
  }

  @Test
  fun fromEvaluation_inEditingPhase_mapsIndicatorsFallbackAndSyncStatusAccurately() {
    val existingIdentity =
      NoteEditorSessionIdentity(
        draftId = NoteRecoveryDraftKeys.forExistingNote(15L, "s-edit"),
        sessionId = "s-edit",
        targetNoteId = 15L,
      )
    val baseline =
      NoteEditorBaseline.ExistingNote(
        noteId = 15L,
        baseRevision = 2L,
        baseNoteUpdatedAt = 2000L,
        title = "Orig",
        normalizedContent = NoteContentDocument.empty(),
        paperStyle = NotePaperStyle.SEM_LINHAS,
        staticFolderId = 5L,
        currentDbIsPinned = false,
      )
    val snapshot =
      NoteEditorEditableSnapshot(
        title = "Orig alterado",
        document = NoteContentDocument.empty(),
        effectiveFolderId = null,
        fallbackSourceFolderId = 9L,
        paperStyle = NotePaperStyle.SEM_LINHAS,
        isPinned = false,
      )
    val evalWithFallback =
      NoteEditorSessionEvaluation(
        hasUnsavedChanges = true,
        dirtyBreakdown = DirtyChangeBreakdown(titleChanged = true, folderChanged = true),
        intendedUserFolderId = 9L,
        effectiveFolderId = null,
        fallbackSourceFolderId = 9L,
        hasPendingFolderFallback = true,
        needsDraftRetention = true,
        canSafelyReconcileToCleanBaseline = false,
        consolidationReadiness =
          ConsolidationReadiness.RequiresExplicitFolderConfirmation(
            fallbackSourceFolderId = 9L,
            fallbackSourceAvailability = FolderAvailability.NotFound(9L),
          ),
        warnings =
          listOf(
            SessionWarning.FallbackFolderApplied(9L, FolderAvailability.NotFound(9L))
          ),
      )

    val uiState =
      NoteEditorUiState.fromEvaluation(
        phase = NoteEditorSessionPhase.EDITING,
        sessionIdentity = existingIdentity,
        baseline = baseline,
        snapshot = snapshot,
        editorState = NoteEditorState(document = NoteContentDocument.empty(), selection = null),
        evaluation = evalWithFallback,
        effectiveFolderName = null,
        stateGeneration = 4L,
        lastSyncedGeneration = 4L,
        autosaveStatus = AutosaveStatus.Saved(syncedGeneration = 4L, checkpointAt = 9999L),
      )

    assertTrue(uiState.isEditing)
    assertTrue(uiState.isExistingNote)
    assertTrue(uiState.hasUnsavedChanges)
    assertTrue(uiState.hasEditableUnsavedChanges)
    assertTrue(uiState.hasPendingFolderFallback)
    assertFalse(uiState.isLegacyIndeterminate)
    assertEquals(9L, uiState.intendedUserFolderId)
    assertNull(uiState.effectiveFolderId)
    assertEquals(9L, uiState.fallbackSourceFolderId)
    assertTrue(uiState.canEdit)
    assertFalse(uiState.canConsolidateNow)
    assertTrue(uiState.needsExplicitFolderConfirmation)
    assertTrue(uiState.isSessionFullySynced)

    // Enquanto pendente ou salvando ou com geração divergente, não está totalmente sincronizado
    assertFalse(uiState.copy(stateGeneration = 5L).isSessionFullySynced)
    assertFalse(uiState.copy(autosaveStatus = AutosaveStatus.PendingDebounce).isSessionFullySynced)
    assertFalse(uiState.copy(autosaveStatus = AutosaveStatus.Saving).isSessionFullySynced)
    assertFalse(
      uiState.copy(autosaveStatus = AutosaveStatus.Error("Erro", isTerminal = false)).isSessionFullySynced
    )

    // Durante consolidação ou término, edição e consolidação ficam desabilitadas
    val readyState =
      uiState.copy(
        consolidationReadiness = ConsolidationReadiness.Ready(null),
        isConsolidating = false,
        isSessionTerminated = false,
      )
    assertTrue(readyState.canConsolidateNow)
    assertFalse(readyState.copy(isConsolidating = true).canConsolidateNow)
    assertFalse(readyState.copy(isConsolidating = true).canEdit)
    assertFalse(readyState.copy(isSessionTerminated = true).canConsolidateNow)
    assertFalse(readyState.copy(isSessionTerminated = true).canEdit)
  }

  @Test
  fun fromEvaluation_legacyBaseline_marksIsLegacyIndeterminate() {
    val identity =
      NoteEditorSessionIdentity(
        draftId = NoteRecoveryDraftKeys.forNewNote("legacy-ui"),
        sessionId = "legacy-ui",
        targetNoteId = null,
      )
    val legacyUi =
      NoteEditorUiState.fromEvaluation(
        phase = NoteEditorSessionPhase.EDITING,
        sessionIdentity = identity,
        baseline = NoteEditorBaseline.LegacyIndeterminate(identity.draftId),
        snapshot =
          NoteEditorEditableSnapshot(
            title = "",
            document = NoteContentDocument.empty(),
            effectiveFolderId = null,
            fallbackSourceFolderId = null,
            paperStyle = NotePaperStyle.LINHAS,
            isPinned = false,
          ),
        editorState = NoteEditorState(document = NoteContentDocument.empty(), selection = null),
        evaluation =
          NoteEditorSessionEvaluation(
            hasUnsavedChanges = true,
            dirtyBreakdown = DirtyChangeBreakdown(forcedByLegacyIndeterminate = true),
            intendedUserFolderId = null,
            effectiveFolderId = null,
            fallbackSourceFolderId = null,
            hasPendingFolderFallback = false,
            needsDraftRetention = true,
            canSafelyReconcileToCleanBaseline = false,
            consolidationReadiness = ConsolidationReadiness.Ready(null),
            warnings = listOf(SessionWarning.LegacyDraftIndeterminateBaseline(identity.draftId)),
          ),
      )

    assertTrue(legacyUi.isLegacyIndeterminate)
    assertFalse(legacyUi.isExistingNote)
    assertTrue(legacyUi.hasEditableUnsavedChanges)
    assertTrue(legacyUi.canConsolidateNow)
  }
}
