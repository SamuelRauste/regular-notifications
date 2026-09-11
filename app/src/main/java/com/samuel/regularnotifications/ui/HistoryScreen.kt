package com.samuel.regularnotifications.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    uiState: HistoryUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier.semantics {
                            contentDescription = "Back"
                        },
                    ) {
                        Text("Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                uiState.isLoading -> HistoryLoading()
                uiState.errorMessage != null -> HistoryError(
                    message = uiState.errorMessage,
                    onRetry = onRetry,
                )

                uiState.items.isEmpty() -> HistoryEmpty()
                else -> HistoryList(items = uiState.items)
            }
        }
    }
}

@Composable
private fun HistoryList(
    items: List<HistoryItem>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.id }) { item ->
            HistoryCard(item)
        }
    }
}

@Composable
private fun HistoryCard(
    item: HistoryItem,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "${item.reminderTitle}, ${item.action.label}"
            },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = item.reminderTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = item.action.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (item.postponedUntil != null) {
                Text(
                    text = "Action time: ${formatHistoryInstant(context, item.occurredAt)}",
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = "New reminder time: ${formatHistoryInstant(context, item.postponedUntil)}",
                    modifier = Modifier.padding(top = 2.dp),
                )
            } else {
                Text(
                    text = formatHistoryInstant(context, item.occurredAt),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun HistoryLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
            Text(
                text = "Loading history…",
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun HistoryEmpty(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No history yet.", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Done, Dismiss, +1 day, and Seen actions will appear here.",
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun HistoryError(
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message)
            Button(
                onClick = onRetry,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text("Try again")
            }
        }
    }
}

internal fun formatHistoryInstant(
    context: Context,
    instant: Instant,
): String {
    val date = Date.from(instant)
    val dateFormat = DateFormat.getDateFormat(context)
    val timeFormat = DateFormat.getTimeFormat(context)
    return "${dateFormat.format(date)}, ${timeFormat.format(date)}"
}
