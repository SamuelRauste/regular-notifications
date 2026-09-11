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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoveryRepositoryTest {
    private val utc = ZoneId.of("UTC")
    private lateinit var database: ReminderDatabase
    private lateinit var repository: ReminderRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ReminderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ReminderRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun missedOccurrencesCollapsePerReminderAndRepeatedRecoveryIsIdempotent() = runBlocking {
        val firstId = repository.createReminder(
            input("First", LocalDateTime.of(2026, 1, 1, 9, 0), intervalDays = 2),
            utc,
            Instant.parse("2026-01-01T08:00:00Z"),
        )
        val secondId = repository.createReminder(
            input("Second", LocalDateTime.of(2026, 1, 1, 10, 0), intervalDays = 7),
            utc,
            Instant.parse("2026-01-01T08:00:00Z"),
        )

        val recovered = repository.reconcileAllForScheduling(
            zoneId = utc,
            now = Instant.parse("2026-01-20T12:00:00Z"),
        )
        val first = recovered.single { it.definition.id == firstId }
        val second = recovered.single { it.definition.id == secondId }

        assertEquals(2, recovered.size)
        assertNotNull(first.state.outstandingDue)
        assertNotNull(second.state.outstandingDue)
        assertTrue(first.state.nextNormal.scheduledAt > Instant.parse("2026-01-20T12:00:00Z"))
        assertTrue(second.state.nextNormal.scheduledAt > Instant.parse("2026-01-20T12:00:00Z"))
        assertTrue(database.reminderEventDao().getForReminder(firstId).isEmpty())
        assertTrue(database.reminderEventDao().getForReminder(secondId).isEmpty())

        val repeated = repository.reconcileAllForScheduling(
            zoneId = utc,
            now = Instant.parse("2026-01-20T12:00:00Z"),
        )
        assertEquals(recovered, repeated)
    }

    @Test
    fun globalResumeUsesFreshRowsForMultipleRemindersAndRebuildsFuturePreview() = runBlocking {
        val pauseAt = Instant.parse("2026-01-01T10:00:00Z")
        val resumeAt = Instant.parse("2026-01-20T10:00:00Z")
        val missedIds = listOf(
            repository.createReminder(
                input("Missed early", LocalDateTime.of(2026, 1, 1, 9, 0), intervalDays = 7),
                utc,
                pauseAt,
            ),
            repository.createReminder(
                input("Missed late", LocalDateTime.of(2026, 1, 1, 9, 0), intervalDays = 7),
                utc,
                pauseAt,
            ),
        )
        val futureId = repository.createReminder(
            input("Future preview", LocalDateTime.of(2026, 1, 30, 9, 0), intervalDays = 7),
            utc,
            pauseAt,
        )
        val disabledId = repository.createReminder(
            input(
                "Individually disabled",
                LocalDateTime.of(2026, 1, 1, 9, 0),
                intervalDays = 7,
                enabled = false,
            ),
            utc,
            pauseAt,
        )
        val anchorsBeforePause = database.reminderDao().getAll().associateBy { it.id }

        repository.setMasterEnabled(enabled = false, zoneId = utc, now = pauseAt)
        val recreatedRepository = ReminderRepository(database)
        recreatedRepository.setMasterEnabled(enabled = true, zoneId = utc, now = resumeAt)

        missedIds.forEach { reminderId ->
            val stored = database.reminderDao().getById(reminderId)!!
            assertTrue(stored.enabled)
            assertEquals(2L, stored.lastResolvedNormalOccurrenceIndex)
            assertEquals(3L, stored.nextNormalOccurrenceIndex)
            assertEquals(
                Instant.parse("2026-01-22T09:00:00Z").toEpochMilli(),
                stored.nextNormalOccurrenceEpochMillis,
            )
            assertNull(database.outstandingDueDao().getByReminderId(reminderId))
            assertTrue(database.reminderEventDao().getForReminder(reminderId).isEmpty())
        }

        val future = recreatedRepository.reconcileReminderForScheduling(
            futureId,
            zoneId = utc,
            now = resumeAt,
        )!!
        assertNotNull(future.state.tomorrowPreview)
        assertEquals(0L, future.state.tomorrowPreview?.normalOccurrenceIndex)
        assertTrue(future.state.tomorrowPreview!!.previewEpochMillis > resumeAt.toEpochMilli())

        val disabled = database.reminderDao().getById(disabledId)!!
        assertFalse(disabled.enabled)
        assertNull(database.outstandingDueDao().getByReminderId(disabledId))
        assertTrue(database.reminderEventDao().getForReminder(disabledId).isEmpty())

        anchorsBeforePause.forEach { (reminderId, before) ->
            val after = database.reminderDao().getById(reminderId)!!
            assertEquals(before.anchorLocalDate, after.anchorLocalDate)
            assertEquals(before.anchorLocalTime, after.anchorLocalTime)
        }

        val beforeRepeat = recreatedRepository.reconcileAllForScheduling(
            zoneId = utc,
            now = resumeAt,
        )
        recreatedRepository.setMasterEnabled(enabled = true, zoneId = utc, now = resumeAt)
        val afterRepeat = recreatedRepository.reconcileAllForScheduling(
            zoneId = utc,
            now = resumeAt,
        )
        assertEquals(beforeRepeat, afterRepeat)
        assertTrue(database.reminderEventDao().getForReminder(futureId).isEmpty())
    }

    @Test
    fun postponedStateSurvivesRecreationAndNewNormalOccurrenceSupersedesIt() = runBlocking {
        val createdAt = Instant.parse("2026-01-01T10:00:00Z")
        val reminderId = repository.createReminder(
            input("Postponed", LocalDateTime.of(2026, 1, 1, 9, 0), intervalDays = 1),
            utc,
            createdAt,
        )
        val before = database.reminderDao().getById(reminderId)!!
        val due = database.outstandingDueDao().getByReminderId(reminderId)!!
        repository.postpone(
            id = reminderId,
            expectedRevision = due.revision,
            expectedNormalOccurrenceIndex = due.normalOccurrenceIndex,
            expectedReminderModifiedAtEpochMillis = before.modifiedAtEpochMillis,
            zoneId = utc,
            now = createdAt,
        )

        val afterRestart = ReminderRepository(database)
        val persisted = afterRestart.reconcileReminderForScheduling(
            reminderId,
            zoneId = utc,
            now = Instant.parse("2026-01-01T12:00:00Z"),
        )!!
        assertEquals(0L, persisted.state.outstandingDue?.normalOccurrenceIndex)
        assertEquals(
            Instant.parse("2026-01-02T09:00:00Z").toEpochMilli(),
            persisted.state.outstandingDue?.dueAtEpochMillis,
        )
        assertEquals(1, persisted.state.outstandingDue?.postponementCount)

        val newer = afterRestart.reconcileReminderForScheduling(
            reminderId,
            zoneId = utc,
            now = Instant.parse("2026-01-02T10:00:00Z"),
        )!!
        assertEquals(1L, newer.state.outstandingDue?.normalOccurrenceIndex)
        assertNull(newer.state.outstandingDue?.postponedUntilEpochMillis)
        assertEquals(0, newer.state.outstandingDue?.postponementCount)
        assertEquals(1, database.reminderEventDao().getForReminder(reminderId).size)
    }

    @Test
    fun resolvedIndividualAndGlobalPausesDoNotResurrectOrCreateHistory() = runBlocking {
        val resolvedId = repository.createReminder(
            input("Resolved", LocalDateTime.of(2026, 1, 1, 9, 0), intervalDays = 7),
            utc,
            Instant.parse("2026-01-01T10:00:00Z"),
        )
        val resolvedDue = database.outstandingDueDao().getByReminderId(resolvedId)!!
        val resolvedReminder = database.reminderDao().getById(resolvedId)!!
        repository.resolve(
            id = resolvedId,
            eventType = ReminderEventType.DONE,
            expectedRevision = resolvedDue.revision,
            expectedNormalOccurrenceIndex = resolvedDue.normalOccurrenceIndex,
            expectedReminderModifiedAtEpochMillis = resolvedReminder.modifiedAtEpochMillis,
            zoneId = utc,
            now = Instant.parse("2026-01-01T10:00:00Z"),
        )
        val afterRestart = ReminderRepository(database)
        val resolvedAgain = afterRestart.reconcileReminderForScheduling(
            resolvedId,
            zoneId = utc,
            now = Instant.parse("2026-01-01T11:00:00Z"),
        )!!
        assertNull(resolvedAgain.state.outstandingDue)
        assertEquals(1, database.reminderEventDao().getForReminder(resolvedId).size)

        val individuallyPausedId = afterRestart.createReminder(
            input("Individual", LocalDateTime.of(2026, 1, 1, 9, 0), intervalDays = 7),
            utc,
            Instant.parse("2026-01-01T08:00:00Z"),
        )
        afterRestart.setEnabled(
            individuallyPausedId,
            enabled = false,
            zoneId = utc,
            now = Instant.parse("2026-01-01T08:00:00Z"),
        )
        val resumedIndividual = afterRestart.setEnabled(
            individuallyPausedId,
            enabled = true,
            zoneId = utc,
            now = Instant.parse("2026-01-20T10:00:00Z"),
        )
        assertEquals(RepositoryActionResult.APPLIED, resumedIndividual)
        val individualSnapshot = afterRestart.reconcileReminderForScheduling(
            individuallyPausedId,
            zoneId = utc,
            now = Instant.parse("2026-01-20T10:00:00Z"),
        )!!
        assertNull(individualSnapshot.state.outstandingDue)
        assertTrue(database.reminderEventDao().getForReminder(individuallyPausedId).isEmpty())

        val globallyPausedId = afterRestart.createReminder(
            input("Global", LocalDateTime.of(2026, 1, 1, 9, 0), intervalDays = 7),
            utc,
            Instant.parse("2026-01-01T08:00:00Z"),
        )
        afterRestart.setMasterEnabled(
            enabled = false,
            zoneId = utc,
            now = Instant.parse("2026-01-01T08:00:00Z"),
        )
        afterRestart.setMasterEnabled(
            enabled = true,
            zoneId = utc,
            now = Instant.parse("2026-01-20T10:00:00Z"),
        )
        val globalSnapshot = afterRestart.reconcileReminderForScheduling(
            globallyPausedId,
            zoneId = utc,
            now = Instant.parse("2026-01-20T10:00:00Z"),
        )!!
        assertTrue(globalSnapshot.masterEnabled)
        assertNull(globalSnapshot.state.outstandingDue)
        assertTrue(database.reminderEventDao().getForReminder(globallyPausedId).isEmpty())
    }

    @Test
    fun previewRecoveryKeepsCurrentOrAcknowledgedPreviewsAndDoesNotReplayMissingOnes() = runBlocking {
        val missingId = repository.createReminder(
            input("Missing", LocalDateTime.of(2026, 1, 2, 9, 0), intervalDays = 7),
            utc,
            Instant.parse("2026-01-01T08:00:00Z"),
        )
        assertNotNull(database.tomorrowPreviewDao().getByReminderId(missingId))
        database.tomorrowPreviewDao().deleteByReminderId(missingId)
        val afterRestart = ReminderRepository(database)
        val missing = afterRestart.reconcileReminderForScheduling(
            missingId,
            zoneId = utc,
            now = Instant.parse("2026-01-01T10:00:00Z"),
        )!!
        assertNull(missing.state.tomorrowPreview)

        val currentId = afterRestart.createReminder(
            input("Current", LocalDateTime.of(2026, 1, 3, 9, 0), intervalDays = 7),
            utc,
            Instant.parse("2026-01-01T08:00:00Z"),
        )
        val current = afterRestart.reconcileReminderForScheduling(
            currentId,
            zoneId = utc,
            now = Instant.parse("2026-01-02T09:10:00Z"),
        )!!
        assertNotNull(current.state.tomorrowPreview)
        assertEquals(0L, current.state.tomorrowPreview?.normalOccurrenceIndex)

        val acknowledgedId = afterRestart.createReminder(
            input("Acknowledged", LocalDateTime.of(2026, 1, 4, 9, 0), intervalDays = 7),
            utc,
            Instant.parse("2026-01-01T08:00:00Z"),
        )
        val acknowledged = database.tomorrowPreviewDao().getByReminderId(acknowledgedId)!!
        afterRestart.acknowledgeTomorrow(
            id = acknowledgedId,
            expectedRevision = acknowledged.revision,
            expectedNormalOccurrenceIndex = acknowledged.normalOccurrenceIndex,
            zoneId = utc,
            now = Instant.parse("2026-01-01T08:30:00Z"),
        )
        val acknowledgedAfterRestart = afterRestart.reconcileReminderForScheduling(
            acknowledgedId,
            zoneId = utc,
            now = Instant.parse("2026-01-01T10:00:00Z"),
        )!!
        assertTrue(acknowledgedAfterRestart.state.tomorrowPreview?.acknowledged == true)

        val dueId = afterRestart.createReminder(
            input("Due", LocalDateTime.of(2026, 1, 1, 9, 0), intervalDays = 7),
            utc,
            Instant.parse("2026-01-01T10:00:00Z"),
        )
        val due = afterRestart.reconcileReminderForScheduling(
            dueId,
            zoneId = utc,
            now = Instant.parse("2026-01-01T10:00:00Z"),
        )!!
        assertNotNull(due.state.outstandingDue)
        assertNull(due.state.tomorrowPreview)

        val dailyId = afterRestart.createReminder(
            input("Daily", LocalDateTime.of(2026, 1, 2, 9, 0), intervalDays = 1),
            utc,
            Instant.parse("2026-01-01T08:00:00Z"),
        )
        val daily = afterRestart.reconcileReminderForScheduling(
            dailyId,
            zoneId = utc,
            now = Instant.parse("2026-01-01T08:00:00Z"),
        )!!
        assertNull(daily.state.tomorrowPreview)
        assertTrue(database.reminderEventDao().getForReminder(missingId).isEmpty())
    }

    @Test
    fun timezoneRecoveryPreservesLocalWallClockAndDoesNotMoveTheAnchor() = runBlocking {
        val reminderId = repository.createReminder(
            input("Travel", LocalDateTime.of(2026, 1, 4, 9, 0), intervalDays = 7),
            utc,
            Instant.parse("2026-01-01T08:00:00Z"),
        )
        val inUtc = repository.reconcileReminderForScheduling(
            reminderId,
            zoneId = utc,
            now = Instant.parse("2026-01-01T08:00:00Z"),
        )!!
        val inTokyo = repository.reconcileReminderForScheduling(
            reminderId,
            zoneId = ZoneId.of("Asia/Tokyo"),
            now = Instant.parse("2026-01-01T08:00:00Z"),
        )!!

        assertEquals(
            inUtc.state.nextNormal.localDateTime.toLocalTime(),
            inTokyo.state.nextNormal.localDateTime.toLocalTime(),
        )
        assertEquals(
            inUtc.state.nextNormal.localDateTime.toLocalDate(),
            inTokyo.state.nextNormal.localDateTime.toLocalDate(),
        )
        assertNotEquals(
            inUtc.state.nextNormal.scheduledAt,
            inTokyo.state.nextNormal.scheduledAt,
        )
        val stored = database.reminderDao().getById(reminderId)!!
        assertEquals("2026-01-04", stored.anchorLocalDate)
        assertEquals("09:00", stored.anchorLocalTime)
    }

    private fun input(
        title: String,
        firstOccurrence: LocalDateTime,
        intervalDays: Int,
        enabled: Boolean = true,
    ) = ReminderInput(
        title = title,
        description = null,
        enabled = enabled,
        firstOccurrence = firstOccurrence,
        intervalDays = intervalDays,
    )
}
