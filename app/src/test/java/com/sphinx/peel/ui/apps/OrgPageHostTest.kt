package com.sphinx.peel.ui.apps

import android.app.Application
import android.net.Uri
import android.os.Message
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.sphinx.peel.engine.OrgDownloads
import com.sphinx.peel.engine.OrgPageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/// The org-page host's policy as Android's web view calls it (`OrgPageClient`, `OrgPageChromeClient`, `OrgPageController`),
/// on Robolectric's WebView. What the rules decide is `OrgPageTest`'s; this checks they are applied: what loads, with which
/// headers, what goes out to the system, what the failure screen says. A real WebView's document-start script, profiles, new
/// windows and downloads need a device (see the phase doc).
@RunWith(RobolectricTestRunner::class)
class OrgPageHostTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val strut = OrgPageRequest("https://acme.sphinx.chat/lab/?app=x&solo=1", "tok", "acme", "Acme", "Board")
    private lateinit var web: WebView
    private lateinit var controller: OrgPageController
    private lateinit var client: OrgPageClient
    private lateinit var chrome: OrgPageChromeClient
    private val outside = mutableListOf<String>()
    private var clock = 10_000L

    private class Req(
        private val url: String,
        private val main: Boolean = true,
        private val gesture: Boolean = false,
        private val method: String = "GET",
    ) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = gesture
        override fun getMethod() = method
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    @Before fun setUp() {
        web = WebView(app)
        controller = OrgPageController(strut, OrgDownloads(java.io.File(app.cacheDir, "org-downloads-test")), CoroutineScope(Dispatchers.Unconfined))
        controller.web = web
        client = OrgPageClient(controller, { outside += it }, { clock })
        chrome = OrgPageChromeClient(controller, { outside += it }, { clock })
    }

    @After fun tearDown() { controller.close() }

    @Test fun `a file of the struts folder loads as it is`() {
        assertFalse(client.shouldOverrideUrlLoading(web, Req("https://acme.sphinx.chat/lab/jobs/j/files/a.md")))
        assertNull(shadowOf(web).lastLoadedUrl)
    }

    @Test fun `struts ui in the folder is loaded again with the bearer`() {
        val url = "https://acme.sphinx.chat/lab/?app=board&solo=1"
        assertTrue(client.shouldOverrideUrlLoading(web, Req(url, gesture = true)))
        assertEquals(url, shadowOf(web).lastLoadedUrl)
        assertEquals("Bearer tok", shadowOf(web).lastAdditionalHttpHeaders["Authorization"])
    }

    @Test fun `a post or a frame is not loaded again`() {
        val url = "https://acme.sphinx.chat/lab/?app=board&solo=1"
        assertFalse(client.shouldOverrideUrlLoading(web, Req(url, method = "POST")))
        assertFalse(client.shouldOverrideUrlLoading(web, Req(url, main = false)))
        assertNull(shadowOf(web).lastLoadedUrl)
    }

    @Test fun `a server redirecting to itself is loaded again only a few times`() {
        val url = "https://acme.sphinx.chat/lab/?app=board&solo=1"
        repeat(5) { assertTrue(client.shouldOverrideUrlLoading(web, Req(url))) }
        assertFalse(client.shouldOverrideUrlLoading(web, Req(url)))
        client.onPageFinished(web, url)
        assertTrue(client.shouldOverrideUrlLoading(web, Req(url)))
    }

    @Test fun `another origin never loads here`() {
        assertTrue(client.shouldOverrideUrlLoading(web, Req("https://evil.example/lab/?app=x")))
        assertTrue(client.shouldOverrideUrlLoading(web, Req("https://acme.sphinx.chat/other/")))
        assertTrue(client.shouldOverrideUrlLoading(web, Req("file:///etc/passwd")))
        assertTrue(client.shouldOverrideUrlLoading(web, Req("intent://x#Intent;end", gesture = true)))
        assertTrue(outside.isEmpty())
        assertNull(shadowOf(web).lastLoadedUrl)
    }

    @Test fun `data and blob are cancelled for the page and allowed in a frame`() {
        assertTrue(client.shouldOverrideUrlLoading(web, Req("data:text/html,hi")))
        assertFalse(client.shouldOverrideUrlLoading(web, Req("data:text/html,hi", main = false)))
        assertTrue(client.shouldOverrideUrlLoading(web, Req("blob:https://acme.sphinx.chat/u")))
        assertFalse(client.shouldOverrideUrlLoading(web, Req("about:srcdoc", main = false)))
    }

    @Test fun `a link elsewhere goes to the browser only on the persons tap`() {
        val link = "https://example.com/a"
        controller.lastTouchAt = 9_500
        assertTrue(client.shouldOverrideUrlLoading(web, Req(link, gesture = true)))
        assertEquals(listOf(link), outside)
        // No gesture (a script's click), a frame's link, or a stale touch: not out.
        client.shouldOverrideUrlLoading(web, Req(link, gesture = false))
        client.shouldOverrideUrlLoading(web, Req(link, main = false, gesture = true))
        controller.lastTouchAt = 5_000
        client.shouldOverrideUrlLoading(web, Req(link, gesture = true))
        assertEquals(listOf(link), outside)
    }

    @Test fun `a main frame http failure shows iOS's words and a frame's does not`() {
        val url = "https://acme.sphinx.chat/lab/?app=x&solo=1"
        client.onReceivedHttpError(web, Req(url, main = false), WebResourceResponse("text/html", "utf-8", 404, "Not Found", emptyMap(), null))
        assertNull(controller.problem)
        client.onReceivedHttpError(web, Req(url), WebResourceResponse("text/html", "utf-8", 200, "OK", emptyMap(), null))
        assertNull(controller.problem)
        client.onPageStarted(web, url, null)
        client.onReceivedHttpError(web, Req(url), WebResourceResponse("text/html", "utf-8", 401, "Unauthorized", emptyMap(), null))
        assertEquals("The workspace did not let this device open it (HTTP 401).", controller.problem)
        // The failed load finishes too, and must not clear its own failure.
        client.onPageFinished(web, url)
        assertNotNull(controller.problem)
        // Try again loads it with the bearer, and a load that works clears the screen.
        controller.retry()
        assertNull(controller.problem)
        assertEquals(url, shadowOf(web).lastLoadedUrl)
        assertEquals("Bearer tok", shadowOf(web).lastAdditionalHttpHeaders["Authorization"])
        client.onPageStarted(web, url, null)
        client.onPageFinished(web, url)
        assertNull(controller.problem)
    }

    @Test fun `try again loads a failed file as the page's own ui, not the file`() {
        controller.failed("The workspace has nothing there (HTTP 404).", "https://acme.sphinx.chat/lab/jobs/j/files/a.md")
        controller.retry()
        // The failed address is a file (no bearer for it), so what loads is the page itself, with its token.
        assertEquals(strut.url, shadowOf(web).lastLoadedUrl)
        assertEquals("Bearer tok", shadowOf(web).lastAdditionalHttpHeaders["Authorization"])
    }

    @Test fun `progress and loading follow the page`() {
        val url = "https://acme.sphinx.chat/lab/?app=x&solo=1"
        client.onPageStarted(web, url, null)
        assertTrue(controller.isLoading)
        chrome.onProgressChanged(web, 40)
        assertEquals(0.4f, controller.progress, 0.001f)
        client.onPageFinished(web, url)
        assertFalse(controller.isLoading)
    }

    @Test fun `the web process ending makes a new web view that loads where it was`() {
        controller.lastUrl = "https://acme.sphinx.chat/lab/?app=x&input=v%3D2&solo=1"
        val before = controller.generation
        val detail = object : RenderProcessGoneDetail() {
            override fun didCrash() = true
            override fun rendererPriorityAtExit() = 0
        }
        assertTrue(client.onRenderProcessGone(web, detail))
        assertEquals(before + 1, controller.generation)
        assertNull(controller.web)
        assertEquals("https://acme.sphinx.chat/lab/?app=x&input=v%3D2&solo=1", controller.initialRequest().url)
        assertEquals("Bearer tok", controller.initialRequest().headers["Authorization"])
        // A file it was on is not reloaded with the bearer: the page's own request.
        controller.lastUrl = "https://acme.sphinx.chat/lab/jobs/j/files/a.pdf"
        assertEquals(strut.url, controller.initialRequest().url)
    }

    @Test fun `a new window goes here, out, or nowhere`() {
        web.loadUrl(strut.url)
        controller.lastTouchAt = 9_900
        // Another app of the org's, on a tap: here, with the token.
        chrome.openWindow(web, "https://acme.sphinx.chat/lab/?app=board&solo=1", isUserGesture = true)
        assertEquals("https://acme.sphinx.chat/lab/?app=board&solo=1", shadowOf(web).lastLoadedUrl)
        assertEquals("Bearer tok", shadowOf(web).lastAdditionalHttpHeaders["Authorization"])
        // A link elsewhere: out, as a link does.
        chrome.openWindow(web, "https://example.com/x", isUserGesture = true)
        assertEquals(listOf("https://example.com/x"), outside)
        // No gesture from the page (a script): nothing, even with a touch just now.
        chrome.openWindow(web, "https://example.com/y", isUserGesture = false)
        chrome.openWindow(web, "javascript:alert(1)", isUserGesture = true)
        assertEquals(listOf("https://example.com/x"), outside)
        // A stale touch: nothing.
        clock = 20_000
        chrome.openWindow(web, "https://example.com/z", isUserGesture = true)
        assertEquals(listOf("https://example.com/x"), outside)
    }

    @Test fun `reload loads strut's ui with the bearer and reloads a file in place`() {
        web.loadUrl("https://acme.sphinx.chat/lab/?app=x&input=v%3D2&solo=1")
        controller.reload()
        assertEquals("Bearer tok", shadowOf(web).lastAdditionalHttpHeaders["Authorization"])
    }

    @Test fun `the title and another app follow the address`() {
        web.loadUrl("https://acme.sphinx.chat/lab/?app=board&solo=1")
        controller.sync()
        assertTrue(controller.showsAnotherApp)
        assertEquals("Acme", strut.title(controller.showsAnotherApp))
    }

    @Test fun `closing the page removes its downloads`() {
        val folder = controller.downloads.newFolder()!!
        assertTrue(folder.exists())
        controller.close()
        assertFalse(folder.exists())
        assertNull(controller.web)
    }

    @Test fun `a new window with no transport opens nothing`() {
        // onCreateWindow with no transport opens nothing.
        assertFalse(chrome.onCreateWindow(web, false, true, Message.obtain()))
    }
}
