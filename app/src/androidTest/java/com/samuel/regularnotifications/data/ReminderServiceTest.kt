package com.samuel.regularnotifications.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samuel.regularnotifications.data.local.ReminderDatabase
import com.samuel.regularnotifications.domain.ReminderInput
import com.samuel.regularnotifications.domain.ReminderSchedulingSnapshot
import com.samuel.regularnotifications.scheduling.AlarmDeliveryRequest
import com.samuel.regularnotifications.scheduling.ReminderScheduler
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderServiceTest {
    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-01-01T10:00:00Z")
    private lateinit var database: ReminderDatabase
    private lateinit var repository: ReminderRepository
    private lateinit var scheduler: RecordingScheduler
    private lateinit var service: ReminderService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ReminderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ReminderRepository(database, clock = { now })
        scheduler = RecordingScheduler(repository)
        service = ReminderService(repository, scheduler)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun metadataEditCancelsOldTargetsThenRebuildsTheCurrentDueWithNewContent() = runBlocking {
        val reminderId = repository.createReminder(input(intervalDays = 7), utc, now)
        val oldDue = database.outstandingDueDao().getByReminderId(reminderId)!!

        assertEquals(
            RepositoryActionResult.APPLIED,
            service.updateReminder(
                reminderId,
                input(title = "Updated title", intervalDays = 7),
                utc,
                now,
            ),
        )

        assertEquals(listOf("cancelAll", "reconcile"), scheduler.operations)
        val rebuilt = scheduler.reconciledSnapshots.single()
        assertEquals("Updated title", rebuilt.definition.title)
        assertEquals(oldDue.normalOccurrenceIndex, rebuilt.state.outstandingDue?.normalOccurrenceIndex)
        assertEquals(oldDue.revision, rebuilt.state.outstandingDue?.revision)
        assertEquals(emptyList<String>(), database.reminderEventDao().getForReminder(reminderId).map { it.action })
    }

    @Test
    fun metadataEditCancelsOldTargetsThenRebuildsTheCurrentTomorrowPreview() = runBlocking {
        val previewNow = Instant.parse("2026-01-01T08:00:00Z")
        val reminderId = repository.createReminder(
            input(
                description = "Before",
                firstOccurrence = LocalDateTime.of(2026, 1, 2, 9, 0),
                intervalDays = 7,
            ),
            utc,
            previewNow,
        )
        val oldPreview = database.tomorrowPreviewDao().getByReminderId(reminderId)!!

        assertEquals(
            RepositoryActionResult.APPLIED,
            service.updateReminder(
                reminderId,
                input(
                    description = "After",
                    firstOccurrence = LocalDateTime.of(2026, 1, 2, 9, 0),
                    intervalDays = 7,
                ),
                utc,
                previewNow,
            ),
        )

        assertEquals(listOf("cancelAll", "reconcile"), scheduler.operations)
        val rebuilt = scheduler.reconciledSnapshots.single()
        assertEquals("After", rebuilt.definition.description)
        assertEquals(oldPreview.normalOccurrenceIndex, rebuilt.state.tomorrowPreview?.normalOccurrenceIndex)
        assertFalse(rebuilt.state.tomorrowPreview!!.acknowledged)
        assertEquals(emptyList<String>(), database.reminderEventDao().getForReminder(reminderId).map { it.action })
    }

    @Test
    fun deleteStillCancelsAllDerivedTargetsBeforeRemovingRoomState() = runBlocking {
        val reminderId = repository.createReminder(input(intervalDays = 7), utc, now)

        assertEquals(RepositoryActionResult.APPLIED, service.deleteReminder(reminderId))

        assertEquals(listOf("cancelAll"), scheduler.operations)
        assertNull(database.reminderDao().getById(reminderId))
        assertNull(database.outstandingDueDao().getByReminderId(reminderId))
        assertNull(database.tomorrowPreviewDao().getByReminderId(reminderId))
        assertEquals(emptyList<String>(), database.reminderEventDao().getForReminder(reminderId).map { it.action })
    }

    private fun input(
        title: String = "Test reminder",
        description: String = "Description",
        firstOccurrence: LocalDateTime = LocalDateTime.of(2026, 1, 1, 9, 0),
        intervalDays: Int,
    ) = ReminderInput(
        title = title,
        description = description,
        enabled = true,
        firstOccurrence = firstOccurrence,
        intervalDays = intervalDays,
    )

    private class RecordingScheduler(
        private val repository: ReminderRepository,
    ) : ReminderScheduler {
        val operations = mutableListOf<String>()
        val reconciledSnapshots = mutableListOf<ReminderSchedulingSnapshot>()

        override suspend fun reconcileReminder(reminderId: Long, now: Instant, zoneId: ZoneId) {
            operations += "reconcile"
            reconciledSnapshots += requireNotNull(
                repository.reconcileReminderForScheduling(reminderId, zoneId, now),
            )
        }

        override suspend fun reconcileAll(now: Instant, zoneId: ZoneId) = Unit

        override suspend fun deliver(request: AlarmDeliveryRequest, now: Instant, zoneId: ZoneId) = Unit

        override fun cancelDue(reminderId: Long) = Unit

        override fun cancelTomorrow(reminderId: Long) = Unit

        override fun cancelAll(reminderId: Long) {
            operations += "cancelAll"
        }
    }
}
