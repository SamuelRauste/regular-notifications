package com.samuel.regularnotifications.scheduling

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExactAlarmPermissionIntentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun settingsIntentUsesTheExactAlarmSpecialAccessAndThisPackage() {
        val intent = createExactAlarmSettingsIntent(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertNotNull(intent)
            assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, intent!!.action)
            assertEquals("package:${context.packageName}", intent.dataString)
        } else {
            assertNull(intent)
        }
    }
}
