package com.samuel.regularnotifications.notifications

import com.samuel.regularnotifications.data.ReminderService
import com.samuel.regularnotifications.data.RepositoryActionResult
import com.samuel.regularnotifications.domain.ReminderEventType
import com.samuel.regularnotifications.domain.NotificationKind
import com.samuel.regularnotifications.scheduling.ReminderScheduler
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coordinates validated notification actions with Room, cleanup, and rescheduling. */
class NotificationActionProcessor(
    private val reminderService: ReminderService,
    private val reminderScheduler: ReminderScheduler,
) {
    private val actionMutex = Mutex()

    suspend fun process(
        request: NotificationActionRequest,
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): RepositoryActionResult = actionMutex.withLock {
        val result = when (request.notificationKind) {
            NotificationKind.DUE -> processDue(request, now, zoneId)
            NotificationKind.TOMORROW -> processTomorrow(request, now, zoneId)
        }

        when (result) {
            RepositoryActionResult.NOT_FOUND -> reminderScheduler.cancelAll(request.reminderId)

            RepositoryActionResult.STALE_REVISION -> {
                // The current notification may belong to a newer state. Rebuild
                // scheduling without cancelling that potentially newer target.
            }

            else -> cancelObsoleteVisibleNotification(request, result)
        }

        if (result != RepositoryActionResult.NOT_FOUND) {
            // This re-reads Room, so a delete/edit racing with cleanup is still
            // handled by the scheduler's normal stale/deleted-state checks.
            reminderScheduler.reconcileReminder(
                reminderId = request.reminderId,
                now = now,
                zoneId = zoneId,
            )
        }
        result
    }

    private suspend fun processDue(
        request: NotificationActionRequest,
        now: Instant,
        zoneId: ZoneId,
    ): RepositoryActionResult = when (request.action) {
        NotificationAction.DONE -> reminderService.resolveNotification(
            id = request.reminderId,
            eventType = ReminderEventType.DONE,
            expectedRevision = request.expectedRevision,
            expectedNormalOccurrenceIndex = request.expectedNormalOccurrenceIndex,
            expectedReminderModifiedAtEpochMillis = request.expectedReminderModifiedAtEpochMillis,
            now = now,
            zoneId = zoneId,
        )

        NotificationAction.DISMISS -> reminderService.resolveNotification(
            id = request.reminderId,
            eventType = ReminderEventType.DISMISSED,
            expectedRevision = request.expectedRevision,
            expectedNormalOccurrenceIndex = request.expectedNormalOccurrenceIndex,
            expectedReminderModifiedAtEpochMillis = request.expectedReminderModifiedAtEpochMillis,
            now = now,
            zoneId = zoneId,
        )

        NotificationAction.POSTPONE_ONE_DAY -> reminderService.postponeNotification(
            id = request.reminderId,
            expectedRevision = request.expectedRevision,
            expectedNormalOccurrenceIndex = request.expectedNormalOccurrenceIndex,
            expectedReminderModifiedAtEpochMillis = request.expectedReminderModifiedAtEpochMillis,
            now = now,
            zoneId = zoneId,
        )

        NotificationAction.TOMORROW_SEEN -> error("Tomorrow action used for a DUE notification")
    }

    private suspend fun processTomorrow(
        request: NotificationActionRequest,
        now: Instant,
        zoneId: ZoneId,
    ): RepositoryActionResult = when (request.action) {
        NotificationAction.TOMORROW_SEEN -> reminderService.acknowledgeTomorrowNotification(
            id = request.reminderId,
            expectedRevision = request.expectedRevision,
            expectedNormalOccurrenceIndex = request.expectedNormalOccurrenceIndex,
            expectedReminderModifiedAtEpochMillis = request.expectedReminderModifiedAtEpochMillis,
            now = now,
            zoneId = zoneId,
        )

        NotificationAction.DONE,
        NotificationAction.DISMISS,
        NotificationAction.POSTPONE_ONE_DAY,
        -> error("DUE action used for a TOMORROW notification")
    }

    private fun cancelObsoleteVisibleNotification(
        request: NotificationActionRequest,
        result: RepositoryActionResult,
    ) {
        when {
            request.notificationKind == NotificationKind.DUE &&
                (result == RepositoryActionResult.APPLIED ||
                    result == RepositoryActionResult.NO_OUTSTANDING_DUE) ->
                reminderScheduler.cancelDue(request.reminderId)

            request.notificationKind == NotificationKind.TOMORROW &&
                (result == RepositoryActionResult.APPLIED ||
                    result == RepositoryActionResult.NO_TOMORROW_PREVIEW ||
                    result == RepositoryActionResult.ALREADY_ACKNOWLEDGED) ->
                reminderScheduler.cancelTomorrow(request.reminderId)
        }
    }
}
