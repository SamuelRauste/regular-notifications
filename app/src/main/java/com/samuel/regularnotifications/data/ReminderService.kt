package com.samuel.regularnotifications.data

import com.samuel.regularnotifications.data.local.ReminderEntity
import com.samuel.regularnotifications.domain.ReminderDraft
import com.samuel.regularnotifications.domain.ReminderEventType
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

    fun observeMasterEnabled(): Flow<Boolean> = repository.observeMasterEnabled()

    fun observeReminder(id: Long): Flow<ReminderEntity?> = repository.observeReminder(id)

    suspend fun setMasterEnabled(
        enabled: Boolean,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
    ): RepositoryActionResult {
        val result = repository.setMasterEnabled(enabled, zoneId, now)
        if (result == RepositoryActionResult.APPLIED) {
            scheduler.reconcileAll(now, zoneId)
        }
        return result
    }

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
            // Any action PendingIntent from the old presentation is stale once
            // the definition version changes. Remove both visible kinds and
            // their old alarms before rebuilding only the Room-current state.
            scheduler.cancelAll(id)
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

    suspend fun resolveNotification(
        id: Long,
        eventType: ReminderEventType,
        expectedRevision: Long,
        expectedNormalOccurrenceIndex: Long,
        expectedReminderModifiedAtEpochMillis: Long,
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): RepositoryActionResult = repository.resolve(
        id = id,
        eventType = eventType,
        expectedRevision = expectedRevision,
        expectedNormalOccurrenceIndex = expectedNormalOccurrenceIndex,
        expectedReminderModifiedAtEpochMillis = expectedReminderModifiedAtEpochMillis,
        now = now,
        zoneId = zoneId,
    )

    suspend fun postponeNotification(
        id: Long,
        expectedRevision: Long,
        expectedNormalOccurrenceIndex: Long,
        expectedReminderModifiedAtEpochMillis: Long,
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): RepositoryActionResult = repository.postpone(
        id = id,
        expectedRevision = expectedRevision,
        expectedNormalOccurrenceIndex = expectedNormalOccurrenceIndex,
        expectedReminderModifiedAtEpochMillis = expectedReminderModifiedAtEpochMillis,
        now = now,
        zoneId = zoneId,
    )

    suspend fun acknowledgeTomorrowNotification(
        id: Long,
        expectedRevision: Long,
        expectedNormalOccurrenceIndex: Long,
        expectedReminderModifiedAtEpochMillis: Long,
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): RepositoryActionResult = repository.acknowledgeTomorrow(
        id = id,
        expectedRevision = expectedRevision,
        expectedNormalOccurrenceIndex = expectedNormalOccurrenceIndex,
        expectedReminderModifiedAtEpochMillis = expectedReminderModifiedAtEpochMillis,
        now = now,
        zoneId = zoneId,
    )
}
