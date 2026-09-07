package com.samuel.regularnotifications.domain

import java.time.Instant
import java.time.ZoneId
import kotlin.math.max

object ReminderStateMachine {
    fun initial(
        definition: ReminderDefinition,
        now: Instant,
        zoneId: ZoneId,
    ): ReminderScheduleState =
        reconcile(definition, current = null, now = now, zoneId = zoneId)

    fun reconcile(
        definition: ReminderDefinition,
        current: ReminderScheduleState?,
        now: Instant,
        zoneId: ZoneId,
    ): ReminderScheduleState {
        val lastResolvedIndex = current?.lastResolvedNormalOccurrenceIndex
        val minimumIndex = lastResolvedIndex?.let {
            require(it < Long.MAX_VALUE) { "Resolved occurrence index exceeded the supported range." }
            it + 1
        } ?: 0
        val window = RecurrenceCalculator.window(definition, now, zoneId, minimumIndex)
        val latestDue = window.latestDue
        val existingDue = current?.outstandingDue
        val outstandingDue = when {
            latestDue == null -> existingDue
            lastResolvedIndex != null && latestDue.index <= lastResolvedIndex && existingDue == null -> null
            existingDue == null -> latestDue.toDueState(revision = 1)
            latestDue.index > existingDue.normalOccurrenceIndex ->
                latestDue.toDueState(revision = existingDue.revision + 1)

            else -> existingDue
        }

        val tomorrowPreview = if (outstandingDue == null) {
            val previewAt = RecurrenceCalculator.tomorrowPreviewAt(window.firstFuture, now, zoneId)
            if (previewAt == null) {
                null
            } else {
                val oldPreview = current?.tomorrowPreview
                if (oldPreview?.normalOccurrenceIndex == window.firstFuture.index) {
                    oldPreview.copy(
                        occurrenceEpochMillis = window.firstFuture.scheduledAt.toEpochMilli(),
                        previewEpochMillis = previewAt.toEpochMilli(),
                        zoneId = zoneId.id,
                    )
                } else {
                    TomorrowPreviewState(
                        normalOccurrenceIndex = window.firstFuture.index,
                        occurrenceEpochMillis = window.firstFuture.scheduledAt.toEpochMilli(),
                        previewEpochMillis = previewAt.toEpochMilli(),
                        zoneId = zoneId.id,
                        acknowledged = false,
                        revision = (oldPreview?.revision ?: 0) + 1,
                    )
                }
            }
        } else {
            null
        }

        return ReminderScheduleState(
            nextNormal = window.firstFuture,
            outstandingDue = outstandingDue,
            tomorrowPreview = if (outstandingDue == null) tomorrowPreview else null,
            lastResolvedNormalOccurrenceIndex = lastResolvedIndex,
        )
    }

    fun postpone(
        current: ReminderScheduleState,
        now: Instant,
        zoneId: ZoneId,
    ): ReminderScheduleState {
        val due = requireNotNull(current.outstandingDue) { "There is no outstanding occurrence to postpone." }
        val baseEpochMillis = max(due.dueAtEpochMillis, now.toEpochMilli())
        val postponedUntil = RecurrenceCalculator.plusCalendarDays(
            instant = Instant.ofEpochMilli(baseEpochMillis),
            days = 1,
            zoneId = zoneId,
        ).toEpochMilli()
        return current.copy(
            outstandingDue = due.copy(
                dueAtEpochMillis = postponedUntil,
                postponedUntilEpochMillis = postponedUntil,
                postponementCount = due.postponementCount + 1,
                revision = due.revision + 1,
            ),
            tomorrowPreview = null,
        )
    }

    fun resolve(
        definition: ReminderDefinition,
        current: ReminderScheduleState,
        now: Instant,
        zoneId: ZoneId,
    ): ReminderScheduleState {
        val due = requireNotNull(current.outstandingDue) { "There is no outstanding occurrence to resolve." }
        return reconcile(
            definition = definition,
            current = current.copy(
                outstandingDue = null,
                tomorrowPreview = null,
                lastResolvedNormalOccurrenceIndex = max(
                    current.lastResolvedNormalOccurrenceIndex ?: -1,
                    due.normalOccurrenceIndex,
                ),
            ),
            now = now,
            zoneId = zoneId,
        )
    }

    fun acknowledgeTomorrow(current: ReminderScheduleState): ReminderScheduleState {
        val preview = requireNotNull(current.tomorrowPreview) { "There is no Tomorrow preview to acknowledge." }
        if (preview.acknowledged) return current
        return current.copy(
            tomorrowPreview = preview.copy(
                acknowledged = true,
                revision = preview.revision + 1,
            ),
        )
    }

    private fun NormalOccurrence.toDueState(revision: Long): OutstandingDueState =
        OutstandingDueState(
            normalOccurrenceIndex = index,
            normalOccurrenceEpochMillis = scheduledAt.toEpochMilli(),
            dueAtEpochMillis = scheduledAt.toEpochMilli(),
            postponedUntilEpochMillis = null,
            postponementCount = 0,
            revision = revision,
        )
}
