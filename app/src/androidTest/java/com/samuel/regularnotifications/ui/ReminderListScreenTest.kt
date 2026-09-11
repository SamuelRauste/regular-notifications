package com.samuel.regularnotifications.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderListScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyListClearlyGuidesANewUserToAddAReminder() {
        var addTapped = false

        composeRule.setContent {
            MaterialTheme {
                ReminderListScreen(
                    uiState = ReminderListUiState(isLoading = false),
                    onAddReminder = { addTapped = true },
                    onEditReminder = {},
                    onSetEnabled = { _, _ -> },
                    onDeleteReminder = {},
                    onRetry = {},
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithText("No reminders yet.").assertIsDisplayed()
        composeRule.onNodeWithText("Add one to get started.").assertIsDisplayed()
        composeRule.onNodeWithText("Add reminder").performClick()
        composeRule.runOnIdle { assertTrue(addTapped) }
    }

    @Test
    fun reminderCardUsesPausedSummaryAndReminderSpecificControlSemantics() {
        composeRule.setContent {
            MaterialTheme {
                ReminderListScreen(
                    uiState = ReminderListUiState(
                        isLoading = false,
                        reminders = listOf(
                            ReminderListItem(
                                id = 7,
                                title = "Take out trash",
                                description = null,
                                enabled = false,
                                intervalDays = 7,
                                nextOccurrence = "Tue 10 Sep, 09:00",
                            ),
                        ),
                    ),
                    onAddReminder = {},
                    onEditReminder = {},
                    onSetEnabled = { _, _ -> },
                    onDeleteReminder = {},
                    onRetry = {},
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithText("Every 7 days · Paused").assertIsDisplayed()
        composeRule.onNodeWithText("Every 7 days · Next: Tue 10 Sep, 09:00").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Edit Take out trash").assertHasClickAction()
        composeRule.onNodeWithContentDescription("Delete Take out trash").assertHasClickAction()
        composeRule.onNodeWithContentDescription("Enable Take out trash").assertHasClickAction()
    }

    @Test
    fun globalPauseIsVisibleWithoutChangingTheReminderSpecificSwitch() {
        var resumed = false

        composeRule.setContent {
            MaterialTheme {
                ReminderListScreen(
                    uiState = ReminderListUiState(
                        isLoading = false,
                        masterEnabled = false,
                        reminders = listOf(
                            ReminderListItem(
                                id = 7,
                                title = "Take out trash",
                                description = null,
                                enabled = true,
                                intervalDays = 7,
                                nextOccurrence = "Tue 10 Sep, 09:00",
                                masterEnabled = false,
                            ),
                        ),
                    ),
                    onAddReminder = {},
                    onEditReminder = {},
                    onSetEnabled = { _, _ -> },
                    onSetMasterEnabled = { resumed = it },
                    onDeleteReminder = {},
                    onRetry = {},
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithText("Every 7 days - Globally paused").assertIsDisplayed()
        composeRule.onNodeWithText("Enabled").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Resume all reminders").performClick()
        composeRule.runOnIdle { assertTrue(resumed) }
    }

    @Test
    fun historyActionIsVisibleAndAccessible() {
        var historyTapped = false

        composeRule.setContent {
            MaterialTheme {
                ReminderListScreen(
                    uiState = ReminderListUiState(isLoading = false),
                    onAddReminder = {},
                    onEditReminder = {},
                    onSetEnabled = { _, _ -> },
                    onDeleteReminder = {},
                    onRetry = {},
                    onDismissError = {},
                    onOpenHistory = { historyTapped = true },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open history")
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertTrue(historyTapped) }
    }
}
