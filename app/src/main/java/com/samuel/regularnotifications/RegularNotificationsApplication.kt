package com.samuel.regularnotifications

import android.app.Application
import android.content.Context
import com.samuel.regularnotifications.data.ReminderRepository
import com.samuel.regularnotifications.data.ReminderService
import com.samuel.regularnotifications.data.local.ReminderDatabase
import com.samuel.regularnotifications.data.local.ReminderDatabaseProvider
import com.samuel.regularnotifications.notifications.ReminderNotificationChannels
import com.samuel.regularnotifications.scheduling.AlarmManagerReminderScheduler
import com.samuel.regularnotifications.scheduling.AndroidExactAlarmCapability
import com.samuel.regularnotifications.scheduling.ExactAlarmCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns the process-wide persistence graph. Future screens and receivers obtain
 * this one container from the application rather than opening Room separately.
 */
class RegularNotificationsApplication : Application() {
    internal val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val appContainer: AppContainer by lazy {
        AppContainer(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        ReminderNotificationChannels.ensureCreated(this)
        applicationScope.launch {
            try {
                appContainer.reminderScheduler.reconcileAll()
            } catch (error: Throwable) {
                android.util.Log.e(TAG, "Startup alarm reconciliation failed", error)
            }
        }
    }

    private companion object {
        const val TAG = "ReminderNotifications"
    }
}

class AppContainer(context: Context) {
    val database: ReminderDatabase = ReminderDatabaseProvider.open(context)

    val reminderRepository: ReminderRepository = ReminderRepository(database)

    val exactAlarmCapability: ExactAlarmCapability = AndroidExactAlarmCapability(context)

    val reminderScheduler: AlarmManagerReminderScheduler =
        AlarmManagerReminderScheduler(
            context = context,
            repository = reminderRepository,
            exactAlarmCapability = exactAlarmCapability,
        )

    val reminderService: ReminderService = ReminderService(reminderRepository, reminderScheduler)
}
