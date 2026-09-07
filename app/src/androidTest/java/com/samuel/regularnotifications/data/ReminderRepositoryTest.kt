package com.samuel.regularnotifications.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samuel.regularnotifications.data.local.ReminderDatabase
import com.samuel.regularnotifications.domain.ReminderEventType
import com.samuel.regularnotifications.domain.ReminderInput
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderRepositoryTest {
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
    fun actionProcessingUsesRevisionsAndRecordsDistinctEvents() = runBlocking {
        val reminderId = repository.createReminder(input(), utc, now)
        val initialDue = database.outstandingDueDao().getByReminderId(reminderId)!!

        assertEquals(0, initialDue.normalOccurrenceIndex)
        assertEquals(
            RepositoryActionResult.APPLIED,
            repository.postpone(reminderId, expectedRevision = initialDue.revision, zoneId = utc, now = now),
        )
        val postponed = database.outstandingDueDao().getByReminderId(reminderId)!!
        assertEquals(initialDue.normalOccurrenceIndex, postponed.normalOccurrenceIndex)
        assertTrue(postponed.revision > initialDue.revision)

        assertEquals(
            RepositoryActionResult.STALE_REVISION,
            repository.postpone(reminderId, expectedRevision = initialDue.revision, zoneId = utc, now = now),
        )
        assertEquals(
            RepositoryActionResult.APPLIED,
            repository.resolve(
                reminderId,
                eventType = ReminderEventType.DISMISSED,
                expectedRevision = postponed.revision,
                zoneId = utc,
                now = now,
            ),
        )

        val events = database.reminderEventDao().getForReminder(reminderId)
        assertEquals(listOf("DISMISSED", "POSTPONED"), events.map { it.action })
        assertNull(database.outstandingDueDao().getByReminderId(reminderId))
    }

    @Test
    fun updateClearsOldScheduleStateAndDeleteRemovesReminder() = runBlocking {
        val reminderId = repository.createReminder(input(), utc, now)
        assertNotNull(database.outstandingDueDao().getByReminderId(reminderId))

        val updated = input().copy(
            title = "Updated reminder",
            firstOccurrence = LocalDateTime.of(2026, 1, 5, 12, 0),
            intervalDays = 14,
        )
        assertEquals(
            RepositoryActionResult.APPLIED,
            repository.updateReminder(reminderId, updated, utc, now),
        )

        val stored = database.reminderDao().getById(reminderId)!!
        assertEquals("Updated reminder", stored.title)
        assertEquals(14, stored.intervalDays)
        assertEquals(0, stored.nextNormalOccurrenceIndex)
        assertNull(database.outstandingDueDao().getByReminderId(reminderId))

        assertEquals(RepositoryActionResult.APPLIED, repository.deleteReminder(reminderId))
        assertNull(database.reminderDao().getById(reminderId))
    }

    @Test
    fun lateTomorrowSeenIsIdempotentAndDoesNotResolveTheReminder() = runBlocking {
        val scheduledNow = Instant.parse("2026-01-01T08:00:00Z")
        val lateSeenNow = Instant.parse("2026-01-01T09:10:00Z")
        val reminderId = repository.createReminder(
            input().copy(
                firstOccurrence = LocalDateTime.of(2026, 1, 2, 9, 0),
                intervalDays = 7,
            ),
            utc,
            scheduledNow,
        )
        val preview = database.tomorrowPreviewDao().getByReminderId(reminderId)!!

        assertEquals(
            RepositoryActionResult.APPLIED,
            repository.acknowledgeTomorrow(
                reminderId,
                expectedRevision = preview.revision,
                zoneId = utc,
                now = lateSeenNow,
            ),
        )
        assertEquals(
            RepositoryActionResult.ALREADY_ACKNOWLEDGED,
            repository.acknowledgeTomorrow(reminderId, zoneId = utc, now = lateSeenNow),
        )

        assertTrue(database.tomorrowPreviewDao().getByReminderId(reminderId)!!.acknowledged)
        assertNull(database.outstandingDueDao().getByReminderId(reminderId))
        assertEquals(listOf("TOMORROW_SEEN"), database.reminderEventDao().getForReminder(reminderId).map { it.action })
    }

    @Test
    fun dailyOneDayReminderNeverPersistsATomorrowPreview() = runBlocking {
        val reminderId = repository.createReminder(
            input().copy(firstOccurrence = LocalDateTime.of(2026, 1, 2, 9, 0)),
            utc,
            Instant.parse("2026-01-01T08:00:00Z"),
        )

        assertNull(database.tomorrowPreviewDao().getByReminderId(reminderId))
    }

    @Test
    fun staleTomorrowRevisionIsRejectedAfterTimezoneReschedule() = runBlocking {
        val scheduledNow = Instant.parse("2025-12-31T23:00:00Z")
        val reminderId = repository.createReminder(
            input().copy(
                firstOccurrence = LocalDateTime.of(2026, 1, 2, 9, 0),
                intervalDays = 7,
            ),
            utc,
            scheduledNow,
        )
        val original = database.tomorrowPreviewDao().getByReminderId(reminderId)!!
        val tokyo = ZoneId.of("Asia/Tokyo")

        assertEquals(RepositoryActionResult.APPLIED, repository.reconcileReminder(reminderId, tokyo, scheduledNow))
        val rescheduled = database.tomorrowPreviewDao().getByReminderId(reminderId)!!
        assertTrue(rescheduled.revision > original.revision)

        assertEquals(
            RepositoryActionResult.STALE_REVISION,
            repository.acknowledgeTomorrow(
                reminderId,
                expectedRevision = original.revision,
                zoneId = tokyo,
                now = scheduledNow,
            ),
        )
    }

    @Test
    fun disablingSkipsTheDisabledPeriodWithoutCreatingHistoryEvents() = runBlocking {
        val disabledAt = Instant.parse("2026-09-01T08:00:00Z")
        val reenabledAt = Instant.parse("2026-09-20T10:00:00Z")
        val reminderId = repository.createReminder(
            input().copy(
                firstOccurrence = LocalDateTime.of(2026, 9, 1, 9, 0),
                intervalDays = 7,
            ),
            utc,
            disabledAt,
        )

        assertEquals(
            RepositoryActionResult.APPLIED,
            repository.setEnabled(reminderId, enabled = false, zoneId = utc, now = disabledAt),
        )
        assertEquals(false, database.reminderDao().getById(reminderId)!!.enabled)
        assertNull(database.outstandingDueDao().getByReminderId(reminderId))
        assertNull(database.tomorrowPreviewDao().getByReminderId(reminderId))
        assertNull(database.reminderDao().getById(reminderId)!!.lastResolvedNormalOccurrenceIndex)

        assertEquals(
            RepositoryActionResult.APPLIED,
            repository.setEnabled(reminderId, enabled = true, zoneId = utc, now = reenabledAt),
        )
        val resumed = database.reminderDao().getById(reminderId)!!
        assertEquals(true, resumed.enabled)
        assertEquals(2L, resumed.lastResolvedNormalOccurrenceIndex)
        assertEquals(3L, resumed.nextNormalOccurrenceIndex)
        assertEquals(
            Instant.parse("2026-09-22T09:00:00Z").toEpochMilli(),
            resumed.nextNormalOccurrenceEpochMillis,
        )
        assertNull(database.outstandingDueDao().getByReminderId(reminderId))
        assertEquals(emptyList<String>(), database.reminderEventDao().getForReminder(reminderId).map { it.action })

        repository.reconcileAll(zoneId = utc, now = reenabledAt)
        assertNull(database.outstandingDueDao().getByReminderId(reminderId))
        assertEquals(2L, database.reminderDao().getById(reminderId)!!.lastResolvedNormalOccurrenceIndex)
        assertEquals(emptyList<String>(), database.reminderEventDao().getForReminder(reminderId).map { it.action })

        repository.reconcileReminder(
            reminderId,
            zoneId = ZoneId.of("Asia/Tokyo"),
            now = reenabledAt,
        )
        val afterTravel = database.reminderDao().getById(reminderId)!!
        assertEquals(2L, afterTravel.lastResolvedNormalOccurrenceIndex)
        assertEquals(3L, afterTravel.nextNormalOccurrenceIndex)
        assertNull(database.outstandingDueDao().getByReminderId(reminderId))
        assertEquals(emptyList<String>(), database.reminderEventDao().getForReminder(reminderId).map { it.action })
    }

    @Test
    fun editingDisabledReminderAsEnabledAlsoSkipsPastOccurrences() = runBlocking {
        val disabledAt = Instant.parse("2026-09-01T08:00:00Z")
        val reenabledAt = Instant.parse("2026-09-20T10:00:00Z")
        val reminderId = repository.createReminder(
            input().copy(
                firstOccurrence = LocalDateTime.of(2026, 9, 1, 9, 0),
                intervalDays = 7,
            ),
            utc,
            disabledAt,
        )
        repository.setEnabled(reminderId, enabled = false, zoneId = utc, now = disabledAt)

        assertEquals(
            RepositoryActionResult.APPLIED,
            repository.updateReminder(
                reminderId,
                input().copy(
                    enabled = true,
                    firstOccurrence = LocalDateTime.of(2026, 9, 1, 9, 0),
                    intervalDays = 7,
                ),
                zoneId = utc,
                now = reenabledAt,
            ),
        )

        val resumed = database.reminderDao().getById(reminderId)!!
        assertTrue(resumed.enabled)
        assertEquals(2L, resumed.lastResolvedNormalOccurrenceIndex)
        assertEquals(3L, resumed.nextNormalOccurrenceIndex)
        assertNull(database.outstandingDueDao().getByReminderId(reminderId))
        assertEquals(emptyList<String>(), database.reminderEventDao().getForReminder(reminderId).map { it.action })
    }

    private fun input() = ReminderInput(
        title = "Test reminder",
        description = "Description",
        enabled = true,
        firstOccurrence = LocalDateTime.of(2026, 1, 1, 9, 0),
        intervalDays = 1,
    )
}
