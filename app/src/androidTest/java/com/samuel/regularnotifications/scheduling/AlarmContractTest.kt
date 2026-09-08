package com.samuel.regularnotifications.scheduling

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samuel.regularnotifications.domain.NotificationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmContractTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun sameReminderAndKindReusePendingIntentIdentityWhileRevisionUpdates() {
        val first = AlarmContract.createPendingIntent(
            context = context,
            reminderId = 42,
            kind = NotificationKind.DUE,
            expectedRevision = 1,
        )
        val updated = AlarmContract.createPendingIntent(
            context = context,
            reminderId = 42,
            kind = NotificationKind.DUE,
            expectedRevision = 2,
        )
        val tomorrow = AlarmContract.createPendingIntent(
            context = context,
            reminderId = 42,
            kind = NotificationKind.TOMORROW,
            expectedRevision = 1,
        )
        val anotherReminder = AlarmContract.createPendingIntent(
            context = context,
            reminderId = 43,
            kind = NotificationKind.DUE,
            expectedRevision = 1,
        )

        assertEquals(first, updated)
        assertNotEquals(first, tomorrow)
        assertNotEquals(first, anotherReminder)
        assertNotNull(first)
    }

    @Test
    fun parserRequiresTheStrictAlarmActionAndFullDataIdentity() {
        val valid = Intent().apply {
            action = AlarmContract.ACTION_DELIVER
            data = Uri.parse("regular-notifications://alarm/due/42")
            putExtra(AlarmContract.EXTRA_REMINDER_ID, 42L)
            putExtra(AlarmContract.EXTRA_NOTIFICATION_KIND, NotificationKind.DUE.uriSegment)
            putExtra(AlarmContract.EXTRA_EXPECTED_REVISION, 7L)
        }

        assertEquals(
            AlarmDeliveryRequest(42, NotificationKind.DUE, 7),
            AlarmContract.parse(valid),
        )
        assertEquals(
            null,
            AlarmContract.parse(valid.setData(Uri.parse("regular-notifications://alarm/due/43"))),
        )
    }
}
