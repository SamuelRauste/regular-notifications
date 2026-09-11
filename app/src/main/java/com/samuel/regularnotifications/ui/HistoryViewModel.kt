package com.samuel.regularnotifications.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.samuel.regularnotifications.data.ReminderRepository
import com.samuel.regularnotifications.data.local.ReminderEntity
import com.samuel.regularnotifications.data.local.ReminderEventEntity
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryUiState(
    val isLoading: Boolean = true,
    val items: List<HistoryItem> = emptyList(),
    val errorMessage: String? = null,
)

data class HistoryItem(
    val id: Long,
    val reminderId: Long,
    val reminderTitle: String,
    val action: HistoryAction,
    val occurredAt: Instant,
    val postponedUntil: Instant?,
)

enum class HistoryAction(val label: String) {
    DONE("Done"),
    DISMISSED("Dismissed"),
    POSTPONED("Postponed"),
    TOMORROW_SEEN("Tomorrow preview seen"),
    OTHER("Activity");

    companion object {
        fun from(storedAction: String): HistoryAction =
            values().firstOrNull { it.name == storedAction } ?: OTHER
    }
}

/** Presents Room-backed reminder events without exposing persistence details to Compose. */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val repository: ReminderRepository,
) : ViewModel() {
    private val retryRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState = _uiState.asStateFlow()

    init {
        observeHistory()
    }

    fun retry() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        retryRequests.tryEmit(Unit)
    }

    private fun observeHistory() {
        viewModelScope.launch {
            retryRequests
                .onStart { emit(Unit) }
                .flatMapLatest {
                    combine(
                        repository.observeAllEvents(),
                        repository.observeReminders(),
                    ) { events, reminders ->
                        events.toHistoryItems(reminders)
                    }
                        .map { items -> HistoryUiState(isLoading = false, items = items) }
                        .onStart { emit(HistoryUiState(isLoading = true)) }
                        .catch { error ->
                            if (error is CancellationException) throw error
                            emit(
                                HistoryUiState(
                                    isLoading = false,
                                    errorMessage = "Could not load history. Try again.",
                                ),
                            )
                        }
                }
                .collect { state -> _uiState.value = state }
        }
    }

    companion object {
        fun factory(repository: ReminderRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { HistoryViewModel(repository) }
        }
    }
}

private fun List<ReminderEventEntity>.toHistoryItems(
    reminders: List<ReminderEntity>,
): List<HistoryItem> {
    val titlesById = reminders.associate { it.id to it.title }
    return mapNotNull { event ->
        val title = titlesById[event.reminderId] ?: return@mapNotNull null
        HistoryItem(
            id = event.id,
            reminderId = event.reminderId,
            reminderTitle = title,
            action = HistoryAction.from(event.action),
            occurredAt = Instant.ofEpochMilli(event.occurredAtEpochMillis),
            postponedUntil = event.postponedUntilEpochMillis?.let(Instant::ofEpochMilli),
        )
    }
}
