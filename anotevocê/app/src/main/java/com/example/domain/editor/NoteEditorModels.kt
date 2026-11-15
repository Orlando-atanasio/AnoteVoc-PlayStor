package com.example.domain.editor

import com.example.domain.model.ContentBlock
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.TextFormatStyle

/**
 * Seleção restrita a um único bloco (limitação aprovada para a versão 1.0).
 * Quando [start] == [end], representa um cursor colapsado.
 */
data class BlockSelection(
  val blockId: String,
  val start: Int,
  val end: Int = start,
) {
  init {
    require(blockId.isNotBlank()) { "blockId não pode ficar em branco." }
    require(start >= 0) { "Posição inicial da seleção não pode ser negativa: $start" }
    require(end >= start) {
      "Posição final ($end) não pode ser menor que a inicial ($start)."
    }
  }

  val isCollapsed: Boolean
    get() = start == end
}

/**
 * Snapshot estruturado usado na pilha de Undo/Redo.
 * Não inclui o papel visual da nota, conforme especificado para a Subetapa 4.2.
 */
internal data class EditorSnapshot(
  val document: NoteContentDocument,
  val selection: BlockSelection?,
)

/**
 * Estado imutável mantido e retornado pelo [NoteEditorEngine].
 */
data class NoteEditorState(
  val document: NoteContentDocument,
  val selection: BlockSelection?,
  val activeTypingStyles: Set<TextFormatStyle> = emptySet(),
  val canUndo: Boolean = false,
  val canRedo: Boolean = false,
  val numberedListNumbers: Map<String, Int> = emptyMap(),
) {
  val focusedBlock: ContentBlock?
    get() = selection?.let { sel -> document.blocks.firstOrNull { it.id == sel.blockId } }

  fun numberForBlock(blockId: String): Int? = numberedListNumbers[blockId]
}
