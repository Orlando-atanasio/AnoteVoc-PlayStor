package com.example.domain.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderNameNormalizerTest {

  @Test
  fun `normalizeKey ignores case and removes all whitespace including unicode spaces while preserving accents`() {
    assertEquals(
      FolderNameNormalizer.normalizeKey("Meu Trabalho"),
      FolderNameNormalizer.normalizeKey("  meu   TRABALHO  "),
    )
    assertEquals(
      FolderNameNormalizer.normalizeKey("MeuTrabalho"),
      FolderNameNormalizer.normalizeKey("Meu\tTra\nbalho\r\n"),
    )
    // Espaços Unicode: NBSP (\u00A0), NNBSP (\u202F), EM SPACE (\u2003), THIN SPACE (\u2009), IDEOGRAPHIC (\u3000)
    assertEquals(
      FolderNameNormalizer.normalizeKey("Meu Trabalho"),
      FolderNameNormalizer.normalizeKey("\u00A0Meu\u00A0Trabalho\u202F"),
    )
    assertEquals(
      FolderNameNormalizer.normalizeKey("Meu Trabalho"),
      FolderNameNormalizer.normalizeKey("\u2003meu\u2009\t\ntrabalho\u3000"),
    )
    assertEquals("anotação", FolderNameNormalizer.normalizeKey("  Anotação\u00A0 "))
    assertNotEquals(
      FolderNameNormalizer.normalizeKey("Anotação"),
      FolderNameNormalizer.normalizeKey("Anotacao"),
    )
  }

  @Test
  fun `sanitizeDisplayName trims ascii and unicode edges and rejects whitespace-only names`() {
    assertEquals(
      "Minhas Ideias",
      FolderNameNormalizer.sanitizeDisplayName("  \u00A0Minhas Ideias\u2003\t\n "),
    )
    assertEquals(
      "Projetos\u00A02026",
      FolderNameNormalizer.sanitizeDisplayName("\u00A0Projetos\u00A02026\u00A0"),
    )
    assertFalse(FolderNameNormalizer.isValid("   \t\r\n "))
    assertFalse(FolderNameNormalizer.isValid("\u00A0\u202F\u2003\u3000"))
    assertFalse(FolderNameNormalizer.isValid(" \u00A0\t\n "))
    assertTrue(FolderNameNormalizer.isValid("\u00A0Estudo\u00A0"))
  }
}
