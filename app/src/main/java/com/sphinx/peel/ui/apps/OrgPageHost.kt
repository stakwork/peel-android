package com.sphinx.peel.ui.apps

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Bitmap
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.ProfileStore
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.sphinx.peel.engine.OrgDownloads
import com.sphinx.peel.engine.OrgNavigation
import com.sphinx.peel.engine.PhonePage
import com.sphinx.peel.engine.WalletHost
import com.sphinx.peel.engine.StrutWallet
import com.sphinx.peel.ui.page.PhoneWalletSheets
import com.sphinx.peel.ui.page.WalletSheets
import com.sphinx.peel.engine.OrgPageRules
import com.sphinx.peel.engine.OrgRequest
import com.sphinx.peel.engine.PageLog
import com.sphinx.peel.engine.PageLocation
import com.sphinx.peel.engine.PageMedia
import com.sphinx.peel.engine.Seller
import com.sphinx.peel.engine.SiteLocationGrant
import com.sphinx.peel.engine.SiteLocationStore
import com.sphinx.peel.ui.theme.Theme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/// The page host: the web view that opens a workspace's app or a stranger's public app, as iOS's `OrgPageSheet` does (see
/// `docs/phases/29-org-page-host.md`). What it decides is plain rules in `engine/OrgPage.kt` and `engine/OrgPageRules.kt`
/// (JVM-tested); this file only applies them to Android's web view.

/// What the page shows of its web view: whether it can go back, is loading (and how far), why a page did not open, a
/// download to share, a download that failed, and which app it shows. Holds the web view for the actions of its toolbar; owns
/// the folders downloads are saved in (iOS `OrgPageController`).
class OrgPageController(
    val page: PhonePage,
    val downloads: OrgDownloads,
    private val scope: CoroutineScope,
    /// The per-page profile the web view's data lives in, when this device's WebView has profiles (deleted with the page).
    profile: String? = null,
) {
    /// The per-page profile, or none: none on a WebView without profiles, and none once putting a web view in it threw
    /// ([dropProfile]); a page with none has the shared web data wiped instead.
    var profile: String? = profile
        private set
    var canGoBack by mutableStateOf(false)
    var isLoading by mutableStateOf(false)
    var progress by mutableStateOf(0f)
    /// Why the page did not open, in the person's words; null when it did.
    var problem by mutableStateOf<String?>(null)
    /// A download that finished, for the share sheet.
    var shared by mutableStateOf<SharedFile?>(null)
    /// A download that failed.
    var notice by mutableStateOf<String?>(null)
    /// The page shows an app other than the one it opened with: a link in it opened that one here.
    var showsAnotherApp by mutableStateOf(false)
    /// The app wallet's handler on this page, when the host has one to give ([OrgWalletBridge]).
    var wallet: OrgWalletBridge? = null
    /// Whether this device's web view could take the wallet's bridge; a public page tells the person when it could not.
    var walletOffered by mutableStateOf(true)
    /// Counts up when the web view's process died and a new web view must be made.
    var generation by mutableIntStateOf(0)
    /// Asks the system for the permissions the app does not hold yet (the page screen's launcher: the system's own prompt), then
    /// tells `done` which the app holds. Until the screen sets it, nothing is held, so nothing is granted.
    var askSystem: (permissions: List<String>, done: (holds: (String) -> Boolean) -> Unit) -> Unit = { _, done -> done { false } }
    /// Shows the system's file picker for a page's `<input type=file>` (the page screen's launcher) and tells `done` what the person
    /// picked, null when they picked nothing; false when it cannot be shown, and then `done` is never called. None until the screen
    /// sets it.
    var chooseFiles: (params: WebChromeClient.FileChooserParams, done: (Array<Uri>?) -> Unit) -> Boolean = { _, _ -> false }

    class SharedFile(val file: File, val mimeType: String?)

    // MARK: - Location (engine/PageLocation.kt, docs/phases/42-page-host-logs.md)

    /// Where the person's answers about sites are kept; none (tests, or a host with no storage) means Allow lasts for this page only.
    var locations: SiteLocationStore? = null
    /// Whether the page is showing (the screen is started): a page that is not never gets a prompt or a position.
    var visible = true
        private set
    /// Sites allowed once, for this page session only (origin to precise); never stored.
    private val allowedOnce = mutableMapOf<String, Boolean>()
    /// Peel's own prompt, up while a page's request waits for the person.
    var locationPrompt by mutableStateOf<LocationAsk?>(null)
    /// The system refused for good (it will not ask again): the page is told no and the person is offered the phone's settings.
    var locationSettingsOffer by mutableStateOf(false)
    private var systemRefusedForGood = false
    /// Whether the system would explain itself before asking again (the screen's `shouldShowRequestPermissionRationale`); false means
    /// a refusal is for good.
    var systemRationale: (String) -> Boolean = { true }
    /// The phone's settings for this app.
    var openPhoneSettings: () -> Unit = {}
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }

    /// A question to the person: [host] wants the location; [answer] is called once with their choice, null for a dismissal.
    class LocationAsk(val host: String, val answer: (LocationAnswer?) -> Unit)
    class LocationAnswer(val allow: Boolean, val once: Boolean, val precise: Boolean)

    /// A page asked for its location ([OrgPageChromeClient.onGeolocationPermissionsShowPrompt]). [reply] is told once, yes or no; it is
    /// never told to remember (Peel's own store is the only memory).
    fun requestLocation(requestOrigin: String, reply: (Boolean) -> Unit) {
        val who = PageLocation.mayAsk(requestOrigin, page.origin, visible)
        if (who == null) {
            pageLog(PageLog.location(requestOrigin, "other", "denied", "none"))
            return reply(false)
        }
        val frame = who.frame.wire
        val stored = locations?.siteLocation(who.origin)
        val once = allowedOnce[who.origin]
        when (PageLocation.decide(stored, once != null)) {
            PageLocation.Decision.DENY -> {
                pageLog(PageLog.location(who.origin, frame, "denied", "none"))
                reply(false)
            }
            PageLocation.Decision.ALLOW -> proceedWithLocation(who, stored?.precise ?: once ?: false, reply)
            PageLocation.Decision.ASK -> {
                locationPrompt = LocationAsk(Seller.host(who.origin) ?: who.origin) { answer ->
                    locationPrompt = null
                    when {
                        answer == null -> {
                            pageLog(PageLog.location(who.origin, frame, "dismissed", "none"))
                            reply(false)
                        }
                        !answer.allow -> {
                            locations?.putSiteLocation(SiteLocationGrant(who.origin, false, false, nowSeconds()))
                            pageLog(PageLog.location(who.origin, frame, "denied", "none"))
                            reply(false)
                        }
                        else -> {
                            if (answer.once) allowedOnce[who.origin] = answer.precise
                            else locations?.putSiteLocation(SiteLocationGrant(who.origin, true, answer.precise, nowSeconds()))
                            proceedWithLocation(who, answer.precise, reply)
                        }
                    }
                }
            }
        }
    }

    /// The person allowed [who]: the system's permission (asked only when not held), then the page's answer.
    private fun proceedWithLocation(who: PageLocation.Attribution, precise: Boolean, reply: (Boolean) -> Unit) {
        val frame = who.frame.wire
        val needed = PageLocation.systemPermissions(precise)
        askSystem2(needed) { holds ->
            val granted = PageLocation.granted(holds)
            if (!granted && !systemRationale(PageLocation.COARSE)) {
                systemRefusedForGood = true
                locationSettingsOffer = true
            }
            pageLog(PageLog.location(who.origin, frame, if (granted) "allowed" else "denied", PageLocation.level(holds)))
            reply(granted)
        }
    }

    private fun askSystem2(needed: List<String>, done: (holds: (String) -> Boolean) -> Unit) {
        if (systemRefusedForGood) {
            // The system will not ask again unless the person changed it in settings: ask anyway only if it is now held.
            askSystem(emptyList()) { holds ->
                if (PageLocation.granted(holds)) { systemRefusedForGood = false; done(holds) } else { locationSettingsOffer = true; done(holds) }
            }
            return
        }
        askSystem(needed, done)
    }

    /// The page withdrew its request: Peel's prompt goes without an answer.
    fun hideLocationPrompt() {
        locationPrompt = null
    }

    /// The screen started or stopped. A page that is not showing has its web view paused (which stops its location) and any prompt
    /// withdrawn (answered no, not remembered).
    fun setVisible(showing: Boolean) {
        visible = showing
        if (showing) {
            runCatching { web?.onResume() }
        } else {
            locationPrompt?.answer?.invoke(null)
            locationPrompt = null
            runCatching { web?.onPause() }
        }
    }

    var web: WebView? = null
    /// Whether the document-start script is in place; when not, [OrgPageClient] hands the token over as the page starts.
    var documentStartScript = false
    internal var lastTouchAt: Long? = null
    /// The address the last failure was at, for Try again.
    private var failedUrl: String? = null
    /// The address the page was at, for a new web view after the old one's process ended.
    internal var lastUrl: String? = null
    /// Whether this load already failed (an HTTP error finishes the load too: it must not clear its own failure).
    internal var failedThisLoad = false
    /// Navigations loaded again with the bearer in a row, so a server redirecting to itself cannot loop.
    internal var reissued = 0

    /// What a fresh web view loads: the page, or where it was when its process ended.
    fun initialRequest(): OrgRequest = page.request(lastUrl) ?: page.request

    fun sync() {
        val web = web ?: return
        canGoBack = web.canGoBack()
        web.url?.let { lastUrl = it }
        showsAnotherApp = page.showsAnotherApp(web.url)
    }

    fun load(request: OrgRequest) {
        val web = web ?: return
        pageLog(PageLog.step("loadUrl", request.url, generation))
        web.loadUrl(request.url, request.headers)
    }

    /// Putting a web view in [profile] threw: the page goes on with none, so its data is wiped as on a WebView without profiles
    /// (on make and on close) rather than left in the shared store, and close does not delete a profile it never used.
    fun dropProfile() {
        profile = null
    }

    /// No web view to act on (its process ended and none has been made since): a new one is made, which loads where the page was.
    private fun renew(why: String) {
        pageLog("$why with no web view: a new one gen=${generation + 1}")
        generation++
    }

    fun goBack() { web?.goBack() }

    /// Loads it again where the person went (see [OrgPageRequest.reload]).
    fun reload() {
        problem = null
        val web = web ?: return renew("reload")
        when (val again = page.reload(web.url)) {
            is PhonePage.Reload.Load -> load(again.request)
            PhonePage.Reload.InPlace -> web.reload()
        }
    }

    /// The page did not open: why, and the address that failed.
    fun failed(problem: String, at: String?) {
        this.problem = problem
        failedUrl = at
        failedThisLoad = true
    }

    /// Loads again what failed, with the page's bearer when that was strut's UI.
    fun retry() {
        problem = null
        val web = web ?: return renew("retry")
        load(page.retry(failedUrl, web.url))
    }

    /// [gone]'s process ended (memory, a crash): it is destroyed (the web view must not be used again), and when it is the one this
    /// page shows, a new web view loads it again, with the token, where it was when that is strut's UI (iOS
    /// `webViewWebContentProcessDidTerminate`). One this page no longer holds goes alone: it must not take the page's own with it.
    fun processGone(gone: WebView) {
        gone.release()
        if (gone !== web) return
        web = null
        generation++
    }

    /// A page's own download of strut's own file: saved in a folder of its own and handed to the share sheet.
    fun download(url: String, userAgent: String?, contentDisposition: String?) {
        if (!OrgPageRules.mayDownload(isMainFrame = true, sourceIsMainFrame = true, served = page.serves(url))) return
        // Only what the navigation carried (iOS WKDownload replays it): no bearer, the web view's own cookies for the address.
        val headers = buildMap {
            userAgent?.takeIf { it.isNotEmpty() }?.let { put("User-Agent", it) }
            runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { put("Cookie", it) }
        }
        scope.launch {
            when (val saved = downloads.save(url, headers, contentDisposition) { page.serves(it) }) {
                is OrgDownloads.Result.Saved -> shared = SharedFile(saved.file, saved.mimeType)
                OrgDownloads.Result.NoRoom -> notice = OrgPageRules.NO_ROOM
                OrgDownloads.Result.Failed -> notice = OrgPageRules.DOWNLOAD_FAILED
            }
        }
    }

    /// The page is gone: nothing is kept. The web view goes first, then its profile (a profile in use cannot be deleted); a
    /// device without profiles has its shared web data wiped instead.
    fun close() {
        web?.let { it.release() }
        web = null
        downloads.cleanUp()
        val name = profile
        if (name != null && name == page.keptProfile) {
            // A public site's profile is kept for its next open: its visitor stays signed in (Forget this account deletes it).
            pageLog("close profile=kept gen=$generation")
        } else if (name != null) {
            val deleted = runCatching { ProfileStore.getInstance().deleteProfile(name) }.getOrDefault(false)
            pageLog("close profileDeleted=$deleted gen=$generation")
            if (!deleted) wipeSharedWebData(null)
        } else {
            pageLog("close profile=none gen=$generation")
            wipeSharedWebData(null)
        }
    }
}

/// Forget this account: every public site's kept profile is emptied, then deleted (iOS `PhoneWeb.forgetWebsiteData`). A profile
/// still in use refuses the delete; it is left emptied, with nothing in it to sign anyone in. On the main thread, as WebView asks.
@SuppressLint("RequiresFeature")
fun forgetKeptWebData() {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
    val store = runCatching { ProfileStore.getInstance() }.getOrNull() ?: return
    for (name in store.allProfileNames.filter { it.startsWith(com.sphinx.peel.engine.PublicPage.KEPT_PROFILE_PREFIX) }) {
        runCatching {
            store.getProfile(name)?.let { kept ->
                kept.cookieManager.removeAllCookies(null)
                kept.webStorage.deleteAllData()
            }
        }
        runCatching { store.deleteProfile(name) }
    }
}

/// One line to logcat under [PageLog.TAG]; built by [PageLog] only, so it never carries more than an origin (see there).
internal fun pageLog(line: String) {
    Log.i(PageLog.TAG, line)
}

private fun WebView.release() {
    stopLoading()
    (parent as? android.view.ViewGroup)?.removeView(this)
    destroy()
}

/// Clears what a visit leaves in the app's shared web data: cookies, web storage, cache and history (nothing of the person's
/// is kept there: Apps sends its token in a header and in the page's own `sessionStorage`).
internal fun wipeSharedWebData(web: WebView?) {
    runCatching {
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        web?.clearCache(true)
        web?.clearHistory()
    }
}

/// The page's web view client (iOS `OrgWebView.Coordinator`): where it may go, its failures, and the token's hand-over when the
/// document-start hook is not there.
internal class OrgPageClient(
    private val controller: OrgPageController,
    private val openOutside: (String) -> Unit,
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) : WebViewClient() {
    private val page get() = controller.page

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        val decision = OrgNavigation.decide(page, url, request.isForMainFrame, request.hasGesture(), controller.lastTouchAt, now())
        if (decision.openOutside) openOutside(url)
        if (!decision.load) return true
        // strut's UI again in this folder: loaded with the bearer, as a redirect or a link drops headers. Files are not.
        if (request.isForMainFrame && request.method.equals("GET", ignoreCase = true) && controller.reissued < MAX_REISSUES) {
            // Only a page with a token has anything to add: a public page's navigation loads as it is.
            page.request(at = url)?.takeIf { it.headers.isNotEmpty() }?.let {
                controller.reissued++
                view.loadUrl(it.url, it.headers)
                return true
            }
        }
        return false
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        pageLog(PageLog.step("pageStarted", url, controller.generation))
        controller.isLoading = true
        controller.failedThisLoad = false
        handOverIfNeeded(view)
        controller.sync()
    }

    override fun onPageCommitVisible(view: WebView, url: String?) {
        pageLog(PageLog.step("commitVisible", url, controller.generation))
        handOverIfNeeded(view)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        pageLog(PageLog.step("pageFinished", url, controller.generation))
        controller.isLoading = false
        controller.reissued = 0
        if (!controller.failedThisLoad) controller.problem = null
        controller.sync()
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = controller.sync()

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        if (!request.isForMainFrame) return
        pageLog(PageLog.httpError(request.url.toString(), errorResponse.statusCode))
        OrgPageRules.failure(errorResponse.statusCode)?.let { controller.failed(it, request.url.toString()) }
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        pageLog(PageLog.error(request.url.toString(), error.errorCode, error.description))
        val why = error.description?.toString().orEmpty()
        // A load we cancelled, or turned into a download or a refusal.
        if (why.contains("ERR_ABORTED")) return
        controller.failed(why.ifEmpty { "The page did not load." }, request.url.toString())
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        pageLog(PageLog.renderGone(detail.didCrash(), detail.rendererPriorityAtExit(), current = view === controller.web))
        controller.processGone(view)
        return true
    }

    /// Without `DOCUMENT_START_SCRIPT` the hand-over runs as the page starts and again as it commits: before its own script
    /// when the page is slow enough, not guaranteed (see the phase doc). The script checks the origin and folder itself.
    private fun handOverIfNeeded(view: WebView) {
        page.script?.let { if (!controller.documentStartScript) view.evaluateJavascript(it, null) }
    }

    private companion object {
        const val MAX_REISSUES = 5
    }
}

/// `target=_blank` and `window.open`: never a window (iOS `createWebViewWith`). The new window is a web view that cannot load
/// anything, only say where it was sent; [OrgNavigation.window] decides what that does.
internal class OrgPageChromeClient(
    private val controller: OrgPageController,
    private val openOutside: (String) -> Unit,
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) : WebChromeClient() {
    override fun onProgressChanged(view: WebView, newProgress: Int) {
        pageLog("progress $newProgress gen=${controller.generation}")
        controller.progress = newProgress / 100f
    }

    /// No picture for a video that has none of its own: WebView's default is a big grey play button, drawn stretched over strut's
    /// camera sheet until the camera's first frame (docs/phases/42-page-host-logs.md). A transparent pixel instead, so the sheet is
    /// dark until then, as on iOS.
    override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    /// The camera or microphone (strut's camera sheet; iOS `requestMediaCapturePermissionFor`): for the origin Peel loaded, after
    /// the system's own permission, which is asked only when the app does not hold it yet; anything else is denied ([PageMedia]).
    override fun onPermissionRequest(request: PermissionRequest) {
        val origin = request.origin?.toString().orEmpty()
        val asked = request.resources?.toList().orEmpty()
        val grantable = PageMedia.grantable(origin, controller.page.origin, asked)
        if (grantable.isEmpty()) {
            pageLog(PageLog.permission(origin, asked, emptyList()))
            runCatching { request.deny() }
            return
        }
        controller.askSystem(PageMedia.systemPermissions(grantable)) { holds ->
            val granted = PageMedia.granted(grantable, holds)
            pageLog(PageLog.permission(origin, asked, granted))
            // A request the page withdrew meanwhile may refuse an answer.
            runCatching { if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray()) }
        }
    }

    /// Location (engine/PageLocation.kt): Peel's own prompt first, its own store second, the system's permission last; WebView is never told
    /// to remember, and Peel never reads a position.
    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
        controller.requestLocation(origin) { granted -> runCatching { callback.invoke(origin, granted, false) } }
    }

    override fun onGeolocationPermissionsHidePrompt() = controller.hideLocationPrompt()

    /// A page's `<input type=file>` ("or choose one from this device"): the system's file picker, as WebKit shows its own on iOS. The
    /// page gets only what the person picks there; WebView is told exactly once, null when they picked nothing.
    override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
        val shown = controller.chooseFiles(params) { picked ->
            pageLog("files picked=${picked?.size ?: 0} gen=${controller.generation}")
            callback.onReceiveValue(picked)
        }
        pageLog("fileChooser accept=${params.acceptTypes?.filter { it.isNotBlank() }?.joinToString(",")?.ifEmpty { null } ?: "-"} multiple=${params.mode == FileChooserParams.MODE_OPEN_MULTIPLE} shown=$shown")
        return shown
    }

    /// The page's console, redacted ([PageLog.console]); true, so the WebView does not also log it as it was written.
    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        pageLog(PageLog.console(message.messageLevel().name, message.message(), message.sourceId(), message.lineNumber()))
        return true
    }

    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        val temp = WebView(view.context)
        temp.settings.javaScriptEnabled = false
        temp.settings.blockNetworkLoads = true
        controller.profile?.let { runCatching { WebViewCompat.setProfile(temp, it) } }
        var taken = false
        fun take(url: String?) {
            if (taken || url == null) return
            taken = true
            openWindow(view, url, isUserGesture)
            view.post { temp.stopLoading(); temp.destroy() }
        }
        temp.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean { take(request.url.toString()); return true }
            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) = take(url)
            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) = take(request.url.toString())
        }
        transport.webView = temp
        resultMsg.sendToTarget()
        return true
    }

    /// Where a new window was sent: here (another app of the org's, or one of its files), out to the system browser, or nowhere.
    fun openWindow(view: WebView, url: String, isUserGesture: Boolean) {
        val acted = isUserGesture && OrgNavigation.personActed(controller.lastTouchAt, now())
        when (val action = OrgNavigation.window(controller.page, url, view.url, acted)) {
            OrgNavigation.Window.Nothing -> Unit
            is OrgNavigation.Window.Here -> action.request?.let { controller.load(it) } ?: view.loadUrl(action.url)
            is OrgNavigation.Window.Outside -> openOutside(action.url)
        }
    }
}

/// What the system's file picker returned: every file picked (several come as clip data), or null for none or a cancel.
internal fun pickedFiles(resultCode: Int, data: Intent?): Array<Uri>? {
    if (resultCode != Activity.RESULT_OK || data == null) return null
    val clip = data.clipData
    val picked = if (clip != null && clip.itemCount > 0) List(clip.itemCount) { clip.getItemAt(it).uri } else listOfNotNull(data.data)
    return picked.filterNotNull().toTypedArray().takeIf { it.isNotEmpty() }
}

/// Where downloads wait: each in a folder of its own (iOS `Peel Org Downloads`); shared through `res/xml/file_paths.xml`.
internal fun orgDownloadsRoot(context: Context) = File(context.cacheDir, "org-downloads")

private fun share(context: Context, file: OrgPageController.SharedFile) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file.file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = file.mimeType ?: "*/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(file.file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // Nothing to share it with.
    }
}

/// Puts the page's web view in the page's profile ([setProfile] is `WebViewCompat.setProfile` on it): "on"; "none" when the page
/// has none; or what it threw, after which the page has none ([OrgPageController.dropProfile]: its data is wiped instead of kept
/// in the shared store with no wipe, which is what a swallowed failure used to leave).
internal fun useProfile(controller: OrgPageController, setProfile: (String) -> Unit): String {
    val name = controller.profile ?: return "none"
    return runCatching { setProfile(name) }.fold({ "on" }, { controller.dropProfile(); PageLog.threw(it) })
}

/// Makes the page's web view: no data kept, the token's hand-over before the page's own script, and the policy of
/// [OrgPageClient] and [OrgPageChromeClient]. Writes what it made to [PageLog] (features, profile, scripts, then each step).
/// Not unit-tested (a WebView with profiles and document-start scripts needs a device).
@SuppressLint("SetJavaScriptEnabled", "RequiresFeature", "ClickableViewAccessibility")
internal fun makeOrgWebView(context: Context, controller: OrgPageController): WebView {
    val page = controller.page
    val generation = controller.generation
    val web = WebView(context)
    // The page's own size, not its content's: a web view whose layout height is WRAP_CONTENT (what AndroidView gives a view with
    // no layout params) lays the page out on a zero-height viewport, so `100vh` is 0 and a page sized by it (strut's solo app)
    // shows nothing at all (docs/phases/42-page-host-logs.md).
    web.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    // A profile of its own when this WebView has them (before anything else is done to it); else, or when that threw, the shared
    // data is wiped.
    val profile = useProfile(controller) { WebViewCompat.setProfile(web, it) }
    if (controller.profile == null) wipeSharedWebData(web)
    web.settings.javaScriptEnabled = true
    web.settings.domStorageEnabled = true
    web.settings.allowFileAccess = false
    web.settings.allowContentAccess = false
    web.settings.javaScriptCanOpenWindowsAutomatically = false
    web.settings.setSupportMultipleWindows(true)
    // On, but nothing is given without the person's yes ([OrgPageChromeClient.onGeolocationPermissionsShowPrompt]).
    web.settings.setGeolocationEnabled(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
    web.setOnTouchListener { _, event ->
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) {
            controller.lastTouchAt = SystemClock.elapsedRealtime()
        }
        false
    }
    val outside = { url: String -> openInBrowser(context, url) }
    web.webViewClient = OrgPageClient(controller, outside)
    web.webChromeClient = OrgPageChromeClient(controller, outside)
    web.setDownloadListener { url, userAgent, disposition, _, _ -> controller.download(url, userAgent, disposition) }
    // Whether it is in the window, and at what size: a web view that is not is the other way a page shows nothing.
    web.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = pageLog("attached gen=$generation")
        override fun onViewDetachedFromWindow(v: View) = pageLog("detached gen=$generation")
    })
    web.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) pageLog("size ${right - left}x${bottom - top} gen=$generation")
    }
    controller.web = web
    // A bridge that throws is a page without the wallet (its notice says so), not a page that never opens.
    var wallet = "none"
    controller.walletOffered = controller.wallet?.let { bridge ->
        runCatching { bridge.install(web) }
            .onSuccess { wallet = if (it) "installed" else "unsupported" }
            .onFailure { wallet = PageLog.threw(it) }
            .getOrDefault(false)
    } ?: true
    val documentStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    val tokenScript = when {
        page.script == null -> "none"
        page.origin.isEmpty() || !documentStart -> "atPageStart"
        else -> runCatching<ScriptHandler> { WebViewCompat.addDocumentStartJavaScript(web, page.script!!, setOf(page.origin)) }
            .fold({ "documentStart" }, { PageLog.threw(it) })
    }
    controller.documentStartScript = tokenScript == "documentStart"
    val webView = runCatching { WebViewCompat.getCurrentWebViewPackage(context)?.let { "${it.packageName}/${it.versionName}" } }.getOrNull()
    pageLog(
        PageLog.made(
            generation, webView, WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE), documentStart,
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER),
            profile, tokenScript, wallet,
        ),
    )
    val first = controller.initialRequest()
    pageLog(PageLog.step("loadUrl", first.url, generation))
    web.loadUrl(first.url, first.headers)
    return web
}

/// A page, full screen (iOS `OrgPageSheet<Page: PhonePage>`): an org's page over Apps or a stranger's public app, the same web
/// view for both. The page's title (an org's app's own, else its workspace; a public page's host), a Back when the page can go
/// back, Reload, progress while it loads, a failure screen with Try again, Done, the app wallet's sheet and the notice a
/// connection leaves with no sheet ([PhoneWalletSheets.toast]) over it. A tap in the page counts for the wallet for
/// [StrutWallet.GESTURE_WINDOW_MS] and for a link out for [OrgNavigation.TAP_WINDOW_MS]. [sheets] is the person's side of the wallet, made here
/// unless a test brings its own (closing the page, which a lock does, answers whatever it waits on as a no).
///
/// The Tasks tab (docs/phases/48-tasks-tab.md) holds a page too: [onClose] null is a page that is a tab (no Done, and Back at its first
/// address is not the page's), and [shown] false is that page kept alive behind another tab (`KeptPage` does not place it): it is
/// treated as a page not showing (paused, no prompt, no Back) and gives up the keyboard.
@Composable
fun OrgPageScreen(
    page: PhonePage, wallet: WalletHost, onClose: (() -> Unit)?, modifier: Modifier = Modifier, sheets: PhoneWalletSheets? = null,
    locations: SiteLocationStore? = null, shown: Boolean = true,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    @Suppress("DEPRECATION")
    val sheets = remember(page) { sheets ?: PhoneWalletSheets(scope) { words -> view.announceForAccessibility(words) } }
    val controller = remember(page) {
        // A public site's own kept profile, else one for this open alone; none without profiles (the shared data is wiped instead).
        val profile = if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            page.keptProfile ?: ("peel_org_" + UUID.randomUUID().toString().replace("-", ""))
        } else {
            null
        }
        OrgPageController(page, OrgDownloads(orgDownloadsRoot(context)), scope, profile).also { c ->
            c.locations = locations
            c.wallet = OrgWalletBridge(page, wallet, sheets, scope) { StrutWallet.personActed(c.lastTouchAt, SystemClock.elapsedRealtime()) }
        }
    }
    // The system's own prompt for what a page's camera or microphone needs ([OrgPageChromeClient.onPermissionRequest]); what waits
    // for its answer is told once it comes.
    val holds = { permission: String -> ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED }
    val waiting = remember(controller) { mutableListOf<((String) -> Boolean) -> Unit>() }
    val systemPrompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val answered = waiting.toList()
        waiting.clear()
        answered.forEach { it(holds) }
    }
    // The system's file picker for a page's `<input type=file>` ([OrgPageChromeClient.onShowFileChooser]); one at a time.
    val choosing = remember(controller) { arrayOfNulls<(Array<Uri>?) -> Unit>(1) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val done = choosing[0]
        choosing[0] = null
        done?.invoke(pickedFiles(result.resultCode, result.data))
    }
    DisposableEffect(controller, filePicker) {
        controller.chooseFiles = { params, done ->
            choosing[0]?.invoke(null)
            choosing[0] = null
            val intent = runCatching { params.createIntent() }.getOrNull()
            intent != null && runCatching { choosing[0] = done; filePicker.launch(intent) }.onFailure { choosing[0] = null }.isSuccess
        }
        onDispose {
            choosing[0]?.invoke(null)
            choosing[0] = null
            controller.chooseFiles = { _, _ -> false }
        }
    }
    DisposableEffect(controller, systemPrompt) {
        controller.askSystem = { permissions, done ->
            val missing = permissions.filterNot(holds)
            if (missing.isEmpty()) {
                done(holds)
            } else {
                waiting += done
                systemPrompt.launch(missing.toTypedArray())
            }
        }
        onDispose { controller.askSystem = { _, done -> done { false } } }
    }
    // A page that is not showing is paused (WebView stops its location then) and gets no prompt ([OrgPageController.setVisible]):
    // Peel not started, or (a tab's page) another tab in front.
    // The stop is told at once, from the observer: a stopped activity draws no frames, so an effect would wait for the start.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val shownNow by androidx.compose.runtime.rememberUpdatedState(shown)
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(controller, shown) {
        controller.setVisible(shown && lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
        if (!shown) {
            controller.web?.clearFocus()
            keyboard?.hide()
        }
    }
    DisposableEffect(controller, lifecycleOwner) {
        val activity = context.findActivity()
        controller.systemRationale = { permission -> activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } ?: true }
        controller.openPhoneSettings = {
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        controller.setVisible(shownNow && lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> controller.setVisible(false)
                androidx.lifecycle.Lifecycle.Event.ON_START -> controller.setVisible(shownNow)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler(enabled = shown && (controller.canGoBack || onClose != null)) { if (controller.canGoBack) controller.goBack() else onClose?.invoke() }
    // The notifications the wallet host has for this page's scope go into its main frame (iOS `listenToWallet`).
    LaunchedEffect(controller) { wallet.notifications.collect { controller.wallet?.push(it) } }
    DisposableEffect(controller) {
        onDispose {
            sheets.close()
            controller.close()
        }
    }
    controller.shared?.let { file ->
        LaunchedEffect(file) {
            share(context, file)
            controller.shared = null
        }
    }

    Column(modifier.fillMaxSize().background(Theme.colors.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onClose != null) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Theme.colors.fieldFill), contentAlignment = Alignment.Center) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Done", tint = Theme.colors.text) }
                }
            }
            if (controller.canGoBack) {
                if (onClose != null) Spacer(Modifier.width(8.dp))
                IconButton(onClick = { controller.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Theme.colors.text) }
            }
            Spacer(Modifier.width(if (onClose == null && !controller.canGoBack) 4.dp else 12.dp))
            Text(
                page.title(controller.showsAnotherApp), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Theme.colors.text,
            )
            IconButton(onClick = { controller.reload() }) { Icon(Icons.Filled.Refresh, "Reload", tint = Theme.colors.secondaryText) }
        }
        if (!controller.walletOffered && page.walletScope.visitorHost != null) {
            Text(
                "This device's web view can't offer Sign in with Sphinx.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // A new web view for a new page (another controller) as for a new process (another generation): keyed on the generation
            // alone, the next page kept the last page's AndroidView, whose web view that page's close had just destroyed, and
            // showed nothing (docs/phases/42-page-host-logs.md).
            key(controller, controller.generation) {
                AndroidView(factory = { makeOrgWebView(it, controller) }, modifier = Modifier.fillMaxSize())
            }
            if (controller.isLoading) {
                LinearProgressIndicator(progress = { controller.progress }, modifier = Modifier.fillMaxWidth())
            }
            sheets.toast?.let { words ->
                Surface(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp), shape = CircleShape,
                    color = Theme.colors.fieldFill, tonalElevation = 4.dp,
                ) {
                    Text(words, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = Theme.colors.text)
                }
            }
            controller.problem?.let { problem ->
                Box(Modifier.fillMaxSize().background(Theme.colors.background).padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Filled.Warning, null, tint = Theme.colors.secondaryText)
                        Text(OrgPageRules.FAILURE_TITLE, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Theme.colors.text)
                        Text(problem, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText)
                        Button(onClick = { controller.retry() }) { Text(OrgPageRules.TRY_AGAIN) }
                    }
                }
            }
        }
    }
    WalletSheets(sheets)
    controller.locationPrompt?.let { ask -> LocationPromptSheet(ask) }
    if (controller.locationSettingsOffer) {
        AlertDialog(
            onDismissRequest = { controller.locationSettingsOffer = false },
            title = { Text("Location is off for Peel") },
            text = { Text(PageLocation.SETTINGS_NOTE) },
            confirmButton = { TextButton(onClick = { controller.locationSettingsOffer = false; controller.openPhoneSettings() }) { Text(PageLocation.OPEN_SETTINGS) } },
            dismissButton = { TextButton(onClick = { controller.locationSettingsOffer = false }) { Text("Not now") } },
        )
    }
    controller.notice?.let { notice ->
        AlertDialog(
            onDismissRequest = { controller.notice = null },
            title = { Text(OrgPageRules.NOTICE_TITLE) },
            text = { Text(notice) },
            confirmButton = { TextButton(onClick = { controller.notice = null }) { Text("OK") } },
        )
    }
}

/// The activity behind a context (a Compose context may be wrapped).
private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
