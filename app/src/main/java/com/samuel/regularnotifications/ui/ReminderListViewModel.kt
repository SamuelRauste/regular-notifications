package com.samuel.regularnotifications.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.samuel.regularnotifications.data.ReminderService
import com.samuel.regularnotifications.data.RepositoryActionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ReminderListViewModel(
    private val service: ReminderService,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ReminderListUiState())
    val uiState = _uiState.asStateFlow()

    init {
        observeReminders()
        refreshDerivedSchedule()
    }

    fun setEnabled(reminderId: Long, enabled: Boolean) {
        runAction {
            service.setEnabled(reminderId, enabled)
        }
    }

    fun delete(reminderId: Long) {
        runAction {
            service.deleteReminder(reminderId)
        }
    }

    fun setMasterEnabled(enabled: Boolean) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isMasterUpdating = true,
                    errorMessage = null,
                )
            }
            val result = try {
                service.setMasterEnabled(enabled)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(
                        isMasterUpdating = false,
                        errorMessage = "Could not update all reminders. Try again.",
                    )
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    isMasterUpdating = false,
                    errorMessage = if (result == RepositoryActionResult.APPLIED) {
                        null
                    } else {
                        "Could not update all reminders. Try again."
                    },
                )
            }
        }
    }

    fun onNotificationPermissionGranted() {
        refreshDerivedSchedule()
    }

    fun retry() {
        _uiState.update { it.copy(errorMessage = null) }
        refreshDerivedSchedule()
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun observeReminders() {
        viewModelScope.launch {
            combine(
                service.observeMasterEnabled(),
                service.observeReminders(),
            ) { masterEnabled, reminders ->
                masterEnabled to reminders.map { it.toReminderListItem(masterEnabled) }
            }
                .catch { error ->
                    if (error is CancellationException) throw error
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Could not load reminders. Try again.",
                        )
                    }
                }
                .collect { (masterEnabled, reminders) ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            reminders = reminders,
                            masterEnabled = masterEnabled,
                        )
                    }
                }
        }
    }

    private fun refreshDerivedSchedule() {
        viewModelScope.launch {
            try {
                service.reconcileAll()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Could not update reminder times. Try again.",
                    )
                }
            }
        }
    }

    private fun runAction(action: suspend () -> RepositoryActionResult) {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            val result = try {
                action()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _uiState.update { state -> state.copy(errorMessage = "Could not save that change. Try again.") }
                return@launch
            }
            if (result != RepositoryActionResult.APPLIED) {
                _uiState.update { state -> state.copy(errorMessage = result.message()) }
            }
        }
    }

    private fun RepositoryActionResult.message(): String = when (this) {
        RepositoryActionResult.NOT_FOUND -> "That reminder no longer exists."
        else -> "Could not save that change. Try again."
    }

    companion object {
        fun factory(service: ReminderService): ViewModelProvider.Factory = viewModelFactory {
            initializer { ReminderListViewModel(service) }
        }
    }
}
