package com.samuel.regularnotifications.domain

enum class ReminderFormField {
    TITLE,
    FIRST_DATE,
    FIRST_TIME,
    INTERVAL_AMOUNT,
    INTERVAL_UNIT,
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
            if (draft.intervalAmount == null || draft.intervalAmount <= 0) {
                put(ReminderFormField.INTERVAL_AMOUNT, "Enter an interval greater than zero.")
            }
            if (draft.intervalUnit == null) {
                put(ReminderFormField.INTERVAL_UNIT, "Choose an interval unit.")
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
            intervalAmount = draft.intervalAmount!!,
            intervalUnit = draft.intervalUnit!!,
        )
    }
}
