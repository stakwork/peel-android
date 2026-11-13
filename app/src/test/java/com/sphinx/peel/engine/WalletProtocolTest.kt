package com.sphinx.peel.engine

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

/// strut's wallet vectors (specs/WALLET.md §7), the same file iOS holds (`Tests/PeelCoreTests/WalletVectors/vectors.json`, byte for byte; strut at the merge
/// of #645 per `Vendor/strut.lock`) run through the port of the checks, as iOS's `WalletProtocolTests` does. `scenarios` are a host's behaviour against a
/// scripted wallet (their replay is the wallet host's, step 6); `host_capabilities`, `credit_*` and `sign_in_*` belong to the host's own ops.
@RunWith(RobolectricTestRunner::class)
class WalletProtocolTest {
    private val all = JSONObject(javaClass.getResource("/wallet-vectors.json")!!.readText())
    private fun list(key: String): List<JSONObject> = all.getJSONArray(key).let { a -> List(a.length()) { a.getJSONObject(it) } }

    /// Equal as JSON: the same keys and values, numbers by value.
    private fun same(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> a.length() == b.length() && a.keys().asSequence().all { b.has(it) && same(a.get(it), b.get(it)) }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { same(a.get(it), b.get(it)) }
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }

    @Test fun `the vectors are there`() {
        assertEquals(1, all.getInt("version"))
        assertEquals(31, list("params").size)
        assertEquals(14, list("results").size)
        assertEquals(5, list("notifications").size)
        assertEquals(6, list("connection_requests").size)
        assertEquals(4, list("preimages").size)
        assertEquals(10, list("scenarios").size)
    }

    @Test fun `params are checked and normalized as strut does`() {
        for (entry in list("params")) {
            val name = entry.getString("name")
            val method = WalletMethod.of(entry.getString("method")) ?: error(name)
            val expect = entry.getJSONObject("expect")
            val result = WalletProtocol.checkParams(method, entry.opt("params"))
            if (expect.has("error")) {
                val refusal = result.exceptionOrNull() as? WalletRefusal
                assertNotNull("$name: accepted ${result.getOrNull()}", refusal)
                assertEquals(name, WalletError.BAD_REQUEST, refusal!!.code)
                assertEquals(expect.getString("error"), "BAD_REQUEST")
            } else {
                val params = result.getOrNull()
                assertNotNull("$name: refused, ${result.exceptionOrNull()?.message}", params)
                assertTrue("$name: $params is not ${expect.get("params")}", same(params, expect.get("params")))
            }
        }
    }

    @Test fun `results are the shape the contract promises or are not`() {
        for (entry in list("results")) {
            val problems = WalletProtocol.resultProblems(WalletMethod.of(entry.getString("method"))!!, entry.get("result"))
            assertEquals("${entry.getString("name")}: $problems", entry.getBoolean("valid"), problems.isEmpty())
        }
    }

    @Test fun `notifications are the shape the contract promises or are not`() {
        for (entry in list("notifications")) {
            val problems = WalletProtocol.notificationProblems(entry.get("notification"))
            assertEquals("${entry.getString("name")}: $problems", entry.getBoolean("valid"), problems.isEmpty())
        }
    }

    @Test fun `connection requests are good or are not, and read only when good`() {
        for (entry in list("connection_requests")) {
            val problems = WalletProtocol.connectionRequestProblems(entry.get("request"))
            assertEquals("${entry.getString("name")}: $problems", entry.getBoolean("valid"), problems.isEmpty())
            assertEquals(entry.getString("name"), problems.isEmpty(), WalletRequest.read(entry.get("request")) != null)
        }
    }

    @Test fun `preimages prove their hash`() {
        for (entry in list("preimages")) {
            assertEquals(entry.toString(), entry.getBoolean("valid"), WalletProtocol.verifyPreimage(entry.getString("preimage"), entry.getString("payment_hash")))
        }
    }

    @Test fun `a sphinx address splits into its key and hint`() {
        val address = SphinxAddress.of(" 02AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA_03bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb_1099511627776 ")
        assertEquals("02" + "a".repeat(64), address?.pubkey)
        assertEquals("03" + "b".repeat(64), address?.lsp)
        assertEquals("1099511627776", address?.scid)
        assertNull(SphinxAddress.of("02aa_03bb_1"))
        assertEquals("03bb_12", WalletProtocol.routeHint("03bb", "12"))
        assertNull(WalletProtocol.routeHint("03bb", "1x2x3"))
        assertNull(WalletProtocol.routeHint("03bb", "99999999999999999999"))
        assertEquals("[[{\"pubkey\":\"03${"b".repeat(64)}\",\"short_channel_id\":\"1099511627776\"}]]".length, address!!.routeHints.toString().length)
    }

    /// Every error is a JSON-RPC error with the wallet code in `data.code`; `BAD_REQUEST` is Invalid params.
    @Test fun `errors are JSON-RPC errors`() {
        assertEquals(-32602, WalletRefusal(WalletError.BAD_REQUEST, "no").body.getJSONObject("error").getInt("code"))
        val quota = WalletRefusal(WalletError.QUOTA_EXCEEDED, "over").body.getJSONObject("error")
        assertEquals(-32000, quota.getInt("code"))
        assertEquals("QUOTA_EXCEEDED", quota.getJSONObject("data").getString("code"))
        assertEquals("over", quota.getString("message"))
    }

    @Test fun `every wallet error code is known`() {
        assertEquals(12, WalletError.entries.size)
    }

    // A few refusals' words, which an app reads (iOS `check`).
    @Test fun `the refusals are worded as iOS words them`() {
        fun words(method: WalletMethod, json: String) = WalletProtocol.checkParams(method, JSONObject(json)).exceptionOrNull()!!.message
        val bolt = "lnbc1" + "q".repeat(30)
        assertEquals("the invoice names no amount: give amount", words(WalletMethod.PAY_INVOICE, """{"invoice":"$bolt"}"""))
        assertEquals("amount must be a whole number of msat above 0", words(WalletMethod.PAY_KEYSEND, """{"pubkey":"02${"a".repeat(64)}","amount":0}"""))
        assertEquals("give exactly one of transaction_id, payment_hash, invoice", words(WalletMethod.LOOKUP_PAYMENT, "{}"))
        assertEquals("limit must be 1 to 100", words(WalletMethod.LIST_TRANSACTIONS, """{"limit":101}"""))
        assertEquals("from is after until", words(WalletMethod.LIST_TRANSACTIONS, """{"from":5,"until":4}"""))
        assertEquals("params must be an object", WalletProtocol.checkParams(WalletMethod.PAY_INVOICE, "x").exceptionOrNull()!!.message)
        assertEquals("invoice must be a BOLT11 string", words(WalletMethod.PAY_INVOICE, "{}"))
        assertTrue(WalletProtocol.checkParams(WalletMethod.GET_BALANCE, null).isSuccess)
        // Metadata past 4096 characters as JSON is refused.
        assertEquals("metadata is longer than 4096 characters as JSON", words(WalletMethod.MAKE_INVOICE, """{"amount":1,"metadata":{"k":"${"x".repeat(4100)}"}}"""))
        assertFalse(WalletProtocol.invoiceNamesAmount("LNBC1" + "q".repeat(30)) ?: false)
        assertEquals(true, WalletProtocol.invoiceNamesAmount("lnbc10n1" + "q".repeat(30)))
    }

    // PR #74's tests of how a number is read (iOS `WalletProtocol.integer`), kept.
    @Test fun integers() {
        assertEquals(1L, WalletProtocol.integer(1))
        assertEquals(1L, WalletProtocol.integer(1L))
        assertEquals(1L, WalletProtocol.integer(1.0))
        assertEquals(1L, WalletProtocol.integer(1.0f))
        assertEquals(-3L, WalletProtocol.integer(-3.0))
        assertEquals(1L, WalletProtocol.integer(java.math.BigDecimal("1.00")))
        assertEquals(1L, WalletProtocol.integer(java.math.BigInteger.ONE))
        assertEquals(WalletProtocol.MAX_SAFE, WalletProtocol.integer(WalletProtocol.MAX_SAFE))
        assertEquals(-WalletProtocol.MAX_SAFE, WalletProtocol.integer(-WalletProtocol.MAX_SAFE))
    }

    @Test fun notIntegers() {
        assertNull(WalletProtocol.integer(1.5))
        assertNull(WalletProtocol.integer(java.math.BigDecimal("1.5")))
        assertNull(WalletProtocol.integer("1"))
        assertNull(WalletProtocol.integer(true))
        assertNull(WalletProtocol.integer(null))
        assertNull(WalletProtocol.integer(JSONObject.NULL))
        assertNull(WalletProtocol.integer(Double.NaN))
        assertNull(WalletProtocol.integer(Double.POSITIVE_INFINITY))
        assertNull(WalletProtocol.integer(WalletProtocol.MAX_SAFE + 1))
        assertNull(WalletProtocol.integer(-WalletProtocol.MAX_SAFE - 1))
        assertNull(WalletProtocol.integer(9007199254740992.0))
        assertNull(WalletProtocol.integer(java.math.BigInteger.TWO.pow(70)))
    }
}
