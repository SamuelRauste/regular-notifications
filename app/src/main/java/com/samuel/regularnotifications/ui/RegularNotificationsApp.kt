package com.samuel.regularnotifications.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.samuel.regularnotifications.AppContainer
import com.samuel.regularnotifications.notifications.rememberNotificationPermissionController

private const val ReminderListRoute = "reminders"
private const val ReminderEditorRoute = "editor/{reminderId}"

@Composable
fun RegularNotificationsApp(
    appContainer: AppContainer,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val notificationPermission = rememberNotificationPermissionController()

    NavHost(
        navController = navController,
        startDestination = ReminderListRoute,
        modifier = modifier,
    ) {
        composable(ReminderListRoute) {
            val viewModel: ReminderListViewModel = viewModel(
                factory = ReminderListViewModel.factory(appContainer.reminderRepository),
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            ReminderListScreen(
                uiState = uiState,
                onAddReminder = {
                    navController.navigate(editorRoute(reminderId = null))
                },
                onEditReminder = { reminderId ->
                    navController.navigate(editorRoute(reminderId))
                },
                onSetEnabled = viewModel::setEnabled,
                onDeleteReminder = viewModel::delete,
                onRetry = viewModel::retry,
                onDismissError = viewModel::clearError,
                notificationPermission = notificationPermission.presentation,
                onNotificationPermissionAction = notificationPermission.onAction,
            )
        }
        composable(ReminderEditorRoute) { backStackEntry ->
            val reminderId = backStackEntry.arguments
                ?.getString("reminderId")
                ?.takeUnless { it == "new" }
                ?.toLongOrNull()
            val viewModel: ReminderEditorViewModel = viewModel(
                factory = ReminderEditorViewModel.factory(appContainer.reminderRepository, reminderId),
            )

            ReminderEditorScreen(
                isNewReminder = reminderId == null,
                viewModel = viewModel,
                onFinished = { navController.popBackStack() },
                onCancel = { navController.popBackStack() },
            )
        }
    }
}

private fun editorRoute(reminderId: Long?): String = "editor/${reminderId ?: "new"}"
