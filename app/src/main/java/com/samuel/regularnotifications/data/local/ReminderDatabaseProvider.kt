package com.samuel.regularnotifications.data.local

import android.content.Context
import androidx.room.Room

object ReminderDatabaseProvider {
    internal fun open(context: Context): ReminderDatabase =
        Room.databaseBuilder(
            context = context.applicationContext,
            klass = ReminderDatabase::class.java,
            name = "reminders.db",
        ).build()
}
