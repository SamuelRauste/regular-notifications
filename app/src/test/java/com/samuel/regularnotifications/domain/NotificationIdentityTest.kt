package com.samuel.regularnotifications.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationIdentityTest {
    @Test
    fun identityIsStableAndSeparatesReminderAndNotificationKinds() {
        val due = NotificationIdentity.notificationId(42, NotificationKind.DUE)
        val sameDue = NotificationIdentity.notificationId(42, NotificationKind.DUE)
        val tomorrow = NotificationIdentity.notificationId(42, NotificationKind.TOMORROW)
        val anotherReminder = NotificationIdentity.notificationId(43, NotificationKind.DUE)

        assertEquals(due, sameDue)
        assertNotEquals(due, tomorrow)
        assertNotEquals(due, anotherReminder)
        assertTrue(due > 0)
        assertEquals(due, NotificationIdentity.alarmRequestCode(42, NotificationKind.DUE))
        assertEquals("regular-notifications://tomorrow/42", NotificationIdentity.pendingIntentData(42, NotificationKind.TOMORROW))
    }
}
