package com.samuel.regularnotifications.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.samuel.regularnotifications.RegularNotificationsApplication
import kotlinx.coroutines.launch

/** Rebuilds disposable alarms after reboot or a wall-clock/time-zone change. */
class SchedulingRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RECOVERY_ACTIONS) {
            Log.w(TAG, "Ignoring unsupported scheduling recovery broadcast")
            return
        }

        val pendingResult = goAsync()
        val application = context.applicationContext as? RegularNotificationsApplication
        if (application == null) {
            Log.e(TAG, "Recovery broadcast received without the application container")
            pendingResult.finish()
            return
        }

        application.applicationScope.launch {
            try {
                application.appContainer.reminderScheduler.reconcileAll()
            } catch (error: Throwable) {
                Log.e(TAG, "Scheduling recovery failed for action=${intent.action}", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ReminderNotifications"
        val RECOVERY_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
