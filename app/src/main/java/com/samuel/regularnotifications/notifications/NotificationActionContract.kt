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

data class NotificationActionRequest(
    val reminderId: Long,
    val notificationKind: NotificationKind,
    val action: NotificationAction,
    val expectedRevision: Long,
    val expectedNormalOccurrenceIndex: Long,
    val expectedReminderModifiedAtEpochMillis: Long,
)

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
    const val EXTRA_EXPECTED_OCCURRENCE_INDEX =
        "com.samuel.regularnotifications.extra.EXPECTED_OCCURRENCE_INDEX"
    const val EXTRA_EXPECTED_REMINDER_MODIFIED_AT =
        "com.samuel.regularnotifications.extra.EXPECTED_REMINDER_MODIFIED_AT"

    fun createPendingIntent(
        context: Context,
        reminderId: Long,
        notificationKind: NotificationKind,
        action: NotificationAction,
        expectedRevision: Long,
        expectedNormalOccurrenceIndex: Long = 0,
        expectedReminderModifiedAtEpochMillis: Long = 0,
    ): PendingIntent {
        require(reminderId > 0) { "Reminder ID must be positive." }
        require(expectedRevision >= 0) { "Action revision must not be negative." }
        require(expectedNormalOccurrenceIndex >= 0) {
            "Action occurrence index must not be negative."
        }
        require(expectedReminderModifiedAtEpochMillis >= 0) {
            "Reminder modification timestamp must not be negative."
        }
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
            putExtra(EXTRA_EXPECTED_OCCURRENCE_INDEX, expectedNormalOccurrenceIndex)
            putExtra(
                EXTRA_EXPECTED_REMINDER_MODIFIED_AT,
                expectedReminderModifiedAtEpochMillis,
            )
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

    fun parse(intent: Intent): NotificationActionRequest? = runCatching {
        parseStrict(intent)
    }.getOrNull()

    private fun parseStrict(intent: Intent): NotificationActionRequest? {
        if (intent.action != ACTION_NOTIFICATION) return null

        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, Long.MIN_VALUE)
        val notificationKind = NotificationKind.values().firstOrNull {
            it.uriSegment == intent.getStringExtra(EXTRA_NOTIFICATION_KIND)
        }
        val action = NotificationAction.values().firstOrNull {
            it.uriSegment == intent.getStringExtra(EXTRA_ACTION)
        }
        val expectedRevision = intent.getLongExtra(EXTRA_EXPECTED_REVISION, Long.MIN_VALUE)
        val expectedNormalOccurrenceIndex = intent.getLongExtra(
            EXTRA_EXPECTED_OCCURRENCE_INDEX,
            Long.MIN_VALUE,
        )
        val expectedReminderModifiedAtEpochMillis = intent.getLongExtra(
            EXTRA_EXPECTED_REMINDER_MODIFIED_AT,
            Long.MIN_VALUE,
        )
        if (reminderId <= 0 ||
            notificationKind == null ||
            action == null ||
            expectedRevision < 0 ||
            expectedNormalOccurrenceIndex < 0 ||
            expectedReminderModifiedAtEpochMillis < 0 ||
            !isAllowed(notificationKind, action)
        ) {
            return null
        }

        val expectedData = NotificationIdentity.actionPendingIntentData(
            reminderId = reminderId,
            kind = notificationKind,
            actionSegment = action.uriSegment,
        )
        if (intent.dataString != expectedData) return null

        return NotificationActionRequest(
            reminderId = reminderId,
            notificationKind = notificationKind,
            action = action,
            expectedRevision = expectedRevision,
            expectedNormalOccurrenceIndex = expectedNormalOccurrenceIndex,
            expectedReminderModifiedAtEpochMillis = expectedReminderModifiedAtEpochMillis,
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
