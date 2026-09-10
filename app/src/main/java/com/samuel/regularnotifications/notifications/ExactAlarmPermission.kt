package com.samuel.regularnotifications.notifications

import android.content.ActivityNotFoundException
import android.util.Log
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
import com.samuel.regularnotifications.scheduling.AlarmSchedulePolicy
import com.samuel.regularnotifications.scheduling.ExactAlarmCapability
import com.samuel.regularnotifications.scheduling.createExactAlarmSettingsIntent

data class ExactAlarmPermissionPresentation(
    val isVisible: Boolean = false,
    val message: String = "",
    val actionLabel: String = "",
)

data class ExactAlarmPermissionController(
    val presentation: ExactAlarmPermissionPresentation,
    val onAction: () -> Unit,
)

@Composable
fun rememberExactAlarmPermissionController(
    exactAlarmCapability: ExactAlarmCapability,
    onCapabilityChanged: () -> Unit = {},
): ExactAlarmPermissionController {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var accessAvailable by remember(exactAlarmCapability) {
        mutableStateOf(exactAlarmCapability.canScheduleExactAlarms())
    }
    val onCapabilityChangedState = rememberUpdatedState(onCapabilityChanged)

    fun refreshAccessState() {
        val updatedAccessAvailable = exactAlarmCapability.canScheduleExactAlarms()
        val changed = AlarmSchedulePolicy.shouldReconcileAfterCapabilityChange(
            wasAvailable = accessAvailable,
            isAvailable = updatedAccessAvailable,
        )
        accessAvailable = updatedAccessAvailable
        if (changed) onCapabilityChangedState.value()
    }

    DisposableEffect(lifecycleOwner, exactAlarmCapability) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshAccessState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val presentation = if (accessAvailable) {
        ExactAlarmPermissionPresentation()
    } else {
        ExactAlarmPermissionPresentation(
            isVisible = true,
            message = "Allow alarms & reminders for notifications to arrive on time.",
            actionLabel = "Allow alarms & reminders",
        )
    }

    val onAction: () -> Unit = if (accessAvailable) {
        {}
    } else {
        {
            createExactAlarmSettingsIntent(context)?.let { intent ->
                try {
                    context.startActivity(intent)
                } catch (error: ActivityNotFoundException) {
                    Log.w(TAG, "Exact-alarm Settings screen is unavailable", error)
                }
            }
        }
    }

    return ExactAlarmPermissionController(
        presentation = presentation,
        onAction = onAction,
    )
}

private const val TAG = "ReminderNotifications"
