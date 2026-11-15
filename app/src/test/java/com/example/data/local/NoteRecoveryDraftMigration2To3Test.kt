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
import com.example.data.local.entity.NoteRecoveryDraftEntity
import com.example.data.repository.FolderRepositoryImpl
import com.example.data.repository.NoteRecoveryDraftRepositoryImpl
import com.example.data.repository.NoteRepositoryImpl
import com.example.domain.model.NoteContentDocument
import com.example.domain.model.NotePaperStyle
import com.example.domain.model.NoteRecoveryDraftInput
import com.example.domain.model.NoteRecoveryDraftKeys
import com.example.domain.model.ParagraphBlock
import com.example.domain.model.SaveCheckpointResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NoteRecoveryDraftMigration2To3Test {

  private var fakeTimeMillis = 1_900_000_000_000L

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
  fun `room schema v3 json is exported with nullable initial_folder_id and initial_paper_style`() {
    val schemaPath = "${AnoteVoceDatabase::class.java.name}/3.json"
    val schemaStream =
      requireNotNull(javaClass.classLoader?.getResourceAsStream(schemaPath)) {
        "Schema JSON v3 não encontrado em $schemaPath"
      }
    val schemaText = schemaStream.bufferedReader().use { it.readText() }
    val root = AnoteVoceJson.instance.parseToJsonElement(schemaText).jsonObject
    val dbObj = root.getValue("database").jsonObject
    assertEquals(3, dbObj.getValue("version").jsonPrimitive.content.toInt())

    val draftsEntity =
      dbObj
        .getValue("entities")
        .jsonArray
        .map { it.jsonObject }
        .first { it.getValue("tableName").jsonPrimitive.content == "note_recovery_drafts" }

    val fieldsByColumn =
      draftsEntity
        .getValue("fields")
        .jsonArray
        .map { it.jsonObject }
        .associateBy { it.getValue("columnName").jsonPrimitive.content }

    val initialFolderField = requireNotNull(fieldsByColumn["initial_folder_id"])
    assertEquals("INTEGER", initialFolderField.getValue("affinity").jsonPrimitive.content)
    assertFalse(initialFolderField["notNull"]?.jsonPrimitive?.booleanOrNull == true)
    assertNull(initialFolderField["defaultValue"])

    val initialPaperField = requireNotNull(fieldsByColumn["initial_paper_style"])
    assertEquals("TEXT", initialPaperField.getValue("affinity").jsonPrimitive.content)
    assertFalse(initialPaperField["notNull"]?.jsonPrimitive?.booleanOrNull == true)
    assertNull(initialPaperField["defaultValue"])
  }

  @Test
  fun `migration 2 to 3 preserves all v2 drafts and keeps new initial columns NULL`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dbName = "migration_v2_to_v3_drafts.db"
    context.deleteDatabase(dbName)

    val newDraftJson =
      """{"schemaVersion":1,"blocks":[{"type":"paragraph","id":"p1","text":"Rascunho v2 sem salvar","spans":[]}]}"""
    val existingDraftJson =
      """{"schemaVersion":1,"blocks":[{"type":"paragraph","id":"p2","text":"Edição pendente v2","spans":[]}]}"""
    val noteJson =
      """{"schemaVersion":1,"blocks":[{"type":"paragraph","id":"p0","text":"Nota base v2","spans":[]}]}"""

    createDatabaseFromExportedSchema(context = context, dbName = dbName, version = 2) { dbV2 ->
      dbV2.execSQL(
        """
        INSERT INTO folders (id, uid, name, normalized_name, active_name_key, is_deleted, deleted_at, created_at, updated_at)
        VALUES (10, 'folder-v2-10', 'Trabalho', 'trabalho', 'trabalho', 0, NULL, 100, 100)
        """.trimIndent()
      )
      dbV2.execSQL(
        """
        INSERT INTO notes (
          id, uid, folder_id, title, content_json, plain_text_preview, paper_style, revision,
          is_pinned, pinned_at, is_deleted, deleted_at, deleted_with_folder, created_at, updated_at
        ) VALUES (
          101, 'note-v2-101', 10, 'Nota existente', ?, 'Nota base v2', 'LINHAS', 2,
          0, NULL, 0, NULL, 0, 200, 300
        )
        """.trimIndent(),
        arrayOf(noteJson),
      )
      dbV2.execSQL(
        """
        INSERT INTO note_recovery_drafts (
          draft_id, target_note_id, session_id, folder_id, is_pinned, title, content_json,
          paper_style, base_revision, checkpoint_revision, base_note_updated_at, checkpoint_at
        ) VALUES (
          'new_legacy-s1', NULL, 'legacy-s1', 10, 1, 'Nova nota v2', ?,
          'OCEAN_BREEZE', 0, 4, NULL, 5000
        )
        """.trimIndent(),
        arrayOf(newDraftJson),
      )
      dbV2.execSQL(
        """
        INSERT INTO note_recovery_drafts (
          draft_id, target_note_id, session_id, folder_id, is_pinned, title, content_json,
          paper_style, base_revision, checkpoint_revision, base_note_updated_at, checkpoint_at
        ) VALUES (
          'note_101_legacy-s2', 101, 'legacy-s2', 10, 0, 'Nota editada v2', ?,
          'TECH_DARK', 2, 3, 300, 6000
        )
        """.trimIndent(),
        arrayOf(existingDraftJson),
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

      writableDb
        .query(
          "SELECT draft_id, initial_folder_id, initial_paper_style FROM note_recovery_drafts ORDER BY checkpoint_at ASC"
        )
        .use { cursor ->
          assertTrue(cursor.moveToFirst())
          assertEquals("new_legacy-s1", cursor.getString(0))
          assertTrue("initial_folder_id legado deve permanecer NULL", cursor.isNull(1))
          assertTrue("initial_paper_style legado deve permanecer NULL", cursor.isNull(2))

          assertTrue(cursor.moveToNext())
          assertEquals("note_101_legacy-s2", cursor.getString(0))
          assertTrue("initial_folder_id legado deve permanecer NULL", cursor.isNull(1))
          assertTrue("initial_paper_style legado deve permanecer NULL", cursor.isNull(2))
          assertFalse(cursor.moveToNext())
        }

      val draftRepository =
        NoteRecoveryDraftRepositoryImpl(
          database = migratedDb,
          draftDao = migratedDb.noteRecoveryDraftDao(),
          dispatchers = testDispatchers,
          clock = testClock,
          noteDao = migratedDb.noteDao(),
        )

      val legacyNewDraft = draftRepository.getCheckpointById("new_legacy-s1")!!
      assertNull(legacyNewDraft.targetNoteId)
      assertEquals("legacy-s1", legacyNewDraft.sessionId)
      assertEquals(10L, legacyNewDraft.folderId)
      assertNull(legacyNewDraft.initialFolderId)
      assertTrue(legacyNewDraft.isPinned)
      assertEquals("Nova nota v2", legacyNewDraft.title)
      assertEquals("Rascunho v2 sem salvar", legacyNewDraft.contentOrNull!!.blocks.first().text)
      assertEquals(NotePaperStyle.OCEAN_BREEZE, legacyNewDraft.paperStyle)
      assertNull(legacyNewDraft.initialPaperStyle)
      assertEquals(0L, legacyNewDraft.baseRevision)
      assertEquals(4L, legacyNewDraft.checkpointRevision)
      assertNull(legacyNewDraft.baseNoteUpdatedAt)
      assertEquals(5000L, legacyNewDraft.checkpointAt)

      val legacyExistingDraft = draftRepository.getCheckpointById("note_101_legacy-s2")!!
      assertEquals(101L, legacyExistingDraft.targetNoteId)
      assertEquals(10L, legacyExistingDraft.folderId)
      assertNull(legacyExistingDraft.initialFolderId)
      assertEquals(NotePaperStyle.TECH_DARK, legacyExistingDraft.paperStyle)
      assertNull(legacyExistingDraft.initialPaperStyle)
      assertEquals(2L, legacyExistingDraft.baseRevision)
      assertEquals(3L, legacyExistingDraft.checkpointRevision)
      assertEquals(300L, legacyExistingDraft.baseNoteUpdatedAt)
    } finally {
      migratedDb.close()
      context.deleteDatabase(dbName)
    }
  }

  @Test
  fun `migration chain 1 to 2 to 3 preserves legacy v1 data and enables v3 drafts`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dbName = "migration_chain_v1_to_v3.db"
    context.deleteDatabase(dbName)

    val noteJson =
      """{"schemaVersion":1,"blocks":[{"type":"paragraph","id":"p1","text":"Legada v1","spans":[]}]}"""

    createDatabaseFromExportedSchema(context = context, dbName = dbName, version = 1) { dbV1 ->
      dbV1.execSQL(
        """
        INSERT INTO app_preferences (id, theme_mode, default_note_paper, home_view_mode, updated_at)
        VALUES (1, 'MODO_DIA', 'FOREST_MIST', 'LISTA', 500)
        """.trimIndent()
      )
      dbV1.execSQL(
        """
        INSERT INTO folders (id, uid, name, normalized_name, active_name_key, is_deleted, deleted_at, created_at, updated_at)
        VALUES (7, 'folder-v1-7', 'Pessoal', 'pessoal', 'pessoal', 0, NULL, 100, 100)
        """.trimIndent()
      )
      dbV1.execSQL(
        """
        INSERT INTO notes (
          id, uid, folder_id, title, content_json, plain_text_preview,
          is_pinned, pinned_at, is_deleted, deleted_at, deleted_with_folder, created_at, updated_at
        ) VALUES (
          55, 'note-v1-55', 7, 'Nota v1', ?, 'Legada v1',
          0, NULL, 0, NULL, 0, 200, 250
        )
        """.trimIndent(),
        arrayOf(noteJson),
      )
    }

    val migratedDb =
      Room.databaseBuilder(context, AnoteVoceDatabase::class.java, dbName)
        .addMigrations(*AnoteVoceDatabase.ALL_MIGRATIONS)
        .allowMainThreadQueries()
        .build()

    try {
      assertEquals(LocalStorageSpec.DATABASE_VERSION, migratedDb.openHelper.writableDatabase.version)

      val migratedNote = migratedDb.noteDao().getById(55L)!!
      assertEquals("Nota v1", migratedNote.title)
      assertEquals(7L, migratedNote.folderId)
      assertEquals("FOREST_MIST", migratedNote.paperStyle)

      val draftRepository =
        NoteRecoveryDraftRepositoryImpl(
          database = migratedDb,
          draftDao = migratedDb.noteRecoveryDraftDao(),
          dispatchers = testDispatchers,
          clock = testClock,
          noteDao = migratedDb.noteDao(),
        )

      val sessionId = "chain-v3-session"
      val draftId = NoteRecoveryDraftKeys.forNewNote(sessionId)
      val saved =
        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = draftId,
            targetNoteId = null,
            sessionId = sessionId,
            folderId = null,
            initialFolderId = 7L,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Nova pós-migração",
            content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "n1", text = "Texto"))),
            paperStyle = NotePaperStyle.MINIMALIST_PRO,
            initialPaperStyle = NotePaperStyle.FOREST_MIST,
            baseRevision = 0L,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = null,
          )
        )
      assertTrue(saved is SaveCheckpointResult.Saved)
      val loaded = draftRepository.getCheckpointById(draftId)!!
      assertEquals(7L, loaded.initialFolderId)
      assertNull(loaded.folderId)
      assertEquals(NotePaperStyle.FOREST_MIST, loaded.initialPaperStyle)
      assertEquals(NotePaperStyle.MINIMALIST_PRO, loaded.paperStyle)
    } finally {
      migratedDb.close()
      context.deleteDatabase(dbName)
    }
  }

  @Test
  fun `new note checkpoints persist initial metadata while existing note checkpoints force null`() =
    runTest {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val db =
        Room.inMemoryDatabaseBuilder(context, AnoteVoceDatabase::class.java)
          .allowMainThreadQueries()
          .build()

      try {
        val folderRepository =
          FolderRepositoryImpl(
            database = db,
            folderDao = db.folderDao(),
            noteDao = db.noteDao(),
            dispatchers = testDispatchers,
            clock = testClock,
          )
        val noteRepository =
          NoteRepositoryImpl(
            database = db,
            noteDao = db.noteDao(),
            folderDao = db.folderDao(),
            dispatchers = testDispatchers,
            clock = testClock,
            appPreferencesDao = db.appPreferencesDao(),
            noteRecoveryDraftDao = db.noteRecoveryDraftDao(),
          )
        val draftRepository =
          NoteRecoveryDraftRepositoryImpl(
            database = db,
            draftDao = db.noteRecoveryDraftDao(),
            dispatchers = testDispatchers,
            clock = testClock,
            noteDao = db.noteDao(),
          )

        val folderInitial = folderRepository.createFolder("Origem")
        val folderChanged = folderRepository.createFolder("Destino")

        val newSession = "new-v3-session"
        val newDraftId = NoteRecoveryDraftKeys.forNewNote(newSession)
        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = newDraftId,
            targetNoteId = null,
            sessionId = newSession,
            folderId = folderChanged.id,
            initialFolderId = folderInitial.id,
            fallbackSourceFolderId = null,
            isPinned = true,
            title = "Nova editada",
            content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "b1", text = "Corpo"))),
            paperStyle = NotePaperStyle.SUNSET_WARMTH,
            initialPaperStyle = NotePaperStyle.PONTILHADO,
            baseRevision = 0L,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = null,
          )
        )

        val reloadedNew = draftRepository.getCheckpointById(newDraftId)!!
        assertEquals(folderChanged.id, reloadedNew.folderId)
        assertEquals(folderInitial.id, reloadedNew.initialFolderId)
        assertEquals(NotePaperStyle.SUNSET_WARMTH, reloadedNew.paperStyle)
        assertEquals(NotePaperStyle.PONTILHADO, reloadedNew.initialPaperStyle)

        val existingNote =
          noteRepository.createNote(
            title = "Nota já salva",
            folderId = folderInitial.id,
            paperStyle = NotePaperStyle.LINHAS,
          )
        val existingSession = "existing-v3-session"
        val existingDraftId = NoteRecoveryDraftKeys.forExistingNote(existingNote.id, existingSession)

        // Mesmo que o chamador informe valores iniciais, checkpoints de nota existente mantêm ambos nulos.
        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = existingDraftId,
            targetNoteId = existingNote.id,
            sessionId = existingSession,
            folderId = folderChanged.id,
            initialFolderId = folderInitial.id,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Nota editada",
            content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "e1", text = "Alterada"))),
            paperStyle = NotePaperStyle.ELEGANCE_NOIR,
            initialPaperStyle = NotePaperStyle.LINHAS,
            baseRevision = existingNote.revision,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = existingNote.updatedAt,
          )
        )

        val reloadedExisting = draftRepository.getCheckpointById(existingDraftId)!!
        assertEquals(existingNote.id, reloadedExisting.targetNoteId)
        assertNull(reloadedExisting.initialFolderId)
        assertNull(reloadedExisting.initialPaperStyle)
        assertEquals(folderChanged.id, reloadedExisting.folderId)
        assertEquals(NotePaperStyle.ELEGANCE_NOIR, reloadedExisting.paperStyle)
      } finally {
        db.close()
      }
    }

  @Test
  fun `unknown or null initial_paper_style maps defensively to null without falling back to LINHAS`() =
    runTest {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val db =
        Room.inMemoryDatabaseBuilder(context, AnoteVoceDatabase::class.java)
          .allowMainThreadQueries()
          .build()

      try {
        val draftDao = db.noteRecoveryDraftDao()
        val draftRepository =
          NoteRecoveryDraftRepositoryImpl(
            database = db,
            draftDao = draftDao,
            dispatchers = testDispatchers,
            clock = testClock,
            noteDao = db.noteDao(),
          )

        val validJson =
          """{"schemaVersion":1,"blocks":[{"type":"paragraph","id":"p1","text":"Seguro","spans":[]}]}"""

        draftDao.insert(
          NoteRecoveryDraftEntity(
            draftId = "new_unknown-initial-paper",
            targetNoteId = null,
            sessionId = "unknown-initial-paper",
            folderId = null,
            initialFolderId = null,
            isPinned = false,
            title = "Desconhecido",
            contentJson = validJson,
            paperStyle = NotePaperStyle.TECH_DARK.name,
            initialPaperStyle = "PAPEL_DESCONHECIDO_V99",
            baseRevision = 0L,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = null,
            checkpointAt = 1000L,
          )
        )
        draftDao.insert(
          NoteRecoveryDraftEntity(
            draftId = "new_null-initial-paper",
            targetNoteId = null,
            sessionId = "null-initial-paper",
            folderId = null,
            initialFolderId = null,
            isPinned = false,
            title = "Legado nulo",
            contentJson = validJson,
            paperStyle = NotePaperStyle.OCEAN_BREEZE.name,
            initialPaperStyle = null,
            baseRevision = 0L,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = null,
            checkpointAt = 1001L,
          )
        )

        val unknownMapped = draftRepository.getCheckpointById("new_unknown-initial-paper")!!
        assertNotNull(unknownMapped)
        assertEquals(NotePaperStyle.TECH_DARK, unknownMapped.paperStyle)
        assertNull(unknownMapped.initialPaperStyle)

        val nullMapped = draftRepository.getCheckpointById("new_null-initial-paper")!!
        assertNotNull(nullMapped)
        assertEquals(NotePaperStyle.OCEAN_BREEZE, nullMapped.paperStyle)
        assertNull(nullMapped.initialPaperStyle)
      } finally {
        db.close()
      }
    }

  private fun createDatabaseFromExportedSchema(
    context: Context,
    dbName: String,
    version: Int,
    seed: (SupportSQLiteDatabase) -> Unit,
  ) {
    val schemaPath = "${AnoteVoceDatabase::class.java.name}/$version.json"
    val schemaText =
      requireNotNull(javaClass.classLoader?.getResourceAsStream(schemaPath)) {
        "Schema JSON v$version não encontrado em $schemaPath"
      }
        .bufferedReader()
        .use { it.readText() }

    val root = AnoteVoceJson.instance.parseToJsonElement(schemaText).jsonObject
    val dbObj = root.getValue("database").jsonObject
    val entities = dbObj.getValue("entities").jsonArray
    val setupQueries = dbObj.getValue("setupQueries").jsonArray

    val callback =
      object : SupportSQLiteOpenHelper.Callback(version) {
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
      assertEquals(version, db.version)
    }
  }
}
