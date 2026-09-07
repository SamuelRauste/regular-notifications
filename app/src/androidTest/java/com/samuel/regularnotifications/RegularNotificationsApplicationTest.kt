package com.samuel.regularnotifications

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RegularNotificationsApplicationTest {
    @Test
    fun applicationContainerOwnsOneDatabaseAndRepositoryPerProcess() {
        val application = ApplicationProvider.getApplicationContext<RegularNotificationsApplication>()

        assertSame(application.appContainer, application.appContainer)
        assertSame(application.appContainer.database, application.appContainer.database)
        assertSame(application.appContainer.reminderRepository, application.appContainer.reminderRepository)
    }
}
