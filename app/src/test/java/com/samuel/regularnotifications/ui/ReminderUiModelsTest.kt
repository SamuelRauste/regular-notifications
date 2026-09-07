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
}
