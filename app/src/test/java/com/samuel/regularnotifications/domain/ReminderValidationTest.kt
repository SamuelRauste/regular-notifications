package com.samuel.regularnotifications.domain

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderValidationTest {
    @Test
    fun blankAndIncompleteDraftReportsUnderstandableFieldErrors() {
        val result = ReminderValidator.validate(ReminderDraft())

        assertFalse(result.isValid)
        assertEquals(
            setOf(
                ReminderFormField.TITLE,
                ReminderFormField.FIRST_DATE,
                ReminderFormField.FIRST_TIME,
                ReminderFormField.INTERVAL_AMOUNT,
                ReminderFormField.INTERVAL_UNIT,
            ),
            result.errors.keys,
        )
    }

    @Test
    fun zeroAndNegativeIntervalsAreRejected() {
        val base = ReminderDraft(
            title = "Take medicine",
            firstDate = LocalDate.of(2026, 1, 1),
            firstTime = LocalTime.of(9, 0),
            intervalUnit = IntervalUnit.HOURS,
        )

        assertFalse(ReminderValidator.validate(base.copy(intervalAmount = 0)).isValid)
        assertFalse(ReminderValidator.validate(base.copy(intervalAmount = -1)).isValid)
    }

    @Test
    fun validDraftBecomesTrimmedInput() {
        val input = ReminderValidator.toInput(
            ReminderDraft(
                title = "  Take medicine  ",
                description = "  With water  ",
                enabled = false,
                firstDate = LocalDate.of(2026, 1, 1),
                firstTime = LocalTime.of(9, 15),
                intervalAmount = 2,
                intervalUnit = IntervalUnit.WEEKS,
            ),
        )

        assertTrue(input != null)
        assertEquals("Take medicine", input?.title)
        assertEquals("With water", input?.description)
        assertFalse(input!!.enabled)
        assertEquals(LocalDate.of(2026, 1, 1).atTime(9, 15), input.firstOccurrence)
        assertEquals(2, input.intervalAmount)
        assertEquals(IntervalUnit.WEEKS, input.intervalUnit)
    }

    @Test
    fun invalidDraftDoesNotBecomeInput() {
        assertNull(ReminderValidator.toInput(ReminderDraft(title = "Title")))
    }
}
