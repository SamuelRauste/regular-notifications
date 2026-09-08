package com.samuel.regularnotifications.notifications

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

data class NotificationPermissionPresentation(
    val isVisible: Boolean = false,
    val message: String = "",
    val actionLabel: String = "",
)

data class NotificationPermissionController(
    val presentation: NotificationPermissionPresentation,
    val onAction: () -> Unit,
)

private const val PREFERENCES_NAME = "notification_preferences"
private const val REQUEST_PRESENTED_KEY = "post_notifications_request_presented"

@Composable
fun rememberNotificationPermissionController(
    onPermissionGranted: () -> Unit = {},
): NotificationPermissionController {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember(context) {
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    var permissionGranted by remember(context) {
        mutableStateOf(hasNotificationPermission(context))
    }
    var requestAlreadyPresented by remember(context) {
        mutableStateOf(preferences.getBoolean(REQUEST_PRESENTED_KEY, false))
    }
    val onPermissionGrantedState = rememberUpdatedState(onPermissionGranted)

    fun refreshPermissionState() {
        val updatedPermissionGranted = hasNotificationPermission(context)
        val wasPermissionGranted = permissionGranted
        permissionGranted = updatedPermissionGranted
        if (notificationPermissionBecameGranted(wasPermissionGranted, updatedPermissionGranted)) {
            onPermissionGrantedState.value()
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        refreshPermissionState()
        requestAlreadyPresented = true
        preferences.edit().putBoolean(REQUEST_PRESENTED_KEY, true).apply()
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissionState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val required = notificationPermissionRequired(Build.VERSION.SDK_INT)
    val shouldShowRationale = activity?.shouldShowRequestPermissionRationale(
        Manifest.permission.POST_NOTIFICATIONS,
    ) == true
    val action = notificationPermissionAction(
        required = required,
        granted = permissionGranted,
        requestAlreadyPresented = requestAlreadyPresented,
        shouldShowRationale = shouldShowRationale,
    )
    val presentation = when (action) {
        NotificationPermissionAction.NONE -> NotificationPermissionPresentation(
            isVisible = false,
            message = "",
            actionLabel = "",
        )

        NotificationPermissionAction.REQUEST -> NotificationPermissionPresentation(
            isVisible = true,
            message = "Allow notifications to receive reminders.",
            actionLabel = "Allow notifications",
        )

        NotificationPermissionAction.OPEN_SETTINGS -> NotificationPermissionPresentation(
            isVisible = true,
            message = "Notifications are off. Enable them in Settings to receive reminders.",
            actionLabel = "Open settings",
        )
    }
    val onAction: () -> Unit = when (action) {
        NotificationPermissionAction.NONE -> ({})
        NotificationPermissionAction.REQUEST -> ({
            requestAlreadyPresented = true
            preferences.edit().putBoolean(REQUEST_PRESENTED_KEY, true).apply()
            activity?.let { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        })

        NotificationPermissionAction.OPEN_SETTINGS -> ({
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        })
    }

    return NotificationPermissionController(
        presentation = presentation,
        onAction = onAction,
    )
}

private fun hasNotificationPermission(context: Context): Boolean {
    if (!notificationPermissionRequired(Build.VERSION.SDK_INT)) return true
    return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
}
