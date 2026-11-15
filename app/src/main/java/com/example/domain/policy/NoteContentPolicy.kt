package com.example.domain.policy

import com.example.domain.model.BulletListItemBlock
import com.example.domain.model.ChecklistItemBlock
import com.example.domain.model.ContentBlock
import com.example.domain.model.FormatSpan
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NumberedListItemBlock
import com.example.domain.model.ParagraphBlock

/**
 * Regras puras para manipulação e normalização de conteúdo estruturado:
 * - Extração de prévia textual simples;
 * - Sanitização de spans de formatação sem descartar texto válido;
 * - Movimentação automática de itens concluídos em cada sequência contígua de checklist.
 */
object NoteContentPolicy {

  fun extractPlainTextPreview(document: NoteContentDocument): String =
    document.blocks
      .mapNotNull { block -> block.text.trim().takeIf { it.isNotEmpty() } }
      .joinToString(separator = "\n")

  fun sanitizeSpans(text: String, spans: List<FormatSpan>): List<FormatSpan> {
    if (text.isEmpty() || spans.isEmpty()) return emptyList()
    val maxLen = text.length
    return spans.mapNotNull { span ->
      val start = span.start.coerceIn(0, maxLen)
      val end = span.end.coerceIn(0, maxLen)
      if (start < end) span.copy(start = start, end = end) else null
    }
  }

  fun sanitizeBlockSpans(block: ContentBlock): ContentBlock {
    val sanitized = sanitizeSpans(block.text, block.spans)
    return when (block) {
      is ParagraphBlock -> block.copy(spans = sanitized)
      is BulletListItemBlock -> block.copy(spans = sanitized)
      is NumberedListItemBlock -> block.copy(spans = sanitized)
      is ChecklistItemBlock -> block.copy(spans = sanitized)
    }
  }

  /**
   * Normaliza o documento antes da persistência ou exibição:
   * 1) Sanitiza todos os [FormatSpan] preservando integralmente o texto dos blocos;
   * 2) Garante a ordenação pendentes-primeiro / concluídos-depois nas sequências de checklist.
   */
  fun normalizeDocument(document: NoteContentDocument): NoteContentDocument {
    val sanitizedBlocks = document.blocks.map(::sanitizeBlockSpans)
    return normalizeAllChecklistSequences(document.copy(blocks = sanitizedBlocks))
  }

  /**
   * Alterna o estado de conclusão do item [blockId] dentro da sua sequência contígua de checklist:
   * - Itens pendentes ficam primeiro; itens concluídos ficam depois.
   * - Ao marcar como concluído, move o item para o final dos itens concluídos da sequência.
   * - Ao desmarcar, move o item para o final dos itens pendentes da sequência.
   * - Todos os demais blocos fora dessa sequência contígua permanecem inalterados.
   */
  fun toggleChecklistItem(
    document: NoteContentDocument,
    blockId: String,
  ): NoteContentDocument {
    val blocks = document.blocks
    val targetIndex = blocks.indexOfFirst { it.id == blockId && it is ChecklistItemBlock }
    if (targetIndex == -1) return document

    var seqStart = targetIndex
    while (seqStart > 0 && blocks[seqStart - 1] is ChecklistItemBlock) {
      seqStart--
    }
    var seqEnd = targetIndex
    while (seqEnd < blocks.lastIndex && blocks[seqEnd + 1] is ChecklistItemBlock) {
      seqEnd++
    }

    val targetItem = blocks[targetIndex] as ChecklistItemBlock
    val toggledItem = targetItem.copy(isChecked = !targetItem.isChecked)

    val pending = mutableListOf<ChecklistItemBlock>()
    val completed = mutableListOf<ChecklistItemBlock>()

    for (i in seqStart..seqEnd) {
      if (i == targetIndex) continue
      val item = blocks[i] as ChecklistItemBlock
      if (item.isChecked) {
        completed.add(item)
      } else {
        pending.add(item)
      }
    }

    if (toggledItem.isChecked) {
      completed.add(toggledItem)
    } else {
      pending.add(toggledItem)
    }

    val updatedBlocks = buildList<ContentBlock>(blocks.size) {
      addAll(blocks.subList(0, seqStart))
      addAll(pending)
      addAll(completed)
      if (seqEnd + 1 < blocks.size) {
        addAll(blocks.subList(seqEnd + 1, blocks.size))
      }
    }

    return document.copy(blocks = updatedBlocks)
  }

  /**
   * Garante que todas as sequências contíguas de checklist mantenham pendentes antes
   * dos concluídos, preservando a ordem relativa dentro de cada grupo.
   */
  fun normalizeAllChecklistSequences(document: NoteContentDocument): NoteContentDocument {
    val blocks = document.blocks
    if (blocks.isEmpty()) return document

    val result = mutableListOf<ContentBlock>()
    var index = 0
    while (index < blocks.size) {
      val current = blocks[index]
      if (current !is ChecklistItemBlock) {
        result.add(current)
        index++
      } else {
        val pending = mutableListOf<ChecklistItemBlock>()
        val completed = mutableListOf<ChecklistItemBlock>()
        while (index < blocks.size && blocks[index] is ChecklistItemBlock) {
          val item = blocks[index] as ChecklistItemBlock
          if (item.isChecked) completed.add(item) else pending.add(item)
          index++
        }
        result.addAll(pending)
        result.addAll(completed)
      }
    }
    return document.copy(blocks = result)
  }
}
