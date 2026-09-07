package com.samuel.regularnotifications.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "reminders")
data class ReminderEntity(
    @androidx.room.PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val description: String?,
    val enabled: Boolean,
    val anchorLocalDate: String,
    val anchorLocalTime: String,
    val intervalDays: Int,
    val nextNormalOccurrenceIndex: Long,
    val nextNormalOccurrenceEpochMillis: Long,
    val nextNormalZoneId: String,
    val lastResolvedNormalOccurrenceIndex: Long?,
    val createdAtEpochMillis: Long,
    val modifiedAtEpochMillis: Long,
)

@Entity(
    tableName = "outstanding_due_states",
    foreignKeys = [
        ForeignKey(
            entity = ReminderEntity::class,
            parentColumns = ["id"],
            childColumns = ["reminderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("reminderId")],
)
data class OutstandingDueEntity(
    @androidx.room.PrimaryKey
    val reminderId: Long,
    val normalOccurrenceIndex: Long,
    val normalOccurrenceEpochMillis: Long,
    val dueAtEpochMillis: Long,
    val postponedUntilEpochMillis: Long?,
    val postponementCount: Int,
    val revision: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "tomorrow_previews",
    foreignKeys = [
        ForeignKey(
            entity = ReminderEntity::class,
            parentColumns = ["id"],
            childColumns = ["reminderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("reminderId")],
)
data class TomorrowPreviewEntity(
    @androidx.room.PrimaryKey
    val reminderId: Long,
    val normalOccurrenceIndex: Long,
    val occurrenceEpochMillis: Long,
    val previewEpochMillis: Long,
    val zoneId: String,
    val acknowledged: Boolean,
    val revision: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "reminder_events",
    foreignKeys = [
        ForeignKey(
            entity = ReminderEntity::class,
            parentColumns = ["id"],
            childColumns = ["reminderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("reminderId"), Index(value = ["reminderId", "occurredAtEpochMillis"])],
)
data class ReminderEventEntity(
    @androidx.room.PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val reminderId: Long,
    val normalOccurrenceIndex: Long?,
    val occurrenceScheduledEpochMillis: Long?,
    val action: String,
    val dueAtEpochMillis: Long?,
    val postponedUntilEpochMillis: Long?,
    val occurredAtEpochMillis: Long,
)
