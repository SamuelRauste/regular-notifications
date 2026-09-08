package com.samuel.regularnotifications.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.samuel.regularnotifications.domain.NotificationIdentity
import com.samuel.regularnotifications.domain.NotificationKind

enum class NotificationAction(val uriSegment: String) {
    DONE("done"),
    DISMISS("dismiss"),
    POSTPONE_ONE_DAY("postpone_one_day"),
    TOMORROW_SEEN("tomorrow_seen"),
}

object NotificationActionContract {
    const val ACTION_NOTIFICATION =
        "com.samuel.regularnotifications.action.NOTIFICATION"
    const val EXTRA_REMINDER_ID =
        "com.samuel.regularnotifications.extra.REMINDER_ID"
    const val EXTRA_NOTIFICATION_KIND =
        "com.samuel.regularnotifications.extra.NOTIFICATION_KIND"
    const val EXTRA_ACTION =
        "com.samuel.regularnotifications.extra.ACTION"
    const val EXTRA_EXPECTED_REVISION =
        "com.samuel.regularnotifications.extra.EXPECTED_REVISION"

    fun createPendingIntent(
        context: Context,
        reminderId: Long,
        notificationKind: NotificationKind,
        action: NotificationAction,
        expectedRevision: Long,
    ): PendingIntent {
        require(isAllowed(notificationKind, action)) {
            "Notification action $action is not valid for $notificationKind."
        }

        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = ACTION_NOTIFICATION
            data = Uri.parse(
                NotificationIdentity.actionPendingIntentData(
                    reminderId = reminderId,
                    kind = notificationKind,
                    actionSegment = action.uriSegment,
                ),
            )
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_NOTIFICATION_KIND, notificationKind.uriSegment)
            putExtra(EXTRA_ACTION, action.uriSegment)
            putExtra(EXTRA_EXPECTED_REVISION, expectedRevision)
        }

        return PendingIntent.getBroadcast(
            context,
            NotificationIdentity.actionPendingIntentRequestCode(
                reminderId = reminderId,
                kind = notificationKind,
                actionSegment = action.uriSegment,
            ),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun isAllowed(
        notificationKind: NotificationKind,
        action: NotificationAction,
    ): Boolean = when (notificationKind) {
        NotificationKind.DUE -> action == NotificationAction.DONE ||
            action == NotificationAction.DISMISS ||
            action == NotificationAction.POSTPONE_ONE_DAY

        NotificationKind.TOMORROW -> action == NotificationAction.TOMORROW_SEEN
    }
}
