package com.samuel.regularnotifications.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyHistoryExplainsWhatWillAppearAndBackIsAccessible() {
        var backTapped = false
        composeRule.setContent {
            MaterialTheme {
                HistoryScreen(
                    uiState = HistoryUiState(isLoading = false),
                    onBack = { backTapped = true },
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithText("No history yet.").assertIsDisplayed()
        composeRule.onNodeWithText("Done, Dismiss, +1 day, and Seen actions will appear here.")
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").assertHasClickAction().performClick()
        composeRule.runOnIdle { assertTrue(backTapped) }
    }

    @Test
    fun populatedHistoryShowsFriendlyActionsAndPostponedTimes() {
        composeRule.setContent {
            MaterialTheme {
                HistoryScreen(
                    uiState = HistoryUiState(
                        isLoading = false,
                        items = listOf(
                            HistoryItem(
                                id = 1,
                                reminderId = 7,
                                reminderTitle = "Take out trash",
                                action = HistoryAction.POSTPONED,
                                occurredAt = Instant.parse("2026-01-01T10:00:00Z"),
                                postponedUntil = Instant.parse("2026-01-02T09:00:00Z"),
                            ),
                        ),
                    ),
                    onBack = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithText("Take out trash").assertIsDisplayed()
        composeRule.onNodeWithText("Postponed").assertIsDisplayed()
        composeRule.onNodeWithText("Action time:", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("New reminder time:", substring = true).assertIsDisplayed()
    }
}
