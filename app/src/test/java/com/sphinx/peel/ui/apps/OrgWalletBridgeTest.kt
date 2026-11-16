package com.sphinx.peel.ui.apps

import com.sphinx.peel.engine.PhonePage
import com.sphinx.peel.engine.ScriptedWallet
import com.sphinx.peel.engine.SignInApp
import com.sphinx.peel.engine.WalletConnectAnswer
import com.sphinx.peel.engine.WalletConnectSheet
import com.sphinx.peel.engine.WalletPaymentSheet
import com.sphinx.peel.engine.OrgPageRequest
import com.sphinx.peel.engine.PublicAppLink
import com.sphinx.peel.engine.PublicPage
import com.sphinx.peel.engine.ROOT_CHALLENGE
import com.sphinx.peel.engine.SignInAsk
import com.sphinx.peel.engine.SignInKey
import com.sphinx.peel.engine.StrutWallet
import com.sphinx.peel.engine.VisitorChallenge
import com.sphinx.peel.engine.VisitorSignature
import com.sphinx.peel.engine.VisitorSigner
import com.sphinx.peel.engine.WalletConnection
import com.sphinx.peel.engine.WalletConnections
import com.sphinx.peel.engine.WalletHost
import com.sphinx.peel.engine.WalletPerson
import com.sphinx.peel.engine.WalletPush
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// The app wallet's bridge on a page of the host (iOS `OrgWalletBridge`): who is answered, with which scope and place, the tap
/// that lets a sheet open, and the notifications pushed in. A real WebView's `addWebMessageListener` needs a device; the
/// message handling and the push are driven here directly.
@RunWith(RobolectricTestRunner::class)
class OrgWalletBridgeTest {
    private class Connections : WalletConnections {
        val rows = mutableMapOf<String, WalletConnection>()
        override fun connection(key: String) = rows[key]
        override fun save(connection: WalletConnection) { rows[connection.key] = connection }
    }

    private class Person : WalletPerson {
        val asks = mutableListOf<SignInAsk>()
        override suspend fun connect(sheet: WalletConnectSheet): WalletConnectAnswer? {
            val offer = sheet.signIn!!
            asks += SignInAsk(offer.host, SignInApp(sheet.app, sheet.version, sheet.title), offer.keys)
            return WalletConnectAnswer.asAsked(sheet)
        }
        override suspend fun pay(sheet: WalletPaymentSheet) = false
        override suspend fun buyCredit(sheet: com.sphinx.peel.engine.WalletCreditSheet) = false
        override fun signedIn(host: String, key: SignInKey) {}
    }

    private class Target(override val url: String?) : StrutWallet.ScriptTarget {
        val ran = mutableListOf<String>()
        override fun run(script: String) { ran += script }
    }

    private val connections = Connections()
    private val person = Person()
    private val host = WalletHost(connections, ScriptedWallet(VisitorSigner { _: VisitorChallenge, key: SignInKey -> VisitorSignature("pub", "sig-${key.wire}") }), { NOW_MS })
    private val org = OrgPageRequest("https://acme.sphinx.chat/lab/?app=x&solo=1", "tok", "acme", "Acme")
    private val public = PublicPage(PublicAppLink.of("https://shop.example.com/?app=moon-game")!!)
    private var tapped = true

    private fun bridge(page: PhonePage) = OrgWalletBridge(page, host, person, CoroutineScope(Dispatchers.Unconfined)) { tapped }

    private fun message(id: Any?, body: JSONObject) = JSONObject().put("id", id).put("body", body).toString()
    private fun hostCapability() = JSONObject().put("v", 1).put("op", "host_capability")
    private fun signIn() = JSONObject().put("v", 1).put("op", "sign_in").put("seller", "https://shop.example.com").put("challenge", ROOT_CHALLENGE)
        .put("app", JSONObject().put("name", "moon-game").put("version", "v3").put("title", "Moon Game"))

    private fun JSONObject.reply() = getJSONObject("reply")
    private fun JSONObject.code() = reply().getJSONObject("error").getJSONObject("data").getString("code")
    private fun JSONObject.text() = reply().getJSONObject("error").getString("message")

    private fun answer(b: OrgWalletBridge, data: String?, origin: String = "https://acme.sphinx.chat", main: Boolean = true,
                       current: String? = "https://acme.sphinx.chat/lab/?app=y") =
        JSONObject(runBlocking { b.envelope(data, origin, main, current) })

    @Test fun `an org page's main frame at its origin is answered and the id comes back`() {
        val r = answer(bridge(org), message(7, hostCapability()))
        assertEquals(7, r.getInt("id"))
        val result = r.reply().getJSONObject("result")
        assertEquals("Peel", result.getString("wallet"))
        // An org's page is no visitor: no host op of the visitor kind.
        assertEquals(0, result.getJSONArray("ops").length())
    }

    @Test fun `a frame, another origin or a page that moved off is not answered`() {
        val b = bridge(org)
        val data = message(1, hostCapability())
        for (r in listOf(
            answer(b, data, main = false),
            answer(b, data, origin = "https://evil.example"),
            answer(b, data, origin = "null"),
            answer(b, data, origin = "http://acme.sphinx.chat"),
            // The page moved elsewhere on the host (another service), or off it.
            answer(b, data, current = "https://acme.sphinx.chat/other/"),
            answer(b, data, current = "https://evil.example/lab/"),
            answer(b, data, current = null),
        )) {
            assertEquals(1, r.getInt("id"))
            assertEquals("RESTRICTED", r.code())
            assertEquals("Peel does not answer there.", r.text())
            assertEquals(-32000, r.reply().getJSONObject("error").getInt("code"))
        }
    }

    @Test fun `a message that is not json is not answered and has no id`() {
        for (data in listOf(null, "", "x", "[1]")) {
            val r = answer(bridge(org), data)
            assertEquals("RESTRICTED", r.code())
            assertTrue(r.isNull("id"))
        }
    }

    @Test fun `an org page's sign in is refused whatever the person did`() {
        tapped = true
        val r = answer(bridge(org), message(2, signIn()))
        assertEquals("RESTRICTED", r.code())
        assertEquals("Peel signs you in only to a public app it opened itself.", r.text())
        assertTrue(person.asks.isEmpty())
    }

    @Test fun `a public page is answered at its origin with the scope and place Peel recorded`() {
        val b = bridge(public)
        val cap = answer(b, message(3, hostCapability()), origin = "https://shop.example.com", current = "https://shop.example.com/x")
        val ops = cap.reply().getJSONObject("result").getJSONArray("ops")
        assertEquals(listOf("buy_credit", "buy_credit_status", "buy_credit_cancel", "sign_in"), List(ops.length()) { ops.getString(it) })
        // The sign-in sheet names the host Peel loaded, and the connection is kept under the origin's key.
        val r = answer(b, message(4, signIn()), origin = "https://shop.example.com", current = "https://shop.example.com/x")
        assertEquals("sig-root", r.reply().getJSONObject("result").getString("sig"))
        assertEquals("shop.example.com", person.asks.single().host)
        assertEquals(setOf("origin:https://shop.example.com"), connections.rows.keys)
        assertEquals("shop.example.com", connections.rows.getValue("origin:https://shop.example.com").place)
    }

    @Test fun `a sheet opens only right after the persons own tap`() {
        tapped = false
        val r = answer(bridge(public), message(5, signIn()), origin = "https://shop.example.com", current = "https://shop.example.com/")
        assertEquals("RESTRICTED", r.code())
        assertEquals("Peel asks you to sign in only right after you click or tap in the app.", r.text())
        assertTrue(person.asks.isEmpty())
    }

    @Test fun `a push for this page's connection goes in and one for another does not`() {
        val b = bridge(org)
        val here = Target("https://acme.sphinx.chat/lab/?app=y")
        val envelope = JSONObject().put("v", 1).put("notification", JSONObject().put("notification_type", "payment_received"))
        assertTrue(b.push(WalletPush("org:acme", "acme/x", envelope), here))
        assertEquals(listOf(StrutWallet.pushScript(envelope.toString())), here.ran)
        assertFalse(b.push(WalletPush("org:globex", "globex/x", envelope), here))
        assertFalse(b.push(WalletPush("origin:https://acme.sphinx.chat", "x", envelope), here))
        assertEquals(1, here.ran.size)
        // Not to a page that moved elsewhere, nor to none.
        assertFalse(b.push(WalletPush("org:acme", "acme/x", envelope), Target("https://evil.example/")))
        assertFalse(b.push(WalletPush("org:acme", "acme/x", envelope), null))
        // A public page hears only its own origin's connection.
        val p = bridge(public)
        val there = Target("https://shop.example.com/")
        assertFalse(p.push(WalletPush("org:acme", "acme/x", envelope), there))
        assertTrue(p.push(WalletPush("origin:https://shop.example.com", "x", envelope), there))
    }

    /// The wallet host's notifications reach a page's web view as a script call: what the host composable's collector does.
    @Test fun `a notification published by the host reaches the web view's script call`() = runBlocking {
        val b = bridge(org)
        val target = Target("https://acme.sphinx.chat/lab/?app=x&solo=1")
        val envelope = JSONObject().put("v", 1).put("note", "\"); alert(1); //</script>\u2028")
        val job = launch(Dispatchers.Unconfined) { host.notifications.collect { b.push(it, target) } }
        yield()
        assertTrue(host.publish(WalletPush("org:acme", "acme/board", envelope)))
        assertTrue(host.publish(WalletPush("org:other", "other/board", envelope)))
        job.cancel()
        assertEquals(1, target.ran.size)
        val script = target.ran.single()
        assertTrue(script.startsWith("if (window.strutWallet && typeof window.strutWallet.notify === 'function') window.strutWallet.notify(\""))
        assertFalse(script.contains("</script>"))
        assertFalse(script.contains('\u2028'))
        assertEquals(script.indexOf("alert(1)"), script.lastIndexOf("alert(1)"))
        // The hostile text is inside the one string: after the opening quote, with its own quotes escaped.
        assertTrue(script, script.contains("\\\\\\\"); alert(1); "))
        assertEquals(StrutWallet.pushScript(envelope.toString()), script)
    }

    private companion object {
        const val NOW_MS = 1793800000000L
    }
}
