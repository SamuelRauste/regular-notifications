package com.samuel.regularnotifications.data

import com.samuel.regularnotifications.data.local.ReminderEntity
import com.samuel.regularnotifications.domain.ReminderDraft
import com.samuel.regularnotifications.domain.ReminderInput
import com.samuel.regularnotifications.scheduling.ReminderScheduler
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

/** Coordinates Room mutations with the derived Android alarm state. */
class ReminderService(
    private val repository: ReminderRepository,
    private val scheduler: ReminderScheduler,
) {
    fun observeReminders(): Flow<List<ReminderEntity>> = repository.observeReminders()

    fun observeReminder(id: Long): Flow<ReminderEntity?> = repository.observeReminder(id)

    suspend fun createReminder(
        input: ReminderInput,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
    ): Long {
        val id = repository.createReminder(input, zoneId, now)
        scheduler.reconcileReminder(id, now, zoneId)
        return id
    }

    suspend fun createReminder(
        draft: ReminderDraft,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
    ): Long {
        val id = repository.createReminder(draft, zoneId, now)
        scheduler.reconcileReminder(id, now, zoneId)
        return id
    }

    suspend fun updateReminder(
        id: Long,
        input: ReminderInput,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
    ): RepositoryActionResult {
        val result = repository.updateReminder(id, input, zoneId, now)
        if (result == RepositoryActionResult.APPLIED) {
            scheduler.reconcileReminder(id, now, zoneId)
        }
        return result
    }

    suspend fun setEnabled(
        id: Long,
        enabled: Boolean,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
    ): RepositoryActionResult {
        val result = repository.setEnabled(id, enabled, zoneId, now)
        if (result == RepositoryActionResult.APPLIED) {
            scheduler.reconcileReminder(id, now, zoneId)
        }
        return result
    }

    suspend fun deleteReminder(id: Long): RepositoryActionResult {
        scheduler.cancelAll(id)
        return repository.deleteReminder(id)
    }

    suspend fun reconcileAll(
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ) = scheduler.reconcileAll(now, zoneId)
}
