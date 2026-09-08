package com.samuel.regularnotifications.scheduling

import com.samuel.regularnotifications.domain.NormalOccurrence
import com.samuel.regularnotifications.domain.OutstandingDueState
import com.samuel.regularnotifications.domain.ReminderDefinition
import com.samuel.regularnotifications.domain.ReminderScheduleState
import com.samuel.regularnotifications.domain.ReminderSchedulingSnapshot
import com.samuel.regularnotifications.domain.TomorrowPreviewState
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmSchedulePlannerTest {
    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-01-01T10:00:00Z")

    @Test
    fun futureEnabledDailyReminderSchedulesOnlyItsNextDueAlarm() {
        val snapshot = snapshot(intervalDays = 1)

        val plan = AlarmSchedulePlanner.plan(snapshot, now)

        assertEquals(Instant.parse("2026-01-02T09:00:00Z").toEpochMilli(), plan.dueTriggerAtEpochMillis)
        assertNull(plan.tomorrowTriggerAtEpochMillis)
    }

    @Test
    fun eligibleFutureReminderSchedulesDueAndTomorrowAlarms() {
        val snapshot = snapshot(
            intervalDays = 7,
            tomorrowPreview = TomorrowPreviewState(
                normalOccurrenceIndex = 0,
                occurrenceEpochMillis = Instant.parse("2026-01-08T09:00:00Z").toEpochMilli(),
                previewEpochMillis = Instant.parse("2026-01-07T09:00:00Z").toEpochMilli(),
                zoneId = utc.id,
                acknowledged = false,
                revision = 3,
            ),
        )

        val plan = AlarmSchedulePlanner.plan(snapshot, now)

        assertEquals(Instant.parse("2026-01-02T09:00:00Z").toEpochMilli(), plan.dueTriggerAtEpochMillis)
        assertEquals(Instant.parse("2026-01-07T09:00:00Z").toEpochMilli(), plan.tomorrowTriggerAtEpochMillis)
    }

    @Test
    fun disabledReminderSchedulesNothingEvenIfDerivedRowsExist() {
        val snapshot = snapshot(
            intervalDays = 7,
            enabled = false,
            outstandingDue = due(revision = 2),
            tomorrowPreview = preview(revision = 3),
        )

        assertEquals(AlarmSchedulePlan(null, null), AlarmSchedulePlanner.plan(snapshot, now))
    }

    @Test
    fun globallyPausedReminderSchedulesNothingWithoutChangingItsIndividualFlag() {
        val snapshot = snapshot(
            intervalDays = 7,
            masterEnabled = false,
            outstandingDue = due(revision = 2),
            tomorrowPreview = preview(revision = 3),
        )

        assertEquals(AlarmSchedulePlan(null, null), AlarmSchedulePlanner.plan(snapshot, now))
        assertTrue(snapshot.definition.enabled)
    }

    @Test
    fun dailyReminderNeverSchedulesTomorrowAndDueSuppressesPreview() {
        val daily = snapshot(intervalDays = 1, tomorrowPreview = preview(revision = 1))
        val dueSnapshot = snapshot(
            intervalDays = 7,
            outstandingDue = due(revision = 2),
            tomorrowPreview = preview(revision = 3),
        )

        assertNull(AlarmSchedulePlanner.plan(daily, now).tomorrowTriggerAtEpochMillis)
        assertNull(AlarmSchedulePlanner.plan(dueSnapshot, now).tomorrowTriggerAtEpochMillis)
    }

    @Test
    fun acknowledgedPreviewIsNotScheduled() {
        val snapshot = snapshot(
            intervalDays = 7,
            tomorrowPreview = preview(revision = 4).copy(acknowledged = true),
        )

        assertNull(AlarmSchedulePlanner.plan(snapshot, now).tomorrowTriggerAtEpochMillis)
    }

    @Test
    fun dueDeliveryAcceptsSeedAndMatchingRevisionButRejectsStaleOrEarlyWork() {
        val dueSnapshot = snapshot(
            intervalDays = 7,
            outstandingDue = due(revision = 4),
        )
        val earlySnapshot = dueSnapshot.copy(
            state = dueSnapshot.state.copy(
                outstandingDue = due(revision = 4).copy(
                    dueAtEpochMillis = Instant.parse("2026-01-01T11:00:00Z").toEpochMilli(),
                ),
            ),
        )

        assertTrue(AlarmDeliveryDecisions.shouldPostDue(dueSnapshot, expectedRevision = 0, now = now))
        assertTrue(AlarmDeliveryDecisions.shouldPostDue(dueSnapshot, expectedRevision = 4, now = now))
        assertFalse(AlarmDeliveryDecisions.shouldPostDue(dueSnapshot, expectedRevision = 3, now = now))
        assertFalse(AlarmDeliveryDecisions.shouldPostDue(earlySnapshot, expectedRevision = 4, now = now))
    }

    @Test
    fun staleDeliveryIsRejectedWhileMasterSwitchIsOff() {
        val snapshot = snapshot(
            intervalDays = 7,
            masterEnabled = false,
            outstandingDue = due(revision = 4),
        )

        assertFalse(AlarmDeliveryDecisions.shouldPostDue(snapshot, expectedRevision = 4, now = now))
    }

    @Test
    fun tomorrowDeliveryRequiresCurrentUnacknowledgedPreview() {
        val current = snapshot(
            intervalDays = 7,
            tomorrowPreview = preview(revision = 5),
        )
        val currentPreview = requireNotNull(current.state.tomorrowPreview)
        val stale = current.copy(
            state = current.state.copy(
                tomorrowPreview = currentPreview.copy(revision = 6),
            ),
        )
        val acknowledged = current.copy(
            state = current.state.copy(
                tomorrowPreview = currentPreview.copy(acknowledged = true),
            ),
        )

        assertTrue(AlarmDeliveryDecisions.shouldPostTomorrow(current, expectedRevision = 5, now = now))
        assertFalse(AlarmDeliveryDecisions.shouldPostTomorrow(stale, expectedRevision = 5, now = now))
        assertFalse(AlarmDeliveryDecisions.shouldPostTomorrow(acknowledged, expectedRevision = 5, now = now))
    }

    private fun snapshot(
        intervalDays: Int,
        enabled: Boolean = true,
        masterEnabled: Boolean = true,
        outstandingDue: OutstandingDueState? = null,
        tomorrowPreview: TomorrowPreviewState? = null,
    ): ReminderSchedulingSnapshot {
        val definition = ReminderDefinition(
            id = 42,
            title = "Test reminder",
            description = null,
            enabled = enabled,
            anchorLocalDate = LocalDate.of(2026, 1, 2),
            anchorLocalTime = LocalTime.of(9, 0),
            intervalDays = intervalDays,
        )
        return ReminderSchedulingSnapshot(
            definition = definition,
            state = ReminderScheduleState(
                nextNormal = NormalOccurrence(
                    index = 0,
                    scheduledAt = Instant.parse("2026-01-02T09:00:00Z"),
                    zoneId = utc,
                ),
                outstandingDue = outstandingDue,
                tomorrowPreview = tomorrowPreview,
            ),
            masterEnabled = masterEnabled,
        )
    }

    private fun due(revision: Long) = OutstandingDueState(
        normalOccurrenceIndex = 0,
        normalOccurrenceEpochMillis = Instant.parse("2026-01-01T09:00:00Z").toEpochMilli(),
        dueAtEpochMillis = Instant.parse("2026-01-01T09:00:00Z").toEpochMilli(),
        postponedUntilEpochMillis = null,
        postponementCount = 0,
        revision = revision,
    )

    private fun preview(revision: Long) = TomorrowPreviewState(
        normalOccurrenceIndex = 0,
        occurrenceEpochMillis = Instant.parse("2026-01-08T09:00:00Z").toEpochMilli(),
        previewEpochMillis = Instant.parse("2026-01-01T09:00:00Z").toEpochMilli(),
        zoneId = utc.id,
        acknowledged = false,
        revision = revision,
    )
}
