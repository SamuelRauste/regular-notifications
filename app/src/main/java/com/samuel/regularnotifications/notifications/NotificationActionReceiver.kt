package com.samuel.regularnotifications.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Phase 3 contract stub. Phase 5 will add repository mutations here; this
 * receiver deliberately does not resolve, dismiss, postpone, or acknowledge.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(
            TAG,
            "Ignoring Phase 3 notification action=${intent.getStringExtra(NotificationActionContract.EXTRA_ACTION)}",
        )
    }

    private companion object {
        const val TAG = "ReminderNotifications"
    }
}
