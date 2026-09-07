package com.samuel.regularnotifications.data.local

import android.content.Context
import androidx.room.Room

object ReminderDatabaseProvider {
    internal fun open(context: Context): ReminderDatabase =
        Room.databaseBuilder(
            context = context.applicationContext,
            klass = ReminderDatabase::class.java,
            name = "reminders.db",
        )
            // Development-only schema v1 stored obsolete interval-unit and
            // duration-anchor fields. This pre-release app intentionally
            // recreates that local database for the simpler v2 model.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
}
