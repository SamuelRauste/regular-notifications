package com.samuel.regularnotifications.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
}
