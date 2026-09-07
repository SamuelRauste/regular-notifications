package com.samuel.regularnotifications.domain

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderStateMachineTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun missedOccurrencesCollapseToOneLatestDueState() {
        val definition = definition(LocalDateTime.of(2026, 1, 1, 9, 0))
        val now = instant("2026-01-05T10:00:00Z")

        val state = ReminderStateMachine.initial(definition, now, utc)
        val repeated = ReminderStateMachine.reconcile(definition, state, now, utc)
        val afterMoreDowntime = ReminderStateMachine.reconcile(
            definition,
            repeated,
            instant("2026-01-07T10:00:00Z"),
            utc,
        )

        assertEquals(5L, state.nextNormal.index)
        assertEquals(4L, state.outstandingDue?.normalOccurrenceIndex)
        assertNull(state.tomorrowPreview)
        assertEquals(state, repeated)
        assertEquals(6L, afterMoreDowntime.outstandingDue?.normalOccurrenceIndex)
        assertEquals(7L, afterMoreDowntime.nextNormal.index)
        assertEquals(2L, afterMoreDowntime.outstandingDue?.revision)
    }

    @Test
    fun futureReminderGetsOneTomorrowPreviewAndAcknowledgementDoesNotMutateSchedule() {
        val definition = definition(LocalDateTime.of(2026, 1, 2, 9, 0))
        val now = instant("2026-01-01T08:00:00Z")

        val state = ReminderStateMachine.initial(definition, now, utc)
        val acknowledged = ReminderStateMachine.acknowledgeTomorrow(state)
        val repeatedAcknowledgement = ReminderStateMachine.acknowledgeTomorrow(acknowledged)

        assertEquals(0, state.nextNormal.index)
        assertEquals(0L, state.tomorrowPreview?.normalOccurrenceIndex)
        assertFalse(state.tomorrowPreview!!.acknowledged)
        assertTrue(acknowledged.tomorrowPreview!!.acknowledged)
        assertEquals(state.nextNormal, acknowledged.nextNormal)
        assertNull(acknowledged.outstandingDue)
        assertEquals(acknowledged, repeatedAcknowledgement)
    }

    @Test
    fun tomorrowPreviewIsSuppressedWhenDueOrPastItsPreviewWindow() {
        val definition = definition(LocalDateTime.of(2026, 1, 2, 9, 0))

        val beforePreview = ReminderStateMachine.initial(definition, instant("2026-01-01T08:00:00Z"), utc)
        val previewExpired = ReminderStateMachine.initial(definition, instant("2026-01-02T08:30:00Z"), utc)
        val due = ReminderStateMachine.initial(definition, instant("2026-01-02T10:00:00Z"), utc)

        assertTrue(beforePreview.tomorrowPreview != null)
        assertNull(previewExpired.tomorrowPreview)
        assertEquals(0L, due.outstandingDue?.normalOccurrenceIndex)
        assertNull(due.tomorrowPreview)
    }

    @Test
    fun repeatedPostponementOnlyMovesTheDisplayedOccurrence() {
        val definition = definition(LocalDateTime.of(2026, 1, 1, 9, 0))
        val initial = ReminderStateMachine.initial(definition, instant("2026-01-01T10:00:00Z"), utc)

        val firstPostponement = ReminderStateMachine.postpone(
            initial,
            now = instant("2026-01-01T10:00:00Z"),
            zoneId = utc,
        )
        val secondPostponement = ReminderStateMachine.postpone(
            firstPostponement,
            now = instant("2026-01-01T11:00:00Z"),
            zoneId = utc,
        )
        val initialDue = checkNotNull(initial.outstandingDue)
        val firstDue = checkNotNull(firstPostponement.outstandingDue)
        val secondDue = checkNotNull(secondPostponement.outstandingDue)

        assertEquals(initial.nextNormal, firstPostponement.nextNormal)
        assertEquals(initialDue.normalOccurrenceIndex, firstDue.normalOccurrenceIndex)
        assertEquals(instant("2026-01-02T10:00:00Z").toEpochMilli(), firstDue.dueAtEpochMillis)
        assertEquals(instant("2026-01-03T10:00:00Z").toEpochMilli(), secondDue.dueAtEpochMillis)
        assertEquals(2, secondDue.postponementCount)
        assertEquals(definition.anchorLocalDate, LocalDateTime.of(2026, 1, 1, 9, 0).toLocalDate())
        assertNotEquals(initialDue.revision, secondDue.revision)
    }

    @Test
    fun aNewNormalOccurrenceReplacesAnOlderPostponedOccurrence() {
        val definition = definition(LocalDateTime.of(2026, 1, 1, 9, 0))
        val initial = ReminderStateMachine.initial(definition, instant("2026-01-01T10:00:00Z"), utc)
        val postponed = ReminderStateMachine.postpone(initial, instant("2026-01-01T10:00:00Z"), utc)

        val recovered = ReminderStateMachine.reconcile(
            definition,
            postponed,
            now = instant("2026-01-02T09:30:00Z"),
            zoneId = utc,
        )

        assertEquals(1L, recovered.outstandingDue?.normalOccurrenceIndex)
        assertEquals(2L, recovered.nextNormal.index)
        assertEquals(0, recovered.outstandingDue?.postponementCount)
        assertEquals(3L, recovered.outstandingDue?.revision)
        assertEquals(instant("2026-01-02T09:00:00Z").toEpochMilli(), recovered.outstandingDue?.dueAtEpochMillis)
    }

    @Test
    fun resolveAdvancesToTheNextNormalOccurrenceAndCanCreateNextPreview() {
        val definition = definition(LocalDateTime.of(2026, 1, 1, 9, 0))
        val initial = ReminderStateMachine.initial(definition, instant("2026-01-01T10:00:00Z"), utc)

        val resolved = ReminderStateMachine.resolve(
            definition,
            initial,
            now = instant("2026-01-01T10:30:00Z"),
            zoneId = utc,
        )

        assertEquals(1, resolved.nextNormal.index)
        assertNull(resolved.outstandingDue)
        assertNull(resolved.tomorrowPreview)
    }

    @Test
    fun reconcileKeepsAcknowledgedTomorrowStateForTheSameNormalOccurrence() {
        val definition = definition(LocalDateTime.of(2026, 1, 2, 9, 0))
        val initial = ReminderStateMachine.initial(definition, instant("2026-01-01T08:00:00Z"), utc)
        val acknowledged = ReminderStateMachine.acknowledgeTomorrow(initial)

        val reconciled = ReminderStateMachine.reconcile(
            definition,
            acknowledged,
            now = instant("2026-01-01T08:30:00Z"),
            zoneId = utc,
        )

        assertEquals(acknowledged.tomorrowPreview, reconciled.tomorrowPreview)
        assertEquals(acknowledged.nextNormal, reconciled.nextNormal)
    }

    @Test
    fun resolvedOccurrenceIsNotRecreatedIfTimezoneChangeMovesItIntoTheFuture() {
        val helsinki = ZoneId.of("Europe/Helsinki")
        val tokyo = ZoneId.of("Asia/Tokyo")
        val definition = definition(LocalDateTime.of(2026, 1, 1, 9, 0))
        val dueInTokyo = ReminderStateMachine.initial(
            definition,
            now = instant("2026-01-01T01:00:00Z"),
            zoneId = tokyo,
        )
        val resolved = ReminderStateMachine.resolve(
            definition,
            dueInTokyo,
            now = instant("2026-01-01T01:00:00Z"),
            zoneId = tokyo,
        )

        val afterTravel = ReminderStateMachine.reconcile(
            definition,
            resolved,
            now = instant("2026-01-01T02:00:00Z"),
            zoneId = helsinki,
        )

        assertEquals(1L, afterTravel.nextNormal.index)
        assertNull(afterTravel.outstandingDue)
    }

    private fun definition(anchor: LocalDateTime): ReminderDefinition = ReminderDefinition(
        id = 1,
        title = "Test reminder",
        description = null,
        enabled = true,
        anchorLocalDate = anchor.toLocalDate(),
        anchorLocalTime = anchor.toLocalTime(),
        durationAnchor = anchor.toInstant(utc.rules.getOffset(anchor)),
        intervalAmount = 1,
        intervalUnit = IntervalUnit.DAYS,
    )

    private fun instant(value: String): Instant = Instant.parse(value)
}
