package com.samuel.regularnotifications.domain

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

object RecurrenceCalculator {
    fun occurrence(
        definition: ReminderDefinition,
        index: Long,
        zoneId: ZoneId,
    ): NormalOccurrence {
        require(index >= 0) { "Occurrence index must not be negative." }

        val daysFromAnchor = Math.multiplyExact(definition.intervalDays.toLong(), index)
        val scheduledAt = LocalDateTime.of(
            definition.anchorLocalDate.plusDays(daysFromAnchor),
            definition.anchorLocalTime,
        ).atZone(zoneId).toInstant()

        return NormalOccurrence(index = index, scheduledAt = scheduledAt, zoneId = zoneId)
    }

    fun firstFuture(
        definition: ReminderDefinition,
        now: Instant,
        zoneId: ZoneId,
    ): NormalOccurrence {
        val first = occurrence(definition, 0, zoneId)
        if (first.scheduledAt > now) return first

        var lower = 0L
        var upper = 1L
        while (occurrence(definition, upper, zoneId).scheduledAt <= now) {
            lower = upper
            upper = upper.checkedMultiplyByTwo()
        }

        while (upper - lower > 1) {
            val middle = lower + (upper - lower) / 2
            if (occurrence(definition, middle, zoneId).scheduledAt <= now) {
                lower = middle
            } else {
                upper = middle
            }
        }
        return occurrence(definition, upper, zoneId)
    }

    fun window(
        definition: ReminderDefinition,
        now: Instant,
        zoneId: ZoneId,
        minimumIndex: Long = 0,
    ): RecurrenceWindow {
        require(minimumIndex >= 0) { "Minimum occurrence index must not be negative." }
        val firstByTime = firstFuture(definition, now, zoneId)
        val firstFuture = if (firstByTime.index >= minimumIndex) {
            firstByTime
        } else {
            occurrence(definition, minimumIndex, zoneId)
        }
        val latestDue = firstFuture.index
            .takeIf { it > 0 }
            ?.let { occurrence(definition, it - 1, zoneId) }
        return RecurrenceWindow(latestDue = latestDue, firstFuture = firstFuture)
    }

    fun tomorrowPreviewAt(
        occurrence: NormalOccurrence,
        now: Instant,
        zoneId: ZoneId,
    ): Instant? {
        if (occurrence.scheduledAt <= now) return null
        val previewAt = tomorrowPreviewInstant(occurrence, zoneId)
        return previewAt.takeIf { it > now && it < occurrence.scheduledAt }
    }

    fun tomorrowPreviewInstant(
        occurrence: NormalOccurrence,
        zoneId: ZoneId,
    ): Instant {
        val actualLocal = occurrence.scheduledAt.atZone(zoneId)
        val previewLocal = actualLocal.toLocalDate().minusDays(1).atTime(actualLocal.toLocalTime())
        return previewLocal.atZone(zoneId).toInstant()
    }

    /**
     * Schedules a postponed occurrence for the next local calendar date after
     * [now], retaining the effective due occurrence's local clock time. The
     * local-date-time conversion intentionally uses the same `atZone` gap and
     * overlap conventions as normal recurrence calculation.
     */
    fun tomorrowAtDueWallClock(
        dueAt: Instant,
        now: Instant,
        zoneId: ZoneId,
    ): Instant {
        val dueClockTime = dueAt.atZone(zoneId).toLocalTime()
        var targetDate = now.atZone(zoneId).toLocalDate().plusDays(1)
        var target = targetDate.atTime(dueClockTime).atZone(zoneId).toInstant()

        // A next local date should always be later than now. Keep the invariant
        // explicit for unusual zone-rule transitions that could adjust a local
        // date/time while resolving a gap.
        while (!target.isAfter(now)) {
            targetDate = targetDate.plusDays(1)
            target = targetDate.atTime(dueClockTime).atZone(zoneId).toInstant()
        }
        return target
    }

    private fun Long.checkedMultiplyByTwo(): Long {
        require(this <= Long.MAX_VALUE / 2) { "Occurrence index exceeded the supported range." }
        return this * 2
    }
}
