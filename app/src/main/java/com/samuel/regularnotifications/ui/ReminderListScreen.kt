package com.samuel.regularnotifications.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderListScreen(
    uiState: ReminderListUiState,
    onAddReminder: () -> Unit,
    onEditReminder: (Long) -> Unit,
    onSetEnabled: (Long, Boolean) -> Unit,
    onDeleteReminder: (Long) -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDeletion by remember { mutableStateOf<ReminderListItem?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Reminders") },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddReminder,
            ) { Text("+ Add") }
        },
    ) { innerPadding ->
        when {
            uiState.isLoading -> LoadingReminders(Modifier.padding(innerPadding))
            uiState.reminders.isEmpty() && uiState.errorMessage != null -> ErrorReminders(
                message = uiState.errorMessage,
                onRetry = onRetry,
                modifier = Modifier.padding(innerPadding),
            )

            uiState.reminders.isEmpty() -> EmptyReminders(
                onAddReminder = onAddReminder,
                modifier = Modifier.padding(innerPadding),
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                uiState.errorMessage?.let { message ->
                    item {
                        ErrorBanner(message = message, onDismiss = onDismissError)
                    }
                }
                items(uiState.reminders, key = { it.id }) { reminder ->
                    ReminderCard(
                        reminder = reminder,
                        onEdit = { onEditReminder(reminder.id) },
                        onSetEnabled = { onSetEnabled(reminder.id, it) },
                        onDelete = { pendingDeletion = reminder },
                    )
                }
            }
        }
    }

    pendingDeletion?.let { reminder ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text("Delete reminder?") },
            text = {
                Text("Delete \"${reminder.title}\" and its local history? This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteReminder(reminder.id)
                        pendingDeletion = null
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun LoadingReminders(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
            Text(
                text = "Loading reminders…",
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun EmptyReminders(
    onAddReminder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("No reminders yet.", style = MaterialTheme.typography.headlineSmall)
            Text("Add one to get started.", style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onAddReminder) {
                Text("Add reminder")
            }
        }
    }
}

@Composable
private fun ErrorReminders(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(message, style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onRetry) {
                Text("Try again")
            }
        }
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun ReminderCard(
    reminder: ReminderListItem,
    onEdit: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = reminder.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    reminder.description?.takeIf { it.isNotBlank() }?.let { description ->
                        Text(
                            text = description,
                            modifier = Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (!reminder.enabled) {
                    Text(
                        text = "Paused",
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Text(
                text = "${reminder.recurrence} · Next: ${reminder.nextOccurrence}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = reminder.enabled,
                            role = Role.Switch,
                            onValueChange = onSetEnabled,
                        )
                        .semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (reminder.enabled) "Enabled" else "Disabled")
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = reminder.enabled,
                        onCheckedChange = null,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onEdit) {
                        Text("Edit")
                    }
                    TextButton(onClick = onDelete) {
                        Text("Delete")
                    }
                }
            }
        }
    }
}
