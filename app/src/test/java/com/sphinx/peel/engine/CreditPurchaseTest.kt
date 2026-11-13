package com.sphinx.peel.engine

import androidx.test.core.app.ApplicationProvider
import com.sphinx.peel.storage.Storage
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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

/// Buying a visitor's credit through the host (`buy_credit`, strut's specs/WALLET.md §1.2; iOS `CreditPurchaseTests`; docs/phases/34-buy-credit.md): strut's vectors
/// (what may be bought, how strut's UI reads the answer and a host's capability) and the host's guard, sheet, single payment, status and withdrawal, over a scripted
/// wallet and person and a real `Storage`. (The Mac's `.remote` scope, and so its "another seller than the page's" case, does not exist here.)
@RunWith(RobolectricTestRunner::class)
class CreditPurchaseTest {
    private val storage = Storage(ApplicationProvider.getApplicationContext(), account = "credit-${System.nanoTime()}")
    private val connections = StorageWalletConnections(storage)
    private val all = JSONObject(javaClass.getResource("/wallet-vectors.json")!!.readText())
    private fun list(key: String): List<JSONObject> = all.getJSONArray(key).let { a -> List(a.length()) { a.getJSONObject(it) } }

    private val nowS = 1_793_800_000L
    private val origin = WalletScope.Origin("https://shop.example.com")
    private val other = WalletScope.Origin("https://other.example.com")
    private val preimage = "42".repeat(32)
    private val hash = MessageDigest.getInstance("SHA-256").digest(ByteArray(32) { 0x42 }).joinToString("") { "%02x".format(it) }
    private val creditKey = WalletHost.creditKey(origin)

    // MARK: strut's vectors

    @Test fun `the credit vectors are there`() {
        assertEquals(16, list("credit_purchases").size)
        assertEquals(15, list("credit_replies").size)
        assertEquals(9, list("host_capabilities").size)
    }

    @Test fun `a purchase as strut reads it`() {
        for (v in list("credit_purchases")) {
            val problems = CreditPurchase.problems(v.opt("purchase"))
            assertEquals("${v.getString("name")}: $problems", v.getBoolean("valid"), problems.isEmpty())
            // `of` agrees: a purchase only when there are no problems.
            assertEquals(v.getString("name"), v.getBoolean("valid"), CreditPurchase.of(v.opt("purchase")) != null)
        }
    }

    @Test fun `a reply as strut's UI reads it`() {
        for (v in list("credit_replies")) {
            val name = v.getString("name")
            val read = CreditReply.read(v.opt("reply"), v.getString("payment_hash"))
            assertEquals(name, v.getString("outcome"), read.outcome)
            assertEquals(name, v.optString("code", "").ifEmpty { null }, read.code)
            assertEquals(name, v.optString("preimage", "").ifEmpty { null }, read.preimage)
        }
    }

    @Test fun `a host's capability as strut reads it`() {
        for (v in list("host_capabilities")) {
            val name = v.getString("name")
            val read = HostCapability.read(v.opt("capability"))
            val expect = v.opt("expect")
            if (expect == null || expect == JSONObject.NULL) {
                assertNull(name, read)
            } else {
                expect as JSONObject
                assertNotNull(name, read)
                val ops = expect.getJSONArray("ops")
                assertEquals(name, List(ops.length()) { ops.getString(it) }, read!!.ops)
                assertEquals(name, expect.optString("wallet", "").ifEmpty { null }, read.wallet)
            }
        }
    }

    @Test fun `what the host itself answers reads as every op and its name`() = runBlocking {
        val read = HostCapability.read(host(ScriptedWallet()).handle(JSONObject().put("v", 1).put("op", "host_capability"), caller(ScriptedPerson())).getJSONObject("result"))
        assertEquals(HostCapability(WalletHost.VISITOR_OPS, "Peel"), read)
        val org = host(ScriptedWallet()).handle(JSONObject().put("v", 1).put("op", "host_capability"), caller(ScriptedPerson(), WalletScope.Org("acme")))
        assertEquals(HostCapability(emptyList(), "Peel"), HostCapability.read(org.getJSONObject("result")))
    }

    // MARK: The guard

    @Test fun `only a visitor's page buys credit`() = runBlocking {
        val wallet = ScriptedWallet(); val person = ScriptedPerson()
        val h = host(wallet)
        val org = caller(person, WalletScope.Org("acme"))
        assertEquals("RESTRICTED", code(h.handle(buy(), org)))
        assertEquals("RESTRICTED", code(h.handle(status(hash), org)))
        // A cancel asks for nothing and answers `{}` for anyone (iOS `withdrawCredit`): there is no sheet of an org's to take down.
        assertEquals(JSONObject().put("result", JSONObject()).toString(), h.handle(cancel(hash), org).toString())
        assertEquals("Peel buys credit only for a public app it opened itself.", h.handle(buy(), org).getJSONObject("error").getString("message"))
        assertTrue(person.takeShown().isEmpty() && wallet.calls.isEmpty())
    }

    @Test fun `what is never paid or asked`() = runBlocking {
        val wallet = ScriptedWallet(); val person = ScriptedPerson()
        val h = host(wallet)
        val bad = purchase().put("amount", 1)
        assertEquals("BAD_REQUEST", code(h.handle(buy(bad), caller(person))))
        assertEquals("BAD_REQUEST", code(h.handle(buy(JSONObject.NULL), caller(person))))
        wallet.script(null, decoded = 249_000_000); wallet.decodes(hash, nowS + 600)
        assertEquals("another amount", "BAD_REQUEST", code(h.handle(buy(), caller(person))))
        wallet.script(null, decoded = 250_000_000); wallet.decodes("a".repeat(64), nowS + 600)
        assertEquals("another hash", "BAD_REQUEST", code(h.handle(buy(), caller(person))))
        assertEquals("The invoice is not the one the server describes.", h.handle(buy(), caller(person)).getJSONObject("error").getString("message"))
        wallet.decodes(hash, nowS)
        assertEquals("ended", "BAD_REQUEST", code(h.handle(buy(), caller(person))))
        wallet.decodes(hash, nowS + 600)
        wallet.failNextProblem(WalletRefusal(WalletError.INSUFFICIENT_BALANCE, "not enough"))
        assertEquals("INSUFFICIENT_BALANCE", code(h.handle(buy(), caller(person))))
        wallet.failNextProblem(null)
        assertEquals("RESTRICTED", code(h.handle(buy(), caller(person, acted = false))))
        assertTrue(person.takeShown().isEmpty())
        assertFalse(wallet.calls.contains("pay"))
    }

    @Test fun `the status asks for a hash`() = runBlocking {
        assertEquals("BAD_REQUEST", code(host(ScriptedWallet()).handle(status("nope"), caller(ScriptedPerson()))))
    }

    // MARK: The sheet and the payment

    @Test fun `a yes pays once and the answer is paid`() = runBlocking {
        val wallet = ScriptedWallet(); val person = ScriptedPerson()
        val h = host(wallet)
        ready(wallet); person.will("approve"); wallet.script(settled())
        val reply = h.handle(buy(purchase(seller = "https://elsewhere.example")), caller(person))
        assertEquals(CreditReply("paid", null, preimage), CreditReply.read(reply, hash))
        val sheet = person.lastCredit!!
        assertEquals("shop.example.com", sheet.host)
        assertEquals(250_000_000L, sheet.amountMsat)
        assertEquals(250.0, sheet.usd, 0.0)
        assertEquals("Voxel Studio", sheet.title)
        assertEquals("acme/voxel/character", sheet.workflow)
        assertEquals(nowS + 600, sheet.expiresAt)
        assertEquals(1, wallet.records(creditKey).size)
        assertEquals(listOf("credit"), person.takeShown())
        // No connection, so nothing for a page to be told of.
        assertNull(connections.connection(creditKey))

        val again = h.handle(buy(), caller(person, acted = false))
        assertEquals("paid", CreditReply.read(again, hash).outcome)
        assertTrue(person.takeShown().isEmpty())
        assertEquals(1, wallet.calls.count { it == "pay" })
    }

    @Test fun `a pending payment and its status, to its page alone`() = runBlocking {
        val wallet = ScriptedWallet(); val person = ScriptedPerson()
        val h = host(wallet)
        ready(wallet); person.will("approve"); wallet.script(pending())
        assertEquals("pending", CreditReply.read(h.handle(buy(), caller(person)), hash).outcome)
        assertEquals("pending", CreditReply.read(h.handle(status(hash), caller(person)), hash).outcome)
        wallet.event(JSONObject().put("notification_type", "payment_sent").put("notification", settled().getJSONObject("result")))
        assertEquals("paid", CreditReply.read(h.handle(status(hash), caller(person)), hash).outcome)
        assertEquals("NOT_FOUND", code(h.handle(status(hash), caller(person, other))))
        assertEquals("NOT_FOUND", code(h.handle(status("b".repeat(64)), caller(person))))
    }

    @Test fun `a decline pays nothing and strut shows its QR`() = runBlocking {
        val wallet = ScriptedWallet(); val person = ScriptedPerson()
        val h = host(wallet)
        ready(wallet); person.will("decline")
        val reply = h.handle(buy(), caller(person))
        assertEquals(CreditReply("fallback", "USER_REJECTED"), CreditReply.read(reply, hash))
        assertEquals("You declined to buy the credit.", reply.getJSONObject("error").getString("message"))
        assertFalse(wallet.calls.contains("pay"))
    }

    @Test fun `a failed payment is a fallback`() = runBlocking {
        val wallet = ScriptedWallet(); val person = ScriptedPerson()
        val h = host(wallet)
        ready(wallet); person.will("approve"); wallet.script(JSONObject().put("error", "PAYMENT_FAILED"))
        val reply = h.handle(buy(), caller(person))
        assertEquals(CreditReply("fallback", "PAYMENT_FAILED"), CreditReply.read(reply, hash))
        // Nothing was kept, and the next try may ask again.
        assertEquals(0, wallet.records(creditKey).size)
    }

    @Test fun `the page withdraws its sheet and the invoice ending takes it down`() = runBlocking {
        val wallet = ScriptedWallet(); val person = ScriptedPerson(absent = true)
        val h = host(wallet)
        ready(wallet)
        val asking = async(Dispatchers.Default) { h.handle(buy(), caller(person)) }
        for (i in 0 until 1000) { if (person.lastCredit != null) break; delay(5) }
        assertNotNull("the sheet never opened", person.lastCredit)
        // Another page's cancel, and one for another invoice, leave it up.
        assertEquals(JSONObject().put("result", JSONObject()).toString(), h.handle(cancel(hash), caller(person, other)).toString())
        assertEquals(JSONObject().put("result", JSONObject()).toString(), h.handle(cancel("b".repeat(64)), caller(person)).toString())
        delay(50)
        assertTrue(asking.isActive)
        h.handle(cancel(hash), caller(person))
        val reply = asking.await()
        assertEquals("USER_REJECTED", code(reply))
        assertEquals("The page stopped waiting, so Peel took its sheet down.", reply.getJSONObject("error").getString("message"))
        assertFalse(wallet.calls.contains("pay"))
        // The slot is free again: a cancel when nothing is up is just `{}`.
        assertEquals(JSONObject().put("result", JSONObject()).toString(), h.handle(cancel(hash), caller(person)).toString())

        // The invoice ends while the sheet is up: it comes down, RESTRICTED.
        wallet.decodes(hash, nowS + 1)
        val ended = h.handle(buy(), caller(person))
        assertEquals("RESTRICTED", code(ended))
        assertEquals("You did not answer in time.", ended.getJSONObject("error").getString("message"))
        assertFalse(wallet.calls.contains("pay"))
    }

    @Test fun `one sheet at a time holds for credit too`() = runBlocking {
        val wallet = ScriptedWallet(); val person = ScriptedPerson(absent = true)
        val h = host(wallet)
        ready(wallet)
        val first = async(Dispatchers.Default) { h.handle(buy(), caller(person)) }
        for (i in 0 until 1000) { if (person.lastCredit != null) break; delay(5) }
        val second = h.handle(buy(), caller(person, other))
        assertEquals("RATE_LIMITED", code(second))
        h.handle(cancel(hash), caller(person))
        assertEquals("USER_REJECTED", code(first.await()))
    }

    // MARK: Helpers

    private fun host(wallet: ScriptedWallet) = WalletHost(connections, wallet, { nowS * 1000 }, 60_000)
    private fun caller(person: ScriptedPerson, scope: WalletScope = origin, acted: Boolean = true) = WalletCaller(scope, "shop.example.com", acted, person)

    /// The decoder reads the purchase's invoice as described, ending in ten minutes; the wallet is not to be asked to pay until a test says so.
    private fun ready(wallet: ScriptedWallet) {
        wallet.script(null, decoded = 250_000_000)
        wallet.decodes(hash, nowS + 600)
    }

    /// strut's first valid purchase, for this test's hash.
    private fun purchase(seller: String = "https://shop.example.com/lab"): JSONObject =
        JSONObject(list("credit_purchases")[0].getJSONObject("purchase").toString())
            .put("payment_hash", hash).put("seller", seller).put("expires_at", nowS + 600)

    private fun buy(purchase: Any? = null) = JSONObject().put("v", 1).put("op", "buy_credit").put("purchase", purchase ?: purchase())
    private fun status(h: String) = JSONObject().put("v", 1).put("op", "buy_credit_status").put("payment_hash", h)
    private fun cancel(h: String) = JSONObject().put("v", 1).put("op", "buy_credit_cancel").put("payment_hash", h)

    private fun settled() = JSONObject().put(
        "result",
        JSONObject().put("transaction_id", hash).put("state", "settled").put("payment_hash", hash).put("amount", 250_000_000).put("fees_paid", 0)
            .put("preimage", preimage).put("created_at", nowS).put("settled_at", nowS),
    )

    private fun pending() = JSONObject().put(
        "result",
        JSONObject().put("transaction_id", hash).put("state", "pending").put("payment_hash", hash).put("amount", 250_000_000).put("created_at", nowS),
    )

    private fun code(reply: JSONObject): String? = reply.optJSONObject("error")?.optJSONObject("data")?.optString("code")
}
