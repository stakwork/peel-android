package com.sphinx.peel.ui.apps

import android.app.Application
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.sphinx.peel.engine.OrgDownloads
import com.sphinx.peel.engine.OrgPageRequest
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

/// The iOS navigation table (`OrgNavigation.decide`), row by row, as the web view client applies it (docs/phases/29-org-page-host.md): serves, about, data,
/// blob, an external link with and without the person's tap, and the schemes that are never loaded. `OrgPageHostTest` has the same rules'
/// first cases; this is the whole table in one place, for a main frame and for a sub frame.
@RunWith(RobolectricTestRunner::class)
class OrgNavigationTableTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val strut = OrgPageRequest("https://acme.sphinx.chat/lab/?app=x&solo=1", "tok", "acme", "Acme")
    private lateinit var web: WebView
    private lateinit var controller: OrgPageController
    private lateinit var client: OrgPageClient
    private val outside = mutableListOf<String>()

    private class Req(private val url: String, private val main: Boolean, private val gesture: Boolean) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = gesture
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    @Before fun setUp() {
        web = WebView(app)
        controller = OrgPageController(strut, OrgDownloads(java.io.File(app.cacheDir, "nav-table-test")), CoroutineScope(Dispatchers.Unconfined))
        controller.web = web
        controller.lastTouchAt = 9_900
        client = OrgPageClient(controller, { outside += it }, { 10_000 })
    }

    @After fun tearDown() { controller.close() }

    /// `cancelled` is what `shouldOverrideUrlLoading` returns (true: not loaded by the web view as it is).
    private fun cancelled(url: String, main: Boolean = true, gesture: Boolean = false) = client.shouldOverrideUrlLoading(web, Req(url, main, gesture))

    @Test fun `serves - the struts folder, in either frame`() {
        for (main in listOf(true, false)) assertFalse(cancelled("https://acme.sphinx.chat/lab/jobs/j/files/a.md", main))
    }

    @Test fun `about - allowed in either frame`() {
        for (main in listOf(true, false)) {
            assertFalse(cancelled("about:blank", main))
            assertFalse(cancelled("about:srcdoc", main))
        }
    }

    @Test fun `data and blob - only as a frame`() {
        assertTrue(cancelled("data:text/html,hi", main = true))
        assertFalse(cancelled("data:text/html,hi", main = false))
        assertTrue(cancelled("blob:https://acme.sphinx.chat/u", main = true))
        assertFalse(cancelled("blob:https://acme.sphinx.chat/u", main = false))
    }

    @Test fun `an external web link goes out on a main frame tap and nowhere else`() {
        assertTrue(cancelled("https://example.com/a", main = true, gesture = true))
        assertTrue(cancelled("http://example.com/b", main = true, gesture = true))
        assertEquals(listOf("https://example.com/a", "http://example.com/b"), outside)
        assertTrue(cancelled("https://example.com/c", main = true, gesture = false))
        assertTrue(cancelled("https://example.com/d", main = false, gesture = true))
        assertEquals(2, outside.size)
    }

    @Test fun `no other scheme is ever loaded or opened`() {
        for (url in listOf("javascript:alert(1)", "file:///etc/hosts", "intent://x#Intent;end", "mailto:a@b.c", "tel:123", "market://details?id=x", "content://x/y", "ftp://example.com/a")) {
            assertTrue(url, cancelled(url, main = true, gesture = true))
        }
        assertTrue(outside.isEmpty())
        assertNull(shadowOf(web).lastLoadedUrl)
    }

    @Test fun `a lookalike host or a port of the struts host is not the strut`() {
        for (url in listOf("https://acme.sphinx.chat.evil.example/lab/", "https://acme.sphinx.chat:8443/lab/", "http://acme.sphinx.chat/lab/", "https://user@acme.sphinx.chat/lab/")) {
            assertTrue(url, cancelled(url, main = true, gesture = false))
        }
        assertTrue(outside.isEmpty())
    }
}
