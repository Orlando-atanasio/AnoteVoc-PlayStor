package com.example.domain.policy

import com.example.data.local.mapper.NoteContentCodec
import com.example.domain.model.BulletListItemBlock
import com.example.domain.model.ChecklistItemBlock
import com.example.domain.model.FormatSpan
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NoteContentState
import com.example.domain.model.NumberedListItemBlock
import com.example.domain.model.ParagraphBlock
import com.example.domain.model.TextFormatStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteContentPolicyTest {

  @Test
  fun `serializes and deserializes structured note document with formatting and blocks`() {
    val original =
      NoteContentDocument(
        schemaVersion = NoteContentDocument.CURRENT_SCHEMA_VERSION,
        blocks =
          listOf(
            ParagraphBlock(
              id = "p1",
              text = "Resumo importante",
              spans =
                listOf(
                  FormatSpan(start = 0, end = 6, style = TextFormatStyle.BOLD),
                  FormatSpan(start = 7, end = 17, style = TextFormatStyle.ITALIC),
                ),
            ),
            BulletListItemBlock(id = "b1", text = "Item com marcador"),
            NumberedListItemBlock(id = "n1", text = "Primeiro passo"),
            ChecklistItemBlock(
              id = "c1",
              text = "Revisar escopo",
              spans = listOf(FormatSpan(0, 7, TextFormatStyle.UNDERLINE)),
              isChecked = false,
            ),
          ),
      )

    val json = NoteContentCodec.encode(original)
    val state = NoteContentCodec.decode(json)

    assertTrue(state is NoteContentState.Valid)
    val decoded = (state as NoteContentState.Valid).document
    assertEquals(original, decoded)
    assertEquals(
      "Resumo importante\nItem com marcador\nPrimeiro passo\nRevisar escopo",
      NoteContentPolicy.extractPlainTextPreview(decoded),
    )
  }

  @Test
  fun `deserialization ignores unknown extra fields inside known blocks`() {
    val jsonWithExtraBlockField =
      """
      {
        "schemaVersion": 1,
        "futureDocumentProperty": true,
        "blocks": [
          {
            "type": "paragraph",
            "id": "p1",
            "text": "Texto preservado",
            "spans": [],
            "futureBlockProperty": "extra"
          }
        ]
      }
      """
        .trimIndent()

    val state = NoteContentCodec.decode(jsonWithExtraBlockField)

    assertTrue(state is NoteContentState.Valid)
    val decoded = (state as NoteContentState.Valid).document
    assertEquals(1, decoded.schemaVersion)
    assertEquals(1, decoded.blocks.size)
    assertEquals("Texto preservado", decoded.blocks.first().text)
  }

  @Test
  fun `sanitizeSpans clamps negative and overflowing bounds and drops empty or inverted spans without losing text`() {
    val text = "Texto"
    val dirtySpans =
      listOf(
        FormatSpan(start = -4, end = 3, style = TextFormatStyle.BOLD), // vira 0..3
        FormatSpan(start = 2, end = 99, style = TextFormatStyle.ITALIC), // vira 2..5
        FormatSpan(start = 2, end = 2, style = TextFormatStyle.UNDERLINE), // vazio -> descartado
        FormatSpan(start = 4, end = 1, style = TextFormatStyle.STRIKETHROUGH), // invertido -> descartado
        FormatSpan(start = 10, end = 20, style = TextFormatStyle.BOLD), // fora do texto -> descartado
      )

    val sanitized = NoteContentPolicy.sanitizeSpans(text, dirtySpans)

    assertEquals(
      listOf(
        FormatSpan(start = 0, end = 3, style = TextFormatStyle.BOLD),
        FormatSpan(start = 2, end = 5, style = TextFormatStyle.ITALIC),
      ),
      sanitized,
    )

    val doc =
      NoteContentDocument(
        blocks = listOf(ParagraphBlock(id = "p1", text = text, spans = dirtySpans))
      )
    val normalizedDoc = NoteContentPolicy.normalizeDocument(doc)
    assertEquals("Texto", normalizedDoc.blocks.first().text)
    assertEquals(sanitized, normalizedDoc.blocks.first().spans)
  }

  @Test
  fun `decode distinguishes corrupted json, unsupported future version and unknown block type preserving rawJson`() {
    val corruptedRaw = "{json-quebrado"
    val corruptedState = NoteContentCodec.decode(corruptedRaw)
    assertTrue(corruptedState is NoteContentState.RequiresHandling.CorruptedJson)
    assertEquals(corruptedRaw, (corruptedState as NoteContentState.RequiresHandling).rawJson)

    val blankState = NoteContentCodec.decode("   ")
    assertTrue(blankState is NoteContentState.RequiresHandling.CorruptedJson)

    val futureRaw =
      """
      {
        "schemaVersion": 99,
        "blocks": [{"type": "paragraph", "id": "p1", "text": "Futuro", "spans": []}]
      }
      """
        .trimIndent()
    val futureState = NoteContentCodec.decode(futureRaw)
    assertTrue(futureState is NoteContentState.RequiresHandling.UnsupportedFutureVersion)
    val typedFuture = futureState as NoteContentState.RequiresHandling.UnsupportedFutureVersion
    assertEquals(99, typedFuture.detectedSchemaVersion)
    assertEquals(futureRaw, typedFuture.rawJson)

    val unknownBlockRaw =
      """
      {
        "schemaVersion": 1,
        "blocks": [
          {"type": "paragraph", "id": "p1", "text": "Oi", "spans": []},
          {"type": "audio_attachment", "id": "a1", "url": "local://1"}
        ]
      }
      """
        .trimIndent()
    val unknownState = NoteContentCodec.decode(unknownBlockRaw)
    assertTrue(unknownState is NoteContentState.RequiresHandling.UnknownBlockType)
    val typedUnknown = unknownState as NoteContentState.RequiresHandling.UnknownBlockType
    assertEquals(listOf("audio_attachment"), typedUnknown.unknownTypes)
    assertEquals(unknownBlockRaw, typedUnknown.rawJson)

    val malformedKnownBlockRaw =
      """
      {
        "schemaVersion": 1,
        "blocks": [
          {"type": "paragraph", "text": "Sem id obrigatório", "spans": []}
        ]
      }
      """
        .trimIndent()
    val malformedBlockState = NoteContentCodec.decode(malformedKnownBlockRaw)
    assertTrue(malformedBlockState is NoteContentState.RequiresHandling.CorruptedJson)
    assertEquals(
      malformedKnownBlockRaw,
      (malformedBlockState as NoteContentState.RequiresHandling).rawJson,
    )
  }

  @Test
  fun `checking checklist item moves it to end of completed items within same sequence`() {
    val doc =
      NoteContentDocument(
        blocks =
          listOf(
            ParagraphBlock(id = "p1", text = "Compras"),
            ChecklistItemBlock(id = "c1", text = "Pão", isChecked = false),
            ChecklistItemBlock(id = "c2", text = "Leite", isChecked = false),
            ChecklistItemBlock(id = "c3", text = "Café", isChecked = true),
            ChecklistItemBlock(id = "c4", text = "Açúcar", isChecked = true),
            ParagraphBlock(id = "p2", text = "Outras tarefas"),
            ChecklistItemBlock(id = "c5", text = "Ligar", isChecked = false),
          )
      )

    val updated = NoteContentPolicy.toggleChecklistItem(doc, blockId = "c1")

    assertEquals(listOf("p1", "c2", "c3", "c4", "c1", "p2", "c5"), updated.blocks.map { it.id })
    val movedItem = updated.blocks.first { it.id == "c1" } as ChecklistItemBlock
    assertTrue(movedItem.isChecked)
    assertFalse((updated.blocks.last() as ChecklistItemBlock).isChecked)
  }

  @Test
  fun `unchecking checklist item moves it to end of pending items within same sequence`() {
    val doc =
      NoteContentDocument(
        blocks =
          listOf(
            ParagraphBlock(id = "p1", text = "Compras"),
            ChecklistItemBlock(id = "c1", text = "Leite", isChecked = false),
            ChecklistItemBlock(id = "c2", text = "Ovos", isChecked = false),
            ChecklistItemBlock(id = "c3", text = "Café", isChecked = true),
            ChecklistItemBlock(id = "c4", text = "Açúcar", isChecked = true),
            BulletListItemBlock(id = "b1", text = "Nota final"),
          )
      )

    val updated = NoteContentPolicy.toggleChecklistItem(doc, blockId = "c3")

    assertEquals(listOf("p1", "c1", "c2", "c3", "c4", "b1"), updated.blocks.map { it.id })
    val uncheckedItem = updated.blocks.first { it.id == "c3" } as ChecklistItemBlock
    assertFalse(uncheckedItem.isChecked)
    assertTrue((updated.blocks.first { it.id == "c4" } as ChecklistItemBlock).isChecked)
  }
}
