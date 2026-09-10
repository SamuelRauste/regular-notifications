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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
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
        val initialReminder = database.reminderDao().getById(reminderId)!!

        assertEquals(0, initialDue.normalOccurrenceIndex)
        assertEquals(
            RepositoryActionResult.APPLIED,
            repository.postpone(
                reminderId,
                expectedRevision = initialDue.revision,
                expectedNormalOccurrenceIndex = initialDue.normalOccurrenceIndex,
                expectedReminderModifiedAtEpochMillis = initialReminder.modifiedAtEpochMillis,
                zoneId = utc,
                now = now,
            ),
        )
        val postponed = database.outstandingDueDao().getByReminderId(reminderId)!!
        assertEquals(initialDue.normalOccurrenceIndex, postponed.normalOccurrenceIndex)
        assertTrue(postponed.revision > initialDue.revision)

        assertEquals(
            RepositoryActionResult.STALE_REVISION,
            repository.postpone(
                reminderId,
                expectedRevision = initialDue.revision,
                expectedNormalOccurrenceIndex = initialDue.normalOccurrenceIndex,
                expectedReminderModifiedAtEpochMillis = initialReminder.modifiedAtEpochMillis,
                zoneId = utc,
                now = now,
            ),
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
    fun doneRecordsOneEventAdvancesCursorAndPreservesTheAnchor() = runBlocking {
        val reminderId = repository.createReminder(
            input().copy(intervalDays = 7),
            utc,
            now,
        )
        val before = database.reminderDao().getById(reminderId)!!
        val due = database.outstandingDueDao().getByReminderId(reminderId)!!

        assertEquals(
            RepositoryActionResult.APPLIED,
            repository.resolve(
                id = reminderId,
                eventType = ReminderEventType.DONE,
                expectedRevision = due.revision,
                expectedNormalOccurrenceIndex = due.normalOccurrenceIndex,
                expectedReminderModifiedAtEpochMillis = before.modifiedAtEpochMillis,
                zoneId = utc,
                now = now,
            ),
        )

        val after = database.reminderDao().getById(reminderId)!!
        assertEquals(before.anchorLocalDate, after.anchorLocalDate)
        assertEquals(before.anchorLocalTime, after.anchorLocalTime)
        assertEquals(before.intervalDays, after.intervalDays)
        assertEquals(0L, after.lastResolvedNormalOccurrenceIndex)
        assertEquals(1L, after.nextNormalOccurrenceIndex)
        assertNull(database.outstandingDueDao().getByReminderId(reminderId))
        assertEquals(
            listOf("DONE"),
            database.reminderEventDao().getForReminder(reminderId).map { it.action },
        )
    }

    @Test
    fun dismissRecordsOneEventAndPreservesTheNormalSchedule() = runBlocking {
        val reminderId = repository.createReminder(input().copy(intervalDays = 7), utc, now)
        val before = database.reminderDao().getById(reminderId)!!
        val due = database.outstandingDueDao().getByReminderId(reminderId)!!

        assertEquals(
            RepositoryActionResult.APPLIED,
            repository.resolve(
                id = reminderId,
                eventType = ReminderEventType.DISMISSED,
                expectedRevision = due.revision,
                expectedNormalOccurrenceIndex = due.normalOccurrenceIndex,
                expectedReminderModifiedAtEpochMillis = before.modifiedAtEpochMillis,
                zoneId = utc,
                now = now,
            ),
        )

        val after = database.reminderDao().getById(reminderId)!!
        assertEquals(before.anchorLocalDate, after.anchorLocalDate)
        assertEquals(before.anchorLocalTime, after.anchorLocalTime)
        assertEquals(before.intervalDays, after.intervalDays)
        assertEquals(listOf("DISMISSED"), database.reminderEventDao().getForReminder(reminderId).map { it.action })
    }

    @Test
    fun staleDoneCannotResolveTheNextOccurrenceWhenARevisionNumberRepeats() = runBlocking {
        val reminderId = repository.createReminder(input(), utc, now)
        val before = database.reminderDao().getById(reminderId)!!
        val firstDue = database.outstandingDueDao().getByReminderId(reminderId)!!

        assertEquals(
            RepositoryActionResult.APPLIED,
            repository.resolve(
                id = reminderId,
                eventType = ReminderEventType.DONE,
                expectedRevision = firstDue.revision,
                expectedNormalOccurrenceIndex = firstDue.normalOccurrenceIndex,
                expectedReminderModifiedAtEpochMillis = before.modifiedAtEpochMillis,
                zoneId = utc,
                now = now,
            ),
        )

        val later = Instant.parse("2026-01-02T10:00:00Z")
        val secondDue = database.outstandingDueDao().getByReminderId(reminderId)
            ?: run {
                repository.reconcileReminder(reminderId, utc, later)
                database.outstandingDueDao().getByReminderId(reminderId)!!
            }
        assertEquals(firstDue.revision, secondDue.revision)
        assertNotEquals(firstDue.normalOccurrenceIndex, secondDue.normalOccurrenceIndex)

        assertEquals(
            RepositoryActionResult.STALE_REVISION,
            repository.resolve(
                id = reminderId,
                eventType = ReminderEventType.DONE,
                expectedRevision = firstDue.revision,
                expectedNormalOccurrenceIndex = firstDue.normalOccurrenceIndex,
                expectedReminderModifiedAtEpochMillis = before.modifiedAtEpochMillis,
                zoneId = utc,
                now = later,
            ),
        )
        assertEquals(listOf("DONE"), database.reminderEventDao().getForReminder(reminderId).map { it.action })
    }

    @Test
    fun staleActionFromAnEditedReminderIsRejectedEvenWithTheSameClockInstant() = runBlocking {
        val reminderId = repository.createReminder(input(), utc, now)
        val before = database.reminderDao().getById(reminderId)!!
        val due = database.outstandingDueDao().getByReminderId(reminderId)!!

        assertEquals(
            RepositoryActionResult.APPLIED,
            repository.updateReminder(
                reminderId,
                input().copy(title = "Edited reminder"),
                utc,
                now,
            ),
        )
        val after = database.reminderDao().getById(reminderId)!!
        assertTrue(after.modifiedAtEpochMillis > before.modifiedAtEpochMillis)

        assertEquals(
            RepositoryActionResult.STALE_REVISION,
            repository.resolve(
                id = reminderId,
                eventType = ReminderEventType.DISMISSED,
                expectedRevision = due.revision,
                expectedNormalOccurrenceIndex = due.normalOccurrenceIndex,
                expectedReminderModifiedAtEpochMillis = before.modifiedAtEpochMillis,
                zoneId = utc,
                now = now,
            ),
        )
        assertEquals(emptyList<String>(), database.reminderEventDao().getForReminder(reminderId).map { it.action })
    }

    @Test
    fun titleOnlyEditPreservesResolvedCursorAndDoesNotResurrectOldOccurrences() = runBlocking {
        val start = Instant.parse("2026-09-01T08:00:00Z")
        val editAt = Instant.parse("2026-09-20T10:00:00Z")
        val reminderId = repository.createReminder(
            input().copy(
                firstOccurrence = LocalDateTime.of(2026, 9, 1, 9, 0),
                intervalDays = 7,
            ),
            utc,
            start,
        )
        repository.setEnabled(reminderId, enabled = false, zoneId = utc, now = editAt)
        repository.setEnabled(reminderId, enabled = true, zoneId = utc, now = editAt)
        val before = database.reminderDao().getById(reminderId)!!

        assertEquals(2L, before.lastResolvedNormalOccurrenceIndex)
        assertEquals(
            RepositoryActionResult.APPLIED,
            repository.updateReminder(
                reminderId,
                input().copy(
                    title = "Renamed reminder",
                    firstOccurrence = LocalDateTime.of(2026, 9, 1, 9, 0),
                    intervalDays = 7,
                ),
                utc,
                editAt,
            ),
        )

        val after = database.reminderDao().getById(reminderId)!!
        assertEquals("Renamed reminder", after.title)
        assertEquals(2L, after.lastResolvedNormalOccurrenceIndex)
        assertEquals(before.anchorLocalDate, after.anchorLocalDate)
        assertEquals(before.anchorLocalTime, after.anchorLocalTime)
        assertEquals(before.intervalDays, after.intervalDays)
        assertEquals(3L, after.nextNormalOccurrenceIndex)
        assertNull(database.outstandingDueDao().getByReminderId(reminderId))
        assertEquals(emptyList<String>(), database.reminderEventDao().getForReminder(reminderId).map { it.action })
    }

    @Test
    fun descriptionOnlyEditPreservesTheCurrentTomorrowPreviewWithoutHistory() = runBlocking {
        val previewNow = Instant.parse("2026-01-01T08:00:00Z")
        val reminderId = repository.createReminder(
            input().copy(
                firstOccurrence = LocalDateTime.of(2026, 1, 2, 9, 0),
                intervalDays = 7,
            ),
            utc,
            previewNow,
        )
        val before = database.reminderDao().getById(reminderId)!!
        val preview = database.tomorrowPreviewDao().getByReminderId(reminderId)!!

        assertEquals(
            RepositoryActionResult.APPLIED,
            repository.updateReminder(
                reminderId,
                input().copy(
                    description = "Updated description",
                    firstOccurrence = LocalDateTime.of(2026, 1, 2, 9, 0),
                    intervalDays = 7,
                ),
                utc,
                previewNow,
            ),
        )

        val after = database.reminderDao().getById(reminderId)!!
        val retainedPreview = database.tomorrowPreviewDao().getByReminderId(reminderId)!!
        assertEquals("Updated description", after.description)
        assertEquals(before.lastResolvedNormalOccurrenceIndex, after.lastResolvedNormalOccurrenceIndex)
        assertEquals(preview.normalOccurrenceIndex, retainedPreview.normalOccurrenceIndex)
        assertEquals(preview.revision, retainedPreview.revision)
        assertFalse(retainedPreview.acknowledged)
        assertEquals(emptyList<String>(), database.reminderEventDao().getForReminder(reminderId).map { it.action })
    }

    @Test
    fun metadataEditKeepsCurrentDueUnresolvedAndRejectsTheOldAction() = runBlocking {
        val reminderId = repository.createReminder(input().copy(intervalDays = 7), utc, now)
        val before = database.reminderDao().getById(reminderId)!!
        val due = database.outstandingDueDao().getByReminderId(reminderId)!!

        assertEquals(
            RepositoryActionResult.APPLIED,
            repository.updateReminder(
                reminderId,
                input().copy(title = "Updated due title", intervalDays = 7),
                utc,
                now,
            ),
        )

        val after = database.reminderDao().getById(reminderId)!!
        val retainedDue = database.outstandingDueDao().getByReminderId(reminderId)!!
        val snapshot = repository.reconcileReminderForScheduling(reminderId, utc, now)!!
        assertEquals("Updated due title", snapshot.definition.title)
        assertEquals(due.normalOccurrenceIndex, retainedDue.normalOccurrenceIndex)
        assertEquals(due.revision, retainedDue.revision)
        assertNull(after.lastResolvedNormalOccurrenceIndex)
        assertEquals(emptyList<String>(), database.reminderEventDao().getForReminder(reminderId).map { it.action })
        assertEquals(
            RepositoryActionResult.STALE_REVISION,
            repository.postpone(
                id = reminderId,
                expectedRevision = due.revision,
                expectedNormalOccurrenceIndex = due.normalOccurrenceIndex,
                expectedReminderModifiedAtEpochMillis = before.modifiedAtEpochMillis,
                zoneId = utc,
                now = now,
            ),
        )
        assertEquals(
            RepositoryActionResult.STALE_REVISION,
            repository.resolve(
                id = reminderId,
                eventType = ReminderEventType.DONE,
                expectedRevision = due.revision,
                expectedNormalOccurrenceIndex = due.normalOccurrenceIndex,
                expectedReminderModifiedAtEpochMillis = before.modifiedAtEpochMillis,
                zoneId = utc,
                now = now,
            ),
        )
        assertNotNull(database.outstandingDueDao().getByReminderId(reminderId))
    }

    @Test
    fun metadataEditPreservesAcknowledgedTomorrowAndRejectsAnOldSeenAction() = runBlocking {
        val previewNow = Instant.parse("2026-01-01T08:00:00Z")
        val reminderId = repository.createReminder(
            input().copy(
                firstOccurrence = LocalDateTime.of(2026, 1, 2, 9, 0),
                intervalDays = 7,
            ),
            utc,
            previewNow,
        )
        val before = database.reminderDao().getById(reminderId)!!
        val oldPreview = database.tomorrowPreviewDao().getByReminderId(reminderId)!!

        assertEquals(
            RepositoryActionResult.APPLIED,
            repository.updateReminder(
                reminderId,
                input().copy(
                    description = "Updated preview description",
                    firstOccurrence = LocalDateTime.of(2026, 1, 2, 9, 0),
                    intervalDays = 7,
                ),
                utc,
                previewNow,
            ),
        )
        assertEquals(
            RepositoryActionResult.STALE_REVISION,
            repository.acknowledgeTomorrow(
                id = reminderId,
                expectedRevision = oldPreview.revision,
                expectedNormalOccurrenceIndex = oldPreview.normalOccurrenceIndex,
                expectedReminderModifiedAtEpochMillis = before.modifiedAtEpochMillis,
                zoneId = utc,
                now = previewNow,
            ),
        )

        val currentReminder = database.reminderDao().getById(reminderId)!!
        val currentPreview = database.tomorrowPreviewDao().getByReminderId(reminderId)!!
        assertEquals(
            RepositoryActionResult.APPLIED,
            repository.acknowledgeTomorrow(
                id = reminderId,
                expectedRevision = currentPreview.revision,
                expectedNormalOccurrenceIndex = currentPreview.normalOccurrenceIndex,
                expectedReminderModifiedAtEpochMillis = currentReminder.modifiedAtEpochMillis,
                zoneId = utc,
                now = previewNow,
            ),
        )

        val acknowledged = database.tomorrowPreviewDao().getByReminderId(reminderId)!!
        assertTrue(acknowledged.acknowledged)
        assertEquals(listOf("TOMORROW_SEEN"), database.reminderEventDao().getForReminder(reminderId).map { it.action })
        assertEquals(
            RepositoryActionResult.APPLIED,
            repository.updateReminder(
                reminderId,
                input().copy(
                    description = "Another description",
                    firstOccurrence = LocalDateTime.of(2026, 1, 2, 9, 0),
                    intervalDays = 7,
                ),
                utc,
                previewNow,
            ),
        )
        assertTrue(database.tomorrowPreviewDao().getByReminderId(reminderId)!!.acknowledged)
        assertEquals(listOf("TOMORROW_SEEN"), database.reminderEventDao().getForReminder(reminderId).map { it.action })
    }

    @Test
    fun scheduleEditResetsIncompatibleCursorAndSkipsReplacementPastOccurrences() = runBlocking {
        val start = Instant.parse("2026-09-01T08:00:00Z")
        val editAt = Instant.parse("2026-09-20T10:00:00Z")
        val reminderId = repository.createReminder(
            input().copy(
                firstOccurrence = LocalDateTime.of(2026, 9, 1, 9, 0),
                intervalDays = 7,
            ),
            utc,
            start,
        )
        repository.setEnabled(reminderId, enabled = false, zoneId = utc, now = editAt)
        assertEquals(2L, database.reminderDao().getById(reminderId)!!.lastResolvedNormalOccurrenceIndex)

        assertEquals(
            RepositoryActionResult.APPLIED,
            repository.updateReminder(
                reminderId,
                input().copy(
                    title = "Replacement schedule",
                    enabled = true,
                    firstOccurrence = LocalDateTime.of(2026, 9, 18, 9, 0),
                    intervalDays = 3,
                ),
                utc,
                editAt,
            ),
        )

        val after = database.reminderDao().getById(reminderId)!!
        assertTrue(after.enabled)
        assertEquals("2026-09-18", after.anchorLocalDate)
        assertEquals(3, after.intervalDays)
        assertEquals(0L, after.lastResolvedNormalOccurrenceIndex)
        assertEquals(1L, after.nextNormalOccurrenceIndex)
        assertEquals(Instant.parse("2026-09-21T09:00:00Z").toEpochMilli(), after.nextNormalOccurrenceEpochMillis)
        assertNull(database.outstandingDueDao().getByReminderId(reminderId))
        assertNull(database.tomorrowPreviewDao().getByReminderId(reminderId))
        assertEquals(emptyList<String>(), database.reminderEventDao().getForReminder(reminderId).map { it.action })
    }

    @Test
    fun editingEnabledReminderAsDisabledClearsDerivedStateWithoutHistory() = runBlocking {
        val reminderId = repository.createReminder(input().copy(intervalDays = 7), utc, now)
        val before = database.reminderDao().getById(reminderId)!!
        assertNotNull(database.outstandingDueDao().getByReminderId(reminderId))

        assertEquals(
            RepositoryActionResult.APPLIED,
            repository.updateReminder(
                reminderId,
                input().copy(enabled = false, intervalDays = 7),
                utc,
                now,
            ),
        )

        val after = database.reminderDao().getById(reminderId)!!
        assertFalse(after.enabled)
        assertEquals(before.anchorLocalDate, after.anchorLocalDate)
        assertEquals(before.anchorLocalTime, after.anchorLocalTime)
        assertEquals(0L, after.lastResolvedNormalOccurrenceIndex)
        assertNull(database.outstandingDueDao().getByReminderId(reminderId))
        assertNull(database.tomorrowPreviewDao().getByReminderId(reminderId))
        assertEquals(emptyList<String>(), database.reminderEventDao().getForReminder(reminderId).map { it.action })
    }

    @Test
    fun editingWhileMasterPausedKeepsDeliveryStateEmpty() = runBlocking {
        val reminderId = repository.createReminder(input().copy(intervalDays = 7), utc, now)
        assertEquals(RepositoryActionResult.APPLIED, repository.setMasterEnabled(false, utc, now))

        assertEquals(
            RepositoryActionResult.APPLIED,
            repository.updateReminder(
                reminderId,
                input().copy(title = "Edited while paused", intervalDays = 7),
                utc,
                now,
            ),
        )

        val after = database.reminderDao().getById(reminderId)!!
        assertTrue(after.enabled)
        assertFalse(database.appSettingsDao().get()!!.masterEnabled)
        assertNull(database.outstandingDueDao().getByReminderId(reminderId))
        assertNull(database.tomorrowPreviewDao().getByReminderId(reminderId))
        assertEquals(emptyList<String>(), database.reminderEventDao().getForReminder(reminderId).map { it.action })
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
        val reminder = database.reminderDao().getById(reminderId)!!

        assertEquals(
            RepositoryActionResult.APPLIED,
            repository.acknowledgeTomorrow(
                reminderId,
                expectedRevision = preview.revision,
                expectedNormalOccurrenceIndex = preview.normalOccurrenceIndex,
                expectedReminderModifiedAtEpochMillis = reminder.modifiedAtEpochMillis,
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
        assertEquals(0L, database.reminderDao().getById(reminderId)!!.nextNormalOccurrenceIndex)
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
    fun globalPausePersistsSkipsOutstandingWorkAndKeepsIndividualState() = runBlocking {
        val reminderId = repository.createReminder(
            input().copy(
                firstOccurrence = LocalDateTime.of(2026, 1, 1, 9, 0),
                intervalDays = 7,
            ),
            utc,
            now,
        )
        val anchorBeforePause = database.reminderDao().getById(reminderId)!!

        assertNotNull(database.outstandingDueDao().getByReminderId(reminderId))
        assertEquals(
            RepositoryActionResult.APPLIED,
            repository.setMasterEnabled(enabled = false, zoneId = utc, now = now),
        )

        val paused = database.reminderDao().getById(reminderId)!!
        assertFalse(database.appSettingsDao().get()!!.masterEnabled)
        assertTrue(paused.enabled)
        assertEquals(0L, paused.lastResolvedNormalOccurrenceIndex)
        assertNull(database.outstandingDueDao().getByReminderId(reminderId))
        assertNull(database.tomorrowPreviewDao().getByReminderId(reminderId))
        assertEquals(emptyList<String>(), database.reminderEventDao().getForReminder(reminderId).map { it.action })
        assertEquals(anchorBeforePause.anchorLocalDate, paused.anchorLocalDate)
        assertEquals(anchorBeforePause.anchorLocalTime, paused.anchorLocalTime)

        val recreatedRepository = ReminderRepository(database, clock = { now })
        assertFalse(recreatedRepository.getMasterEnabled())

        val resumedAt = Instant.parse("2026-01-20T10:00:00Z")
        assertEquals(
            RepositoryActionResult.APPLIED,
            recreatedRepository.setMasterEnabled(enabled = true, zoneId = utc, now = resumedAt),
        )

        val resumed = database.reminderDao().getById(reminderId)!!
        assertTrue(database.appSettingsDao().get()!!.masterEnabled)
        assertTrue(resumed.enabled)
        assertEquals(2L, resumed.lastResolvedNormalOccurrenceIndex)
        assertEquals(3L, resumed.nextNormalOccurrenceIndex)
        assertNull(database.outstandingDueDao().getByReminderId(reminderId))
        assertEquals(emptyList<String>(), database.reminderEventDao().getForReminder(reminderId).map { it.action })
    }

    @Test
    fun globalResumeDoesNotEnableAnIndividuallyDisabledReminder() = runBlocking {
        val reminderId = repository.createReminder(
            input().copy(
                enabled = false,
                firstOccurrence = LocalDateTime.of(2026, 1, 1, 9, 0),
                intervalDays = 7,
            ),
            utc,
            now,
        )

        repository.setMasterEnabled(enabled = false, zoneId = utc, now = now)
        repository.setMasterEnabled(
            enabled = true,
            zoneId = utc,
            now = Instant.parse("2026-01-20T10:00:00Z"),
        )

        val stored = database.reminderDao().getById(reminderId)!!
        assertFalse(stored.enabled)
        assertNull(database.outstandingDueDao().getByReminderId(reminderId))
        assertNull(database.tomorrowPreviewDao().getByReminderId(reminderId))
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
