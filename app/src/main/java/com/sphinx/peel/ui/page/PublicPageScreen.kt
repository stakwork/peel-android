package com.sphinx.peel.ui.page

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.PublicAppLink
import com.sphinx.peel.engine.Seller
import com.sphinx.peel.engine.StorageWalletConnections
import com.sphinx.peel.engine.WalletCaller
import com.sphinx.peel.engine.WalletError
import com.sphinx.peel.engine.WalletErrors
import com.sphinx.peel.engine.WalletHost
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.theme.Theme
import com.sphinx.peel.ui.toast.LocalToast
import com.sphinx.peel.ui.toast.ToastType
import kotlinx.coroutines.launch
import org.json.JSONObject

/// How long a tap in the page counts as the person acting (iOS `StrutWallet.gestureWindow`).
const val GESTURE_WINDOW_MS = 5_000L

/// How soon after a touch a link to another site counts as one the person tapped (iOS: within 1 second).
const val LINK_TAP_WINDOW_MS = 1_000L

/// Peel's answer to the page's `window.webkit.messageHandlers.strutWallet.postMessage(body)`, as a promise. The page
/// is a stranger's strut app whose UI looks for iOS's handler, so Android offers the same object, backed by the
/// `peelWallet` message listener (`addWebMessageListener`, which tells Peel the frame and origin of each message).
/// Each call is `{id, body}`; the answer comes back as `{id, reply}`, `reply` being the wallet's `{result}`/`{error}`
/// (errors as [WalletErrors.body] builds them). `postMessage` resolves to the parsed reply, as strut reads it.
internal const val BRIDGE_NAME = "peelWallet"
internal val WALLET_SHIM = """
(function () {
  if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.strutWallet) return;
  var pending = {};
  var next = 1;
  var listening = false;
  function fail(message) { return { error: { code: -32000, message: message, data: { code: 'INTERNAL' } } }; }
  var handler = {
    postMessage: function (body) {
      return new Promise(function (resolve) {
        var bridge = window.$BRIDGE_NAME;
        if (!bridge) { resolve(fail("Peel's wallet is not available here.")); return; }
        if (!listening) {
          listening = true;
          bridge.onmessage = function (event) {
            try {
              var answer = JSON.parse(event.data);
              var done = pending[answer.id];
              if (done) { delete pending[answer.id]; done(answer.reply); }
            } catch (e) {}
          };
        }
        var id = next++;
        pending[id] = resolve;
        try { bridge.postMessage(JSON.stringify({ id: id, body: body })); }
        catch (e) { delete pending[id]; resolve(fail("Peel's wallet could not be reached.")); }
      });
    }
  };
  window.webkit = window.webkit || {};
  window.webkit.messageHandlers = window.webkit.messageHandlers || {};
  window.webkit.messageHandlers.strutWallet = handler;
})();
""".trimIndent()

/// A stranger's public strut app in a web view (iOS `PublicPageView`): the address loaded as given, no cookies or
/// storage of the person's (and none kept), navigation kept to the origin Peel loaded, and the wallet bridge for
/// that origin's main frame only. See `docs/phases/27-sign-in-with-sphinx.md`.
@SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
@Composable
fun PublicPageScreen(link: PublicAppLink, engine: Engine, storage: Storage, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val toast = LocalToast.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var web by remember { mutableStateOf<WebView?>(null) }
    var lastTouch by remember { mutableStateOf(0L) }
    var walletOffered by remember { mutableStateOf(true) }
    var failed by remember(link) { mutableStateOf(false) }
    val sheets = remember(link) {
        SignInSheets { host, key ->
            val text = SignInWords.toast(host, key)
            toast.show(text, ToastType.Success)
            view.announceForAccessibility(text)
        }
    }
    val wallet = remember(link) { WalletHost(StorageWalletConnections(storage), engine.visitorSigner, sheets) }

    BackHandler { web?.takeIf { it.canGoBack() }?.goBack() ?: onClose() }

    Surface(modifier.fillMaxSize(), color = Theme.colors.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Theme.colors.fieldFill), contentAlignment = Alignment.Center) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close", tint = Theme.colors.text) }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    link.host, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold, color = Theme.colors.text,
                )
            }
            if (!walletOffered) {
                Text(
                    "This device's web view can't offer Sign in with Sphinx.",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText,
                )
            }
            if (failed) {
                Text(
                    "Couldn't open ${link.host}.",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText,
                )
            }
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportMultipleWindows(false)
                        // A page of someone else's keeps nothing of the person's, and nothing it sets stays: both ends of its visit are cleaned.
                        wipe(this)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        // The person's tap in the page is what lets it ask for a sign-in (`personActed`).
                        setOnTouchListener { _, event ->
                            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) {
                                lastTouch = SystemClock.elapsedRealtime()
                            }
                            false
                        }
                        webViewClient = object : WebViewClient() {
                            // iOS's policy for a stranger's page: the origin Peel loaded and the app's own `about:` frame
                            // load; `data:`/`blob:` only in sub-frames; another http(s) site never loads here, but a link the
                            // person just tapped opens in the system browser; every other scheme loads nothing.
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url
                                val scheme = url.scheme?.lowercase()
                                return when {
                                    Seller.origin(url.toString()) == link.origin -> false
                                    scheme == "about" -> false
                                    scheme == "data" || scheme == "blob" -> request.isForMainFrame
                                    scheme == "http" || scheme == "https" -> {
                                        val tapped = lastTouch != 0L && SystemClock.elapsedRealtime() - lastTouch <= LINK_TAP_WINDOW_MS
                                        if (request.isForMainFrame && request.hasGesture() && tapped) {
                                            try {
                                                view.context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                            } catch (e: ActivityNotFoundException) {
                                                // Nothing to open it: it simply does not open.
                                            }
                                        }
                                        true
                                    }
                                    else -> true
                                }
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) failed = true
                            }
                        }
                        walletOffered = installWallet(this, link, wallet, scope) {
                            lastTouch != 0L && SystemClock.elapsedRealtime() - lastTouch <= GESTURE_WINDOW_MS
                        }
                        loadUrl(link.url)
                        web = this
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
    sheets.ask?.let { ask -> SignInSheet(ask) { sheets.answer(it) } }

    DisposableEffect(link) {
        onDispose {
            web?.let { wipe(it); it.destroy() }
        }
    }
}

/// Clears what a visit leaves: cookies, web storage, cache and history (a WebView's data is shared by the whole app,
/// which keeps nothing of the person's in it: Apps sends its token in a header).
private fun wipe(web: WebView) {
    CookieManager.getInstance().removeAllCookies(null)
    WebStorage.getInstance().deleteAllData()
    web.clearCache(true)
    web.clearHistory()
}

/// Offers the wallet to the loaded origin's pages: the `peelWallet` listener (that origin only) and the document-start
/// shim that makes it look like iOS's `strutWallet` handler. False when this WebView has no such support.
@SuppressLint("RequiresFeature")
private fun installWallet(
    web: WebView, link: PublicAppLink, wallet: WalletHost, scope: kotlinx.coroutines.CoroutineScope, personActed: () -> Boolean,
): Boolean {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
        !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    ) return false
    val rules = setOf(link.origin)
    WebViewCompat.addWebMessageListener(web, BRIDGE_NAME, rules) { _, message: WebMessageCompat, sourceOrigin, isMainFrame, reply ->
        val parsed = runCatching { JSONObject(message.data ?: "") }.getOrNull()
        val id = parsed?.opt("id")
        fun answer(body: JSONObject) {
            val envelope = JSONObject().put("id", id ?: JSONObject.NULL).put("reply", body)
            reply.postMessage(envelope.toString())
        }
        // Only the main frame whose origin is exactly the one Peel loaded is answered.
        if (parsed == null || !isMainFrame || Seller.origin(sourceOrigin.toString()) != link.origin) {
            answer(WalletErrors.body(WalletError.RESTRICTED, "Peel does not answer there."))
            return@addWebMessageListener
        }
        val caller = WalletCaller.page(link.origin, personActed())
        scope.launch { answer(wallet.handle(parsed.opt("body"), caller)) }
    }
    WebViewCompat.addDocumentStartJavaScript(web, WALLET_SHIM, rules)
    return true
}
