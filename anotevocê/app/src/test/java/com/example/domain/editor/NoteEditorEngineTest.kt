package com.example.domain.editor

import com.example.domain.model.BulletListItemBlock
import com.example.domain.model.ChecklistItemBlock
import com.example.domain.model.FormatSpan
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NumberedListItemBlock
import com.example.domain.model.ParagraphBlock
import com.example.domain.model.TextFormatStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteEditorEngineTest {

  private fun sequentialIdGenerator(prefix: String = "b"): () -> String {
    var counter = 0
    return { "$prefix${++counter}" }
  }

  @Test
  fun `applies removes and toggles bold italic and underline coalescing and splitting spans`() {
    val doc =
      NoteContentDocument(
        blocks = listOf(ParagraphBlock(id = "p1", text = "Texto importante aqui"))
      )
    val engine = NoteEditorEngine(doc, blockIdGenerator = sequentialIdGenerator())

    // Aplica negrito em "Texto" e depois itálico sobreposto em "o imp"
    engine.selectBlock("p1", start = 0, end = 5)
    engine.toggleBold()
    engine.selectBlock("p1", start = 4, end = 9)
    engine.toggleItalic()
    engine.selectBlock("p1", start = 6, end = 16)
    engine.toggleUnderline()

    val formatted = engine.state.focusedBlock!!
    assertEquals(
      listOf(
        FormatSpan(0, 5, TextFormatStyle.BOLD),
        FormatSpan(4, 9, TextFormatStyle.ITALIC),
        FormatSpan(6, 16, TextFormatStyle.UNDERLINE),
      ),
      formatted.spans,
    )

    // Seleção parcialmente em negrito ("o im", 4..8) aplica negrito no trecho todo e coalesce para 0..8
    engine.selectBlock("p1", start = 4, end = 8)
    engine.toggleBold()
    assertTrue(
      engine.state.focusedBlock!!.spans.contains(FormatSpan(0, 8, TextFormatStyle.BOLD))
    )

    // Remover negrito do meio (2..5) divide o span em 0..2 e 5..8 sem afetar itálico/sublinhado
    engine.selectBlock("p1", start = 2, end = 5)
    engine.toggleBold()
    val afterSplit = engine.state.focusedBlock!!
    assertEquals(
      listOf(
        FormatSpan(0, 2, TextFormatStyle.BOLD),
        FormatSpan(4, 9, TextFormatStyle.ITALIC),
        FormatSpan(5, 8, TextFormatStyle.BOLD),
        FormatSpan(6, 16, TextFormatStyle.UNDERLINE),
      ),
      afterSplit.spans,
    )

    // Remoção explícita de sublinhado
    engine.selectBlock("p1", start = 6, end = 16)
    engine.removeFormat(TextFormatStyle.UNDERLINE)
    assertFalse(
      engine.state.focusedBlock!!.spans.any { it.style == TextFormatStyle.UNDERLINE }
    )
  }

  @Test
  fun `collapsed cursor formatting controls subsequent typed text without cluttering history`() {
    val doc = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Oi")))
    val engine = NoteEditorEngine(doc, blockIdGenerator = sequentialIdGenerator())

    engine.selectBlock("p1", 2)
    engine.toggleBold()
    engine.toggleUnderline()
    // Alternar estilo no cursor sem digitar ainda não altera documento nem cria entrada de undo
    assertFalse(engine.state.canUndo)
    assertEquals(
      setOf(TextFormatStyle.BOLD, TextFormatStyle.UNDERLINE),
      engine.state.activeTypingStyles,
    )

    engine.insertText(" mundo")
    assertTrue(engine.state.canUndo)
    val block = engine.state.focusedBlock!!
    assertEquals("Oi mundo", block.text)
    assertEquals(
      listOf(
        FormatSpan(2, 8, TextFormatStyle.BOLD),
        FormatSpan(2, 8, TextFormatStyle.UNDERLINE),
      ),
      block.spans,
    )

    // Desliga negrito no meio do trecho e insere texto sem negrito (mantendo sublinhado)
    engine.selectBlock("p1", 5)
    engine.toggleBold()
    engine.insertText("X")
    val splitBlock = engine.state.focusedBlock!!
    assertEquals("Oi muXndo", splitBlock.text)
    assertEquals(
      listOf(
        FormatSpan(2, 5, TextFormatStyle.BOLD),
        FormatSpan(2, 9, TextFormatStyle.UNDERLINE),
        FormatSpan(6, 9, TextFormatStyle.BOLD),
      ),
      splitBlock.spans,
    )
  }

  @Test
  fun `enter in paragraph splits text and spans across new paragraph and replaces active selection`() {
    val doc =
      NoteContentDocument(
        blocks =
          listOf(
            ParagraphBlock(
              id = "p1",
              text = "Olá grande mundo",
              spans =
                listOf(
                  FormatSpan(0, 16, TextFormatStyle.BOLD),
                  FormatSpan(11, 16, TextFormatStyle.ITALIC),
                ),
            )
          )
      )
    val engine = NoteEditorEngine(doc, blockIdGenerator = sequentialIdGenerator("new"))

    // Enter com seleção ativa remove o trecho selecionado ("grande ") e divide no ponto inicial
    engine.selectBlock("p1", start = 4, end = 11)
    engine.pressEnter()

    val blocks = engine.state.document.blocks
    assertEquals(2, blocks.size)
    assertEquals("Olá ", blocks[0].text)
    assertEquals(listOf(FormatSpan(0, 4, TextFormatStyle.BOLD)), blocks[0].spans)

    assertEquals("new1", blocks[1].id)
    assertTrue(blocks[1] is ParagraphBlock)
    assertEquals("mundo", blocks[1].text)
    assertEquals(
      listOf(
        FormatSpan(0, 5, TextFormatStyle.BOLD),
        FormatSpan(0, 5, TextFormatStyle.ITALIC),
      ),
      blocks[1].spans,
    )
    assertEquals(BlockSelection("new1", 0, 0), engine.state.selection)
  }

  @Test
  fun `enter continues bullet numbered and checklist items and exits list on empty item`() {
    val engine = NoteEditorEngine(blockIdGenerator = sequentialIdGenerator())

    // Lista com marcadores: continuidade e encerramento
    engine.toggleBulletList()
    engine.insertText("Maçã")
    engine.pressEnter()
    engine.insertText("Banana")
    engine.pressEnter()
    assertEquals(3, engine.state.document.blocks.size)
    assertTrue(engine.state.document.blocks.all { it is BulletListItemBlock })

    // Enter em item vazio encerra a lista convertendo o item vazio em ParagraphBlock
    engine.pressEnter()
    val afterExitBullet = engine.state.document.blocks
    assertEquals(3, afterExitBullet.size)
    assertTrue(afterExitBullet[0] is BulletListItemBlock)
    assertTrue(afterExitBullet[1] is BulletListItemBlock)
    assertTrue(afterExitBullet[2] is ParagraphBlock)
    assertEquals("", afterExitBullet[2].text)

    // Lista numerada: continuidade, numeração coerente e encerramento no meio da lista
    engine.pressEnter()
    engine.toggleNumberedList()
    engine.insertText("Passo 1")
    engine.pressEnter()
    engine.insertText("Passo 2")
    engine.pressEnter()
    engine.insertText("Passo 3")

    val numberedBlocks = engine.state.document.blocks.filterIsInstance<NumberedListItemBlock>()
    assertEquals(3, numberedBlocks.size)
    assertEquals(1, engine.state.numberForBlock(numberedBlocks[0].id))
    assertEquals(2, engine.state.numberForBlock(numberedBlocks[1].id))
    assertEquals(3, engine.state.numberForBlock(numberedBlocks[2].id))

    // Limpa o item do meio e pressiona Enter para encerrar a lista no meio
    engine.selectBlock(numberedBlocks[1].id, start = 0, end = numberedBlocks[1].text.length)
    engine.pressBackspace()
    engine.pressEnter()

    val afterMiddleExit = engine.state.document.blocks
    val p1 = numberedBlocks[0].id
    val exited = numberedBlocks[1].id
    val p3 = numberedBlocks[2].id
    assertTrue(afterMiddleExit.first { it.id == exited } is ParagraphBlock)
    assertEquals(1, engine.state.numberForBlock(p1))
    assertNull(engine.state.numberForBlock(exited))
    // Sequência seguinte após parágrafo reinicia coerentemente em 1
    assertEquals(1, engine.state.numberForBlock(p3))
  }

  @Test
  fun `enter on checklist creates pending item and preserves pending-before-completed ordering`() {
    val doc =
      NoteContentDocument(
        blocks =
          listOf(
            ChecklistItemBlock(id = "c1", text = "Pendente", isChecked = false),
            ChecklistItemBlock(id = "c2", text = "Concluído", isChecked = true),
          )
      )
    val engine = NoteEditorEngine(doc, blockIdGenerator = sequentialIdGenerator("item"))

    // Enter em item concluído não vazio cria novo item pendente e mantém pendentes antes dos concluídos
    engine.selectBlock("c2", start = 9)
    engine.pressEnter()

    val blocks = engine.state.document.blocks
    assertEquals(listOf("c1", "item1", "c2"), blocks.map { it.id })
    assertFalse((blocks[0] as ChecklistItemBlock).isChecked)
    assertFalse((blocks[1] as ChecklistItemBlock).isChecked)
    assertTrue((blocks[2] as ChecklistItemBlock).isChecked)
    assertEquals(BlockSelection("item1", 0, 0), engine.state.selection)

    // Enter no item vazio recém-criado encerra a checklist convertendo-o em parágrafo
    engine.pressEnter()
    val afterExit = engine.state.document.blocks
    assertTrue(afterExit[1] is ParagraphBlock)
    assertEquals(listOf("c1", "item1", "c2"), afterExit.map { it.id })
  }

  @Test
  fun `checklist completion and unchecking preserve relative order within each group`() {
    val doc =
      NoteContentDocument(
        blocks =
          listOf(
            ParagraphBlock(id = "h1", text = "Compras"),
            ChecklistItemBlock(id = "c1", text = "Pão", isChecked = false),
            ChecklistItemBlock(id = "c2", text = "Leite", isChecked = false),
            ChecklistItemBlock(id = "c3", text = "Ovos", isChecked = false),
            ChecklistItemBlock(id = "c4", text = "Café", isChecked = true),
            ChecklistItemBlock(id = "c5", text = "Açúcar", isChecked = true),
            ParagraphBlock(id = "h2", text = "Outra lista"),
            ChecklistItemBlock(id = "c6", text = "Água", isChecked = false),
          )
      )
    val engine = NoteEditorEngine(doc)

    // Concluir c1 move c1 para o fim da primeira checklist (após c4 e c5)
    engine.toggleChecklistItem("c1")
    assertEquals(
      listOf("h1", "c2", "c3", "c4", "c5", "c1", "h2", "c6"),
      engine.state.document.blocks.map { it.id },
    )

    // Concluir c2 move c2 para o fim após c1
    engine.toggleChecklistItem("c2")
    assertEquals(
      listOf("h1", "c3", "c4", "c5", "c1", "c2", "h2", "c6"),
      engine.state.document.blocks.map { it.id },
    )

    // Desmarcar c4 retorna c4 ao final do grupo de pendentes (após c3, antes de c5)
    engine.toggleChecklistItem("c4")
    assertEquals(
      listOf("h1", "c3", "c4", "c5", "c1", "c2", "h2", "c6"),
      engine.state.document.blocks.map { it.id },
    )
    assertFalse((engine.state.document.blocks[2] as ChecklistItemBlock).isChecked)
    assertTrue((engine.state.document.blocks[3] as ChecklistItemBlock).isChecked)
  }

  @Test
  fun `backspace deletes characters surrogate pairs selections unwraps lists and merges paragraphs`() {
    val doc =
      NoteContentDocument(
        blocks =
          listOf(
            ParagraphBlock(
              id = "p1",
              text = "Olá ",
              spans = listOf(FormatSpan(0, 3, TextFormatStyle.BOLD)),
            ),
            BulletListItemBlock(
              id = "b1",
              text = "😀Item",
              spans = listOf(FormatSpan(2, 6, TextFormatStyle.ITALIC)),
            ),
          )
      )
    val engine = NoteEditorEngine(doc)

    // Apaga seleção dentro de "😀Item"
    engine.selectBlock("b1", start = 2, end = 4)
    engine.pressBackspace()
    assertEquals("😀em", engine.state.focusedBlock!!.text)
    assertEquals(listOf(FormatSpan(2, 4, TextFormatStyle.ITALIC)), engine.state.focusedBlock!!.spans)

    // Backspace logo após emoji remove o par substituto completo (2 UTF-16 chars)
    engine.pressBackspace()
    assertEquals("em", engine.state.focusedBlock!!.text)
    assertEquals(listOf(FormatSpan(0, 2, TextFormatStyle.ITALIC)), engine.state.focusedBlock!!.spans)
    assertEquals(BlockSelection("b1", 0, 0), engine.state.selection)

    // Backspace no início (0) do item de lista converte para parágrafo preservando texto e spans
    engine.pressBackspace()
    assertTrue(engine.state.focusedBlock is ParagraphBlock)
    assertEquals("em", engine.state.focusedBlock!!.text)
    assertEquals(listOf(FormatSpan(0, 2, TextFormatStyle.ITALIC)), engine.state.focusedBlock!!.spans)

    // Segundo Backspace no início do parágrafo une com o bloco anterior deslocando spans
    engine.pressBackspace()
    assertEquals(1, engine.state.document.blocks.size)
    val merged = engine.state.document.blocks.first()
    assertEquals("p1", merged.id)
    assertEquals("Olá em", merged.text)
    assertEquals(
      listOf(
        FormatSpan(0, 3, TextFormatStyle.BOLD),
        FormatSpan(4, 6, TextFormatStyle.ITALIC),
      ),
      merged.spans,
    )
    assertEquals(BlockSelection("p1", 4, 4), engine.state.selection)

    // Backspace no início do primeiro parágrafo é no-op
    engine.selectBlock("p1", 0)
    val beforeBoundary = engine.state.document
    engine.pressBackspace()
    assertEquals(beforeBoundary, engine.state.document)
  }

  @Test
  fun `coherent numbering updates dynamically across interruptions conversions and deletions`() {
    val doc =
      NoteContentDocument(
        blocks =
          listOf(
            NumberedListItemBlock(id = "n1", text = "Um"),
            NumberedListItemBlock(id = "n2", text = "Dois"),
            NumberedListItemBlock(id = "n3", text = "Três"),
            BulletListItemBlock(id = "b1", text = "Marcador"),
            NumberedListItemBlock(id = "n4", text = "Nova lista 1"),
            NumberedListItemBlock(id = "n5", text = "Nova lista 2"),
          )
      )
    val engine = NoteEditorEngine(doc)

    assertEquals(
      mapOf("n1" to 1, "n2" to 2, "n3" to 3, "n4" to 1, "n5" to 2),
      engine.state.numberedListNumbers,
    )

    // Converte b1 em item numerado -> unifica tudo numa sequência contínua 1..6
    engine.selectBlock("b1", 0)
    engine.toggleNumberedList()
    assertEquals(
      mapOf("n1" to 1, "n2" to 2, "n3" to 3, "b1" to 4, "n4" to 5, "n5" to 6),
      engine.state.numberedListNumbers,
    )

    // Desfaz e volta à numeração anterior separada
    engine.undo()
    assertEquals(
      mapOf("n1" to 1, "n2" to 2, "n3" to 3, "n4" to 1, "n5" to 2),
      engine.state.numberedListNumbers,
    )
  }

  @Test
  fun `undo and redo restore document formatting checklist order and avoid noop history entries`() {
    val doc =
      NoteContentDocument(
        blocks =
          listOf(
            ChecklistItemBlock(
              id = "c1",
              text = "Comprar café",
              spans = listOf(FormatSpan(0, 7, TextFormatStyle.BOLD)),
              isChecked = false,
            ),
            ChecklistItemBlock(
              id = "c2",
              text = "Pagar conta",
              spans = listOf(FormatSpan(6, 11, TextFormatStyle.UNDERLINE)),
              isChecked = false,
            ),
          )
      )
    val engine = NoteEditorEngine(doc, blockIdGenerator = sequentialIdGenerator())

    assertFalse(engine.state.canUndo)
    assertFalse(engine.state.canRedo)

    // Operações sem efeito real não criam histórico
    engine.selectBlock("c1", 0)
    engine.pressBackspace() // no-op: cursor em 0 no item -> vira parágrafo! Espere: em checklist vira parágrafo!
    // Vamos restaurar e testar no-ops reais em parágrafo/formatação/checklist inválido
    engine.undo()
    assertFalse(engine.state.canUndo)
    // Redo stack tem 1 entrada agora; no-op NÃO pode limpar redoStack!
    engine.toggleChecklistItem("inexistente")
    engine.selectBlock("c1", 0, 7)
    engine.applyFormat(TextFormatStyle.BOLD) // já é BOLD
    engine.removeFormat(TextFormatStyle.ITALIC) // não tem ITALIC
    assertFalse(engine.state.canUndo)
    assertTrue(engine.state.canRedo)

    // Agora executa sequência real: concluir c1 -> editar texto -> novo item
    engine.toggleChecklistItem("c1")
    val afterCheckOrder = engine.state.document.blocks.map { it.id }
    assertEquals(listOf("c2", "c1"), afterCheckOrder)
    assertFalse(engine.state.canRedo)

    engine.selectBlock("c2", 11)
    engine.insertText(" hoje")
    assertEquals("Pagar conta hoje", engine.state.document.blocks[0].text)

    // Undo restaura texto de c2
    engine.undo()
    assertEquals("Pagar conta", engine.state.document.blocks[0].text)
    assertEquals(listOf("c2", "c1"), engine.state.document.blocks.map { it.id })

    // Undo restaura ordem e formatação originais antes de marcar c1
    engine.undo()
    assertEquals(doc, engine.state.document)
    assertFalse(engine.state.canUndo)
    assertTrue(engine.state.canRedo)

    // Redo refaz marcação + ordenação + edição
    engine.redo()
    assertEquals(listOf("c2", "c1"), engine.state.document.blocks.map { it.id })
    assertTrue((engine.state.document.blocks[1] as ChecklistItemBlock).isChecked)
    assertEquals(listOf(FormatSpan(0, 7, TextFormatStyle.BOLD)), engine.state.document.blocks[1].spans)

    engine.redo()
    assertEquals("Pagar conta hoje", engine.state.document.blocks[0].text)
    assertFalse(engine.state.canRedo)
  }

  @Test
  fun `combined editing sequence and ime applyTextChange preserve spans and split lines`() {
    val engine = NoteEditorEngine(blockIdGenerator = sequentialIdGenerator("blk"))

    // 1. Documento vazio -> insere texto -> aplica itálico -> IME altera parte do texto
    engine.insertText("Reunião amanhã")
    val firstId = engine.state.document.blocks.first().id
    engine.selectBlock(firstId, 0, 7)
    engine.toggleItalic()

    // IME troca "amanhã" por "sexta" mantendo "Reunião " em itálico
    engine.applyTextChange(
      blockId = firstId,
      newText = "Reunião sexta",
      selectionStart = 13,
    )
    assertEquals("Reunião sexta", engine.state.focusedBlock!!.text)
    assertEquals(listOf(FormatSpan(0, 7, TextFormatStyle.ITALIC)), engine.state.focusedBlock!!.spans)

    // Ativa negrito no cursor colapsado e digita via applyTextChange (IME)
    engine.selectBlock(firstId, 13)
    engine.toggleBold()
    engine.applyTextChange(
      blockId = firstId,
      newText = "Reunião sexta!",
      selectionStart = 14,
    )
    assertEquals(
      listOf(
        FormatSpan(0, 7, TextFormatStyle.ITALIC),
        FormatSpan(13, 14, TextFormatStyle.BOLD),
      ),
      engine.state.focusedBlock!!.spans,
    )

    // IME envia '\n' no final -> funciona como Enter em parágrafo
    engine.applyTextChange(
      blockId = firstId,
      newText = "Reunião sexta!\n",
      selectionStart = 15,
    )
    assertEquals(2, engine.state.document.blocks.size)

    // Converte segundo bloco em checklist e adiciona itens
    engine.toggleChecklist()
    engine.insertText("Pauta")
    engine.pressEnter()
    engine.insertText("Ata")

    val checklistIds = engine.state.document.blocks.filterIsInstance<ChecklistItemBlock>().map { it.id }
    assertEquals(2, checklistIds.size)
    engine.toggleChecklistItem(checklistIds.first())
    assertEquals(
      listOf(checklistIds[1], checklistIds[0]),
      engine.state.document.blocks.filterIsInstance<ChecklistItemBlock>().map { it.id },
    )
  }

  @Test
  fun `handles empty documents out-of-bounds selections and bounded history safely`() {
    val emptyEngine = NoteEditorEngine(maxHistorySize = 3, blockIdGenerator = sequentialIdGenerator())

    assertTrue(emptyEngine.state.document.blocks.isEmpty())
    assertNull(emptyEngine.state.selection)

    // Operações seguras em documento vazio
    emptyEngine.pressBackspace()
    emptyEngine.undo()
    emptyEngine.redo()
    emptyEngine.insertText("")
    assertTrue(emptyEngine.state.document.blocks.isEmpty())
    assertFalse(emptyEngine.state.canUndo)
    assertFalse(emptyEngine.state.canRedo)

    // Enter em documento vazio cria primeiro parágrafo
    emptyEngine.pressEnter()
    assertEquals(1, emptyEngine.state.document.blocks.size)
    assertTrue(emptyEngine.state.canUndo)
    emptyEngine.undo()
    assertTrue(emptyEngine.state.document.blocks.isEmpty())

    // Colagem multilinha em documento vazio cria múltiplos parágrafos ordenados
    emptyEngine.insertText("Linha 1\nLinha 2\nLinha 3")
    assertEquals(listOf("Linha 1", "Linha 2", "Linha 3"), emptyEngine.state.document.blocks.map { it.text })

    // Seleção invertida e fora de limites é normalizada sem exceção; bloco inexistente é ignorado
    val targetId = emptyEngine.state.document.blocks[1].id
    emptyEngine.selectBlock(targetId, start = 999, end = -5)
    assertEquals(BlockSelection(targetId, start = 0, end = 7), emptyEngine.state.selection)
    emptyEngine.selectBlock("desconhecido", start = 0, end = 2)
    assertEquals(BlockSelection(targetId, start = 0, end = 7), emptyEngine.state.selection)

    // Limite de histórico (maxHistorySize = 3)
    emptyEngine.selectBlock(targetId, 7)
    emptyEngine.insertText(" A")
    emptyEngine.insertText(" B")
    emptyEngine.insertText(" C")
    emptyEngine.insertText(" D")
    var undoCount = 0
    while (emptyEngine.state.canUndo) {
      emptyEngine.undo()
      undoCount++
    }
    assertEquals(3, undoCount)
  }

  @Test
  fun `backspace after emoji and with cursor between UTF-16 surrogates preserves unicode integrity and spans`() {
    val doc =
      NoteContentDocument(
        blocks =
          listOf(
            ParagraphBlock(
              id = "p1",
              text = "Olá 😀 mundo",
              spans =
                listOf(
                  FormatSpan(0, 3, TextFormatStyle.BOLD),
                  FormatSpan(4, 12, TextFormatStyle.UNDERLINE),
                  FormatSpan(7, 12, TextFormatStyle.ITALIC),
                ),
            )
          )
      )

    // 1. Backspace imediatamente depois do emoji ("😀" ocupa índices 4..6)
    val engineAfter = NoteEditorEngine(doc)
    engineAfter.selectBlock("p1", 6)
    engineAfter.pressBackspace()
    val blockAfter = engineAfter.state.focusedBlock!!
    assertValidUtf16(blockAfter.text)
    assertEquals("Olá  mundo", blockAfter.text)
    assertEquals(BlockSelection("p1", 4, 4), engineAfter.state.selection)
    assertEquals(
      listOf(
        FormatSpan(0, 3, TextFormatStyle.BOLD),
        FormatSpan(4, 10, TextFormatStyle.UNDERLINE),
        FormatSpan(5, 10, TextFormatStyle.ITALIC),
      ),
      blockAfter.spans,
    )

    // 2. Cursor originalmente posicionado entre os dois substitutos (índice 5):
    // normalização avança para depois do par (6) e Backspace remove o emoji completo
    val engineMiddle = NoteEditorEngine(doc, initialSelection = BlockSelection("p1", 5, 5))
    assertEquals(BlockSelection("p1", 6, 6), engineMiddle.state.selection)
    engineMiddle.selectBlock("p1", 5)
    assertEquals(BlockSelection("p1", 6, 6), engineMiddle.state.selection)

    engineMiddle.pressBackspace()
    val blockMiddle = engineMiddle.state.focusedBlock!!
    assertValidUtf16(blockMiddle.text)
    assertEquals("Olá  mundo", blockMiddle.text)
    assertEquals(BlockSelection("p1", 4, 4), engineMiddle.state.selection)
    assertEquals(
      listOf(
        FormatSpan(0, 3, TextFormatStyle.BOLD),
        FormatSpan(4, 10, TextFormatStyle.UNDERLINE),
        FormatSpan(5, 10, TextFormatStyle.ITALIC),
      ),
      blockMiddle.spans,
    )
  }

  @Test
  fun `partial and full emoji selections normalize safely delete cleanly and heal legacy split spans on edit`() {
    // Documento legado com spans que dividem o par substituto de "😀" (índices 1..3 em "A😀B")
    val legacyDoc =
      NoteContentDocument(
        blocks =
          listOf(
            ParagraphBlock(
              id = "p1",
              text = "A😀B",
              spans =
                listOf(
                  FormatSpan(0, 2, TextFormatStyle.BOLD),
                  FormatSpan(2, 4, TextFormatStyle.UNDERLINE),
                ),
            )
          )
      )
    val engine = NoteEditorEngine(legacyDoc)

    // Carga inicial preserva documento legado exatamente como veio (sem sujar estado ao abrir)
    assertEquals(legacyDoc, engine.state.document)

    // Seleção parcial atingindo só a primeira metade (1..2) ou só a segunda metade (2..3) expande para 1..3
    engine.selectBlock("p1", start = 1, end = 2)
    assertEquals(BlockSelection("p1", 1, 3), engine.state.selection)
    engine.selectBlock("p1", start = 2, end = 3)
    assertEquals(BlockSelection("p1", 1, 3), engine.state.selection)

    // Aplicar itálico sobre a seleção do emoji gera edição real e sana os spans legados partidos
    engine.applyFormat(TextFormatStyle.ITALIC)
    val healed = engine.state.focusedBlock!!
    assertValidUtf16(healed.text)
    assertEquals(
      listOf(
        FormatSpan(0, 3, TextFormatStyle.BOLD),
        FormatSpan(1, 3, TextFormatStyle.ITALIC),
        FormatSpan(1, 4, TextFormatStyle.UNDERLINE),
      ),
      healed.spans,
    )

    // Seleção completa do emoji e exclusão por Backspace preservam texto restante e spans deslocados
    engine.selectBlock("p1", start = 1, end = 3)
    engine.pressBackspace()
    val deleted = engine.state.focusedBlock!!
    assertValidUtf16(deleted.text)
    assertEquals("AB", deleted.text)
    assertEquals(BlockSelection("p1", 1, 1), engine.state.selection)
    assertEquals(
      listOf(
        FormatSpan(0, 1, TextFormatStyle.BOLD),
        FormatSpan(1, 2, TextFormatStyle.UNDERLINE),
      ),
      deleted.spans,
    )
  }

  @Test
  fun `ime applyTextChange handles emoji replacements insertions and deletions preserving unicode and spans`() {
    val doc =
      NoteContentDocument(
        blocks =
          listOf(
            ParagraphBlock(
              id = "p1",
              text = "A😀B",
              spans =
                listOf(
                  FormatSpan(0, 1, TextFormatStyle.BOLD),
                  FormatSpan(1, 3, TextFormatStyle.ITALIC),
                  FormatSpan(3, 4, TextFormatStyle.UNDERLINE),
                ),
            )
          )
      )
    val engine = NoteEditorEngine(doc)

    // Troca "😀" (\uD83D\uDE00) por "😁" (\uD83D\uDE01) (mesmo high surrogate)
    engine.selectBlock("p1", start = 1, end = 3)
    engine.applyTextChange(blockId = "p1", newText = "A😁B", selectionStart = 3)
    var block = engine.state.focusedBlock!!
    assertValidUtf16(block.text)
    assertEquals("A😁B", block.text)
    assertEquals(
      listOf(
        FormatSpan(0, 1, TextFormatStyle.BOLD),
        FormatSpan(1, 3, TextFormatStyle.ITALIC),
        FormatSpan(3, 4, TextFormatStyle.UNDERLINE),
      ),
      block.spans,
    )

    // Troca "😁" por "🐀" (\uD83D\uDC00) e depois por "🀀" (\uD83C\uDC00, mesmo low surrogate \uDC00)
    engine.applyTextChange(blockId = "p1", newText = "A🐀B", selectionStart = 3)
    assertValidUtf16(engine.state.focusedBlock!!.text)
    engine.selectBlock("p1", start = 1, end = 3)
    engine.applyTextChange(blockId = "p1", newText = "A🀀B", selectionStart = 3)
    block = engine.state.focusedBlock!!
    assertValidUtf16(block.text)
    assertEquals("A🀀B", block.text)
    assertEquals(
      listOf(
        FormatSpan(0, 1, TextFormatStyle.BOLD),
        FormatSpan(1, 3, TextFormatStyle.ITALIC),
        FormatSpan(3, 4, TextFormatStyle.UNDERLINE),
      ),
      block.spans,
    )

    // Inserção de emoji antes de outro e remoção via IME (inclusive com seleção enviada no meio do par)
    engine.applyTextChange(blockId = "p1", newText = "A😀🀀B", selectionStart = 2)
    block = engine.state.focusedBlock!!
    assertValidUtf16(block.text)
    assertEquals("A😀🀀B", block.text)
    // selectionStart=2 cairia no meio de "😀" (1..3); normalização alinha para 3
    assertEquals(BlockSelection("p1", 3, 3), engine.state.selection)

    // Remoção de "😀" antes de "🀀" via IME
    engine.applyTextChange(blockId = "p1", newText = "A🀀B", selectionStart = 1)
    block = engine.state.focusedBlock!!
    assertValidUtf16(block.text)
    assertEquals("A🀀B", block.text)
    assertEquals(BlockSelection("p1", 1, 1), engine.state.selection)

    // Inserção e remoção de emoji adjacente com mesmo high surrogate ("😀😁" -> "😁")
    engine.applyTextChange(blockId = "p1", newText = "😀😁", selectionStart = 4)
    assertValidUtf16(engine.state.focusedBlock!!.text)
    engine.applyTextChange(blockId = "p1", newText = "😁", selectionStart = 0)
    assertValidUtf16(engine.state.focusedBlock!!.text)
    assertEquals("😁", engine.state.focusedBlock!!.text)
  }

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
}
