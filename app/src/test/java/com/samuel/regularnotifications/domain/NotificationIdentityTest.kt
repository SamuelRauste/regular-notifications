package com.samuel.regularnotifications.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationIdentityTest {
    @Test
    fun identityIsStableAndSeparatesReminderAndNotificationKinds() {
        val due = NotificationIdentity.target(42, NotificationKind.DUE)
        val sameDue = NotificationIdentity.target(42, NotificationKind.DUE)
        val tomorrow = NotificationIdentity.target(42, NotificationKind.TOMORROW)
        val anotherReminder = NotificationIdentity.target(43, NotificationKind.DUE)

        assertEquals(due, sameDue)
        assertNotEquals(due, tomorrow)
        assertNotEquals(due, anotherReminder)
        assertTrue(due.notificationId > 0)
        assertEquals(due.notificationId, anotherReminder.notificationId)
        assertNotEquals(due.tag, anotherReminder.tag)
        assertEquals(
            NotificationIdentity.alarmRequestCode(42, NotificationKind.DUE),
            NotificationIdentity.alarmRequestCode(42, NotificationKind.DUE),
        )
        assertEquals("regular-notifications://tomorrow/42", NotificationIdentity.pendingIntentData(42, NotificationKind.TOMORROW))
        assertNotEquals(
            NotificationIdentity.actionPendingIntentData(42, NotificationKind.DUE, "done"),
            NotificationIdentity.actionPendingIntentData(42, NotificationKind.DUE, "dismiss"),
        )
    }
}
