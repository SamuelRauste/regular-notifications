package com.samuel.regularnotifications.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPermissionPolicyTest {
    @Test
    fun notificationPermissionIsOnlyRequiredFromAndroid13() {
        assertEquals(false, notificationPermissionRequired(32))
        assertEquals(true, notificationPermissionRequired(33))
    }

    @Test
    fun reconciliationIsRequestedOnlyOnDeniedToGrantedTransition() {
        assertEquals(true, notificationPermissionBecameGranted(wasGranted = false, isGranted = true))
        assertEquals(false, notificationPermissionBecameGranted(wasGranted = true, isGranted = true))
        assertEquals(false, notificationPermissionBecameGranted(wasGranted = false, isGranted = false))
    }

    @Test
    fun initialAndroid13StateOffersAUserInitiatedRequest() {
        assertEquals(
            NotificationPermissionAction.REQUEST,
            notificationPermissionAction(
                required = true,
                granted = false,
                requestAlreadyPresented = false,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun deniedPermissionCanOfferOneMoreRationaleRequest() {
        assertEquals(
            NotificationPermissionAction.REQUEST,
            notificationPermissionAction(
                required = true,
                granted = false,
                requestAlreadyPresented = true,
                shouldShowRationale = true,
            ),
        )
    }

    @Test
    fun previouslyRejectedPermissionFallsBackToSettings() {
        assertEquals(
            NotificationPermissionAction.OPEN_SETTINGS,
            notificationPermissionAction(
                required = true,
                granted = false,
                requestAlreadyPresented = true,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun grantedOrUnsupportedPermissionNeedsNoAction() {
        assertEquals(
            NotificationPermissionAction.NONE,
            notificationPermissionAction(
                required = true,
                granted = true,
                requestAlreadyPresented = true,
                shouldShowRationale = false,
            ),
        )
        assertEquals(
            NotificationPermissionAction.NONE,
            notificationPermissionAction(
                required = false,
                granted = false,
                requestAlreadyPresented = false,
                shouldShowRationale = false,
            ),
        )
    }
}
