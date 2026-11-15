package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.core.coroutines.DispatcherProvider
import com.example.core.serialization.AnoteVoceJson
import com.example.core.time.ClockProvider
import com.example.data.local.database.AnoteVoceDatabase
import com.example.data.local.entity.NoteEntity
import com.example.data.repository.NoteRepositoryImpl
import com.example.data.repository.PreferencesRepositoryImpl
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NoteContentState
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.ParagraphBlock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotePaperAndMigrationTest {

  private var fakeTimeMillis = 1_700_000_000_000L

  private val testClock =
    object : ClockProvider {
      override fun nowMillis(): Long = ++fakeTimeMillis
    }

  private val testDispatcher = UnconfinedTestDispatcher()
  private val testDispatchers =
    object : DispatcherProvider {
      override val main: CoroutineDispatcher = testDispatcher
      override val io: CoroutineDispatcher = testDispatcher
      override val default: CoroutineDispatcher = testDispatcher
    }

  @Test
  fun `room schema v2 json is exported and accessible in test resources`() {
    val schemaPath = "${AnoteVoceDatabase::class.java.name}/2.json"
    val schemaStream =
      requireNotNull(javaClass.classLoader?.getResourceAsStream(schemaPath)) {
        "Schema JSON v2 não encontrado em $schemaPath"
      }
    val schemaJson = schemaStream.bufferedReader().use { it.readText() }
    assertTrue(schemaJson.contains("\"version\": 2"))
    assertTrue(schemaJson.contains("\"columnName\": \"paper_style\""))
    assertTrue(schemaJson.contains("\"columnName\": \"revision\""))
    assertTrue(schemaJson.contains("\"tableName\": \"note_recovery_drafts\""))
  }

  @Test
  fun `migration 1 to 2 inherits existing global paper style and preserves all legacy data`() =
    runTest {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val dbName = "migration_inherit_global_paper.db"
      context.deleteDatabase(dbName)

      val validJson =
        """{"schemaVersion":1,"blocks":[{"type":"paragraph","id":"p1","text":"Conteúdo preservado","spans":[]}]}"""
      val futureJson =
        """{"schemaVersion":99,"blocks":[{"type":"paragraph","id":"f1","text":"Futuro","spans":[]}]}"""

      createV1DatabaseFromExportedSchema(context, dbName) { dbV1 ->
        dbV1.execSQL(
          """
          INSERT INTO app_preferences (id, theme_mode, default_note_paper, home_view_mode, updated_at)
          VALUES (1, 'MODO_NATURAL', 'ELEGANCE_NOIR', 'GRADE', 1000)
          """.trimIndent()
        )
        dbV1.execSQL(
          """
          INSERT INTO folders (id, uid, name, normalized_name, active_name_key, is_deleted, deleted_at, created_at, updated_at)
          VALUES (10, 'folder-uid-10', 'Projetos', 'projetos', 'projetos', 0, NULL, 100, 200)
          """.trimIndent()
        )
        dbV1.execSQL(
          """
          INSERT INTO notes (
            id, uid, folder_id, title, content_json, plain_text_preview,
            is_pinned, pinned_at, is_deleted, deleted_at, deleted_with_folder, created_at, updated_at
          ) VALUES (
            101, 'note-uid-101', 10, 'Nota Fixada', ?, 'Conteúdo preservado',
            1, 350, 0, NULL, 0, 300, 400
          )
          """.trimIndent(),
          arrayOf(validJson),
        )
        dbV1.execSQL(
          """
          INSERT INTO notes (
            id, uid, folder_id, title, content_json, plain_text_preview,
            is_pinned, pinned_at, is_deleted, deleted_at, deleted_with_folder, created_at, updated_at
          ) VALUES (
            102, 'note-uid-102', NULL, 'Nota Apagada', ?, 'Futuro',
            0, NULL, 1, 550, 0, 500, 550
          )
          """.trimIndent(),
          arrayOf(futureJson),
        )
      }

      val migratedDb =
        Room.databaseBuilder(context, AnoteVoceDatabase::class.java, dbName)
          .addMigrations(*AnoteVoceDatabase.ALL_MIGRATIONS)
          .allowMainThreadQueries()
          .build()

      try {
        // Abre a conexão pelo Room para disparar MIGRATION_1_2 -> MIGRATION_2_3 e validar o schema atual.
        val writableDb = migratedDb.openHelper.writableDatabase
        assertEquals(LocalStorageSpec.DATABASE_VERSION, writableDb.version)

        writableDb
          .query(
            "SELECT default_note_paper, theme_mode, home_view_mode, updated_at FROM app_preferences WHERE id = 1"
          )
          .use { prefCursor ->
            assertTrue(prefCursor.moveToFirst())
            assertEquals("ELEGANCE_NOIR", prefCursor.getString(0))
            assertEquals("MODO_NATURAL", prefCursor.getString(1))
            assertEquals("GRADE", prefCursor.getString(2))
            assertEquals(1000L, prefCursor.getLong(3))
          }

        val note101 = migratedDb.noteDao().getById(101L)!!
        assertEquals("note-uid-101", note101.uid)
        assertEquals(10L, note101.folderId)
        assertEquals("Nota Fixada", note101.title)
        assertEquals(validJson, note101.contentJson)
        assertEquals("Conteúdo preservado", note101.plainTextPreview)
        assertEquals("ELEGANCE_NOIR", note101.paperStyle)
        assertEquals(1L, note101.revision)
        assertTrue(note101.isPinned)
        assertEquals(350L, note101.pinnedAt)
        assertFalse(note101.isDeleted)
        assertEquals(300L, note101.createdAt)
        assertEquals(400L, note101.updatedAt)

        val note102 = migratedDb.noteDao().getById(102L)!!
        assertEquals("note-uid-102", note102.uid)
        assertEquals(null, note102.folderId)
        assertEquals("Nota Apagada", note102.title)
        assertEquals(futureJson, note102.contentJson)
        assertEquals("ELEGANCE_NOIR", note102.paperStyle)
        assertEquals(1L, note102.revision)
        assertTrue(note102.isDeleted)
        assertEquals(550L, note102.deletedAt)

        val folder10 = migratedDb.folderDao().getById(10L)!!
        assertEquals("Projetos", folder10.name)

        // Garante que a nova tabela note_recovery_drafts está operacional após a migração
        assertTrue(migratedDb.noteRecoveryDraftDao().getAllCheckpoints().isEmpty())
      } finally {
        migratedDb.close()
        context.deleteDatabase(dbName)
      }
    }

  @Test
  fun `migration 1 to 2 falls back to LINHAS when app_preferences is absent`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dbName = "migration_absent_preferences.db"
    context.deleteDatabase(dbName)

    val validJson =
      """{"schemaVersion":1,"blocks":[{"type":"paragraph","id":"p1","text":"Sem prefs","spans":[]}]}"""

    createV1DatabaseFromExportedSchema(context, dbName) { dbV1 ->
      dbV1.execSQL(
        """
        INSERT INTO notes (
          id, uid, folder_id, title, content_json, plain_text_preview,
          is_pinned, pinned_at, is_deleted, deleted_at, deleted_with_folder, created_at, updated_at
        ) VALUES (
          1, 'note-uid-1', NULL, 'Antiga', ?, 'Sem prefs',
          0, NULL, 0, NULL, 0, 10, 20
        )
        """.trimIndent(),
        arrayOf(validJson),
      )
    }

    val migratedDb =
      Room.databaseBuilder(context, AnoteVoceDatabase::class.java, dbName)
        .addMigrations(*AnoteVoceDatabase.ALL_MIGRATIONS)
        .allowMainThreadQueries()
        .build()

    try {
      val writableDb = migratedDb.openHelper.writableDatabase
      assertEquals(LocalStorageSpec.DATABASE_VERSION, writableDb.version)

      val migratedNote = migratedDb.noteDao().getById(1L)!!
      assertEquals("LINHAS", migratedNote.paperStyle)
      assertEquals("Antiga", migratedNote.title)
      assertEquals(validJson, migratedNote.contentJson)
      assertEquals(null, migratedDb.appPreferencesDao().getPreferences())
    } finally {
      migratedDb.close()
      context.deleteDatabase(dbName)
    }
  }

  @Test
  fun `migration 1 to 2 falls back to LINHAS when app_preferences has invalid paper value and preserves global row`() =
    runTest {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val dbName = "migration_invalid_paper.db"
      context.deleteDatabase(dbName)

      val validJson =
        """{"schemaVersion":1,"blocks":[{"type":"paragraph","id":"p1","text":"Com papel inválido","spans":[]}]}"""

      createV1DatabaseFromExportedSchema(context, dbName) { dbV1 ->
        dbV1.execSQL(
          """
          INSERT INTO app_preferences (id, theme_mode, default_note_paper, home_view_mode, updated_at)
          VALUES (1, 'MODO_DIA', 'ESTILO_DESCONHECIDO_V9', 'LISTA', 500)
          """.trimIndent()
        )
        dbV1.execSQL(
          """
          INSERT INTO notes (
            id, uid, folder_id, title, content_json, plain_text_preview,
            is_pinned, pinned_at, is_deleted, deleted_at, deleted_with_folder, created_at, updated_at
          ) VALUES (
            5, 'note-uid-5', NULL, 'Legada', ?, 'Com papel inválido',
            0, NULL, 0, NULL, 0, 50, 60
          )
          """.trimIndent(),
          arrayOf(validJson),
        )
      }

      val migratedDb =
        Room.databaseBuilder(context, AnoteVoceDatabase::class.java, dbName)
          .addMigrations(*AnoteVoceDatabase.ALL_MIGRATIONS)
          .allowMainThreadQueries()
          .build()

      try {
        val writableDb = migratedDb.openHelper.writableDatabase
        assertEquals(LocalStorageSpec.DATABASE_VERSION, writableDb.version)

        val migratedNote = migratedDb.noteDao().getById(5L)!!
        assertEquals("LINHAS", migratedNote.paperStyle)
        assertEquals("Legada", migratedNote.title)
        assertEquals(validJson, migratedNote.contentJson)

        val rawGlobalPrefs = migratedDb.appPreferencesDao().getPreferences()!!
        assertEquals("ESTILO_DESCONHECIDO_V9", rawGlobalPrefs.defaultNotePaper)
      } finally {
        migratedDb.close()
        context.deleteDatabase(dbName)
      }
    }

  @Test
  fun `notes persist individual paper styles without mutating global default preference`() =
    runTest {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val db =
        Room.inMemoryDatabaseBuilder(context, AnoteVoceDatabase::class.java)
          .allowMainThreadQueries()
          .build()
      try {
        val preferencesRepo =
          PreferencesRepositoryImpl(
            database = db,
            appPreferencesDao = db.appPreferencesDao(),
            dispatchers = testDispatchers,
            clock = testClock,
          )
        val noteRepo =
          NoteRepositoryImpl(
            database = db,
            noteDao = db.noteDao(),
            folderDao = db.folderDao(),
            dispatchers = testDispatchers,
            clock = testClock,
            appPreferencesDao = db.appPreferencesDao(),
            noteRecoveryDraftDao = db.noteRecoveryDraftDao(),
          )

        preferencesRepo.updateDefaultNotePaper(NotePaperStyle.OCEAN_BREEZE)

        val inheritedNote = noteRepo.createNote(title = "Herdou padrão")
        assertEquals(NotePaperStyle.OCEAN_BREEZE, inheritedNote.paperStyle)

        val customPaperNote =
          noteRepo.createNote(
            title = "Papel próprio",
            paperStyle = NotePaperStyle.TECH_DARK,
          )
        assertEquals(NotePaperStyle.TECH_DARK, customPaperNote.paperStyle)

        // Alterar o papel de uma nota não modifica app_preferences.default_note_paper
        val updatedPaperNote =
          noteRepo.updateNotePaperStyle(inheritedNote.id, NotePaperStyle.SUNSET_WARMTH)
        assertEquals(NotePaperStyle.SUNSET_WARMTH, updatedPaperNote.paperStyle)
        assertEquals(inheritedNote.revision + 1L, updatedPaperNote.revision)
        assertEquals(NotePaperStyle.OCEAN_BREEZE, preferencesRepo.getPreferences().defaultNotePaper)

        // Verifica todos os 9 valores válidos de NotePaperStyle persistindo e mapeando corretamente
        for (style in NotePaperStyle.entries) {
          val n =
            noteRepo.createNote(
              title = style.labelPtBr,
              content =
                NoteContentDocument(blocks = listOf(ParagraphBlock(id = "b1", text = style.name))),
              paperStyle = style,
            )
          val reloaded = noteRepo.getNoteById(n.id)
          assertNotNull(reloaded)
          assertEquals(style, reloaded!!.paperStyle)
        }

        // Registro com valor de papel desconhecido faz fallback seguro para LINHAS sem quebrar a leitura
        val unknownId =
          db.noteDao().insert(
            NoteEntity(
              uid = "u-unknown-paper",
              title = "Papel futuro",
              contentJson =
                """{"schemaVersion":1,"blocks":[{"type":"paragraph","id":"p1","text":"Ok","spans":[]}]}""",
              plainTextPreview = "Ok",
              paperStyle = "FUTURO_PAPEL",
              createdAt = 1L,
              updatedAt = 1L,
            )
          )
        val safeLoaded = noteRepo.getNoteById(unknownId)!!
        assertEquals(NotePaperStyle.LINHAS, safeLoaded.paperStyle)
        assertTrue(safeLoaded.contentState is NoteContentState.Valid)

        // Confere que a preferência global permaneceu intocada ao longo de todas as alterações individuais
        assertEquals(NotePaperStyle.OCEAN_BREEZE, preferencesRepo.getPreferences().defaultNotePaper)
      } finally {
        db.close()
      }
    }

  private fun createV1DatabaseFromExportedSchema(
    context: Context,
    dbName: String,
    seed: (SupportSQLiteDatabase) -> Unit,
  ) {
    val schemaPath = "${AnoteVoceDatabase::class.java.name}/1.json"
    val schemaText =
      requireNotNull(javaClass.classLoader?.getResourceAsStream(schemaPath)) {
        "Schema JSON v1 não encontrado em $schemaPath"
      }
        .bufferedReader()
        .use { it.readText() }

    val root = AnoteVoceJson.instance.parseToJsonElement(schemaText).jsonObject
    val dbObj = root.getValue("database").jsonObject
    val entities = dbObj.getValue("entities").jsonArray
    val setupQueries = dbObj.getValue("setupQueries").jsonArray

    val callback =
      object : SupportSQLiteOpenHelper.Callback(1) {
        override fun onCreate(db: SupportSQLiteDatabase) {
          for (entityElem in entities) {
            val entityObj = entityElem.jsonObject
            val tableName = entityObj.getValue("tableName").jsonPrimitive.content
            val createSql =
              entityObj.getValue("createSql").jsonPrimitive.content.replace(
                "\${TABLE_NAME}",
                tableName,
              )
            db.execSQL(createSql)

            val indices = entityObj["indices"]?.jsonArray.orEmpty()
            for (indexElem in indices) {
              val indexCreateSql =
                indexElem.jsonObject
                  .getValue("createSql")
                  .jsonPrimitive
                  .content
                  .replace("\${TABLE_NAME}", tableName)
              db.execSQL(indexCreateSql)
            }
          }
          for (setupElem in setupQueries) {
            db.execSQL(setupElem.jsonPrimitive.content)
          }
          seed(db)
        }

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
      }

    val config =
      SupportSQLiteOpenHelper.Configuration.builder(context)
        .name(dbName)
        .callback(callback)
        .build()

    FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
      val db = helper.writableDatabase
      assertEquals(1, db.version)
    }
  }
}
