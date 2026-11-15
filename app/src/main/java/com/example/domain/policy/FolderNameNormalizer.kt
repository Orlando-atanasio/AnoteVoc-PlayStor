package com.example.domain.policy

import java.util.Locale

/**
 * Normaliza nomes de pastas personalizadas segundo a regra aprovada:
 * - Ignora diferenças entre maiúsculas e minúsculas (pt-BR);
 * - Remove todos os espaços em branco na chave de comparação, incluindo espaços Unicode (ex.: NBSP);
 * - Preserva acentos (ex.: "Anotação" != "Anotacao");
 * - Preserva o nome original tratado (trim) para exibição.
 */
object FolderNameNormalizer {
  private val PT_BR_LOCALE: Locale = Locale.forLanguageTag("pt-BR")

  fun sanitizeDisplayName(rawName: String): String = rawName.trim(::isUnicodeWhitespace)

  fun normalizeKey(rawName: String): String =
    buildString(rawName.length) {
        var index = 0
        while (index < rawName.length) {
          val codePoint = rawName.codePointAt(index)
          if (!isUnicodeWhitespace(codePoint)) {
            appendCodePoint(codePoint)
          }
          index += Character.charCount(codePoint)
        }
      }
      .lowercase(PT_BR_LOCALE)

  fun isValid(rawName: String): Boolean = normalizeKey(rawName).isNotEmpty()

  private fun isUnicodeWhitespace(ch: Char): Boolean =
    ch.isWhitespace() || Character.isSpaceChar(ch) || ch == '\u0085'

  private fun isUnicodeWhitespace(codePoint: Int): Boolean =
    Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0x0085
}
