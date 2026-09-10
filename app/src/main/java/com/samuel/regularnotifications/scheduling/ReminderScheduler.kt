package com.samuel.regularnotifications.scheduling

import com.samuel.regularnotifications.domain.NotificationKind
import java.time.Instant
import java.time.ZoneId

data class AlarmDeliveryRequest(
    val reminderId: Long,
    val kind: NotificationKind,
    val expectedRevision: Long,
)

/** Boundary between Room-derived schedule state and Android alarm state. */
interface ReminderScheduler {
    suspend fun reconcileReminder(
        reminderId: Long,
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    )

    suspend fun reconcileAll(
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    )

    suspend fun deliver(
        request: AlarmDeliveryRequest,
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    )

    /** Cancels both the DUE alarm and its visible notification. */
    fun cancelDue(reminderId: Long)

    /** Cancels both the TOMORROW alarm and its visible notification. */
    fun cancelTomorrow(reminderId: Long)

    fun cancelAll(reminderId: Long)
}
