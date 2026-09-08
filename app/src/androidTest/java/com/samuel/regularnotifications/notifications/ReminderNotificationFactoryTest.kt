package com.samuel.regularnotifications.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samuel.regularnotifications.domain.NotificationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderNotificationFactoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val factory = ReminderNotificationFactory(context)

    @Test
    fun dueNotificationHasTheThreeDueActionsAndDescription() {
        val notification = factory.buildDueNotification(input(intervalDays = 1))

        assertEquals("Take out trash", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Bins by the door", notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(listOf("Done", "Dismiss", "+1 day"), notification.actionTitles())
        assertFalse(notification.actionTitles().contains("Seen"))
    }

    @Test
    fun tomorrowNotificationHasOnlySeenAndUsesTomorrowTitle() {
        val notification = factory.buildTomorrowNotification(input(intervalDays = 7))

        assertNotNull(notification)
        assertEquals(
            "Tomorrow: Take out trash",
            notification!!.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals(listOf("Seen"), notification.actionTitles())
        assertFalse(notification.actionTitles().contains("Done"))
        assertFalse(notification.actionTitles().contains("Dismiss"))
        assertFalse(notification.actionTitles().contains("+1 day"))
    }

    @Test
    fun everyOneDayReminderCannotBuildTomorrowNotification() {
        assertEquals(null, factory.buildTomorrowNotification(input(intervalDays = 1)))
    }

    @Test
    fun notificationChannelCreationIsIdempotent() {
        ReminderNotificationChannels.ensureCreated(context)
        ReminderNotificationChannels.ensureCreated(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = manager.getNotificationChannel(
                ReminderNotificationChannels.REMINDERS_CHANNEL_ID,
            )
            assertNotNull(channel)
            assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel!!.importance)
        }
    }

    @Test
    fun actionPendingIntentsAreStablePerActionAndCarryDistinctIdentities() {
        val done = NotificationActionContract.createPendingIntent(
            context,
            reminderId = 42,
            notificationKind = NotificationKind.DUE,
            action = NotificationAction.DONE,
            expectedRevision = 1,
        )
        val sameDoneWithNewRevision = NotificationActionContract.createPendingIntent(
            context,
            reminderId = 42,
            notificationKind = NotificationKind.DUE,
            action = NotificationAction.DONE,
            expectedRevision = 2,
        )
        val dismiss = NotificationActionContract.createPendingIntent(
            context,
            reminderId = 42,
            notificationKind = NotificationKind.DUE,
            action = NotificationAction.DISMISS,
            expectedRevision = 1,
        )
        val tomorrowSeen = NotificationActionContract.createPendingIntent(
            context,
            reminderId = 42,
            notificationKind = NotificationKind.TOMORROW,
            action = NotificationAction.TOMORROW_SEEN,
            expectedRevision = 1,
        )

        assertEquals(done, sameDoneWithNewRevision)
        assertNotEquals(done, dismiss)
        assertNotEquals(done, tomorrowSeen)
    }

    private fun input(intervalDays: Int) = ReminderNotificationInput(
        reminderId = 42,
        title = "Take out trash",
        description = "Bins by the door",
        intervalDays = intervalDays,
        expectedRevision = 3,
    )

    private fun Notification.actionTitles(): List<String> =
        actions.orEmpty().map { it.title.toString() }
}
