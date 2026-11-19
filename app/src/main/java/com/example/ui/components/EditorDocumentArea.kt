package com.example.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.editor.NoteEditorState
import com.example.domain.model.BulletListItemBlock
import com.example.domain.model.ChecklistItemBlock
import com.example.domain.model.ContentBlock
import com.example.domain.model.FormatSpan
import com.example.domain.model.NumberedListItemBlock
import com.example.domain.model.ParagraphBlock
import com.example.domain.model.TextFormatStyle

@Composable
fun EditorDocumentArea(
  title: String,
  editorState: NoteEditorState,
  stateGeneration: Long,
  isReadOnlyMode: Boolean,
  canEdit: Boolean,
  paperPalette: NotePaperPalette,
  focusRequestTrigger: Long,
  focusRequestBlockId: String?,
  onTitleChanged: (String) -> Unit,
  onEmptyDocumentTextChanged: (String) -> Unit,
  onBlockTextChanged: (blockId: String, newText: String, selStart: Int, selEnd: Int) -> Unit,
  onBlockSelectionChanged: (blockId: String, start: Int, end: Int) -> Unit,
  onBackspaceAtStart: () -> Unit,
  onToggleChecklistItem: (blockId: String) -> Unit,
  onTapReadOnlyContent: (blockId: String?, offset: Int?) -> Unit,
  onContentFocusChanged: (blockKey: String, focused: Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val blockFocusRequesters = remember { mutableStateMapOf<String, FocusRequester>() }
  val emptyDocFocusRequester = remember { FocusRequester() }
  val blocks = editorState.document.blocks
  val activeBlockId = editorState.selection?.blockId

  LaunchedEffect(blocks.map { it.id }) {
    val activeIds = blocks.map { it.id }.toSet()
    val staleKeys = blockFocusRequesters.keys.filter { it !in activeIds }
    staleKeys.forEach { blockFocusRequesters.remove(it) }
  }

  LaunchedEffect(focusRequestTrigger, isReadOnlyMode, activeBlockId, blocks.isEmpty()) {
    if (!isReadOnlyMode && focusRequestTrigger > 0L) {
      if (blocks.isEmpty()) {
        runCatching { emptyDocFocusRequester.requestFocus() }
      } else {
        val targetId = focusRequestBlockId ?: activeBlockId ?: blocks.first().id
        val requester = blockFocusRequesters.getOrPut(targetId) { FocusRequester() }
        runCatching { requester.requestFocus() }
      }
    }
  }

  // Quando um Enter/Conversão cria um novo bloco em edição, transfere o foco para ele automaticamente.
  LaunchedEffect(isReadOnlyMode, activeBlockId) {
    if (!isReadOnlyMode && activeBlockId != null) {
      val requester = blockFocusRequesters[activeBlockId]
      if (requester != null) {
        runCatching { requester.requestFocus() }
      }
    }
  }

  Column(
    modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    TitleSection(
      title = title,
      isReadOnlyMode = isReadOnlyMode,
      canEdit = canEdit,
      paperPalette = paperPalette,
      onTitleChanged = onTitleChanged,
      onTapReadOnly = { onTapReadOnlyContent(blocks.firstOrNull()?.id, 0) },
      onTitleFocusChanged = { focused -> onContentFocusChanged("__title__", focused) },
    )

    if (blocks.isEmpty()) {
      if (isReadOnlyMode) {
        Box(
          modifier =
            Modifier.fillMaxWidth()
              .heightIn(min = 160.dp)
              .pointerInput(Unit) { detectTapGestures { onTapReadOnlyContent(null, 0) } }
              .testTag("editor_empty_readonly_area")
        ) {
          Text(
            text = stringResource(R.string.editor_reading_mode_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = paperPalette.secondaryContentColor.copy(alpha = 0.72f),
          )
        }
      } else {
        var emptyFieldText by remember { mutableStateOf("") }
        LaunchedEffect(stateGeneration) { emptyFieldText = "" }

        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp)) {
          if (emptyFieldText.isEmpty()) {
            Text(
              text = stringResource(R.string.editor_content_placeholder),
              style = MaterialTheme.typography.bodyLarge,
              color = paperPalette.secondaryContentColor.copy(alpha = 0.72f),
            )
          }
          BasicTextField(
            value = emptyFieldText,
            onValueChange = { typed ->
              if (typed.isNotEmpty()) {
                emptyFieldText = typed
                onEmptyDocumentTextChanged(typed)
              }
            },
            enabled = canEdit,
            textStyle =
              MaterialTheme.typography.bodyLarge.copy(color = paperPalette.contentColor),
            cursorBrush = SolidColor(paperPalette.accentColor),
            keyboardOptions =
              KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Default,
              ),
            modifier =
              Modifier.fillMaxWidth()
                .heightIn(min = 160.dp)
                .focusRequester(emptyDocFocusRequester)
                .onFocusChanged { onContentFocusChanged("__empty__", it.isFocused) }
                .testTag("editor_empty_content_field"),
          )
        }
      }
    } else {
      blocks.forEachIndexed { index, block ->
        val focusRequester = remember(block.id) {
          blockFocusRequesters.getOrPut(block.id) { FocusRequester() }
        }
        ContentBlockRow(
          index = index,
          block = block,
          numberedLabel = editorState.numberForBlock(block.id),
          editorState = editorState,
          stateGeneration = stateGeneration,
          isReadOnlyMode = isReadOnlyMode,
          canEdit = canEdit,
          showPlaceholder = index == 0 && blocks.size == 1 && block.text.isEmpty(),
          paperPalette = paperPalette,
          focusRequester = focusRequester,
          onBlockTextChanged = onBlockTextChanged,
          onBlockSelectionChanged = onBlockSelectionChanged,
          onBackspaceAtStart = onBackspaceAtStart,
          onToggleChecklistItem = onToggleChecklistItem,
          onTapReadOnlyBlock = { offset -> onTapReadOnlyContent(block.id, offset) },
          onBlockFocusChanged = { focused -> onContentFocusChanged(block.id, focused) },
        )
      }

      if (isReadOnlyMode) {
        Spacer(
          modifier =
            Modifier.fillMaxWidth()
              .heightIn(min = 120.dp)
              .pointerInput(blocks.lastOrNull()?.id) {
                detectTapGestures {
                  val last = blocks.lastOrNull()
                  onTapReadOnlyContent(last?.id, last?.text?.length ?: 0)
                }
              }
              .testTag("editor_readonly_tail_tap_area")
        )
      }
    }
  }
}

@Composable
private fun TitleSection(
  title: String,
  isReadOnlyMode: Boolean,
  canEdit: Boolean,
  paperPalette: NotePaperPalette,
  onTitleChanged: (String) -> Unit,
  onTapReadOnly: () -> Unit,
  onTitleFocusChanged: (Boolean) -> Unit,
) {
  val titleStyle =
    MaterialTheme.typography.headlineSmall.copy(
      color = paperPalette.contentColor,
      fontWeight = FontWeight.SemiBold,
    )

  if (isReadOnlyMode) {
    if (title.isNotBlank()) {
      Text(
        text = title,
        style = titleStyle,
        modifier =
          Modifier.fillMaxWidth()
            .pointerInput(Unit) { detectTapGestures { onTapReadOnly() } }
            .testTag("editor_title_readonly"),
      )
    }
  } else {
    Box(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
      if (title.isEmpty()) {
        Text(
          text = stringResource(R.string.editor_title_placeholder),
          style = titleStyle.copy(color = paperPalette.secondaryContentColor.copy(alpha = 0.65f)),
        )
      }
      BasicTextField(
        value = title,
        onValueChange = onTitleChanged,
        enabled = canEdit,
        textStyle = titleStyle,
        cursorBrush = SolidColor(paperPalette.accentColor),
        keyboardOptions =
          KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Next,
          ),
        modifier =
          Modifier.fillMaxWidth()
            .onFocusChanged { onTitleFocusChanged(it.isFocused) }
            .testTag("editor_title_field"),
      )
    }
  }
}

@Composable
private fun ContentBlockRow(
  index: Int,
  block: ContentBlock,
  numberedLabel: Int?,
  editorState: NoteEditorState,
  stateGeneration: Long,
  isReadOnlyMode: Boolean,
  canEdit: Boolean,
  showPlaceholder: Boolean,
  paperPalette: NotePaperPalette,
  focusRequester: FocusRequester,
  onBlockTextChanged: (blockId: String, newText: String, selStart: Int, selEnd: Int) -> Unit,
  onBlockSelectionChanged: (blockId: String, start: Int, end: Int) -> Unit,
  onBackspaceAtStart: () -> Unit,
  onToggleChecklistItem: (blockId: String) -> Unit,
  onTapReadOnlyBlock: (offset: Int) -> Unit,
  onBlockFocusChanged: (Boolean) -> Unit,
) {
  val isCheckedChecklist = block is ChecklistItemBlock && block.isChecked
  val bodyStyle: TextStyle =
    MaterialTheme.typography.bodyLarge.copy(
      color =
        if (isCheckedChecklist) paperPalette.secondaryContentColor.copy(alpha = 0.72f)
        else paperPalette.contentColor
    )

  val indentPadding =
    when (block) {
      is ParagraphBlock -> 0.dp
      is BulletListItemBlock -> (block.indentLevel.coerceIn(0, 4) * 16).dp
      is NumberedListItemBlock -> (block.indentLevel.coerceIn(0, 4) * 16).dp
      is ChecklistItemBlock -> (block.indentLevel.coerceIn(0, 4) * 16).dp
    }

  val annotatedText =
    remember(block.text, block.spans, isCheckedChecklist, paperPalette.secondaryContentColor) {
      buildBlockAnnotatedString(
        text = block.text,
        spans = block.spans,
        isCheckedChecklist = isCheckedChecklist,
        checkedColor = paperPalette.secondaryContentColor.copy(alpha = 0.72f),
      )
    }

  Row(
    modifier = Modifier.fillMaxWidth().padding(start = indentPadding),
    verticalAlignment = Alignment.Top,
  ) {
    when (block) {
      is ParagraphBlock -> Unit
      is BulletListItemBlock -> {
        Text(
          text = "•",
          style = bodyStyle.copy(fontWeight = FontWeight.Bold, color = paperPalette.accentColor),
          modifier = Modifier.padding(top = 1.dp, end = 10.dp),
        )
      }
      is NumberedListItemBlock -> {
        val number = numberedLabel ?: 1
        Text(
          text = "$number.",
          style = bodyStyle.copy(fontWeight = FontWeight.SemiBold, color = paperPalette.accentColor),
          modifier = Modifier.padding(top = 1.dp, end = 8.dp),
        )
      }
      is ChecklistItemBlock -> {
        val toggleLabel = stringResource(R.string.cd_editor_checklist_toggle, block.text)
        Box(
          modifier = Modifier.size(48.dp),
          contentAlignment = Alignment.TopCenter,
        ) {
          Checkbox(
            checked = block.isChecked,
            onCheckedChange = { onToggleChecklistItem(block.id) },
            enabled = canEdit,
            colors =
              CheckboxDefaults.colors(
                checkedColor = paperPalette.accentColor,
                uncheckedColor = paperPalette.secondaryContentColor,
              ),
            modifier =
              Modifier.semantics { contentDescription = toggleLabel }
                .testTag("editor_checklist_checkbox_$index"),
          )
        }
      }
    }

    val textTopPadding = if (block is ChecklistItemBlock) 12.dp else 0.dp

    if (isReadOnlyMode) {
      var layoutResult by remember(block.id) { mutableStateOf<TextLayoutResult?>(null) }
      Text(
        text = annotatedText,
        style = bodyStyle,
        onTextLayout = { layoutResult = it },
        modifier =
          Modifier.weight(1f)
            .padding(top = textTopPadding)
            .pointerInput(block.id, block.text) {
              detectTapGestures { tapOffset ->
                val charOffset =
                  layoutResult?.getOffsetForPosition(tapOffset)?.coerceIn(0, block.text.length)
                    ?: block.text.length
                onTapReadOnlyBlock(charOffset)
              }
            }
            .testTag("editor_block_readonly_$index"),
      )
    } else {
      val engineSelection =
        if (editorState.selection?.blockId == block.id) {
          TextRange(
            editorState.selection.start.coerceIn(0, block.text.length),
            editorState.selection.end.coerceIn(0, block.text.length),
          )
        } else {
          TextRange(block.text.length)
        }

      var localText by remember(block.id) { mutableStateOf(block.text) }
      var localSelection by remember(block.id) { mutableStateOf(engineSelection) }
      var localComposition by remember(block.id) { mutableStateOf<TextRange?>(null) }

      LaunchedEffect(stateGeneration, block.text, engineSelection) {
        localText = block.text
        localSelection = engineSelection
        if (localComposition != null && localComposition!!.max > block.text.length) {
          localComposition = null
        }
      }

      val displayAnnotated =
        remember(
          localText,
          block.text,
          annotatedText,
          isCheckedChecklist,
          paperPalette.secondaryContentColor,
        ) {
          if (localText == block.text) {
            annotatedText
          } else {
            buildBlockAnnotatedString(
              text = localText,
              spans = block.spans,
              isCheckedChecklist = isCheckedChecklist,
              checkedColor = paperPalette.secondaryContentColor.copy(alpha = 0.72f),
            )
          }
        }

      val fieldValue =
        TextFieldValue(
          annotatedString = displayAnnotated,
          selection =
            TextRange(
              localSelection.start.coerceIn(0, localText.length),
              localSelection.end.coerceIn(0, localText.length),
            ),
          composition =
            localComposition?.takeIf { it.min >= 0 && it.max <= localText.length },
        )

      Box(modifier = Modifier.weight(1f).padding(top = textTopPadding)) {
        if (showPlaceholder && localText.isEmpty()) {
          Text(
            text = stringResource(R.string.editor_content_placeholder),
            style = bodyStyle.copy(color = paperPalette.secondaryContentColor.copy(alpha = 0.72f)),
          )
        }
        BasicTextField(
          value = fieldValue,
          onValueChange = { newValue ->
            val previousText = localText
            val previousSelection = localSelection
            localText = newValue.text
            localSelection = newValue.selection
            localComposition = newValue.composition

            if (newValue.text != previousText) {
              onBlockTextChanged(
                block.id,
                newValue.text,
                newValue.selection.start,
                newValue.selection.end,
              )
            } else if (newValue.selection != previousSelection) {
              onBlockSelectionChanged(
                block.id,
                newValue.selection.min,
                newValue.selection.max,
              )
            }
          },
          enabled = canEdit,
          textStyle = bodyStyle,
          cursorBrush = SolidColor(paperPalette.accentColor),
          keyboardOptions =
            KeyboardOptions(
              capitalization = KeyboardCapitalization.Sentences,
              imeAction = ImeAction.Default,
            ),
          modifier =
            Modifier.fillMaxWidth()
              .focusRequester(focusRequester)
              .onFocusChanged { focusState ->
                if (focusState.isFocused && editorState.selection?.blockId != block.id) {
                  onBlockSelectionChanged(
                    block.id,
                    fieldValue.selection.min,
                    fieldValue.selection.max,
                  )
                }
                onBlockFocusChanged(focusState.isFocused)
              }
              .onPreviewKeyEvent { keyEvent ->
                if (
                  keyEvent.type == KeyEventType.KeyDown &&
                    keyEvent.key == Key.Backspace &&
                    fieldValue.selection.collapsed &&
                    fieldValue.selection.start == 0
                ) {
                  onBackspaceAtStart()
                  true
                } else {
                  false
                }
              }
              .testTag("editor_block_field_$index"),
        )
      }
    }
  }
}

fun buildBlockAnnotatedString(
  text: String,
  spans: List<FormatSpan>,
  isCheckedChecklist: Boolean,
  checkedColor: Color,
): AnnotatedString = buildAnnotatedString {
  append(text)
  val maxLen = text.length
  for (span in spans) {
    val s = span.start.coerceIn(0, maxLen)
    val e = span.end.coerceIn(0, maxLen)
    if (s >= e) continue
    val style =
      when (span.style) {
        TextFormatStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        TextFormatStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        TextFormatStyle.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
        TextFormatStyle.STRIKETHROUGH -> SpanStyle(textDecoration = TextDecoration.LineThrough)
      }
    addStyle(style, s, e)
  }
  if (isCheckedChecklist && maxLen > 0) {
    addStyle(
      SpanStyle(
        color = checkedColor,
        textDecoration = TextDecoration.LineThrough,
      ),
      0,
      maxLen,
    )
  }
}
