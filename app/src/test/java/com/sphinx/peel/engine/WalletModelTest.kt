package com.sphinx.peel.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// The wallet's records and budget: iOS `WalletHostTests` (periods, what a budget counts), `WalletProtocolTests` (the preimage vectors), strut's `results` and
/// `notifications` vectors for the shapes a record makes, and `WalletConnection`'s grant. Robolectric for `org.json`.
@RunWith(RobolectricTestRunner::class)
class WalletModelTest {
    private val vectors: JSONObject = JSONObject(javaClass.getResource("/wallet-vectors.json")!!.readText())

    private fun record(
        id: String, state: WalletRecord.State, amount: Long, fees: Long? = null, createdAt: Long = 10,
        direction: WalletRecord.Direction = WalletRecord.Direction.OUTGOING, type: WalletRecord.PaymentType = WalletRecord.PaymentType.KEYSEND,
    ) = WalletRecord(id, direction, type, state, "", amount, fees, createdAt = createdAt, connection = "c", app = "a")

    // MARK: Budgets (iOS `WalletHostTests`)

    @Test fun `periods renew from creation`() {
        val day = 86_400L
        assertEquals(1_000L to null, WalletBudget.period(1_000, WalletRenewal.NEVER, 99_999_999))
        assertEquals((1_000 + day * 3) to (1_000 + day * 4), WalletBudget.period(1_000, WalletRenewal.DAILY, 1_000 + day * 3 + 5))
        assertEquals(1_000L to (1_000 + 7 * day), WalletBudget.period(1_000, WalletRenewal.WEEKLY, 1_000 + 6 * day))
        // 2026-01-31: February is short, so it renews on its last day.
        val jan31 = 1_769_817_600L
        val (start, renews) = WalletBudget.period(jan31, WalletRenewal.MONTHLY, jan31 + 40 * day)
        assertEquals(1_772_236_800L, start) // 2026-02-28
        assertEquals(1_774_915_200L, renews) // 2026-03-31
        assertEquals(1_801_353_600L, WalletBudget.period(jan31, WalletRenewal.YEARLY, jan31 + 400 * day).first) // 2027-01-31
    }

    @Test fun `a period before its creation is its start, and the boundaries are exact`() {
        assertEquals(1_000L to null, WalletBudget.period(1_000, WalletRenewal.DAILY, 500))
        val day = 86_400L
        // On the boundary a new period has started.
        assertEquals((1_000 + day) to (1_000 + 2 * day), WalletBudget.period(1_000, WalletRenewal.DAILY, 1_000 + day))
        assertEquals(1_000L to (1_000 + day), WalletBudget.period(1_000, WalletRenewal.DAILY, 1_000 + day - 1))
        val jan31 = 1_769_817_600L
        // Right at its first renewal and the second.
        assertEquals(1_772_236_800L, WalletBudget.period(jan31, WalletRenewal.MONTHLY, 1_772_236_800L).first)
        assertEquals(jan31 to 1_772_236_800L, WalletBudget.period(jan31, WalletRenewal.MONTHLY, 1_772_236_799L))
        // A leap year keeps its day: 2028-02-29 + 1 year is 2029-02-28.
        val leap = 1_835_395_200L // 2028-02-29
        assertEquals(1_866_931_200L, WalletBudget.period(leap, WalletRenewal.YEARLY, leap + 10).second) // 2029-02-28
    }

    @Test fun `a budget counts pending settled with fees and not failed`() {
        val entries = listOf(
            record("a", WalletRecord.State.PENDING, 100),
            record("b", WalletRecord.State.SETTLED, 200, fees = 5),
            record("c", WalletRecord.State.FAILED, 400),
            record("d", WalletRecord.State.SETTLED, 800, direction = WalletRecord.Direction.INCOMING, type = WalletRecord.PaymentType.BOLT11),
            record("e", WalletRecord.State.PENDING, 1_600, createdAt = 5),
        ).mapNotNull { it.entry }
        assertEquals(305L, WalletBudget.used(entries, 10))
        assertTrue(WalletBudget.Guard(400, 10).admits(95, entries))
        assertFalse(WalletBudget.Guard(400, 10).admits(96, entries))
        assertTrue(WalletBudget.Guard(null, 10).admits(Long.MAX_VALUE, entries))
        // An expired invoice made counts nothing, and a settled payment with no fee known counts its amount.
        assertEquals(0L, record("x", WalletRecord.State.EXPIRED, 5).entry!!.countedMsat)
        assertEquals(7L, record("y", WalletRecord.State.SETTLED, 7).entry!!.countedMsat)
        assertEquals(0L, WalletBudget.used(emptyList(), 0))
        // Never overflows: a payment past the largest amount is never admitted.
        assertFalse(WalletBudget.Guard(Long.MAX_VALUE, 0).admits(Long.MAX_VALUE, entries))
    }

    // MARK: A connection's grant

    @Test fun `what connect answers is the grant as the contract shapes it`() {
        val full = WalletConnection(
            "org:acme", "acme/board", "Board", "Acme", listOf(WalletMethod.PAY_KEYSEND, WalletMethod.GET_BUDGET),
            listOf(WalletNotificationType.PAYMENT_SENT), 5_000_000, WalletRenewal.WEEKLY, 1_794_000_000, OverBudget.REFUSE, 1_793_800_000,
        )
        assertEquals(
            """{"methods":["pay_keysend","get_budget"],"notification_types":["payment_sent"],"max_amount":5000000,"renewal_period":"weekly","expires_at":1794000000}""",
            JSONObject(full.granted.toString()).let { sorted(it) },
        )
        // No limit: no amount and no renewal in it; nothing at all but the lists.
        val bare = WalletConnection("origin:https://x.example", "/x", "X", "x.example", createdAt = 1)
        assertEquals("""{"methods":[],"notification_types":[]}""", sorted(bare.granted))
    }

    private fun sorted(o: JSONObject): String {
        val order = listOf("methods", "notification_types", "max_amount", "renewal_period", "expires_at")
        return order.filter { o.has(it) }.joinToString(",", "{", "}") { "\"$it\":${o.get(it).let { v -> if (v is JSONArray) v.toString() else if (v is String) "\"$v\"" else v.toString() }}" }
    }

    @Test fun `a connection expires at its time`() {
        val c = WalletConnection("k", "a", "A", "p", expiresAt = 100, createdAt = 1)
        assertFalse(c.isExpired(99))
        assertTrue(c.isExpired(100))
        assertFalse(WalletConnection("k", "a", "A", "p", createdAt = 1).isExpired(Long.MAX_VALUE))
    }

    @Test fun `methods that pay are the ones a budget counts, and connect and get_info are never requested`() {
        assertEquals(setOf(WalletMethod.PAY_INVOICE, WalletMethod.PAY_KEYSEND, WalletMethod.PAY_LIGHTNING_ADDRESS), WalletMethod.entries.filter { it.pays }.toSet())
        assertEquals(WalletMethod.entries.size - 2, WalletMethod.entries.count { it.isRequestable })
        assertFalse(WalletMethod.CONNECT.isRequestable || WalletMethod.GET_INFO.isRequestable)
        assertEquals(WalletMethod.GET_INFO, WalletMethod.of("get_info"))
        assertNull(WalletMethod.of("nope"))
        assertEquals(listOf("never", "daily", "weekly", "monthly", "yearly"), WalletRenewal.entries.map { it.wire })
        assertEquals(listOf("payment_received", "payment_sent", "payment_failed"), WalletNotificationType.entries.map { it.wire })
    }

    // MARK: strut's vectors

    private fun result(name: String): JSONObject {
        val results = vectors.getJSONArray("results")
        for (i in 0 until results.length()) results.getJSONObject(i).let { if (it.getString("name") == name) return it.getJSONObject("result") }
        error(name)
    }

    /// Equal as JSON values: the same keys, the same values.
    private fun assertJson(expected: JSONObject, actual: JSONObject) {
        assertEquals(expected.keys().asSequence().toSet(), actual.keys().asSequence().toSet())
        for (k in expected.keys()) {
            val e = expected.get(k)
            val a = actual.get(k)
            if (e is JSONObject) assertJson(e, a as JSONObject) else assertEquals(k, e.toString(), a.toString())
        }
    }

    private val hash1 = "66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925"
    private val hash2 = "72cd6e8422c407fb6d098690f1130b7ded7ec2f7f5e1d30bd9d521f015363793"
    private val hash3 = "52fe6094743bfd4f9be4321d98adc7e23c1ab622b0ba830e271d1ee1cbfd7850"
    private val zeros = "0".repeat(64)
    private val ones = "01".repeat(32)

    @Test fun `a pay answer is settled only with its preimage and pending otherwise`() {
        val settled = WalletRecord(
            "tx-1", WalletRecord.Direction.OUTGOING, WalletRecord.PaymentType.BOLT11, WalletRecord.State.SETTLED, hash1, 21_000, 10, zeros,
            1_793_800_000, 1_793_800_002, connection = "c", app = "a",
        )
        assertJson(result("pay_invoice settled"), settled.payment)
        // Settled with no proof is no settled answer: it reads as pending, and never carries the preimage it does not have.
        val unproven = settled.copy(preimage = null)
        assertFalse(unproven.provenSettled)
        assertEquals("pending", unproven.payment.getString("state"))
        assertFalse(unproven.payment.has("preimage") || unproven.payment.has("settled_at") || unproven.payment.has("fees_paid"))
        assertTrue(settled.provenSettled)
        val pending = WalletRecord("tx-2", WalletRecord.Direction.OUTGOING, WalletRecord.PaymentType.KEYSEND, WalletRecord.State.PENDING, hash2, 20_000, createdAt = 1_793_800_000, connection = "c", app = "a")
        assertJson(result("pay_keysend pending: no preimage yet"), pending.payment)
        val failed = WalletRecord("tx-3", WalletRecord.Direction.OUTGOING, WalletRecord.PaymentType.KEYSEND, WalletRecord.State.FAILED, hash3, 20_000, createdAt = 1_793_800_000, failureReason = "no route", connection = "c", app = "a")
        assertJson(result("pay_keysend failed with a reason"), failed.payment)
        // A pending record never carries a preimage even when it has one.
        assertFalse(pending.copy(preimage = ones).payment.has("preimage"))
        // Settled with a proof but no settled time says when it was made.
        assertEquals(1_793_800_000L, settled.copy(settledAt = null).payment.getLong("settled_at"))
    }

    @Test fun `an invoice made is a transaction with its expiry`() {
        val made = WalletRecord(
            hash3, WalletRecord.Direction.INCOMING, WalletRecord.PaymentType.BOLT11, WalletRecord.State.PENDING, hash3, 500_000,
            createdAt = 1_793_800_000, expiresAt = 1_793_803_600,
            invoice = "lnbc5u1pqpzry9x8gf2tvdw0s3jn54khce6mua7lqpzry9x8gf2tvdw0s3jn54khce6mua7l", description = "Q3 report", connection = "c", app = "a",
        )
        val shape = result("make_invoice")
        assertJson(shape, made.transaction.apply { put("transaction_id", JSONObject.NULL); remove("transaction_id") })
    }

    @Test fun `a settled keysend is a transaction and a lookup as strut's vectors have them`() {
        val settled = WalletRecord(
            "tx-2", WalletRecord.Direction.OUTGOING, WalletRecord.PaymentType.KEYSEND, WalletRecord.State.SETTLED, hash2, 20_000, 1_000, ones,
            1_793_800_000, 1_793_803_000, pubkey = "02" + "a".repeat(64), connection = "c", app = "a",
        )
        val notification = vectors.getJSONArray("notifications").getJSONObject(0).getJSONObject("notification").getJSONObject("notification")
        assertJson(notification, settled.transaction)
        assertJson(result("lookup_payment of a settled keysend"), settled.lookup)
        // Before it settled, a lookup carries no fee, no time and no preimage; a failure its reason.
        val pending = settled.copy(state = WalletRecord.State.PENDING)
        assertFalse(pending.lookup.has("settled_at") || pending.lookup.has("fees_paid") || pending.lookup.getJSONObject("details").has("preimage"))
        val failed = settled.copy(state = WalletRecord.State.FAILED, failureReason = "no route")
        assertEquals("no route", failed.lookup.getString("failure_reason"))
        assertEquals("no route", failed.transaction.getString("failure_reason"))
        // A bolt11 lookup names its invoice and memo, not a pubkey.
        val bolt = settled.copy(paymentType = WalletRecord.PaymentType.BOLT11, invoice = "lnbc1", description = "memo").lookup.getJSONObject("details")
        assertEquals("lnbc1", bolt.getString("invoice"))
        assertEquals("memo", bolt.getString("description"))
        assertFalse(bolt.has("pubkey"))
    }

    // MARK: The preimage

    @Test fun `a preimage proves a payment only if it hashes to it`() {
        val list = vectors.getJSONArray("preimages")
        assertEquals(4, list.length())
        for (i in 0 until list.length()) {
            val v = list.getJSONObject(i)
            assertEquals(v.toString(), v.getBoolean("valid"), WalletProtocol.verifyPreimage(v.getString("preimage"), v.getString("payment_hash")))
        }
        // In any case, and only 64 hex characters.
        assertTrue(WalletProtocol.verifyPreimage(zeros.uppercase(), hash1.uppercase()))
        assertFalse(WalletProtocol.verifyPreimage("g".repeat(64), hash1))
        assertFalse(WalletProtocol.verifyPreimage(zeros, "00"))
        assertTrue(WalletProtocol.isHash(hash1) && !WalletProtocol.isHash(hash1 + "0"))
    }
}
