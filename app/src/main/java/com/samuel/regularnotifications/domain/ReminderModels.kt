package com.samuel.regularnotifications.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

data class ReminderDraft(
    val title: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val firstDate: LocalDate? = null,
    val firstTime: LocalTime? = null,
    val intervalAmount: Int? = null,
    val intervalUnit: IntervalUnit? = null,
)

data class ReminderInput(
    val title: String,
    val description: String?,
    val enabled: Boolean,
    val firstOccurrence: LocalDateTime,
    val intervalAmount: Int,
    val intervalUnit: IntervalUnit,
)

data class ReminderDefinition(
    val id: Long,
    val title: String,
    val description: String?,
    val enabled: Boolean,
    val anchorLocalDate: LocalDate,
    val anchorLocalTime: LocalTime,
    val durationAnchor: Instant,
    val intervalAmount: Int,
    val intervalUnit: IntervalUnit,
)

fun ReminderInput.toDefinition(id: Long, zoneId: ZoneId): ReminderDefinition =
    ReminderDefinition(
        id = id,
        title = title.trim(),
        description = description?.trim()?.takeIf { it.isNotEmpty() },
        enabled = enabled,
        anchorLocalDate = firstOccurrence.toLocalDate(),
        anchorLocalTime = firstOccurrence.toLocalTime(),
        durationAnchor = firstOccurrence.atZone(zoneId).toInstant(),
        intervalAmount = intervalAmount,
        intervalUnit = intervalUnit,
    )

data class NormalOccurrence(
    val index: Long,
    val scheduledAt: Instant,
    val zoneId: ZoneId,
) {
    val localDateTime: LocalDateTime
        get() = scheduledAt.atZone(zoneId).toLocalDateTime()
}

data class RecurrenceWindow(
    val latestDue: NormalOccurrence?,
    val firstFuture: NormalOccurrence,
)

data class OutstandingDueState(
    val normalOccurrenceIndex: Long,
    val normalOccurrenceEpochMillis: Long,
    val dueAtEpochMillis: Long,
    val postponedUntilEpochMillis: Long?,
    val postponementCount: Int,
    val revision: Long,
)

data class TomorrowPreviewState(
    val normalOccurrenceIndex: Long,
    val occurrenceEpochMillis: Long,
    val previewEpochMillis: Long,
    val zoneId: String,
    val acknowledged: Boolean,
    val revision: Long,
)

/**
 * A lifecycle derived from the persisted preview timestamps and acknowledgement.
 * It is not stored separately because the existing row already represents it.
 */
enum class TomorrowPreviewLifecycle {
    SCHEDULED,
    CURRENT,
    ACKNOWLEDGED,
    OBSOLETE,
}

data class ReminderScheduleState(
    val nextNormal: NormalOccurrence,
    val outstandingDue: OutstandingDueState?,
    val tomorrowPreview: TomorrowPreviewState?,
    val lastResolvedNormalOccurrenceIndex: Long? = null,
)

enum class ReminderEventType {
    DONE,
    DISMISSED,
    POSTPONED,
    TOMORROW_SEEN,
}
