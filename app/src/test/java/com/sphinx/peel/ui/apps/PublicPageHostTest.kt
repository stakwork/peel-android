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

    /// Reload and Try again with no web view (its process ended and none was made since) make a new one, which loads where the
    /// page was; they used to do nothing at all (docs/phases/42-page-host-logs.md).
    @Test fun `reload and try again with no web view make a new one`() {
        controller.web = null
        val before = controller.generation
        controller.reload()
        assertEquals(before + 1, controller.generation)
        controller.failed("The page did not load.", page.url)
        controller.retry()
        assertEquals(before + 2, controller.generation)
        assertNull(controller.problem)
        assertEquals(page.url, controller.initialRequest().url)
    }

    /// A page whose web view could not be put in its profile goes on with none: its data is wiped as without profiles.
    @Test fun `a profile that cannot be used is dropped`() {
        val profiled = OrgPageController(page, OrgDownloads(java.io.File(app.cacheDir, "profile-downloads-test")), CoroutineScope(Dispatchers.Unconfined), "peel_org_test")
        assertEquals("on", useProfile(profiled) {})
        assertEquals("peel_org_test", profiled.profile)
        assertEquals("threw:IllegalStateException", useProfile(profiled) { throw IllegalStateException("in use") })
        assertNull(profiled.profile)
        assertEquals("none", useProfile(profiled) { error("not called") })
        profiled.close()
    }

    /// A public site's kept profile outlives the page (its visitor stays signed in); a profile made for one open does not.
    @Test fun `closing a public page keeps its site's profile`() {
        val kept = OrgPageController(page, OrgDownloads(java.io.File(app.cacheDir, "kept-downloads-test")), CoroutineScope(Dispatchers.Unconfined), page.keptProfile)
        val before = org.robolectric.shadows.ShadowLog.getLogsForTag(com.sphinx.peel.engine.PageLog.TAG).size
        kept.close()
        val once = OrgPageController(page, OrgDownloads(java.io.File(app.cacheDir, "once-downloads-test")), CoroutineScope(Dispatchers.Unconfined), "peel_org_once")
        once.close()
        val lines = org.robolectric.shadows.ShadowLog.getLogsForTag(com.sphinx.peel.engine.PageLog.TAG).drop(before).map { it.msg }
        assertEquals(listOf("close profile=kept gen=0", "close profileDeleted=false gen=0"), lines)
    }

    @Test fun `a failing load shows the failure screen`() {
        client.onPageStarted(web, page.url, null)
        client.onReceivedHttpError(web, Req(page.url), WebResourceResponse("text/html", "utf-8", 404, "Not Found", emptyMap(), null))
        assertEquals("The workspace has nothing there (HTTP 404).", controller.problem)
    }

    @Test fun `the wallet is offered by default and a missing bridge is no failure`() {
        assertTrue(controller.walletOffered)
        assertNull(controller.wallet)
    }
}
