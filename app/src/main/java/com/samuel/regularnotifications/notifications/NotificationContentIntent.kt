package com.samuel.regularnotifications.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.samuel.regularnotifications.MainActivity
import com.samuel.regularnotifications.domain.NotificationIdentity
import com.samuel.regularnotifications.domain.NotificationKind

/** Opens the existing main list when the notification body is tapped. */
object NotificationContentIntent {
    fun create(
        context: Context,
        reminderId: Long,
        kind: NotificationKind,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(NotificationIdentity.contentPendingIntentData(reminderId, kind))
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context,
            NotificationIdentity.contentPendingIntentRequestCode(reminderId, kind),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
