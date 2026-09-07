package com.samuel.regularnotifications.domain

enum class ReminderFormField {
    TITLE,
    FIRST_DATE,
    FIRST_TIME,
    INTERVAL_DAYS,
}

data class ReminderValidationResult(
    val errors: Map<ReminderFormField, String>,
) {
    val isValid: Boolean
        get() = errors.isEmpty()
}

object ReminderValidator {
    fun validate(draft: ReminderDraft): ReminderValidationResult {
        val errors = buildMap {
            if (draft.title.isBlank()) {
                put(ReminderFormField.TITLE, "Enter a title.")
            }
            if (draft.firstDate == null) {
                put(ReminderFormField.FIRST_DATE, "Choose a first date.")
            }
            if (draft.firstTime == null) {
                put(ReminderFormField.FIRST_TIME, "Choose a first time.")
            }
            if (draft.intervalDays == null || draft.intervalDays <= 0) {
                put(ReminderFormField.INTERVAL_DAYS, "Enter days greater than zero.")
            }
        }
        return ReminderValidationResult(errors)
    }

    fun toInput(draft: ReminderDraft): ReminderInput? {
        if (!validate(draft).isValid) return null
        return ReminderInput(
            title = draft.title.trim(),
            description = draft.description.trim().takeIf { it.isNotEmpty() },
            enabled = draft.enabled,
            firstOccurrence = draft.firstDate!!.atTime(draft.firstTime!!),
            intervalDays = draft.intervalDays!!,
        )
    }
}
