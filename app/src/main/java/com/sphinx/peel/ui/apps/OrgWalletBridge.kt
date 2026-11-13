package com.sphinx.peel.ui.apps

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.sphinx.peel.engine.PhonePage
import com.sphinx.peel.engine.StrutWallet
import com.sphinx.peel.engine.WalletCaller
import com.sphinx.peel.engine.WalletError
import com.sphinx.peel.engine.WalletErrors
import com.sphinx.peel.engine.WalletHost
import com.sphinx.peel.engine.WalletPerson
import com.sphinx.peel.engine.WalletPush
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

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

/// What a push is run in: a page's web view, in its main frame, in the page's own world.
internal class WebViewTarget(private val web: WebView) : StrutWallet.ScriptTarget {
    override val url: String? get() = web.url
    override fun run(script: String) = web.evaluateJavascript(script, null)
}

/// The app wallet's handler on a page of the host (iOS `OrgWalletBridge`, docs/app-wallet.md). On an org's page the main frame
/// is the org's own strut UI, which can name any of the org's apps, so the org is what Peel trusts ([com.sphinx.peel.engine.WalletScope.Org]);
/// on a stranger's public app Peel loaded itself, the box's own code, so only the origin Peel loaded
/// ([com.sphinx.peel.engine.WalletScope.Origin]). The sheets name either from Peel's own record of the page it opened
/// ([PhonePage.walletPlace]), never from the page, and only the main frame at that origin, while the page still shows what
/// the page serves, is answered. A sheet opens only right after the person's own tap ([acted]).
class OrgWalletBridge(
    private val page: PhonePage,
    private val host: WalletHost,
    private val person: WalletPerson,
    private val scope: CoroutineScope,
    private val acted: () -> Boolean,
) {
    private var web: WebView? = null

    /// Offers the wallet to the page's origin: the `` listener (that origin only) and the document-start shim that makes
    /// it look like iOS's `strutWallet` handler. False when this WebView has no such support (nothing is installed then).
    @SuppressLint("RequiresFeature")
    fun install(web: WebView): Boolean {
        if (page.origin.isEmpty() || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) return false
        this.web = web
        val rules = setOf(page.origin)
        WebViewCompat.addWebMessageListener(web, BRIDGE_NAME, rules) { _, message: WebMessageCompat, sourceOrigin, isMainFrame, reply ->
            val current = web.url
            scope.launch { reply.postMessage(envelope(message.data, sourceOrigin.toString(), isMainFrame, current)) }
        }
        WebViewCompat.addDocumentStartJavaScript(web, WALLET_SHIM, rules)
        return true
    }

    /// One message `{id, body}` from the page, answered as `{id, reply}`: `reply` is the wallet's `{result}`/`{error}` (errors as
    /// [WalletErrors.body] builds them). Only the main frame at the page's origin, while the web view shows what the page serves
    /// ([currentUrl]), is answered; any other sender is told `RESTRICTED`, no more.
    suspend fun envelope(data: String?, sourceOrigin: String, isMainFrame: Boolean, currentUrl: String?): String {
        val parsed = runCatching { JSONObject(data ?: "") }.getOrNull()
        fun wrap(reply: JSONObject) = JSONObject().put("id", parsed?.opt("id") ?: JSONObject.NULL).put("reply", reply).toString()
        if (parsed == null || !StrutWallet.mayAnswer(isMainFrame, sourceOrigin, page.origin) || currentUrl == null || !page.serves(currentUrl)) {
            return wrap(WalletErrors.body(WalletError.RESTRICTED, NOT_ANSWERED))
        }
        val caller = WalletCaller(page.walletScope, page.walletPlace, acted(), person)
        return wrap(host.handle(parsed.opt("body"), caller))
    }

    /// A notification for this page's connection goes into its main frame (iOS `push`); one for another connection does not.
    fun push(push: WalletPush, target: StrutWallet.ScriptTarget? = web?.let(::WebViewTarget)): Boolean {
        if (target == null || push.connection != page.walletScope.key) return false
        return StrutWallet.push(push.envelope.toString(), target, page.origin)
    }

    companion object {
        /// What a sender that is not answered is told (as `RESTRICTED`, in [WalletErrors]'s shape).
        const val NOT_ANSWERED = "Peel does not answer there."
    }
}
