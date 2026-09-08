package com.samuel.regularnotifications.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug-only adb entry point for inspecting Phase 3 notifications before
 * AlarmManager scheduling exists. It is not included in release builds.
 */
class DebugNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = ReminderNotificationManager(context)
        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, DEFAULT_REMINDER_ID)
        val kind = intent.action
        val input = ReminderNotificationInput(
            reminderId = reminderId,
            title = intent.getStringExtra(EXTRA_TITLE) ?: "Test reminder",
            description = intent.getStringExtra(EXTRA_DESCRIPTION),
            intervalDays = intent.getIntExtra(EXTRA_INTERVAL_DAYS, 7),
            expectedRevision = intent.getLongExtra(EXTRA_EXPECTED_REVISION, 1L),
        )
        val posted = when (kind) {
            ACTION_DUE -> manager.postDue(input)
            ACTION_TOMORROW -> manager.postTomorrow(input)
            ACTION_CANCEL_DUE -> {
                manager.cancelDue(reminderId)
                true
            }

            ACTION_CANCEL_TOMORROW -> {
                manager.cancelTomorrow(reminderId)
                true
            }

            else -> false
        }
        Log.i(TAG, "Phase 3 notification debug action=$kind result=$posted")
    }

    private companion object {
        const val TAG = "ReminderNotifications"
        const val DEFAULT_REMINDER_ID = 42L
        const val EXTRA_REMINDER_ID = "reminderId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DESCRIPTION = "description"
        const val EXTRA_INTERVAL_DAYS = "intervalDays"
        const val EXTRA_EXPECTED_REVISION = "expectedRevision"

        const val ACTION_DUE = "com.samuel.regularnotifications.debug.SHOW_DUE"
        const val ACTION_TOMORROW = "com.samuel.regularnotifications.debug.SHOW_TOMORROW"
        const val ACTION_CANCEL_DUE = "com.samuel.regularnotifications.debug.CANCEL_DUE"
        const val ACTION_CANCEL_TOMORROW = "com.samuel.regularnotifications.debug.CANCEL_TOMORROW"
    }
}
