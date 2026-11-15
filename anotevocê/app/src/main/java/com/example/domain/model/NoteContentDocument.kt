package com.example.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NoteContentDocument(
  val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
  val blocks: List<ContentBlock> = emptyList(),
) {
  companion object {
    const val CURRENT_SCHEMA_VERSION: Int = 1

    fun empty(): NoteContentDocument = NoteContentDocument(schemaVersion = CURRENT_SCHEMA_VERSION)
  }
}

@Serializable
sealed interface ContentBlock {
  val id: String
  val text: String
  val spans: List<FormatSpan>
}

@Serializable
@SerialName("paragraph")
data class ParagraphBlock(
  override val id: String,
  override val text: String = "",
  override val spans: List<FormatSpan> = emptyList(),
) : ContentBlock

@Serializable
@SerialName("bullet_list_item")
data class BulletListItemBlock(
  override val id: String,
  override val text: String = "",
  override val spans: List<FormatSpan> = emptyList(),
  val indentLevel: Int = 0,
) : ContentBlock

@Serializable
@SerialName("numbered_list_item")
data class NumberedListItemBlock(
  override val id: String,
  override val text: String = "",
  override val spans: List<FormatSpan> = emptyList(),
  val indentLevel: Int = 0,
) : ContentBlock

@Serializable
@SerialName("checklist_item")
data class ChecklistItemBlock(
  override val id: String,
  override val text: String = "",
  override val spans: List<FormatSpan> = emptyList(),
  val isChecked: Boolean = false,
  val indentLevel: Int = 0,
) : ContentBlock

@Serializable
data class FormatSpan(
  val start: Int,
  val end: Int,
  val style: TextFormatStyle,
)

@Serializable
enum class TextFormatStyle {
  @SerialName("bold") BOLD,
  @SerialName("italic") ITALIC,
  @SerialName("underline") UNDERLINE,
  @SerialName("strikethrough") STRIKETHROUGH,
}
