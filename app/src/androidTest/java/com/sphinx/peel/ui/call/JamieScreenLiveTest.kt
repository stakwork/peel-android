package com.sphinx.peel.ui.call

import android.Manifest
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sphinx.peel.engine.AppWallet
import com.sphinx.peel.engine.BoxLink
import com.sphinx.peel.engine.BoxStore
import com.sphinx.peel.engine.Boxes
import com.sphinx.peel.engine.CallLink
import com.sphinx.peel.engine.CallServer
import com.sphinx.peel.engine.CallTicket
import com.sphinx.peel.engine.JamieScreen
import com.sphinx.peel.engine.WalletConnection
import com.sphinx.peel.engine.WalletConnections
import com.sphinx.peel.engine.WalletHost
import com.sphinx.peel.ui.apps.OrgPageScreen
import com.sphinx.peel.ui.theme.PeelTheme
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/// Jamie's screen as the app itself, live (docs/phases/57-live-canvas-tile.md, "Checked"): before a voice service sets the `screen`
/// attribute, a stand-in Jamie does. Skipped unless the run names a box: `-e boxStrut http://127.0.0.1:3077 -e boxToken strut-person.…
/// [-e callServer 127.0.0.1:7880] [-e app stadeum/canvas/canvas]`, with `livekit-server --dev` (its `devkey`/`secret` mint both people's
/// tokens here) and `adb reverse` for both.
///
/// A "Jamie" joins with the attributes the voice service gives it (`tts`, `recording`, `screen` naming the box's app) and shares the
/// emulator's camera as its screen. Ada, linked to the box, sees Jamie's screen take the big tile with the people above it, and the tile
/// is the box's app in a web view, loaded with her token (handed to strut's UI, never in the address). "Show everyone" brings the grid
/// back. The screen is saved as `jamie-screen-live.png` in the app's external files.
@RunWith(AndroidJUnit4::class)
class JamieScreenLiveTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun jamiesScreenOpensTheBoxsAppLiveForAPersonLinkedToIt() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val strut = args.getString("boxStrut")
        val token = args.getString("boxToken")
        assumeTrue("no box named (-e boxStrut, -e boxToken)", strut != null && token != null)
        val callServer = args.getString("callServer") ?: "127.0.0.1:7880"
        val app = args.getString("app") ?: "stadeum/canvas/canvas"
        val context = rule.activity.applicationContext
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)

        // Ada is linked to the box.
        val now = Instant.now()
        val box = BoxLink(strut!!, "ada", "Ada", token!!, now.plusSeconds(3600), linkedAt = now, renewedAt = now)
        val store = object : BoxStore {
            var text: String? = null
            override fun read() = text
            override fun write(text: String) { this.text = text }
            override fun delete() { text = null }
        }
        BoxLink.write(listOf(box), store)
        withContext(Dispatchers.Main) { Boxes.model.open(store) }

        val link = CallLink.new(callServer, audioOnly = true)
        val screen = "$strut/?app=${enc(app)}&input=${enc("present=call-live")}"
        val jamieTicket = ticket(callServer, link.room, "jamie_box", "Jamie", mapOf("tts" to "off", "recording" to "true", JamieScreen.ATTRIBUTE to screen))
        val adaTicket = ticket(callServer, link.room, "ada_phone", "Ada")
        val (jamie, ada) = withContext(Dispatchers.Main) {
            fun session(ticket: CallTicket) =
                CallSession(context, ticket, link, "live", "Live", camera = false, askMicPermission = { true }, askCameraPermission = { true })
            session(jamieTicket) to session(adaTicket)
        }
        try {
            // [main]: the check reads the sessions, on the main thread; else it goes there itself (the web view's own calls).
            suspend fun until(what: String, seconds: Long = 30, main: Boolean = true, check: () -> Boolean) {
                val ok = withTimeoutOrNull(seconds * 1000) {
                    while (!(if (main) withContext(Dispatchers.Main) { check() } else withContext(Dispatchers.IO) { check() })) delay(250)
                    true
                }
                val seen = withContext(Dispatchers.Main) { "Ada's tiles ${ada.tiles.map { it.id + (it.screen?.let { s -> " → $s" } ?: "") }}" }
                assertNotNull("timed out waiting for $what ($seen)", ok)
            }
            until("both connected") { jamie.state == CallConnectionState.CONNECTED && ada.state == CallConnectionState.CONNECTED }
            withContext(Dispatchers.Main) { jamie.startCameraShare(jamie.cameras().first().id) }
            until("Jamie's screen at Ada's, naming the box's app") { ada.tiles.any { it.isScreen && it.screen == screen } }
            val screenTile = withContext(Dispatchers.Main) { ada.tiles.first { it.isScreen } }
            assertEquals(screenTile.id, withContext(Dispatchers.Main) { ada.focusTile?.id })

            val wallet = WalletHost(NoConnections, NoWallet)
            rule.setContent {
                PeelTheme {
                    CallScreen(
                        CallPhase.Active(ada), onHide = {}, onLeave = {}, onRetry = {},
                        livePage = { page, modifier -> OrgPageScreen(page, wallet, onClose = null, modifier = modifier, bare = true) },
                    )
                }
            }

            // The box's app, alone, with Ada's token handed over (never in the address), and drawn.
            val expected = "$strut/?app=${enc(app)}&input=${enc("present=call-live")}&solo=1"
            until("the box's app in the tile", main = false) { webView()?.let { onMain { it.url } } == expected }
            val web = webView()!!
            assertTrue(onMain { web.url!! }.contains(token).not())
            until("strut's UI holding Ada's token", seconds = 20, main = false) { js(web, "sessionStorage.getItem('strut/apiKey')") == "\"$token\"" }
            until("the app drawn", seconds = 40, main = false) { (js(web, "document.body ? document.body.innerText.length : 0")?.toIntOrNull() ?: 0) > 0 }
            Log.i(TAG, "the page says: ${js(web, "document.body.innerText.slice(0, 120)")}")
            delay(6_000)
            snapshot("jamie-screen-live.png")

            // The big tile is under the row of people: the web view starts below the strip's tiles.
            val top = onMain { IntArray(2).also { web.getLocationOnScreen(it) }[1] }
            Log.i(TAG, "web view at y=$top, ${onMain { web.width }}x${onMain { web.height }}")

            // Taps are the page's: the grid is the button.
            rule.onNodeWithContentDescription("Show everyone").performClick()
            rule.waitForIdle()
            assertNull(withContext(Dispatchers.Main) { ada.focusTile })
            until("no web view in the grid", main = false) { webView() == null }
            delay(3_000)
            snapshot("jamie-screen-grid.png")
        } finally {
            withContext(Dispatchers.Main) {
                jamie.leave()
                ada.leave()
                Boxes.model.close()
            }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /// A LiveKit token from `livekit-server --dev`'s key, for [identity] named [name] in [room], with [attributes] as the voice
    /// service's own are, then read as Peel reads any call server's ticket.
    private fun ticket(callServer: String, room: String, identity: String, name: String, attributes: Map<String, String> = emptyMap()): CallTicket {
        val b64 = Base64.getUrlEncoder().withoutPadding()
        val now = System.currentTimeMillis() / 1000
        val claims = JSONObject().put("iss", "devkey").put("sub", identity).put("name", name).put("nbf", now - 10).put("exp", now + 3600)
            .put("video", JSONObject().put("room", room).put("roomJoin", true).put("canPublish", true).put("canSubscribe", true).put("canPublishData", true))
        if (attributes.isNotEmpty()) claims.put("attributes", JSONObject(attributes))
        val head = b64.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val body = b64.encodeToString(claims.toString().toByteArray())
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("secret".toByteArray(), "HmacSHA256")) }
        val jwt = "$head.$body.${b64.encodeToString(mac.doFinal("$head.$body".toByteArray()))}"
        return CallServer.ticketOn(callServer, "ws://$callServer", jwt, room, name)
    }

    /// [block] on the main thread, from the test's.
    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result.set(block()) }
        return result.get()
    }

    private fun webView(): WebView? = onMain { find(rule.activity.window.decorView) }

    private fun find(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
        else -> null
    }

    /// [script]'s value in [web], as JSON.
    private fun js(web: WebView, script: String): String? {
        val value = AtomicReference<String?>()
        val done = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { web.evaluateJavascript(script) { value.set(it); done.countDown() } }
        done.await(5, TimeUnit.SECONDS)
        return value.get()
    }

    private fun snapshot(name: String) {
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val file = File(rule.activity.getExternalFilesDir(null), name)
        file.outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "saved ${file.absolutePath}")
    }

    private object NoConnections : WalletConnections {
        override fun connection(key: String): WalletConnection? = null
        override fun save(connection: WalletConnection) = Unit
    }

    /// No wallet: the canvas never asks for one.
    private object NoWallet : AppWallet {
        override suspend fun network() = error("no wallet")
        override suspend fun decode(invoice: String) = error("no wallet")
        override suspend fun problem(payment: com.sphinx.peel.engine.WalletPayment) = error("no wallet")
        override suspend fun pay(payment: com.sphinx.peel.engine.WalletPayment, tag: com.sphinx.peel.engine.WalletTag) = error("no wallet")
        override suspend fun makeInvoice(amountMsat: Long, description: String, tag: com.sphinx.peel.engine.WalletTag) = error("no wallet")
        override suspend fun records(connection: String) = emptyList<com.sphinx.peel.engine.WalletRecord>()
        override suspend fun balance() = 0L
        override suspend fun payeeName(pubkey: String): String? = null
        override suspend fun signVisitor(challenge: com.sphinx.peel.engine.VisitorChallenge, key: com.sphinx.peel.engine.SignInKey) = error("no wallet")
        override val signsPerDomain = false
        override fun updates() = kotlinx.coroutines.flow.emptyFlow<com.sphinx.peel.engine.WalletRecord>()
    }

    private companion object {
        const val TAG = "JamieScreenLiveTest"
    }
}
