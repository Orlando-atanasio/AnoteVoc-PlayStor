package com.example.domain.editor

import com.example.domain.editor.NoteEditorTextHelpers.backspaceDeleteRange
import com.example.domain.editor.NoteEditorTextHelpers.blockIndentLevel
import com.example.domain.editor.NoteEditorTextHelpers.computeActiveStyles
import com.example.domain.editor.NoteEditorTextHelpers.computeActiveStylesForSelection
import com.example.domain.editor.NoteEditorTextHelpers.computeMinimalDiffBounds
import com.example.domain.editor.NoteEditorTextHelpers.isRangeFullyCoveredByStyle
import com.example.domain.editor.NoteEditorTextHelpers.normalizeAndCoalesceSpans
import com.example.domain.editor.NoteEditorTextHelpers.normalizeSelection
import com.example.domain.editor.NoteEditorTextHelpers.resolveActiveSelection
import com.example.domain.editor.NoteEditorTextHelpers.sanitizeSelection
import com.example.domain.editor.NoteEditorTextHelpers.shiftSpansForEdit
import com.example.domain.editor.NoteEditorTextHelpers.sliceSpans
import com.example.domain.editor.NoteEditorTextHelpers.updateBlockTextAndSpans
import com.example.domain.model.BulletListItemBlock
import com.example.domain.model.ChecklistItemBlock
import com.example.domain.model.ContentBlock
import com.example.domain.model.FormatSpan
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NumberedListItemBlock
import com.example.domain.model.ParagraphBlock
import com.example.domain.model.TextFormatStyle
import com.example.domain.policy.NoteContentPolicy
import java.util.UUID

/**
 * Motor puro de edição estruturada do AnoteVocê 1.0.
 *
 * Opera exclusivamente sobre modelos de domínio ([NoteContentDocument], [ContentBlock],
 * [FormatSpan]) sem qualquer dependência da UI Android, Compose ou persistência.
 */
class NoteEditorEngine(
  initialDocument: NoteContentDocument = NoteContentDocument.empty(),
  initialSelection: BlockSelection? = null,
  private val maxHistorySize: Int = DEFAULT_MAX_HISTORY_SIZE,
  private val blockIdGenerator: () -> String = { UUID.randomUUID().toString() },
) {
  init {
    require(maxHistorySize > 0) { "maxHistorySize deve ser maior que zero." }
  }

  private val undoStack = ArrayDeque<EditorSnapshot>()
  private val redoStack = ArrayDeque<EditorSnapshot>()

  var state: NoteEditorState = buildInitialState(initialDocument, initialSelection)
    private set

  fun reset(
    document: NoteContentDocument,
    selection: BlockSelection? = null,
  ): NoteEditorState {
    undoStack.clear()
    redoStack.clear()
    state = buildInitialState(document, selection)
    return state
  }

  fun selectBlock(
    blockId: String,
    start: Int,
    end: Int = start,
  ): NoteEditorState {
    val block = state.document.blocks.firstOrNull { it.id == blockId } ?: return state
    val normalized = normalizeSelection(block, start, end)
    val typingStyles = computeActiveStyles(block, normalized)
    if (state.selection == normalized && state.activeTypingStyles == typingStyles) {
      return state
    }
    state = state.copy(selection = normalized, activeTypingStyles = typingStyles)
    return state
  }

  fun insertText(text: String): NoteEditorState {
    if (text.isEmpty() && (state.selection == null || state.selection?.isCollapsed == true)) {
      return state
    }

    if (state.document.blocks.isEmpty()) {
      return insertTextIntoEmptyDocument(text)
    }

    val selection = resolveActiveSelection(state.document, state.selection) ?: return state
    if (!text.contains('\n')) {
      return replaceRangeInBlock(
        blockId = selection.blockId,
        delStart = selection.start,
        delEnd = selection.end,
        insertedText = text,
        typingStyles = state.activeTypingStyles,
        newSelectionStart = selection.start + text.length,
        newSelectionEnd = selection.start + text.length,
      )
    }

    return insertMultilineText(
      selection = selection,
      text = text,
      typingStyles = state.activeTypingStyles,
    )
  }

  fun applyTextChange(
    blockId: String,
    newText: String,
    selectionStart: Int,
    selectionEnd: Int = selectionStart,
  ): NoteEditorState {
    val block = state.document.blocks.firstOrNull { it.id == blockId } ?: return state
    val oldText = block.text

    if (oldText == newText) {
      return selectBlock(blockId, selectionStart, selectionEnd)
    }

    val (prefixLen, suffixLen) = computeMinimalDiffBounds(oldText, newText)
    val delStart = prefixLen
    val delEnd = oldText.length - suffixLen
    val inserted = newText.substring(prefixLen, newText.length - suffixLen)

    val matchesCurrentTypingSite =
      state.selection?.let { sel ->
        sel.blockId == blockId &&
          ((sel.isCollapsed && delStart == sel.start && delEnd == sel.start) ||
            (!sel.isCollapsed && delStart == sel.start && delEnd == sel.end))
      } == true

    val effectiveTypingStyles =
      if (matchesCurrentTypingSite) {
        state.activeTypingStyles
      } else {
        computeActiveStyles(block, BlockSelection(blockId, delStart, delEnd))
      }

    if (inserted.contains('\n')) {
      val afterRemoval =
        if (delStart < delEnd) {
          replaceBlockContent(
            document = state.document,
            blockId = blockId,
            delStart = delStart,
            delEnd = delEnd,
            insertedText = "",
            typingStyles = effectiveTypingStyles,
          )
        } else {
          state.document
        }

      if (inserted == "\n") {
        val splitDoc = splitOrExitAt(afterRemoval, blockId, delStart, delStart)
        return commitDocumentChange(
          newDocument = splitDoc.first,
          newSelection = splitDoc.second,
          activeTypingStyles = emptySet(),
        )
      }

      val seedSelection = BlockSelection(blockId = blockId, start = delStart, end = delStart)
      val multiResult =
        applyMultilineEdit(
          baseDocument = afterRemoval,
          selection = seedSelection,
          text = inserted,
          typingStyles = effectiveTypingStyles,
        )
      return commitDocumentChange(
        newDocument = multiResult.first,
        newSelection = multiResult.second,
        activeTypingStyles = emptySet(),
      )
    }

    val clampedSelStart = minOf(selectionStart, selectionEnd).coerceIn(0, newText.length)
    val clampedSelEnd = maxOf(selectionStart, selectionEnd).coerceIn(0, newText.length)

    return replaceRangeInBlock(
      blockId = blockId,
      delStart = delStart,
      delEnd = delEnd,
      insertedText = inserted,
      typingStyles = effectiveTypingStyles,
      newSelectionStart = clampedSelStart,
      newSelectionEnd = clampedSelEnd,
    )
  }

  fun pressEnter(): NoteEditorState {
    val currentDoc = state.document
    if (currentDoc.blocks.isEmpty()) {
      val newBlock = ParagraphBlock(id = generateUniqueBlockId(currentDoc))
      val newDoc = NoteContentDocument(blocks = listOf(newBlock))
      return commitDocumentChange(
        newDocument = newDoc,
        newSelection = BlockSelection(newBlock.id, 0, 0),
        activeTypingStyles = emptySet(),
      )
    }

    val selection = resolveActiveSelection(currentDoc, state.selection) ?: return state
    val (newDoc, newSelection) =
      splitOrExitAt(
        document = currentDoc,
        blockId = selection.blockId,
        selStart = selection.start,
        selEnd = selection.end,
      )

    return commitDocumentChange(
      newDocument = newDoc,
      newSelection = newSelection,
      activeTypingStyles = emptySet(),
    )
  }

  fun pressBackspace(): NoteEditorState {
    val currentDoc = state.document
    val blocks = currentDoc.blocks
    if (blocks.isEmpty()) return state

    val selection = resolveActiveSelection(currentDoc, state.selection) ?: return state
    val blockIndex = blocks.indexOfFirst { it.id == selection.blockId }
    if (blockIndex == -1) return state
    val block = blocks[blockIndex]

    // Caso 1: há seleção não colapsada dentro do bloco -> apaga apenas o intervalo selecionado.
    if (!selection.isCollapsed) {
      return replaceRangeInBlock(
        blockId = block.id,
        delStart = selection.start,
        delEnd = selection.end,
        insertedText = "",
        typingStyles = emptySet(),
        newSelectionStart = selection.start,
        newSelectionEnd = selection.start,
      )
    }

    val cursor = selection.start

    // Caso 2: cursor após o início do bloco -> remove o code point (ou par substituto) anterior.
    if (cursor > 0) {
      val deleteBounds = backspaceDeleteRange(block.text, cursor)
      if (deleteBounds.isEmpty) return state
      return replaceRangeInBlock(
        blockId = block.id,
        delStart = deleteBounds.start,
        delEnd = deleteBounds.endExclusive,
        insertedText = "",
        typingStyles = emptySet(),
        newSelectionStart = deleteBounds.start,
        newSelectionEnd = deleteBounds.start,
      )
    }

    // Caso 3: cursor no início (0) de um item de lista -> converte para parágrafo preservando texto/spans.
    if (block !is ParagraphBlock) {
      val converted = ParagraphBlock(id = block.id, text = block.text, spans = block.spans)
      val updatedBlocks = blocks.toMutableList().apply { set(blockIndex, converted) }
      val newDoc = NoteContentPolicy.normalizeDocument(currentDoc.copy(blocks = updatedBlocks))
      val newSel = BlockSelection(blockId = converted.id, start = 0, end = 0)
      return commitDocumentChange(
        newDocument = newDoc,
        newSelection = newSel,
        activeTypingStyles = computeActiveStyles(converted, newSel),
      )
    }

    // Caso 4: cursor no início do primeiro parágrafo -> nada a remover (não gera histórico).
    if (blockIndex == 0) {
      return state
    }

    // Caso 5: cursor no início de um parágrafo após outro bloco -> une com o bloco anterior.
    val previousBlock = blocks[blockIndex - 1]
    val mergeOffset = previousBlock.text.length
    val mergedText = previousBlock.text + block.text
    val shiftedCurrentSpans =
      block.spans.map { span ->
        span.copy(start = span.start + mergeOffset, end = span.end + mergeOffset)
      }
    val mergedSpans = normalizeAndCoalesceSpans(mergedText, previousBlock.spans + shiftedCurrentSpans)
    val mergedBlock = updateBlockTextAndSpans(previousBlock, mergedText, mergedSpans)

    val updatedBlocks = buildList(blocks.size - 1) {
      addAll(blocks.subList(0, blockIndex - 1))
      add(mergedBlock)
      if (blockIndex + 1 < blocks.size) {
        addAll(blocks.subList(blockIndex + 1, blocks.size))
      }
    }
    val newDoc = NoteContentPolicy.normalizeDocument(currentDoc.copy(blocks = updatedBlocks))
    val newSel = BlockSelection(blockId = mergedBlock.id, start = mergeOffset, end = mergeOffset)
    return commitDocumentChange(
      newDocument = newDoc,
      newSelection = newSel,
      activeTypingStyles = computeActiveStyles(mergedBlock, newSel),
    )
  }

  fun toggleBold(): NoteEditorState = toggleFormat(TextFormatStyle.BOLD)

  fun toggleItalic(): NoteEditorState = toggleFormat(TextFormatStyle.ITALIC)

  fun toggleUnderline(): NoteEditorState = toggleFormat(TextFormatStyle.UNDERLINE)

  fun toggleFormat(style: TextFormatStyle): NoteEditorState {
    val selection = resolveActiveSelection(state.document, state.selection)
    if (selection == null || selection.isCollapsed) {
      val updatedTypingStyles =
        if (style in state.activeTypingStyles) {
          state.activeTypingStyles - style
        } else {
          state.activeTypingStyles + style
        }
      if (updatedTypingStyles != state.activeTypingStyles) {
        state = state.copy(activeTypingStyles = updatedTypingStyles)
      }
      return state
    }

    val block = state.document.blocks.firstOrNull { it.id == selection.blockId } ?: return state
    val fullyCovered = isRangeFullyCoveredByStyle(block.spans, selection.start, selection.end, style)
    return if (fullyCovered) {
      removeFormat(style)
    } else {
      applyFormat(style)
    }
  }

  fun applyFormat(style: TextFormatStyle): NoteEditorState {
    val selection = resolveActiveSelection(state.document, state.selection)
    if (selection == null || selection.isCollapsed) {
      if (style !in state.activeTypingStyles) {
        state = state.copy(activeTypingStyles = state.activeTypingStyles + style)
      }
      return state
    }

    val blockIndex = state.document.blocks.indexOfFirst { it.id == selection.blockId }
    if (blockIndex == -1) return state
    val block = state.document.blocks[blockIndex]

    val updatedSpans =
      normalizeAndCoalesceSpans(
        text = block.text,
        spans = block.spans + FormatSpan(selection.start, selection.end, style),
      )

    if (updatedSpans == block.spans) {
      return state
    }

    val updatedBlock = updateBlockTextAndSpans(block, block.text, updatedSpans)
    val updatedBlocks = state.document.blocks.toMutableList().apply { set(blockIndex, updatedBlock) }
    val newDoc = state.document.copy(blocks = updatedBlocks)
    return commitDocumentChange(
      newDocument = newDoc,
      newSelection = selection,
      activeTypingStyles = computeActiveStyles(updatedBlock, selection),
    )
  }

  fun removeFormat(style: TextFormatStyle): NoteEditorState {
    val selection = resolveActiveSelection(state.document, state.selection)
    if (selection == null || selection.isCollapsed) {
      if (style in state.activeTypingStyles) {
        state = state.copy(activeTypingStyles = state.activeTypingStyles - style)
      }
      return state
    }

    val blockIndex = state.document.blocks.indexOfFirst { it.id == selection.blockId }
    if (blockIndex == -1) return state
    val block = state.document.blocks[blockIndex]

    val remaining = mutableListOf<FormatSpan>()
    for (span in block.spans) {
      if (span.style != style || span.end <= selection.start || span.start >= selection.end) {
        remaining.add(span)
      } else {
        if (span.start < selection.start) {
          remaining.add(span.copy(end = selection.start))
        }
        if (span.end > selection.end) {
          remaining.add(span.copy(start = selection.end))
        }
      }
    }

    val updatedSpans = normalizeAndCoalesceSpans(block.text, remaining)
    if (updatedSpans == block.spans) {
      return state
    }

    val updatedBlock = updateBlockTextAndSpans(block, block.text, updatedSpans)
    val updatedBlocks = state.document.blocks.toMutableList().apply { set(blockIndex, updatedBlock) }
    val newDoc = state.document.copy(blocks = updatedBlocks)
    return commitDocumentChange(
      newDocument = newDoc,
      newSelection = selection,
      activeTypingStyles = computeActiveStyles(updatedBlock, selection),
    )
  }

  fun toggleBulletList(): NoteEditorState = toggleBlockListType(TargetListType.BULLET)

  fun toggleNumberedList(): NoteEditorState = toggleBlockListType(TargetListType.NUMBERED)

  fun toggleChecklist(): NoteEditorState = toggleBlockListType(TargetListType.CHECKLIST)

  fun toggleChecklistItem(blockId: String): NoteEditorState {
    val currentDoc = state.document
    val updatedDoc = NoteContentPolicy.toggleChecklistItem(currentDoc, blockId)
    if (updatedDoc == currentDoc) {
      return state
    }

    val resolvedSelection = sanitizeSelection(updatedDoc, state.selection)
    val typingStyles = computeActiveStylesForSelection(updatedDoc, resolvedSelection)

    return commitDocumentChange(
      newDocument = updatedDoc,
      newSelection = resolvedSelection,
      activeTypingStyles = typingStyles,
    )
  }

  fun undo(): NoteEditorState {
    if (undoStack.isEmpty()) return state

    val previous = undoStack.removeLast()
    redoStack.addLast(EditorSnapshot(state.document, state.selection))

    val restoredDoc = previous.document
    val restoredSelection = sanitizeSelection(restoredDoc, previous.selection)
    val typingStyles = computeActiveStylesForSelection(restoredDoc, restoredSelection)

    state =
      NoteEditorState(
        document = restoredDoc,
        selection = restoredSelection,
        activeTypingStyles = typingStyles,
        canUndo = undoStack.isNotEmpty(),
        canRedo = redoStack.isNotEmpty(),
        numberedListNumbers = computeNumberedListNumbers(restoredDoc),
      )
    return state
  }

  fun redo(): NoteEditorState {
    if (redoStack.isEmpty()) return state

    val next = redoStack.removeLast()
    pushUndoSnapshot(EditorSnapshot(state.document, state.selection))

    val restoredDoc = next.document
    val restoredSelection = sanitizeSelection(restoredDoc, next.selection)
    val typingStyles = computeActiveStylesForSelection(restoredDoc, restoredSelection)

    state =
      NoteEditorState(
        document = restoredDoc,
        selection = restoredSelection,
        activeTypingStyles = typingStyles,
        canUndo = undoStack.isNotEmpty(),
        canRedo = redoStack.isNotEmpty(),
        numberedListNumbers = computeNumberedListNumbers(restoredDoc),
      )
    return state
  }

  private enum class TargetListType {
    BULLET,
    NUMBERED,
    CHECKLIST,
  }

  private fun toggleBlockListType(target: TargetListType): NoteEditorState {
    val currentDoc = state.document
    if (currentDoc.blocks.isEmpty()) {
      val newId = generateUniqueBlockId(currentDoc)
      val newBlock: ContentBlock =
        when (target) {
          TargetListType.BULLET -> BulletListItemBlock(id = newId)
          TargetListType.NUMBERED -> NumberedListItemBlock(id = newId)
          TargetListType.CHECKLIST -> ChecklistItemBlock(id = newId, isChecked = false)
        }
      val newDoc = NoteContentDocument(blocks = listOf(newBlock))
      val newSel = BlockSelection(newId, 0, 0)
      return commitDocumentChange(
        newDocument = newDoc,
        newSelection = newSel,
        activeTypingStyles = state.activeTypingStyles,
      )
    }

    val selection = resolveActiveSelection(currentDoc, state.selection) ?: return state
    val index = currentDoc.blocks.indexOfFirst { it.id == selection.blockId }
    if (index == -1) return state
    val currentBlock = currentDoc.blocks[index]

    val convertedBlock: ContentBlock =
      when (target) {
        TargetListType.BULLET ->
          if (currentBlock is BulletListItemBlock) {
            ParagraphBlock(id = currentBlock.id, text = currentBlock.text, spans = currentBlock.spans)
          } else {
            BulletListItemBlock(
              id = currentBlock.id,
              text = currentBlock.text,
              spans = currentBlock.spans,
              indentLevel = blockIndentLevel(currentBlock),
            )
          }

        TargetListType.NUMBERED ->
          if (currentBlock is NumberedListItemBlock) {
            ParagraphBlock(id = currentBlock.id, text = currentBlock.text, spans = currentBlock.spans)
          } else {
            NumberedListItemBlock(
              id = currentBlock.id,
              text = currentBlock.text,
              spans = currentBlock.spans,
              indentLevel = blockIndentLevel(currentBlock),
            )
          }

        TargetListType.CHECKLIST ->
          if (currentBlock is ChecklistItemBlock) {
            ParagraphBlock(id = currentBlock.id, text = currentBlock.text, spans = currentBlock.spans)
          } else {
            ChecklistItemBlock(
              id = currentBlock.id,
              text = currentBlock.text,
              spans = currentBlock.spans,
              isChecked = false,
              indentLevel = blockIndentLevel(currentBlock),
            )
          }
      }

    val updatedBlocks = currentDoc.blocks.toMutableList().apply { set(index, convertedBlock) }
    val newDoc = NoteContentPolicy.normalizeDocument(currentDoc.copy(blocks = updatedBlocks))
    val newSel = sanitizeSelection(newDoc, selection)
    return commitDocumentChange(
      newDocument = newDoc,
      newSelection = newSel,
      activeTypingStyles = state.activeTypingStyles,
    )
  }

  private fun insertTextIntoEmptyDocument(text: String): NoteEditorState {
    if (text.isEmpty()) return state

    if (!text.contains('\n')) {
      val blockId = generateUniqueBlockId(state.document)
      val spans =
        if (state.activeTypingStyles.isEmpty()) {
          emptyList()
        } else {
          state.activeTypingStyles
            .sortedBy { it.ordinal }
            .map { style -> FormatSpan(start = 0, end = text.length, style = style) }
        }
      val newBlock = ParagraphBlock(id = blockId, text = text, spans = spans)
      val newDoc = NoteContentDocument(blocks = listOf(newBlock))
      val newSel = BlockSelection(blockId, text.length, text.length)
      return commitDocumentChange(
        newDocument = newDoc,
        newSelection = newSel,
        activeTypingStyles = state.activeTypingStyles,
      )
    }

    val lines = text.split('\n')
    val usedIds = HashSet<String>(lines.size)
    val blocks =
      lines.map { line ->
        var id = blockIdGenerator()
        var suffix = 1
        while (!usedIds.add(id)) {
          id = "${blockIdGenerator()}_${suffix++}"
        }
        val spans =
          if (line.isEmpty() || state.activeTypingStyles.isEmpty()) {
            emptyList()
          } else {
            state.activeTypingStyles
              .sortedBy { it.ordinal }
              .map { style -> FormatSpan(start = 0, end = line.length, style = style) }
          }
        ParagraphBlock(id = id, text = line, spans = spans)
      }
    val newDoc = NoteContentDocument(blocks = blocks)
    val last = blocks.last()
    val newSel = BlockSelection(last.id, last.text.length, last.text.length)
    return commitDocumentChange(
      newDocument = newDoc,
      newSelection = newSel,
      activeTypingStyles = state.activeTypingStyles,
    )
  }

  private fun insertMultilineText(
    selection: BlockSelection,
    text: String,
    typingStyles: Set<TextFormatStyle>,
  ): NoteEditorState {
    val result =
      applyMultilineEdit(
        baseDocument = state.document,
        selection = selection,
        text = text,
        typingStyles = typingStyles,
      )
    val newSel = result.second
    val newTypingStyles = computeActiveStylesForSelection(result.first, newSel)
    return commitDocumentChange(
      newDocument = result.first,
      newSelection = newSel,
      activeTypingStyles = newTypingStyles,
    )
  }

  private fun applyMultilineEdit(
    baseDocument: NoteContentDocument,
    selection: BlockSelection,
    text: String,
    typingStyles: Set<TextFormatStyle>,
  ): Pair<NoteContentDocument, BlockSelection> {
    var workingDoc =
      if (!selection.isCollapsed) {
        replaceBlockContent(
          document = baseDocument,
          blockId = selection.blockId,
          delStart = selection.start,
          delEnd = selection.end,
          insertedText = "",
          typingStyles = emptySet(),
        )
      } else {
        baseDocument
      }

    var currentSelection = BlockSelection(selection.blockId, selection.start, selection.start)
    val parts = text.split('\n')

    parts.forEachIndexed { index, part ->
      if (part.isNotEmpty()) {
        workingDoc =
          replaceBlockContent(
            document = workingDoc,
            blockId = currentSelection.blockId,
            delStart = currentSelection.start,
            delEnd = currentSelection.end,
            insertedText = part,
            typingStyles = typingStyles,
          )
        val cursorAfterPart = currentSelection.start + part.length
        currentSelection = BlockSelection(currentSelection.blockId, cursorAfterPart, cursorAfterPart)
      }
      if (index < parts.lastIndex) {
        val split =
          splitOrExitAt(
            document = workingDoc,
            blockId = currentSelection.blockId,
            selStart = currentSelection.start,
            selEnd = currentSelection.end,
          )
        workingDoc = split.first
        currentSelection = split.second
      }
    }

    return workingDoc to currentSelection
  }

  private fun splitOrExitAt(
    document: NoteContentDocument,
    blockId: String,
    selStart: Int,
    selEnd: Int,
  ): Pair<NoteContentDocument, BlockSelection> {
    val cleanedDoc =
      if (selStart < selEnd) {
        replaceBlockContent(
          document = document,
          blockId = blockId,
          delStart = selStart,
          delEnd = selEnd,
          insertedText = "",
          typingStyles = emptySet(),
        )
      } else {
        document
      }

    val blocks = cleanedDoc.blocks
    val index = blocks.indexOfFirst { it.id == blockId }
    if (index == -1) {
      return cleanedDoc to BlockSelection(blockId, selStart, selStart)
    }

    val block = blocks[index]
    val splitPos = selStart.coerceIn(0, block.text.length)

    // Enter em item vazio de lista encerra a lista e converte o item em parágrafo.
    if (block !is ParagraphBlock && block.text.isEmpty()) {
      val converted = ParagraphBlock(id = block.id, text = "", spans = emptyList())
      val updatedBlocks = blocks.toMutableList().apply { set(index, converted) }
      val normalizedDoc = NoteContentPolicy.normalizeDocument(cleanedDoc.copy(blocks = updatedBlocks))
      return normalizedDoc to BlockSelection(converted.id, 0, 0)
    }

    val leftText = block.text.substring(0, splitPos)
    val rightText = block.text.substring(splitPos)

    val leftSpans = sliceSpans(block.spans, 0, splitPos, shift = 0, text = leftText)
    val rightSpans =
      sliceSpans(block.spans, splitPos, block.text.length, shift = -splitPos, text = rightText)

    val leftBlock = updateBlockTextAndSpans(block, leftText, leftSpans)
    val newBlockId = generateUniqueBlockId(cleanedDoc)
    val rightBlock: ContentBlock =
      when (block) {
        is ParagraphBlock ->
          ParagraphBlock(id = newBlockId, text = rightText, spans = rightSpans)
        is BulletListItemBlock ->
          BulletListItemBlock(
            id = newBlockId,
            text = rightText,
            spans = rightSpans,
            indentLevel = block.indentLevel,
          )
        is NumberedListItemBlock ->
          NumberedListItemBlock(
            id = newBlockId,
            text = rightText,
            spans = rightSpans,
            indentLevel = block.indentLevel,
          )
        is ChecklistItemBlock ->
          ChecklistItemBlock(
            id = newBlockId,
            text = rightText,
            spans = rightSpans,
            isChecked = false,
            indentLevel = block.indentLevel,
          )
      }

    val updatedBlocks = buildList(blocks.size + 1) {
      addAll(blocks.subList(0, index))
      add(leftBlock)
      add(rightBlock)
      if (index + 1 < blocks.size) {
        addAll(blocks.subList(index + 1, blocks.size))
      }
    }

    val normalizedDoc = NoteContentPolicy.normalizeDocument(cleanedDoc.copy(blocks = updatedBlocks))
    return normalizedDoc to BlockSelection(rightBlock.id, 0, 0)
  }

  private fun replaceRangeInBlock(
    blockId: String,
    delStart: Int,
    delEnd: Int,
    insertedText: String,
    typingStyles: Set<TextFormatStyle>,
    newSelectionStart: Int,
    newSelectionEnd: Int,
  ): NoteEditorState {
    val currentDoc = state.document
    val newDoc =
      replaceBlockContent(
        document = currentDoc,
        blockId = blockId,
        delStart = delStart,
        delEnd = delEnd,
        insertedText = insertedText,
        typingStyles = typingStyles,
      )

    val updatedBlock = newDoc.blocks.firstOrNull { it.id == blockId } ?: return state
    val newSel = normalizeSelection(updatedBlock, newSelectionStart, newSelectionEnd)

    if (newDoc == currentDoc) {
      if (newSel != state.selection) {
        state =
          state.copy(
            selection = newSel,
            activeTypingStyles = computeActiveStyles(updatedBlock, newSel),
          )
      }
      return state
    }

    val nextTypingStyles =
      if (newSel.isCollapsed && insertedText.isNotEmpty()) {
        typingStyles
      } else {
        computeActiveStyles(updatedBlock, newSel)
      }

    return commitDocumentChange(
      newDocument = newDoc,
      newSelection = newSel,
      activeTypingStyles = nextTypingStyles,
    )
  }

  private fun replaceBlockContent(
    document: NoteContentDocument,
    blockId: String,
    delStart: Int,
    delEnd: Int,
    insertedText: String,
    typingStyles: Set<TextFormatStyle>,
  ): NoteContentDocument {
    val index = document.blocks.indexOfFirst { it.id == blockId }
    if (index == -1) return document
    val block = document.blocks[index]

    val safeDelStart = delStart.coerceIn(0, block.text.length)
    val safeDelEnd = delEnd.coerceIn(safeDelStart, block.text.length)
    val newText =
      buildString(block.text.length - (safeDelEnd - safeDelStart) + insertedText.length) {
        append(block.text, 0, safeDelStart)
        append(insertedText)
        append(block.text, safeDelEnd, block.text.length)
      }

    val updatedSpans =
      shiftSpansForEdit(
        spans = block.spans,
        newText = newText,
        delStart = safeDelStart,
        delEnd = safeDelEnd,
        insertedLength = insertedText.length,
        typingStyles = typingStyles,
      )

    val updatedBlock = updateBlockTextAndSpans(block, newText, updatedSpans)
    if (updatedBlock == block) return document

    val updatedBlocks = document.blocks.toMutableList().apply { set(index, updatedBlock) }
    return document.copy(blocks = updatedBlocks)
  }

  private fun commitDocumentChange(
    newDocument: NoteContentDocument,
    newSelection: BlockSelection?,
    activeTypingStyles: Set<TextFormatStyle>,
  ): NoteEditorState {
    val normalizedSelection = sanitizeSelection(newDocument, newSelection)
    if (newDocument == state.document) {
      if (normalizedSelection != state.selection || activeTypingStyles != state.activeTypingStyles) {
        state =
          state.copy(
            selection = normalizedSelection,
            activeTypingStyles = activeTypingStyles,
          )
      }
      return state
    }

    pushUndoSnapshot(EditorSnapshot(state.document, state.selection))
    redoStack.clear()

    state =
      NoteEditorState(
        document = newDocument,
        selection = normalizedSelection,
        activeTypingStyles = activeTypingStyles,
        canUndo = undoStack.isNotEmpty(),
        canRedo = false,
        numberedListNumbers = computeNumberedListNumbers(newDocument),
      )
    return state
  }

  private fun pushUndoSnapshot(snapshot: EditorSnapshot) {
    if (undoStack.size >= maxHistorySize) {
      undoStack.removeFirst()
    }
    undoStack.addLast(snapshot)
  }

  private fun buildInitialState(
    initialDocument: NoteContentDocument,
    initialSelection: BlockSelection?,
  ): NoteEditorState {
    val normalizedDoc = NoteContentPolicy.normalizeDocument(initialDocument)
    val resolvedSelection =
      if (normalizedDoc.blocks.isEmpty()) {
        null
      } else {
        sanitizeSelection(normalizedDoc, initialSelection)
          ?: BlockSelection(normalizedDoc.blocks.first().id, 0, 0)
      }
    val typingStyles = computeActiveStylesForSelection(normalizedDoc, resolvedSelection)

    return NoteEditorState(
      document = normalizedDoc,
      selection = resolvedSelection,
      activeTypingStyles = typingStyles,
      canUndo = false,
      canRedo = false,
      numberedListNumbers = computeNumberedListNumbers(normalizedDoc),
    )
  }

  private fun generateUniqueBlockId(document: NoteContentDocument): String {
    val existingIds = document.blocks.asSequence().map { it.id }.toHashSet()
    var candidate = blockIdGenerator()
    var suffix = 1
    while (candidate in existingIds) {
      candidate = "${blockIdGenerator()}_${suffix++}"
    }
    return candidate
  }

  companion object {
    const val DEFAULT_MAX_HISTORY_SIZE: Int = 200

    fun computeNumberedListNumbers(document: NoteContentDocument): Map<String, Int> =
      NoteEditorTextHelpers.computeNumberedListNumbers(document)
  }
}
