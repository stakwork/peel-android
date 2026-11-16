package com.sphinx.peel.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class FakeConnections : WalletConnections {
    val rows = mutableMapOf<String, WalletConnection>()
    override fun connection(key: String) = rows[key]
    override fun save(connection: WalletConnection) { rows[connection.key] = connection }
}

private class FakeSigner : VisitorSigner {
    val signed = mutableListOf<Pair<VisitorChallenge, SignInKey>>()
    override suspend fun sign(challenge: VisitorChallenge, key: SignInKey): VisitorSignature {
        signed += challenge to key
        return VisitorSignature("pub-${key.wire}", "sig-${key.wire}")
    }
}

private class FakePerson : WalletPerson {
    val asks = mutableListOf<SignInAsk>()
    val notices = mutableListOf<Pair<String, SignInKey>>()
    val asked = CompletableDeferred<Unit>()
    var cancelled = false
    /// What a sheet does: answers at once with this by default.
    var answer: suspend (SignInAsk) -> SignInKey? = { it.keys.first() }
    override suspend fun askSignIn(ask: SignInAsk): SignInKey? {
        asks += ask
        asked.complete(Unit)
        try {
            return answer(ask)
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled = true
            throw e
        }
    }
    override fun signedIn(host: String, key: SignInKey) { notices += host to key }
}

@RunWith(RobolectricTestRunner::class)
class WalletHostTest {
    private var now = NOW
    private val connections = FakeConnections()
    private val signer = FakeSigner()
    private val person = FakePerson()
    private fun host(sheetTimeoutMs: Long = 5000) = WalletHost(connections, signer, { now }, sheetTimeoutMs)
    private val origin = "https://shop.example.com"
    private fun caller(acted: Boolean = true) = WalletCaller(WalletScope.Origin(origin), "shop.example.com", acted, person)
    private fun orgCaller(acted: Boolean = true) = WalletCaller(WalletScope.Org("acme"), "Acme", acted, person)
    private val scope = "origin:$origin"

    private fun signIn(challenge: String = ROOT_CHALLENGE, seller: String = origin) = JSONObject().put("v", 1).put("op", "sign_in")
        .put("seller", seller).put("challenge", challenge)
        .put("app", JSONObject().put("name", "moon-game").put("version", "v3").put("title", "Moon Game"))

    private fun JSONObject.code() = getJSONObject("error").getJSONObject("data").getString("code")
    private fun JSONObject.numeric() = getJSONObject("error").getInt("code")
    private fun message(op: String, app: JSONObject? = JSONObject().put("name", "n").put("version", "1")) =
        JSONObject().put("v", 1).put("op", op).apply { if (app != null) put("app", app) }
    private fun app(request: Boolean = true) = JSONObject().put("name", "n").put("version", "1").apply {
        if (request) put("request", JSONObject().put("request_methods", org.json.JSONArray().put("pay_keysend")))
    }
    private fun JSONObject.text() = getJSONObject("error").getString("message")

    @Test fun hostCapability() = runBlocking {
        val r = host().handle(JSONObject().put("v", 1).put("op", "host_capability"), caller())
        val result = r.getJSONObject("result")
        assertEquals(1, result.getInt("version"))
        assertEquals(JSONArray().put("sign_in").toString(), result.getJSONArray("ops").toString())
        assertEquals("Peel", result.getString("wallet"))
    }

    @Test fun otherOps() = runBlocking {
        val h = host()
        assertEquals("BAD_REQUEST", h.handle("x", caller()).code())
        assertEquals("BAD_REQUEST", h.handle(JSONObject().put("v", 2).put("op", "sign_in"), caller()).code())
        assertEquals("BAD_REQUEST", h.handle(message("nope"), caller()).code())
        assertEquals("That is not a wallet message Peel reads.", h.handle(JSONObject().put("v", 1), caller()).text())
    }

    @Test fun versionIsReadAsAnInteger() = runBlocking {
        val h = host()
        fun op(v: Any) = JSONObject().put("v", v).put("op", "host_capability")
        assertNotNull(h.handle(JSONObject("""{"v":1.0,"op":"host_capability"}"""), caller()).optJSONObject("result"))
        assertEquals("BAD_REQUEST", h.handle(JSONObject("""{"v":1.5,"op":"host_capability"}"""), caller()).code())
        assertEquals("BAD_REQUEST", h.handle(op("1"), caller()).code())
        assertEquals("BAD_REQUEST", h.handle(op(true), caller()).code())
    }

    @Test fun errorBodyIsStrutsShape() = runBlocking {
        val bad = host().handle("x", caller())
        assertEquals(-32602, bad.numeric())
        assertEquals("BAD_REQUEST", bad.code())
        val other = host().handle(signIn(), caller(acted = false))
        assertEquals(-32000, other.numeric())
        assertEquals("RESTRICTED", other.code())
        assertEquals(-32000, WalletErrors.body(WalletError.INTERNAL, "m").numeric())
        assertEquals("INTERNAL", WalletErrors.body(WalletError.INTERNAL, "m").code())
        assertEquals(12, WalletError.entries.size)
    }

    @Test fun capability() = runBlocking {
        val h = host()
        val cap = h.handle(message("capability", app()), caller()).getJSONObject("result")
        assertEquals(1, cap.getInt("version"))
        assertEquals(0, cap.getJSONArray("methods").length())
        assertEquals(0, cap.getJSONArray("notifications").length())
        assertEquals("sphinx", cap.getString("wallet"))
        assertFalse(cap.getBoolean("connected"))
        val none = h.handle(message("capability", app(request = false)), caller())
        assertEquals("RESTRICTED", none.code())
        assertEquals("This app asks for no wallet.", none.text())
        assertEquals("BAD_REQUEST", h.handle(message("capability", null), caller()).code())
        assertEquals("BAD_REQUEST", h.handle(message("capability", JSONObject().put("name", "").put("version", "1")), caller()).code())
        assertEquals("BAD_REQUEST", h.handle(message("capability", JSONObject().put("name", "n")), caller()).code())
        assertEquals("BAD_REQUEST", h.handle(message("capability", app().put("peer", "a/b")), caller()).code())
        assertEquals("BAD_REQUEST", h.handle(message("capability", app().put("peer", "")), caller()).code())
        assertNotNull(h.handle(message("capability", app().put("peer", JSONObject.NULL)), caller()).optJSONObject("result"))
        assertNotNull(h.handle(message("capability", app().put("peer", "alice")), caller()).optJSONObject("result"))
    }

    @Test fun calls() = runBlocking {
        val h = host()
        fun call(method: Any?) = message("call", app()).apply { if (method != null) put("method", method) }
        assertEquals("The call names no method.", h.handle(call(null), caller()).text())
        assertEquals("BAD_REQUEST", h.handle(call(5), caller()).code())
        val unknown = h.handle(call("pay\nnow"), caller())
        assertEquals("NOT_IMPLEMENTED", unknown.code())
        assertEquals("Peel's wallet has no method pay now.", unknown.text())
        assertEquals("Peel's wallet has no method ${"x".repeat(39)}….", h.handle(call("x".repeat(60)), caller()).text())
        val known = h.handle(call("pay_invoice"), caller())
        assertEquals("NOT_IMPLEMENTED", known.code())
        assertEquals("Peel's wallet cannot do pay_invoice yet.", known.text())
        assertEquals("BAD_REQUEST", h.handle(message("call", null), caller()).code())
    }

    @Test fun theSheetSeesTheCleanedClaims() = runBlocking {
        val m = signIn().put("app", JSONObject().put("name", "moon\u202Egame").put("version", "v".repeat(30)).put("title", "Moon\nGame <<<"))
        host().handle(m, caller())
        val shown = person.asks.single().app
        assertEquals("moongame", shown.name)
        assertEquals("v".repeat(19) + "…", shown.version)
        assertEquals("Moon Game << <", shown.title)
        assertEquals("/moongame", connections.rows.getValue(scope).app)
    }

    @Test fun judgedAgainstTheLoadedOriginNotTheClaimedSeller() = runBlocking {
        // The page claims evil.example.net as seller with evil's challenge, but Peel loaded shop.example.com.
        val r = host().handle(signIn(EVIL_CHALLENGE, "https://evil.example.net"), caller())
        assertEquals("BAD_REQUEST", r.code())
        assertTrue(r.text().startsWith("Peel does not sign that: the challenge is for evil.example.net, not shop.example.com"))
        assertTrue(person.asks.isEmpty() && signer.signed.isEmpty())
    }

    @Test fun noSheetWithoutAGesture() = runBlocking {
        val r = host().handle(signIn(), caller(acted = false))
        assertEquals("RESTRICTED", r.code())
        assertEquals("Peel asks you to sign in only right after you click or tap in the app.", r.text())
        assertTrue(person.asks.isEmpty())
    }

    @Test fun firstSignInSavesThenLaterOnesAreSilent() = runBlocking {
        person.answer = { SignInKey.domain }
        val first = host().handle(signIn(), caller()).getJSONObject("result")
        assertEquals("sig-domain", first.getString("sig"))
        assertEquals(listOf(SignInKey.root, SignInKey.domain), person.asks.single().keys)
        val saved = connections.rows.getValue(scope)
        assertEquals(SignInKey.domain, saved.signIn)
        assertTrue(saved.methods.isEmpty())
        assertEquals("shop.example.com", saved.place)
        assertEquals("Moon Game", saved.title)
        // Later, with no tap and no sheet.
        val later = host().handle(signIn(), caller(acted = false)).getJSONObject("result")
        assertEquals("pub-domain", later.getString("key"))
        assertEquals(1, person.asks.size)
        assertEquals(listOf("shop.example.com" to SignInKey.domain), person.notices)
    }

    @Test fun aHeldRootKeyDoesNotSignADomainChallenge() = runBlocking {
        connections.save(WalletConnection(scope, "a", "A", "shop.example.com", signIn = SignInKey.root, createdAt = 1))
        val domain = challengeOf(claims(key = "domain"))
        val r = host().handle(signIn(domain), caller(acted = false))
        assertEquals("RESTRICTED", r.code())
        assertTrue(signer.signed.isEmpty())
    }

    @Test fun domainChallengeOffersOnlyDomain() = runBlocking {
        host().handle(signIn(challengeOf(claims(key = "domain"))), caller())
        assertEquals(listOf(SignInKey.domain), person.asks.single().keys)
        assertEquals(SignInKey.domain, signer.signed.single().second)
    }

    @Test fun revokingBringsTheSheetBack() = runBlocking {
        val h = host()
        h.handle(signIn(), caller())
        connections.rows.remove(scope)
        assertEquals("RESTRICTED", h.handle(signIn(), caller(acted = false)).code())
        h.handle(signIn(), caller())
        assertEquals(2, person.asks.size)
    }

    @Test fun declining() = runBlocking {
        person.answer = { null }
        val r = host().handle(signIn(), caller())
        assertEquals("USER_REJECTED", r.code())
        assertEquals("You declined to sign in.", r.text())
        assertTrue(connections.rows.isEmpty() && signer.signed.isEmpty())
    }

    @Test fun onlyOneSheetAtATime() = runBlocking {
        val gate = CompletableDeferred<SignInKey?>()
        person.answer = { gate.await() }
        val h = host()
        val first = async(Dispatchers.Default) { h.handle(signIn(), caller()) }
        person.asked.await()
        val second = h.handle(signIn(), caller())
        assertEquals("RATE_LIMITED", second.code())
        assertEquals("Peel is already asking you something; answer that first.", second.text())
        gate.complete(SignInKey.root)
        assertNotNull(first.await().optJSONObject("result"))
    }

    @Test fun theSheetTimesOutWhenTheChallengeEnds() = runBlocking {
        person.answer = { awaitCancellation() }
        // The challenge ends 40 ms from now; the sheet's own limit is far longer.
        now = 1793800060000L - 40
        val r = host(sheetTimeoutMs = 60_000).handle(signIn(), caller())
        assertEquals("RESTRICTED", r.code())
        assertEquals("You did not answer in time.", r.text())
        assertTrue(person.cancelled)
        assertTrue(connections.rows.isEmpty())
        // The slot is free again.
        person.answer = { null }
        now = NOW
        assertEquals("USER_REJECTED", host().handle(signIn(), caller()).code())
    }

    @Test fun theSheetTimesOutAtItsOwnLimit() = runBlocking {
        person.answer = { awaitCancellation() }
        val r = host(sheetTimeoutMs = 40).handle(signIn(), caller())
        assertEquals("You did not answer in time.", r.text())
    }

    @Test fun aConnectionRevokedWhileTheSheetIsUpStaysRevoked() = runBlocking {
        connections.save(WalletConnection(scope, "old", "Old", "shop.example.com", methods = listOf(WalletMethod.PAY_KEYSEND), createdAt = 1))
        person.answer = { connections.rows.remove(scope); SignInKey.root }
        val r = host().handle(signIn(), caller())
        assertNotNull(r.optJSONObject("result"))
        val saved = connections.rows.getValue(scope)
        assertTrue(saved.methods.isEmpty())
        assertEquals("/moon-game", saved.app)
        assertEquals(SignInKey.root, saved.signIn)
    }

    @Test fun aHeldConnectionKeepsItsMethods() = runBlocking {
        connections.save(WalletConnection(scope, "old", "Old", "shop.example.com", methods = listOf(WalletMethod.PAY_KEYSEND), createdAt = 1))
        host().handle(signIn(), caller())
        assertEquals(listOf(WalletMethod.PAY_KEYSEND), connections.rows.getValue(scope).methods)
        assertEquals(SignInKey.root, connections.rows.getValue(scope).signIn)
    }

    @Test fun expiredWhileAskedKeepsTheConnectionButDoesNotSign() = runBlocking {
        person.answer = { now = 1793800060001L; SignInKey.root }
        val r = host().handle(signIn(), caller())
        assertEquals("RESTRICTED", r.code())
        assertEquals("The sign-in ran out of time while you were asked. Sign in again.", r.text())
        assertEquals(SignInKey.root, connections.rows.getValue(scope).signIn)
        assertTrue(signer.signed.isEmpty())
    }

    @Test fun anUnexpectedFailureIsInternal() = runBlocking {
        val failing = WalletHost(connections, { _, _ -> throw IllegalStateException("no key") }, { now })
        val r = failing.handle(signIn(), caller())
        assertEquals("INTERNAL", r.code())
        assertEquals("no key", r.text())
        assertNull(r.optJSONObject("result"))
    }

    // MARK: Scopes (iOS `WalletScope`, `WalletCaller`; an org's page)

    @Test fun scopeKeysAndVisitorHosts() {
        assertEquals("org:acme", WalletScope.Org("acme").key)
        assertEquals("origin:https://shop.example.com", WalletScope.Origin("https://shop.example.com").key)
        assertNull(WalletScope.Org("acme").visitorHost)
        assertEquals("shop.example.com", WalletScope.Origin("https://shop.example.com:8443").visitorHost)
        assertEquals("127.0.0.1", WalletScope.Origin("http://127.0.0.1:3000").visitorHost)
        // The page's address, not what it says: an origin that is no address has no host.
        assertNull(WalletScope.Origin("not an origin").visitorHost)
        // An org's page and a box Peel loaded are both named by the page rather than known to Peel.
        assertTrue(WalletScope.Org("acme").byOrg)
        assertTrue(WalletScope.Origin("https://x.example").byOrg)
        assertEquals(WalletScope.Org("acme"), WalletScope.Org("acme"))
        assertFalse(WalletScope.Org("acme") == WalletScope.Org("globex"))
    }

    @Test fun hostCapabilityIsPerScope() = runBlocking {
        val ask = JSONObject().put("v", 1).put("op", "host_capability")
        val org = host().handle(ask, orgCaller()).getJSONObject("result")
        assertEquals(1, org.getInt("version"))
        assertEquals(0, org.getJSONArray("ops").length())
        assertEquals("Peel", org.getString("wallet"))
        val origin = host().handle(ask, caller()).getJSONObject("result")
        assertEquals(JSONArray().put("sign_in").toString(), origin.getJSONArray("ops").toString())
        assertEquals("Peel", origin.getString("wallet"))
    }

    @Test fun anOrgPageIsNeverSignedIn() = runBlocking {
        val r = host().handle(signIn(seller = "https://acme.example.com"), orgCaller())
        assertEquals("RESTRICTED", r.code())
        assertEquals(-32000, r.numeric())
        assertEquals("Peel signs you in only to a public app it opened itself.", r.text())
        // Not even for a held connection, a tap, or a challenge that names the org's own host; no sheet, no signature, nothing kept.
        connections.save(WalletConnection("org:acme", "x", "X", "Acme", signIn = SignInKey.root, createdAt = 1))
        assertEquals("RESTRICTED", host().handle(signIn(), orgCaller(acted = true)).code())
        assertTrue(person.asks.isEmpty())
        assertTrue(signer.signed.isEmpty())
        assertTrue(person.notices.isEmpty())
    }

    @Test fun creditIsNotBoughtForAnOrg() = runBlocking {
        for (op in listOf("buy_credit", "buy_credit_status", "buy_credit_cancel")) {
            val r = host().handle(message(op), orgCaller())
            assertEquals(op, "RESTRICTED", r.code())
            assertEquals("Peel buys credit only for a public app it opened itself.", r.text())
        }
    }

    @Test fun anOrgPageAsksTheSameWalletQuestions() = runBlocking {
        val cap = host().handle(message("capability", app()), orgCaller()).getJSONObject("result")
        assertEquals(0, cap.getJSONArray("methods").length())
        assertEquals("RESTRICTED", host().handle(message("capability", app(request = false)), orgCaller()).code())
        // No spending wallet here: a call is refused, whichever org's app names it.
        val call = host().handle(message("call", app()).put("method", "get_info"), orgCaller())
        assertEquals("NOT_IMPLEMENTED", call.code())
        assertEquals("BAD_REQUEST", host().handle("x", orgCaller()).code())
    }

    @Test fun theCallersPersonIsTheOneAsked() = runBlocking {
        val other = FakePerson()
        val r = host().handle(signIn(), WalletCaller(WalletScope.Origin(origin), "shop.example.com", true, other))
        assertNotNull(r.optJSONObject("result"))
        assertEquals(1, other.asks.size)
        assertTrue(person.asks.isEmpty())
        // The sheet names the host from the scope, the connection the place from the caller's own record.
        assertEquals("shop.example.com", other.asks.single().host)
        assertEquals("shop.example.com", connections.rows.getValue(scope).place)
    }

    @Test fun aConnectionIsKeptUnderItsScopesKey() = runBlocking {
        host().handle(signIn(), caller())
        assertEquals(setOf(scope), connections.rows.keys)
        // A silent sign-in reads the same key, and tells the person by the scope's host.
        host().handle(signIn(), caller(acted = false))
        assertEquals(listOf("shop.example.com" to SignInKey.root), person.notices)
    }

    @Test fun notificationsReachEveryListenerAndOnlyTheirOwnIsForAPage() = runBlocking {
        val h = host()
        val first = mutableListOf<WalletPush>()
        val second = mutableListOf<WalletPush>()
        val job1 = kotlinx.coroutines.GlobalScope.launch(Dispatchers.Unconfined) { h.notifications.collect { first += it } }
        val job2 = kotlinx.coroutines.GlobalScope.launch(Dispatchers.Unconfined) { h.notifications.collect { second += it } }
        val push = WalletPush("org:acme", "acme/board", JSONObject().put("v", 1))
        assertTrue(h.publish(push))
        assertEquals(listOf(push), first)
        assertEquals(listOf(push), second)
        job1.cancel(); job2.cancel()
        // Nobody listening: nothing is lost that anyone wanted, and it is no failure.
        assertTrue(h.publish(push))
    }
}
