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

        val tomorrowPreview = reconcileTomorrowPreview(
            definition = definition,
            oldPreview = current?.tomorrowPreview,
            nextNormal = window.firstFuture,
            hasOutstandingDue = outstandingDue != null,
            now = now,
            zoneId = zoneId,
        )

        return ReminderScheduleState(
            nextNormal = window.firstFuture,
            outstandingDue = outstandingDue,
            tomorrowPreview = tomorrowPreview,
            lastResolvedNormalOccurrenceIndex = lastResolvedIndex,
        )
    }

    /** Every-1-day reminders deliberately have no Tomorrow preview. */
    fun supportsTomorrowPreview(definition: ReminderDefinition): Boolean = definition.intervalDays >= 2

    /**
     * Classifies a persisted preview without deleting it merely because its
     * delivery time has passed. A CURRENT preview is still valid for Seen.
     */
    fun tomorrowPreviewLifecycle(
        preview: TomorrowPreviewState,
        now: Instant,
        hasOutstandingDue: Boolean,
    ): TomorrowPreviewLifecycle = when {
        hasOutstandingDue || preview.occurrenceEpochMillis <= now.toEpochMilli() ->
            TomorrowPreviewLifecycle.OBSOLETE

        preview.acknowledged -> TomorrowPreviewLifecycle.ACKNOWLEDGED
        preview.previewEpochMillis > now.toEpochMilli() -> TomorrowPreviewLifecycle.SCHEDULED
        else -> TomorrowPreviewLifecycle.CURRENT
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

    private fun reconcileTomorrowPreview(
        definition: ReminderDefinition,
        oldPreview: TomorrowPreviewState?,
        nextNormal: NormalOccurrence,
        hasOutstandingDue: Boolean,
        now: Instant,
        zoneId: ZoneId,
    ): TomorrowPreviewState? {
        if (hasOutstandingDue || !supportsTomorrowPreview(definition)) return null

        val occurrenceEpochMillis = nextNormal.scheduledAt.toEpochMilli()
        val previewEpochMillis = RecurrenceCalculator
            .tomorrowPreviewInstant(nextNormal, zoneId)
            .toEpochMilli()
        if (previewEpochMillis >= occurrenceEpochMillis) return null

        if (oldPreview?.normalOccurrenceIndex == nextNormal.index) {
            val schedulingChanged =
                oldPreview.occurrenceEpochMillis != occurrenceEpochMillis ||
                    oldPreview.previewEpochMillis != previewEpochMillis ||
                    oldPreview.zoneId != zoneId.id
            val reconciled = oldPreview.copy(
                occurrenceEpochMillis = occurrenceEpochMillis,
                previewEpochMillis = previewEpochMillis,
                zoneId = zoneId.id,
                revision = if (schedulingChanged) oldPreview.revision + 1 else oldPreview.revision,
            )
            return reconciled.takeUnless {
                tomorrowPreviewLifecycle(it, now, hasOutstandingDue = false) ==
                    TomorrowPreviewLifecycle.OBSOLETE
            }
        }

        // There is no persisted preview for this target. Do not replay one
        // after its scheduled delivery time during recovery.
        if (previewEpochMillis <= now.toEpochMilli()) return null

        return TomorrowPreviewState(
            normalOccurrenceIndex = nextNormal.index,
            occurrenceEpochMillis = occurrenceEpochMillis,
            previewEpochMillis = previewEpochMillis,
            zoneId = zoneId.id,
            acknowledged = false,
            revision = (oldPreview?.revision ?: 0) + 1,
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
