package com.example.domain.repository

import com.example.domain.model.AppPreferences
import com.example.domain.model.AppThemeMode
import com.example.domain.model.Folder
import com.example.domain.model.HomeViewMode
import com.example.domain.model.Note
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NoteContentState
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.NoteRecoveryDraft
import com.example.domain.model.NoteRecoveryDraftInput
import com.example.domain.model.SaveCheckpointResult
import kotlinx.coroutines.flow.Flow

class DuplicateFolderNameException(val folderName: String) :
  IllegalStateException("Já existe uma pasta ativa com o nome \"$folderName\".")

class InvalidFolderNameException :
  IllegalArgumentException("O nome da pasta não pode ficar em branco.")

class FolderNotFoundException(val folderId: Long) :
  NoSuchElementException("Pasta não encontrada: $folderId")

class FolderDeletedException(val folderId: Long) :
  IllegalStateException("A pasta $folderId está em Apagados e precisa ser restaurada antes desta operação.")

class FolderNotDeletedException(val folderId: Long) :
  IllegalStateException("A pasta $folderId está ativa; apenas pastas em Apagados podem ser excluídas permanentemente.")

class NoteNotFoundException(val noteId: Long) :
  NoSuchElementException("Nota não encontrada: $noteId")

class NoteDeletedException(val noteId: Long) :
  IllegalStateException("A nota $noteId está em Apagados e precisa ser restaurada antes desta operação.")

class NoteNotDeletedException(val noteId: Long) :
  IllegalStateException("A nota $noteId está ativa; apenas notas em Apagados podem ser excluídas permanentemente.")

class UnreadableNoteContentException(
  val noteId: Long,
  val contentState: NoteContentState.RequiresHandling,
) :
  IllegalStateException(
    "A nota $noteId possui conteúdo que não pode ser interpretado com segurança pela versão atual."
  )

class NoteConflictException(
  val noteId: Long,
  val expectedRevision: Long,
  val actualRevision: Long,
) :
  IllegalStateException(
    "Conflito de revisão na nota $noteId: esperada $expectedRevision, atual $actualRevision."
  )

class InvalidRecoveryDraftException(message: String) : IllegalArgumentException(message)

enum class FolderDeleteMode {
  FOLDER_ONLY,
  FOLDER_AND_CONTENT,
}

sealed interface RestoreFolderResult {
  data class RestoredOriginal(val folderId: Long) : RestoreFolderResult
  data class MergedIntoActive(val targetFolderId: Long, val removedFolderId: Long) :
    RestoreFolderResult
}

interface FolderRepository {
  fun observeActiveFolders(): Flow<List<Folder>>
  fun observeDeletedFolders(): Flow<List<Folder>>
  suspend fun getFolderById(folderId: Long): Folder?
  suspend fun createFolder(name: String): Folder
  suspend fun renameFolder(folderId: Long, newName: String): Folder
  suspend fun deleteFolder(folderId: Long, mode: FolderDeleteMode)
  suspend fun restoreFolder(folderId: Long): RestoreFolderResult
  suspend fun permanentlyDeleteFolder(folderId: Long, deleteLinkedNotes: Boolean)
}

interface NoteRepository {
  fun observeAllActiveNotes(): Flow<List<Note>>
  fun observePinnedActiveNotes(): Flow<List<Note>>
  fun observeActiveNotesByFolder(folderId: Long): Flow<List<Note>>
  fun observeDeletedNotes(): Flow<List<Note>>
  fun observeNoteById(noteId: Long): Flow<Note?>
  suspend fun getNoteById(noteId: Long): Note?
  suspend fun createNote(
    title: String,
    content: NoteContentDocument = NoteContentDocument.empty(),
    folderId: Long? = null,
    isPinned: Boolean = false,
    paperStyle: NotePaperStyle? = null,
    clearDraftId: String? = null,
  ): Note
  suspend fun updateNote(
    noteId: Long,
    title: String,
    content: NoteContentDocument,
    folderId: Long?,
    paperStyle: NotePaperStyle? = null,
    expectedBaseRevision: Long? = null,
    clearDraftId: String? = null,
  ): Note
  suspend fun updateNotePaperStyle(noteId: Long, paperStyle: NotePaperStyle): Note
  suspend fun setNotePinned(noteId: Long, pinned: Boolean)
  suspend fun moveNoteToFolder(noteId: Long, folderId: Long?)
  suspend fun softDeleteNote(noteId: Long)
  suspend fun restoreNote(noteId: Long): Note
  suspend fun permanentlyDeleteNote(noteId: Long)
}

interface NoteRecoveryDraftRepository {
  fun observeAllCheckpoints(): Flow<List<NoteRecoveryDraft>>
  suspend fun getAllCheckpoints(): List<NoteRecoveryDraft>
  suspend fun getCheckpointById(draftId: String): NoteRecoveryDraft?
  suspend fun getCheckpointsForNote(noteId: Long): List<NoteRecoveryDraft>
  suspend fun saveCheckpoint(input: NoteRecoveryDraftInput): SaveCheckpointResult
  suspend fun deleteCheckpoint(draftId: String)
  suspend fun deleteCheckpointsForNote(noteId: Long)
}

interface PreferencesRepository {
  fun observePreferences(): Flow<AppPreferences>
  suspend fun getPreferences(): AppPreferences
  suspend fun updateThemeMode(themeMode: AppThemeMode)
  suspend fun updateDefaultNotePaper(paperStyle: NotePaperStyle)
  suspend fun updateHomeViewMode(homeViewMode: HomeViewMode)
}
