package com.sphinx.peel.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
    private fun host(sheetTimeoutMs: Long = 5000) = WalletHost(connections, signer, person, { now }, sheetTimeoutMs)
    private val origin = "https://shop.example.com"
    private fun caller(acted: Boolean = true) = WalletCaller.page(origin, acted)
    private val scope = "origin:$origin"

    private fun signIn(challenge: String = ROOT_CHALLENGE, seller: String = origin) = JSONObject().put("v", 1).put("op", "sign_in")
        .put("seller", seller).put("challenge", challenge)
        .put("app", JSONObject().put("name", "moon-game").put("version", "v3").put("title", "Moon Game"))

    private fun JSONObject.code() = getJSONObject("error").getString("code")
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
        assertEquals("BAD_REQUEST", h.handle(JSONObject().put("v", 1).put("op", "nope"), caller()).code())
        assertEquals("That is not a wallet message Peel reads.", h.handle(JSONObject().put("v", 1), caller()).text())
        val call = h.handle(JSONObject().put("v", 1).put("op", "call").put("method", "pay"), caller())
        assertEquals("NOT_IMPLEMENTED", call.code())
        assertEquals("Peel's wallet has no method pay.", call.text())
        val cap = h.handle(JSONObject().put("v", 1).put("op", "capability"), caller()).getJSONObject("result")
        assertFalse(cap.getBoolean("connected"))
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
        connections.save(WalletConnection(scope, "a", "A", "shop.example.com", emptyList(), SignInKey.root, 1))
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
        connections.save(WalletConnection(scope, "old", "Old", "shop.example.com", listOf("pay"), null, 1))
        person.answer = { connections.rows.remove(scope); SignInKey.root }
        val r = host().handle(signIn(), caller())
        assertNotNull(r.optJSONObject("result"))
        val saved = connections.rows.getValue(scope)
        assertTrue(saved.methods.isEmpty())
        assertEquals("moon-game", saved.app)
        assertEquals(SignInKey.root, saved.signIn)
    }

    @Test fun aHeldConnectionKeepsItsMethods() = runBlocking {
        connections.save(WalletConnection(scope, "old", "Old", "shop.example.com", listOf("pay"), null, 1))
        host().handle(signIn(), caller())
        assertEquals(listOf("pay"), connections.rows.getValue(scope).methods)
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
        val failing = WalletHost(connections, { _, _ -> throw IllegalStateException("no key") }, person, { now })
        val r = failing.handle(signIn(), caller())
        assertEquals("INTERNAL", r.code())
        assertEquals("no key", r.text())
        assertNull(r.optJSONObject("result"))
    }
}
