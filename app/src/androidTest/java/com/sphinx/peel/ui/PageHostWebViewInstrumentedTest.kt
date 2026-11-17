package com.sphinx.peel.ui

import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/// A real WebView, a local MockWebServer page (http, so no `file://`), and the pieces `OrgPageScreen` relies on that Robolectric cannot give
/// (docs/phases/29-org-page-host.md "UNTESTED"): a document-start script running before the page's own script, and a `postMessage` promise round
/// trip through `addWebMessageListener` with the same shim shape as `OrgWalletBridge` (`window.webkit.messageHandlers.strutWallet`).
/// The shim below is a copy of the production one's contract, not the production constant (that is `internal` to the UI package's file and
/// would couple this to its text); the round trip checks the mechanism. NOT RUN where this was written (no emulator): compiled only.
@RunWith(AndroidJUnit4::class)
class PageHostWebViewInstrumentedTest {
    private val server = MockWebServer()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun start() {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(PAGE))
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    /// Loads the page and waits for the title the page's script sets; returns it.
    private fun load(web: () -> WebView): Pair<WebView, String> {
        val holder = AtomicReference<WebView>()
        onMain { holder.set(web()) }
        val web = holder.get()
        val title = AtomicReference<String>()
        val done = CountDownLatch(1)
        onMain {
            web.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) = poll(view, 0)

                // The page's promise may settle after the load finishes: look again until the title moves off "pending".
                private fun poll(view: WebView, tries: Int) {
                    view.evaluateJavascript("document.title") { value ->
                        val seen = value.trim('"')
                        if (seen != "pending" || tries >= 50) { title.set(seen); done.countDown() } else view.postDelayed({ poll(view, tries + 1) }, 200)
                    }
                }
            }
            web.loadUrl(server.url("/lab/").toString())
        }
        assertTrue("the page did not load", done.await(20, TimeUnit.SECONDS))
        return web to title.get()
    }

    @Test fun aDocumentStartScriptRunsBeforeThePagesOwnScript() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
        val (_, seen) = load {
            WebView(ApplicationProvider.getApplicationContext()).also { web ->
                web.settings.javaScriptEnabled = true
                // The hand-over's shape: the token in the page's own sessionStorage before its script reads it.
                WebViewCompat.addDocumentStartJavaScript(web, "sessionStorage.setItem('strut/apiKey', 'tok');", setOf(server.url("/").toString().trimEnd('/')))
            }
        }
        assertEquals("tok", seen)
    }

    @Test fun postMessageRoundTripsThroughTheListener() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
        val origins = setOf(server.url("/").toString().trimEnd('/'))
        val received = AtomicReference<String>()
        val (_, seen) = load {
            WebView(ApplicationProvider.getApplicationContext()).also { web ->
                web.settings.javaScriptEnabled = true
                WebViewCompat.addWebMessageListener(web, "peelWallet", origins) { _, message, _, isMainFrame, reply ->
                    received.set(message.data)
                    assertTrue(isMainFrame)
                    reply.postMessage("""{"id":1,"reply":{"result":{"wallet":"Peel"}}}""")
                }
                WebViewCompat.addDocumentStartJavaScript(web, SHIM, origins)
            }
        }
        // The page awaits strutWallet.postMessage(...) and sets its title to the answer's wallet name.
        assertTrue(received.get().contains("host_capability"))
        assertEquals("Peel", seen)
    }

    private companion object {
        /// The same contract as `WALLET_SHIM`: `window.webkit.messageHandlers.strutWallet.postMessage(body)` resolves to the host's `reply`.
        const val SHIM = """
            (function () {
              var pending = {}; var next = 1; var listening = false;
              window.webkit = window.webkit || {}; window.webkit.messageHandlers = window.webkit.messageHandlers || {};
              window.webkit.messageHandlers.strutWallet = { postMessage: function (body) {
                return new Promise(function (resolve) {
                  if (!listening) { listening = true; window.peelWallet.onmessage = function (e) { var a = JSON.parse(e.data); var d = pending[a.id]; if (d) { delete pending[a.id]; d(a.reply); } }; }
                  var id = next++; pending[id] = resolve;
                  window.peelWallet.postMessage(JSON.stringify({ id: id, body: body }));
                });
              } };
            })();
        """

        const val PAGE = """
            <html><head><title>pending</title></head><body><script>
              var key = sessionStorage.getItem('strut/apiKey');
              if (key) { document.title = key; }
              if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.strutWallet) {
                window.webkit.messageHandlers.strutWallet.postMessage({ v: 1, op: 'host_capability' }).then(function (r) { document.title = r.result.wallet; });
              }
            </script></body></html>
        """
    }
}
