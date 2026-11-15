package com.example.domain.editor

import com.example.domain.model.FormatSpan
import com.example.domain.model.ParagraphBlock
import com.example.domain.model.TextFormatStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteEditorTextHelpersTest {

  private fun assertValidUtf16(text: String) {
    for (i in text.indices) {
      val ch = text[i]
      if (ch.isHighSurrogate()) {
        assertTrue(
          "High surrogate isolado no índice $i em '$text'",
          i + 1 < text.length && text[i + 1].isLowSurrogate(),
        )
      }
      if (ch.isLowSurrogate()) {
        assertTrue(
          "Low surrogate isolado no índice $i em '$text'",
          i - 1 >= 0 && text[i - 1].isHighSurrogate(),
        )
      }
    }
  }

  @Test
  fun `previousCodePointStart handles cursor boundaries surrogate pairs and out-of-bounds safely`() {
    val text = "A😀B" // 0:'A', 1:'\uD83D', 2:'\uDE00', 3:'B' (len = 4)
    assertValidUtf16(text)

    assertEquals(0, NoteEditorTextHelpers.previousCodePointStart(text, -10))
    assertEquals(0, NoteEditorTextHelpers.previousCodePointStart(text, 0))
    assertEquals(0, NoteEditorTextHelpers.previousCodePointStart(text, 1))
    // Cursor no meio do par substituto retorna o início do par substituto (1)
    assertEquals(1, NoteEditorTextHelpers.previousCodePointStart(text, 2))
    // Cursor imediatamente após o emoji retorna o início do par substituto (1)
    assertEquals(1, NoteEditorTextHelpers.previousCodePointStart(text, 3))
    assertEquals(3, NoteEditorTextHelpers.previousCodePointStart(text, 4))
    assertEquals(3, NoteEditorTextHelpers.previousCodePointStart(text, 999))
    assertEquals(0, NoteEditorTextHelpers.previousCodePointStart("", 0))
    assertEquals(0, NoteEditorTextHelpers.previousCodePointStart("", 5))
  }

  @Test
  fun `backspaceDeleteRange returns unambiguous half-open bounds for emoji middle after and edges`() {
    val text = "A😀B"
    assertValidUtf16(text)

    val atStart = NoteEditorTextHelpers.backspaceDeleteRange(text, 0)
    assertEquals(TextRangeBounds(0, 0), atStart)
    assertTrue(atStart.isEmpty)

    val negative = NoteEditorTextHelpers.backspaceDeleteRange(text, -3)
    assertEquals(TextRangeBounds(0, 0), negative)
    assertTrue(negative.isEmpty)

    val afterA = NoteEditorTextHelpers.backspaceDeleteRange(text, 1)
    assertEquals(TextRangeBounds(0, 1), afterA)
    assertFalse(afterA.isEmpty)

    // Tanto no meio do par (2) quanto depois (3), o intervalo é [1, 3) (emoji inteiro)
    assertEquals(TextRangeBounds(1, 3), NoteEditorTextHelpers.backspaceDeleteRange(text, 2))
    assertEquals(TextRangeBounds(1, 3), NoteEditorTextHelpers.backspaceDeleteRange(text, 3))

    assertEquals(TextRangeBounds(3, 4), NoteEditorTextHelpers.backspaceDeleteRange(text, 4))
    assertEquals(TextRangeBounds(3, 4), NoteEditorTextHelpers.backspaceDeleteRange(text, 50))
  }

  @Test
  fun `isInsideSurrogatePair and normalizeSelection align collapsed and partial emoji selections`() {
    val block = ParagraphBlock(id = "p1", text = "A😀B")
    assertFalse(NoteEditorTextHelpers.isInsideSurrogatePair(block.text, 0))
    assertFalse(NoteEditorTextHelpers.isInsideSurrogatePair(block.text, 1))
    assertTrue(NoteEditorTextHelpers.isInsideSurrogatePair(block.text, 2))
    assertFalse(NoteEditorTextHelpers.isInsideSurrogatePair(block.text, 3))
    assertFalse(NoteEditorTextHelpers.isInsideSurrogatePair(block.text, 4))

    // Cursor colapsado no meio do par avança para depois do emoji (3)
    assertEquals(BlockSelection("p1", 3, 3), NoteEditorTextHelpers.normalizeSelection(block, 2, 2))
    // Seleções parciais atingindo apenas metade do emoji expandem para abranger o emoji completo (1..3)
    assertEquals(BlockSelection("p1", 1, 3), NoteEditorTextHelpers.normalizeSelection(block, 1, 2))
    assertEquals(BlockSelection("p1", 1, 3), NoteEditorTextHelpers.normalizeSelection(block, 2, 3))
    assertEquals(BlockSelection("p1", 1, 3), NoteEditorTextHelpers.normalizeSelection(block, 3, 2))
    assertEquals(BlockSelection("p1", 0, 3), NoteEditorTextHelpers.normalizeSelection(block, 0, 2))
    assertEquals(BlockSelection("p1", 1, 4), NoteEditorTextHelpers.normalizeSelection(block, 2, 4))
    // Fronteiras válidas são preservadas
    assertEquals(BlockSelection("p1", 1, 1), NoteEditorTextHelpers.normalizeSelection(block, 1, 1))
    assertEquals(BlockSelection("p1", 1, 3), NoteEditorTextHelpers.normalizeSelection(block, 1, 3))
  }

  @Test
  fun `computeMinimalDiffBounds never splits UTF-16 surrogate pairs on shared high or low surrogates`() {
    // Mesmo high surrogate: 😀 (\uD83D\uDE00) -> 😁 (\uD83D\uDE01)
    assertEquals(0 to 0, NoteEditorTextHelpers.computeMinimalDiffBounds("😀", "😁"))
    assertEquals(1 to 1, NoteEditorTextHelpers.computeMinimalDiffBounds("A😀B", "A😁B"))

    // Mesmo low surrogate: 🐀 (\uD83D\uDC00) -> 🀀 (\uD83C\uDC00)
    assertValidUtf16("🐀")
    assertValidUtf16("🀀")
    assertEquals('\uDC00', "🐀"[1])
    assertEquals('\uDC00', "🀀"[1])
    assertEquals(0 to 0, NoteEditorTextHelpers.computeMinimalDiffBounds("🐀", "🀀"))
    assertEquals(1 to 1, NoteEditorTextHelpers.computeMinimalDiffBounds("X🐀Y", "X🀀Y"))

    // Remoção / inserção de emoji adjacente a outro com mesmo high surrogate
    assertEquals(0 to 0, NoteEditorTextHelpers.computeMinimalDiffBounds("😀😁", "😁"))
    assertEquals(0 to 0, NoteEditorTextHelpers.computeMinimalDiffBounds("😁", "😀😁"))
    assertEquals(2 to 0, NoteEditorTextHelpers.computeMinimalDiffBounds("😀", "😀😁"))
  }

  @Test
  fun `normalizeAndCoalesceSpans expands legacy spans that split surrogate pairs`() {
    val text = "A😀B"
    val legacySpans =
      listOf(
        FormatSpan(0, 2, TextFormatStyle.BOLD),
        FormatSpan(2, 4, TextFormatStyle.ITALIC),
      )

    val normalized = NoteEditorTextHelpers.normalizeAndCoalesceSpans(text, legacySpans)
    assertEquals(
      listOf(
        FormatSpan(0, 3, TextFormatStyle.BOLD),
        FormatSpan(1, 4, TextFormatStyle.ITALIC),
      ),
      normalized,
    )
    normalized.forEach { span ->
      assertFalse(NoteEditorTextHelpers.isInsideSurrogatePair(text, span.start))
      assertFalse(NoteEditorTextHelpers.isInsideSurrogatePair(text, span.end))
    }
  }
}
