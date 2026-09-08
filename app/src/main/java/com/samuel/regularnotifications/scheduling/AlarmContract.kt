package com.samuel.regularnotifications.scheduling

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.samuel.regularnotifications.domain.NotificationIdentity
import com.samuel.regularnotifications.domain.NotificationKind

object AlarmContract {
    const val ACTION_DELIVER =
        "com.samuel.regularnotifications.action.DELIVER_ALARM"
    const val EXTRA_REMINDER_ID =
        "com.samuel.regularnotifications.extra.ALARM_REMINDER_ID"
    const val EXTRA_NOTIFICATION_KIND =
        "com.samuel.regularnotifications.extra.ALARM_NOTIFICATION_KIND"
    const val EXTRA_EXPECTED_REVISION =
        "com.samuel.regularnotifications.extra.ALARM_EXPECTED_REVISION"

    fun createPendingIntent(
        context: Context,
        reminderId: Long,
        kind: NotificationKind,
        expectedRevision: Long,
    ): PendingIntent {
        require(reminderId > 0) { "Reminder ID must be positive." }
        require(expectedRevision >= 0) { "Alarm revision must not be negative." }

        val intent = Intent(context, AlarmDeliveryReceiver::class.java).apply {
            action = ACTION_DELIVER
            data = Uri.parse(NotificationIdentity.alarmPendingIntentData(reminderId, kind))
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_NOTIFICATION_KIND, kind.uriSegment)
            putExtra(EXTRA_EXPECTED_REVISION, expectedRevision)
        }
        return PendingIntent.getBroadcast(
            context,
            NotificationIdentity.alarmRequestCode(reminderId, kind),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun parse(intent: Intent): AlarmDeliveryRequest? {
        if (intent.action != ACTION_DELIVER) return null

        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, Long.MIN_VALUE)
        val kind = NotificationKind.values().firstOrNull {
            it.uriSegment == intent.getStringExtra(EXTRA_NOTIFICATION_KIND)
        }
        val expectedRevision = intent.getLongExtra(EXTRA_EXPECTED_REVISION, Long.MIN_VALUE)
        if (reminderId <= 0 || kind == null || expectedRevision < 0) return null

        val expectedData = NotificationIdentity.alarmPendingIntentData(reminderId, kind)
        if (intent.dataString != expectedData) return null

        return AlarmDeliveryRequest(
            reminderId = reminderId,
            kind = kind,
            expectedRevision = expectedRevision,
        )
    }
}
