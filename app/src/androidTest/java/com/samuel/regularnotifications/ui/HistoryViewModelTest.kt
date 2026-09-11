package com.samuel.regularnotifications.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samuel.regularnotifications.data.ReminderRepository
import com.samuel.regularnotifications.data.local.ReminderDatabase
import com.samuel.regularnotifications.data.local.ReminderEventEntity
import com.samuel.regularnotifications.domain.ReminderEventType
import com.samuel.regularnotifications.domain.ReminderInput
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryViewModelTest {
    private val utc = ZoneId.of("UTC")
    private val baseTime = Instant.parse("2026-01-01T10:00:00Z")
    private lateinit var database: ReminderDatabase
    private lateinit var repository: ReminderRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ReminderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ReminderRepository(database, clock = { baseTime })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun historyMapsActionsNewestFirstAndUsesCurrentTitles() = runBlocking {
        val doneId = repository.createReminder(
            input(title = "Old title"),
            utc,
            baseTime,
        )
        val doneDue = database.outstandingDueDao().getByReminderId(doneId)!!
        val doneReminder = database.reminderDao().getById(doneId)!!
        repository.resolve(
            id = doneId,
            eventType = ReminderEventType.DONE,
            expectedRevision = doneDue.revision,
            expectedNormalOccurrenceIndex = doneDue.normalOccurrenceIndex,
            expectedReminderModifiedAtEpochMillis = doneReminder.modifiedAtEpochMillis,
            zoneId = utc,
            now = baseTime,
        )

        val dismissedId = repository.createReminder(
            input(title = "Dismissed reminder"),
            utc,
            baseTime,
        )
        val dismissedDue = database.outstandingDueDao().getByReminderId(dismissedId)!!
        val dismissedReminder = database.reminderDao().getById(dismissedId)!!
        repository.resolve(
            id = dismissedId,
            eventType = ReminderEventType.DISMISSED,
            expectedRevision = dismissedDue.revision,
            expectedNormalOccurrenceIndex = dismissedDue.normalOccurrenceIndex,
            expectedReminderModifiedAtEpochMillis = dismissedReminder.modifiedAtEpochMillis,
            zoneId = utc,
            now = baseTime.plusSeconds(60),
        )

        val postponedId = repository.createReminder(
            input(
                title = "Postponed reminder",
                firstOccurrence = LocalDateTime.of(2026, 1, 1, 8, 0),
            ),
            utc,
            baseTime,
        )
        val postponedDue = database.outstandingDueDao().getByReminderId(postponedId)!!
        val postponedReminder = database.reminderDao().getById(postponedId)!!
        repository.postpone(
            id = postponedId,
            expectedRevision = postponedDue.revision,
            expectedNormalOccurrenceIndex = postponedDue.normalOccurrenceIndex,
            expectedReminderModifiedAtEpochMillis = postponedReminder.modifiedAtEpochMillis,
            zoneId = utc,
            now = baseTime.plusSeconds(120),
        )

        val futureId = repository.createReminder(
            input(
                title = "Preview reminder",
                firstOccurrence = LocalDateTime.of(2026, 1, 2, 9, 0),
            ),
            utc,
            Instant.parse("2026-01-01T08:00:00Z"),
        )
        val preview = database.tomorrowPreviewDao().getByReminderId(futureId)!!
        repository.acknowledgeTomorrow(
            id = futureId,
            expectedRevision = preview.revision,
            expectedNormalOccurrenceIndex = preview.normalOccurrenceIndex,
            zoneId = utc,
            now = baseTime.plusSeconds(180),
        )

        val viewModel = HistoryViewModel(repository)
        val loaded = viewModel.uiState.await { !it.isLoading }

        assertEquals(
            listOf(
                HistoryAction.TOMORROW_SEEN,
                HistoryAction.POSTPONED,
                HistoryAction.DISMISSED,
                HistoryAction.DONE,
            ),
            loaded.items.map { it.action },
        )
        assertEquals("Postponed reminder", loaded.items[1].reminderTitle)
        assertEquals(
            Instant.parse("2026-01-02T08:00:00Z"),
            loaded.items[1].postponedUntil,
        )

        repository.updateReminder(
            id = doneId,
            input = input(title = "Renamed reminder"),
            zoneId = utc,
            now = baseTime.plusSeconds(180),
        )
        val renamed = viewModel.uiState.await {
            it.items.any { item -> item.reminderId == doneId && item.reminderTitle == "Renamed reminder" }
        }
        assertEquals("Renamed reminder", renamed.items.last().reminderTitle)

        repository.deleteReminder(dismissedId)
        val afterDelete = viewModel.uiState.await {
            it.items.none { item -> item.reminderId == dismissedId }
        }
        assertTrue(afterDelete.items.none { it.reminderId == dismissedId })
        assertNull(afterDelete.errorMessage)
    }

    @Test
    fun historyReactsToNewEventsAndDoesNotInventPauseOrRecoveryEvents() = runBlocking {
        val reminderId = repository.createReminder(
            input(firstOccurrence = LocalDateTime.of(2026, 1, 10, 9, 0)),
            utc,
            baseTime,
        )
        val viewModel = HistoryViewModel(repository)
        assertTrue(viewModel.uiState.await { !it.isLoading }.items.isEmpty())

        val eventId = database.reminderEventDao().insert(
            ReminderEventEntity(
                reminderId = reminderId,
                normalOccurrenceIndex = null,
                occurrenceScheduledEpochMillis = null,
                action = ReminderEventType.DONE.name,
                dueAtEpochMillis = null,
                postponedUntilEpochMillis = null,
                occurredAtEpochMillis = baseTime.toEpochMilli(),
            ),
        )
        val appeared = viewModel.uiState.await { it.items.size == 1 }
        assertEquals(eventId, appeared.items.single().id)
        assertEquals(HistoryAction.DONE, appeared.items.single().action)

        repository.setEnabled(reminderId, enabled = false, zoneId = utc, now = baseTime)
        repository.reconcileReminderForScheduling(
            reminderId,
            zoneId = utc,
            now = Instant.parse("2026-01-20T10:00:00Z"),
        )
        repository.setMasterEnabled(
            enabled = false,
            zoneId = utc,
            now = Instant.parse("2026-01-20T10:00:00Z"),
        )

        val stillEmpty = viewModel.uiState.await { !it.isLoading }
        assertEquals(1, stillEmpty.items.size)

        val reminder = database.reminderDao().getById(reminderId)!!
        val due = database.outstandingDueDao().getByReminderId(reminderId)
        assertNull(due)
        assertTrue(!reminder.enabled)
    }

    @Test
    fun historyUsesEventIdDescendingWhenOccurredTimesTie() = runBlocking {
        val reminderId = repository.createReminder(
            input(firstOccurrence = LocalDateTime.of(2026, 1, 10, 9, 0)),
            utc,
            baseTime,
        )
        val occurredAt = baseTime.toEpochMilli()
        database.reminderEventDao().insert(
            ReminderEventEntity(
                reminderId = reminderId,
                normalOccurrenceIndex = null,
                occurrenceScheduledEpochMillis = null,
                action = ReminderEventType.DONE.name,
                dueAtEpochMillis = null,
                postponedUntilEpochMillis = null,
                occurredAtEpochMillis = occurredAt,
            ),
        )
        val secondId = database.reminderEventDao().insert(
            ReminderEventEntity(
                reminderId = reminderId,
                normalOccurrenceIndex = null,
                occurrenceScheduledEpochMillis = null,
                action = ReminderEventType.DISMISSED.name,
                dueAtEpochMillis = null,
                postponedUntilEpochMillis = null,
                occurredAtEpochMillis = occurredAt,
            ),
        )

        val viewModel = HistoryViewModel(repository)
        val loaded = viewModel.uiState.await { it.items.size == 2 }

        assertEquals(secondId, loaded.items.first().id)
        assertEquals(HistoryAction.DISMISSED, loaded.items.first().action)
    }

    private fun input(
        title: String = "Test reminder",
        firstOccurrence: LocalDateTime = LocalDateTime.of(2026, 1, 1, 9, 0),
    ) = ReminderInput(
        title = title,
        description = null,
        enabled = true,
        firstOccurrence = firstOccurrence,
        intervalDays = 7,
    )

    private suspend fun <T> Flow<T>.await(predicate: (T) -> Boolean): T =
        withTimeout(5_000) { first(predicate) }
}
