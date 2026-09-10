package com.samuel.regularnotifications.scheduling

import android.app.AlarmManager
import android.content.Context
import android.util.Log
import com.samuel.regularnotifications.data.ReminderRepository
import com.samuel.regularnotifications.domain.NotificationKind
import com.samuel.regularnotifications.domain.ReminderSchedulingSnapshot
import com.samuel.regularnotifications.notifications.ReminderNotificationInput
import com.samuel.regularnotifications.notifications.ReminderNotificationManager
import java.time.Instant
import java.time.ZoneId

/** One-shot AlarmManager implementation backed by Room snapshots. */
class AlarmManagerReminderScheduler(
    context: Context,
    private val repository: ReminderRepository,
    private val exactAlarmCapability: ExactAlarmCapability =
        AndroidExactAlarmCapability(context),
    private val notificationManager: ReminderNotificationManager =
        ReminderNotificationManager(context.applicationContext),
) : ReminderScheduler {
    private val applicationContext = context.applicationContext
    private val alarmManager = requireNotNull(
        applicationContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager,
    )

    override suspend fun reconcileReminder(
        reminderId: Long,
        now: Instant,
        zoneId: ZoneId,
    ) {
        val snapshot = repository.reconcileReminderForScheduling(
            id = reminderId,
            zoneId = zoneId,
            now = now,
        )
        if (snapshot == null) {
            cancelAll(reminderId)
        } else {
            applyPlan(snapshot, now)
        }
    }

    override suspend fun reconcileAll(now: Instant, zoneId: ZoneId) {
        repository.reconcileAllForScheduling(zoneId = zoneId, now = now)
            .forEach { snapshot -> applyPlan(snapshot, now) }
    }

    override suspend fun deliver(
        request: AlarmDeliveryRequest,
        now: Instant,
        zoneId: ZoneId,
    ) {
        val snapshot = repository.reconcileReminderForScheduling(
            id = request.reminderId,
            zoneId = zoneId,
            now = now,
        )
        if (snapshot == null) {
            cancelAll(request.reminderId)
            return
        }

        val validDelivery = when (request.kind) {
            NotificationKind.DUE -> AlarmDeliveryDecisions.shouldPostDue(
                snapshot = snapshot,
                expectedRevision = request.expectedRevision,
                now = now,
            )

            NotificationKind.TOMORROW -> AlarmDeliveryDecisions.shouldPostTomorrow(
                snapshot = snapshot,
                expectedRevision = request.expectedRevision,
                now = now,
            )
        }

        if (validDelivery) {
            val posted = postNotification(snapshot, request.kind)
            if (posted) {
                // The one-shot alarm has done its work. Do not immediately
                // recreate the same alarm while the notification remains visible.
                applyPlan(
                    snapshot = snapshot,
                    now = now,
                    skipKind = request.kind,
                    preserveNotificationKind = request.kind,
                )
            } else {
                // A denied POST_NOTIFICATIONS permission must not cause a
                // tight immediate-alarm retry loop. Keep Room's outstanding
                // state intact and wait for permission-granted reconciliation.
                applyPlan(snapshot, now, skipKind = request.kind)
            }
        } else {
            Log.d(
                TAG,
                "Ignoring stale or early alarm reminderId=${request.reminderId} kind=${request.kind}",
            )
            applyPlan(snapshot, now)
        }
    }

    override fun cancelDue(reminderId: Long) {
        alarmManager.cancel(
            AlarmContract.createPendingIntent(
                context = applicationContext,
                reminderId = reminderId,
                kind = NotificationKind.DUE,
                expectedRevision = 0,
            ),
        )
    }

    override fun cancelTomorrow(reminderId: Long) {
        alarmManager.cancel(
            AlarmContract.createPendingIntent(
                context = applicationContext,
                reminderId = reminderId,
                kind = NotificationKind.TOMORROW,
                expectedRevision = 0,
            ),
        )
    }

    override fun cancelAll(reminderId: Long) {
        cancelDue(reminderId)
        cancelTomorrow(reminderId)
        notificationManager.cancelAll(reminderId)
    }

    private fun applyPlan(
        snapshot: ReminderSchedulingSnapshot,
        now: Instant,
        skipKind: NotificationKind? = null,
        preserveNotificationKind: NotificationKind? = null,
    ) {
        val plan = AlarmSchedulePlanner.plan(snapshot, now)

        if (skipKind != NotificationKind.DUE && plan.dueTriggerAtEpochMillis != null) {
            val dueRevision = snapshot.state.outstandingDue?.revision ?: 0L
            schedule(
                reminderId = snapshot.definition.id,
                kind = NotificationKind.DUE,
                triggerAtEpochMillis = plan.dueTriggerAtEpochMillis,
                expectedRevision = dueRevision,
            )
        } else {
            cancelDue(snapshot.definition.id)
            if (preserveNotificationKind != NotificationKind.DUE) {
                notificationManager.cancelDue(snapshot.definition.id)
            }
        }

        if (skipKind != NotificationKind.TOMORROW &&
            plan.tomorrowTriggerAtEpochMillis != null
        ) {
            val previewRevision = requireNotNull(snapshot.state.tomorrowPreview).revision
            schedule(
                reminderId = snapshot.definition.id,
                kind = NotificationKind.TOMORROW,
                triggerAtEpochMillis = plan.tomorrowTriggerAtEpochMillis,
                expectedRevision = previewRevision,
            )
        } else {
            cancelTomorrow(snapshot.definition.id)
            if (preserveNotificationKind != NotificationKind.TOMORROW) {
                notificationManager.cancelTomorrow(snapshot.definition.id)
            }
        }
    }

    private fun schedule(
        reminderId: Long,
        kind: NotificationKind,
        triggerAtEpochMillis: Long,
        expectedRevision: Long,
    ) {
        val pendingIntent = AlarmContract.createPendingIntent(
            context = applicationContext,
            reminderId = reminderId,
            kind = kind,
            expectedRevision = expectedRevision,
        )
        val mode = AlarmSchedulePolicy.select(
            exactAlarmCapability.canScheduleExactAlarms(),
        )
        try {
            when (mode) {
                AlarmScheduleMode.EXACT -> alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtEpochMillis,
                    pendingIntent,
                )

                AlarmScheduleMode.INEXACT -> alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtEpochMillis,
                    pendingIntent,
                )
            }
        } catch (error: SecurityException) {
            if (mode == AlarmScheduleMode.EXACT) {
                // Permission can be revoked between canScheduleExactAlarms() and
                // setExactAndAllowWhileIdle(). The same PendingIntent identity
                // makes this fallback replace the failed exact attempt safely.
                Log.w(
                    TAG,
                    "Exact alarm access changed; using inexact fallback for " +
                        "reminderId=$reminderId kind=$kind",
                    error,
                )
                try {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtEpochMillis,
                        pendingIntent,
                    )
                } catch (fallbackError: SecurityException) {
                    Log.e(
                        TAG,
                        "Unable to schedule fallback alarm for " +
                            "reminderId=$reminderId kind=$kind",
                        fallbackError,
                    )
                }
            } else {
                Log.e(
                    TAG,
                    "Unable to schedule inexact alarm for " +
                        "reminderId=$reminderId kind=$kind",
                    error,
                )
            }
        }
    }

    private fun postNotification(
        snapshot: ReminderSchedulingSnapshot,
        kind: NotificationKind,
    ): Boolean {
        val revision = when (kind) {
            NotificationKind.DUE -> requireNotNull(snapshot.state.outstandingDue).revision
            NotificationKind.TOMORROW -> requireNotNull(snapshot.state.tomorrowPreview).revision
        }
        val input = ReminderNotificationInput(
            reminderId = snapshot.definition.id,
            title = snapshot.definition.title,
            description = snapshot.definition.description,
            intervalDays = snapshot.definition.intervalDays,
            expectedRevision = revision,
        )
        return when (kind) {
            NotificationKind.DUE -> notificationManager.postDue(input)
            NotificationKind.TOMORROW -> notificationManager.postTomorrow(input)
        }
    }

    private companion object {
        const val TAG = "ReminderNotifications"
    }
}
