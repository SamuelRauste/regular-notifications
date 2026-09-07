package com.samuel.regularnotifications.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurrenceCalculatorTest {
    private val helsinki = ZoneId.of("Europe/Helsinki")
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val utc = ZoneId.of("UTC")

    @Test
    fun occurrenceUsesEveryXDaysForOneTwoSevenAndThirtyDays() {
        val anchor = LocalDateTime.of(2026, 9, 1, 9, 0)

        assertEquals(
            LocalDateTime.of(2026, 9, 3, 9, 0),
            RecurrenceCalculator.occurrence(definition(anchor, 1), 2, utc).localDateTime,
        )
        assertEquals(
            LocalDateTime.of(2026, 9, 5, 9, 0),
            RecurrenceCalculator.occurrence(definition(anchor, 2), 2, utc).localDateTime,
        )
        assertEquals(
            LocalDateTime.of(2026, 9, 15, 9, 0),
            RecurrenceCalculator.occurrence(definition(anchor, 7), 2, utc).localDateTime,
        )
        assertEquals(
            LocalDateTime.of(2026, 10, 31, 9, 0),
            RecurrenceCalculator.occurrence(definition(anchor, 30), 2, utc).localDateTime,
        )
    }

    @Test
    fun firstFutureReturnsAnchorWhenItIsStillFuture() {
        val definition = definition(LocalDateTime.of(2026, 1, 10, 9, 0), intervalDays = 3)

        val result = RecurrenceCalculator.firstFuture(
            definition = definition,
            now = Instant.parse("2026-01-01T00:00:00Z"),
            zoneId = utc,
        )

        assertEquals(0L, result.index)
        assertEquals(LocalDateTime.of(2026, 1, 10, 9, 0), result.localDateTime)
    }

    @Test
    fun pastAnchorFindsFirstFutureAndLatestDueOccurrence() {
        val definition = definition(LocalDateTime.of(2026, 1, 1, 9, 0), intervalDays = 2)
        val now = Instant.parse("2026-01-10T10:00:00Z")

        val firstFuture = RecurrenceCalculator.firstFuture(definition, now, utc)
        val window = RecurrenceCalculator.window(definition, now, utc)

        assertEquals(5L, firstFuture.index)
        assertEquals(LocalDateTime.of(2026, 1, 11, 9, 0), firstFuture.localDateTime)
        assertEquals(4L, window.latestDue?.index)
        assertEquals(LocalDateTime.of(2026, 1, 9, 9, 0), window.latestDue?.localDateTime)
    }

    @Test
    fun recurrenceCrossesMonthAndYearBoundariesFromTheOriginalAnchor() {
        val definition = definition(LocalDateTime.of(2024, 12, 29, 23, 30), intervalDays = 7)

        assertEquals(
            LocalDateTime.of(2025, 1, 5, 23, 30),
            RecurrenceCalculator.occurrence(definition, 1, utc).localDateTime,
        )
        assertEquals(
            LocalDateTime.of(2025, 1, 26, 23, 30),
            RecurrenceCalculator.occurrence(definition, 4, utc).localDateTime,
        )
    }

    @Test
    fun recurrenceHandlesLeapYearsUsingCalendarDays() {
        val definition = definition(LocalDateTime.of(2024, 2, 28, 9, 0), intervalDays = 2)

        assertEquals(
            LocalDateTime.of(2024, 3, 1, 9, 0),
            RecurrenceCalculator.occurrence(definition, 1, utc).localDateTime,
        )
    }

    @Test
    fun springDstTransitionPreservesLocalWallClockTime() {
        val definition = definition(LocalDateTime.of(2026, 3, 28, 9, 0), intervalDays = 1)
        val anchor = RecurrenceCalculator.occurrence(definition, 0, helsinki)
        val next = RecurrenceCalculator.occurrence(definition, 1, helsinki)

        assertEquals(LocalDateTime.of(2026, 3, 29, 9, 0), next.localDateTime)
        assertEquals(Duration.ofHours(23), Duration.between(anchor.scheduledAt, next.scheduledAt))
    }

    @Test
    fun autumnDstTransitionPreservesLocalWallClockTime() {
        val definition = definition(LocalDateTime.of(2026, 10, 24, 9, 0), intervalDays = 1)
        val anchor = RecurrenceCalculator.occurrence(definition, 0, helsinki)
        val next = RecurrenceCalculator.occurrence(definition, 1, helsinki)

        assertEquals(LocalDateTime.of(2026, 10, 25, 9, 0), next.localDateTime)
        assertEquals(Duration.ofHours(25), Duration.between(anchor.scheduledAt, next.scheduledAt))
    }

    @Test
    fun finlandToJapanChangeKeepsTheSameLocalDateAndTime() {
        val definition = definition(LocalDateTime.of(2026, 1, 1, 9, 0), intervalDays = 3)
        val now = Instant.parse("2026-01-01T08:00:00Z")

        val inHelsinki = RecurrenceCalculator.firstFuture(definition, now, helsinki)
        val inTokyo = RecurrenceCalculator.firstFuture(definition, now, tokyo)

        assertEquals(LocalDateTime.of(2026, 1, 4, 9, 0), inHelsinki.localDateTime)
        assertEquals(LocalDateTime.of(2026, 1, 4, 9, 0), inTokyo.localDateTime)
        assertNotEquals(inHelsinki.scheduledAt, inTokyo.scheduledAt)
    }

    @Test
    fun occurrenceIndexRemainsAnchoredWithoutAccumulatedDrift() {
        val definition = definition(LocalDateTime.of(2026, 3, 28, 9, 0), intervalDays = 3)

        val fourth = RecurrenceCalculator.occurrence(definition, 3, helsinki)

        assertEquals(LocalDateTime.of(2026, 4, 6, 9, 0), fourth.localDateTime)
    }

    @Test
    fun tomorrowPreviewUsesThePreviousLocalCalendarDateAndCanBeTooLateToCreate() {
        val actual = RecurrenceCalculator.occurrence(
            definition(LocalDateTime.of(2026, 1, 3, 9, 0), intervalDays = 7),
            index = 0,
            zoneId = helsinki,
        )

        val preview = RecurrenceCalculator.tomorrowPreviewAt(
            occurrence = actual,
            now = LocalDateTime.of(2026, 1, 2, 8, 0).atZone(helsinki).toInstant(),
            zoneId = helsinki,
        )
        val tooLate = RecurrenceCalculator.tomorrowPreviewAt(
            occurrence = actual,
            now = LocalDateTime.of(2026, 1, 2, 10, 0).atZone(helsinki).toInstant(),
            zoneId = helsinki,
        )

        assertEquals(LocalDateTime.of(2026, 1, 2, 9, 0), preview?.atZone(helsinki)?.toLocalDateTime())
        assertNull(tooLate)
    }

    @Test
    fun calendarDayPostponementPreservesLocalTimeAcrossSpringDst() {
        val beforeDst = LocalDateTime.of(2026, 3, 28, 9, 0).atZone(helsinki).toInstant()

        val postponed = RecurrenceCalculator.plusCalendarDays(beforeDst, 1, helsinki)

        assertEquals(LocalDateTime.of(2026, 3, 29, 9, 0), postponed.atZone(helsinki).toLocalDateTime())
        assertTrue(postponed > beforeDst)
    }

    private fun definition(anchor: LocalDateTime, intervalDays: Int): ReminderDefinition = ReminderDefinition(
        id = 1,
        title = "Test reminder",
        description = null,
        enabled = true,
        anchorLocalDate = anchor.toLocalDate(),
        anchorLocalTime = anchor.toLocalTime(),
        intervalDays = intervalDays,
    )
}
