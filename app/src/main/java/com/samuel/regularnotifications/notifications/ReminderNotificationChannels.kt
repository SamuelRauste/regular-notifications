package com.samuel.regularnotifications.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.samuel.regularnotifications.R

object ReminderNotificationChannels {
    const val REMINDERS_CHANNEL_ID = "reminders"

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager
            ?: return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                REMINDERS_CHANNEL_ID,
                context.getString(R.string.notification_channel_reminders_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notification_channel_reminders_description)
                setShowBadge(true)
            },
        )
    }
}
