package com.sphinx.peel.ui.apps

import android.app.Application
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.sphinx.peel.engine.OrgDownloads
import com.sphinx.peel.engine.PublicAppLink
import com.sphinx.peel.engine.PublicPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/// The one page host with a stranger's public page in it (what `PublicPageScreen` was): no credential anywhere, the origin alone,
/// the failure screen and the way out for a tapped link that every page of the host has.
@RunWith(RobolectricTestRunner::class)
class PublicPageHostTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val page = PublicPage(PublicAppLink.of("https://shop.example.com/?app=moon-game")!!)
    private lateinit var web: WebView
    private lateinit var controller: OrgPageController
    private lateinit var client: OrgPageClient
    private val outside = mutableListOf<String>()

    private class Req(private val url: String, private val main: Boolean = true, private val gesture: Boolean = false) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = gesture
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    @Before fun setUp() {
        web = WebView(app)
        controller = OrgPageController(page, OrgDownloads(java.io.File(app.cacheDir, "public-downloads-test")), CoroutineScope(Dispatchers.Unconfined))
        controller.web = web
        client = OrgPageClient(controller, { outside += it }, { 10_000 })
    }

    @After fun tearDown() { controller.close() }

    @Test fun `its own origin loads as it is and is never given a header`() {
        assertFalse(client.shouldOverrideUrlLoading(web, Req("https://shop.example.com/cart", gesture = true)))
        assertNull(shadowOf(web).lastLoadedUrl)
        assertEquals(page.url, controller.initialRequest().url)
        assertTrue(controller.initialRequest().headers.isEmpty())
    }

    @Test fun `another site never loads and goes out only on a tap`() {
        controller.lastTouchAt = 9_600
        assertTrue(client.shouldOverrideUrlLoading(web, Req("https://example.org/a", gesture = true)))
        assertEquals(listOf("https://example.org/a"), outside)
        assertTrue(client.shouldOverrideUrlLoading(web, Req("https://example.org/b", gesture = false)))
        assertTrue(client.shouldOverrideUrlLoading(web, Req("javascript:alert(1)", gesture = true)))
        assertEquals(1, outside.size)
    }

    @Test fun `reload and try again load where it is, with no credential`() {
        web.loadUrl("https://shop.example.com/cart")
        controller.reload()
        assertEquals("https://shop.example.com/cart", shadowOf(web).lastLoadedUrl)
        assertTrue(shadowOf(web).lastAdditionalHttpHeaders.isEmpty())
        controller.failed("The workspace had a problem answering (HTTP 500).", "https://shop.example.com/broken")
        controller.retry()
        assertEquals("https://shop.example.com/broken", shadowOf(web).lastLoadedUrl)
        assertTrue(shadowOf(web).lastAdditionalHttpHeaders.isEmpty())
    }

    @Test fun `a failing load shows the failure screen`() {
        client.onPageStarted(web, page.url, null)
        client.onReceivedHttpError(web, Req(page.url), WebResourceResponse("text/html", "utf-8", 404, "Not Found", emptyMap(), null))
        assertEquals("The workspace has nothing there (HTTP 404).", controller.problem)
    }

    @Test fun `the title is the host and another app is none`() {
        controller.sync()
        assertFalse(controller.showsAnotherApp)
        assertEquals("shop.example.com", page.title(controller.showsAnotherApp))
    }

    @Test fun `the wallet is offered by default and a missing bridge is no failure`() {
        assertTrue(controller.walletOffered)
        assertNull(controller.wallet)
    }
}
