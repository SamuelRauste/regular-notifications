package com.samuel.regularnotifications.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ReminderEntity::class,
        OutstandingDueEntity::class,
        TomorrowPreviewEntity::class,
        ReminderEventEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ReminderDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao

    abstract fun outstandingDueDao(): OutstandingDueDao

    abstract fun tomorrowPreviewDao(): TomorrowPreviewDao

    abstract fun reminderEventDao(): ReminderEventDao
}
