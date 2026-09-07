package com.samuel.regularnotifications.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samuel.regularnotifications.domain.ReminderFormField
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderEditorScreen(
    isNewReminder: Boolean,
    viewModel: ReminderEditorViewModel,
    onFinished: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event == ReminderEditorEvent.Saved) onFinished()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (isNewReminder) "New reminder" else "Edit reminder") },
                navigationIcon = {
                    TextButton(onClick = onCancel) {
                        Text("Cancel")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (uiState.isLoading) {
            EditorLoading(modifier = Modifier.padding(innerPadding))
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                uiState.errorMessage?.let { message ->
                    EditorError(message = message, onDismiss = viewModel::clearError)
                }

                OutlinedTextField(
                    value = uiState.title,
                    onValueChange = viewModel::updateTitle,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Title") },
                    supportingText = uiState.fieldErrors[ReminderFormField.TITLE]?.let { error ->
                        { Text(error) }
                    },
                    isError = ReminderFormField.TITLE in uiState.fieldErrors,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )

                OutlinedTextField(
                    value = uiState.description,
                    onValueChange = viewModel::updateDescription,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Description (optional)") },
                    minLines = 2,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("First occurrence", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { showDatePicker = true },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .semantics {
                                    contentDescription = "First date: ${uiState.firstDate.displayDate()}"
                                },
                        ) {
                            Text(uiState.firstDate.displayDate())
                        }
                        OutlinedButton(
                            onClick = { showTimePicker = true },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .semantics {
                                    contentDescription = "First time: ${uiState.firstTime.displayTime()}"
                                },
                        ) {
                            Text(uiState.firstTime.displayTime())
                        }
                    }
                    uiState.fieldErrors[ReminderFormField.FIRST_DATE]?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error)
                    }
                    uiState.fieldErrors[ReminderFormField.FIRST_TIME]?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error)
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Repeat", style = MaterialTheme.typography.titleSmall)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Every")
                        OutlinedTextField(
                            value = uiState.intervalDays,
                            onValueChange = viewModel::updateIntervalDays,
                            modifier = Modifier
                                .width(96.dp)
                                .semantics { contentDescription = "Repeat interval in days" },
                            placeholder = { Text("1") },
                            isError = ReminderFormField.INTERVAL_DAYS in uiState.fieldErrors,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                        )
                        Text("days")
                    }
                    uiState.fieldErrors[ReminderFormField.INTERVAL_DAYS]?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = uiState.enabled,
                            role = Role.Switch,
                            onValueChange = viewModel::updateEnabled,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enabled", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (uiState.enabled) "This reminder is active." else "This reminder is paused.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Switch(checked = uiState.enabled, onCheckedChange = null)
                }

                Spacer(Modifier.weight(1f, fill = false))
                Button(
                    onClick = viewModel::save,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    enabled = !uiState.isSaving,
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Save reminder")
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        SystemDatePicker(
            initialDate = uiState.firstDate,
            onDateSelected = viewModel::updateFirstDate,
            onDismiss = { showDatePicker = false },
        )
    }
    if (showTimePicker) {
        SystemTimePicker(
            initialTime = uiState.firstTime,
            onTimeSelected = viewModel::updateFirstTime,
            onDismiss = { showTimePicker = false },
        )
    }
}

@Composable
private fun EditorLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
            Text("Loading reminder…", modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun EditorError(
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
private fun SystemDatePicker(
    initialDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val currentOnDateSelected by rememberUpdatedState(onDateSelected)
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    DisposableEffect(context) {
        val dialog = DatePickerDialog(
            context,
            { _, year, month, day ->
                currentOnDateSelected(LocalDate.of(year, month + 1, day))
                currentOnDismiss()
            },
            initialDate.year,
            initialDate.monthValue - 1,
            initialDate.dayOfMonth,
        ).apply {
            setOnDismissListener { currentOnDismiss() }
            show()
        }
        onDispose {
            dialog.setOnDismissListener(null)
            dialog.dismiss()
        }
    }
}

@Composable
private fun SystemTimePicker(
    initialTime: LocalTime,
    onTimeSelected: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val currentOnTimeSelected by rememberUpdatedState(onTimeSelected)
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    DisposableEffect(context) {
        val dialog = TimePickerDialog(
            context,
            { _, hour, minute ->
                currentOnTimeSelected(LocalTime.of(hour, minute))
                currentOnDismiss()
            },
            initialTime.hour,
            initialTime.minute,
            android.text.format.DateFormat.is24HourFormat(context),
        ).apply {
            setOnDismissListener { currentOnDismiss() }
            show()
        }
        onDispose {
            dialog.setOnDismissListener(null)
            dialog.dismiss()
        }
    }
}

private fun LocalDate.displayDate(): String =
    format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

private fun LocalTime.displayTime(): String =
    format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
