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
import com.example.domain.repository.InvalidRecoveryDraftException
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NoteRecoveryDraftMigration3To4Test {

  private var fakeTimeMillis = 2_000_000_000_000L

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
  fun `room schema v4 json is exported with nullable fallback_source_folder_id`() {
    val schemaPath = "${AnoteVoceDatabase::class.java.name}/4.json"
    val schemaStream =
      requireNotNull(javaClass.classLoader?.getResourceAsStream(schemaPath)) {
        "Schema JSON v4 não encontrado em $schemaPath"
      }
    val schemaText = schemaStream.bufferedReader().use { it.readText() }
    val root = AnoteVoceJson.instance.parseToJsonElement(schemaText).jsonObject
    val dbObj = root.getValue("database").jsonObject
    assertEquals(4, dbObj.getValue("version").jsonPrimitive.content.toInt())

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

    val fallbackField = requireNotNull(fieldsByColumn["fallback_source_folder_id"])
    assertEquals("INTEGER", fallbackField.getValue("affinity").jsonPrimitive.content)
    assertFalse(fallbackField["notNull"]?.jsonPrimitive?.booleanOrNull == true)
    assertNull(fallbackField["defaultValue"])

    assertTrue(fieldsByColumn.containsKey("folder_id"))
    assertTrue(fieldsByColumn.containsKey("initial_folder_id"))
    assertTrue(fieldsByColumn.containsKey("initial_paper_style"))
  }

  @Test
  fun `migration 3 to 4 preserves all v3 drafts and keeps fallback_source_folder_id NULL without backfill`() =
    runTest {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val dbName = "migration_v3_to_v4_drafts.db"
      context.deleteDatabase(dbName)

      val newDraftJson =
        """{"schemaVersion":1,"blocks":[{"type":"paragraph","id":"p1","text":"Rascunho v3 novo","spans":[]}]}"""
      val legacyV2DraftJson =
        """{"schemaVersion":1,"blocks":[{"type":"paragraph","id":"p2","text":"Rascunho legado v2","spans":[]}]}"""
      val existingDraftJson =
        """{"schemaVersion":1,"blocks":[{"type":"paragraph","id":"p3","text":"Edição v3 existente","spans":[]}]}"""
      val noteJson =
        """{"schemaVersion":1,"blocks":[{"type":"paragraph","id":"p0","text":"Nota base v3","spans":[]}]}"""

      createDatabaseFromExportedSchema(context = context, dbName = dbName, version = 3) { dbV3 ->
        dbV3.execSQL(
          """
          INSERT INTO folders (id, uid, name, normalized_name, active_name_key, is_deleted, deleted_at, created_at, updated_at)
          VALUES (10, 'folder-v3-10', 'Trabalho', 'trabalho', 'trabalho', 0, NULL, 100, 100)
          """.trimIndent()
        )
        dbV3.execSQL(
          """
          INSERT INTO folders (id, uid, name, normalized_name, active_name_key, is_deleted, deleted_at, created_at, updated_at)
          VALUES (20, 'folder-v3-20', 'Pessoal', 'pessoal', 'pessoal', 0, NULL, 105, 105)
          """.trimIndent()
        )
        dbV3.execSQL(
          """
          INSERT INTO notes (
            id, uid, folder_id, title, content_json, plain_text_preview, paper_style, revision,
            is_pinned, pinned_at, is_deleted, deleted_at, deleted_with_folder, created_at, updated_at
          ) VALUES (
            101, 'note-v3-101', 10, 'Nota existente v3', ?, 'Nota base v3', 'LINHAS', 2,
            0, NULL, 0, NULL, 0, 200, 300
          )
          """.trimIndent(),
          arrayOf(noteJson),
        )
        dbV3.execSQL(
          """
          INSERT INTO note_recovery_drafts (
            draft_id, target_note_id, session_id, folder_id, initial_folder_id, is_pinned, title,
            content_json, paper_style, initial_paper_style, base_revision, checkpoint_revision,
            base_note_updated_at, checkpoint_at
          ) VALUES (
            'new_v3-s1', NULL, 'v3-s1', 10, 20, 1, 'Nova nota v3', ?,
            'OCEAN_BREEZE', 'FOREST_MIST', 0, 4, NULL, 5000
          )
          """.trimIndent(),
          arrayOf(newDraftJson),
        )
        dbV3.execSQL(
          """
          INSERT INTO note_recovery_drafts (
            draft_id, target_note_id, session_id, folder_id, initial_folder_id, is_pinned, title,
            content_json, paper_style, initial_paper_style, base_revision, checkpoint_revision,
            base_note_updated_at, checkpoint_at
          ) VALUES (
            'new_legacy-v2', NULL, 'legacy-v2', 10, NULL, 0, 'Legado sem iniciais', ?,
            'SUNSET_WARMTH', NULL, 0, 2, NULL, 5500
          )
          """.trimIndent(),
          arrayOf(legacyV2DraftJson),
        )
        dbV3.execSQL(
          """
          INSERT INTO note_recovery_drafts (
            draft_id, target_note_id, session_id, folder_id, initial_folder_id, is_pinned, title,
            content_json, paper_style, initial_paper_style, base_revision, checkpoint_revision,
            base_note_updated_at, checkpoint_at
          ) VALUES (
            'note_101_v3-s2', 101, 'v3-s2', 10, NULL, 0, 'Nota editada v3', ?,
            'TECH_DARK', NULL, 2, 3, 300, 6000
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
        assertEquals(4, writableDb.version)

        writableDb
          .query(
            "SELECT draft_id, folder_id, initial_folder_id, fallback_source_folder_id FROM note_recovery_drafts ORDER BY checkpoint_at ASC"
          )
          .use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("new_v3-s1", cursor.getString(0))
            assertEquals(10L, cursor.getLong(1))
            assertEquals(20L, cursor.getLong(2))
            assertTrue("fallback_source_folder_id não pode ser preenchido automaticamente", cursor.isNull(3))

            assertTrue(cursor.moveToNext())
            assertEquals("new_legacy-v2", cursor.getString(0))
            assertEquals(10L, cursor.getLong(1))
            assertTrue(cursor.isNull(2))
            assertTrue("fallback_source_folder_id legado deve permanecer NULL", cursor.isNull(3))

            assertTrue(cursor.moveToNext())
            assertEquals("note_101_v3-s2", cursor.getString(0))
            assertEquals(10L, cursor.getLong(1))
            assertTrue(cursor.isNull(2))
            assertTrue("fallback_source_folder_id de nota existente deve permanecer NULL", cursor.isNull(3))

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

        val migratedNew = draftRepository.getCheckpointById("new_v3-s1")!!
        assertNull(migratedNew.targetNoteId)
        assertEquals(10L, migratedNew.folderId)
        assertEquals(20L, migratedNew.initialFolderId)
        assertNull(migratedNew.fallbackSourceFolderId)
        assertTrue(migratedNew.isPinned)
        assertEquals("Nova nota v3", migratedNew.title)
        assertEquals("Rascunho v3 novo", migratedNew.contentOrNull!!.blocks.first().text)
        assertEquals(NotePaperStyle.OCEAN_BREEZE, migratedNew.paperStyle)
        assertEquals(NotePaperStyle.FOREST_MIST, migratedNew.initialPaperStyle)
        assertEquals(0L, migratedNew.baseRevision)
        assertEquals(4L, migratedNew.checkpointRevision)
        assertNull(migratedNew.baseNoteUpdatedAt)
        assertEquals(5000L, migratedNew.checkpointAt)

        val migratedLegacy = draftRepository.getCheckpointById("new_legacy-v2")!!
        assertNull(migratedLegacy.initialFolderId)
        assertNull(migratedLegacy.initialPaperStyle)
        assertNull(migratedLegacy.fallbackSourceFolderId)
        assertEquals(10L, migratedLegacy.folderId)

        val migratedExisting = draftRepository.getCheckpointById("note_101_v3-s2")!!
        assertEquals(101L, migratedExisting.targetNoteId)
        assertEquals(10L, migratedExisting.folderId)
        assertNull(migratedExisting.initialFolderId)
        assertNull(migratedExisting.initialPaperStyle)
        assertNull(migratedExisting.fallbackSourceFolderId)
        assertEquals(2L, migratedExisting.baseRevision)
        assertEquals(3L, migratedExisting.checkpointRevision)
        assertEquals(300L, migratedExisting.baseNoteUpdatedAt)
      } finally {
        migratedDb.close()
        context.deleteDatabase(dbName)
      }
    }

  @Test
  fun `migration chain 1 to 2 to 3 to 4 and 2 to 3 to 4 preserve data and support v4 fallback`() =
    runTest {
      val context = ApplicationProvider.getApplicationContext<Context>()

      // Cadeia 1 -> 2 -> 3 -> 4
      val dbNameV1 = "migration_chain_v1_to_v4.db"
      context.deleteDatabase(dbNameV1)
      val noteJsonV1 =
        """{"schemaVersion":1,"blocks":[{"type":"paragraph","id":"p1","text":"Legada v1","spans":[]}]}"""

      createDatabaseFromExportedSchema(context = context, dbName = dbNameV1, version = 1) { dbV1 ->
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
          arrayOf(noteJsonV1),
        )
      }

      val migratedFromV1 =
        Room.databaseBuilder(context, AnoteVoceDatabase::class.java, dbNameV1)
          .addMigrations(*AnoteVoceDatabase.ALL_MIGRATIONS)
          .allowMainThreadQueries()
          .build()

      try {
        assertEquals(4, migratedFromV1.openHelper.writableDatabase.version)
        val note = migratedFromV1.noteDao().getById(55L)!!
        assertEquals("Nota v1", note.title)
        assertEquals("FOREST_MIST", note.paperStyle)

        val draftRepo =
          NoteRecoveryDraftRepositoryImpl(
            database = migratedFromV1,
            draftDao = migratedFromV1.noteRecoveryDraftDao(),
            dispatchers = testDispatchers,
            clock = testClock,
            noteDao = migratedFromV1.noteDao(),
          )
        val sessionId = "chain-v4-from-v1"
        val draftId = NoteRecoveryDraftKeys.forNewNote(sessionId)
        val saved =
          draftRepo.saveCheckpoint(
            NoteRecoveryDraftInput(
              draftId = draftId,
              targetNoteId = null,
              sessionId = sessionId,
              folderId = null,
              initialFolderId = 7L,
              fallbackSourceFolderId = 99L,
              isPinned = false,
              title = "Com fallback v4",
              content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "n1", text = "Texto"))),
              paperStyle = NotePaperStyle.MINIMALIST_PRO,
              initialPaperStyle = NotePaperStyle.FOREST_MIST,
              baseRevision = 0L,
              checkpointRevision = 1L,
              baseNoteUpdatedAt = null,
            )
          )
        assertTrue(saved is SaveCheckpointResult.Saved)
        val loaded = draftRepo.getCheckpointById(draftId)!!
        assertNull(loaded.folderId)
        assertEquals(7L, loaded.initialFolderId)
        assertEquals(99L, loaded.fallbackSourceFolderId)
      } finally {
        migratedFromV1.close()
        context.deleteDatabase(dbNameV1)
      }

      // Cadeia 2 -> 3 -> 4
      val dbNameV2 = "migration_chain_v2_to_v4.db"
      context.deleteDatabase(dbNameV2)
      val draftJsonV2 =
        """{"schemaVersion":1,"blocks":[{"type":"paragraph","id":"d2","text":"Rascunho v2","spans":[]}]}"""

      createDatabaseFromExportedSchema(context = context, dbName = dbNameV2, version = 2) { dbV2 ->
        dbV2.execSQL(
          """
          INSERT INTO note_recovery_drafts (
            draft_id, target_note_id, session_id, folder_id, is_pinned, title, content_json,
            paper_style, base_revision, checkpoint_revision, base_note_updated_at, checkpoint_at
          ) VALUES (
            'new_chain-v2', NULL, 'chain-v2', 12, 1, 'Rascunho v2', ?,
            'LINHAS', 0, 5, NULL, 9000
          )
          """.trimIndent(),
          arrayOf(draftJsonV2),
        )
      }

      val migratedFromV2 =
        Room.databaseBuilder(context, AnoteVoceDatabase::class.java, dbNameV2)
          .addMigrations(*AnoteVoceDatabase.ALL_MIGRATIONS)
          .allowMainThreadQueries()
          .build()

      try {
        assertEquals(4, migratedFromV2.openHelper.writableDatabase.version)
        val draftRepo =
          NoteRecoveryDraftRepositoryImpl(
            database = migratedFromV2,
            draftDao = migratedFromV2.noteRecoveryDraftDao(),
            dispatchers = testDispatchers,
            clock = testClock,
            noteDao = migratedFromV2.noteDao(),
          )
        val loadedV2 = draftRepo.getCheckpointById("new_chain-v2")!!
        assertEquals(12L, loadedV2.folderId)
        assertNull(loadedV2.initialFolderId)
        assertNull(loadedV2.initialPaperStyle)
        assertNull(loadedV2.fallbackSourceFolderId)
        assertEquals(5L, loadedV2.checkpointRevision)
      } finally {
        migratedFromV2.close()
        context.deleteDatabase(dbNameV2)
      }
    }

  @Test
  fun `persists and reads fallbackSourceFolderId and enforces mutual exclusivity and validity`() =
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

        val folderA = folderRepository.createFolder("Pasta A")
        val folderB = folderRepository.createFolder("Pasta B")
        val existingNote = noteRepository.createNote(title = "Existente", folderId = folderA.id)

        // 1. Nova nota com fallbackSourceFolderId preenchido e folderId = null
        val newSession = "new-fallback-session"
        val newDraftId = NoteRecoveryDraftKeys.forNewNote(newSession)
        val newSaved =
          draftRepository.saveCheckpoint(
            NoteRecoveryDraftInput(
              draftId = newDraftId,
              targetNoteId = null,
              sessionId = newSession,
              folderId = null,
              initialFolderId = folderA.id,
              fallbackSourceFolderId = folderB.id,
              isPinned = false,
              title = "Nova com fallback",
              content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Corpo"))),
              paperStyle = NotePaperStyle.LINHAS,
              initialPaperStyle = NotePaperStyle.LINHAS,
              baseRevision = 0L,
              checkpointRevision = 1L,
              baseNoteUpdatedAt = null,
            )
          )
        assertTrue(newSaved is SaveCheckpointResult.Saved)
        val reloadedNew = draftRepository.getCheckpointById(newDraftId)!!
        assertNull(reloadedNew.folderId)
        assertEquals(folderA.id, reloadedNew.initialFolderId)
        assertEquals(folderB.id, reloadedNew.fallbackSourceFolderId)

        // 2. Nota existente com fallbackSourceFolderId preenchido e folderId = null
        val existingSession = "existing-fallback-session"
        val existingDraftId = NoteRecoveryDraftKeys.forExistingNote(existingNote.id, existingSession)
        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = existingDraftId,
            targetNoteId = existingNote.id,
            sessionId = existingSession,
            folderId = null,
            fallbackSourceFolderId = folderB.id,
            isPinned = false,
            title = "Existente com fallback",
            content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "e1", text = "Editado"))),
            paperStyle = NotePaperStyle.TECH_DARK,
            baseRevision = existingNote.revision,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = existingNote.updatedAt,
          )
        )
        val reloadedExisting = draftRepository.getCheckpointById(existingDraftId)!!
        assertNull(reloadedExisting.folderId)
        assertEquals(folderB.id, reloadedExisting.fallbackSourceFolderId)
        assertNull(reloadedExisting.initialFolderId)
        assertNull(reloadedExisting.initialPaperStyle)

        // 3. Exclusividade mútua: folderId != null e fallbackSourceFolderId != null é rejeitado na inserção e na atualização
        try {
          draftRepository.saveCheckpoint(
            NoteRecoveryDraftInput(
              draftId = NoteRecoveryDraftKeys.forNewNote("invalid-both-insert"),
              targetNoteId = null,
              sessionId = "invalid-both-insert",
              folderId = folderA.id,
              initialFolderId = folderA.id,
              fallbackSourceFolderId = folderB.id,
              isPinned = false,
              title = "Inválido",
              content = NoteContentDocument.empty(),
              paperStyle = NotePaperStyle.LINHAS,
              initialPaperStyle = NotePaperStyle.LINHAS,
              baseRevision = 0L,
              checkpointRevision = 1L,
              baseNoteUpdatedAt = null,
            )
          )
          fail("Esperava InvalidRecoveryDraftException ao coexistir folderId e fallbackSourceFolderId")
        } catch (_: InvalidRecoveryDraftException) {
          assertNull(
            draftRepository.getCheckpointById(NoteRecoveryDraftKeys.forNewNote("invalid-both-insert"))
          )
        }

        try {
          draftRepository.saveCheckpoint(
            NoteRecoveryDraftInput(
              draftId = newDraftId,
              targetNoteId = null,
              sessionId = newSession,
              folderId = folderA.id,
              initialFolderId = folderA.id,
              fallbackSourceFolderId = folderB.id,
              isPinned = false,
              title = "Atualização inválida",
              content = NoteContentDocument.empty(),
              paperStyle = NotePaperStyle.LINHAS,
              initialPaperStyle = NotePaperStyle.LINHAS,
              baseRevision = 0L,
              checkpointRevision = 2L,
              baseNoteUpdatedAt = null,
            )
          )
          fail("Esperava InvalidRecoveryDraftException ao atualizar com folderId e fallbackSourceFolderId")
        } catch (_: InvalidRecoveryDraftException) {
          val intact = draftRepository.getCheckpointById(newDraftId)!!
          assertEquals("Nova com fallback", intact.title)
          assertEquals(1L, intact.checkpointRevision)
          assertNull(intact.folderId)
          assertEquals(folderB.id, intact.fallbackSourceFolderId)
        }

        // 4. fallbackSourceFolderId não positivo (0 ou negativo) também é rejeitado
        for (invalidFallbackId in listOf(0L, -3L)) {
          try {
            draftRepository.saveCheckpoint(
              NoteRecoveryDraftInput(
                draftId = newDraftId,
                targetNoteId = null,
                sessionId = newSession,
                folderId = null,
                initialFolderId = folderA.id,
                fallbackSourceFolderId = invalidFallbackId,
                isPinned = false,
                title = "Fallback inválido",
                content = NoteContentDocument.empty(),
                paperStyle = NotePaperStyle.LINHAS,
                initialPaperStyle = NotePaperStyle.LINHAS,
                baseRevision = 0L,
                checkpointRevision = 2L,
                baseNoteUpdatedAt = null,
              )
            )
            fail("Esperava InvalidRecoveryDraftException para fallbackSourceFolderId=$invalidFallbackId")
          } catch (_: InvalidRecoveryDraftException) {
            assertEquals(1L, draftRepository.getCheckpointById(newDraftId)!!.checkpointRevision)
          }
        }

        // 5. Atualização válida resolvendo fallback para pasta ativa limpa fallbackSourceFolderId
        val resolvedUpdate =
          draftRepository.saveCheckpoint(
            NoteRecoveryDraftInput(
              draftId = newDraftId,
              targetNoteId = null,
              sessionId = newSession,
              folderId = folderA.id,
              initialFolderId = folderA.id,
              fallbackSourceFolderId = null,
              isPinned = false,
              title = "Resolvida para A",
              content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Resolvido"))),
              paperStyle = NotePaperStyle.LINHAS,
              initialPaperStyle = NotePaperStyle.LINHAS,
              baseRevision = 0L,
              checkpointRevision = 2L,
              baseNoteUpdatedAt = null,
            )
          )
        assertTrue(resolvedUpdate is SaveCheckpointResult.Saved)
        val afterResolve = draftRepository.getCheckpointById(newDraftId)!!
        assertEquals(folderA.id, afterResolve.folderId)
        assertNull(afterResolve.fallbackSourceFolderId)
        assertEquals(2L, afterResolve.checkpointRevision)
      } finally {
        db.close()
      }
    }

  @Test
  fun `updating draft preserves fallbackSourceFolderId until explicitly cleared with null`() =
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
        val draftRepository =
          NoteRecoveryDraftRepositoryImpl(
            database = db,
            draftDao = db.noteRecoveryDraftDao(),
            dispatchers = testDispatchers,
            clock = testClock,
            noteDao = db.noteDao(),
          )

        val folderA = folderRepository.createFolder("Origem")
        val folderB = folderRepository.createFolder("Fallback")
        val sessionId = "fallback-contract-session"
        val draftId = NoteRecoveryDraftKeys.forNewNote(sessionId)

        // 1. Gravação inicial com fallback ativo (folderId = null, fallbackSourceFolderId = folderB.id)
        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = draftId,
            targetNoteId = null,
            sessionId = sessionId,
            folderId = null,
            initialFolderId = folderA.id,
            fallbackSourceFolderId = folderB.id,
            isPinned = false,
            title = "Rascunho 1",
            content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "V1"))),
            paperStyle = NotePaperStyle.LINHAS,
            initialPaperStyle = NotePaperStyle.LINHAS,
            baseRevision = 0L,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = null,
          )
        )
        assertEquals(folderB.id, draftRepository.getCheckpointById(draftId)!!.fallbackSourceFolderId)

        // 2. Atualização de texto/título preservando fallbackSourceFolderId = folderB.id mantém o metadado intacto
        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = draftId,
            targetNoteId = null,
            sessionId = sessionId,
            folderId = null,
            initialFolderId = folderA.id,
            fallbackSourceFolderId = folderB.id,
            isPinned = false,
            title = "Rascunho 2",
            content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "V2"))),
            paperStyle = NotePaperStyle.OCEAN_BREEZE,
            initialPaperStyle = NotePaperStyle.LINHAS,
            baseRevision = 0L,
            checkpointRevision = 2L,
            baseNoteUpdatedAt = null,
          )
        )
        val preserved = draftRepository.getCheckpointById(draftId)!!
        assertNull(preserved.folderId)
        assertEquals(folderB.id, preserved.fallbackSourceFolderId)
        assertEquals("Rascunho 2", preserved.title)

        // 3. Exclusividade continua impedindo coexistência durante tentativa de atualização inválida
        try {
          draftRepository.saveCheckpoint(
            NoteRecoveryDraftInput(
              draftId = draftId,
              targetNoteId = null,
              sessionId = sessionId,
              folderId = folderA.id,
              initialFolderId = folderA.id,
              fallbackSourceFolderId = folderB.id,
              isPinned = false,
              title = "Exclusividade violada",
              content = NoteContentDocument.empty(),
              paperStyle = NotePaperStyle.LINHAS,
              initialPaperStyle = NotePaperStyle.LINHAS,
              baseRevision = 0L,
              checkpointRevision = 3L,
              baseNoteUpdatedAt = null,
            )
          )
          fail("Esperava InvalidRecoveryDraftException para coexistência folderId e fallbackSourceFolderId")
        } catch (_: InvalidRecoveryDraftException) {
          val afterRejected = draftRepository.getCheckpointById(draftId)!!
          assertNull(afterRejected.folderId)
          assertEquals(folderB.id, afterRejected.fallbackSourceFolderId)
          assertEquals(2L, afterRejected.checkpointRevision)
        }

        // 4. Atualização enviando explicitamente fallbackSourceFolderId = null limpa o metadado (ex.: confirmação de raiz)
        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = draftId,
            targetNoteId = null,
            sessionId = sessionId,
            folderId = null,
            initialFolderId = folderA.id,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Raiz confirmada",
            content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "V3"))),
            paperStyle = NotePaperStyle.OCEAN_BREEZE,
            initialPaperStyle = NotePaperStyle.LINHAS,
            baseRevision = 0L,
            checkpointRevision = 3L,
            baseNoteUpdatedAt = null,
          )
        )
        val cleared = draftRepository.getCheckpointById(draftId)!!
        assertNull(cleared.folderId)
        assertNull(cleared.fallbackSourceFolderId)
        assertEquals("Raiz confirmada", cleared.title)
        assertEquals(3L, cleared.checkpointRevision)
      } finally {
        db.close()
      }
    }

  @Test
  fun `enforces immutability of initialFolderId and initialPaperStyle and rejects stale revisions`() =
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

        val sessionId = "immutable-session"
        val draftId = NoteRecoveryDraftKeys.forNewNote(sessionId)

        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = draftId,
            targetNoteId = null,
            sessionId = sessionId,
            folderId = null,
            initialFolderId = 10L,
            fallbackSourceFolderId = 20L,
            isPinned = false,
            title = "Inicial v4",
            content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Texto 1"))),
            paperStyle = NotePaperStyle.SUNSET_WARMTH,
            initialPaperStyle = NotePaperStyle.FOREST_MIST,
            baseRevision = 0L,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = null,
          )
        )

        // 1. Tentativas de mutar initialFolderId são rejeitadas sem alterar o registro
        for (mutatedFolderId in listOf(99L, null)) {
          try {
            draftRepository.saveCheckpoint(
              NoteRecoveryDraftInput(
                draftId = draftId,
                targetNoteId = null,
                sessionId = sessionId,
                folderId = null,
                initialFolderId = mutatedFolderId,
                fallbackSourceFolderId = 20L,
                isPinned = false,
                title = "Mutação folder",
                content = NoteContentDocument.empty(),
                paperStyle = NotePaperStyle.SUNSET_WARMTH,
                initialPaperStyle = NotePaperStyle.FOREST_MIST,
                baseRevision = 0L,
                checkpointRevision = 2L,
                baseNoteUpdatedAt = null,
              )
            )
            fail("Esperava InvalidRecoveryDraftException ao alterar initialFolderId para $mutatedFolderId")
          } catch (_: InvalidRecoveryDraftException) {
            val current = draftRepository.getCheckpointById(draftId)!!
            assertEquals(10L, current.initialFolderId)
            assertEquals(1L, current.checkpointRevision)
            assertEquals("Inicial v4", current.title)
          }
        }

        // 2. Tentativas de mutar initialPaperStyle são rejeitadas sem alterar o registro
        for (mutatedPaper in listOf(NotePaperStyle.TECH_DARK, null)) {
          try {
            draftRepository.saveCheckpoint(
              NoteRecoveryDraftInput(
                draftId = draftId,
                targetNoteId = null,
                sessionId = sessionId,
                folderId = null,
                initialFolderId = 10L,
                fallbackSourceFolderId = 20L,
                isPinned = false,
                title = "Mutação paper",
                content = NoteContentDocument.empty(),
                paperStyle = NotePaperStyle.SUNSET_WARMTH,
                initialPaperStyle = mutatedPaper,
                baseRevision = 0L,
                checkpointRevision = 2L,
                baseNoteUpdatedAt = null,
              )
            )
            fail("Esperava InvalidRecoveryDraftException ao alterar initialPaperStyle para $mutatedPaper")
          } catch (_: InvalidRecoveryDraftException) {
            val current = draftRepository.getCheckpointById(draftId)!!
            assertEquals(NotePaperStyle.FOREST_MIST, current.initialPaperStyle)
            assertEquals(1L, current.checkpointRevision)
            assertEquals("Inicial v4", current.title)
          }
        }

        // 3. Atualização preservando initialFolderId e initialPaperStyle funciona normalmente
        val validUpdate =
          draftRepository.saveCheckpoint(
            NoteRecoveryDraftInput(
              draftId = draftId,
              targetNoteId = null,
              sessionId = sessionId,
              folderId = 30L,
              initialFolderId = 10L,
              fallbackSourceFolderId = null,
              isPinned = true,
              title = "Atualizado v4 rev 2",
              content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "p1", text = "Texto 2"))),
              paperStyle = NotePaperStyle.ELEGANCE_NOIR,
              initialPaperStyle = NotePaperStyle.FOREST_MIST,
              baseRevision = 0L,
              checkpointRevision = 2L,
              baseNoteUpdatedAt = null,
            )
          )
        assertTrue(validUpdate is SaveCheckpointResult.Saved)

        // 4. Revisão menor ou igual é ignorada sem sobrescrever dados mais recentes
        val staleOlder =
          draftRepository.saveCheckpoint(
            NoteRecoveryDraftInput(
              draftId = draftId,
              targetNoteId = null,
              sessionId = sessionId,
              folderId = null,
              initialFolderId = 10L,
              fallbackSourceFolderId = 20L,
              isPinned = false,
              title = "Atrasado rev 1",
              content = NoteContentDocument.empty(),
              paperStyle = NotePaperStyle.LINHAS,
              initialPaperStyle = NotePaperStyle.FOREST_MIST,
              baseRevision = 0L,
              checkpointRevision = 1L,
              baseNoteUpdatedAt = null,
            )
          )
        assertTrue(staleOlder is SaveCheckpointResult.IgnoredStaleRevision)

        val staleEqual =
          draftRepository.saveCheckpoint(
            NoteRecoveryDraftInput(
              draftId = draftId,
              targetNoteId = null,
              sessionId = sessionId,
              folderId = null,
              initialFolderId = 10L,
              fallbackSourceFolderId = 20L,
              isPinned = false,
              title = "Duplicado rev 2",
              content = NoteContentDocument.empty(),
              paperStyle = NotePaperStyle.LINHAS,
              initialPaperStyle = NotePaperStyle.FOREST_MIST,
              baseRevision = 0L,
              checkpointRevision = 2L,
              baseNoteUpdatedAt = null,
            )
          )
        assertTrue(staleEqual is SaveCheckpointResult.IgnoredStaleRevision)

        val latest = draftRepository.getCheckpointById(draftId)!!
        assertEquals("Atualizado v4 rev 2", latest.title)
        assertEquals(30L, latest.folderId)
        assertNull(latest.fallbackSourceFolderId)
        assertEquals(2L, latest.checkpointRevision)

        // 5. Rascunho legado (iniciais NULL) preserva NULLs e rejeita preenchimento retroativo
        val legacySession = "legacy-null-initials"
        val legacyDraftId = NoteRecoveryDraftKeys.forNewNote(legacySession)
        val validJson =
          """{"schemaVersion":1,"blocks":[{"type":"paragraph","id":"l1","text":"Legado","spans":[]}]}"""
        draftDao.insert(
          NoteRecoveryDraftEntity(
            draftId = legacyDraftId,
            targetNoteId = null,
            sessionId = legacySession,
            folderId = 10L,
            initialFolderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Legado",
            contentJson = validJson,
            paperStyle = NotePaperStyle.OCEAN_BREEZE.name,
            initialPaperStyle = null,
            baseRevision = 0L,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = null,
            checkpointAt = 1000L,
          )
        )

        try {
          draftRepository.saveCheckpoint(
            NoteRecoveryDraftInput(
              draftId = legacyDraftId,
              targetNoteId = null,
              sessionId = legacySession,
              folderId = null,
              initialFolderId = 10L,
              fallbackSourceFolderId = 10L,
              isPinned = false,
              title = "Retroativo",
              content = NoteContentDocument.empty(),
              paperStyle = NotePaperStyle.OCEAN_BREEZE,
              initialPaperStyle = null,
              baseRevision = 0L,
              checkpointRevision = 2L,
              baseNoteUpdatedAt = null,
            )
          )
          fail("Esperava InvalidRecoveryDraftException ao preencher initialFolderId retroativamente em legado")
        } catch (_: InvalidRecoveryDraftException) {
          assertNull(draftRepository.getCheckpointById(legacyDraftId)!!.initialFolderId)
        }

        try {
          draftRepository.saveCheckpoint(
            NoteRecoveryDraftInput(
              draftId = legacyDraftId,
              targetNoteId = null,
              sessionId = legacySession,
              folderId = null,
              initialFolderId = null,
              fallbackSourceFolderId = 10L,
              isPinned = false,
              title = "Retroativo papel",
              content = NoteContentDocument.empty(),
              paperStyle = NotePaperStyle.OCEAN_BREEZE,
              initialPaperStyle = NotePaperStyle.OCEAN_BREEZE,
              baseRevision = 0L,
              checkpointRevision = 2L,
              baseNoteUpdatedAt = null,
            )
          )
          fail("Esperava InvalidRecoveryDraftException ao preencher initialPaperStyle retroativamente em legado")
        } catch (_: InvalidRecoveryDraftException) {
          assertNull(draftRepository.getCheckpointById(legacyDraftId)!!.initialPaperStyle)
        }

        // Atualização legada preservando iniciais nulos funciona e grava fallbackSourceFolderId
        val legacyUpdate =
          draftRepository.saveCheckpoint(
            NoteRecoveryDraftInput(
              draftId = legacyDraftId,
              targetNoteId = null,
              sessionId = legacySession,
              folderId = null,
              initialFolderId = null,
              fallbackSourceFolderId = 10L,
              isPinned = false,
              title = "Legado com fallback",
              content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "l1", text = "Editado"))),
              paperStyle = NotePaperStyle.OCEAN_BREEZE,
              initialPaperStyle = null,
              baseRevision = 0L,
              checkpointRevision = 2L,
              baseNoteUpdatedAt = null,
            )
          )
        assertTrue(legacyUpdate is SaveCheckpointResult.Saved)
        val reloadedLegacy = draftRepository.getCheckpointById(legacyDraftId)!!
        assertNull(reloadedLegacy.initialFolderId)
        assertNull(reloadedLegacy.initialPaperStyle)
        assertEquals(10L, reloadedLegacy.fallbackSourceFolderId)

        // 6. Valor futuro desconhecido em initial_paper_style permanece intacto se atualização mantiver null e rejeita sobrescrita
        val futurePaperSession = "future-initial-paper"
        val futurePaperDraftId = NoteRecoveryDraftKeys.forNewNote(futurePaperSession)
        draftDao.insert(
          NoteRecoveryDraftEntity(
            draftId = futurePaperDraftId,
            targetNoteId = null,
            sessionId = futurePaperSession,
            folderId = null,
            initialFolderId = null,
            fallbackSourceFolderId = null,
            isPinned = false,
            title = "Futuro",
            contentJson = validJson,
            paperStyle = NotePaperStyle.LINHAS.name,
            initialPaperStyle = "PAPEL_DESCONHECIDO_V99",
            baseRevision = 0L,
            checkpointRevision = 1L,
            baseNoteUpdatedAt = null,
            checkpointAt = 1001L,
          )
        )

        try {
          draftRepository.saveCheckpoint(
            NoteRecoveryDraftInput(
              draftId = futurePaperDraftId,
              targetNoteId = null,
              sessionId = futurePaperSession,
              folderId = null,
              initialFolderId = null,
              fallbackSourceFolderId = null,
              isPinned = false,
              title = "Sobrescrita indevida",
              content = NoteContentDocument.empty(),
              paperStyle = NotePaperStyle.LINHAS,
              initialPaperStyle = NotePaperStyle.LINHAS,
              baseRevision = 0L,
              checkpointRevision = 2L,
              baseNoteUpdatedAt = null,
            )
          )
          fail("Esperava InvalidRecoveryDraftException ao sobrescrever initialPaperStyle desconhecido")
        } catch (_: InvalidRecoveryDraftException) {
          assertEquals(
            "PAPEL_DESCONHECIDO_V99",
            draftDao.getByDraftId(futurePaperDraftId)!!.initialPaperStyle,
          )
        }

        draftRepository.saveCheckpoint(
          NoteRecoveryDraftInput(
            draftId = futurePaperDraftId,
            targetNoteId = null,
            sessionId = futurePaperSession,
            folderId = null,
            initialFolderId = null,
            fallbackSourceFolderId = 15L,
            isPinned = false,
            title = "Futuro preservado",
            content = NoteContentDocument(blocks = listOf(ParagraphBlock(id = "f1", text = "Ok"))),
            paperStyle = NotePaperStyle.LINHAS,
            initialPaperStyle = null,
            baseRevision = 0L,
            checkpointRevision = 2L,
            baseNoteUpdatedAt = null,
          )
        )
        assertEquals(
          "PAPEL_DESCONHECIDO_V99",
          draftDao.getByDraftId(futurePaperDraftId)!!.initialPaperStyle,
        )
        assertEquals(15L, draftRepository.getCheckpointById(futurePaperDraftId)!!.fallbackSourceFolderId)
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
