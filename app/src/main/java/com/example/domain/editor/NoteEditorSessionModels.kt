package com.example.domain.editor

import com.example.domain.model.Folder
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NoteContentState
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.NoteRecoveryDraftKeys

/**
 * Expectativa declarada pelo fluxo de abertura ao recuperar um checkpoint persistido.
 */
sealed interface RecoveryTargetExpectation {
  data object Any : RecoveryTargetExpectation

  data object NewNote : RecoveryTargetExpectation

  data class ExistingNote(val noteId: Long) : RecoveryTargetExpectation {
    init {
      require(noteId > 0L) { "noteId esperado deve ser positivo: $noteId" }
    }
  }
}

/**
 * Solicitação de abertura de uma sessão do editor.
 */
sealed interface NoteEditorOpenRequest {
  data class CreateNew(
    val initialFolderId: Long? = null,
    val defaultPaperStyle: NotePaperStyle = NotePaperStyle.LINHAS,
    val initialPinned: Boolean = false,
    val sessionId: String? = null,
  ) : NoteEditorOpenRequest

  data class OpenExisting(
    val noteId: Long,
    val sessionId: String? = null,
  ) : NoteEditorOpenRequest {
    init {
      require(noteId > 0L) { "noteId deve ser positivo: $noteId" }
    }
  }

  data class RecoverDraft(
    val draftId: String,
    val expectedTarget: RecoveryTargetExpectation = RecoveryTargetExpectation.Any,
  ) : NoteEditorOpenRequest {
    init {
      require(draftId.isNotBlank()) { "draftId não pode ficar em branco." }
    }
  }
}

/**
 * Identidade imutável da sessão vinculada ao par (`draftId`, `sessionId`, `targetNoteId`).
 */
data class NoteEditorSessionIdentity(
  val draftId: String,
  val sessionId: String,
  val targetNoteId: Long?,
) {
  init {
    NoteRecoveryDraftKeys.assertConsistentKeys(
      draftId = draftId,
      sessionId = sessionId,
      targetNoteId = targetNoteId,
    )
  }
}

/**
 * Estado resolvido de disponibilidade de uma referência de pasta, já obtido antes da avaliação pura.
 */
sealed interface FolderAvailability {
  data object Root : FolderAvailability

  data class Active(val folder: Folder) : FolderAvailability {
    val folderId: Long
      get() = folder.id
  }

  data class InTrash(val folder: Folder) : FolderAvailability {
    val folderId: Long
      get() = folder.id
  }

  data class PermanentlyDeleted(val folderId: Long) : FolderAvailability {
    init {
      require(folderId > 0L) { "folderId deve ser positivo: $folderId" }
    }
  }

  data class NotFound(val folderId: Long) : FolderAvailability {
    init {
      require(folderId > 0L) { "folderId deve ser positivo: $folderId" }
    }
  }

  data class Unknown(
    val folderId: Long,
    val reason: String? = null,
  ) : FolderAvailability {
    init {
      require(folderId > 0L) { "folderId deve ser positivo: $folderId" }
    }
  }

  val folderIdOrNull: Long?
    get() =
      when (this) {
        Root -> null
        is Active -> folder.id
        is InTrash -> folder.id
        is PermanentlyDeleted -> folderId
        is NotFound -> folderId
        is Unknown -> folderId
      }

  val isActiveOrRoot: Boolean
    get() = this is Root || this is Active

  val isDefinitelyUnavailable: Boolean
    get() = this is InTrash || this is PermanentlyDeleted || this is NotFound
}

/**
 * Referências de pastas associadas à sessão já resolvidas pelo chamador.
 */
data class ResolvedDraftFolderContext(
  val effectiveTarget: FolderAvailability = FolderAvailability.Root,
  val fallbackSource: FolderAvailability? = null,
  val initialBaseline: FolderAvailability? = null,
  val existingNoteFolder: FolderAvailability? = null,
)

/**
 * Baseline estático da sessão contra o qual o estado editável é comparado.
 */
sealed interface NoteEditorBaseline {
  data class NewNote(
    val title: String,
    val normalizedContent: NoteContentDocument,
    val paperStyle: NotePaperStyle,
    val staticFolderId: Long?,
    val initialIsPinned: Boolean,
  ) : NoteEditorBaseline

  data class LegacyIndeterminate(
    val draftId: String,
  ) : NoteEditorBaseline

  data class ExistingNote(
    val noteId: Long,
    val baseRevision: Long,
    val baseNoteUpdatedAt: Long,
    val title: String,
    val normalizedContent: NoteContentDocument,
    val paperStyle: NotePaperStyle,
    val staticFolderId: Long?,
    val currentDbIsPinned: Boolean,
  ) : NoteEditorBaseline
}

/**
 * Conflito entre a referência-base do rascunho e a nota persistida atual.
 */
sealed interface RecoveryConflict {
  val draftId: String
  val targetNoteId: Long

  data class NoteDiverged(
    override val draftId: String,
    override val targetNoteId: Long,
    val draftBaseRevision: Long,
    val currentNoteRevision: Long,
    val draftBaseUpdatedAt: Long?,
    val currentNoteUpdatedAt: Long,
  ) : RecoveryConflict

  data class InvalidBaseReference(
    override val draftId: String,
    override val targetNoteId: Long,
    val draftBaseRevision: Long,
    val draftBaseUpdatedAt: Long?,
  ) : RecoveryConflict
}

enum class ConflictResolutionOption {
  KEEP_DRAFT_OVER_CURRENT_NOTE,
  DISCARD_DRAFT_USE_CURRENT_NOTE,
  SAVE_DRAFT_AS_NEW_NOTE,
}

/**
 * Condições impeditivas para abertura ou edição segura da sessão.
 */
sealed interface SessionBlockReason {
  data class DraftNotFound(val draftId: String) : SessionBlockReason

  data class DraftTargetMismatch(
    val draftId: String,
    val expected: RecoveryTargetExpectation,
    val actualTargetNoteId: Long?,
  ) : SessionBlockReason

  data class TargetNoteNotFound(val noteId: Long) : SessionBlockReason

  data class TargetNoteInTrash(val noteId: Long) : SessionBlockReason

  data class UnreadableTargetNoteContent(
    val noteId: Long,
    val contentState: NoteContentState.RequiresHandling,
  ) : SessionBlockReason

  data class UnreadableDraftContent(
    val draftId: String,
    val contentState: NoteContentState.RequiresHandling,
  ) : SessionBlockReason

  data class InvalidDraftBaseReference(
    val draftId: String,
    val targetNoteId: Long?,
  ) : SessionBlockReason

  data class InvalidPersistedDraftKeys(
    val draftId: String,
    val reason: String,
  ) : SessionBlockReason
}

/**
 * Avisos não impeditivos relevantes para transparência na interface.
 */
sealed interface SessionWarning {
  data class FallbackFolderApplied(
    val originalFolderId: Long,
    val availability: FolderAvailability,
  ) : SessionWarning

  data class OriginalBaselineFolderUnavailable(
    val baselineFolderId: Long,
    val availability: FolderAvailability,
  ) : SessionWarning

  data class CurrentDestinationUnknown(
    val folderId: Long,
    val reason: String? = null,
  ) : SessionWarning

  data class LegacyDraftIndeterminateBaseline(val draftId: String) : SessionWarning

  data class UnresolvedRecoveryConflict(val conflict: RecoveryConflict) : SessionWarning

  data class ExistingNotePinUpdatedExternally(val currentDbIsPinned: Boolean) : SessionWarning
}

/**
 * Estado de prontidão da sessão para consolidação definitiva na tabela `notes`.
 */
sealed interface ConsolidationReadiness {
  data class Ready(val effectiveFolderId: Long?) : ConsolidationReadiness

  data class RequiresExplicitFolderConfirmation(
    val fallbackSourceFolderId: Long,
    val fallbackSourceAvailability: FolderAvailability,
  ) : ConsolidationReadiness

  data class BlockedByUnknownFolderState(
    val folderId: Long,
    val reason: String? = null,
  ) : ConsolidationReadiness

  data class BlockedByConflict(val conflict: RecoveryConflict) : ConsolidationReadiness

  data class BlockedBySessionState(val reason: SessionBlockReason?) : ConsolidationReadiness
}

/**
 * Retrato editável atual mantido pela sessão (sem I/O).
 */
data class NoteEditorEditableSnapshot(
  val title: String,
  val document: NoteContentDocument,
  val effectiveFolderId: Long?,
  val fallbackSourceFolderId: Long?,
  val paperStyle: NotePaperStyle,
  val isPinned: Boolean,
) {
  init {
    require(effectiveFolderId == null || fallbackSourceFolderId == null) {
      "effectiveFolderId e fallbackSourceFolderId são mutuamente exclusivos."
    }
  }

  val intendedUserFolderId: Long?
    get() = fallbackSourceFolderId ?: effectiveFolderId
}

data class DirtyChangeBreakdown(
  val titleChanged: Boolean = false,
  val contentChanged: Boolean = false,
  val folderChanged: Boolean = false,
  val paperStyleChanged: Boolean = false,
  val pinChanged: Boolean = false,
  val forcedByLegacyIndeterminate: Boolean = false,
) {
  val hasAnyChange: Boolean
    get() =
      titleChanged ||
        contentChanged ||
        folderChanged ||
        paperStyleChanged ||
        pinChanged ||
        forcedByLegacyIndeterminate
}

enum class SessionLifecycleStage {
  LOADING,
  EDITING,
  BLOCKED,
  ERROR,
}

data class NoteEditorSessionEvaluationInput(
  val lifecycleStage: SessionLifecycleStage,
  val baseline: NoteEditorBaseline?,
  val snapshot: NoteEditorEditableSnapshot?,
  val folderContext: ResolvedDraftFolderContext = ResolvedDraftFolderContext(),
  val activeConflict: RecoveryConflict? = null,
  val blockReason: SessionBlockReason? = null,
)

data class NoteEditorSessionEvaluation(
  val hasUnsavedChanges: Boolean,
  val dirtyBreakdown: DirtyChangeBreakdown,
  val intendedUserFolderId: Long?,
  val effectiveFolderId: Long?,
  val fallbackSourceFolderId: Long?,
  val hasPendingFolderFallback: Boolean,
  val needsDraftRetention: Boolean,
  val canSafelyReconcileToCleanBaseline: Boolean,
  val consolidationReadiness: ConsolidationReadiness,
  val warnings: List<SessionWarning>,
)

/**
 * Resultado puro da preparação/abertura de uma solicitação antes da entrada no loop de edição.
 */
sealed interface RecoveryOpeningEvaluation {
  data class ReadyToEdit(
    val identity: NoteEditorSessionIdentity,
    val baseline: NoteEditorBaseline,
    val initialSnapshot: NoteEditorEditableSnapshot,
    val folderContext: ResolvedDraftFolderContext,
    val activeConflict: RecoveryConflict?,
    val evaluation: NoteEditorSessionEvaluation,
  ) : RecoveryOpeningEvaluation

  data class Blocked(
    val reason: SessionBlockReason,
    val warnings: List<SessionWarning> = emptyList(),
  ) : RecoveryOpeningEvaluation
}
