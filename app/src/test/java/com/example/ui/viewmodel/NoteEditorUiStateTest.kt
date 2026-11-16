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

  @Test
  fun isSessionFullySynced_rejectsPendingSavingHeldErrorConsolidatingAndIncoherentSavedGeneration() {
    val baseSynced =
      NoteEditorUiState(
        phase = NoteEditorSessionPhase.EDITING,
        stateGeneration = 4L,
        lastSyncedGeneration = 4L,
        autosaveStatus = AutosaveStatus.Saved(syncedGeneration = 4L, checkpointAt = 1000L),
        isConsolidating = false,
      )
    assertTrue(baseSynced.isSessionFullySynced)

    // Idle com gerações alinhadas em EDITING sem consolidação também está sincronizado
    assertTrue(
      NoteEditorUiState(
        phase = NoteEditorSessionPhase.EDITING,
        stateGeneration = 0L,
        lastSyncedGeneration = 0L,
        autosaveStatus = AutosaveStatus.Idle,
        isConsolidating = false,
      ).isSessionFullySynced
    )

    // Fases não editáveis nunca indicam sincronização completa
    assertFalse(baseSynced.copy(phase = NoteEditorSessionPhase.LOADING).isSessionFullySynced)
    assertFalse(baseSynced.copy(phase = NoteEditorSessionPhase.BLOCKED).isSessionFullySynced)
    assertFalse(baseSynced.copy(phase = NoteEditorSessionPhase.ERROR).isSessionFullySynced)

    // Divergência de gerações
    assertFalse(baseSynced.copy(stateGeneration = 5L).isSessionFullySynced)

    // Estados de autosave pendentes, em execução, retidos ou em erro
    assertFalse(baseSynced.copy(autosaveStatus = AutosaveStatus.PendingDebounce).isSessionFullySynced)
    assertFalse(baseSynced.copy(autosaveStatus = AutosaveStatus.Saving).isSessionFullySynced)
    assertFalse(
      baseSynced.copy(autosaveStatus = AutosaveStatus.HeldPendingResolution).isSessionFullySynced
    )
    assertFalse(
      baseSynced.copy(autosaveStatus = AutosaveStatus.Error("Erro", isTerminal = false)).isSessionFullySynced
    )

    // Consolidação em curso
    assertFalse(baseSynced.copy(isConsolidating = true).isSessionFullySynced)

    // Falso positivo evitado: Saved com syncedGeneration incoerente em relação a lastSyncedGeneration
    assertFalse(
      baseSynced
        .copy(autosaveStatus = AutosaveStatus.Saved(syncedGeneration = 3L, checkpointAt = 900L))
        .isSessionFullySynced
    )
  }

  @Test
  fun defensiveCopies_inNoteEditorUiState_isolateWarningsAndEditorStateCollections() {
    val mutableSpans =
      mutableListOf(
        com.example.domain.model.FormatSpan(0, 3, com.example.domain.model.TextFormatStyle.BOLD)
      )
    val mutableBlocks =
      mutableListOf<com.example.domain.model.ContentBlock>(
        com.example.domain.model.ParagraphBlock(id = "p1", text = "Olá", spans = mutableSpans)
      )
    val mutableStyles = mutableSetOf(com.example.domain.model.TextFormatStyle.BOLD)
    val mutableNumbers = mutableMapOf("n1" to 1)
    val mutableWarnings =
      mutableListOf<SessionWarning>(SessionWarning.CurrentDestinationUnknown(77L))

    val uiState =
      NoteEditorUiState(
        phase = NoteEditorSessionPhase.EDITING,
        editorState =
          NoteEditorState(
            document = NoteContentDocument(blocks = mutableBlocks),
            selection = null,
            activeTypingStyles = mutableStyles,
            numberedListNumbers = mutableNumbers,
          ),
        warnings = mutableWarnings,
      )
    val copiedState = uiState.copy(title = "Copiado")

    // Mutação posterior das coleções mutáveis fornecidas pelo chamador
    mutableSpans.clear()
    mutableBlocks.clear()
    mutableStyles.add(com.example.domain.model.TextFormatStyle.ITALIC)
    mutableNumbers["n2"] = 2
    mutableWarnings.clear()

    assertEquals(1, uiState.editorState.document.blocks.size)
    assertEquals(1, uiState.editorState.document.blocks[0].spans.size)
    assertEquals(setOf(com.example.domain.model.TextFormatStyle.BOLD), uiState.editorState.activeTypingStyles)
    assertEquals(mapOf("n1" to 1), uiState.editorState.numberedListNumbers)
    assertEquals(1, uiState.warnings.size)
    assertEquals(1, copiedState.warnings.size)
    assertEquals(1, copiedState.editorState.document.blocks.size)

    org.junit.Assert.assertThrows(UnsupportedOperationException::class.java) {
      @Suppress("UNCHECKED_CAST")
      (uiState.warnings as MutableList<SessionWarning>).add(
        SessionWarning.LegacyDraftIndeterminateBaseline("new_z")
      )
    }
    org.junit.Assert.assertThrows(UnsupportedOperationException::class.java) {
      @Suppress("UNCHECKED_CAST")
      (uiState.editorState.activeTypingStyles as MutableSet<com.example.domain.model.TextFormatStyle>).clear()
    }
    org.junit.Assert.assertThrows(UnsupportedOperationException::class.java) {
      @Suppress("UNCHECKED_CAST")
      (uiState.editorState.numberedListNumbers as MutableMap<String, Int>).clear()
    }
  }
}
