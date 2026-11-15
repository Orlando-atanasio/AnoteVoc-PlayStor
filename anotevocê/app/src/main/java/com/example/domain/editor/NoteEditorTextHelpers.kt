package com.example.domain.editor

import com.example.domain.model.BulletListItemBlock
import com.example.domain.model.ChecklistItemBlock
import com.example.domain.model.ContentBlock
import com.example.domain.model.FormatSpan
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NumberedListItemBlock
import com.example.domain.model.ParagraphBlock
import com.example.domain.model.TextFormatStyle
import com.example.domain.policy.NoteContentPolicy

/**
 * Intervalo semiaberto `[start, endExclusive)` sem ambiguidade para edições em texto UTF-16.
 */
internal data class TextRangeBounds(
  val start: Int,
  val endExclusive: Int,
) {
  init {
    require(start >= 0) { "start não pode ser negativo: $start" }
    require(endExclusive >= start) {
      "endExclusive ($endExclusive) não pode ser menor que start ($start)."
    }
  }

  val isEmpty: Boolean
    get() = start >= endExclusive
}

/**
 * Funções puras de apoio estrutural para seleção em bloco único,
 * cálculo de numeração de listas e manipulação segura de [FormatSpan].
 */
internal object NoteEditorTextHelpers {

  fun isInsideSurrogatePair(text: String, index: Int): Boolean =
    index in 1 until text.length &&
      Character.isHighSurrogate(text[index - 1]) &&
      Character.isLowSurrogate(text[index])

  fun computeNumberedListNumbers(document: NoteContentDocument): Map<String, Int> {
    val blocks = document.blocks
    if (blocks.isEmpty()) return emptyMap()

    val numbers = LinkedHashMap<String, Int>()
    val countersByIndent = mutableMapOf<Int, Int>()

    for (block in blocks) {
      if (block !is NumberedListItemBlock) {
        countersByIndent.clear()
        continue
      }

      val indent = block.indentLevel.coerceAtLeast(0)
      val keysToReset = countersByIndent.keys.filter { it > indent }
      for (key in keysToReset) {
        countersByIndent.remove(key)
      }
      val nextNumber = (countersByIndent[indent] ?: 0) + 1
      countersByIndent[indent] = nextNumber
      numbers[block.id] = nextNumber
    }

    return numbers
  }

  fun resolveActiveSelection(
    document: NoteContentDocument,
    currentSelection: BlockSelection?,
  ): BlockSelection? {
    if (document.blocks.isEmpty()) return null
    return sanitizeSelection(document, currentSelection)
      ?: BlockSelection(document.blocks.first().id, 0, 0)
  }

  fun sanitizeSelection(
    document: NoteContentDocument,
    selection: BlockSelection?,
  ): BlockSelection? {
    if (document.blocks.isEmpty() || selection == null) return null
    val block = document.blocks.firstOrNull { it.id == selection.blockId } ?: return null
    return normalizeSelection(block, selection.start, selection.end)
  }

  fun normalizeSelection(
    block: ContentBlock,
    start: Int,
    end: Int,
  ): BlockSelection {
    val text = block.text
    val maxLen = text.length
    var safeStart = minOf(start, end).coerceIn(0, maxLen)
    var safeEnd = maxOf(start, end).coerceIn(0, maxLen)
    if (safeStart == safeEnd) {
      if (isInsideSurrogatePair(text, safeStart)) {
        safeStart += 1
        safeEnd = safeStart
      }
    } else {
      if (isInsideSurrogatePair(text, safeStart)) {
        safeStart -= 1
      }
      if (isInsideSurrogatePair(text, safeEnd)) {
        safeEnd += 1
      }
    }
    return BlockSelection(blockId = block.id, start = safeStart, end = safeEnd)
  }

  fun computeActiveStyles(
    block: ContentBlock,
    selection: BlockSelection,
  ): Set<TextFormatStyle> {
    if (block.text.isEmpty() || block.spans.isEmpty()) return emptySet()
    if (selection.isCollapsed) {
      val cursor = selection.start
      if (cursor <= 0) return emptySet()
      val refPos = (cursor - 1).coerceIn(0, block.text.lastIndex)
      return block.spans
        .asSequence()
        .filter { span -> refPos in span.start until span.end }
        .map { it.style }
        .toSet()
    }

    return TextFormatStyle.entries
      .asSequence()
      .filter { style ->
        isRangeFullyCoveredByStyle(block.spans, selection.start, selection.end, style)
      }
      .toSet()
  }

  fun computeActiveStylesForSelection(
    document: NoteContentDocument,
    selection: BlockSelection?,
  ): Set<TextFormatStyle> {
    val sel = selection ?: return emptySet()
    val block = document.blocks.firstOrNull { it.id == sel.blockId } ?: return emptySet()
    return computeActiveStyles(block, sel)
  }

  fun isRangeFullyCoveredByStyle(
    spans: List<FormatSpan>,
    start: Int,
    end: Int,
    style: TextFormatStyle,
  ): Boolean {
    if (start >= end) return false
    val styleSpans =
      spans
        .asSequence()
        .filter { it.style == style && it.end > start && it.start < end }
        .sortedWith(compareBy<FormatSpan> { it.start }.thenBy { it.end })
        .toList()

    if (styleSpans.isEmpty()) return false
    var coveredUntil = start
    for (span in styleSpans) {
      if (span.start > coveredUntil) return false
      if (span.end > coveredUntil) {
        coveredUntil = span.end
      }
      if (coveredUntil >= end) return true
    }
    return coveredUntil >= end
  }

  fun shiftSpansForEdit(
    spans: List<FormatSpan>,
    newText: String,
    delStart: Int,
    delEnd: Int,
    insertedLength: Int,
    typingStyles: Set<TextFormatStyle>,
  ): List<FormatSpan> {
    val removedLength = delEnd - delStart
    val delta = insertedLength - removedLength
    val isPureInsertion = removedLength == 0
    val result = mutableListOf<FormatSpan>()

    for (span in spans) {
      when {
        span.end <= delStart -> {
          result.add(span)
        }
        span.start >= delEnd -> {
          result.add(span.copy(start = span.start + delta, end = span.end + delta))
        }
        span.start < delStart && span.end > delEnd -> {
          if (isPureInsertion && insertedLength > 0 && span.style !in typingStyles) {
            result.add(span.copy(end = delStart))
            result.add(span.copy(start = delStart + insertedLength, end = span.end + delta))
          } else {
            result.add(span.copy(end = span.end + delta))
          }
        }
        else -> {
          if (span.start < delStart) {
            result.add(span.copy(end = delStart))
          }
          if (span.end > delEnd) {
            result.add(span.copy(start = delStart + insertedLength, end = span.end + delta))
          }
        }
      }
    }

    if (insertedLength > 0 && typingStyles.isNotEmpty()) {
      for (style in typingStyles) {
        result.add(
          FormatSpan(
            start = delStart,
            end = delStart + insertedLength,
            style = style,
          )
        )
      }
    }

    return normalizeAndCoalesceSpans(newText, result)
  }

  fun sliceSpans(
    spans: List<FormatSpan>,
    rangeStart: Int,
    rangeEnd: Int,
    shift: Int,
    text: String,
  ): List<FormatSpan> {
    if (text.isEmpty() || rangeStart >= rangeEnd || spans.isEmpty()) return emptyList()
    val sliced =
      spans.mapNotNull { span ->
        val s = maxOf(span.start, rangeStart) + shift
        val e = minOf(span.end, rangeEnd) + shift
        if (s < e) span.copy(start = s, end = e) else null
      }
    return normalizeAndCoalesceSpans(text, sliced)
  }

  fun normalizeAndCoalesceSpans(
    text: String,
    spans: List<FormatSpan>,
  ): List<FormatSpan> {
    val sanitized =
      NoteContentPolicy.sanitizeSpans(text, spans).mapNotNull { span ->
        val alignedStart =
          if (isInsideSurrogatePair(text, span.start)) span.start - 1 else span.start
        val alignedEnd =
          if (isInsideSurrogatePair(text, span.end)) span.end + 1 else span.end
        if (alignedStart < alignedEnd) {
          span.copy(start = alignedStart, end = alignedEnd)
        } else {
          null
        }
      }
    if (sanitized.isEmpty()) return emptyList()

    val merged = mutableListOf<FormatSpan>()
    val byStyle = sanitized.groupBy { it.style }
    for (style in TextFormatStyle.entries) {
      val list = byStyle[style]?.sortedWith(compareBy<FormatSpan> { it.start }.thenBy { it.end })
      if (list.isNullOrEmpty()) continue

      var current = list.first()
      for (i in 1 until list.size) {
        val next = list[i]
        if (next.start <= current.end) {
          current = current.copy(end = maxOf(current.end, next.end))
        } else {
          merged.add(current)
          current = next
        }
      }
      merged.add(current)
    }

    return merged.sortedWith(
      compareBy<FormatSpan> { it.start }.thenBy { it.end }.thenBy { it.style.ordinal }
    )
  }

  fun updateBlockTextAndSpans(
    block: ContentBlock,
    text: String,
    spans: List<FormatSpan>,
  ): ContentBlock =
    when (block) {
      is ParagraphBlock -> block.copy(text = text, spans = spans)
      is BulletListItemBlock -> block.copy(text = text, spans = spans)
      is NumberedListItemBlock -> block.copy(text = text, spans = spans)
      is ChecklistItemBlock -> block.copy(text = text, spans = spans)
    }

  fun blockIndentLevel(block: ContentBlock): Int =
    when (block) {
      is ParagraphBlock -> 0
      is BulletListItemBlock -> block.indentLevel
      is NumberedListItemBlock -> block.indentLevel
      is ChecklistItemBlock -> block.indentLevel
    }

  fun computeMinimalDiffBounds(oldText: String, newText: String): Pair<Int, Int> {
    val minLen = minOf(oldText.length, newText.length)
    var prefix = 0
    while (prefix < minLen && oldText[prefix] == newText[prefix]) {
      prefix++
    }

    var suffix = 0
    while (
      suffix < (minLen - prefix) &&
        oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
    ) {
      suffix++
    }

    while (
      prefix > 0 &&
        (isInsideSurrogatePair(oldText, prefix) || isInsideSurrogatePair(newText, prefix))
    ) {
      prefix--
    }

    while (
      suffix > 0 &&
        (isInsideSurrogatePair(oldText, oldText.length - suffix) ||
          isInsideSurrogatePair(newText, newText.length - suffix))
    ) {
      suffix--
    }

    return prefix to suffix
  }

  fun previousCodePointStart(text: String, cursor: Int): Int {
    val safeCursor = cursor.coerceIn(0, text.length)
    if (safeCursor == 0) return 0
    if (isInsideSurrogatePair(text, safeCursor)) {
      return safeCursor - 1
    }
    val prev = text[safeCursor - 1]
    return if (
      safeCursor >= 2 &&
        Character.isLowSurrogate(prev) &&
        Character.isHighSurrogate(text[safeCursor - 2])
    ) {
      safeCursor - 2
    } else {
      safeCursor - 1
    }
  }

  fun backspaceDeleteRange(text: String, cursor: Int): TextRangeBounds {
    val safeCursor = cursor.coerceIn(0, text.length)
    if (safeCursor == 0) return TextRangeBounds(start = 0, endExclusive = 0)
    val delEnd = if (isInsideSurrogatePair(text, safeCursor)) safeCursor + 1 else safeCursor
    val delStart = previousCodePointStart(text, delEnd)
    return TextRangeBounds(start = delStart, endExclusive = delEnd)
  }
}
