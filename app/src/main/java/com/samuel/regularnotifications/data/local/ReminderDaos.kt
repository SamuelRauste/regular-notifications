package com.samuel.regularnotifications.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AppSettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = ${AppSettingsEntity.SINGLETON_ID}")
    suspend fun get(): AppSettingsEntity?

    @Query("SELECT masterEnabled FROM app_settings WHERE id = ${AppSettingsEntity.SINGLETON_ID}")
    fun observeMasterEnabled(): Flow<Boolean?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: AppSettingsEntity)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY enabled DESC, nextNormalOccurrenceEpochMillis ASC, id ASC")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE id = :id")
    fun observeById(id: Long): Flow<ReminderEntity?>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getById(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminders ORDER BY id ASC")
    suspend fun getAll(): List<ReminderEntity>

    @Insert
    suspend fun insert(reminder: ReminderEntity): Long

    @Update
    suspend fun update(reminder: ReminderEntity)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface OutstandingDueDao {
    @Query("SELECT * FROM outstanding_due_states WHERE reminderId = :reminderId")
    suspend fun getByReminderId(reminderId: Long): OutstandingDueEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: OutstandingDueEntity)

    @Query("DELETE FROM outstanding_due_states WHERE reminderId = :reminderId")
    suspend fun deleteByReminderId(reminderId: Long)
}

@Dao
interface TomorrowPreviewDao {
    @Query("SELECT * FROM tomorrow_previews WHERE reminderId = :reminderId")
    suspend fun getByReminderId(reminderId: Long): TomorrowPreviewEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: TomorrowPreviewEntity)

    @Query("DELETE FROM tomorrow_previews WHERE reminderId = :reminderId")
    suspend fun deleteByReminderId(reminderId: Long)
}

@Dao
interface ReminderEventDao {
    @Query("SELECT * FROM reminder_events WHERE reminderId = :reminderId ORDER BY occurredAtEpochMillis DESC, id DESC")
    fun observeForReminder(reminderId: Long): Flow<List<ReminderEventEntity>>

    @Query("SELECT * FROM reminder_events ORDER BY occurredAtEpochMillis DESC, id DESC")
    fun observeAll(): Flow<List<ReminderEventEntity>>

    @Query("SELECT * FROM reminder_events WHERE reminderId = :reminderId ORDER BY occurredAtEpochMillis DESC, id DESC")
    suspend fun getForReminder(reminderId: Long): List<ReminderEventEntity>

    @Insert
    suspend fun insert(event: ReminderEventEntity): Long
}
