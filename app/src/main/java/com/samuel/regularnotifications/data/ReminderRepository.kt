package com.samuel.regularnotifications.data

import androidx.room.withTransaction
import com.samuel.regularnotifications.data.local.AppSettingsEntity
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
import com.samuel.regularnotifications.domain.ReminderSchedulingSnapshot
import com.samuel.regularnotifications.domain.ReminderStateMachine
import com.samuel.regularnotifications.domain.ReminderValidator
import com.samuel.regularnotifications.domain.RecurrenceCalculator
import com.samuel.regularnotifications.domain.toDefinition
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
    private val appSettingsDao = database.appSettingsDao()
    private val reminderDao = database.reminderDao()
    private val outstandingDueDao = database.outstandingDueDao()
    private val tomorrowPreviewDao = database.tomorrowPreviewDao()
    private val eventDao = database.reminderEventDao()

    fun observeReminders(): Flow<List<ReminderEntity>> = reminderDao.observeAll()

    fun observeMasterEnabled(): Flow<Boolean> =
        appSettingsDao.observeMasterEnabled().map { it ?: true }

    fun observeReminder(id: Long): Flow<ReminderEntity?> = reminderDao.observeById(id)

    fun observeEvents(reminderId: Long): Flow<List<ReminderEventEntity>> =
        eventDao.observeForReminder(reminderId)

    suspend fun getMasterEnabled(): Boolean = database.withTransaction {
        ensureMasterEnabled()
    }

    /**
     * Persists the global delivery switch and reconciles every reminder in the
     * same Room transaction. A global pause uses the same inactive-occurrence
     * cursor operation as an individual pause, so occurrences are skipped
     * without creating fake history or changing each reminder's enabled flag.
     */
    suspend fun setMasterEnabled(
        enabled: Boolean,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = clock(),
    ): RepositoryActionResult = database.withTransaction {
        val wasMasterEnabled = ensureMasterEnabled()
        val reminders = reminderDao.getAll()
        if (!enabled && wasMasterEnabled) {
            appSettingsDao.upsert(AppSettingsEntity(masterEnabled = false))
            reminders.forEach { reminder ->
                reconcileStored(
                    reminder = reminder,
                    now = now,
                    zoneId = zoneId,
                    masterEnabled = false,
                )
            }
            return@withTransaction RepositoryActionResult.APPLIED
        }

        // If the app was globally paused while the process was dead, advance
        // every reminder's inactive cursor before turning delivery back on.
        // This prevents the disabled period from becoming an overdue backlog.
        if (enabled && !wasMasterEnabled) {
            reminders.forEach { reminder ->
                reconcileStored(
                    reminder = reminder,
                    now = now,
                    zoneId = zoneId,
                    masterEnabled = false,
                )
            }
        }
        appSettingsDao.upsert(AppSettingsEntity(masterEnabled = enabled))
        reminders.forEach { reminder ->
            reconcileStored(
                reminder = reminder,
                now = now,
                zoneId = zoneId,
                masterEnabled = enabled,
            )
        }
        RepositoryActionResult.APPLIED
    }

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
        val masterEnabled = ensureMasterEnabled()
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
        reconcileStored(inserted, now, zoneId, masterEnabled)
        id
    }

    suspend fun updateReminder(
        id: Long,
        input: ReminderInput,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = clock(),
    ): RepositoryActionResult = database.withTransaction {
        val masterEnabled = ensureMasterEnabled()
        val existing = reminderDao.getById(id) ?: return@withTransaction RepositoryActionResult.NOT_FOUND
        val definition = input.toDefinition(id = id)
        if (existing.scheduleDefinitionChanged(definition)) {
            replaceScheduleForEdit(existing, definition, masterEnabled, now, zoneId)
        } else {
            updateUnchangedSchedule(existing, definition, masterEnabled, now, zoneId)
        }
        RepositoryActionResult.APPLIED
    }

    suspend fun setEnabled(
        id: Long,
        enabled: Boolean,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = clock(),
    ): RepositoryActionResult = database.withTransaction {
        val masterEnabled = ensureMasterEnabled()
        val existing = reminderDao.getById(id) ?: return@withTransaction RepositoryActionResult.NOT_FOUND
        if (existing.enabled == enabled) {
            reconcileStored(existing, now, zoneId, masterEnabled)
        } else {
            val skipped = ReminderStateMachine.skipInactiveOccurrences(
                definition = existing.toDefinition(),
                current = loadState(existing),
                now = now,
                zoneId = zoneId,
            )
            val updated = existing.copy(
                enabled = enabled,
                modifiedAtEpochMillis = nextModifiedAt(existing, now),
            )
            reminderDao.update(updated)
            persistSchedule(updated, skipped, now, zoneId)

            // Reconcile after enabling so the next future occurrence can get
            // its normal derived state (for example, a new Tomorrow preview).
            if (enabled) {
                reconcileStored(requireNotNull(reminderDao.getById(id)), now, zoneId, masterEnabled)
            }
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
        val masterEnabled = ensureMasterEnabled()
        val reminder = reminderDao.getById(id) ?: return@withTransaction RepositoryActionResult.NOT_FOUND
        reconcileStored(reminder, now, zoneId, masterEnabled)
        RepositoryActionResult.APPLIED
    }

    suspend fun reconcileAll(
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = clock(),
    ) {
        reconcileAllForScheduling(zoneId = zoneId, now = now)
    }

    /**
     * Reconciles one reminder in Room and returns the exact state from which
     * Android alarms may be derived. A missing row means that any old alarm
     * for the ID must be cancelled by the caller.
     */
    suspend fun reconcileReminderForScheduling(
        id: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = clock(),
    ): ReminderSchedulingSnapshot? = database.withTransaction {
        val masterEnabled = ensureMasterEnabled()
        val reminder = reminderDao.getById(id) ?: return@withTransaction null
        val state = reconcileStored(reminder, now, zoneId, masterEnabled)
        val stored = requireNotNull(reminderDao.getById(id))
        ReminderSchedulingSnapshot(
            definition = stored.toDefinition(),
            state = state,
            masterEnabled = masterEnabled,
            reminderModifiedAtEpochMillis = stored.modifiedAtEpochMillis,
        )
    }

    /** Reconciles every Room row, making a complete alarm rebuild possible. */
    suspend fun reconcileAllForScheduling(
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = clock(),
    ): List<ReminderSchedulingSnapshot> = database.withTransaction {
        val masterEnabled = ensureMasterEnabled()
        reminderDao.getAll().map { reminder ->
            val state = reconcileStored(reminder, now, zoneId, masterEnabled)
            val stored = requireNotNull(reminderDao.getById(reminder.id))
            ReminderSchedulingSnapshot(
                definition = stored.toDefinition(),
                state = state,
                masterEnabled = masterEnabled,
                reminderModifiedAtEpochMillis = stored.modifiedAtEpochMillis,
            )
        }
    }

    suspend fun postpone(
        id: Long,
        expectedRevision: Long? = null,
        expectedNormalOccurrenceIndex: Long? = null,
        expectedReminderModifiedAtEpochMillis: Long? = null,
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
        if (expectedNormalOccurrenceIndex != null &&
            expectedNormalOccurrenceIndex != due.normalOccurrenceIndex
        ) {
            return@withTransaction RepositoryActionResult.STALE_REVISION
        }
        if (expectedReminderModifiedAtEpochMillis != null &&
            expectedReminderModifiedAtEpochMillis != normalized.reminder.modifiedAtEpochMillis
        ) {
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
        expectedNormalOccurrenceIndex: Long? = null,
        expectedReminderModifiedAtEpochMillis: Long? = null,
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
        if (expectedNormalOccurrenceIndex != null &&
            expectedNormalOccurrenceIndex != due.normalOccurrenceIndex
        ) {
            return@withTransaction RepositoryActionResult.STALE_REVISION
        }
        if (expectedReminderModifiedAtEpochMillis != null &&
            expectedReminderModifiedAtEpochMillis != normalized.reminder.modifiedAtEpochMillis
        ) {
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
        expectedNormalOccurrenceIndex: Long? = null,
        expectedReminderModifiedAtEpochMillis: Long? = null,
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
        if (expectedReminderModifiedAtEpochMillis != null &&
            expectedReminderModifiedAtEpochMillis != reminder.modifiedAtEpochMillis
        ) {
            return@withTransaction RepositoryActionResult.STALE_REVISION
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
        if (expectedNormalOccurrenceIndex != null &&
            expectedNormalOccurrenceIndex != preview.normalOccurrenceIndex
        ) {
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
        val masterEnabled = ensureMasterEnabled()
        val reminder = reminderDao.getById(id) ?: return null
        val state = reconcileStored(reminder, now, zoneId, masterEnabled)
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
        masterEnabled: Boolean? = null,
    ): ReminderScheduleState {
        val current = loadState(reminder)
        val definition = reminder.toDefinition()
        val deliveryEnabled = (masterEnabled ?: ensureMasterEnabled()) && reminder.enabled
        val state = if (deliveryEnabled) {
            ReminderStateMachine.reconcile(definition, current, now, zoneId)
        } else {
            ReminderStateMachine.skipInactiveOccurrences(
                definition = definition,
                current = current,
                now = now,
                zoneId = zoneId,
            )
        }
        persistSchedule(reminder, state, now, zoneId)
        return state
    }

    /**
     * A title/description edit keeps the logical schedule state intact. An
     * enabled-state change uses the same skip/resume transition as the direct
     * list switch, while retaining the unchanged schedule definition.
     */
    private suspend fun updateUnchangedSchedule(
        existing: ReminderEntity,
        definition: ReminderDefinition,
        masterEnabled: Boolean,
        now: Instant,
        zoneId: ZoneId,
    ) {
        val updated = existing.copy(
            title = definition.title,
            description = definition.description,
            enabled = definition.enabled,
            modifiedAtEpochMillis = nextModifiedAt(existing, now),
        )
        reminderDao.update(updated)

        if (existing.enabled == definition.enabled) {
            // Preserve the cursor and persisted DUE/TOMORROW rows. Reconcile
            // only discards state that is no longer logically current.
            reconcileStored(updated, now, zoneId, masterEnabled)
            return
        }

        val skipped = ReminderStateMachine.skipInactiveOccurrences(
            definition = definition,
            current = loadState(existing),
            now = now,
            zoneId = zoneId,
        )
        persistSchedule(updated, skipped, now, zoneId)

        if (definition.enabled) {
            // Re-enable through the editor follows the direct switch rule:
            // never recover a disabled-period backlog.
            reconcileStored(requireNotNull(reminderDao.getById(existing.id)), now, zoneId, masterEnabled)
        }
    }

    /**
     * A new anchor or interval creates a new logical occurrence sequence, so
     * the old cursor and derived DUE/TOMORROW rows are deliberately not reused.
     * Past occurrences on the replacement schedule are skipped without history
     * so editing does not manufacture an overdue backlog.
     */
    private suspend fun replaceScheduleForEdit(
        existing: ReminderEntity,
        definition: ReminderDefinition,
        masterEnabled: Boolean,
        now: Instant,
        zoneId: ZoneId,
    ) {
        val nextNormal = RecurrenceCalculator.firstFuture(definition, now, zoneId)
        val replacement = definition.toEntity(
            id = existing.id,
            nextNormal = nextNormal,
            createdAtEpochMillis = existing.createdAtEpochMillis,
            modifiedAtEpochMillis = nextModifiedAt(existing, now),
            zoneId = zoneId,
            lastResolvedNormalOccurrenceIndex = null,
        )
        reminderDao.update(replacement)
        outstandingDueDao.deleteByReminderId(existing.id)
        tomorrowPreviewDao.deleteByReminderId(existing.id)

        val skippedReplacementPast = ReminderStateMachine.skipInactiveOccurrences(
            definition = definition,
            current = ReminderScheduleState(
                nextNormal = nextNormal,
                outstandingDue = null,
                tomorrowPreview = null,
                lastResolvedNormalOccurrenceIndex = null,
            ),
            now = now,
            zoneId = zoneId,
        )
        persistSchedule(replacement, skippedReplacementPast, now, zoneId)
        reconcileStored(
            requireNotNull(reminderDao.getById(existing.id)),
            now,
            zoneId,
            masterEnabled,
        )
    }

    private suspend fun ensureMasterEnabled(): Boolean {
        val current = appSettingsDao.get()
        if (current != null) return current.masterEnabled
        appSettingsDao.upsert(AppSettingsEntity())
        return true
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

    private fun nextModifiedAt(existing: ReminderEntity, now: Instant): Long {
        val nowEpochMillis = now.toEpochMilli()
        return if (existing.modifiedAtEpochMillis == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            maxOf(nowEpochMillis, existing.modifiedAtEpochMillis + 1)
        }
    }
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

private fun ReminderEntity.scheduleDefinitionChanged(definition: ReminderDefinition): Boolean =
    anchorLocalDate != definition.anchorLocalDate.toString() ||
        anchorLocalTime != definition.anchorLocalTime.toString() ||
        intervalDays != definition.intervalDays

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
