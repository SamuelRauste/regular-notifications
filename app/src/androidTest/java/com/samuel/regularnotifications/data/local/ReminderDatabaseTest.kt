package com.samuel.regularnotifications.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
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
class ReminderDatabaseTest {
    private lateinit var database: ReminderDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ReminderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun scheduleRowsUpsertAndReminderDeletionCascadesHistory() = runBlocking {
        val reminderId = database.reminderDao().insert(reminder())
        val now = Instant.parse("2026-01-01T10:00:00Z").toEpochMilli()

        database.outstandingDueDao().upsert(
            OutstandingDueEntity(
                reminderId = reminderId,
                normalOccurrenceIndex = 0,
                normalOccurrenceEpochMillis = now - 3_600_000,
                dueAtEpochMillis = now - 3_600_000,
                postponedUntilEpochMillis = null,
                postponementCount = 0,
                revision = 1,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
        )
        database.reminderEventDao().insert(
            ReminderEventEntity(
                reminderId = reminderId,
                normalOccurrenceIndex = 0,
                occurrenceScheduledEpochMillis = now - 3_600_000,
                action = "DISMISSED",
                dueAtEpochMillis = now - 3_600_000,
                postponedUntilEpochMillis = null,
                occurredAtEpochMillis = now,
            ),
        )

        val replacedDue = database.outstandingDueDao().getByReminderId(reminderId)!!.copy(revision = 2)
        database.outstandingDueDao().upsert(replacedDue)

        assertEquals(2, database.outstandingDueDao().getByReminderId(reminderId)?.revision)
        database.outstandingDueDao().deleteByReminderId(reminderId)
        database.tomorrowPreviewDao().upsert(
            TomorrowPreviewEntity(
                reminderId = reminderId,
                normalOccurrenceIndex = 1,
                occurrenceEpochMillis = now + 86_400_000,
                previewEpochMillis = now + 82_800_000,
                zoneId = "UTC",
                acknowledged = false,
                revision = 1,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
        )
        assertNotNull(database.tomorrowPreviewDao().getByReminderId(reminderId))
        assertEquals(1, database.reminderEventDao().getForReminder(reminderId).size)

        database.reminderDao().deleteById(reminderId)

        assertNull(database.reminderDao().getById(reminderId))
        assertNull(database.outstandingDueDao().getByReminderId(reminderId))
        assertNull(database.tomorrowPreviewDao().getByReminderId(reminderId))
        assertTrue(database.reminderEventDao().getForReminder(reminderId).isEmpty())
    }

    private fun reminder() = ReminderEntity(
        title = "Test reminder",
        description = null,
        enabled = true,
        anchorLocalDate = "2026-01-01",
        anchorLocalTime = "09:00",
        intervalDays = 1,
        nextNormalOccurrenceIndex = 1,
        nextNormalOccurrenceEpochMillis = Instant.parse("2026-01-02T09:00:00Z").toEpochMilli(),
        nextNormalZoneId = "UTC",
        lastResolvedNormalOccurrenceIndex = null,
        createdAtEpochMillis = 0,
        modifiedAtEpochMillis = 0,
    )
}
