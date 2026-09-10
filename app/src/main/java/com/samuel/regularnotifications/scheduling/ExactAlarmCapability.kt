package com.samuel.regularnotifications.scheduling

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Reads whether this installation may use Android's exact-alarm APIs. */
interface ExactAlarmCapability {
    fun canScheduleExactAlarms(): Boolean
}

/** Android implementation with the API 31 permission boundary kept in one place. */
class AndroidExactAlarmCapability(context: Context) : ExactAlarmCapability {
    private val alarmManager = requireNotNull(
        context.applicationContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager,
    )

    override fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true

        return try {
            alarmManager.canScheduleExactAlarms()
        } catch (_: SecurityException) {
            // A permission change can race this check. Treat that race as denied;
            // the scheduler will use its inexact fallback.
            false
        }
    }
}

enum class AlarmScheduleMode {
    EXACT,
    INEXACT,
}

/** Pure policy used by the Android scheduler and directly covered by JVM tests. */
object AlarmSchedulePolicy {
    fun select(canScheduleExactAlarms: Boolean): AlarmScheduleMode =
        if (canScheduleExactAlarms) AlarmScheduleMode.EXACT else AlarmScheduleMode.INEXACT

    fun shouldReconcileAfterCapabilityChange(
        wasAvailable: Boolean,
        isAvailable: Boolean,
    ): Boolean = wasAvailable != isAvailable
}

internal fun exactAlarmAccessRequired(sdkInt: Int): Boolean = sdkInt >= 31

/** Returns the user-initiated Settings intent, or null on Android versions without this access. */
fun createExactAlarmSettingsIntent(context: Context): Intent? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null

    return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
