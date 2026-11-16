package com.example.domain.editor

import com.example.domain.model.ContentBlock
import com.example.domain.model.Note
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NoteContentState
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.NoteRecoveryDraft
import com.example.domain.model.NoteRecoveryDraftKeys
import com.example.domain.model.ParagraphBlock
import com.example.domain.policy.NoteContentPolicy
import java.util.Collections

/**
 * Avaliador puro, determinístico e sem efeitos colaterais para abertura, comparação de baseline,
 * fallback de pastas, detecção de dirty e prontidão para consolidação de sessões de edição.
 */
object NoteEditorSessionEvaluator {

  /**
   * Canonicaliza o documento preservando IDs, textos (sem trim), listas vazias e metadados de bloco:
   * 1) Aplica [NoteContentPolicy.normalizeDocument];
   * 2) Aplica [NoteEditorTextHelpers.normalizeAndCoalesceSpans] em cada bloco;
   * 3) Garante propriedade imutável das listas de blocos e spans retornadas.
   */
  fun canonicalizeDocument(document: NoteContentDocument): NoteContentDocument {
    val policyNormalized = NoteContentPolicy.normalizeDocument(document)
    if (policyNormalized.blocks.isEmpty()) {
      return NoteContentDocument(
        schemaVersion = policyNormalized.schemaVersion,
        blocks = Collections.unmodifiableList(emptyList()),
      )
    }

    val coalescedBlocks: List<ContentBlock> =
      policyNormalized.blocks.map { block ->
        val coalescedSpans =
          NoteEditorTextHelpers.normalizeAndCoalesceSpans(
            text = block.text,
            spans = block.spans,
          )
        NoteEditorTextHelpers.updateBlockTextAndSpans(
          block = block,
          text = block.text,
          spans = Collections.unmodifiableList(coalescedSpans),
        ).toImmutableSnapshot()
      }
    return NoteContentDocument(
      schemaVersion = policyNormalized.schemaVersion,
      blocks = Collections.unmodifiableList(coalescedBlocks),
    )
  }

  /**
   * Considera equivalente a vazio apenas:
   * - documento sem blocos; ou
   * - documento com exatamente um [ParagraphBlock], texto vazio (`""`) e sem spans.
   *
   * Listas vazias, textos com apenas whitespace, múltiplos parágrafos vazios ou blocos com spans
   * NÃO são equivalentes a vazio.
   */
  fun isEquivalentToEmptyDocument(document: NoteContentDocument): Boolean {
    val canonical = canonicalizeDocument(document)
    if (canonical.blocks.isEmpty()) return true
    if (canonical.blocks.size != 1) return false
    val single = canonical.blocks[0]
    return single is ParagraphBlock && single.text.isEmpty() && single.spans.isEmpty()
  }

  /**
   * Compara estruturalmente dois documentos após canonicalização.
   */
  fun areDocumentsEquivalent(
    left: NoteContentDocument,
    right: NoteContentDocument,
  ): Boolean {
    val canonicalLeft = canonicalizeDocument(left)
    val canonicalRight = canonicalizeDocument(right)
    if (canonicalLeft.schemaVersion != canonicalRight.schemaVersion) return false
    if (isEmptyCanonical(canonicalLeft) && isEmptyCanonical(canonicalRight)) {
      return true
    }
    return canonicalLeft == canonicalRight
  }

  private fun isEmptyCanonical(canonical: NoteContentDocument): Boolean {
    if (canonical.blocks.isEmpty()) return true
    if (canonical.blocks.size != 1) return false
    val single = canonical.blocks[0]
    return single is ParagraphBlock && single.text.isEmpty() && single.spans.isEmpty()
  }

  /**
   * Avalia a abertura de uma sessão (nova nota, nota existente ou recuperação de rascunho)
   * usando exclusivamente dados já carregados e mapas de disponibilidade já resolvidos.
   *
   * Para [NoteEditorOpenRequest.CreateNew] e [NoteEditorOpenRequest.OpenExisting], quando
   * `request.sessionId` for nulo, o chamador DEVE informar um [generatedSessionId] exclusivo e válido.
   */
  fun evaluateOpening(
    request: NoteEditorOpenRequest,
    draft: NoteRecoveryDraft? = null,
    existingNote: Note? = null,
    folderAvailabilityById: Map<Long, FolderAvailability> = emptyMap(),
    generatedSessionId: String? = null,
  ): RecoveryOpeningEvaluation =
    when (request) {
      is NoteEditorOpenRequest.CreateNew ->
        evaluateCreateNew(
          request = request,
          folderAvailabilityById = folderAvailabilityById,
          generatedSessionId = generatedSessionId,
        )

      is NoteEditorOpenRequest.OpenExisting ->
        evaluateOpenExisting(
          request = request,
          existingNote = existingNote,
          folderAvailabilityById = folderAvailabilityById,
          generatedSessionId = generatedSessionId,
        )

      is NoteEditorOpenRequest.RecoverDraft ->
        evaluateRecoverDraft(
          request = request,
          draft = draft,
          existingNote = existingNote,
          folderAvailabilityById = folderAvailabilityById,
        )
    }

  private fun resolveSessionId(
    requestSessionId: String?,
    generatedSessionId: String?,
  ): String {
    if (requestSessionId != null) {
      return NoteRecoveryDraftKeys.normalizeAndValidateSessionId(requestSessionId)
    }
    val callerProvidedId =
      generatedSessionId
        ?: throw IllegalArgumentException(
          "Um identificador de sessão exclusivo deve ser fornecido pelo chamador quando request.sessionId for nulo."
        )
    return NoteRecoveryDraftKeys.normalizeAndValidateSessionId(callerProvidedId)
  }

  private fun evaluateCreateNew(
    request: NoteEditorOpenRequest.CreateNew,
    folderAvailabilityById: Map<Long, FolderAvailability>,
    generatedSessionId: String?,
  ): RecoveryOpeningEvaluation {
    val cleanSessionId = resolveSessionId(request.sessionId, generatedSessionId)
    val identity =
      NoteEditorSessionIdentity(
        draftId = NoteRecoveryDraftKeys.forNewNote(cleanSessionId),
        sessionId = cleanSessionId,
        targetNoteId = null,
      )

    val resolvedPaperStyle = request.defaultPaperStyle ?: NotePaperStyle.LINHAS

    val baseline =
      NoteEditorBaseline.NewNote(
        title = "",
        normalizedContent = canonicalizeDocument(NoteContentDocument.empty()),
        paperStyle = resolvedPaperStyle,
        staticFolderId = request.initialFolderId,
        initialIsPinned = request.initialPinned,
      )

    val resolvedInitial = resolveFolderAvailability(request.initialFolderId, folderAvailabilityById)
    val (effectiveFolderId, fallbackSourceFolderId, effectiveAvailability, fallbackAvailability) =
      resolveDestinationTuple(
        requestedEffectiveFolderId = request.initialFolderId,
        requestedFallbackSourceFolderId = null,
        folderAvailabilityById = folderAvailabilityById,
      )

    val folderContext =
      ResolvedDraftFolderContext(
        effectiveTarget = effectiveAvailability,
        fallbackSource = fallbackAvailability,
        initialBaseline = resolvedInitial,
        existingNoteFolder = null,
      )
    val snapshot =
      NoteEditorEditableSnapshot(
        title = "",
        document = NoteContentDocument.empty(),
        effectiveFolderId = effectiveFolderId,
        fallbackSourceFolderId = fallbackSourceFolderId,
        paperStyle = resolvedPaperStyle,
        isPinned = request.initialPinned,
      )

    val evaluation =
      evaluateSession(
        NoteEditorSessionEvaluationInput(
          lifecycleStage = SessionLifecycleStage.EDITING,
          baseline = baseline,
          snapshot = snapshot,
          folderContext = folderContext,
        )
      )

    return RecoveryOpeningEvaluation.ReadyToEdit(
      identity = identity,
      baseline = baseline,
      initialSnapshot = snapshot,
      folderContext = folderContext,
      activeConflict = null,
      evaluation = evaluation,
    )
  }

  private fun evaluateOpenExisting(
    request: NoteEditorOpenRequest.OpenExisting,
    existingNote: Note?,
    folderAvailabilityById: Map<Long, FolderAvailability>,
    generatedSessionId: String?,
  ): RecoveryOpeningEvaluation {
    val cleanSessionId = resolveSessionId(request.sessionId, generatedSessionId)

    if (existingNote == null || existingNote.id != request.noteId) {
      return RecoveryOpeningEvaluation.Blocked(SessionBlockReason.TargetNoteNotFound(request.noteId))
    }
    if (existingNote.isDeleted) {
      return RecoveryOpeningEvaluation.Blocked(SessionBlockReason.TargetNoteInTrash(request.noteId))
    }
    val contentState = existingNote.contentState
    if (contentState is NoteContentState.RequiresHandling) {
      return RecoveryOpeningEvaluation.Blocked(
        SessionBlockReason.UnreadableTargetNoteContent(request.noteId, contentState)
      )
    }

    val identity =
      NoteEditorSessionIdentity(
        draftId = NoteRecoveryDraftKeys.forExistingNote(existingNote.id, cleanSessionId),
        sessionId = cleanSessionId,
        targetNoteId = existingNote.id,
      )

    val canonicalDoc = canonicalizeDocument((contentState as NoteContentState.Valid).document)
    val baseline =
      NoteEditorBaseline.ExistingNote(
        noteId = existingNote.id,
        baseRevision = existingNote.revision,
        baseNoteUpdatedAt = existingNote.updatedAt,
        title = existingNote.title,
        normalizedContent = canonicalDoc,
        paperStyle = existingNote.paperStyle,
        staticFolderId = existingNote.folderId,
        currentDbIsPinned = existingNote.isPinned,
      )

    val existingFolderAvailability =
      resolveFolderAvailability(existingNote.folderId, folderAvailabilityById)
    val (effectiveFolderId, fallbackSourceFolderId, effectiveAvailability, fallbackAvailability) =
      resolveDestinationTuple(
        requestedEffectiveFolderId = existingNote.folderId,
        requestedFallbackSourceFolderId = null,
        folderAvailabilityById = folderAvailabilityById,
      )

    val folderContext =
      ResolvedDraftFolderContext(
        effectiveTarget = effectiveAvailability,
        fallbackSource = fallbackAvailability,
        initialBaseline = null,
        existingNoteFolder = existingFolderAvailability,
      )
    val snapshot =
      NoteEditorEditableSnapshot(
        title = existingNote.title,
        document = canonicalDoc,
        effectiveFolderId = effectiveFolderId,
        fallbackSourceFolderId = fallbackSourceFolderId,
        paperStyle = existingNote.paperStyle,
        isPinned = existingNote.isPinned,
      )

    val evaluation =
      evaluateSession(
        NoteEditorSessionEvaluationInput(
          lifecycleStage = SessionLifecycleStage.EDITING,
          baseline = baseline,
          snapshot = snapshot,
          folderContext = folderContext,
        )
      )

    return RecoveryOpeningEvaluation.ReadyToEdit(
      identity = identity,
      baseline = baseline,
      initialSnapshot = snapshot,
      folderContext = folderContext,
      activeConflict = null,
      evaluation = evaluation,
    )
  }

  private fun evaluateRecoverDraft(
    request: NoteEditorOpenRequest.RecoverDraft,
    draft: NoteRecoveryDraft?,
    existingNote: Note?,
    folderAvailabilityById: Map<Long, FolderAvailability>,
  ): RecoveryOpeningEvaluation {
    if (draft == null) {
      return RecoveryOpeningEvaluation.Blocked(SessionBlockReason.DraftNotFound(request.draftId))
    }

    if (draft.draftId != request.draftId) {
      return RecoveryOpeningEvaluation.Blocked(
        SessionBlockReason.DraftIdentityMismatch(
          requestedDraftId = request.draftId,
          providedDraftId = draft.draftId,
        )
      )
    }

    if (!matchesExpectation(draft.targetNoteId, request.expectedTarget)) {
      return RecoveryOpeningEvaluation.Blocked(
        SessionBlockReason.DraftTargetMismatch(
          draftId = draft.draftId,
          expected = request.expectedTarget,
          actualTargetNoteId = draft.targetNoteId,
        )
      )
    }

    val identity =
      try {
        NoteEditorSessionIdentity(
          draftId = draft.draftId,
          sessionId = draft.sessionId,
          targetNoteId = draft.targetNoteId,
        )
      } catch (e: IllegalArgumentException) {
        return RecoveryOpeningEvaluation.Blocked(
          SessionBlockReason.InvalidPersistedDraftKeys(
            draftId = draft.draftId,
            reason = e.message ?: "Chaves do rascunho inválidas.",
          )
        )
      }

    val draftContentState = draft.contentState
    if (draftContentState is NoteContentState.RequiresHandling) {
      return RecoveryOpeningEvaluation.Blocked(
        SessionBlockReason.UnreadableDraftContent(draft.draftId, draftContentState)
      )
    }
    val draftDocument = canonicalizeDocument((draftContentState as NoteContentState.Valid).document)

    val extraWarnings = mutableListOf<SessionWarning>()
    val baseline: NoteEditorBaseline
    val activeConflict: RecoveryConflict?
    val effectiveInitialFolderAvailability: FolderAvailability?
    val existingFolderAvailability: FolderAvailability?
    val resolvedIsPinned: Boolean

    if (draft.targetNoteId == null) {
      if (!draft.hasValidBaseReference) {
        return RecoveryOpeningEvaluation.Blocked(
          SessionBlockReason.InvalidDraftBaseReference(draft.draftId, targetNoteId = null)
        )
      }

      effectiveInitialFolderAvailability =
        draft.initialFolderId?.let { resolveFolderAvailability(it, folderAvailabilityById) }
      existingFolderAvailability = null
      resolvedIsPinned = draft.isPinned

      val reliableInitialPaper = draft.initialPaperStyle
      baseline =
        if (reliableInitialPaper == null) {
          NoteEditorBaseline.LegacyIndeterminate(draftId = draft.draftId)
        } else {
          NoteEditorBaseline.NewNote(
            title = "",
            normalizedContent = canonicalizeDocument(NoteContentDocument.empty()),
            paperStyle = reliableInitialPaper,
            staticFolderId = draft.initialFolderId,
            initialIsPinned = false,
          )
        }
      activeConflict = null
    } else {
      val targetNoteId = draft.targetNoteId
      if (existingNote == null || existingNote.id != targetNoteId) {
        return RecoveryOpeningEvaluation.Blocked(SessionBlockReason.TargetNoteNotFound(targetNoteId))
      }
      if (existingNote.isDeleted) {
        return RecoveryOpeningEvaluation.Blocked(SessionBlockReason.TargetNoteInTrash(targetNoteId))
      }
      val noteContentState = existingNote.contentState
      if (noteContentState is NoteContentState.RequiresHandling) {
        return RecoveryOpeningEvaluation.Blocked(
          SessionBlockReason.UnreadableTargetNoteContent(targetNoteId, noteContentState)
        )
      }

      val canonicalNoteDoc = canonicalizeDocument((noteContentState as NoteContentState.Valid).document)
      // Nota existente sempre ignora initialFolderId/initialPaperStyle e usa isPinned atual do banco.
      effectiveInitialFolderAvailability = null
      existingFolderAvailability = resolveFolderAvailability(existingNote.folderId, folderAvailabilityById)
      resolvedIsPinned = existingNote.isPinned
      if (draft.isPinned != existingNote.isPinned) {
        extraWarnings.add(SessionWarning.ExistingNotePinUpdatedExternally(existingNote.isPinned))
      }

      baseline =
        NoteEditorBaseline.ExistingNote(
          noteId = existingNote.id,
          baseRevision = existingNote.revision,
          baseNoteUpdatedAt = existingNote.updatedAt,
          title = existingNote.title,
          normalizedContent = canonicalNoteDoc,
          paperStyle = existingNote.paperStyle,
          staticFolderId = existingNote.folderId,
          currentDbIsPinned = existingNote.isPinned,
        )

      activeConflict =
        if (!draft.hasValidBaseReference) {
          RecoveryConflict.InvalidBaseReference(
            draftId = draft.draftId,
            targetNoteId = targetNoteId,
            draftBaseRevision = draft.baseRevision,
            draftBaseUpdatedAt = draft.baseNoteUpdatedAt,
          )
        } else if (draft.hasConflictWith(existingNote)) {
          RecoveryConflict.NoteDiverged(
            draftId = draft.draftId,
            targetNoteId = targetNoteId,
            draftBaseRevision = draft.baseRevision,
            currentNoteRevision = existingNote.revision,
            draftBaseUpdatedAt = draft.baseNoteUpdatedAt,
            currentNoteUpdatedAt = existingNote.updatedAt,
          )
        } else {
          null
        }
    }

    // Contrato de exclusividade e fallback na recuperação:
    // Se fallbackSourceFolderId já estava persistido, nunca reativa como pasta efetiva mesmo se restaurada.
    val (requestedEffectiveId, requestedFallbackId) =
      if (draft.fallbackSourceFolderId != null) {
        null to draft.fallbackSourceFolderId
      } else {
        draft.folderId to null
      }

    val (effectiveFolderId, fallbackSourceFolderId, effectiveAvailability, fallbackAvailability) =
      resolveDestinationTuple(
        requestedEffectiveFolderId = requestedEffectiveId,
        requestedFallbackSourceFolderId = requestedFallbackId,
        folderAvailabilityById = folderAvailabilityById,
      )

    val folderContext =
      ResolvedDraftFolderContext(
        effectiveTarget = effectiveAvailability,
        fallbackSource = fallbackAvailability,
        initialBaseline = effectiveInitialFolderAvailability,
        existingNoteFolder = existingFolderAvailability,
      )

    val snapshot =
      NoteEditorEditableSnapshot(
        title = draft.title,
        document = draftDocument,
        effectiveFolderId = effectiveFolderId,
        fallbackSourceFolderId = fallbackSourceFolderId,
        paperStyle = draft.paperStyle,
        isPinned = resolvedIsPinned,
      )

    val baseEvaluation =
      evaluateSession(
        NoteEditorSessionEvaluationInput(
          lifecycleStage = SessionLifecycleStage.EDITING,
          baseline = baseline,
          snapshot = snapshot,
          folderContext = folderContext,
          activeConflict = activeConflict,
        )
      )

    val combinedWarnings = Collections.unmodifiableList((extraWarnings + baseEvaluation.warnings).distinct())
    return RecoveryOpeningEvaluation.ReadyToEdit(
      identity = identity,
      baseline = baseline,
      initialSnapshot = snapshot,
      folderContext = folderContext,
      activeConflict = activeConflict,
      evaluation = baseEvaluation.copy(warnings = combinedWarnings),
    )
  }

  /**
   * Avalia o estado corrente da sessão (dirty, retenção de checkpoint, reconciliação,
   * prontidão para consolidação e avisos) sem executar I/O.
   */
  fun evaluateSession(
    input: NoteEditorSessionEvaluationInput,
  ): NoteEditorSessionEvaluation {
    if (input.lifecycleStage != SessionLifecycleStage.EDITING) {
      val coherentContext =
        input.snapshot?.let { snap ->
          resolveCoherentFolderContext(
            baseline = input.baseline,
            snapshot = snap,
            folderContext = input.folderContext,
          )
        } ?: input.folderContext
      return NoteEditorSessionEvaluation(
        hasUnsavedChanges = false,
        dirtyBreakdown = DirtyChangeBreakdown(),
        intendedUserFolderId = input.snapshot?.intendedUserFolderId,
        effectiveFolderId = input.snapshot?.effectiveFolderId,
        fallbackSourceFolderId = input.snapshot?.fallbackSourceFolderId,
        hasPendingFolderFallback = input.snapshot?.fallbackSourceFolderId != null,
        needsDraftRetention = false,
        canSafelyReconcileToCleanBaseline = false,
        consolidationReadiness = ConsolidationReadiness.BlockedBySessionState(input.blockReason),
        warnings = collectWarnings(input, coherentContext, fallbackAppliedByEvaluator = null),
      )
    }

    val baseline =
      input.baseline
        ?: return NoteEditorSessionEvaluation(
          hasUnsavedChanges = false,
          dirtyBreakdown = DirtyChangeBreakdown(),
          intendedUserFolderId = input.snapshot?.intendedUserFolderId,
          effectiveFolderId = input.snapshot?.effectiveFolderId,
          fallbackSourceFolderId = input.snapshot?.fallbackSourceFolderId,
          hasPendingFolderFallback = input.snapshot?.fallbackSourceFolderId != null,
          needsDraftRetention = false,
          canSafelyReconcileToCleanBaseline = false,
          consolidationReadiness = ConsolidationReadiness.BlockedBySessionState(input.blockReason),
          warnings = Collections.unmodifiableList(emptyList()),
        )

    val snapshot =
      input.snapshot
        ?: return NoteEditorSessionEvaluation(
          hasUnsavedChanges = false,
          dirtyBreakdown = DirtyChangeBreakdown(),
          intendedUserFolderId = null,
          effectiveFolderId = null,
          fallbackSourceFolderId = null,
          hasPendingFolderFallback = false,
          needsDraftRetention = false,
          canSafelyReconcileToCleanBaseline = false,
          consolidationReadiness = ConsolidationReadiness.BlockedBySessionState(input.blockReason),
          warnings = Collections.unmodifiableList(emptyList()),
        )

    // Valida coerência entre o snapshot e o folderContext antes de calcular dirty e consolidação.
    val coherentContext =
      resolveCoherentFolderContext(
        baseline = baseline,
        snapshot = snapshot,
        folderContext = input.folderContext,
      )

    // Se o destino efetivo corrente é sabidamente indisponível (InTrash / PermanentlyDeleted / NotFound),
    // aplica fallback efetivo para null preservando a pasta de origem em fallbackSourceFolderId.
    val (resolvedEffectiveFolderId, resolvedFallbackSourceFolderId, fallbackAppliedWarning) =
      normalizeUnavailableEffectiveTarget(
        snapshot = snapshot,
        effectiveAvailability = coherentContext.effectiveTarget,
      )

    val effectiveContextAfterFallback =
      if (fallbackAppliedWarning != null) {
        coherentContext.copy(
          effectiveTarget = FolderAvailability.Root,
          fallbackSource = coherentContext.effectiveTarget,
        )
      } else {
        coherentContext
      }

    val intendedUserFolderId = resolvedFallbackSourceFolderId ?: resolvedEffectiveFolderId
    val hasPendingFolderFallback = resolvedFallbackSourceFolderId != null

    val dirtyBreakdown: DirtyChangeBreakdown =
      when (baseline) {
        is NoteEditorBaseline.LegacyIndeterminate ->
          DirtyChangeBreakdown(forcedByLegacyIndeterminate = true)

        is NoteEditorBaseline.NewNote ->
          DirtyChangeBreakdown(
            titleChanged = snapshot.title != baseline.title,
            contentChanged = !areDocumentsEquivalent(snapshot.document, baseline.normalizedContent),
            folderChanged = intendedUserFolderId != baseline.staticFolderId,
            paperStyleChanged = snapshot.paperStyle != baseline.paperStyle,
            pinChanged = snapshot.isPinned != baseline.initialIsPinned,
            forcedByLegacyIndeterminate = false,
          )

        is NoteEditorBaseline.ExistingNote ->
          DirtyChangeBreakdown(
            titleChanged = snapshot.title != baseline.title,
            contentChanged = !areDocumentsEquivalent(snapshot.document, baseline.normalizedContent),
            folderChanged = intendedUserFolderId != baseline.staticFolderId,
            paperStyleChanged = snapshot.paperStyle != baseline.paperStyle,
            // Regra obrigatória: em nota existente, isPinned nunca participa da avaliação de dirty do editor.
            pinChanged = false,
            forcedByLegacyIndeterminate = false,
          )
      }

    val hasUnsavedChanges = dirtyBreakdown.hasAnyChange
    val isLegacy = baseline is NoteEditorBaseline.LegacyIndeterminate
    val hasActiveConflict = input.activeConflict != null
    val hasUnknownCurrentDestination =
      effectiveContextAfterFallback.effectiveTarget is FolderAvailability.Unknown
    val needsDraftRetention =
      hasUnsavedChanges ||
        hasPendingFolderFallback ||
        isLegacy ||
        hasActiveConflict ||
        hasUnknownCurrentDestination
    val canSafelyReconcileToCleanBaseline = !needsDraftRetention

    val consolidationReadiness =
      evaluateConsolidationReadiness(
        input = input,
        coherentContext = effectiveContextAfterFallback,
        resolvedEffectiveFolderId = resolvedEffectiveFolderId,
        resolvedFallbackSourceFolderId = resolvedFallbackSourceFolderId,
      )

    val warnings =
      collectWarnings(
        input = input,
        coherentContext = effectiveContextAfterFallback,
        fallbackAppliedByEvaluator = fallbackAppliedWarning,
      )

    return NoteEditorSessionEvaluation(
      hasUnsavedChanges = hasUnsavedChanges,
      dirtyBreakdown = dirtyBreakdown,
      intendedUserFolderId = intendedUserFolderId,
      effectiveFolderId = resolvedEffectiveFolderId,
      fallbackSourceFolderId = resolvedFallbackSourceFolderId,
      hasPendingFolderFallback = hasPendingFolderFallback,
      needsDraftRetention = needsDraftRetention,
      canSafelyReconcileToCleanBaseline = canSafelyReconcileToCleanBaseline,
      consolidationReadiness = consolidationReadiness,
      warnings = warnings,
    )
  }

  /**
   * Garante que `folderContext` corresponda exatamente aos identificadores presentes em `snapshot`
   * e `baseline`, nunca convertendo informação ausente ou inconsistente em confirmação de raiz.
   */
  private fun resolveCoherentFolderContext(
    baseline: NoteEditorBaseline?,
    snapshot: NoteEditorEditableSnapshot,
    folderContext: ResolvedDraftFolderContext,
  ): ResolvedDraftFolderContext {
    val coherentEffectiveTarget: FolderAvailability =
      if (snapshot.effectiveFolderId == null) {
        if (folderContext.effectiveTarget is FolderAvailability.Root) {
          FolderAvailability.Root
        } else {
          val mismatchedId =
            folderContext.effectiveTarget.folderIdOrNull
              ?: error("FolderAvailability não-Root deve possuir folderId.")
          FolderAvailability.Unknown(
            folderId = mismatchedId,
            reason = "Disponibilidade efetiva divergente para destino na raiz.",
          )
        }
      } else {
        val expectedId = snapshot.effectiveFolderId
        val provided = folderContext.effectiveTarget
        if (provided !is FolderAvailability.Root && provided.folderIdOrNull == expectedId) {
          provided
        } else {
          FolderAvailability.Unknown(
            folderId = expectedId,
            reason = "Disponibilidade ausente ou divergente para a pasta $expectedId.",
          )
        }
      }

    val coherentFallbackSource: FolderAvailability? =
      snapshot.fallbackSourceFolderId?.let { expectedFallbackId ->
        val provided = folderContext.fallbackSource
        if (provided != null && provided !is FolderAvailability.Root && provided.folderIdOrNull == expectedFallbackId) {
          provided
        } else {
          FolderAvailability.Unknown(
            folderId = expectedFallbackId,
            reason = "Disponibilidade ausente ou divergente para a pasta de fallback $expectedFallbackId.",
          )
        }
      }

    val coherentInitialBaseline: FolderAvailability? =
      when (baseline) {
        is NoteEditorBaseline.NewNote ->
          when (val baselineFolderId = baseline.staticFolderId) {
            null ->
              if (folderContext.initialBaseline is FolderAvailability.Root) FolderAvailability.Root else null
            else -> {
              val provided = folderContext.initialBaseline
              when {
                provided == null -> null
                provided !is FolderAvailability.Root && provided.folderIdOrNull == baselineFolderId ->
                  provided
                else ->
                  FolderAvailability.Unknown(
                    folderId = baselineFolderId,
                    reason = "Disponibilidade divergente para a pasta inicial $baselineFolderId.",
                  )
              }
            }
          }

        else -> folderContext.initialBaseline
      }

    return folderContext.copy(
      effectiveTarget = coherentEffectiveTarget,
      fallbackSource = coherentFallbackSource,
      initialBaseline = coherentInitialBaseline,
    )
  }

  /**
   * Atualiza o snapshot editável com novas disponibilidades de pasta sem alterar o destino
   * caso a pasta de fallback seja restaurada externamente e sem aplicar fallback quando o estado
   * do destino atual for [FolderAvailability.Unknown].
   */
  fun applyFolderAvailabilityToSnapshot(
    snapshot: NoteEditorEditableSnapshot,
    folderAvailabilityById: Map<Long, FolderAvailability>,
    previousContext: ResolvedDraftFolderContext? = null,
    baseline: NoteEditorBaseline? = null,
  ): Pair<NoteEditorEditableSnapshot, ResolvedDraftFolderContext> {
    val mergedAvailability = mergeAvailabilityMap(folderAvailabilityById, previousContext)
    val (effectiveFolderId, fallbackSourceFolderId, effectiveAvailability, fallbackAvailability) =
      resolveDestinationTuple(
        requestedEffectiveFolderId = snapshot.effectiveFolderId,
        requestedFallbackSourceFolderId = snapshot.fallbackSourceFolderId,
        folderAvailabilityById = mergedAvailability,
      )

    val preservedInitialBaseline =
      resolvePreservedAnchorAvailability(
        anchorFolderId = (baseline as? NoteEditorBaseline.NewNote)?.staticFolderId,
        anchorIsRootBaseline = baseline is NoteEditorBaseline.NewNote && baseline.staticFolderId == null,
        previousAvailability = previousContext?.initialBaseline,
        incomingAvailabilityById = folderAvailabilityById,
        mergedAvailabilityById = mergedAvailability,
      )

    val preservedExistingNoteFolder =
      resolvePreservedAnchorAvailability(
        anchorFolderId = (baseline as? NoteEditorBaseline.ExistingNote)?.staticFolderId,
        anchorIsRootBaseline =
          baseline is NoteEditorBaseline.ExistingNote && baseline.staticFolderId == null,
        previousAvailability = previousContext?.existingNoteFolder,
        incomingAvailabilityById = folderAvailabilityById,
        mergedAvailabilityById = mergedAvailability,
      )

    val updatedSnapshot =
      snapshot.copy(
        effectiveFolderId = effectiveFolderId,
        fallbackSourceFolderId = fallbackSourceFolderId,
      )
    val context =
      ResolvedDraftFolderContext(
        effectiveTarget = effectiveAvailability,
        fallbackSource = fallbackAvailability,
        initialBaseline = preservedInitialBaseline,
        existingNoteFolder = preservedExistingNoteFolder,
      )
    return updatedSnapshot to context
  }

  private fun mergeAvailabilityMap(
    incoming: Map<Long, FolderAvailability>,
    previousContext: ResolvedDraftFolderContext?,
  ): Map<Long, FolderAvailability> {
    if (previousContext == null) return incoming
    val merged = HashMap<Long, FolderAvailability>(incoming.size + 4)
    listOfNotNull(
      previousContext.effectiveTarget,
      previousContext.fallbackSource,
      previousContext.initialBaseline,
      previousContext.existingNoteFolder,
    ).forEach { prev ->
      val prevId = prev.folderIdOrNull
      if (prevId != null && prev !is FolderAvailability.Root) {
        merged[prevId] = prev
      }
    }
    merged.putAll(incoming)
    return merged
  }

  private fun resolvePreservedAnchorAvailability(
    anchorFolderId: Long?,
    anchorIsRootBaseline: Boolean,
    previousAvailability: FolderAvailability?,
    incomingAvailabilityById: Map<Long, FolderAvailability>,
    mergedAvailabilityById: Map<Long, FolderAvailability>,
  ): FolderAvailability? {
    if (anchorIsRootBaseline) {
      return previousAvailability as? FolderAvailability.Root ?: FolderAvailability.Root
    }
    if (anchorFolderId != null) {
      incomingAvailabilityById[anchorFolderId]?.let { return it }
      if (
        previousAvailability != null &&
          previousAvailability !is FolderAvailability.Root &&
          previousAvailability.folderIdOrNull == anchorFolderId
      ) {
        return previousAvailability
      }
      return mergedAvailabilityById[anchorFolderId] ?: previousAvailability
    }
    val prevId = previousAvailability?.folderIdOrNull
    if (prevId != null) {
      return incomingAvailabilityById[prevId] ?: previousAvailability
    }
    return previousAvailability
  }

  fun matchesExpectation(
    actualTargetNoteId: Long?,
    expectation: RecoveryTargetExpectation,
  ): Boolean =
    when (expectation) {
      RecoveryTargetExpectation.Any -> true
      RecoveryTargetExpectation.NewNote -> actualTargetNoteId == null
      is RecoveryTargetExpectation.ExistingNote -> actualTargetNoteId == expectation.noteId
    }

  fun resolveFolderAvailability(
    folderId: Long?,
    folderAvailabilityById: Map<Long, FolderAvailability>,
  ): FolderAvailability {
    if (folderId == null) return FolderAvailability.Root
    return folderAvailabilityById[folderId] ?: FolderAvailability.Unknown(folderId)
  }

  private fun evaluateConsolidationReadiness(
    input: NoteEditorSessionEvaluationInput,
    coherentContext: ResolvedDraftFolderContext,
    resolvedEffectiveFolderId: Long?,
    resolvedFallbackSourceFolderId: Long?,
  ): ConsolidationReadiness {
    val activeConflict = input.activeConflict
    if (activeConflict != null) {
      return ConsolidationReadiness.BlockedByConflict(activeConflict)
    }

    val effectiveAvailability = coherentContext.effectiveTarget
    if (effectiveAvailability is FolderAvailability.Unknown) {
      return ConsolidationReadiness.BlockedByUnknownFolderState(
        folderId = effectiveAvailability.folderId,
        reason = effectiveAvailability.reason,
      )
    }

    if (resolvedFallbackSourceFolderId != null) {
      val fallbackAvailability =
        coherentContext.fallbackSource
          ?: FolderAvailability.Unknown(
            folderId = resolvedFallbackSourceFolderId,
            reason = "Disponibilidade ausente para a pasta de fallback $resolvedFallbackSourceFolderId.",
          )
      return ConsolidationReadiness.RequiresExplicitFolderConfirmation(
        fallbackSourceFolderId = resolvedFallbackSourceFolderId,
        fallbackSourceAvailability = fallbackAvailability,
      )
    }

    return ConsolidationReadiness.Ready(effectiveFolderId = resolvedEffectiveFolderId)
  }

  private fun normalizeUnavailableEffectiveTarget(
    snapshot: NoteEditorEditableSnapshot,
    effectiveAvailability: FolderAvailability,
  ): Triple<Long?, Long?, SessionWarning.FallbackFolderApplied?> {
    if (snapshot.fallbackSourceFolderId != null) {
      return Triple(null, snapshot.fallbackSourceFolderId, null)
    }

    val currentEffectiveId = snapshot.effectiveFolderId
    if (currentEffectiveId != null && effectiveAvailability.isDefinitelyUnavailable) {
      return Triple(
        null,
        currentEffectiveId,
        SessionWarning.FallbackFolderApplied(
          originalFolderId = currentEffectiveId,
          availability = effectiveAvailability,
        ),
      )
    }

    return Triple(currentEffectiveId, null, null)
  }

  private data class DestinationResolution(
    val effectiveFolderId: Long?,
    val fallbackSourceFolderId: Long?,
    val effectiveAvailability: FolderAvailability,
    val fallbackAvailability: FolderAvailability?,
  )

  private fun resolveDestinationTuple(
    requestedEffectiveFolderId: Long?,
    requestedFallbackSourceFolderId: Long?,
    folderAvailabilityById: Map<Long, FolderAvailability>,
  ): DestinationResolution {
    if (requestedFallbackSourceFolderId != null) {
      return DestinationResolution(
        effectiveFolderId = null,
        fallbackSourceFolderId = requestedFallbackSourceFolderId,
        effectiveAvailability = FolderAvailability.Root,
        fallbackAvailability =
          resolveFolderAvailability(requestedFallbackSourceFolderId, folderAvailabilityById),
      )
    }

    if (requestedEffectiveFolderId == null) {
      return DestinationResolution(
        effectiveFolderId = null,
        fallbackSourceFolderId = null,
        effectiveAvailability = FolderAvailability.Root,
        fallbackAvailability = null,
      )
    }

    val availability = resolveFolderAvailability(requestedEffectiveFolderId, folderAvailabilityById)
    return when {
      availability.isDefinitelyUnavailable ->
        DestinationResolution(
          effectiveFolderId = null,
          fallbackSourceFolderId = requestedEffectiveFolderId,
          effectiveAvailability = FolderAvailability.Root,
          fallbackAvailability = availability,
        )

      else ->
        DestinationResolution(
          effectiveFolderId = requestedEffectiveFolderId,
          fallbackSourceFolderId = null,
          effectiveAvailability = availability,
          fallbackAvailability = null,
        )
    }
  }

  private fun collectWarnings(
    input: NoteEditorSessionEvaluationInput,
    coherentContext: ResolvedDraftFolderContext,
    fallbackAppliedByEvaluator: SessionWarning.FallbackFolderApplied?,
  ): List<SessionWarning> =
    Collections.unmodifiableList(
      buildList {
        val snapshot = input.snapshot
        if (fallbackAppliedByEvaluator != null) {
          add(fallbackAppliedByEvaluator)
        } else if (
          snapshot?.fallbackSourceFolderId != null &&
            coherentContext.fallbackSource != null
        ) {
          add(
            SessionWarning.FallbackFolderApplied(
              originalFolderId = snapshot.fallbackSourceFolderId,
              availability = coherentContext.fallbackSource,
            )
          )
        }

        val effectiveTarget = coherentContext.effectiveTarget
        if (effectiveTarget is FolderAvailability.Unknown) {
          add(
            SessionWarning.CurrentDestinationUnknown(
              folderId = effectiveTarget.folderId,
              reason = effectiveTarget.reason,
            )
          )
        }

        val initialBaseline = coherentContext.initialBaseline
        if (initialBaseline != null && initialBaseline.isDefinitelyUnavailable) {
          initialBaseline.folderIdOrNull?.let { folderId ->
            add(
              SessionWarning.OriginalBaselineFolderUnavailable(
                baselineFolderId = folderId,
                availability = initialBaseline,
              )
            )
          }
        }

        val baseline = input.baseline
        if (baseline is NoteEditorBaseline.LegacyIndeterminate) {
          add(SessionWarning.LegacyDraftIndeterminateBaseline(baseline.draftId))
        }

        val conflict = input.activeConflict
        if (conflict != null) {
          add(SessionWarning.UnresolvedRecoveryConflict(conflict))
        }
      }
    )
}
