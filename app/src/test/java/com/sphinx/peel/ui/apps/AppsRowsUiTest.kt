package com.sphinx.peel.ui.apps

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.sphinx.peel.engine.TalkHome
import com.sphinx.peel.engine.WorkspaceApp
import com.sphinx.peel.ui.theme.PeelTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// The Apps list's two kinds of row, drawn alone (docs/phases/36-apps-cosmetics.md): `AppScreen` itself reads the Stadeum singleton and the network, so
/// its rows (`AppRow`, `TalkRow`) are driven directly: the words, a tap, the long-press menus, and the icon drawing for known, aliased and unknown names.
@RunWith(RobolectricTestRunner::class)
class AppsRowsUiTest {
    @get:Rule val rule = createComposeRule()

    private val app = WorkspaceApp("acme", "board", "Board", about = "Plan the week", icon = "shield-check", version = "v3")

    @Test fun `an app row shows title, about and where it comes from`() {
        rule.setContent { PeelTheme { AppRow(app, showsSource = true, onOpen = {}, onShare = {}) } }
        rule.onNodeWithText("Board").assertExists()
        rule.onNodeWithText("Plan the week").assertExists()
        rule.onNodeWithText("@acme · v3").assertExists()
    }

    @Test fun `with one workspace the source is not repeated`() {
        rule.setContent { PeelTheme { AppRow(app, showsSource = false, onOpen = {}, onShare = {}) } }
        rule.onNodeWithText("v3").assertExists()
    }

    @Test fun `a tap opens and a long press offers the share link`() {
        var opened = 0
        val shared = mutableListOf<String>()
        rule.setContent { PeelTheme { AppRow(app, true, onOpen = { opened++ }, onShare = { shared += it }) } }
        rule.onNodeWithText("Board").performClick()
        assertEquals(1, opened)
        rule.onNodeWithText("Board").performTouchInput { longClick() }
        rule.onNodeWithText("Share link").performClick()
        assertEquals(listOf(app.shareLink()!!), shared)
        assertEquals(1, opened)
    }

    @Test fun `an app with no peer has no share menu`() {
        rule.setContent { PeelTheme { AppRow(WorkspaceApp(null, "mine", "Mine"), false, onOpen = {}, onShare = {}) } }
        rule.onNodeWithText("Mine").performTouchInput { longClick() }
        rule.onNodeWithText("Share link").assertDoesNotExist()
        // The menu still opens: it can pin.
        rule.onNodeWithText("Pin to Chats").assertExists()
    }

    @Test fun `the menu pins and unpins, and a pinned row shows a pin`() {
        var toggled = 0
        var pinned by androidx.compose.runtime.mutableStateOf(false)
        rule.setContent { PeelTheme { AppRow(app, true, onOpen = {}, onShare = {}, pinned = pinned, onTogglePin = { toggled++; pinned = !pinned }) } }
        rule.onNodeWithContentDescription("Pinned to Chats").assertDoesNotExist()
        rule.onNodeWithText("Board").performTouchInput { longClick() }
        rule.onNodeWithText("Pin to Chats").performClick()
        assertEquals(1, toggled)
        rule.onNodeWithContentDescription("Pinned to Chats").assertExists()
        rule.onNodeWithText("Board").performTouchInput { longClick() }
        rule.onNodeWithText("Unpin from Chats").performClick()
        assertEquals(2, toggled)
        rule.onNodeWithContentDescription("Pinned to Chats").assertDoesNotExist()
    }

    @Test fun `icons draw as the real lucide icon, an old name as its new one, an unknown or no name as the grid`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        // The bundled set is the library's (the old hand-drawn table is gone): a name only the real set has resolves to itself.
        val set = AppIcons.load(context)
        assertTrue("the whole lucide set is bundled, not a table of a few", set.names.size > 1000)
        assertEquals("shield-check", AppIcons.vector("shield-check")!!.name)
        // "bar-chart-3" is lucide's old name for chart-column.
        assertEquals("chart-column", AppIcons.vector("bar-chart-3")!!.name)
        // None or unknown: Apps' grid.
        assertEquals("layout-grid", AppIcons.vector("no-such-icon")!!.name)
        assertEquals("layout-grid", AppIcons.vector(null)!!.name)
        // Each name's vector draws real paths, and the rows around them still show.
        assertTrue(AppIcons.vector("shield-check")!!.root.size > 0)
        rule.setContent {
            PeelTheme {
                androidx.compose.foundation.layout.Column {
                    listOf("shield-check", "bar-chart-3", "no-such-icon", null).forEach { icon ->
                        AppRow(WorkspaceApp("acme", "a-$icon", "App $icon", icon = icon), false, {}, {})
                    }
                }
            }
        }
        listOf("shield-check", "bar-chart-3", "no-such-icon", "null").forEach { rule.onNodeWithText("App $it").assertExists() }
    }

    private val home = TalkHome("acme", "Acme", "https://acme.sphinx.chat", "tok")

    @Test fun `a talk row names the workspace and talks on a tap`() {
        var talked = 0
        rule.setContent { PeelTheme { TalkRow(home, onClick = { talked++ }, onHistory = {}) } }
        rule.onNodeWithText("Acme").assertExists()
        rule.onNodeWithText("@acme").assertExists()
        rule.onNodeWithContentDescription("Talk to Jamie on Acme").assertExists()
        rule.onNodeWithText("Acme").performClick()
        assertEquals(1, talked)
    }

    @Test fun `a talk row's long press has New conversation and History`() {
        var talked = 0
        var history = 0
        rule.setContent { PeelTheme { TalkRow(home, onClick = { talked++ }, onHistory = { history++ }) } }
        rule.onNodeWithText("Acme").performTouchInput { longClick() }
        rule.onNodeWithText("History").performClick()
        assertEquals(1, history)
        assertEquals(0, talked)
        rule.onNodeWithText("Acme").performTouchInput { longClick() }
        rule.onNodeWithText("New conversation").performClick()
        assertEquals(1, talked)
        assertTrue(history == 1)
    }
}
