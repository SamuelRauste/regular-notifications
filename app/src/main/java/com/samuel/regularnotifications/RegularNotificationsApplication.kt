package com.samuel.regularnotifications

import android.app.Application
import android.content.Context
import com.samuel.regularnotifications.data.ReminderRepository
import com.samuel.regularnotifications.data.local.ReminderDatabase
import com.samuel.regularnotifications.data.local.ReminderDatabaseProvider
import com.samuel.regularnotifications.notifications.ReminderNotificationChannels

/**
 * Owns the process-wide persistence graph. Future screens and receivers obtain
 * this one container from the application rather than opening Room separately.
 */
class RegularNotificationsApplication : Application() {
    val appContainer: AppContainer by lazy {
        AppContainer(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        ReminderNotificationChannels.ensureCreated(this)
    }
}

class AppContainer(context: Context) {
    val database: ReminderDatabase = ReminderDatabaseProvider.open(context)

    val reminderRepository: ReminderRepository = ReminderRepository(database)
}
