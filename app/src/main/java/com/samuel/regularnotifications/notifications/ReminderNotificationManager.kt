package com.samuel.regularnotifications.notifications

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.samuel.regularnotifications.domain.NotificationIdentity
import com.samuel.regularnotifications.domain.NotificationKind

/** Centralizes posting and cancellation so each reminder/kind has one target. */
class ReminderNotificationManager(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(applicationContext)
    private val factory = ReminderNotificationFactory(applicationContext)

    init {
        ReminderNotificationChannels.ensureCreated(applicationContext)
    }

    fun postDue(input: ReminderNotificationInput): Boolean {
        if (!notificationManager.areNotificationsEnabled()) return false
        notify(NotificationKind.DUE, input.reminderId, factory.buildDueNotification(input))
        return true
    }

    fun postTomorrow(input: ReminderNotificationInput): Boolean {
        val notification = factory.buildTomorrowNotification(input) ?: return false
        if (!notificationManager.areNotificationsEnabled()) return false
        notify(NotificationKind.TOMORROW, input.reminderId, notification)
        return true
    }

    fun cancelDue(reminderId: Long) {
        cancel(reminderId, NotificationKind.DUE)
    }

    fun cancelTomorrow(reminderId: Long) {
        cancel(reminderId, NotificationKind.TOMORROW)
    }

    fun cancelAll(reminderId: Long) {
        cancelDue(reminderId)
        cancelTomorrow(reminderId)
    }

    @SuppressLint("MissingPermission")
    private fun notify(
        kind: NotificationKind,
        reminderId: Long,
        notification: android.app.Notification,
    ) {
        val target = NotificationIdentity.target(reminderId, kind)
        notificationManager.notify(target.tag, target.notificationId, notification)
    }

    private fun cancel(reminderId: Long, kind: NotificationKind) {
        val target = NotificationIdentity.target(reminderId, kind)
        notificationManager.cancel(target.tag, target.notificationId)
    }
}
