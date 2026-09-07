package com.samuel.regularnotifications.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samuel.regularnotifications.data.ReminderRepository
import com.samuel.regularnotifications.data.local.ReminderDatabase
import com.samuel.regularnotifications.domain.ReminderFormField
import com.samuel.regularnotifications.domain.ReminderInput
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderViewModelTest {
    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-01-01T10:00:00Z")
    private lateinit var database: ReminderDatabase
    private lateinit var repository: ReminderRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ReminderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ReminderRepository(database, clock = { now })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun listViewModelReflectsCreateEnableDisableAndDelete() = runBlocking {
        val viewModel = ReminderListViewModel(repository)
        viewModel.uiState.await { !it.isLoading && it.reminders.isEmpty() }

        val reminderId = repository.createReminder(input(), utc, now)
        val added = viewModel.uiState.await { state ->
            state.reminders.singleOrNull()?.id == reminderId
        }
        assertTrue(added.reminders.single().enabled)
        assertEquals("Every 7 days", added.reminders.single().recurrence)

        viewModel.setEnabled(reminderId, enabled = false)
        val disabled = viewModel.uiState.await { state ->
            state.reminders.singleOrNull()?.enabled == false
        }
        assertFalse(disabled.reminders.single().enabled)

        viewModel.delete(reminderId)
        assertTrue(viewModel.uiState.await { !it.isLoading && it.reminders.isEmpty() }.reminders.isEmpty())
    }

    @Test
    fun editorViewModelCreatesAndEditsTheSimpleDayIntervalForm() = runBlocking {
        val clock = Clock.fixed(now, utc)
        val createViewModel = ReminderEditorViewModel(repository, reminderId = null, clock = clock)
        createViewModel.updateTitle("  Take out trash  ")
        createViewModel.updateDescription("  Bins by the door  ")
        createViewModel.updateFirstDate(LocalDate.of(2026, 1, 5))
        createViewModel.updateFirstTime(LocalTime.of(9, 0))
        createViewModel.updateIntervalDays("7")

        val createdEvent = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { createViewModel.events.first() }
        }
        createViewModel.save()
        assertEquals(ReminderEditorEvent.Saved, createdEvent.await())

        val created = database.reminderDao().getAll().single()
        assertEquals("Take out trash", created.title)
        assertEquals("Bins by the door", created.description)
        assertEquals(7, created.intervalDays)

        val editViewModel = ReminderEditorViewModel(repository, created.id, clock)
        val loaded = editViewModel.uiState.await { !it.isLoading }
        assertEquals("Take out trash", loaded.title)
        assertEquals("7", loaded.intervalDays)

        editViewModel.updateTitle("Take recycling out")
        editViewModel.updateIntervalDays("14")
        val editedEvent = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { editViewModel.events.first() }
        }
        editViewModel.save()
        assertEquals(ReminderEditorEvent.Saved, editedEvent.await())

        val edited = database.reminderDao().getById(created.id)!!
        assertEquals("Take recycling out", edited.title)
        assertEquals(14, edited.intervalDays)
    }

    @Test
    fun editorViewModelShowsAnInlineErrorForAnIntervalBelowOneDay() {
        val viewModel = ReminderEditorViewModel(repository, reminderId = null, Clock.fixed(now, utc))
        viewModel.updateTitle("Water plants")
        viewModel.updateIntervalDays("0")

        viewModel.save()

        assertTrue(ReminderFormField.INTERVAL_DAYS in viewModel.uiState.value.fieldErrors)
    }

    private suspend fun <T> Flow<T>.await(predicate: (T) -> Boolean): T =
        withTimeout(5_000) { first(predicate) }

    private fun input() = ReminderInput(
        title = "Test reminder",
        description = null,
        enabled = true,
        firstOccurrence = LocalDateTime.of(2026, 1, 10, 9, 0),
        intervalDays = 7,
    )
}
