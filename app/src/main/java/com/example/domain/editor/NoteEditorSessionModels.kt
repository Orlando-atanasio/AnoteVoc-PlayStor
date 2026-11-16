package com.example.domain.editor

import com.example.domain.model.BulletListItemBlock
import com.example.domain.model.ChecklistItemBlock
import com.example.domain.model.ContentBlock
import com.example.domain.model.Folder
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NoteContentState
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.NoteRecoveryDraftKeys
import com.example.domain.model.NumberedListItemBlock
import com.example.domain.model.ParagraphBlock
import java.util.Collections

/**
 * Cria cópia defensiva profunda de [NoteContentDocument] com listas não modificáveis
 * para impedir mutação externa posterior nos limites da camada de sessão.
 */
internal fun NoteContentDocument.toImmutableSnapshot(): NoteContentDocument =
  NoteContentDocument(
    schemaVersion = schemaVersion,
    blocks = Collections.unmodifiableList(blocks.map { block -> block.toImmutableSnapshot() }),
  )

internal fun ContentBlock.toImmutableSnapshot(): ContentBlock =
  when (this) {
    is ParagraphBlock ->
      copy(spans = Collections.unmodifiableList(spans.toList()))
    is BulletListItemBlock ->
      copy(spans = Collections.unmodifiableList(spans.toList()))
    is NumberedListItemBlock ->
      copy(spans = Collections.unmodifiableList(spans.toList()))
    is ChecklistItemBlock ->
      copy(spans = Collections.unmodifiableList(spans.toList()))
  }

internal fun NoteEditorState.toImmutableSnapshot(): NoteEditorState =
  copy(
    document = document.toImmutableSnapshot(),
    activeTypingStyles = Collections.unmodifiableSet(activeTypingStyles.toSet()),
    numberedListNumbers = Collections.unmodifiableMap(numberedListNumbers.toMap()),
  )

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
    val defaultPaperStyle: NotePaperStyle? = null,
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
  class NewNote private constructor(
    val title: String,
    val normalizedContent: NoteContentDocument,
    val paperStyle: NotePaperStyle,
    val staticFolderId: Long?,
    val initialIsPinned: Boolean,
  ) : NoteEditorBaseline {
    fun copy(
      title: String = this.title,
      normalizedContent: NoteContentDocument = this.normalizedContent,
      paperStyle: NotePaperStyle = this.paperStyle,
      staticFolderId: Long? = this.staticFolderId,
      initialIsPinned: Boolean = this.initialIsPinned,
    ): NewNote =
      invoke(
        title = title,
        normalizedContent = normalizedContent,
        paperStyle = paperStyle,
        staticFolderId = staticFolderId,
        initialIsPinned = initialIsPinned,
      )

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is NewNote) return false
      return title == other.title &&
        normalizedContent == other.normalizedContent &&
        paperStyle == other.paperStyle &&
        staticFolderId == other.staticFolderId &&
        initialIsPinned == other.initialIsPinned
    }

    override fun hashCode(): Int {
      var result = title.hashCode()
      result = 31 * result + normalizedContent.hashCode()
      result = 31 * result + paperStyle.hashCode()
      result = 31 * result + (staticFolderId?.hashCode() ?: 0)
      result = 31 * result + initialIsPinned.hashCode()
      return result
    }

    override fun toString(): String =
      "NewNote(title=$title, normalizedContent=$normalizedContent, paperStyle=$paperStyle, " +
        "staticFolderId=$staticFolderId, initialIsPinned=$initialIsPinned)"

    companion object {
      operator fun invoke(
        title: String,
        normalizedContent: NoteContentDocument,
        paperStyle: NotePaperStyle,
        staticFolderId: Long?,
        initialIsPinned: Boolean,
      ): NewNote =
        NewNote(
          title = title,
          normalizedContent = normalizedContent.toImmutableSnapshot(),
          paperStyle = paperStyle,
          staticFolderId = staticFolderId,
          initialIsPinned = initialIsPinned,
        )
    }
  }

  data class LegacyIndeterminate(
    val draftId: String,
  ) : NoteEditorBaseline

  class ExistingNote private constructor(
    val noteId: Long,
    val baseRevision: Long,
    val baseNoteUpdatedAt: Long,
    val title: String,
    val normalizedContent: NoteContentDocument,
    val paperStyle: NotePaperStyle,
    val staticFolderId: Long?,
    val currentDbIsPinned: Boolean,
  ) : NoteEditorBaseline {
    fun copy(
      noteId: Long = this.noteId,
      baseRevision: Long = this.baseRevision,
      baseNoteUpdatedAt: Long = this.baseNoteUpdatedAt,
      title: String = this.title,
      normalizedContent: NoteContentDocument = this.normalizedContent,
      paperStyle: NotePaperStyle = this.paperStyle,
      staticFolderId: Long? = this.staticFolderId,
      currentDbIsPinned: Boolean = this.currentDbIsPinned,
    ): ExistingNote =
      invoke(
        noteId = noteId,
        baseRevision = baseRevision,
        baseNoteUpdatedAt = baseNoteUpdatedAt,
        title = title,
        normalizedContent = normalizedContent,
        paperStyle = paperStyle,
        staticFolderId = staticFolderId,
        currentDbIsPinned = currentDbIsPinned,
      )

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is ExistingNote) return false
      return noteId == other.noteId &&
        baseRevision == other.baseRevision &&
        baseNoteUpdatedAt == other.baseNoteUpdatedAt &&
        title == other.title &&
        normalizedContent == other.normalizedContent &&
        paperStyle == other.paperStyle &&
        staticFolderId == other.staticFolderId &&
        currentDbIsPinned == other.currentDbIsPinned
    }

    override fun hashCode(): Int {
      var result = noteId.hashCode()
      result = 31 * result + baseRevision.hashCode()
      result = 31 * result + baseNoteUpdatedAt.hashCode()
      result = 31 * result + title.hashCode()
      result = 31 * result + normalizedContent.hashCode()
      result = 31 * result + paperStyle.hashCode()
      result = 31 * result + (staticFolderId?.hashCode() ?: 0)
      result = 31 * result + currentDbIsPinned.hashCode()
      return result
    }

    override fun toString(): String =
      "ExistingNote(noteId=$noteId, baseRevision=$baseRevision, baseNoteUpdatedAt=$baseNoteUpdatedAt, " +
        "title=$title, normalizedContent=$normalizedContent, paperStyle=$paperStyle, " +
        "staticFolderId=$staticFolderId, currentDbIsPinned=$currentDbIsPinned)"

    companion object {
      operator fun invoke(
        noteId: Long,
        baseRevision: Long,
        baseNoteUpdatedAt: Long,
        title: String,
        normalizedContent: NoteContentDocument,
        paperStyle: NotePaperStyle,
        staticFolderId: Long?,
        currentDbIsPinned: Boolean,
      ): ExistingNote =
        ExistingNote(
          noteId = noteId,
          baseRevision = baseRevision,
          baseNoteUpdatedAt = baseNoteUpdatedAt,
          title = title,
          normalizedContent = normalizedContent.toImmutableSnapshot(),
          paperStyle = paperStyle,
          staticFolderId = staticFolderId,
          currentDbIsPinned = currentDbIsPinned,
        )
    }
  }
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

  data class DraftIdentityMismatch(
    val requestedDraftId: String,
    val providedDraftId: String,
  ) : SessionBlockReason

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

  data class SessionAlreadyActive(
    val requestedDraftId: String?,
    val requestedSessionId: String?,
    val requestedTargetNoteId: Long?,
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
 * Retrato editável atual mantido pela sessão (sem I/O), com cópia defensiva profunda de documento.
 */
class NoteEditorEditableSnapshot private constructor(
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

  fun copy(
    title: String = this.title,
    document: NoteContentDocument = this.document,
    effectiveFolderId: Long? = this.effectiveFolderId,
    fallbackSourceFolderId: Long? = this.fallbackSourceFolderId,
    paperStyle: NotePaperStyle = this.paperStyle,
    isPinned: Boolean = this.isPinned,
  ): NoteEditorEditableSnapshot =
    invoke(
      title = title,
      document = document,
      effectiveFolderId = effectiveFolderId,
      fallbackSourceFolderId = fallbackSourceFolderId,
      paperStyle = paperStyle,
      isPinned = isPinned,
    )

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is NoteEditorEditableSnapshot) return false
    return title == other.title &&
      document == other.document &&
      effectiveFolderId == other.effectiveFolderId &&
      fallbackSourceFolderId == other.fallbackSourceFolderId &&
      paperStyle == other.paperStyle &&
      isPinned == other.isPinned
  }

  override fun hashCode(): Int {
    var result = title.hashCode()
    result = 31 * result + document.hashCode()
    result = 31 * result + (effectiveFolderId?.hashCode() ?: 0)
    result = 31 * result + (fallbackSourceFolderId?.hashCode() ?: 0)
    result = 31 * result + paperStyle.hashCode()
    result = 31 * result + isPinned.hashCode()
    return result
  }

  override fun toString(): String =
    "NoteEditorEditableSnapshot(title=$title, document=$document, effectiveFolderId=$effectiveFolderId, " +
      "fallbackSourceFolderId=$fallbackSourceFolderId, paperStyle=$paperStyle, isPinned=$isPinned)"

  companion object {
    operator fun invoke(
      title: String,
      document: NoteContentDocument,
      effectiveFolderId: Long?,
      fallbackSourceFolderId: Long?,
      paperStyle: NotePaperStyle,
      isPinned: Boolean,
    ): NoteEditorEditableSnapshot =
      NoteEditorEditableSnapshot(
        title = title,
        document = document.toImmutableSnapshot(),
        effectiveFolderId = effectiveFolderId,
        fallbackSourceFolderId = fallbackSourceFolderId,
        paperStyle = paperStyle,
        isPinned = isPinned,
      )
  }
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

class NoteEditorSessionEvaluation private constructor(
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
) {
  fun copy(
    hasUnsavedChanges: Boolean = this.hasUnsavedChanges,
    dirtyBreakdown: DirtyChangeBreakdown = this.dirtyBreakdown,
    intendedUserFolderId: Long? = this.intendedUserFolderId,
    effectiveFolderId: Long? = this.effectiveFolderId,
    fallbackSourceFolderId: Long? = this.fallbackSourceFolderId,
    hasPendingFolderFallback: Boolean = this.hasPendingFolderFallback,
    needsDraftRetention: Boolean = this.needsDraftRetention,
    canSafelyReconcileToCleanBaseline: Boolean = this.canSafelyReconcileToCleanBaseline,
    consolidationReadiness: ConsolidationReadiness = this.consolidationReadiness,
    warnings: List<SessionWarning> = this.warnings,
  ): NoteEditorSessionEvaluation =
    invoke(
      hasUnsavedChanges = hasUnsavedChanges,
      dirtyBreakdown = dirtyBreakdown,
      intendedUserFolderId = intendedUserFolderId,
      effectiveFolderId = effectiveFolderId,
      fallbackSourceFolderId = fallbackSourceFolderId,
      hasPendingFolderFallback = hasPendingFolderFallback,
      needsDraftRetention = needsDraftRetention,
      canSafelyReconcileToCleanBaseline = canSafelyReconcileToCleanBaseline,
      consolidationReadiness = consolidationReadiness,
      warnings = warnings,
    )

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is NoteEditorSessionEvaluation) return false
    return hasUnsavedChanges == other.hasUnsavedChanges &&
      dirtyBreakdown == other.dirtyBreakdown &&
      intendedUserFolderId == other.intendedUserFolderId &&
      effectiveFolderId == other.effectiveFolderId &&
      fallbackSourceFolderId == other.fallbackSourceFolderId &&
      hasPendingFolderFallback == other.hasPendingFolderFallback &&
      needsDraftRetention == other.needsDraftRetention &&
      canSafelyReconcileToCleanBaseline == other.canSafelyReconcileToCleanBaseline &&
      consolidationReadiness == other.consolidationReadiness &&
      warnings == other.warnings
  }

  override fun hashCode(): Int {
    var result = hasUnsavedChanges.hashCode()
    result = 31 * result + dirtyBreakdown.hashCode()
    result = 31 * result + (intendedUserFolderId?.hashCode() ?: 0)
    result = 31 * result + (effectiveFolderId?.hashCode() ?: 0)
    result = 31 * result + (fallbackSourceFolderId?.hashCode() ?: 0)
    result = 31 * result + hasPendingFolderFallback.hashCode()
    result = 31 * result + needsDraftRetention.hashCode()
    result = 31 * result + canSafelyReconcileToCleanBaseline.hashCode()
    result = 31 * result + consolidationReadiness.hashCode()
    result = 31 * result + warnings.hashCode()
    return result
  }

  override fun toString(): String =
    "NoteEditorSessionEvaluation(hasUnsavedChanges=$hasUnsavedChanges, dirtyBreakdown=$dirtyBreakdown, " +
      "intendedUserFolderId=$intendedUserFolderId, effectiveFolderId=$effectiveFolderId, " +
      "fallbackSourceFolderId=$fallbackSourceFolderId, hasPendingFolderFallback=$hasPendingFolderFallback, " +
      "needsDraftRetention=$needsDraftRetention, canSafelyReconcileToCleanBaseline=$canSafelyReconcileToCleanBaseline, " +
      "consolidationReadiness=$consolidationReadiness, warnings=$warnings)"

  companion object {
    operator fun invoke(
      hasUnsavedChanges: Boolean,
      dirtyBreakdown: DirtyChangeBreakdown,
      intendedUserFolderId: Long?,
      effectiveFolderId: Long?,
      fallbackSourceFolderId: Long?,
      hasPendingFolderFallback: Boolean,
      needsDraftRetention: Boolean,
      canSafelyReconcileToCleanBaseline: Boolean,
      consolidationReadiness: ConsolidationReadiness,
      warnings: List<SessionWarning>,
    ): NoteEditorSessionEvaluation =
      NoteEditorSessionEvaluation(
        hasUnsavedChanges = hasUnsavedChanges,
        dirtyBreakdown = dirtyBreakdown,
        intendedUserFolderId = intendedUserFolderId,
        effectiveFolderId = effectiveFolderId,
        fallbackSourceFolderId = fallbackSourceFolderId,
        hasPendingFolderFallback = hasPendingFolderFallback,
        needsDraftRetention = needsDraftRetention,
        canSafelyReconcileToCleanBaseline = canSafelyReconcileToCleanBaseline,
        consolidationReadiness = consolidationReadiness,
        warnings = Collections.unmodifiableList(warnings.toList()),
      )
  }
}

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

  class Blocked private constructor(
    val reason: SessionBlockReason,
    val warnings: List<SessionWarning>,
  ) : RecoveryOpeningEvaluation {
    fun copy(
      reason: SessionBlockReason = this.reason,
      warnings: List<SessionWarning> = this.warnings,
    ): Blocked = invoke(reason = reason, warnings = warnings)

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is Blocked) return false
      return reason == other.reason && warnings == other.warnings
    }

    override fun hashCode(): Int {
      var result = reason.hashCode()
      result = 31 * result + warnings.hashCode()
      return result
    }

    override fun toString(): String = "Blocked(reason=$reason, warnings=$warnings)"

    companion object {
      operator fun invoke(
        reason: SessionBlockReason,
        warnings: List<SessionWarning> = emptyList(),
      ): Blocked =
        Blocked(
          reason = reason,
          warnings = Collections.unmodifiableList(warnings.toList()),
        )
    }
  }
}
