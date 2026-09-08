package com.samuel.regularnotifications.notifications

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import com.samuel.regularnotifications.R
import com.samuel.regularnotifications.domain.NotificationKind

data class ReminderNotificationInput(
    val reminderId: Long,
    val title: String,
    val description: String?,
    val intervalDays: Int,
    val expectedRevision: Long,
)

class ReminderNotificationFactory(
    context: Context,
) {
    private val applicationContext = context.applicationContext

    init {
        ReminderNotificationChannels.ensureCreated(applicationContext)
    }

    fun buildDueNotification(input: ReminderNotificationInput): Notification {
        val title = input.safeTitle()
        val body = input.descriptionText(fallback = "Reminder due")
        return NotificationCompat.Builder(
            applicationContext,
            ReminderNotificationChannels.REMINDERS_CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(
                NotificationContentIntent.create(
                    context = applicationContext,
                    reminderId = input.reminderId,
                    kind = NotificationKind.DUE,
                ),
            )
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .addAction(
                action(
                    input = input,
                    label = "Done",
                    action = NotificationAction.DONE,
                ),
            )
            .addAction(
                action(
                    input = input,
                    label = "Dismiss",
                    action = NotificationAction.DISMISS,
                ),
            )
            .addAction(
                action(
                    input = input,
                    label = "+1 day",
                    action = NotificationAction.POSTPONE_ONE_DAY,
                ),
            )
            .build()
    }

    fun buildTomorrowNotification(input: ReminderNotificationInput): Notification? {
        if (!supportsTomorrowNotification(input.intervalDays)) return null

        val title = input.safeTitle()
        val body = input.descriptionText(fallback = "Upcoming reminder")
        return NotificationCompat.Builder(
            applicationContext,
            ReminderNotificationChannels.REMINDERS_CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Tomorrow: $title")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(
                NotificationContentIntent.create(
                    context = applicationContext,
                    reminderId = input.reminderId,
                    kind = NotificationKind.TOMORROW,
                ),
            )
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .addAction(
                action(
                    input = input,
                    label = "Seen",
                    action = NotificationAction.TOMORROW_SEEN,
                    kind = NotificationKind.TOMORROW,
                ),
            )
            .build()
    }

    private fun action(
        input: ReminderNotificationInput,
        label: String,
        action: NotificationAction,
        kind: NotificationKind = NotificationKind.DUE,
    ): NotificationCompat.Action = NotificationCompat.Action.Builder(
        R.drawable.ic_notification,
        label,
        NotificationActionContract.createPendingIntent(
            context = applicationContext,
            reminderId = input.reminderId,
            notificationKind = kind,
            action = action,
            expectedRevision = input.expectedRevision,
        ),
    ).build()

    private fun ReminderNotificationInput.safeTitle(): String =
        title.trim().takeIf { it.isNotEmpty() } ?: "Reminder"

    private fun ReminderNotificationInput.descriptionText(fallback: String): String =
        description?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
}
