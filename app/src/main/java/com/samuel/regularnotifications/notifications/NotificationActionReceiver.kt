package com.samuel.regularnotifications.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.samuel.regularnotifications.RegularNotificationsApplication
import kotlinx.coroutines.launch

/** Processes explicit notification action and delete-intent broadcasts. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val request = NotificationActionContract.parse(intent)
        if (request == null) {
            Log.w(TAG, "Ignoring malformed notification action broadcast")
            return
        }

        val pendingResult = goAsync()
        val application = context.applicationContext as? RegularNotificationsApplication
        if (application == null) {
            Log.e(TAG, "Notification action received without the application container")
            pendingResult.finish()
            return
        }

        application.applicationScope.launch {
            try {
                val result = application.appContainer.notificationActionProcessor.process(request)
                Log.d(
                    TAG,
                    "Processed notification action reminderId=${request.reminderId} " +
                        "kind=${request.notificationKind} action=${request.action} result=$result",
                )
            } catch (error: Throwable) {
                Log.e(
                    TAG,
                    "Notification action failed for reminderId=${request.reminderId}",
                    error,
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ReminderNotifications"
    }
}
