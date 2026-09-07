package com.samuel.regularnotifications.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderUiModelsTest {
    @Test
    fun everyDaysLabelUsesClearSingularAndPluralText() {
        assertEquals("Every 1 day", everyDaysLabel(1))
        assertEquals("Every 2 days", everyDaysLabel(2))
        assertEquals("Every 7 days", everyDaysLabel(7))
    }

    @Test
    fun enabledReminderShowsItsNextOccurrence() {
        val item = ReminderListItem(
            id = 1,
            title = "Take out trash",
            description = null,
            enabled = true,
            intervalDays = 7,
            nextOccurrence = "Tue 22 Sep, 09:00",
        )

        assertEquals("Every 7 days · Next: Tue 22 Sep, 09:00", item.scheduleSummary)
    }

    @Test
    fun disabledReminderShowsPausedInsteadOfItsCachedNextOccurrence() {
        val item = ReminderListItem(
            id = 1,
            title = "Take out trash",
            description = null,
            enabled = false,
            intervalDays = 7,
            nextOccurrence = "Tue 10 Sep, 09:00",
        )

        assertEquals("Every 7 days · Paused", item.scheduleSummary)
    }
}
