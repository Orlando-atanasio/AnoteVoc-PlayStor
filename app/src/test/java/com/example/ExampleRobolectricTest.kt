package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.coroutines.DispatcherProvider
import com.example.core.time.ClockProvider
import com.example.data.local.database.AnoteVoceDatabase
import com.example.data.repository.FolderRepositoryImpl
import com.example.data.repository.NoteRepositoryImpl
import com.example.data.repository.PreferencesRepositoryImpl
import com.example.ui.AnoteVoceApp
import com.example.ui.viewmodel.AnoteVoceViewModelFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class ExampleRobolectricTest {

  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var database: AnoteVoceDatabase
  private lateinit var factory: AnoteVoceViewModelFactory

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database =
      Room.inMemoryDatabaseBuilder(context, AnoteVoceDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    val dispatcher = UnconfinedTestDispatcher()
    val dispatchers =
      object : DispatcherProvider {
        override val main: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
      }
    val clock =
      object : ClockProvider {
        private var time = 1_700_000_000_000L
        override fun nowMillis(): Long = ++time
      }

    factory =
      AnoteVoceViewModelFactory(
        folderRepository =
          FolderRepositoryImpl(
            database = database,
            folderDao = database.folderDao(),
            noteDao = database.noteDao(),
            dispatchers = dispatchers,
            clock = clock,
          ),
        noteRepository =
          NoteRepositoryImpl(
            database = database,
            noteDao = database.noteDao(),
            folderDao = database.folderDao(),
            dispatchers = dispatchers,
            clock = clock,
          ),
        preferencesRepository =
          PreferencesRepositoryImpl(
            database = database,
            appPreferencesDao = database.appPreferencesDao(),
            dispatchers = dispatchers,
            clock = clock,
          ),
      )
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun `read app name from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("AnoteVocê", appName)
  }

  @Test
  fun `app opens directly on Todas no meu phone with home greeting and empty state`() {
    composeTestRule.setContent {
      AnoteVoceApp(viewModelFactory = factory)
    }

    composeTestRule
      .onNodeWithTag("top_bar_title")
      .assertIsDisplayed()
      .assertTextEquals("Todas no meu phone")
    composeTestRule.onNodeWithTag("home_greeting_text").assertIsDisplayed()
    composeTestRule.onNodeWithText("Que bom ter você por aqui!").assertIsDisplayed()
    composeTestRule.onNodeWithTag("empty_state_text").assertIsDisplayed()
    composeTestRule.onNodeWithText("Nenhuma nota por aqui").assertIsDisplayed()
  }
}
