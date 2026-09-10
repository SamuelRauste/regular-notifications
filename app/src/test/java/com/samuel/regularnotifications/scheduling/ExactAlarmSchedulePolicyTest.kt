package com.samuel.regularnotifications.scheduling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactAlarmSchedulePolicyTest {
    @Test
    fun exactCapabilitySelectsExactAlarmMode() {
        assertEquals(
            AlarmScheduleMode.EXACT,
            AlarmSchedulePolicy.select(canScheduleExactAlarms = true),
        )
    }

    @Test
    fun unavailableExactCapabilitySelectsInexactFallbackMode() {
        assertEquals(
            AlarmScheduleMode.INEXACT,
            AlarmSchedulePolicy.select(canScheduleExactAlarms = false),
        )
    }

    @Test
    fun capabilityIsRequiredOnlyFromAndroid12() {
        assertFalse(exactAlarmAccessRequired(30))
        assertTrue(exactAlarmAccessRequired(31))
    }

    @Test
    fun eitherCapabilityTransitionRequestsOneReconciliation() {
        assertTrue(
            AlarmSchedulePolicy.shouldReconcileAfterCapabilityChange(
                wasAvailable = false,
                isAvailable = true,
            ),
        )
        assertTrue(
            AlarmSchedulePolicy.shouldReconcileAfterCapabilityChange(
                wasAvailable = true,
                isAvailable = false,
            ),
        )
        assertFalse(
            AlarmSchedulePolicy.shouldReconcileAfterCapabilityChange(
                wasAvailable = true,
                isAvailable = true,
            ),
        )
    }
}
