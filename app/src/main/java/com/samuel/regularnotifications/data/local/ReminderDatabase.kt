package com.samuel.regularnotifications.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        AppSettingsEntity::class,
        ReminderEntity::class,
        OutstandingDueEntity::class,
        TomorrowPreviewEntity::class,
        ReminderEventEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class ReminderDatabase : RoomDatabase() {
    abstract fun appSettingsDao(): AppSettingsDao

    abstract fun reminderDao(): ReminderDao

    abstract fun outstandingDueDao(): OutstandingDueDao

    abstract fun tomorrowPreviewDao(): TomorrowPreviewDao

    abstract fun reminderEventDao(): ReminderEventDao
}
