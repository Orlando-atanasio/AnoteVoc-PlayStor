package com.example.domain.model

data class Folder(
  val id: Long,
  val uid: String,
  val name: String,
  val normalizedName: String,
  val isDeleted: Boolean,
  val deletedAt: Long?,
  val createdAt: Long,
  val updatedAt: Long,
)

/**
 * Representa o estado de leitura do conteúdo estruturado de uma nota.
 * Garante que notas com JSON corrompido, versão futura ou bloco desconhecido
 * não quebrem a listagem e preservem o payload original intacto.
 */
sealed interface NoteContentState {
  data class Valid(val document: NoteContentDocument) : NoteContentState

  sealed interface RequiresHandling : NoteContentState {
    val rawJson: String

    data class UnsupportedFutureVersion(
      override val rawJson: String,
      val detectedSchemaVersion: Int,
    ) : RequiresHandling

    data class UnknownBlockType(
      override val rawJson: String,
      val schemaVersion: Int,
      val unknownTypes: List<String>,
    ) : RequiresHandling

    data class CorruptedJson(
      override val rawJson: String,
      val reason: String?,
    ) : RequiresHandling
  }
}

data class Note(
  val id: Long,
  val uid: String,
  val folderId: Long?,
  val title: String,
  val contentState: NoteContentState,
  val plainTextPreview: String,
  val paperStyle: NotePaperStyle = NotePaperStyle.LINHAS,
  val revision: Long = 1L,
  val isPinned: Boolean,
  val pinnedAt: Long?,
  val isDeleted: Boolean,
  val deletedAt: Long?,
  val deletedWithFolder: Boolean,
  val createdAt: Long,
  val updatedAt: Long,
) {
  val contentOrNull: NoteContentDocument?
    get() = (contentState as? NoteContentState.Valid)?.document

  val requiresContentHandling: Boolean
    get() = contentState is NoteContentState.RequiresHandling
}

object NoteRecoveryDraftKeys {
  private const val NEW_DRAFT_PREFIX = "new_"
  private const val EXISTING_NOTE_PREFIX = "note_"
  private const val FORBIDDEN_GENERIC_NEW = "new"
  private val SESSION_ID_REGEX = Regex("^[A-Za-z0-9._:-]+$")

  fun normalizeAndValidateSessionId(sessionId: String): String {
    val cleanSession = sessionId.trim()
    require(cleanSession.isNotEmpty()) {
      "O identificador de sessão não pode ficar em branco."
    }
    require(!cleanSession.equals(FORBIDDEN_GENERIC_NEW, ignoreCase = true)) {
      "Identificador de sessão 'new' é proibido para evitar colisões."
    }
    require(SESSION_ID_REGEX.matches(cleanSession)) {
      "O identificador de sessão possui caracteres inválidos ou espaços internos."
    }
    return cleanSession
  }

  fun forNewNote(sessionId: String): String {
    val cleanSession = normalizeAndValidateSessionId(sessionId)
    return "$NEW_DRAFT_PREFIX$cleanSession"
  }

  fun forExistingNote(noteId: Long, sessionId: String): String {
    require(noteId > 0L) { "Identificador de nota inválido para checkpoint: $noteId" }
    val cleanSession = normalizeAndValidateSessionId(sessionId)
    return "$EXISTING_NOTE_PREFIX${noteId}_$cleanSession"
  }

  fun validateDraftId(draftId: String) {
    val normalized = draftId.trim()
    require(normalized.isNotEmpty()) { "O identificador do rascunho não pode ficar em branco." }
    require(!normalized.equals(FORBIDDEN_GENERIC_NEW, ignoreCase = true)) {
      "Chave genérica 'new' é proibida para evitar colisões entre sessões."
    }
    require(!normalized.any { it.isWhitespace() }) {
      "O identificador do rascunho não pode conter espaços."
    }
  }

  fun expectedDraftId(targetNoteId: Long?, sessionId: String): String {
    val cleanSession = normalizeAndValidateSessionId(sessionId)
    return if (targetNoteId == null) {
      "$NEW_DRAFT_PREFIX$cleanSession"
    } else {
      require(targetNoteId > 0L) { "Identificador de nota alvo inválido: $targetNoteId" }
      "$EXISTING_NOTE_PREFIX${targetNoteId}_$cleanSession"
    }
  }

  fun assertConsistentKeys(
    draftId: String,
    sessionId: String,
    targetNoteId: Long?,
  ): Pair<String, String> {
    val cleanDraftId = draftId.trim()
    validateDraftId(cleanDraftId)
    val cleanSessionId = normalizeAndValidateSessionId(sessionId)
    val expected = expectedDraftId(targetNoteId, cleanSessionId)
    require(cleanDraftId == expected) {
      "Identificador de rascunho '$cleanDraftId' incompatível com targetNoteId=$targetNoteId e sessionId='$cleanSessionId'."
    }
    return cleanDraftId to cleanSessionId
  }
}

data class NoteRecoveryDraft(
  val draftId: String,
  val targetNoteId: Long?,
  val sessionId: String,
  val folderId: Long?,
  val initialFolderId: Long? = null,
  val fallbackSourceFolderId: Long? = null,
  val isPinned: Boolean,
  val title: String,
  val contentState: NoteContentState,
  val paperStyle: NotePaperStyle,
  val initialPaperStyle: NotePaperStyle? = null,
  val baseRevision: Long,
  val checkpointRevision: Long,
  val baseNoteUpdatedAt: Long?,
  val checkpointAt: Long,
) {
  val contentOrNull: NoteContentDocument?
    get() = (contentState as? NoteContentState.Valid)?.document

  val requiresContentHandling: Boolean
    get() = contentState is NoteContentState.RequiresHandling

  val hasValidBaseReference: Boolean
    get() =
      if (targetNoteId == null) {
        baseRevision == 0L && baseNoteUpdatedAt == null
      } else {
        targetNoteId > 0L &&
          baseRevision > 0L &&
          baseNoteUpdatedAt != null &&
          baseNoteUpdatedAt > 0L
      }

  fun hasConflictWith(note: Note): Boolean {
    if (targetNoteId == null) return false
    if (targetNoteId != note.id) return true
    if (note.isDeleted) return true
    if (!hasValidBaseReference) return true
    if (note.requiresContentHandling || requiresContentHandling) return true
    return note.revision != baseRevision || note.updatedAt != baseNoteUpdatedAt
  }
}

data class NoteRecoveryDraftInput(
  val draftId: String,
  val targetNoteId: Long?,
  val sessionId: String,
  val folderId: Long?,
  val initialFolderId: Long? = null,
  val fallbackSourceFolderId: Long?,
  val isPinned: Boolean,
  val title: String,
  val content: NoteContentDocument,
  val paperStyle: NotePaperStyle,
  val initialPaperStyle: NotePaperStyle? = null,
  val baseRevision: Long,
  val checkpointRevision: Long,
  val baseNoteUpdatedAt: Long?,
)

sealed interface SaveCheckpointResult {
  data class Saved(val draft: NoteRecoveryDraft) : SaveCheckpointResult

  data class IgnoredStaleRevision(
    val draftId: String,
    val persistedCheckpointRevision: Long,
    val attemptedCheckpointRevision: Long,
  ) : SaveCheckpointResult
}

sealed interface ReplaceCheckpointWithNewNoteResult {
  data class Replaced(
    val draft: NoteRecoveryDraft
  ) : ReplaceCheckpointWithNewNoteResult

  data class AlreadyReplaced(
    val draft: NoteRecoveryDraft
  ) : ReplaceCheckpointWithNewNoteResult
}
