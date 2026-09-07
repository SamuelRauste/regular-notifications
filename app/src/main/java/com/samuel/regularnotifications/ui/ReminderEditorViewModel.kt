package com.samuel.regularnotifications.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.samuel.regularnotifications.data.ReminderRepository
import com.samuel.regularnotifications.data.RepositoryActionResult
import com.samuel.regularnotifications.domain.ReminderDraft
import com.samuel.regularnotifications.domain.ReminderFormField
import com.samuel.regularnotifications.domain.ReminderValidator
import java.time.Clock
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ReminderEditorViewModel(
    private val repository: ReminderRepository,
    private val reminderId: Long?,
    clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val defaultFirstOccurrence = LocalDateTime.now(clock)
        .plusHours(1)
        .withMinute(0)
        .withSecond(0)
        .withNano(0)

    private val _uiState = MutableStateFlow(
        ReminderEditorUiState(
            isLoading = reminderId != null,
            title = "",
            description = "",
            enabled = true,
            firstDate = defaultFirstOccurrence.toLocalDate(),
            firstTime = defaultFirstOccurrence.toLocalTime(),
            intervalDays = "1",
        ),
    )
    val uiState = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ReminderEditorEvent>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    init {
        reminderId?.let(::loadReminder)
    }

    fun updateTitle(title: String) = updateForm(ReminderFormField.TITLE) { it.copy(title = title) }

    fun updateDescription(description: String) = updateForm { it.copy(description = description) }

    fun updateFirstDate(firstDate: java.time.LocalDate) =
        updateForm(ReminderFormField.FIRST_DATE) { it.copy(firstDate = firstDate) }

    fun updateFirstTime(firstTime: java.time.LocalTime) =
        updateForm(ReminderFormField.FIRST_TIME) { it.copy(firstTime = firstTime) }

    fun updateIntervalDays(intervalDays: String) =
        updateForm(ReminderFormField.INTERVAL_DAYS) { it.copy(intervalDays = intervalDays) }

    fun updateEnabled(enabled: Boolean) = updateForm { it.copy(enabled = enabled) }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun save() {
        val state = _uiState.value
        if (state.isLoading || state.isSaving) return

        val draft = state.toDraft()
        val validation = ReminderValidator.validate(draft)
        if (!validation.isValid) {
            _uiState.update { it.copy(fieldErrors = validation.errors, errorMessage = null) }
            return
        }
        val input = checkNotNull(ReminderValidator.toInput(draft))

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                if (reminderId == null) {
                    repository.createReminder(input)
                } else {
                    val result = repository.updateReminder(reminderId, input)
                    if (result != RepositoryActionResult.APPLIED) {
                        showSaveFailure(result)
                        return@launch
                    }
                }
                _uiState.update { it.copy(isSaving = false) }
                _events.emit(ReminderEditorEvent.Saved)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Could not save this reminder. Try again.",
                    )
                }
            }
        }
    }

    private fun loadReminder(id: Long) {
        viewModelScope.launch {
            try {
                val reminder = repository.observeReminder(id).first()
                if (reminder == null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "This reminder no longer exists.",
                        )
                    }
                    return@launch
                }
                _uiState.value = ReminderEditorUiState(
                    isLoading = false,
                    title = reminder.title,
                    description = reminder.description.orEmpty(),
                    enabled = reminder.enabled,
                    firstDate = java.time.LocalDate.parse(reminder.anchorLocalDate),
                    firstTime = java.time.LocalTime.parse(reminder.anchorLocalTime),
                    intervalDays = reminder.intervalDays.toString(),
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Could not load this reminder. Try again.",
                    )
                }
            }
        }
    }

    private fun updateForm(
        field: ReminderFormField? = null,
        transform: (ReminderEditorUiState) -> ReminderEditorUiState,
    ) {
        _uiState.update { current ->
            transform(current).copy(
                fieldErrors = if (field == null) current.fieldErrors else current.fieldErrors - field,
                errorMessage = null,
            )
        }
    }

    private fun ReminderEditorUiState.toDraft(): ReminderDraft = ReminderDraft(
        title = title,
        description = description,
        enabled = enabled,
        firstDate = firstDate,
        firstTime = firstTime,
        intervalDays = intervalDays.trim().toIntOrNull(),
    )

    private fun showSaveFailure(result: RepositoryActionResult) {
        _uiState.update {
            it.copy(
                isSaving = false,
                errorMessage = if (result == RepositoryActionResult.NOT_FOUND) {
                    "This reminder no longer exists."
                } else {
                    "Could not save this reminder. Try again."
                },
            )
        }
    }

    companion object {
        fun factory(
            repository: ReminderRepository,
            reminderId: Long?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ReminderEditorViewModel(repository, reminderId) }
        }
    }
}
