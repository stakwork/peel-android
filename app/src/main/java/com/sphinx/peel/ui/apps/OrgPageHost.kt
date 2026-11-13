package com.sphinx.peel.ui.apps

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Message
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
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
import com.sphinx.peel.ui.theme.Theme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/// The page host: the web view that opens a workspace's app or a stranger's public app, as iOS's `OrgPageSheet` does (see
/// `docs/phases/28-org-page-host.md`). What it decides is plain rules in `engine/OrgPage.kt` and `engine/OrgPageRules.kt`
/// (JVM-tested); this file only applies them to Android's web view.

/// What the page shows of its web view: whether it can go back, is loading (and how far), why a page did not open, a
/// download to share, a download that failed, and which app it shows. Holds the web view for the actions of its toolbar; owns
/// the folders downloads are saved in (iOS `OrgPageController`).
class OrgPageController(
    val page: PhonePage,
    val downloads: OrgDownloads,
    private val scope: CoroutineScope,
    /// The per-page profile the web view's data lives in, when this device's WebView has profiles (deleted with the page).
    val profile: String? = null,
) {
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

    class SharedFile(val file: File, val mimeType: String?)

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

    fun load(request: OrgRequest) { web?.loadUrl(request.url, request.headers) }

    fun goBack() { web?.goBack() }

    /// Loads it again where the person went (see [OrgPageRequest.reload]).
    fun reload() {
        val web = web ?: return
        problem = null
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
        load(page.retry(failedUrl, web?.url))
    }

    /// The web view's process ended (memory, a crash): a new web view loads it again, with the token, where it was when that is
    /// strut's UI (iOS `webViewWebContentProcessDidTerminate`).
    fun processGone() {
        web?.let { it.release() }
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
        if (name != null) {
            val deleted = runCatching { ProfileStore.getInstance().deleteProfile(name) }.getOrDefault(false)
            if (!deleted) wipeSharedWebData(null)
        } else {
            wipeSharedWebData(null)
        }
    }
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
        controller.isLoading = true
        controller.failedThisLoad = false
        handOverIfNeeded(view)
        controller.sync()
    }

    override fun onPageCommitVisible(view: WebView, url: String?) = handOverIfNeeded(view)

    override fun onPageFinished(view: WebView, url: String?) {
        controller.isLoading = false
        controller.reissued = 0
        if (!controller.failedThisLoad) controller.problem = null
        controller.sync()
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = controller.sync()

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        if (!request.isForMainFrame) return
        OrgPageRules.failure(errorResponse.statusCode)?.let { controller.failed(it, request.url.toString()) }
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        val why = error.description?.toString().orEmpty()
        // A load we cancelled, or turned into a download or a refusal.
        if (why.contains("ERR_ABORTED")) return
        controller.failed(why.ifEmpty { "The page did not load." }, request.url.toString())
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        controller.processGone()
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
        controller.progress = newProgress / 100f
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

/// Makes the page's web view: no data kept, the token's hand-over before the page's own script, and the policy of
/// [OrgPageClient] and [OrgPageChromeClient]. Not unit-tested (a WebView with profiles and document-start scripts needs a device).
@SuppressLint("SetJavaScriptEnabled", "RequiresFeature", "ClickableViewAccessibility")
internal fun makeOrgWebView(context: Context, controller: OrgPageController): WebView {
    val page = controller.page
    val web = WebView(context)
    // A profile of its own when this WebView has them (before anything else is done to it); else the shared data is wiped.
    if (controller.profile != null) {
        runCatching { WebViewCompat.setProfile(web, controller.profile) }
    } else {
        wipeSharedWebData(web)
    }
    web.settings.javaScriptEnabled = true
    web.settings.domStorageEnabled = true
    web.settings.allowFileAccess = false
    web.settings.allowContentAccess = false
    web.settings.javaScriptCanOpenWindowsAutomatically = false
    web.settings.setSupportMultipleWindows(true)
    web.settings.setGeolocationEnabled(false)
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
    controller.web = web
    controller.walletOffered = controller.wallet?.install(web) ?: true
    controller.documentStartScript = page.script != null && page.origin.isNotEmpty() && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
        runCatching<ScriptHandler> { WebViewCompat.addDocumentStartJavaScript(web, page.script!!, setOf(page.origin)) }.isSuccess
    val first = controller.initialRequest()
    web.loadUrl(first.url, first.headers)
    return web
}

/// A page, full screen (iOS `OrgPageSheet<Page: PhonePage>`): an org's page over Apps or a stranger's public app, the same web
/// view for both. The page's title (an org's app's own, else its workspace; a public page's host), a Back when the page can go
/// back, Reload, progress while it loads, a failure screen with Try again, Done, the app wallet's sheet and the notice a
/// connection leaves with no sheet ([PhoneWalletSheets.toast]) over it. A tap in the page counts for the wallet for
/// [StrutWallet.GESTURE_WINDOW_MS] and for a link out for [OrgNavigation.TAP_WINDOW_MS].
@Composable
fun OrgPageScreen(page: PhonePage, wallet: WalletHost, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    @Suppress("DEPRECATION")
    val sheets = remember(page) { PhoneWalletSheets(scope) { words -> view.announceForAccessibility(words) } }
    val controller = remember(page) {
        val profile = if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) "peel_org_" + UUID.randomUUID().toString().replace("-", "") else null
        OrgPageController(page, OrgDownloads(orgDownloadsRoot(context)), scope, profile).also { c ->
            c.wallet = OrgWalletBridge(page, wallet, sheets, scope) { StrutWallet.personActed(c.lastTouchAt, SystemClock.elapsedRealtime()) }
        }
    }
    BackHandler { if (controller.canGoBack) controller.goBack() else onClose() }
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
            Box(Modifier.size(40.dp).clip(CircleShape).background(Theme.colors.fieldFill), contentAlignment = Alignment.Center) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Done", tint = Theme.colors.text) }
            }
            if (controller.canGoBack) {
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { controller.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Theme.colors.text) }
            }
            Spacer(Modifier.width(12.dp))
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
            key(controller.generation) {
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
    controller.notice?.let { notice ->
        AlertDialog(
            onDismissRequest = { controller.notice = null },
            title = { Text(OrgPageRules.NOTICE_TITLE) },
            text = { Text(notice) },
            confirmButton = { TextButton(onClick = { controller.notice = null }) { Text("OK") } },
        )
    }
}
