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
    fun occurrenceSupportsEveryIntervalUnit() {
        val anchor = LocalDateTime.of(2026, 1, 1, 9, 0)

        val minutes = RecurrenceCalculator.occurrence(
            definition(anchor, IntervalUnit.MINUTES, amount = 15),
            index = 4,
            zoneId = helsinki,
        )
        val hours = RecurrenceCalculator.occurrence(
            definition(anchor, IntervalUnit.HOURS, amount = 2),
            index = 4,
            zoneId = helsinki,
        )
        val days = RecurrenceCalculator.occurrence(
            definition(anchor, IntervalUnit.DAYS, amount = 2),
            index = 4,
            zoneId = helsinki,
        )
        val weeks = RecurrenceCalculator.occurrence(
            definition(anchor, IntervalUnit.WEEKS, amount = 2),
            index = 4,
            zoneId = helsinki,
        )

        assertEquals(anchor.atZone(helsinki).plusMinutes(60).toInstant(), minutes.scheduledAt)
        assertEquals(anchor.atZone(helsinki).plusHours(8).toInstant(), hours.scheduledAt)
        assertEquals(anchor.plusDays(8), days.localDateTime)
        assertEquals(anchor.plusWeeks(8), weeks.localDateTime)
    }

    @Test
    fun firstFutureReturnsFirstOccurrenceWhenAnchorIsFuture() {
        val definition = definition(
            anchor = LocalDateTime.of(2026, 1, 10, 9, 0),
            unit = IntervalUnit.DAYS,
        )

        val result = RecurrenceCalculator.firstFuture(
            definition = definition,
            now = Instant.parse("2026-01-01T00:00:00Z"),
            zoneId = utc,
        )

        assertEquals(0, result.index)
        assertEquals(LocalDateTime.of(2026, 1, 10, 9, 0), result.localDateTime)
    }

    @Test
    fun firstFutureSkipsPastOccurrencesAndWindowIdentifiesLatestDue() {
        val definition = definition(
            anchor = LocalDateTime.of(2026, 1, 1, 9, 0),
            unit = IntervalUnit.DAYS,
        )
        val now = Instant.parse("2026-01-05T10:00:00Z")

        val firstFuture = RecurrenceCalculator.firstFuture(definition, now, utc)
        val window = RecurrenceCalculator.window(definition, now, utc)

        assertEquals(5L, firstFuture.index)
        assertEquals(LocalDateTime.of(2026, 1, 6, 9, 0), firstFuture.localDateTime)
        assertEquals(4L, window.latestDue?.index)
        assertEquals(LocalDateTime.of(2026, 1, 5, 9, 0), window.latestDue?.localDateTime)
    }

    @Test
    fun recurrenceHandlesMonthAndYearBoundaries() {
        val daily = definition(
            anchor = LocalDateTime.of(2024, 12, 31, 23, 30),
            unit = IntervalUnit.DAYS,
        )
        val weekly = definition(
            anchor = LocalDateTime.of(2024, 12, 31, 23, 30),
            unit = IntervalUnit.WEEKS,
        )

        assertEquals(
            LocalDateTime.of(2025, 1, 2, 23, 30),
            RecurrenceCalculator.occurrence(daily, 2, utc).localDateTime,
        )
        assertEquals(
            LocalDateTime.of(2025, 1, 14, 23, 30),
            RecurrenceCalculator.occurrence(weekly, 2, utc).localDateTime,
        )
    }

    @Test
    fun dayAndWeekIntervalsPreserveWallClockTimeAcrossDst() {
        val daily = definition(
            anchor = LocalDateTime.of(2026, 3, 28, 9, 0),
            unit = IntervalUnit.DAYS,
        )
        val weekly = definition(
            anchor = LocalDateTime.of(2026, 3, 22, 9, 0),
            unit = IntervalUnit.WEEKS,
        )

        val nextDaily = RecurrenceCalculator.occurrence(daily, 1, helsinki)
        val nextWeekly = RecurrenceCalculator.occurrence(weekly, 1, helsinki)

        assertEquals(LocalDateTime.of(2026, 3, 29, 9, 0), nextDaily.localDateTime)
        assertEquals(LocalDateTime.of(2026, 3, 29, 9, 0), nextWeekly.localDateTime)
        assertEquals(Duration.ofHours(23), Duration.between(daily.durationAnchor, nextDaily.scheduledAt))
        assertEquals(Duration.ofHours(167), Duration.between(weekly.durationAnchor, nextWeekly.scheduledAt))
    }

    @Test
    fun minuteAndHourIntervalsRemainDurationBasedWhenZoneChanges() {
        val anchor = LocalDateTime.of(2026, 1, 1, 9, 0)
        val minutes = definition(anchor, IntervalUnit.MINUTES, amount = 90)
        val hours = definition(anchor, IntervalUnit.HOURS, amount = 3)

        val minutesInHelsinki = RecurrenceCalculator.occurrence(minutes, 2, helsinki)
        val minutesInTokyo = RecurrenceCalculator.occurrence(minutes, 2, tokyo)
        val hoursInHelsinki = RecurrenceCalculator.occurrence(hours, 2, helsinki)
        val hoursInTokyo = RecurrenceCalculator.occurrence(hours, 2, tokyo)

        assertEquals(minutesInHelsinki.scheduledAt, minutesInTokyo.scheduledAt)
        assertEquals(hoursInHelsinki.scheduledAt, hoursInTokyo.scheduledAt)
        assertNotEquals(minutesInHelsinki.localDateTime, minutesInTokyo.localDateTime)
    }

    @Test
    fun dayAndWeekIntervalsUseTheCurrentZoneWallClockAfterTimezoneChange() {
        val definition = definition(
            anchor = LocalDateTime.of(2026, 1, 1, 9, 0),
            unit = IntervalUnit.DAYS,
        )
        val now = Instant.parse("2026-01-01T08:00:00Z")

        val inHelsinki = RecurrenceCalculator.firstFuture(definition, now, helsinki)
        val inTokyo = RecurrenceCalculator.firstFuture(definition, now, tokyo)

        assertEquals(LocalDateTime.of(2026, 1, 2, 9, 0), inHelsinki.localDateTime)
        assertEquals(LocalDateTime.of(2026, 1, 2, 9, 0), inTokyo.localDateTime)
        assertNotEquals(inHelsinki.scheduledAt, inTokyo.scheduledAt)
    }

    @Test
    fun occurrenceIndexesStayAnchoredInsteadOfAccumulatingDstDrift() {
        val definition = definition(
            anchor = LocalDateTime.of(2026, 3, 28, 9, 0),
            unit = IntervalUnit.DAYS,
        )

        val seventh = RecurrenceCalculator.occurrence(definition, 7, helsinki)

        assertEquals(LocalDateTime.of(2026, 4, 4, 9, 0), seventh.localDateTime)
        assertEquals(Duration.ofDays(6).plusHours(23), Duration.between(definition.durationAnchor, seventh.scheduledAt))
    }

    @Test
    fun tomorrowPreviewUsesThePreviousLocalCalendarDateAndCanBeObsolete() {
        val actual = RecurrenceCalculator.occurrence(
            definition(LocalDateTime.of(2026, 1, 3, 9, 0), IntervalUnit.DAYS),
            index = 0,
            zoneId = helsinki,
        )

        val preview = RecurrenceCalculator.tomorrowPreviewAt(
            occurrence = actual,
            now = LocalDateTime.of(2026, 1, 2, 8, 0).atZone(helsinki).toInstant(),
            zoneId = helsinki,
        )
        val obsolete = RecurrenceCalculator.tomorrowPreviewAt(
            occurrence = actual,
            now = LocalDateTime.of(2026, 1, 2, 10, 0).atZone(helsinki).toInstant(),
            zoneId = helsinki,
        )

        assertEquals(LocalDateTime.of(2026, 1, 2, 9, 0), preview?.atZone(helsinki)?.toLocalDateTime())
        assertNull(obsolete)
    }

    @Test
    fun tomorrowPreviewPreservesWallClockTimeAcrossDst() {
        val actual = RecurrenceCalculator.occurrence(
            definition(LocalDateTime.of(2026, 3, 30, 9, 0), IntervalUnit.DAYS),
            index = 0,
            zoneId = helsinki,
        )

        val preview = checkNotNull(RecurrenceCalculator.tomorrowPreviewAt(
            occurrence = actual,
            now = LocalDateTime.of(2026, 3, 28, 8, 0).atZone(helsinki).toInstant(),
            zoneId = helsinki,
        ))

        assertEquals(LocalDateTime.of(2026, 3, 29, 9, 0), preview.atZone(helsinki).toLocalDateTime())
        assertEquals(Duration.ofHours(24), Duration.between(preview, actual.scheduledAt))
    }

    @Test
    fun tomorrowPreviewUsesTheZoneInWhichTheReminderIsCurrentlyScheduled() {
        val definition = definition(LocalDateTime.of(2026, 1, 1, 9, 0), IntervalUnit.DAYS)
        val actualInTokyo = RecurrenceCalculator.occurrence(definition, 1, tokyo)
        val actualInHelsinki = RecurrenceCalculator.occurrence(definition, 1, helsinki)

        val previewInTokyo = RecurrenceCalculator.tomorrowPreviewAt(
            occurrence = actualInTokyo,
            now = Instant.parse("2025-12-31T23:00:00Z"),
            zoneId = tokyo,
        )
        val previewInHelsinki = RecurrenceCalculator.tomorrowPreviewAt(
            occurrence = actualInHelsinki,
            now = Instant.parse("2026-01-01T06:00:00Z"),
            zoneId = helsinki,
        )

        assertEquals(LocalDateTime.of(2026, 1, 1, 9, 0), previewInTokyo?.atZone(tokyo)?.toLocalDateTime())
        assertEquals(LocalDateTime.of(2026, 1, 1, 9, 0), previewInHelsinki?.atZone(helsinki)?.toLocalDateTime())
    }

    @Test
    fun calendarDayAdditionPreservesLocalTimeAcrossDst() {
        val beforeDst = LocalDateTime.of(2026, 3, 28, 9, 0).atZone(helsinki).toInstant()

        val postponed = RecurrenceCalculator.plusCalendarDays(beforeDst, 1, helsinki)

        assertEquals(LocalDateTime.of(2026, 3, 29, 9, 0), postponed.atZone(helsinki).toLocalDateTime())
        assertEquals(Duration.ofHours(23), Duration.between(beforeDst, postponed))
        assertTrue(postponed > beforeDst)
    }

    private fun definition(
        anchor: LocalDateTime,
        unit: IntervalUnit,
        amount: Int = 1,
    ): ReminderDefinition = ReminderDefinition(
        id = 1,
        title = "Test reminder",
        description = null,
        enabled = true,
        anchorLocalDate = anchor.toLocalDate(),
        anchorLocalTime = anchor.toLocalTime(),
        durationAnchor = anchor.atZone(helsinki).toInstant(),
        intervalAmount = amount,
        intervalUnit = unit,
    )
}
