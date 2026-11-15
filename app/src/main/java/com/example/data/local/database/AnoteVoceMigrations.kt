package com.example.data.local.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.domain.model.NotePaperStyle

object AnoteVoceMigrations {

  /**
   * Migração v1 -> v2:
   * - Lê `app_preferences.default_note_paper`, valida contra [NotePaperStyle] (fallback `LINHAS`
   *   apenas se ausente ou inválido) sem alterar a tabela `app_preferences`;
   * - Adiciona `paper_style` e `revision` em `notes` e aplica o papel global vigente a todas as
   *   notas preexistentes;
   * - Cria a tabela `note_recovery_drafts` e seus índices sem chaves estrangeiras em cascata.
   */
  val MIGRATION_1_2: Migration =
    object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        val resolvedGlobalPaper = resolveValidGlobalPaper(db)

        db.execSQL(
          "ALTER TABLE `notes` ADD COLUMN `paper_style` TEXT NOT NULL DEFAULT 'LINHAS'"
        )
        db.execSQL(
          "ALTER TABLE `notes` ADD COLUMN `revision` INTEGER NOT NULL DEFAULT 1"
        )
        db.execSQL(
          "UPDATE `notes` SET `paper_style` = ?",
          arrayOf(resolvedGlobalPaper),
        )

        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `note_recovery_drafts` (
            `draft_id` TEXT NOT NULL,
            `target_note_id` INTEGER,
            `session_id` TEXT NOT NULL,
            `folder_id` INTEGER,
            `is_pinned` INTEGER NOT NULL DEFAULT 0,
            `title` TEXT NOT NULL,
            `content_json` TEXT NOT NULL,
            `paper_style` TEXT NOT NULL,
            `base_revision` INTEGER NOT NULL DEFAULT 0,
            `checkpoint_revision` INTEGER NOT NULL DEFAULT 1,
            `base_note_updated_at` INTEGER,
            `checkpoint_at` INTEGER NOT NULL,
            PRIMARY KEY(`draft_id`)
          )
          """.trimIndent()
        )
        db.execSQL(
          "CREATE UNIQUE INDEX IF NOT EXISTS `index_note_recovery_drafts_session_id` ON `note_recovery_drafts` (`session_id`)"
        )
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_note_recovery_drafts_target_note_id` ON `note_recovery_drafts` (`target_note_id`)"
        )
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_note_recovery_drafts_checkpoint_at` ON `note_recovery_drafts` (`checkpoint_at`)"
        )
      }
    }

  /**
   * Migração v2 -> v3:
   * - Acrescenta `initial_folder_id` e `initial_paper_style` como colunas anuláveis em
   *   `note_recovery_drafts`, preservando os registros legados da v2 com `NULL` (sem
   *   preenchimento fictício nem cópia de `folder_id` / `paper_style`).
   */
  val MIGRATION_2_3: Migration =
    object : Migration(2, 3) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "ALTER TABLE `note_recovery_drafts` ADD COLUMN `initial_folder_id` INTEGER"
        )
        db.execSQL(
          "ALTER TABLE `note_recovery_drafts` ADD COLUMN `initial_paper_style` TEXT"
        )
      }
    }

  /**
   * Migração v3 -> v4:
   * - Acrescenta `fallback_source_folder_id` como coluna anulável em
   *   `note_recovery_drafts`, preservando integralmente os registros existentes com `NULL`
   *   (sem preenchimento automático a partir de `folder_id`, `initial_folder_id` ou outro campo).
   */
  val MIGRATION_3_4: Migration =
    object : Migration(3, 4) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "ALTER TABLE `note_recovery_drafts` ADD COLUMN `fallback_source_folder_id` INTEGER"
        )
      }
    }

  internal fun resolveValidGlobalPaper(db: SupportSQLiteDatabase): String {
    db.query("SELECT `default_note_paper` FROM `app_preferences` WHERE `id` = 1 LIMIT 1").use {
      cursor ->
      if (cursor.moveToFirst() && !cursor.isNull(0)) {
        val rawPaper = cursor.getString(0)?.trim()
        val matched = NotePaperStyle.entries.firstOrNull { it.name == rawPaper }
        if (matched != null) {
          return matched.name
        }
      }
    }
    return NotePaperStyle.LINHAS.name
  }
}
