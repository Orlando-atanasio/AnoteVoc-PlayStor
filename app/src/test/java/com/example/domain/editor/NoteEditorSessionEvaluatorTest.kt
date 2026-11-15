package com.example.domain.editor

import com.example.domain.model.BulletListItemBlock
import com.example.domain.model.ChecklistItemBlock
import com.example.domain.model.Folder
import com.example.domain.model.FormatSpan
import com.example.domain.model.Note
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NoteContentState
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.NoteRecoveryDraft
import com.example.domain.model.NoteRecoveryDraftKeys
import com.example.domain.model.NumberedListItemBlock
import com.example.domain.model.ParagraphBlock
import com.example.domain.model.TextFormatStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteEditorSessionEvaluatorTest {

  @Test
  fun createNew_withActiveFolder_startsCleanAndReadyForConsolidation() {
    val folderA = sampleFolder(id = 10L, name = "Trabalho")
    val result =
      NoteEditorSessionEvaluator.evaluateOpening(
        request =
          NoteEditorOpenRequest.CreateNew(
            initialFolderId = folderA.id,
            defaultPaperStyle = NotePaperStyle.PONTILHADO,
            initialPinned = true,
            sessionId = "sess-1",
          ),
        folderAvailabilityById = mapOf(folderA.id to FolderAvailability.Active(folderA)),
      )

    val ready = result as RecoveryOpeningEvaluation.ReadyToEdit
    assertEquals(NoteRecoveryDraftKeys.forNewNote("sess-1"), ready.identity.draftId)
    assertNull(ready.identity.targetNoteId)

    val baseline = ready.baseline as NoteEditorBaseline.NewNote
    assertEquals("", baseline.title)
    assertEquals(folderA.id, baseline.staticFolderId)
    assertEquals(NotePaperStyle.PONTILHADO, baseline.paperStyle)
    assertTrue(baseline.initialIsPinned)

    assertEquals(folderA.id, ready.initialSnapshot.effectiveFolderId)
    assertNull(ready.initialSnapshot.fallbackSourceFolderId)
    assertFalse(ready.evaluation.hasUnsavedChanges)
    assertFalse(ready.evaluation.needsDraftRetention)
    assertTrue(ready.evaluation.canSafelyReconcileToCleanBaseline)
    assertEquals(ConsolidationReadiness.Ready(folderA.id), ready.evaluation.consolidationReadiness)
    assertTrue(ready.evaluation.warnings.isEmpty())
  }

  @Test
  fun createNew_withTrashedInitialFolder_appliesFallbackWithoutMarkingDirty() {
    val trashedFolder = sampleFolder(id = 20L, name = "Arquivo", isDeleted = true)
    val result =
      NoteEditorSessionEvaluator.evaluateOpening(
        request =
          NoteEditorOpenRequest.CreateNew(
            initialFolderId = trashedFolder.id,
            defaultPaperStyle = NotePaperStyle.LINHAS,
            sessionId = "sess-2",
          ),
        folderAvailabilityById = mapOf(trashedFolder.id to FolderAvailability.InTrash(trashedFolder)),
      )

    val ready = result as RecoveryOpeningEvaluation.ReadyToEdit
    assertNull(ready.initialSnapshot.effectiveFolderId)
    assertEquals(trashedFolder.id, ready.initialSnapshot.fallbackSourceFolderId)
    assertEquals(trashedFolder.id, ready.evaluation.intendedUserFolderId)
    assertFalse(ready.evaluation.hasUnsavedChanges)
    assertFalse(ready.evaluation.dirtyBreakdown.folderChanged)
    assertTrue(ready.evaluation.hasPendingFolderFallback)
    assertTrue(ready.evaluation.needsDraftRetention)
    assertFalse(ready.evaluation.canSafelyReconcileToCleanBaseline)
    assertTrue(
      ready.evaluation.consolidationReadiness is ConsolidationReadiness.RequiresExplicitFolderConfirmation
    )
    assertTrue(
      ready.evaluation.warnings.any { it is SessionWarning.FallbackFolderApplied }
    )
    assertTrue(
      ready.evaluation.warnings.any { it is SessionWarning.OriginalBaselineFolderUnavailable }
    )
  }

  @Test
  fun createNew_detectsDirtyChangesAcrossAllFiveAxes() {
    val folderA = sampleFolder(id = 1L, name = "A")
    val folderB = sampleFolder(id = 2L, name = "B")
    val opening =
      NoteEditorSessionEvaluator.evaluateOpening(
        request =
          NoteEditorOpenRequest.CreateNew(
            initialFolderId = folderA.id,
            defaultPaperStyle = NotePaperStyle.LINHAS,
            initialPinned = false,
            sessionId = "sess-axes",
          ),
        folderAvailabilityById =
          mapOf(
            folderA.id to FolderAvailability.Active(folderA),
            folderB.id to FolderAvailability.Active(folderB),
          ),
      ) as RecoveryOpeningEvaluation.ReadyToEdit

    val folderContext =
      ResolvedDraftFolderContext(
        effectiveTarget = FolderAvailability.Active(folderB),
        initialBaseline = FolderAvailability.Active(folderA),
      )

    // 1. Título sem trim (" " conta como alteração real)
    val titleEval =
      NoteEditorSessionEvaluator.evaluateSession(
        NoteEditorSessionEvaluationInput(
          lifecycleStage = SessionLifecycleStage.EDITING,
          baseline = opening.baseline,
          snapshot = opening.initialSnapshot.copy(title = " "),
          folderContext = opening.folderContext,
        )
      )
    assertTrue(titleEval.hasUnsavedChanges)
    assertTrue(titleEval.dirtyBreakdown.titleChanged)

    // 2. Conteúdo
    val contentEval =
      NoteEditorSessionEvaluator.evaluateSession(
        NoteEditorSessionEvaluationInput(
          lifecycleStage = SessionLifecycleStage.EDITING,
          baseline = opening.baseline,
          snapshot =
            opening.initialSnapshot.copy(
              document =
                NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Texto")))
            ),
          folderContext = opening.folderContext,
        )
      )
    assertTrue(contentEval.dirtyBreakdown.contentChanged)

    // 3. Pasta
    val folderEval =
      NoteEditorSessionEvaluator.evaluateSession(
        NoteEditorSessionEvaluationInput(
          lifecycleStage = SessionLifecycleStage.EDITING,
          baseline = opening.baseline,
          snapshot = opening.initialSnapshot.copy(effectiveFolderId = folderB.id),
          folderContext = folderContext,
        )
      )
    assertTrue(folderEval.dirtyBreakdown.folderChanged)

    // 4. Papel
    val paperEval =
      NoteEditorSessionEvaluator.evaluateSession(
        NoteEditorSessionEvaluationInput(
          lifecycleStage = SessionLifecycleStage.EDITING,
          baseline = opening.baseline,
          snapshot = opening.initialSnapshot.copy(paperStyle = NotePaperStyle.OCEAN_BREEZE),
          folderContext = opening.folderContext,
        )
      )
    assertTrue(paperEval.dirtyBreakdown.paperStyleChanged)

    // 5. Pin (participa da baseline em Nova Nota)
    val pinEval =
      NoteEditorSessionEvaluator.evaluateSession(
        NoteEditorSessionEvaluationInput(
          lifecycleStage = SessionLifecycleStage.EDITING,
          baseline = opening.baseline,
          snapshot = opening.initialSnapshot.copy(isPinned = true),
          folderContext = opening.folderContext,
        )
      )
    assertTrue(pinEval.dirtyBreakdown.pinChanged)
  }

  @Test
  fun recoverDraft_legacyIndeterminateNewNote_neverInfersRootOrLinhasAndRemainsDirty() {
    val sessionId = "legacy-1"
    val legacyDraft =
      sampleNewDraft(
        sessionId = sessionId,
        folderId = null,
        initialFolderId = null,
        fallbackSourceFolderId = null,
        title = "",
        document = NoteContentDocument.empty(),
        paperStyle = NotePaperStyle.LINHAS,
        initialPaperStyle = null, // Rascunho legado sem baseline confiável
        isPinned = false,
      )

    val result =
      NoteEditorSessionEvaluator.evaluateOpening(
        request =
          NoteEditorOpenRequest.RecoverDraft(
            draftId = legacyDraft.draftId,
            expectedTarget = RecoveryTargetExpectation.NewNote,
          ),
        draft = legacyDraft,
      )

    val ready = result as RecoveryOpeningEvaluation.ReadyToEdit
    assertTrue(ready.baseline is NoteEditorBaseline.LegacyIndeterminate)
    assertTrue(ready.evaluation.hasUnsavedChanges)
    assertTrue(ready.evaluation.dirtyBreakdown.forcedByLegacyIndeterminate)
    assertTrue(ready.evaluation.needsDraftRetention)
    assertFalse(ready.evaluation.canSafelyReconcileToCleanBaseline)
    assertTrue(
      ready.evaluation.warnings.any { it is SessionWarning.LegacyDraftIndeterminateBaseline }
    )
  }

  @Test
  fun openAndRecoverExistingNote_usesDbBaseline_ignoresInitialMetadata_andIgnoresPinChanges() {
    val folderA = sampleFolder(id = 5L, name = "Pessoal")
    val dbNote =
      sampleNote(
        id = 42L,
        folderId = folderA.id,
        title = "Lista",
        document =
          NoteContentDocument(
            blocks = listOf(ParagraphBlock(id = "b1", text = "Item 1"))
          ),
        paperStyle = NotePaperStyle.SEM_LINHAS,
        revision = 3L,
        updatedAt = 3000L,
        isPinned = true,
      )

    val draftWithStaleMetadata =
      NoteRecoveryDraft(
        draftId = NoteRecoveryDraftKeys.forExistingNote(42L, "sess-ex"),
        targetNoteId = 42L,
        sessionId = "sess-ex",
        folderId = folderA.id,
        initialFolderId = 999L, // deve ser ignorado em nota existente
        fallbackSourceFolderId = null,
        isPinned = false, // divergiu do banco (true)
        title = "Lista",
        contentState =
          NoteContentState.Valid(
            NoteContentDocument(blocks = listOf(ParagraphBlock(id = "b1", text = "Item 1")))
          ),
        paperStyle = NotePaperStyle.SEM_LINHAS,
        initialPaperStyle = NotePaperStyle.OCEAN_BREEZE, // deve ser ignorado em nota existente
        baseRevision = 3L,
        checkpointRevision = 2L,
        baseNoteUpdatedAt = 3000L,
        checkpointAt = 4000L,
      )

    val opening =
      NoteEditorSessionEvaluator.evaluateOpening(
        request =
          NoteEditorOpenRequest.RecoverDraft(
            draftId = draftWithStaleMetadata.draftId,
            expectedTarget = RecoveryTargetExpectation.ExistingNote(42L),
          ),
        draft = draftWithStaleMetadata,
        existingNote = dbNote,
        folderAvailabilityById = mapOf(folderA.id to FolderAvailability.Active(folderA)),
      ) as RecoveryOpeningEvaluation.ReadyToEdit

    val baseline = opening.baseline as NoteEditorBaseline.ExistingNote
    assertEquals(folderA.id, baseline.staticFolderId)
    assertEquals(NotePaperStyle.SEM_LINHAS, baseline.paperStyle)
    assertTrue(baseline.currentDbIsPinned)

    // Snapshot segue o valor atual do banco e emite aviso de alteração externa
    assertTrue(opening.initialSnapshot.isPinned)
    assertTrue(
      opening.evaluation.warnings.contains(SessionWarning.ExistingNotePinUpdatedExternally(true))
    )
    assertFalse(opening.evaluation.hasUnsavedChanges)
    assertTrue(opening.evaluation.canSafelyReconcileToCleanBaseline)

    // Alterar isPinned em nota existente NUNCA marca o editor como dirty
    val afterPinToggle =
      NoteEditorSessionEvaluator.evaluateSession(
        NoteEditorSessionEvaluationInput(
          lifecycleStage = SessionLifecycleStage.EDITING,
          baseline = opening.baseline,
          snapshot = opening.initialSnapshot.copy(isPinned = false),
          folderContext = opening.folderContext,
        )
      )
    assertFalse(afterPinToggle.dirtyBreakdown.pinChanged)
    assertFalse(afterPinToggle.hasUnsavedChanges)
    assertTrue(afterPinToggle.canSafelyReconcileToCleanBaseline)
  }

  @Test
  fun recoverDraft_targetExpectationMismatch_returnsBlocked() {
    val newDraft =
      sampleNewDraft(
        sessionId = "s1",
        initialPaperStyle = NotePaperStyle.LINHAS,
      )
    val mismatchNewExpectedExisting =
      NoteEditorSessionEvaluator.evaluateOpening(
        request =
          NoteEditorOpenRequest.RecoverDraft(
            draftId = newDraft.draftId,
            expectedTarget = RecoveryTargetExpectation.ExistingNote(99L),
          ),
        draft = newDraft,
      ) as RecoveryOpeningEvaluation.Blocked

    assertTrue(mismatchNewExpectedExisting.reason is SessionBlockReason.DraftTargetMismatch)

    val existingDraft =
      NoteRecoveryDraft(
        draftId = NoteRecoveryDraftKeys.forExistingNote(10L, "s2"),
        targetNoteId = 10L,
        sessionId = "s2",
        folderId = null,
        initialFolderId = null,
        fallbackSourceFolderId = null,
        isPinned = false,
        title = "A",
        contentState = NoteContentState.Valid(NoteContentDocument.empty()),
        paperStyle = NotePaperStyle.LINHAS,
        initialPaperStyle = null,
        baseRevision = 1L,
        checkpointRevision = 1L,
        baseNoteUpdatedAt = 100L,
        checkpointAt = 200L,
      )

    val mismatchWrongId =
      NoteEditorSessionEvaluator.evaluateOpening(
        request =
          NoteEditorOpenRequest.RecoverDraft(
            draftId = existingDraft.draftId,
            expectedTarget = RecoveryTargetExpectation.ExistingNote(20L),
          ),
        draft = existingDraft,
      ) as RecoveryOpeningEvaluation.Blocked

    val mismatchReason = mismatchWrongId.reason as SessionBlockReason.DraftTargetMismatch
    assertEquals(10L, mismatchReason.actualTargetNoteId)

    val mismatchExistingExpectedNew =
      NoteEditorSessionEvaluator.evaluateOpening(
        request =
          NoteEditorOpenRequest.RecoverDraft(
            draftId = existingDraft.draftId,
            expectedTarget = RecoveryTargetExpectation.NewNote,
          ),
        draft = existingDraft,
      ) as RecoveryOpeningEvaluation.Blocked
    assertTrue(mismatchExistingExpectedNew.reason is SessionBlockReason.DraftTargetMismatch)
  }

  @Test
  fun recoverDraft_conflictsAndBlockedReasons_areProperlyClassified() {
    // 1. Draft inexistente
    val missing =
      NoteEditorSessionEvaluator.evaluateOpening(
        request = NoteEditorOpenRequest.RecoverDraft("new_missing"),
        draft = null,
      ) as RecoveryOpeningEvaluation.Blocked
    assertEquals(SessionBlockReason.DraftNotFound("new_missing"), missing.reason)

    // 2. Conteúdo do draft ilegível
    val unreadableDraft =
      sampleNewDraft(sessionId = "bad-doc", initialPaperStyle = NotePaperStyle.LINHAS).copy(
        contentState =
          NoteContentState.RequiresHandling.CorruptedJson(rawJson = "{bad", reason = "syntax")
      )
    val unreadableResult =
      NoteEditorSessionEvaluator.evaluateOpening(
        request = NoteEditorOpenRequest.RecoverDraft(unreadableDraft.draftId),
        draft = unreadableDraft,
      ) as RecoveryOpeningEvaluation.Blocked
    assertTrue(unreadableResult.reason is SessionBlockReason.UnreadableDraftContent)

    // 3. Nota nova com baseRevision inválida -> Blocked(InvalidDraftBaseReference)
    val invalidNewBaseDraft =
      sampleNewDraft(sessionId = "bad-base", initialPaperStyle = NotePaperStyle.LINHAS).copy(
        baseRevision = 2L,
        baseNoteUpdatedAt = 500L,
      )
    val invalidNewBaseResult =
      NoteEditorSessionEvaluator.evaluateOpening(
        request = NoteEditorOpenRequest.RecoverDraft(invalidNewBaseDraft.draftId),
        draft = invalidNewBaseDraft,
      ) as RecoveryOpeningEvaluation.Blocked
    assertTrue(invalidNewBaseResult.reason is SessionBlockReason.InvalidDraftBaseReference)

    // 4. Nota existente com base divergente -> ReadyToEdit + NoteDiverged + BlockedByConflict
    val dbNote =
      sampleNote(
        id = 77L,
        folderId = null,
        title = "Atual",
        document = NoteContentDocument.empty(),
        revision = 5L,
        updatedAt = 5000L,
      )
    val divergedDraft =
      NoteRecoveryDraft(
        draftId = NoteRecoveryDraftKeys.forExistingNote(77L, "s-div"),
        targetNoteId = 77L,
        sessionId = "s-div",
        folderId = null,
        initialFolderId = null,
        fallbackSourceFolderId = null,
        isPinned = false,
        title = "Editada",
        contentState = NoteContentState.Valid(NoteContentDocument.empty()),
        paperStyle = NotePaperStyle.LINHAS,
        initialPaperStyle = null,
        baseRevision = 4L,
        checkpointRevision = 1L,
        baseNoteUpdatedAt = 4000L,
        checkpointAt = 4500L,
      )
    val divergedReady =
      NoteEditorSessionEvaluator.evaluateOpening(
        request = NoteEditorOpenRequest.RecoverDraft(divergedDraft.draftId),
        draft = divergedDraft,
        existingNote = dbNote,
      ) as RecoveryOpeningEvaluation.ReadyToEdit

    assertTrue(divergedReady.activeConflict is RecoveryConflict.NoteDiverged)
    assertTrue(
      divergedReady.evaluation.consolidationReadiness is ConsolidationReadiness.BlockedByConflict
    )
    assertTrue(
      divergedReady.evaluation.warnings.any { it is SessionWarning.UnresolvedRecoveryConflict }
    )

    // 5. Nota existente com baseReference inválida -> RecoveryConflict.InvalidBaseReference
    val invalidExistingBaseDraft = divergedDraft.copy(baseRevision = 0L, baseNoteUpdatedAt = null)
    val invalidExistingBaseReady =
      NoteEditorSessionEvaluator.evaluateOpening(
        request = NoteEditorOpenRequest.RecoverDraft(invalidExistingBaseDraft.draftId),
        draft = invalidExistingBaseDraft,
        existingNote = dbNote,
      ) as RecoveryOpeningEvaluation.ReadyToEdit

    assertTrue(invalidExistingBaseReady.activeConflict is RecoveryConflict.InvalidBaseReference)
    assertTrue(
      invalidExistingBaseReady.evaluation.consolidationReadiness
        is ConsolidationReadiness.BlockedByConflict
    )

    // 6. Nota na lixeira
    val trashedNote = dbNote.copy(isDeleted = true)
    val trashedResult =
      NoteEditorSessionEvaluator.evaluateOpening(
        request = NoteEditorOpenRequest.RecoverDraft(divergedDraft.draftId),
        draft = divergedDraft,
        existingNote = trashedNote,
      ) as RecoveryOpeningEvaluation.Blocked
    assertEquals(SessionBlockReason.TargetNoteInTrash(77L), trashedResult.reason)
  }

  @Test
  fun documentCanonicalization_handlesEmptyListsWhitespaceAndCoalescesSpans() {
    // Documento vazio [] == um único ParagraphBlock vazio mesmo com IDs diferentes
    val singleEmptyParagraph =
      NoteContentDocument(blocks = listOf(ParagraphBlock(id = "any-id", text = "")))
    assertTrue(NoteEditorSessionEvaluator.isEquivalentToEmptyDocument(NoteContentDocument.empty()))
    assertTrue(NoteEditorSessionEvaluator.isEquivalentToEmptyDocument(singleEmptyParagraph))
    assertTrue(
      NoteEditorSessionEvaluator.areDocumentsEquivalent(
        NoteContentDocument.empty(),
        singleEmptyParagraph,
      )
    )

    // Whitespace puro (" ") NÃO é equivalente a vazio
    val whitespaceParagraph =
      NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = " ")))
    assertFalse(NoteEditorSessionEvaluator.isEquivalentToEmptyDocument(whitespaceParagraph))
    assertFalse(
      NoteEditorSessionEvaluator.areDocumentsEquivalent(
        NoteContentDocument.empty(),
        whitespaceParagraph,
      )
    )

    // Listas vazias NÃO são equivalentes a vazio
    val emptyBullet =
      NoteContentDocument(blocks = listOf(BulletListItemBlock(id = "b1", text = "")))
    val emptyNumbered =
      NoteContentDocument(blocks = listOf(NumberedListItemBlock(id = "n1", text = "")))
    val emptyChecklist =
      NoteContentDocument(blocks = listOf(ChecklistItemBlock(id = "c1", text = "")))
    assertFalse(NoteEditorSessionEvaluator.isEquivalentToEmptyDocument(emptyBullet))
    assertFalse(NoteEditorSessionEvaluator.isEquivalentToEmptyDocument(emptyNumbered))
    assertFalse(NoteEditorSessionEvaluator.isEquivalentToEmptyDocument(emptyChecklist))

    // Dois parágrafos vazios NÃO são equivalentes a vazio
    val twoEmptyParagraphs =
      NoteContentDocument(
        blocks =
          listOf(
            ParagraphBlock(id = "p1", text = ""),
            ParagraphBlock(id = "p2", text = ""),
          )
      )
    assertFalse(NoteEditorSessionEvaluator.isEquivalentToEmptyDocument(twoEmptyParagraphs))

    // Spans sobrepostos/adjacentes coalescidos equivalem ao span unificado
    val fragmentedDoc =
      NoteContentDocument(
        blocks =
          listOf(
            ParagraphBlock(
              id = "p1",
              text = "AnoteVoce",
              spans =
                listOf(
                  FormatSpan(0, 5, TextFormatStyle.BOLD),
                  FormatSpan(3, 9, TextFormatStyle.BOLD),
                ),
            )
          )
      )
    val coalescedDoc =
      NoteContentDocument(
        blocks =
          listOf(
            ParagraphBlock(
              id = "p1",
              text = "AnoteVoce",
              spans = listOf(FormatSpan(0, 9, TextFormatStyle.BOLD)),
            )
          )
      )
    assertTrue(NoteEditorSessionEvaluator.areDocumentsEquivalent(fragmentedDoc, coalescedDoc))

    // Diferença de indentação e estado checked em checklist são detectadas
    val checklistUnchecked =
      NoteContentDocument(
        blocks = listOf(ChecklistItemBlock(id = "c1", text = "Comprar", isChecked = false))
      )
    val checklistChecked =
      NoteContentDocument(
        blocks = listOf(ChecklistItemBlock(id = "c1", text = "Comprar", isChecked = true))
      )
    assertFalse(
      NoteEditorSessionEvaluator.areDocumentsEquivalent(checklistUnchecked, checklistChecked)
    )

    // Título exatamente comparado (sem trim)
    val base =
      NoteEditorBaseline.NewNote(
        title = "Titulo",
        normalizedContent = NoteEditorSessionEvaluator.canonicalizeDocument(NoteContentDocument.empty()),
        paperStyle = NotePaperStyle.LINHAS,
        staticFolderId = null,
        initialIsPinned = false,
      )
    val titleSpacesEval =
      NoteEditorSessionEvaluator.evaluateSession(
        NoteEditorSessionEvaluationInput(
          lifecycleStage = SessionLifecycleStage.EDITING,
          baseline = base,
          snapshot =
            NoteEditorEditableSnapshot(
              title = "Titulo ",
              document = NoteContentDocument.empty(),
              effectiveFolderId = null,
              fallbackSourceFolderId = null,
              paperStyle = NotePaperStyle.LINHAS,
              isPinned = false,
            ),
        )
      )
    assertTrue(titleSpacesEval.dirtyBreakdown.titleChanged)
  }

  @Test
  fun folderFallback_externalRestore_keepsStabilityWithoutSilentReactivation_andExplicitResolutionCleans() {
    val folderA = sampleFolder(id = 1L, name = "A")
    val folderB = sampleFolder(id = 2L, name = "B")

    // Cenário obrigatório:
    // 1. Nota começa em A, usuário escolhe B, A e B ficam indisponíveis -> fallback (folderId=null, fallbackSource=B)
    val draftWithFallback =
      sampleNewDraft(
        sessionId = "fb-restore",
        folderId = null,
        initialFolderId = folderA.id,
        fallbackSourceFolderId = folderB.id,
        initialPaperStyle = NotePaperStyle.LINHAS,
      )

    // Primeira recuperação: A e B indisponíveis
    val evalBothUnavailable =
      NoteEditorSessionEvaluator.evaluateOpening(
        request = NoteEditorOpenRequest.RecoverDraft(draftWithFallback.draftId),
        draft = draftWithFallback,
        folderAvailabilityById =
          mapOf(
            folderA.id to FolderAvailability.PermanentlyDeleted(folderA.id),
            folderB.id to FolderAvailability.InTrash(folderB.copy(isDeleted = true)),
          ),
      ) as RecoveryOpeningEvaluation.ReadyToEdit

    assertNull(evalBothUnavailable.initialSnapshot.effectiveFolderId)
    assertEquals(folderB.id, evalBothUnavailable.initialSnapshot.fallbackSourceFolderId)
    assertEquals(folderB.id, evalBothUnavailable.evaluation.intendedUserFolderId)
    assertTrue(evalBothUnavailable.evaluation.hasUnsavedChanges)
    assertTrue(evalBothUnavailable.evaluation.dirtyBreakdown.folderChanged)

    // Segunda recuperação: A e B foram restauradas externamente para Active!
    // Regra crítica: NÃO reativar B silenciosamente e NÃO alterar hasUnsavedChanges (continua true, pois B != A).
    val evalBothRestored =
      NoteEditorSessionEvaluator.evaluateOpening(
        request = NoteEditorOpenRequest.RecoverDraft(draftWithFallback.draftId),
        draft = draftWithFallback,
        folderAvailabilityById =
          mapOf(
            folderA.id to FolderAvailability.Active(folderA),
            folderB.id to FolderAvailability.Active(folderB),
          ),
      ) as RecoveryOpeningEvaluation.ReadyToEdit

    assertNull(evalBothRestored.initialSnapshot.effectiveFolderId)
    assertEquals(folderB.id, evalBothRestored.initialSnapshot.fallbackSourceFolderId)
    assertEquals(folderB.id, evalBothRestored.evaluation.intendedUserFolderId)
    assertTrue(evalBothRestored.evaluation.hasUnsavedChanges)
    assertTrue(evalBothRestored.evaluation.hasPendingFolderFallback)
    assertTrue(
      evalBothRestored.evaluation.consolidationReadiness
        is ConsolidationReadiness.RequiresExplicitFolderConfirmation
    )

    // Usuário escolhe explicitamente voltar para A (ativa) -> resolve fallback e volta ao clean baseline
    val resolvedToA =
      NoteEditorSessionEvaluator.evaluateSession(
        NoteEditorSessionEvaluationInput(
          lifecycleStage = SessionLifecycleStage.EDITING,
          baseline = evalBothRestored.baseline,
          snapshot =
            evalBothRestored.initialSnapshot.copy(
              effectiveFolderId = folderA.id,
              fallbackSourceFolderId = null,
            ),
          folderContext =
            ResolvedDraftFolderContext(
              effectiveTarget = FolderAvailability.Active(folderA),
              fallbackSource = null,
              initialBaseline = FolderAvailability.Active(folderA),
            ),
        )
      )

    assertFalse(resolvedToA.hasUnsavedChanges)
    assertFalse(resolvedToA.hasPendingFolderFallback)
    assertFalse(resolvedToA.needsDraftRetention)
    assertTrue(resolvedToA.canSafelyReconcileToCleanBaseline)
    assertEquals(ConsolidationReadiness.Ready(folderA.id), resolvedToA.consolidationReadiness)

    // Usuário escolhe explicitamente a raiz (null) -> resolve fallback, mas mantém folderChanged = true pois baseline era A
    val resolvedToRoot =
      NoteEditorSessionEvaluator.evaluateSession(
        NoteEditorSessionEvaluationInput(
          lifecycleStage = SessionLifecycleStage.EDITING,
          baseline = evalBothRestored.baseline,
          snapshot =
            evalBothRestored.initialSnapshot.copy(
              effectiveFolderId = null,
              fallbackSourceFolderId = null,
            ),
          folderContext =
            ResolvedDraftFolderContext(
              effectiveTarget = FolderAvailability.Root,
              fallbackSource = null,
              initialBaseline = FolderAvailability.Active(folderA),
            ),
        )
      )

    assertTrue(resolvedToRoot.hasUnsavedChanges)
    assertTrue(resolvedToRoot.dirtyBreakdown.folderChanged)
    assertFalse(resolvedToRoot.hasPendingFolderFallback)
    assertEquals(ConsolidationReadiness.Ready(null), resolvedToRoot.consolidationReadiness)
  }

  @Test
  fun folderAvailabilityUnknown_blocksCurrentTargetWithoutFallback_butAllowsHistoricalUnknown() {
    val folderA = sampleFolder(id = 1L, name = "A")
    val baseline =
      NoteEditorBaseline.NewNote(
        title = "",
        normalizedContent = NoteEditorSessionEvaluator.canonicalizeDocument(NoteContentDocument.empty()),
        paperStyle = NotePaperStyle.LINHAS,
        staticFolderId = folderA.id,
        initialIsPinned = false,
      )

    // Caso 1: Destino efetivo corrente está Unknown -> NÃO aplica fallback automático, bloqueia consolidação
    val currentUnknownEval =
      NoteEditorSessionEvaluator.evaluateSession(
        NoteEditorSessionEvaluationInput(
          lifecycleStage = SessionLifecycleStage.EDITING,
          baseline = baseline,
          snapshot =
            NoteEditorEditableSnapshot(
              title = "",
              document = NoteContentDocument.empty(),
              effectiveFolderId = 55L,
              fallbackSourceFolderId = null,
              paperStyle = NotePaperStyle.LINHAS,
              isPinned = false,
            ),
          folderContext =
            ResolvedDraftFolderContext(
              effectiveTarget = FolderAvailability.Unknown(55L, "Timeout I/O"),
              initialBaseline = FolderAvailability.Active(folderA),
            ),
        )
      )

    assertEquals(55L, currentUnknownEval.effectiveFolderId)
    assertNull(currentUnknownEval.fallbackSourceFolderId)
    assertFalse(currentUnknownEval.hasPendingFolderFallback)
    assertTrue(
      currentUnknownEval.consolidationReadiness is ConsolidationReadiness.BlockedByUnknownFolderState
    )
    assertTrue(
      currentUnknownEval.warnings.any { it is SessionWarning.CurrentDestinationUnknown }
    )

    // Caso 2: Destino efetivo é Active(folderA), mas initialBaseline histórico está Unknown -> Ready (não bloqueia!)
    val historicalInitialUnknownEval =
      NoteEditorSessionEvaluator.evaluateSession(
        NoteEditorSessionEvaluationInput(
          lifecycleStage = SessionLifecycleStage.EDITING,
          baseline = baseline,
          snapshot =
            NoteEditorEditableSnapshot(
              title = "",
              document = NoteContentDocument.empty(),
              effectiveFolderId = folderA.id,
              fallbackSourceFolderId = null,
              paperStyle = NotePaperStyle.LINHAS,
              isPinned = false,
            ),
          folderContext =
            ResolvedDraftFolderContext(
              effectiveTarget = FolderAvailability.Active(folderA),
              initialBaseline = FolderAvailability.Unknown(999L, "Erro consulta histórica"),
            ),
        )
      )

    assertEquals(
      ConsolidationReadiness.Ready(folderA.id),
      historicalInitialUnknownEval.consolidationReadiness,
    )
    assertFalse(
      historicalInitialUnknownEval.warnings.any { it is SessionWarning.CurrentDestinationUnknown }
    )

    // Caso 3: Destino efetivo caiu para Root com fallbackSourceFolderId = 88L cuja disponibilidade é Unknown ->
    // Deve exigir confirmação explícita (RequiresExplicitFolderConfirmation), NÃO BlockedByUnknownFolderState
    val historicalFallbackUnknownEval =
      NoteEditorSessionEvaluator.evaluateSession(
        NoteEditorSessionEvaluationInput(
          lifecycleStage = SessionLifecycleStage.EDITING,
          baseline = baseline,
          snapshot =
            NoteEditorEditableSnapshot(
              title = "",
              document = NoteContentDocument.empty(),
              effectiveFolderId = null,
              fallbackSourceFolderId = 88L,
              paperStyle = NotePaperStyle.LINHAS,
              isPinned = false,
            ),
          folderContext =
            ResolvedDraftFolderContext(
              effectiveTarget = FolderAvailability.Root,
              fallbackSource = FolderAvailability.Unknown(88L, "Indisponível temporariamente"),
              initialBaseline = FolderAvailability.Active(folderA),
            ),
        )
      )

    assertTrue(
      historicalFallbackUnknownEval.consolidationReadiness
        is ConsolidationReadiness.RequiresExplicitFolderConfirmation
    )
  }

  @Test
  fun lifecycleStagesNonEditing_neverReportUnsavedChangesAndNeverAllowCleanReconcile() {
    val baseline =
      NoteEditorBaseline.NewNote(
        title = "",
        normalizedContent = NoteEditorSessionEvaluator.canonicalizeDocument(NoteContentDocument.empty()),
        paperStyle = NotePaperStyle.LINHAS,
        staticFolderId = null,
        initialIsPinned = false,
      )
    val dirtySnapshot =
      NoteEditorEditableSnapshot(
        title = "Alterado",
        document = NoteContentDocument.empty(),
        effectiveFolderId = null,
        fallbackSourceFolderId = null,
        paperStyle = NotePaperStyle.LINHAS,
        isPinned = false,
      )

    listOf(
        SessionLifecycleStage.LOADING,
        SessionLifecycleStage.BLOCKED,
        SessionLifecycleStage.ERROR,
      )
      .forEach { stage ->
        val eval =
          NoteEditorSessionEvaluator.evaluateSession(
            NoteEditorSessionEvaluationInput(
              lifecycleStage = stage,
              baseline = baseline,
              snapshot = dirtySnapshot,
              blockReason = SessionBlockReason.DraftNotFound("x"),
            )
          )
        assertFalse("Stage $stage não deve expor hasUnsavedChanges", eval.hasUnsavedChanges)
        assertFalse("Stage $stage não deve reter rascunho", eval.needsDraftRetention)
        assertFalse(
          "Stage $stage não deve reconciliar baseline limpa",
          eval.canSafelyReconcileToCleanBaseline,
        )
        assertTrue(eval.consolidationReadiness is ConsolidationReadiness.BlockedBySessionState)
      }
  }

  @Test
  fun snapshotInvariant_rejectsBothEffectiveAndFallbackFoldersSimultaneously() {
    assertThrows(IllegalArgumentException::class.java) {
      NoteEditorEditableSnapshot(
        title = "Teste",
        document = NoteContentDocument.empty(),
        effectiveFolderId = 1L,
        fallbackSourceFolderId = 2L,
        paperStyle = NotePaperStyle.LINHAS,
        isPinned = false,
      )
    }
  }

  @Test
  fun applyFolderAvailabilityToSnapshot_appliesFallbackOnlyForUnavailableActiveFolder() {
    val folderB = sampleFolder(id = 2L, name = "B")
    val activeSnap =
      NoteEditorEditableSnapshot(
        title = "N",
        document = NoteContentDocument.empty(),
        effectiveFolderId = folderB.id,
        fallbackSourceFolderId = null,
        paperStyle = NotePaperStyle.LINHAS,
        isPinned = false,
      )

    // Unknown -> mantém effectiveFolderId sem aplicar fallback
    val (afterUnknown, ctxUnknown) =
      NoteEditorSessionEvaluator.applyFolderAvailabilityToSnapshot(
        snapshot = activeSnap,
        folderAvailabilityById = mapOf(folderB.id to FolderAvailability.Unknown(folderB.id)),
      )
    assertEquals(folderB.id, afterUnknown.effectiveFolderId)
    assertNull(afterUnknown.fallbackSourceFolderId)
    assertTrue(ctxUnknown.effectiveTarget is FolderAvailability.Unknown)

    // NotFound -> aplica fallback automático
    val (afterNotFound, ctxNotFound) =
      NoteEditorSessionEvaluator.applyFolderAvailabilityToSnapshot(
        snapshot = activeSnap,
        folderAvailabilityById = mapOf(folderB.id to FolderAvailability.NotFound(folderB.id)),
      )
    assertNull(afterNotFound.effectiveFolderId)
    assertEquals(folderB.id, afterNotFound.fallbackSourceFolderId)
    assertNotNull(ctxNotFound.fallbackSource)
  }

  private fun sampleFolder(
    id: Long,
    name: String,
    isDeleted: Boolean = false,
  ): Folder =
    Folder(
      id = id,
      uid = "folder-$id",
      name = name,
      normalizedName = name.lowercase(),
      isDeleted = isDeleted,
      deletedAt = if (isDeleted) 1000L else null,
      createdAt = 100L,
      updatedAt = 200L,
    )

  private fun sampleNote(
    id: Long,
    folderId: Long?,
    title: String,
    document: NoteContentDocument,
    paperStyle: NotePaperStyle = NotePaperStyle.LINHAS,
    revision: Long = 1L,
    updatedAt: Long = 1000L,
    isPinned: Boolean = false,
    isDeleted: Boolean = false,
  ): Note =
    Note(
      id = id,
      uid = "note-$id",
      folderId = folderId,
      title = title,
      contentState = NoteContentState.Valid(document),
      plainTextPreview = "",
      paperStyle = paperStyle,
      revision = revision,
      isPinned = isPinned,
      pinnedAt = if (isPinned) 500L else null,
      isDeleted = isDeleted,
      deletedAt = if (isDeleted) 900L else null,
      deletedWithFolder = false,
      createdAt = 100L,
      updatedAt = updatedAt,
    )

  private fun sampleNewDraft(
    sessionId: String,
    folderId: Long? = null,
    initialFolderId: Long? = null,
    fallbackSourceFolderId: Long? = null,
    title: String = "",
    document: NoteContentDocument = NoteContentDocument.empty(),
    paperStyle: NotePaperStyle = NotePaperStyle.LINHAS,
    initialPaperStyle: NotePaperStyle? = NotePaperStyle.LINHAS,
    isPinned: Boolean = false,
  ): NoteRecoveryDraft =
    NoteRecoveryDraft(
      draftId = NoteRecoveryDraftKeys.forNewNote(sessionId),
      targetNoteId = null,
      sessionId = sessionId,
      folderId = folderId,
      initialFolderId = initialFolderId,
      fallbackSourceFolderId = fallbackSourceFolderId,
      isPinned = isPinned,
      title = title,
      contentState = NoteContentState.Valid(document),
      paperStyle = paperStyle,
      initialPaperStyle = initialPaperStyle,
      baseRevision = 0L,
      checkpointRevision = 1L,
      baseNoteUpdatedAt = null,
      checkpointAt = 1000L,
    )
}
