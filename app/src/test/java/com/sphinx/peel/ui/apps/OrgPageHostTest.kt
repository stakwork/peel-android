package com.sphinx.peel.ui.apps

import android.app.Application
import android.net.Uri
import android.os.Message
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.sphinx.peel.engine.OrgDownloads
import com.sphinx.peel.engine.OrgPageRequest
import com.sphinx.peel.engine.PageLog
import com.sphinx.peel.engine.PageLocation
import com.sphinx.peel.engine.PageMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog

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

    /// A gone renderer reported by a web view this page no longer holds is that one's alone (docs/phases/42-page-host-logs.md).
    @Test fun `a web view the page no longer holds going does not take the page's own`() {
        val stale = WebView(app)
        val before = controller.generation
        val detail = object : RenderProcessGoneDetail() {
            override fun didCrash() = false
            override fun rendererPriorityAtExit() = 0
        }
        assertTrue(client.onRenderProcessGone(stale, detail))
        assertSame(web, controller.web)
        assertEquals(before, controller.generation)
    }

    /// What the page's steps write to logcat: the origin and numbers, never the app, the folder or the token.
    @Test fun `the client logs each step by its origin alone`() {
        val url = "https://acme.sphinx.chat/lab/?app=x&solo=1"
        val before = ShadowLog.getLogsForTag(PageLog.TAG).size
        client.onPageStarted(web, url, null)
        client.onReceivedHttpError(web, Req(url), WebResourceResponse("text/html", "utf-8", 500, "Error", emptyMap(), null))
        client.onPageFinished(web, url)
        chrome.onProgressChanged(web, 100)
        val lines = ShadowLog.getLogsForTag(PageLog.TAG).drop(before).map { it.msg }
        assertEquals(
            listOf(
                "pageStarted https://acme.sphinx.chat gen=0",
                "httpError https://acme.sphinx.chat status=500",
                "pageFinished https://acme.sphinx.chat gen=0",
                "progress 100 gen=0",
            ),
            lines,
        )
    }

    private class Asked(private val from: String, private val what: Array<String>) : PermissionRequest() {
        var granted: List<String>? = null
        var denied = false
        override fun getOrigin(): Uri = Uri.parse(from)
        override fun getResources(): Array<String> = what
        override fun grant(resources: Array<String>) { granted = resources.toList() }
        override fun deny() { denied = true }
    }

    /// strut's camera sheet (iOS `requestMediaCapturePermissionFor`): the page's own origin gets the camera once the system's
    /// permission is held, asked through the screen's prompt only for what is missing (docs/phases/42-page-host-logs.md).
    @Test fun `the page's own origin gets the camera after the system's permission`() {
        val askedSystem = mutableListOf<List<String>>()
        var holdsCamera = true
        controller.askSystem = { permissions, done -> askedSystem += permissions; done { it == PageMedia.CAMERA && holdsCamera } }
        val camera = Asked("https://acme.sphinx.chat/", arrayOf(PageMedia.VIDEO))
        chrome.onPermissionRequest(camera)
        assertEquals(listOf(listOf(PageMedia.CAMERA)), askedSystem)
        assertEquals(listOf(PageMedia.VIDEO), camera.granted)

        // The person said no to the system: the page is told no.
        holdsCamera = false
        val refused = Asked("https://acme.sphinx.chat/", arrayOf(PageMedia.VIDEO))
        chrome.onPermissionRequest(refused)
        assertTrue(refused.denied)
        assertNull(refused.granted)
    }

    @Test fun `another origin, a sandboxed frame or anything but the camera and microphone is denied without asking`() {
        val askedSystem = mutableListOf<List<String>>()
        controller.askSystem = { permissions, done -> askedSystem += permissions; done { true } }
        for (request in listOf(
            Asked("https://evil.example.com/", arrayOf(PageMedia.VIDEO)),
            Asked("null", arrayOf(PageMedia.VIDEO)),
            Asked("https://acme.sphinx.chat/", arrayOf("android.webkit.resource.PROTECTED_MEDIA_ID")),
        )) {
            chrome.onPermissionRequest(request)
            assertTrue(request.denied)
            assertNull(request.granted)
        }
        assertEquals(emptyList<List<String>>(), askedSystem)
    }

    private class Geo : android.webkit.GeolocationPermissions.Callback {
        val calls = mutableListOf<Triple<String, Boolean, Boolean>>()
        override fun invoke(origin: String, allow: Boolean, retain: Boolean) { calls += Triple(origin, allow, retain) }
    }

    private class MemoryLocations : com.sphinx.peel.engine.SiteLocationStore {
        val grants = mutableMapOf<String, com.sphinx.peel.engine.SiteLocationGrant>()
        override fun siteLocation(origin: String) = grants[origin]
        override fun putSiteLocation(grant: com.sphinx.peel.engine.SiteLocationGrant) { grants[grant.origin] = grant }
    }

    /// Location: Peel's prompt, then its store, then the system; WebView is never told to remember.
    @Test fun `a page's location waits for Peel's prompt, is stored, and is never retained`() {
        val store = MemoryLocations().also { controller.locations = it }
        val askedSystem = mutableListOf<List<String>>()
        controller.askSystem = { permissions, done -> askedSystem += permissions; done { true } }
        val first = Geo()
        chrome.onGeolocationPermissionsShowPrompt("https://acme.sphinx.chat", first)
        assertEquals(emptyList<Any>(), first.calls)
        controller.locationPrompt!!.answer(OrgPageController.LocationAnswer(allow = true, once = false, precise = true))
        assertEquals(listOf(Triple("https://acme.sphinx.chat", true, false)), first.calls)
        assertEquals(listOf(listOf(PageLocation.COARSE, PageLocation.FINE)), askedSystem)
        assertTrue(store.grants["https://acme.sphinx.chat"]!!.precise)
        // Now stored: no prompt.
        val second = Geo()
        chrome.onGeolocationPermissionsShowPrompt("null", second)
        assertNull(controller.locationPrompt)
        assertEquals(listOf(Triple("null", true, false)), second.calls)
    }

    @Test fun `a refused, other-origin or hidden page is denied, allow once is not stored`() {
        val store = MemoryLocations().also { controller.locations = it }
        controller.askSystem = { _, done -> done { true } }
        val other = Geo()
        chrome.onGeolocationPermissionsShowPrompt("https://evil.example.com", other)
        assertEquals(listOf(Triple("https://evil.example.com", false, false)), other.calls)
        assertNull(controller.locationPrompt)

        val once = Geo()
        chrome.onGeolocationPermissionsShowPrompt("https://acme.sphinx.chat", once)
        controller.locationPrompt!!.answer(OrgPageController.LocationAnswer(allow = true, once = true, precise = false))
        assertTrue(once.calls.single().second)
        assertTrue(store.grants.isEmpty())

        val no = Geo()
        store.putSiteLocation(com.sphinx.peel.engine.SiteLocationGrant("https://acme.sphinx.chat", false, false, 1))
        chrome.onGeolocationPermissionsShowPrompt("https://acme.sphinx.chat", no)
        assertEquals(listOf(Triple("https://acme.sphinx.chat", false, false)), no.calls)
        assertNull(controller.locationPrompt)

        controller.setVisible(false)
        val hidden = Geo()
        chrome.onGeolocationPermissionsShowPrompt("https://acme.sphinx.chat", hidden)
        assertEquals(false, hidden.calls.single().second)
    }

    /// strut's camera sheet is a video with no poster until the first frame: WebView's own default (a big play button) is not drawn.
    @Test fun `a video with no poster shows a transparent pixel, not a play button`() {
        val poster = chrome.defaultVideoPoster!!
        assertEquals(1, poster.width)
        assertEquals(1, poster.height)
        assertEquals(0, android.graphics.Color.alpha(poster.getPixel(0, 0)))
    }

    private class Params(private val accept: Array<String>, private val multiple: Boolean = false) : WebChromeClient.FileChooserParams() {
        override fun getMode() = if (multiple) MODE_OPEN_MULTIPLE else MODE_OPEN
        override fun getAcceptTypes() = accept
        override fun isCaptureEnabled() = false
        override fun getTitle(): CharSequence? = null
        override fun getFilenameHint(): String? = null
        override fun createIntent() = Intent(Intent.ACTION_GET_CONTENT).setType(accept.firstOrNull() ?: "*/*")
    }

    /// A page's `<input type=file>` ("or choose one from this device"): the system's picker, and the page gets what was picked,
    /// told once; nothing shown and nothing told until the screen can show it (docs/phases/42-page-host-logs.md).
    @Test fun `a file input shows the system's picker and the page gets what was picked`() {
        val told = mutableListOf<List<Uri>?>()
        val callback = ValueCallback<Array<Uri>> { told += it?.toList() }
        assertFalse(chrome.onShowFileChooser(web, callback, Params(arrayOf("image/*"))))
        assertEquals(emptyList<List<Uri>?>(), told)

        val photo = Uri.parse("content://media/external/images/1")
        var shownFor: WebChromeClient.FileChooserParams? = null
        controller.chooseFiles = { params, done -> shownFor = params; done(arrayOf(photo)); true }
        assertTrue(chrome.onShowFileChooser(web, callback, Params(arrayOf("image/*"))))
        assertEquals(listOf("image/*"), shownFor!!.acceptTypes.toList())
        assertEquals(listOf(listOf(photo)), told)

        controller.chooseFiles = { _, done -> done(null); true }
        chrome.onShowFileChooser(web, callback, Params(arrayOf("image/*")))
        assertEquals(listOf(listOf(photo), null), told)
    }

    @Test fun `what the picker returned is every file picked, or none`() {
        val a = Uri.parse("content://docs/a")
        val b = Uri.parse("content://docs/b")
        assertEquals(listOf(a), pickedFiles(Activity.RESULT_OK, Intent().setData(a))!!.toList())
        val two = Intent().apply { clipData = ClipData.newRawUri(null, a).apply { addItem(ClipData.Item(b)) } }
        assertEquals(listOf(a, b), pickedFiles(Activity.RESULT_OK, two)!!.toList())
        assertNull(pickedFiles(Activity.RESULT_CANCELED, Intent().setData(a)))
        assertNull(pickedFiles(Activity.RESULT_OK, null))
        assertNull(pickedFiles(Activity.RESULT_OK, Intent()))
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
