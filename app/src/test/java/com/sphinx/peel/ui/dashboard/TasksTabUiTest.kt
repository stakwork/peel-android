package com.sphinx.peel.ui.dashboard

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.sphinx.peel.engine.OpenedApp
import com.sphinx.peel.engine.PublicAppLink
import com.sphinx.peel.engine.PublicPage
import com.sphinx.peel.engine.ScriptedWallet
import com.sphinx.peel.engine.WalletConnection
import com.sphinx.peel.engine.WalletConnections
import com.sphinx.peel.engine.WalletHost
import com.sphinx.peel.ui.apps.OrgPageScreen
import com.sphinx.peel.ui.page.PhoneWalletSheets
import com.sphinx.peel.ui.theme.PeelTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/// The Tasks tab's two faces, composed over Robolectric (docs/phases/48-tasks-tab.md): the list of employers, and an employer's page
/// kept alive behind the other tabs (`KeptPage` + `OrgPageScreen(shown)`). `MainTabs` itself needs the native engine, so it is not
/// composed here; its rules are `TabStateTest`'s and `TasksTabTest`'s.
@RunWith(RobolectricTestRunner::class)
class TasksTabUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Connections : WalletConnections {
        val rows = mutableMapOf<String, WalletConnection>()
        override fun connection(key: String) = rows[key]
        override fun save(connection: WalletConnection) { rows[connection.key] = connection }
    }

    private val host = WalletHost(Connections(), ScriptedWallet(), { 1_793_800_000_000L })
    private val acme = PublicAppLink.of("https://acme.invalid/?app=strut%2Fworker&solo=1&public=1")!!
    private val bolt = PublicAppLink.of("https://bolt.invalid/?app=strut%2Fworker&solo=1&public=1")!!

    private fun webViews(): List<WebView> = rule.runOnIdle {
        fun walk(v: View): List<WebView> = if (v is WebView) listOf(v) else (v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { walk(g.getChildAt(it)) } } ?: emptyList()
        walk(rule.activity.window.decorView)
    }

    @Test fun `several employers are a row each, titled by the connection or the host, with the host under it`() {
        val opened = mutableListOf<PublicAppLink>()
        val removed = mutableListOf<PublicAppLink>()
        rule.setContent {
            PeelTheme { TasksList(listOf(OpenedApp(acme, 2, "Acme Tasks"), OpenedApp(bolt, 1)), onOpen = { opened += it }, onRemove = { removed += it }) }
        }
        rule.onNodeWithText("Acme Tasks").assertExists()
        rule.onNodeWithText("acme.invalid").assertExists()
        rule.onNodeWithText("bolt.invalid").performClick()
        assertEquals(listOf(bolt), opened)
        rule.onNodeWithText("Acme Tasks").performTouchInput { longClick() }
        rule.onNodeWithText("Remove from Tasks").performClick()
        assertEquals(listOf(acme), removed)
    }

    /// Switching tabs only stops placing the page: the same web view stays in the window, and its wallet slot stays open (a page
    /// leaving the composition closes it: `OrgPageDisposeTest`). It is not drawn or reachable meanwhile, and has no Back.
    @Test fun `the page lives on behind another tab and comes back as it was`() {
        val sheets = PhoneWalletSheets(CoroutineScope(Dispatchers.Unconfined))
        var shown by mutableStateOf(true)
        rule.setContent {
            PeelTheme {
                KeptPage(shown = shown) {
                    // A page picked off the list (it has a close), so its Back is on while it shows.
                    val page = remember { PublicPage(acme) }
                    OrgPageScreen(page, host, onClose = {}, sheets = sheets, shown = shown)
                }
            }
        }
        val web = webViews().single()
        assertEquals(acme.url, shadowOf(web).lastLoadedUrl)
        rule.onNodeWithContentDescription("Reload").assertIsDisplayed()
        assertTrue(rule.activity.onBackPressedDispatcher.hasEnabledCallbacks())

        shown = false
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Reload").assertIsNotDisplayed()
        assertTrue("the page was not closed", sheets.stillOpen)
        assertSame(web, webViews().single())
        assertFalse("a page out of sight takes no Back", rule.activity.onBackPressedDispatcher.hasEnabledCallbacks())

        shown = true
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Reload").assertIsDisplayed()
        assertSame("the same web view, not loaded again", web, webViews().single())
        assertEquals(acme.url, shadowOf(web).lastLoadedUrl)
        assertTrue(sheets.stillOpen)
        assertTrue(rule.activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test fun `the one employer's page is the tab, with no Done, and Back at its first address is not the page's`() {
        rule.setContent { PeelTheme { OrgPageScreen(PublicPage(acme), host, onClose = null) } }
        rule.onNodeWithContentDescription("Done").assertDoesNotExist()
        rule.onNodeWithText("acme.invalid").assertExists()
        assertFalse(rule.activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test fun `a page picked off the list has Done, and Back, to return to the list`() {
        var closed = 0
        rule.setContent { PeelTheme { OrgPageScreen(PublicPage(bolt), host, onClose = { closed++ }) } }
        rule.onNodeWithContentDescription("Done").performClick()
        assertEquals(1, closed)
        assertTrue(rule.activity.onBackPressedDispatcher.hasEnabledCallbacks())
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(2, closed)
    }
}
