package com.samuel.regularnotifications.scheduling

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.samuel.regularnotifications.RegularNotificationsApplication
import kotlinx.coroutines.launch

/** Rebuilds disposable alarms after lifecycle, clock, or package changes. */
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
                if (isExactAlarmPermissionStateChanged(intent)) {
                    val exactAccessAvailable =
                        application.appContainer.exactAlarmCapability.canScheduleExactAlarms()
                    Log.i(
                        TAG,
                        "Exact-alarm permission state changed; available=$exactAccessAvailable",
                    )
                }
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
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            EXACT_ALARM_PERMISSION_STATE_CHANGED_ACTION,
        )

        private const val EXACT_ALARM_PERMISSION_STATE_CHANGED_ACTION =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"

        private fun isExactAlarmPermissionStateChanged(intent: Intent): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
    }
}
