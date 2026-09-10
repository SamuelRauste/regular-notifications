package com.samuel.regularnotifications.notifications

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samuel.regularnotifications.domain.NotificationIdentity
import com.samuel.regularnotifications.domain.NotificationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationActionContractTest {
    @Test
    fun parserReturnsTheTypedRequestOnlyForACompleteMatchingIdentity() {
        val request = NotificationActionContract.parse(validIntent())

        assertEquals(
            NotificationActionRequest(
                reminderId = 42,
                notificationKind = NotificationKind.DUE,
                action = NotificationAction.DONE,
                expectedRevision = 7,
                expectedNormalOccurrenceIndex = 3,
                expectedReminderModifiedAtEpochMillis = 100,
            ),
            request,
        )
    }

    @Test
    fun parserRejectsMissingRevisionAndOccurrenceGuards() {
        val malformed = Intent().apply {
            action = NotificationActionContract.ACTION_NOTIFICATION
            data = Uri.parse(
                NotificationIdentity.actionPendingIntentData(42, NotificationKind.DUE, "done"),
            )
            putExtra(NotificationActionContract.EXTRA_REMINDER_ID, 42L)
            putExtra(NotificationActionContract.EXTRA_NOTIFICATION_KIND, "due")
            putExtra(NotificationActionContract.EXTRA_ACTION, "done")
        }

        assertNull(NotificationActionContract.parse(malformed))
    }

    @Test
    fun parserRejectsWrongKindActionCombinationAndData() {
        val wrongAction = validIntent().apply {
            putExtra(NotificationActionContract.EXTRA_NOTIFICATION_KIND, "tomorrow")
        }
        val wrongData = validIntent().setData(Uri.parse("regular-notifications://wrong"))

        assertNull(NotificationActionContract.parse(wrongAction))
        assertNull(NotificationActionContract.parse(wrongData))
    }

    @Test
    fun parserIgnoresWronglyTypedNumericExtras() {
        val malformed = validIntent().apply {
            putExtra(NotificationActionContract.EXTRA_EXPECTED_REVISION, "7")
        }

        assertNull(NotificationActionContract.parse(malformed))
    }

    private fun validIntent(): Intent = Intent().apply {
        action = NotificationActionContract.ACTION_NOTIFICATION
        data = Uri.parse(
            NotificationIdentity.actionPendingIntentData(42, NotificationKind.DUE, "done"),
        )
        putExtra(NotificationActionContract.EXTRA_REMINDER_ID, 42L)
        putExtra(NotificationActionContract.EXTRA_NOTIFICATION_KIND, "due")
        putExtra(NotificationActionContract.EXTRA_ACTION, "done")
        putExtra(NotificationActionContract.EXTRA_EXPECTED_REVISION, 7L)
        putExtra(NotificationActionContract.EXTRA_EXPECTED_OCCURRENCE_INDEX, 3L)
        putExtra(NotificationActionContract.EXTRA_EXPECTED_REMINDER_MODIFIED_AT, 100L)
    }
}
