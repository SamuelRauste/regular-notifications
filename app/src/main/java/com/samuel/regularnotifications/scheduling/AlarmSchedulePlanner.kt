package com.samuel.regularnotifications.scheduling

import com.samuel.regularnotifications.domain.ReminderSchedulingSnapshot
import com.samuel.regularnotifications.domain.supportsTomorrow
import java.time.Instant

data class AlarmSchedulePlan(
    val dueTriggerAtEpochMillis: Long?,
    val tomorrowTriggerAtEpochMillis: Long?,
)

/** Pure scheduling decisions; Android APIs are kept out of this component. */
object AlarmSchedulePlanner {
    fun plan(
        snapshot: ReminderSchedulingSnapshot,
        now: Instant,
    ): AlarmSchedulePlan {
        if (!snapshot.definition.enabled) return AlarmSchedulePlan(null, null)

        val nowEpochMillis = now.toEpochMilli()
        val dueTrigger = snapshot.state.outstandingDue?.dueAtEpochMillis
            ?: snapshot.state.nextNormal.scheduledAt.toEpochMilli()

        val preview = snapshot.state.tomorrowPreview
        val tomorrowTrigger = preview
            ?.takeIf {
                supportsTomorrow(snapshot.definition.intervalDays) &&
                    !it.acknowledged &&
                    snapshot.state.outstandingDue == null &&
                    it.occurrenceEpochMillis > nowEpochMillis
            }
            ?.previewEpochMillis

        return AlarmSchedulePlan(
            dueTriggerAtEpochMillis = maxOf(dueTrigger, nowEpochMillis),
            tomorrowTriggerAtEpochMillis = tomorrowTrigger?.let { maxOf(it, nowEpochMillis) },
        )
    }
}

/** Pure stale-work checks used immediately before a notification is posted. */
object AlarmDeliveryDecisions {
    fun shouldPostDue(
        snapshot: ReminderSchedulingSnapshot,
        expectedRevision: Long,
        now: Instant,
    ): Boolean {
        if (!snapshot.definition.enabled) return false
        val due = snapshot.state.outstandingDue ?: return false
        if (due.dueAtEpochMillis > now.toEpochMilli()) return false
        return expectedRevision == 0L || due.revision == expectedRevision
    }

    fun shouldPostTomorrow(
        snapshot: ReminderSchedulingSnapshot,
        expectedRevision: Long,
        now: Instant,
    ): Boolean {
        if (!snapshot.definition.enabled || !supportsTomorrow(snapshot.definition.intervalDays)) {
            return false
        }
        if (snapshot.state.outstandingDue != null) return false
        val preview = snapshot.state.tomorrowPreview ?: return false
        return !preview.acknowledged &&
            preview.revision == expectedRevision &&
            preview.previewEpochMillis <= now.toEpochMilli() &&
            preview.occurrenceEpochMillis > now.toEpochMilli()
    }
}
