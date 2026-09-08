package com.samuel.regularnotifications.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.samuel.regularnotifications.RegularNotificationsApplication
import kotlinx.coroutines.launch

/** Receives only the explicit PendingIntents created by AlarmContract. */
class AlarmDeliveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val request = AlarmContract.parse(intent)
        if (request == null) {
            Log.w(TAG, "Ignoring malformed alarm broadcast")
            return
        }

        val pendingResult = goAsync()
        val application = context.applicationContext as? RegularNotificationsApplication
        if (application == null) {
            Log.e(TAG, "Alarm broadcast received without the application container")
            pendingResult.finish()
            return
        }

        application.applicationScope.launch {
            try {
                application.appContainer.reminderScheduler.deliver(request)
            } catch (error: Throwable) {
                Log.e(TAG, "Alarm delivery failed for reminderId=${request.reminderId}", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ReminderNotifications"
    }
}
