package com.samuel.regularnotifications.notifications

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samuel.regularnotifications.data.ReminderRepository
import com.samuel.regularnotifications.data.ReminderService
import com.samuel.regularnotifications.data.RepositoryActionResult
import com.samuel.regularnotifications.data.local.ReminderDatabase
import com.samuel.regularnotifications.domain.NotificationKind
import com.samuel.regularnotifications.domain.ReminderEventType
import com.samuel.regularnotifications.domain.ReminderInput
import com.samuel.regularnotifications.scheduling.AlarmDeliveryRequest
import com.samuel.regularnotifications.scheduling.ReminderScheduler
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationActionProcessorTest {
    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-01-01T10:00:00Z")
    private lateinit var database: ReminderDatabase
    private lateinit var repository: ReminderRepository
    private lateinit var scheduler: RecordingScheduler
    private lateinit var processor: NotificationActionProcessor

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ReminderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ReminderRepository(database, clock = { now })
        scheduler = RecordingScheduler()
        processor = NotificationActionProcessor(ReminderService(repository, scheduler), scheduler)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun doneCancelsTheDisplayedDueNotificationAndReconcilesTheNextSchedule() = runBlocking {
        val reminderId = repository.createReminder(input(intervalDays = 7), utc, now)
        val due = database.outstandingDueDao().getByReminderId(reminderId)!!
        val reminder = database.reminderDao().getById(reminderId)!!

        val result = processor.process(
            request = request(
                reminderId = reminderId,
                kind = NotificationKind.DUE,
                action = NotificationAction.DONE,
                revision = due.revision,
                occurrenceIndex = due.normalOccurrenceIndex,
                reminderModifiedAt = reminder.modifiedAtEpochMillis,
            ),
            now = now,
            zoneId = utc,
        )

        assertEquals(RepositoryActionResult.APPLIED, result)
        assertEquals(listOf(reminderId), scheduler.cancelledDue)
        assertEquals(listOf(reminderId), scheduler.reconciled)
        assertNull(database.outstandingDueDao().getByReminderId(reminderId))
        assertEquals(listOf("DONE"), database.reminderEventDao().getForReminder(reminderId).map { it.action })
    }

    @Test
    fun postponedActionCancelsTheOldNotificationAndChangesOnlyTheOutstandingDueState() = runBlocking {
        val reminderId = repository.createReminder(input(intervalDays = 7), utc, now)
        val before = database.reminderDao().getById(reminderId)!!
        val due = database.outstandingDueDao().getByReminderId(reminderId)!!

        val result = processor.process(
            request = request(
                reminderId = reminderId,
                kind = NotificationKind.DUE,
                action = NotificationAction.POSTPONE_ONE_DAY,
                revision = due.revision,
                occurrenceIndex = due.normalOccurrenceIndex,
                reminderModifiedAt = before.modifiedAtEpochMillis,
            ),
            now = now,
            zoneId = utc,
        )

        val postponed = database.outstandingDueDao().getByReminderId(reminderId)!!
        val after = database.reminderDao().getById(reminderId)!!
        assertEquals(RepositoryActionResult.APPLIED, result)
        assertEquals(listOf(reminderId), scheduler.cancelledDue)
        assertTrue(postponed.revision > due.revision)
        assertEquals(due.normalOccurrenceIndex, postponed.normalOccurrenceIndex)
        assertEquals(before.anchorLocalDate, after.anchorLocalDate)
        assertEquals(before.anchorLocalTime, after.anchorLocalTime)
        assertEquals(before.nextNormalOccurrenceIndex, after.nextNormalOccurrenceIndex)
        assertEquals(listOf("POSTPONED"), database.reminderEventDao().getForReminder(reminderId).map { it.action })
    }

    @Test
    fun staleActionReconcilesWithoutCancellingTheCurrentNotification() = runBlocking {
        val reminderId = repository.createReminder(input(intervalDays = 7), utc, now)
        val reminder = database.reminderDao().getById(reminderId)!!
        val due = database.outstandingDueDao().getByReminderId(reminderId)!!
        repository.postpone(
            id = reminderId,
            expectedRevision = due.revision,
            expectedNormalOccurrenceIndex = due.normalOccurrenceIndex,
            expectedReminderModifiedAtEpochMillis = reminder.modifiedAtEpochMillis,
            zoneId = utc,
            now = now,
        )

        val result = processor.process(
            request = request(
                reminderId = reminderId,
                kind = NotificationKind.DUE,
                action = NotificationAction.DONE,
                revision = due.revision,
                occurrenceIndex = due.normalOccurrenceIndex,
                reminderModifiedAt = reminder.modifiedAtEpochMillis,
            ),
            now = now,
            zoneId = utc,
        )

        assertEquals(RepositoryActionResult.STALE_REVISION, result)
        assertTrue(scheduler.cancelledDue.isEmpty())
        assertEquals(listOf(reminderId), scheduler.reconciled)
        assertEquals(listOf("POSTPONED"), database.reminderEventDao().getForReminder(reminderId).map { it.action })
    }

    @Test
    fun tomorrowSeenAcknowledgesOnlyThePreviewAndCancelsItsNotification() = runBlocking {
        val scheduledNow = Instant.parse("2026-01-01T08:00:00Z")
        val reminderId = repository.createReminder(
            input(
                firstOccurrence = LocalDateTime.of(2026, 1, 2, 9, 0),
                intervalDays = 7,
            ),
            utc,
            scheduledNow,
        )
        val preview = database.tomorrowPreviewDao().getByReminderId(reminderId)!!
        val reminder = database.reminderDao().getById(reminderId)!!

        val result = processor.process(
            request = request(
                reminderId = reminderId,
                kind = NotificationKind.TOMORROW,
                action = NotificationAction.TOMORROW_SEEN,
                revision = preview.revision,
                occurrenceIndex = preview.normalOccurrenceIndex,
                reminderModifiedAt = reminder.modifiedAtEpochMillis,
            ),
            now = Instant.parse("2026-01-01T09:10:00Z"),
            zoneId = utc,
        )

        assertEquals(RepositoryActionResult.APPLIED, result)
        assertEquals(listOf(reminderId), scheduler.cancelledTomorrow)
        assertTrue(database.tomorrowPreviewDao().getByReminderId(reminderId)!!.acknowledged)
        assertNull(database.outstandingDueDao().getByReminderId(reminderId))
        assertEquals(listOf("TOMORROW_SEEN"), database.reminderEventDao().getForReminder(reminderId).map { it.action })
    }

    private fun request(
        reminderId: Long,
        kind: NotificationKind,
        action: NotificationAction,
        revision: Long,
        occurrenceIndex: Long,
        reminderModifiedAt: Long,
    ) = NotificationActionRequest(
        reminderId = reminderId,
        notificationKind = kind,
        action = action,
        expectedRevision = revision,
        expectedNormalOccurrenceIndex = occurrenceIndex,
        expectedReminderModifiedAtEpochMillis = reminderModifiedAt,
    )

    private fun input(
        firstOccurrence: LocalDateTime = LocalDateTime.of(2026, 1, 1, 9, 0),
        intervalDays: Int,
    ) = ReminderInput(
        title = "Test reminder",
        description = "Description",
        enabled = true,
        firstOccurrence = firstOccurrence,
        intervalDays = intervalDays,
    )

    private class RecordingScheduler : ReminderScheduler {
        val cancelledDue = mutableListOf<Long>()
        val cancelledTomorrow = mutableListOf<Long>()
        val cancelledAll = mutableListOf<Long>()
        val reconciled = mutableListOf<Long>()

        override suspend fun reconcileReminder(reminderId: Long, now: Instant, zoneId: ZoneId) {
            reconciled += reminderId
        }

        override suspend fun reconcileAll(now: Instant, zoneId: ZoneId) = Unit

        override suspend fun deliver(request: AlarmDeliveryRequest, now: Instant, zoneId: ZoneId) = Unit

        override fun cancelDue(reminderId: Long) {
            cancelledDue += reminderId
        }

        override fun cancelTomorrow(reminderId: Long) {
            cancelledTomorrow += reminderId
        }

        override fun cancelAll(reminderId: Long) {
            cancelledAll += reminderId
        }
    }
}
