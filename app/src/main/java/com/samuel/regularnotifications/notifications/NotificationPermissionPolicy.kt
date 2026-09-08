package com.samuel.regularnotifications.notifications

enum class NotificationPermissionAction {
    NONE,
    REQUEST,
    OPEN_SETTINGS,
}

fun notificationPermissionRequired(sdkInt: Int): Boolean = sdkInt >= 33

fun notificationPermissionAction(
    required: Boolean,
    granted: Boolean,
    requestAlreadyPresented: Boolean,
    shouldShowRationale: Boolean,
): NotificationPermissionAction = when {
    !required || granted -> NotificationPermissionAction.NONE
    !requestAlreadyPresented || shouldShowRationale -> NotificationPermissionAction.REQUEST
    else -> NotificationPermissionAction.OPEN_SETTINGS
}
