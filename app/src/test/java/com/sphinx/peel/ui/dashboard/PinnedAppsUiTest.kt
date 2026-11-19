package com.sphinx.peel.ui.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.sphinx.peel.engine.WorkspaceApp
import com.sphinx.peel.ui.theme.PeelTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// The Apps row and the apps pinned under it, drawn alone as `ChatListBody` draws them (docs/phases/36-apps-cosmetics.md).
@RunWith(RobolectricTestRunner::class)
class PinnedAppsUiTest {
    @get:Rule val rule = createComposeRule()

    private val board = WorkspaceApp("acme", "board", "Board")

    @Test fun `a pinned row shows under Apps and the chevron folds it`() {
        var layout by mutableStateOf(SidebarLayout(pinnedApps = listOf(board.id)))
        rule.setContent {
            PeelTheme {
                Column {
                    AppsRow(fold = layout.appsOpen, onToggleFold = { layout = layout.copy(appsOpen = !layout.appsOpen) }, onClick = {})
                    if (layout.appsOpen) PinnedAppRow(board, showsSource = false, pinned = true, onOpen = {}, onTogglePin = {})
                }
            }
        }
        rule.onNodeWithText("Apps").assertExists()
        rule.onNodeWithText("Board").assertExists()
        rule.onNodeWithContentDescription("Pinned apps").performClick()
        rule.onNodeWithText("Board").assertDoesNotExist()
        rule.onNodeWithContentDescription("Pinned apps").performClick()
        rule.onNodeWithText("Board").assertExists()
    }

    @Test fun `no chevron without a pinned app`() {
        rule.setContent { PeelTheme { AppsRow(fold = null, onToggleFold = {}, onClick = {}) } }
        rule.onNodeWithContentDescription("Pinned apps").assertDoesNotExist()
    }

    @Test fun `a tap opens, the menu unpins, and the workspace shows with several`() {
        var opened = 0
        var toggled = 0
        rule.setContent { PeelTheme { PinnedAppRow(board, showsSource = true, pinned = true, onOpen = { opened++ }, onTogglePin = { toggled++ }) } }
        rule.onNodeWithText("Board \u00b7 @acme").performClick()
        assertEquals(1, opened)
        rule.onNodeWithText("Board \u00b7 @acme").performTouchInput { longClick() }
        rule.onNodeWithText("Unpin").performClick()
        assertEquals(1, toggled)
        assertEquals(1, opened)
    }

    @Test fun `an app found by a search that is not pinned offers Pin to Chats`() {
        rule.setContent { PeelTheme { PinnedAppRow(board, showsSource = false, pinned = false, onOpen = {}, onTogglePin = {}) } }
        rule.onNodeWithText("Board").performTouchInput { longClick() }
        rule.onNodeWithText("Pin to Chats").assertExists()
    }
}
