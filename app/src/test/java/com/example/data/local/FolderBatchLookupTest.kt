package com.example.data.local

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.coroutines.DispatcherProvider
import com.example.core.time.ClockProvider
import com.example.data.local.dao.FolderDao
import com.example.data.local.database.AnoteVoceDatabase
import com.example.data.local.entity.FolderEntity
import com.example.data.repository.FolderRepositoryImpl
import com.example.domain.editor.FolderAvailability
import com.example.domain.repository.FolderDeleteMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FolderBatchLookupTest {

  private lateinit var database: AnoteVoceDatabase
  private lateinit var realFolderDao: FolderDao
  private lateinit var folderRepository: FolderRepositoryImpl

  private var fakeTimeMillis = 1_700_100_000_000L

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

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database =
      Room.inMemoryDatabaseBuilder(context, AnoteVoceDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    realFolderDao = database.folderDao()
    folderRepository =
      FolderRepositoryImpl(
        database = database,
        folderDao = realFolderDao,
        noteDao = database.noteDao(),
        dispatchers = testDispatchers,
        clock = testClock,
      )
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun `empty set returns empty map without querying FolderDao`() = runTest {
    val spyDao = SpyFolderDao(realFolderDao)
    val repo = repositoryWithDao(spyDao)

    val result = repo.getFoldersByIds(emptySet())

    assertTrue(result.isEmpty())
    assertEquals(0, spyDao.recordedBatches.size)
  }

  @Test
  fun `maps active, trashed and missing folder ids accurately`() = runTest {
    val active = folderRepository.createFolder("Trabalho")
    val trashed = folderRepository.createFolder("Arquivo Morto")
    folderRepository.deleteFolder(trashed.id, FolderDeleteMode.FOLDER_ONLY)
    val missingId = 99_999L

    val result = folderRepository.getFoldersByIds(linkedSetOf(active.id, trashed.id, missingId))

    assertEquals(3, result.size)
    val activeAvailability = result[active.id] as FolderAvailability.Active
    assertEquals(active.id, activeAvailability.folder.id)
    assertEquals("Trabalho", activeAvailability.folder.name)

    val trashedAvailability = result[trashed.id] as FolderAvailability.InTrash
    assertEquals(trashed.id, trashedAvailability.folder.id)
    assertTrue(trashedAvailability.folder.isDeleted)

    assertEquals(FolderAvailability.NotFound(missingId), result[missingId])
  }

  @Test
  fun `handles more than 999 ids via chunked queries of at most 500 ids`() = runTest {
    val spyDao = SpyFolderDao(realFolderDao)
    val repo = repositoryWithDao(spyDao)

    val activeIds = mutableListOf<Long>()
    val trashedIds = mutableListOf<Long>()
    repeat(12) { index ->
      val created = repo.createFolder("Pasta $index")
      if (index % 2 == 0) {
        activeIds.add(created.id)
      } else {
        repo.deleteFolder(created.id, FolderDeleteMode.FOLDER_ONLY)
        trashedIds.add(created.id)
      }
    }

    val requestedIds = LinkedHashSet<Long>(1_250)
    requestedIds.addAll(activeIds)
    requestedIds.addAll(trashedIds)
    var candidateId = 50_000L
    while (requestedIds.size < 1_250) {
      requestedIds.add(candidateId++)
    }

    val result = repo.getFoldersByIds(requestedIds)

    assertEquals(1_250, result.size)
    assertEquals(listOf(500, 500, 250), spyDao.recordedBatches.map { it.size })
    for (id in activeIds) {
      assertTrue(result[id] is FolderAvailability.Active)
    }
    for (id in trashedIds) {
      assertTrue(result[id] is FolderAvailability.InTrash)
    }
    for (id in requestedIds) {
      if (id !in activeIds && id !in trashedIds) {
        assertEquals(FolderAvailability.NotFound(id), result[id])
      }
    }
  }

  @Test
  fun `query exception maps all requested ids to Unknown and never NotFound or Root`() = runTest {
    val active = folderRepository.createFolder("Existente")
    val failingDao =
      SpyFolderDao(
        delegate = realFolderDao,
        onGetByIds = { _, _ -> throw SQLiteException("Falha simulada de leitura") },
      )
    val repo = repositoryWithDao(failingDao)
    val requestedIds = linkedSetOf(active.id, 777L)

    val result = repo.getFoldersByIds(requestedIds)

    assertEquals(2, result.size)
    requestedIds.forEach { id ->
      val availability = result[id]
      assertTrue(availability is FolderAvailability.Unknown)
      availability as FolderAvailability.Unknown
      assertEquals(id, availability.folderId)
      assertEquals("Falha simulada de leitura", availability.reason)
    }
  }

  @Test
  fun `failure on second chunk maps all ids including first chunk ids to Unknown`() = runTest {
    val firstBatchFolder = folderRepository.createFolder("Lote Um")
    val spyDao =
      SpyFolderDao(
        delegate = realFolderDao,
        onGetByIds = { callIndex, _ ->
          if (callIndex == 2) {
            throw IllegalStateException("Erro no segundo lote")
          }
          null
        },
      )
    val repo = repositoryWithDao(spyDao)

    val requestedIds = LinkedHashSet<Long>(650)
    requestedIds.add(firstBatchFolder.id)
    var nextId = 10_000L
    while (requestedIds.size < 650) {
      requestedIds.add(nextId++)
    }

    val result = repo.getFoldersByIds(requestedIds)

    assertEquals(2, spyDao.recordedBatches.size)
    assertEquals(650, result.size)
    requestedIds.forEach { id ->
      val availability = result[id]
      assertTrue("Esperava Unknown para id=$id", availability is FolderAvailability.Unknown)
      assertEquals("Erro no segundo lote", (availability as FolderAvailability.Unknown).reason)
    }
  }

  @Test
  fun `propagates CancellationException without converting to Unknown`() = runTest {
    val cancelingDao =
      SpyFolderDao(
        delegate = realFolderDao,
        onGetByIds = { _, _ -> throw CancellationException("Coroutine cancelada") },
      )
    val repo = repositoryWithDao(cancelingDao)

    try {
      repo.getFoldersByIds(setOf(1L, 2L))
      fail("Esperava propagação de CancellationException")
    } catch (e: CancellationException) {
      assertEquals("Coroutine cancelada", e.message)
    }
  }

  @Test
  fun `rejects non-positive folder ids with IllegalArgumentException before querying DAO`() =
    runTest {
      val spyDao = SpyFolderDao(realFolderDao)
      val repo = repositoryWithDao(spyDao)

      val invalidSets = listOf(setOf(0L), setOf(-1L), setOf(1L, 0L), setOf(1L, -42L))
      for (invalidIds in invalidSets) {
        try {
          repo.getFoldersByIds(invalidIds)
          fail("Esperava IllegalArgumentException para ids=$invalidIds")
        } catch (_: IllegalArgumentException) {
          // esperado
        }
      }

      assertEquals(0, spyDao.recordedBatches.size)
    }

  private fun repositoryWithDao(folderDao: FolderDao): FolderRepositoryImpl =
    FolderRepositoryImpl(
      database = database,
      folderDao = folderDao,
      noteDao = database.noteDao(),
      dispatchers = testDispatchers,
      clock = testClock,
    )

  private class SpyFolderDao(
    private val delegate: FolderDao,
    private val onGetByIds: (suspend (callIndex: Int, folderIds: List<Long>) -> List<FolderEntity>?)? =
      null,
  ) : FolderDao by delegate {
    val recordedBatches = mutableListOf<List<Long>>()

    override suspend fun getByIds(folderIds: List<Long>): List<FolderEntity> {
      recordedBatches.add(folderIds.toList())
      val callIndex = recordedBatches.size
      val overrideResult = onGetByIds?.invoke(callIndex, folderIds)
      return overrideResult ?: delegate.getByIds(folderIds)
    }
  }
}
