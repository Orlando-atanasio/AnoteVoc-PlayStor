package com.example.data.local.mapper

import com.example.core.serialization.AnoteVoceJson
import com.example.data.local.entity.AppPreferencesEntity
import com.example.data.local.entity.FolderEntity
import com.example.data.local.entity.NoteEntity
import com.example.data.local.entity.NoteRecoveryDraftEntity
import com.example.domain.model.AppPreferences
import com.example.domain.model.AppThemeMode
import com.example.domain.model.Folder
import com.example.domain.model.HomeViewMode
import com.example.domain.model.Note
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NoteContentState
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.NoteRecoveryDraft
import com.example.domain.policy.NoteContentPolicy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

object NoteContentCodec {
  private val KNOWN_BLOCK_TYPES = setOf(
    "paragraph",
    "bullet_list_item",
    "numbered_list_item",
    "checklist_item",
  )

  fun encode(document: NoteContentDocument): String =
    AnoteVoceJson.instance.encodeToString(NoteContentDocument.serializer(), document)

  /**
   * Decodifica [rawJson] de forma defensiva:
   * - Nunca lança exceção capaz de derrubar o Flow da listagem;
   * - Nunca substitui conteúdo inválido por uma nota vazia;
   * - Distingue JSON corrompido, versão futura e tipo de bloco desconhecido,
   *   preservando [rawJson] intacto em todos os casos de falha.
   */
  fun decode(rawJson: String): NoteContentState {
    if (rawJson.isBlank()) {
      return NoteContentState.RequiresHandling.CorruptedJson(
        rawJson = rawJson,
        reason = "Conteúdo JSON vazio ou ausente.",
      )
    }

    val rootElement =
      try {
        AnoteVoceJson.instance.parseToJsonElement(rawJson)
      } catch (e: Exception) {
        return NoteContentState.RequiresHandling.CorruptedJson(
          rawJson = rawJson,
          reason = e.message,
        )
      }

    val rootObject =
      rootElement as? JsonObject
        ?: return NoteContentState.RequiresHandling.CorruptedJson(
          rawJson = rawJson,
          reason = "Raiz do documento JSON não é um objeto.",
        )

    val schemaVersion =
      (rootObject["schemaVersion"] as? JsonPrimitive)?.intOrNull
        ?: return NoteContentState.RequiresHandling.CorruptedJson(
          rawJson = rawJson,
          reason = "Campo 'schemaVersion' ausente ou inválido.",
        )

    if (schemaVersion <= 0) {
      return NoteContentState.RequiresHandling.CorruptedJson(
        rawJson = rawJson,
        reason = "Campo 'schemaVersion' inválido: $schemaVersion.",
      )
    }

    if (schemaVersion > NoteContentDocument.CURRENT_SCHEMA_VERSION) {
      return NoteContentState.RequiresHandling.UnsupportedFutureVersion(
        rawJson = rawJson,
        detectedSchemaVersion = schemaVersion,
      )
    }

    val blocksArray =
      rootObject["blocks"] as? JsonArray
        ?: return NoteContentState.RequiresHandling.CorruptedJson(
          rawJson = rawJson,
          reason = "Campo 'blocks' ausente ou não é uma lista.",
        )

    val unknownTypes = linkedSetOf<String>()
    for (element in blocksArray) {
      val blockObj =
        element as? JsonObject
          ?: return NoteContentState.RequiresHandling.CorruptedJson(
            rawJson = rawJson,
            reason = "Elemento da lista 'blocks' não é um objeto JSON.",
          )
      val typePrimitive = blockObj["type"] as? JsonPrimitive
      val blockType =
        if (typePrimitive != null && typePrimitive.isString) typePrimitive.content else null
      if (blockType == null) {
        return NoteContentState.RequiresHandling.CorruptedJson(
          rawJson = rawJson,
          reason = "Bloco sem discriminador 'type' válido.",
        )
      }
      if (blockType !in KNOWN_BLOCK_TYPES) {
        unknownTypes.add(blockType)
      }
    }

    if (unknownTypes.isNotEmpty()) {
      return NoteContentState.RequiresHandling.UnknownBlockType(
        rawJson = rawJson,
        schemaVersion = schemaVersion,
        unknownTypes = unknownTypes.toList(),
      )
    }

    val decoded =
      try {
        AnoteVoceJson.instance.decodeFromJsonElement(NoteContentDocument.serializer(), rootElement)
      } catch (e: Exception) {
        return NoteContentState.RequiresHandling.CorruptedJson(
          rawJson = rawJson,
          reason = e.message,
        )
      }

    // Sanitiza spans em memória para proteção da interface sem alterar o registro persistido.
    val safeDocument = decoded.copy(blocks = decoded.blocks.map(NoteContentPolicy::sanitizeBlockSpans))
    return NoteContentState.Valid(safeDocument)
  }
}

fun FolderEntity.toDomain(): Folder =
  Folder(
    id = id,
    uid = uid,
    name = name,
    normalizedName = normalizedName,
    isDeleted = isDeleted,
    deletedAt = deletedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
  )

fun NoteEntity.toDomain(): Note =
  Note(
    id = id,
    uid = uid,
    folderId = folderId,
    title = title,
    contentState = NoteContentCodec.decode(contentJson),
    plainTextPreview = plainTextPreview,
    paperStyle = enumValueOrDefault(paperStyle, NotePaperStyle.LINHAS),
    revision = revision,
    isPinned = isPinned,
    pinnedAt = pinnedAt,
    isDeleted = isDeleted,
    deletedAt = deletedAt,
    deletedWithFolder = deletedWithFolder,
    createdAt = createdAt,
    updatedAt = updatedAt,
  )

fun NoteRecoveryDraftEntity.toDomain(): NoteRecoveryDraft =
  NoteRecoveryDraft(
    draftId = draftId,
    targetNoteId = targetNoteId,
    sessionId = sessionId,
    folderId = folderId,
    initialFolderId = initialFolderId,
    isPinned = isPinned,
    title = title,
    contentState = NoteContentCodec.decode(contentJson),
    paperStyle = enumValueOrDefault(paperStyle, NotePaperStyle.LINHAS),
    initialPaperStyle = enumValueOrNull<NotePaperStyle>(initialPaperStyle),
    baseRevision = baseRevision,
    checkpointRevision = checkpointRevision,
    baseNoteUpdatedAt = baseNoteUpdatedAt,
    checkpointAt = checkpointAt,
  )

fun AppPreferencesEntity.toDomain(): AppPreferences =
  AppPreferences(
    themeMode = enumValueOrDefault(themeMode, AppThemeMode.MODO_DIA),
    defaultNotePaper = enumValueOrDefault(defaultNotePaper, NotePaperStyle.LINHAS),
    homeViewMode = enumValueOrDefault(homeViewMode, HomeViewMode.LISTA),
    updatedAt = updatedAt,
  )

fun AppPreferences.toEntity(): AppPreferencesEntity =
  AppPreferencesEntity(
    id = AppPreferencesEntity.SINGLETON_ID,
    themeMode = themeMode.name,
    defaultNotePaper = defaultNotePaper.name,
    homeViewMode = homeViewMode.name,
    updatedAt = updatedAt,
  )

internal inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String, fallback: T): T =
  enumValues<T>().firstOrNull { it.name == raw } ?: fallback

internal inline fun <reified T : Enum<T>> enumValueOrNull(raw: String?): T? =
  raw?.let { value -> enumValues<T>().firstOrNull { it.name == value } }
