package com.sphinx.peel.ui.apps

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sphinx.peel.engine.OrgPageRequest
import com.sphinx.peel.engine.OrgPageRules
import com.sphinx.peel.engine.PhonePage
import com.sphinx.peel.engine.PublicAppLink
import com.sphinx.peel.engine.PublicPage
import com.sphinx.peel.engine.ScriptedWallet
import com.sphinx.peel.engine.WalletConnection
import com.sphinx.peel.engine.WalletConnections
import com.sphinx.peel.engine.WalletHost
import com.sphinx.peel.ui.theme.PeelTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/// `OrgPageScreen` itself, composed over Robolectric's WebView (docs/phases/29-org-page-host.md, 30): the toolbar, what the page's first load is, the
/// failure overlay and Try again, the renderer going and a new web view loading where it was, the download listener reaching `OrgDownloads`,
/// and the public page's wallet note. What ShadowWebView cannot do is not here (see the phase doc): a real document-start script,
/// `addWebMessageListener`, profiles, a download of a real file.
@RunWith(RobolectricTestRunner::class)
class OrgPageScreenUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Connections : WalletConnections {
        val rows = mutableMapOf<String, WalletConnection>()
        override fun connection(key: String) = rows[key]
        override fun save(connection: WalletConnection) { rows[connection.key] = connection }
    }

    private class Req(private val url: String, private val main: Boolean = true) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    // `.invalid` never resolves, so a download of it fails the same way everywhere.
    private val org = OrgPageRequest("https://strut.invalid/lab/?app=board&solo=1", "tok", "acme", "Acme", "Board")
    private val host = WalletHost(Connections(), ScriptedWallet(), { 1_793_800_000_000L })
    private var closed = 0

    private fun show(page: PhonePage = org) = rule.setContent { PeelTheme { OrgPageScreen(page, host, onClose = { closed++ }) } }

    private fun webView(): WebView = rule.runOnIdle {
        fun walk(v: View): WebView? = if (v is WebView) v else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { walk(g.getChildAt(it)) } }
        walk(rule.activity.window.decorView)!!
    }

    private fun client() = shadowOf(webView()).webViewClient as OrgPageClient

    @Test fun `it opens the page with the persons token under a bar with no title`() {
        show()
        val web = webView()
        assertEquals(org.url, shadowOf(web).lastLoadedUrl)
        assertEquals("Bearer tok", shadowOf(web).lastAdditionalHttpHeaders["Authorization"])
        // Full screen: Close and More, and nothing else: no Done, no Reload in reach, neither the app's title nor the workspace.
        rule.onNodeWithContentDescription("Close").assertExists()
        rule.onNodeWithContentDescription("More").assertExists()
        rule.onNodeWithContentDescription("Done").assertDoesNotExist()
        rule.onNodeWithContentDescription("Reload").assertDoesNotExist()
        rule.onNodeWithText("Board").assertDoesNotExist()
        rule.onNodeWithText("Acme").assertDoesNotExist()
    }

    @Test fun `close closes and reload in More loads again with the bearer`() {
        show()
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Reload").performClick()
        assertEquals("Bearer tok", shadowOf(webView()).lastAdditionalHttpHeaders["Authorization"])
        rule.onNodeWithContentDescription("Close").performClick()
        assertEquals(1, closed)
    }

    @Test fun `inside Apps it is titled by the app and its back returns to Apps`() {
        rule.setContent { PeelTheme { OrgPageScreen(org, host, onClose = { closed++ }, inApps = true, title = "Board") } }
        rule.onNodeWithText("Board").assertExists()
        rule.onNodeWithText("Acme").assertDoesNotExist()
        rule.onNodeWithContentDescription("Close").assertDoesNotExist()
        rule.onNodeWithContentDescription("More").assertExists()
        // Nothing to go back to in the page: back leaves for the list.
        rule.onNodeWithContentDescription("Apps").performClick()
        assertEquals(1, closed)
    }

    @Test fun `a failed load shows the failure screen and Try again loads it again`() {
        show()
        val web = webView()
        rule.runOnUiThread {
            client().onPageStarted(web, org.url, null)
            client().onReceivedHttpError(web, Req(org.url), WebResourceResponse("text/html", "utf-8", 401, "Unauthorized", emptyMap(), null))
        }
        rule.onNodeWithText(OrgPageRules.FAILURE_TITLE).assertExists()
        rule.onNodeWithText("The workspace did not let this device open it (HTTP 401).").assertExists()
        rule.onNodeWithText(OrgPageRules.TRY_AGAIN).performClick()
        rule.onNodeWithText(OrgPageRules.FAILURE_TITLE).assertDoesNotExist()
        assertEquals(org.url, shadowOf(webView()).lastLoadedUrl)
        assertEquals("Bearer tok", shadowOf(webView()).lastAdditionalHttpHeaders["Authorization"])
    }

    @Test fun `a failure of a frame does not show the screen`() {
        show()
        val web = webView()
        rule.runOnUiThread { client().onReceivedHttpError(web, Req(org.url, main = false), WebResourceResponse("text/html", "utf-8", 500, "x", emptyMap(), null)) }
        rule.onNodeWithText(OrgPageRules.FAILURE_TITLE).assertDoesNotExist()
    }

    @Test fun `when the web process ends a new web view loads the page again`() {
        show()
        val before = webView()
        val detail = object : RenderProcessGoneDetail() {
            override fun didCrash() = true
            override fun rendererPriorityAtExit() = 0
        }
        rule.runOnUiThread { assert(client().onRenderProcessGone(before, detail)) }
        rule.waitForIdle()
        val after = webView()
        assertNotSame(before, after)
        assertEquals(org.url, shadowOf(after).lastLoadedUrl)
        assertEquals("Bearer tok", shadowOf(after).lastAdditionalHttpHeaders["Authorization"])
    }

    @Test fun `a download the page may not make is ignored`() {
        show()
        val listener = shadowOf(webView()).downloadListener
        assertNotNull(listener)
        rule.runOnUiThread { listener.onDownloadStart("https://evil.example/lab/jobs/j/files/a.md", "ua", "attachment", "text/plain", 1) }
        rule.waitForIdle()
        rule.onNodeWithText(OrgPageRules.NOTICE_TITLE).assertDoesNotExist()
    }

    @Test fun `a download of the struts own file goes to OrgDownloads and its failure is told`() {
        show()
        val listener = shadowOf(webView()).downloadListener
        rule.runOnUiThread { listener.onDownloadStart("https://strut.invalid/lab/jobs/j/files/a.md", "ua", "attachment; filename=a.md", "text/plain", 1) }
        rule.waitUntil(15_000) { rule.onAllNodesWithText(OrgPageRules.DOWNLOAD_FAILED).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(OrgPageRules.NOTICE_TITLE).assertExists()
        rule.onNodeWithText("OK").performClick()
        rule.onNodeWithText(OrgPageRules.NOTICE_TITLE).assertDoesNotExist()
    }

    @Test fun `a public page says when the device cannot offer sign in`() {
        val page = PublicPage(PublicAppLink.of("https://shop.example.com/?app=moon-game")!!)
        show(page)
        // Robolectric's WebView has no WEB_MESSAGE_LISTENER: nothing is installed and the person is told.
        rule.onNodeWithText("This device's web view can't offer Sign in with Sphinx.").assertExists()
        assertSame(page, page)
    }
}
