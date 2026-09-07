package com.samuel.regularnotifications.data

import androidx.room.withTransaction
import com.samuel.regularnotifications.data.local.OutstandingDueEntity
import com.samuel.regularnotifications.data.local.ReminderDatabase
import com.samuel.regularnotifications.data.local.ReminderEntity
import com.samuel.regularnotifications.data.local.ReminderEventEntity
import com.samuel.regularnotifications.data.local.TomorrowPreviewEntity
import com.samuel.regularnotifications.domain.ReminderDefinition
import com.samuel.regularnotifications.domain.ReminderDraft
import com.samuel.regularnotifications.domain.ReminderEventType
import com.samuel.regularnotifications.domain.ReminderInput
import com.samuel.regularnotifications.domain.ReminderScheduleState
import com.samuel.regularnotifications.domain.ReminderStateMachine
import com.samuel.regularnotifications.domain.ReminderValidator
import com.samuel.regularnotifications.domain.RecurrenceCalculator
import com.samuel.regularnotifications.domain.toDefinition
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

enum class RepositoryActionResult {
    APPLIED,
    NOT_FOUND,
    NO_OUTSTANDING_DUE,
    NO_TOMORROW_PREVIEW,
    ALREADY_ACKNOWLEDGED,
    STALE_REVISION,
}

class ReminderRepository(
    private val database: ReminderDatabase,
    private val clock: () -> Instant = { Instant.now() },
) {
    private val reminderDao = database.reminderDao()
    private val outstandingDueDao = database.outstandingDueDao()
    private val tomorrowPreviewDao = database.tomorrowPreviewDao()
    private val eventDao = database.reminderEventDao()

    fun observeReminders(): Flow<List<ReminderEntity>> = reminderDao.observeAll()

    fun observeReminder(id: Long): Flow<ReminderEntity?> = reminderDao.observeById(id)

    fun observeEvents(reminderId: Long): Flow<List<ReminderEventEntity>> =
        eventDao.observeForReminder(reminderId)

    suspend fun createReminder(
        draft: ReminderDraft,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = clock(),
    ): Long {
        val input = ReminderValidator.toInput(draft)
            ?: error("Cannot create an invalid reminder: ${ReminderValidator.validate(draft).errors}")
        return createReminder(input, zoneId, now)
    }

    suspend fun createReminder(
        input: ReminderInput,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = clock(),
    ): Long = database.withTransaction {
        val definition = input.toDefinition(id = 0)
        val nextNormal = RecurrenceCalculator.firstFuture(definition, now, zoneId)
        val entity = definition.toEntity(
            id = 0,
            nextNormal = nextNormal,
            createdAtEpochMillis = now.toEpochMilli(),
            modifiedAtEpochMillis = now.toEpochMilli(),
            zoneId = zoneId,
        )
        val id = reminderDao.insert(entity)
        val inserted = requireNotNull(reminderDao.getById(id))
        reconcileStored(inserted, now, zoneId)
        id
    }

    suspend fun updateReminder(
        id: Long,
        input: ReminderInput,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = clock(),
    ): RepositoryActionResult = database.withTransaction {
        val existing = reminderDao.getById(id) ?: return@withTransaction RepositoryActionResult.NOT_FOUND
        val definition = input.toDefinition(id = id)
        val nextNormal = RecurrenceCalculator.firstFuture(definition, now, zoneId)
        reminderDao.update(
            definition.toEntity(
                id = id,
                nextNormal = nextNormal,
                createdAtEpochMillis = existing.createdAtEpochMillis,
                modifiedAtEpochMillis = now.toEpochMilli(),
                zoneId = zoneId,
            ),
        )
        outstandingDueDao.deleteByReminderId(id)
        tomorrowPreviewDao.deleteByReminderId(id)
        reconcileStored(requireNotNull(reminderDao.getById(id)), now, zoneId)
        RepositoryActionResult.APPLIED
    }

    suspend fun setEnabled(
        id: Long,
        enabled: Boolean,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = clock(),
    ): RepositoryActionResult = database.withTransaction {
        val existing = reminderDao.getById(id) ?: return@withTransaction RepositoryActionResult.NOT_FOUND
        val updated = existing.copy(
            enabled = enabled,
            modifiedAtEpochMillis = now.toEpochMilli(),
        )
        reminderDao.update(updated)
        if (enabled) {
            reconcileStored(updated, now, zoneId)
        } else {
            outstandingDueDao.deleteByReminderId(id)
            tomorrowPreviewDao.deleteByReminderId(id)
        }
        RepositoryActionResult.APPLIED
    }

    suspend fun deleteReminder(id: Long): RepositoryActionResult = database.withTransaction {
        val existing = reminderDao.getById(id) ?: return@withTransaction RepositoryActionResult.NOT_FOUND
        outstandingDueDao.deleteByReminderId(id)
        tomorrowPreviewDao.deleteByReminderId(id)
        reminderDao.deleteById(existing.id)
        RepositoryActionResult.APPLIED
    }

    suspend fun reconcileReminder(
        id: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = clock(),
    ): RepositoryActionResult = database.withTransaction {
        val reminder = reminderDao.getById(id) ?: return@withTransaction RepositoryActionResult.NOT_FOUND
        reconcileStored(reminder, now, zoneId)
        RepositoryActionResult.APPLIED
    }

    suspend fun reconcileAll(
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = clock(),
    ) = database.withTransaction {
        reminderDao.getAll().forEach { reconcileStored(it, now, zoneId) }
    }

    suspend fun postpone(
        id: Long,
        expectedRevision: Long? = null,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = clock(),
    ): RepositoryActionResult = database.withTransaction {
        val normalized = normalize(id, now, zoneId)
            ?: return@withTransaction RepositoryActionResult.NOT_FOUND
        val due = normalized.state.outstandingDue
            ?: return@withTransaction RepositoryActionResult.NO_OUTSTANDING_DUE
        if (expectedRevision != null && expectedRevision != due.revision) {
            return@withTransaction RepositoryActionResult.STALE_REVISION
        }

        val postponed = ReminderStateMachine.postpone(normalized.state, now, zoneId)
        persistSchedule(normalized.reminder, postponed, now, zoneId)
        eventDao.insert(
            ReminderEventEntity(
                reminderId = id,
                normalOccurrenceIndex = due.normalOccurrenceIndex,
                occurrenceScheduledEpochMillis = due.normalOccurrenceEpochMillis,
                action = ReminderEventType.POSTPONED.name,
                dueAtEpochMillis = due.dueAtEpochMillis,
                postponedUntilEpochMillis = postponed.outstandingDue!!.postponedUntilEpochMillis,
                occurredAtEpochMillis = now.toEpochMilli(),
            ),
        )
        RepositoryActionResult.APPLIED
    }

    suspend fun resolve(
        id: Long,
        eventType: ReminderEventType,
        expectedRevision: Long? = null,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = clock(),
    ): RepositoryActionResult = database.withTransaction {
        require(eventType == ReminderEventType.DONE || eventType == ReminderEventType.DISMISSED)
        val normalized = normalize(id, now, zoneId)
            ?: return@withTransaction RepositoryActionResult.NOT_FOUND
        val due = normalized.state.outstandingDue
            ?: return@withTransaction RepositoryActionResult.NO_OUTSTANDING_DUE
        if (expectedRevision != null && expectedRevision != due.revision) {
            return@withTransaction RepositoryActionResult.STALE_REVISION
        }

        val resolved = ReminderStateMachine.resolve(normalized.definition, normalized.state, now, zoneId)
        persistSchedule(normalized.reminder, resolved, now, zoneId)
        eventDao.insert(
            ReminderEventEntity(
                reminderId = id,
                normalOccurrenceIndex = due.normalOccurrenceIndex,
                occurrenceScheduledEpochMillis = due.normalOccurrenceEpochMillis,
                action = eventType.name,
                dueAtEpochMillis = due.dueAtEpochMillis,
                postponedUntilEpochMillis = due.postponedUntilEpochMillis,
                occurredAtEpochMillis = now.toEpochMilli(),
            ),
        )
        RepositoryActionResult.APPLIED
    }

    suspend fun acknowledgeTomorrow(
        id: Long,
        expectedRevision: Long? = null,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = clock(),
    ): RepositoryActionResult = database.withTransaction {
        val reminder = reminderDao.getById(id)
            ?: return@withTransaction RepositoryActionResult.NOT_FOUND
        val persistedPreview = tomorrowPreviewDao.getByReminderId(id)
            ?: return@withTransaction RepositoryActionResult.NO_TOMORROW_PREVIEW
        if (persistedPreview.acknowledged) {
            return@withTransaction RepositoryActionResult.ALREADY_ACKNOWLEDGED
        }

        // Reconciliation preserves a current (slightly late) preview. It can
        // still discard an actually obsolete one, such as a target occurrence
        // that is now due. Check the resulting revision rather than accepting
        // a stale action tied to the pre-reschedule state.
        val reconciled = reconcileStored(reminder, now, zoneId)
        val preview = reconciled.tomorrowPreview
            ?: return@withTransaction RepositoryActionResult.NO_TOMORROW_PREVIEW
        if (preview.acknowledged) {
            return@withTransaction RepositoryActionResult.ALREADY_ACKNOWLEDGED
        }
        if (expectedRevision != null && expectedRevision != preview.revision) {
            return@withTransaction RepositoryActionResult.STALE_REVISION
        }

        val acknowledged = ReminderStateMachine.acknowledgeTomorrow(reconciled)
        persistSchedule(requireNotNull(reminderDao.getById(id)), acknowledged, now, zoneId)
        eventDao.insert(
            ReminderEventEntity(
                reminderId = id,
                normalOccurrenceIndex = preview.normalOccurrenceIndex,
                occurrenceScheduledEpochMillis = preview.occurrenceEpochMillis,
                action = ReminderEventType.TOMORROW_SEEN.name,
                dueAtEpochMillis = null,
                postponedUntilEpochMillis = null,
                occurredAtEpochMillis = now.toEpochMilli(),
            ),
        )
        RepositoryActionResult.APPLIED
    }

    private suspend fun normalize(
        id: Long,
        now: Instant,
        zoneId: ZoneId,
    ): NormalizedReminder? {
        val reminder = reminderDao.getById(id) ?: return null
        val state = reconcileStored(reminder, now, zoneId)
        return NormalizedReminder(
            reminder = requireNotNull(reminderDao.getById(id)),
            definition = reminder.toDefinition(),
            state = state,
        )
    }

    private suspend fun reconcileStored(
        reminder: ReminderEntity,
        now: Instant,
        zoneId: ZoneId,
    ): ReminderScheduleState {
        val current = loadState(reminder)
        val definition = reminder.toDefinition()
        val state = if (reminder.enabled) {
            ReminderStateMachine.reconcile(definition, current, now, zoneId)
        } else {
            ReminderScheduleState(
                nextNormal = RecurrenceCalculator.firstFuture(definition, now, zoneId),
                outstandingDue = null,
                tomorrowPreview = null,
                lastResolvedNormalOccurrenceIndex = reminder.lastResolvedNormalOccurrenceIndex,
            )
        }
        persistSchedule(reminder, state, now, zoneId)
        return state
    }

    private suspend fun loadState(reminder: ReminderEntity): ReminderScheduleState {
        val due = outstandingDueDao.getByReminderId(reminder.id)
        val preview = tomorrowPreviewDao.getByReminderId(reminder.id)
        return ReminderScheduleState(
            nextNormal = com.samuel.regularnotifications.domain.NormalOccurrence(
                index = reminder.nextNormalOccurrenceIndex,
                scheduledAt = Instant.ofEpochMilli(reminder.nextNormalOccurrenceEpochMillis),
                zoneId = ZoneId.of(reminder.nextNormalZoneId),
            ),
            outstandingDue = due?.toDomain(),
            tomorrowPreview = preview?.toDomain(),
            lastResolvedNormalOccurrenceIndex = reminder.lastResolvedNormalOccurrenceIndex,
        )
    }

    private suspend fun persistSchedule(
        reminder: ReminderEntity,
        state: ReminderScheduleState,
        now: Instant,
        zoneId: ZoneId,
    ) {
        reminderDao.update(
            reminder.copy(
                nextNormalOccurrenceIndex = state.nextNormal.index,
                nextNormalOccurrenceEpochMillis = state.nextNormal.scheduledAt.toEpochMilli(),
                nextNormalZoneId = zoneId.id,
                lastResolvedNormalOccurrenceIndex = state.lastResolvedNormalOccurrenceIndex,
            ),
        )

        val oldDue = outstandingDueDao.getByReminderId(reminder.id)
        val due = state.outstandingDue
        if (due == null) {
            outstandingDueDao.deleteByReminderId(reminder.id)
        } else {
            outstandingDueDao.upsert(
                due.toEntity(
                    reminderId = reminder.id,
                    createdAtEpochMillis = oldDue?.createdAtEpochMillis ?: now.toEpochMilli(),
                    updatedAtEpochMillis = now.toEpochMilli(),
                ),
            )
        }

        val oldPreview = tomorrowPreviewDao.getByReminderId(reminder.id)
        val preview = state.tomorrowPreview
        if (preview == null) {
            tomorrowPreviewDao.deleteByReminderId(reminder.id)
        } else {
            tomorrowPreviewDao.upsert(
                preview.toEntity(
                    reminderId = reminder.id,
                    createdAtEpochMillis = oldPreview?.createdAtEpochMillis ?: now.toEpochMilli(),
                    updatedAtEpochMillis = now.toEpochMilli(),
                ),
            )
        }
    }

    private data class NormalizedReminder(
        val reminder: ReminderEntity,
        val definition: ReminderDefinition,
        val state: ReminderScheduleState,
    )
}

private fun ReminderDefinition.toEntity(
    id: Long,
    nextNormal: com.samuel.regularnotifications.domain.NormalOccurrence,
    createdAtEpochMillis: Long,
    modifiedAtEpochMillis: Long,
    zoneId: ZoneId,
    lastResolvedNormalOccurrenceIndex: Long? = null,
): ReminderEntity =
    ReminderEntity(
        id = id,
        title = title,
        description = description,
        enabled = enabled,
        anchorLocalDate = anchorLocalDate.toString(),
        anchorLocalTime = anchorLocalTime.toString(),
        intervalDays = intervalDays,
        nextNormalOccurrenceIndex = nextNormal.index,
        nextNormalOccurrenceEpochMillis = nextNormal.scheduledAt.toEpochMilli(),
        nextNormalZoneId = zoneId.id,
        lastResolvedNormalOccurrenceIndex = lastResolvedNormalOccurrenceIndex,
        createdAtEpochMillis = createdAtEpochMillis,
        modifiedAtEpochMillis = modifiedAtEpochMillis,
    )

private fun ReminderEntity.toDefinition(): ReminderDefinition =
    ReminderDefinition(
        id = id,
        title = title,
        description = description,
        enabled = enabled,
        anchorLocalDate = java.time.LocalDate.parse(anchorLocalDate),
        anchorLocalTime = java.time.LocalTime.parse(anchorLocalTime),
        intervalDays = intervalDays,
    )

private fun OutstandingDueEntity.toDomain() =
    com.samuel.regularnotifications.domain.OutstandingDueState(
        normalOccurrenceIndex = normalOccurrenceIndex,
        normalOccurrenceEpochMillis = normalOccurrenceEpochMillis,
        dueAtEpochMillis = dueAtEpochMillis,
        postponedUntilEpochMillis = postponedUntilEpochMillis,
        postponementCount = postponementCount,
        revision = revision,
    )

private fun com.samuel.regularnotifications.domain.OutstandingDueState.toEntity(
    reminderId: Long,
    createdAtEpochMillis: Long,
    updatedAtEpochMillis: Long,
) = OutstandingDueEntity(
    reminderId = reminderId,
    normalOccurrenceIndex = normalOccurrenceIndex,
    normalOccurrenceEpochMillis = normalOccurrenceEpochMillis,
    dueAtEpochMillis = dueAtEpochMillis,
    postponedUntilEpochMillis = postponedUntilEpochMillis,
    postponementCount = postponementCount,
    revision = revision,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun TomorrowPreviewEntity.toDomain() =
    com.samuel.regularnotifications.domain.TomorrowPreviewState(
        normalOccurrenceIndex = normalOccurrenceIndex,
        occurrenceEpochMillis = occurrenceEpochMillis,
        previewEpochMillis = previewEpochMillis,
        zoneId = zoneId,
        acknowledged = acknowledged,
        revision = revision,
    )

private fun com.samuel.regularnotifications.domain.TomorrowPreviewState.toEntity(
    reminderId: Long,
    createdAtEpochMillis: Long,
    updatedAtEpochMillis: Long,
) = TomorrowPreviewEntity(
    reminderId = reminderId,
    normalOccurrenceIndex = normalOccurrenceIndex,
    occurrenceEpochMillis = occurrenceEpochMillis,
    previewEpochMillis = previewEpochMillis,
    zoneId = zoneId,
    acknowledged = acknowledged,
    revision = revision,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)
