package com.samuel.regularnotifications.ui

import com.samuel.regularnotifications.data.local.ReminderEntity
import com.samuel.regularnotifications.domain.ReminderFormField
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ReminderListItem(
    val id: Long,
    val title: String,
    val description: String?,
    val enabled: Boolean,
    val intervalDays: Int,
    val nextOccurrence: String,
) {
    val recurrence: String
        get() = everyDaysLabel(intervalDays)

    val scheduleSummary: String
        get() = if (enabled) {
            "$recurrence · Next: $nextOccurrence"
        } else {
            "$recurrence · Paused"
        }
}

data class ReminderListUiState(
    val isLoading: Boolean = true,
    val reminders: List<ReminderListItem> = emptyList(),
    val errorMessage: String? = null,
)

data class ReminderEditorUiState(
    val isLoading: Boolean,
    val title: String,
    val description: String,
    val enabled: Boolean,
    val firstDate: LocalDate,
    val firstTime: LocalTime,
    val intervalDays: String,
    val fieldErrors: Map<ReminderFormField, String> = emptyMap(),
    val errorMessage: String? = null,
    val isSaving: Boolean = false,
)

sealed interface ReminderEditorEvent {
    data object Saved : ReminderEditorEvent
}

fun everyDaysLabel(intervalDays: Int): String =
    "Every $intervalDays ${if (intervalDays == 1) "day" else "days"}"

internal fun ReminderEntity.toReminderListItem(zoneId: ZoneId = ZoneId.systemDefault()): ReminderListItem =
    ReminderListItem(
        id = id,
        title = title,
        description = description,
        enabled = enabled,
        intervalDays = intervalDays,
        nextOccurrence = Instant.ofEpochMilli(nextNormalOccurrenceEpochMillis)
            .atZone(zoneId)
            .format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault())),
    )
