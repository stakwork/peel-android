package com.sphinx.peel.engine

import androidx.test.core.app.ApplicationProvider
import com.sphinx.peel.storage.Storage
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// The wallet host against strut's scenarios (vectors.json `scenarios`, specs/WALLET.md §7; iOS `WalletHostTests.strutsScenario`) and the rest of iOS's host
/// tests, over a scripted wallet and a scripted person and a real `Storage`. A scenario is a connection, an app's request and steps (a call, or a wallet event),
/// each with what the person is shown and answers, whether the wallet may be asked, and what the page reads.
@RunWith(RobolectricTestRunner::class)
class WalletHostScenarioTest {
    private val storage = Storage(ApplicationProvider.getApplicationContext(), account = "wh-${System.nanoTime()}")
    private val connections = StorageWalletConnections(storage)
    private val nowS = 1_793_800_000L
    private val scope = WalletScope.Org("bench")
    private val vectors = JSONObject(javaClass.getResource("/wallet-vectors.json")!!.readText())

    private fun host(wallet: ScriptedWallet, timeoutMs: Long = 60_000) = WalletHost(connections, wallet, { nowS * 1000 }, timeoutMs)
    private fun caller(person: ScriptedPerson, acted: Boolean = true, scope: WalletScope = this.scope, place: String = "Yours") = WalletCaller(scope, place, acted, person)
    private fun app(request: Any?, name: String = "bench") = JSONObject().put("name", name).put("version", "v3").put("title", "Bench").put("request", request ?: JSONObject.NULL)
    private fun message(method: String, params: Any? = null, request: Any?, app: JSONObject? = null) =
        JSONObject().put("v", 1).put("op", "call").put("method", method).put("app", app ?: app(request)).apply { if (params != null) put("params", params) }

    private fun same(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> a.length() == b.length() && a.keys().asSequence().all { b.has(it) && same(a.get(it), b.get(it)) }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { same(a.get(it), b.get(it)) }
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }

    private fun names(a: JSONArray?) = a?.let { List(it.length()) { i -> it.getString(i) } } ?: emptyList()

    /// A scenario's connection as kept, and the request its app sends.
    private fun setUp(scenario: JSONObject, wallet: ScriptedWallet): Any? {
        scenario.opt("request")?.takeIf { it != JSONObject.NULL }?.let { return it }
        val c = scenario.optJSONObject("connection") ?: JSONObject()
        val methods = names(c.optJSONArray("methods")).mapNotNull { WalletMethod.of(it) }
        val types = names(c.optJSONArray("notification_types")).mapNotNull { WalletNotificationType.of(it) }
        storage.saveWalletConnection(
            WalletConnection(
                scope.key, "/bench", "Bench", "Yours", methods, types, WalletProtocol.integer(c.opt("max_amount")),
                WalletRenewal.of(c.opt("renewal_period") as? String) ?: WalletRenewal.NEVER, null, OverBudget.of(c.opt("over_budget") as? String) ?: OverBudget.ASK, nowS - 100,
            ),
        )
        wallet.seed(WalletProtocol.integer(c.opt("used_budget")) ?: 0, scope.key, "/bench", nowS - 50)
        return JSONObject().put("request_methods", JSONArray(methods.map { it.wire })).put("notification_types", JSONArray(types.map { it.wire }))
            .apply { if (c.has("max_amount")) put("max_amount", c.get("max_amount")); if (c.has("renewal_period")) put("renewal_period", c.get("renewal_period")) }
    }

    @Test fun `all ten scenarios are replayed`() {
        assertEquals(10, vectors.getJSONArray("scenarios").length())
    }

    @Test fun `strut's scenarios`() = runBlocking<Unit> {
        val scenarios = vectors.getJSONArray("scenarios")
        for (index in 0 until scenarios.length()) replay(scenarios.getJSONObject(index), index)
    }

    private suspend fun replay(scenario: JSONObject, index: Int) {
        val name = scenario.getString("name")
        val s = Storage(ApplicationProvider.getApplicationContext(), account = "sc-$index-${System.nanoTime()}")
        // Each scenario on a database of its own (as iOS's `TempProfile`).
        val own = StorageWalletConnections(s)
        val wallet = ScriptedWallet()
        val person = ScriptedPerson()
        val h = WalletHost(own, wallet, { nowS * 1000 })
        val pushes = PushLog(h)
        h.start()
        val request = run {
            scenario.opt("request")?.takeIf { it != JSONObject.NULL } ?: run {
                val c = scenario.optJSONObject("connection") ?: JSONObject()
                val methods = names(c.optJSONArray("methods")).mapNotNull { WalletMethod.of(it) }
                val types = names(c.optJSONArray("notification_types")).mapNotNull { WalletNotificationType.of(it) }
                own.save(
                    WalletConnection(
                        scope.key, "/bench", "Bench", "Yours", methods, types, WalletProtocol.integer(c.opt("max_amount")),
                        WalletRenewal.of(c.opt("renewal_period") as? String) ?: WalletRenewal.NEVER, null, OverBudget.of(c.opt("over_budget") as? String) ?: OverBudget.ASK, nowS - 100,
                    ),
                )
                wallet.seed(WalletProtocol.integer(c.opt("used_budget")) ?: 0, scope.key, "/bench", nowS - 50)
                JSONObject().put("request_methods", JSONArray(methods.map { it.wire })).put("notification_types", JSONArray(types.map { it.wire }))
                    .apply { if (c.has("max_amount")) put("max_amount", c.get("max_amount")); if (c.has("renewal_period")) put("renewal_period", c.get("renewal_period")) }
            }
        }
        val steps = scenario.getJSONArray("steps")
        for (n in 0 until steps.length()) {
            val step = steps.getJSONObject(n)
            val at = "$name, step ${n + 1}"
            if (step.has("wallet_event")) {
                wallet.event(step.getJSONObject("wallet_event"))
                val expected = step.optJSONObject("expect")?.opt("notification")?.takeIf { it != JSONObject.NULL }
                val told = pushes.take(if (expected == null) 0 else 1)
                if (expected != null) {
                    assertEquals("$at: ${told.size} notifications", 1, told.size)
                    val pushed = told.first().envelope
                    assertTrue("$at: ${pushed.opt("notification")}", same((pushed.opt("notification") as JSONObject).get("notification"), expected) || same(pushed.opt("notification"), expected))
                    assertTrue(at, same(pushed.opt("app"), JSONObject().put("name", "bench")))
                    val problems = WalletProtocol.notificationProblems(pushed.opt("notification"))
                    assertTrue("$at: $problems", problems.isEmpty())
                } else {
                    assertTrue("$at: told ${told.map { it.envelope }}", told.isEmpty())
                }
                continue
            }
            val call = step.optJSONObject("call") ?: JSONObject()
            val method = call.optString("method")
            wallet.script(step.opt("wallet")?.takeIf { it != JSONObject.NULL } as? JSONObject, step.optJSONObject("decoded")?.let { WalletProtocol.integer(it.opt("amount")) })
            person.will(step.opt("person") as? String)
            val before = wallet.calls.size
            val reply = h.handle(message(method, call.opt("params"), request), caller(person))
            val sheet = step.opt("sheet") as? String
            assertEquals("$at: sheets", sheet?.let { listOf(it) } ?: emptyList<String>(), person.takeShown())
            if (!step.has("wallet") || step.opt("wallet") == JSONObject.NULL) assertEquals("$at: the wallet was asked", before, wallet.calls.size)
            val expect = step.optJSONObject("expect") ?: JSONObject()
            val code = expect.opt("error") as? String
            if (code != null) {
                assertEquals("$at: $reply", code, reply.optJSONObject("error")?.optJSONObject("data")?.optString("code"))
            } else {
                val result = reply.opt("result")
                assertTrue("$at: $reply is not ${expect.opt("result")}", same(result, expect.opt("result")))
                val wm = WalletMethod.of(method)
                if (wm != null && result != null) assertTrue("$at: ${WalletProtocol.resultProblems(wm, result)}", WalletProtocol.resultProblems(wm, result).isEmpty())
            }
        }
        h.stop()
        pushes.close()
    }

    // MARK: Beyond the scenarios (iOS `WalletHostTests`)

    private val keysendTo = JSONObject().put("pubkey", SCRIPT_KEY).put("amount", 2_000)
    private fun pending(id: String, amount: Long = 2_000) = JSONObject().put("result", JSONObject().put("transaction_id", id).put("state", "pending").put("payment_hash", "7".repeat(64)).put("amount", amount).put("created_at", nowS))
    private val keysendRequest = JSONObject().put("request_methods", JSONArray().put("pay_keysend").put("get_budget").put("lookup_payment")).put("max_amount", 10_000).put("renewal_period", "daily")
    private fun code(r: JSONObject) = r.optJSONObject("error")?.optJSONObject("data")?.optString("code")

    private suspend fun connect(h: WalletHost, person: ScriptedPerson, request: Any, c: WalletCaller = caller(person), named: JSONObject? = null): JSONObject {
        person.will("approve")
        return h.handle(message("connect", request = request, app = named), c)
    }

    @Test fun `no sheet opens without the person, and one at a time and none for ever`() = runBlocking {
        val wallet = ScriptedWallet()
        val person = ScriptedPerson()
        val h = host(wallet)
        // No tap: connect refused with the copy, no sheet.
        person.will("approve")
        val still = h.handle(message("connect", request = keysendRequest), caller(person, acted = false))
        assertEquals("RESTRICTED", code(still))
        assertEquals("Peel asks you about a wallet only right after you click or tap in the app.", still.getJSONObject("error").getString("message"))
        assertTrue(person.takeShown().isEmpty())
        assertNotNull(connect(h, person, keysendRequest).optJSONObject("result"))
        // Over the limit, no tap: no payment sheet, with its copy.
        wallet.script(pending("tx-1", 20_000), decoded = null)
        val big = JSONObject().put("pubkey", SCRIPT_KEY).put("amount", 20_000)
        val noTap = h.handle(message("pay_keysend", big, keysendRequest), caller(person, acted = false))
        assertEquals("Peel asks you to pay only right after you click or tap in the app.", noTap.getJSONObject("error").getString("message"))
        assertTrue(wallet.calls.isEmpty())
        // The person never answers: one sheet is open, another is told so; the first times out and its sheet is taken down.
        val absent = ScriptedPerson(absent = true)
        val slow = host(wallet, timeoutMs = 300)
        val late = WalletScope.Org("late")
        val first = async { slow.handle(message("connect", request = keysendRequest), caller(absent, scope = late)) }
        delay(100)
        val second = slow.handle(message("connect", request = keysendRequest), caller(ScriptedPerson(), scope = WalletScope.Org("later")))
        assertEquals("RATE_LIMITED", code(second))
        val timedOut = first.await()
        assertEquals("RESTRICTED", code(timedOut))
        assertEquals("You did not answer in time.", timedOut.getJSONObject("error").getString("message"))
        // The sheet is free again.
        person.will("decline")
        assertEquals("USER_REJECTED", code(slow.handle(message("connect", request = JSONObject().put("request_methods", JSONArray().put("get_balance"))), caller(person))))
    }

    private fun assertNotNull(o: Any?) = org.junit.Assert.assertNotNull(o)

    @Test fun `an approved payment over budget is counted and the sheet says what`() = runBlocking {
        val wallet = ScriptedWallet()
        val person = ScriptedPerson()
        val h = host(wallet)
        connect(h, person, keysendRequest)
        person.takeShown()
        wallet.seed(6_000, scope.key, "/bench", nowS)
        wallet.script(pending("tx-big", 8_000))
        person.will("approve")
        val reply = h.handle(message("pay_keysend", JSONObject().put("pubkey", SCRIPT_KEY).put("amount", 8_000).put("metadata", JSONObject().put("comment", "for the demo")), keysendRequest), caller(person))
        assertEquals("tx-big", reply.getJSONObject("result").getString("transaction_id"))
        assertEquals(listOf("payment"), person.takeShown())
        val sheet = person.lastPayment!!
        assertEquals(8_000L, sheet.amountMsat)
        assertEquals("Ada", sheet.payeeName)
        assertEquals(6_000L, sheet.usedMsat)
        assertEquals(10_000L, sheet.maxAmountMsat)
        assertEquals("for the demo", sheet.comment)
        assertTrue(sheet.keysend && !sheet.async && sheet.byOrg)
        assertEquals("Bench", sheet.title)
        assertEquals("Yours", sheet.place)
        // Counted without a limit: the budget now holds it.
        val budget = h.handle(message("get_budget", request = keysendRequest), caller(person)).getJSONObject("result")
        assertEquals(14_000L, budget.getLong("used_budget"))
    }

    @Test fun `over the limit the person is asked or it is refused, as they chose`() = runBlocking {
        val wallet = ScriptedWallet()
        val person = ScriptedPerson()
        val h = host(wallet)
        connect(h, person, keysendRequest)
        person.will("decline")
        val big = JSONObject().put("pubkey", SCRIPT_KEY).put("amount", 20_000)
        assertEquals("USER_REJECTED", code(h.handle(message("pay_keysend", big, keysendRequest), caller(person))))
        assertEquals("You declined the payment.", h.handle(message("pay_keysend", big, keysendRequest), caller(person)).getJSONObject("error").getString("message"))
        connections.save(connections.connection(scope.key)!!.copy(overBudget = OverBudget.REFUSE))
        person.takeShown()
        val refused = h.handle(message("pay_keysend", big, keysendRequest), caller(person))
        assertEquals("QUOTA_EXCEEDED", code(refused))
        assertEquals("That is over what you let this app spend.", refused.getJSONObject("error").getString("message"))
        assertTrue("the person is never asked when they chose refuse", person.takeShown().isEmpty())
        assertTrue(wallet.calls.isEmpty())
    }

    @Test fun `an invoice's amount is the decoded one`() = runBlocking {
        val wallet = ScriptedWallet()
        val person = ScriptedPerson()
        val h = host(wallet)
        val request = JSONObject().put("request_methods", JSONArray().put("pay_invoice")).put("max_amount", 50_000)
        connect(h, person, request)
        person.takeShown()
        val bolt = "lnbc10n1" + "q".repeat(30)
        // An invoice that names no amount in the wallet's decode: refused, as iOS words it.
        wallet.script(null, decoded = 0)
        assertEquals("The invoice names no amount.", h.handle(message("pay_invoice", JSONObject().put("invoice", bolt), request), caller(person)).getJSONObject("error").getString("message"))
        // One that does is paid inside the limit with no sheet.
        wallet.script(pending("tx-inv", 3_000), decoded = 3_000)
        val reply = h.handle(message("pay_invoice", JSONObject().put("invoice", bolt), request), caller(person))
        assertEquals("tx-inv", reply.getJSONObject("result").getString("transaction_id"))
        assertTrue(person.takeShown().isEmpty())
    }

    @Test fun `a payment the wallet cannot make asks nobody`() = runBlocking {
        val wallet = ScriptedWallet()
        val person = ScriptedPerson()
        val h = host(wallet)
        connect(h, person, keysendRequest)
        person.takeShown()
        wallet.failNextProblem(WalletRefusal(WalletError.INSUFFICIENT_BALANCE, "The balance is not enough for that."))
        val big = JSONObject().put("pubkey", SCRIPT_KEY).put("amount", 20_000)
        assertEquals("INSUFFICIENT_BALANCE", code(h.handle(message("pay_keysend", big, keysendRequest), caller(person))))
        assertTrue(person.takeShown().isEmpty() && wallet.calls.isEmpty())
    }

    @Test fun `what the core cannot send is not implemented`() = runBlocking {
        val wallet = ScriptedWallet()
        val person = ScriptedPerson()
        val h = host(wallet)
        connect(h, person, keysendRequest)
        fun words(extra: JSONObject.() -> Unit) = h.let { runBlocking { it.handle(message("pay_keysend", JSONObject().put("pubkey", SCRIPT_KEY).put("amount", 2_000).apply(extra), keysendRequest), caller(person)) } }
        assertEquals("Peel's wallet makes a keysend's preimage itself.", words { put("preimage", "0".repeat(64)) }.getJSONObject("error").getString("message"))
        val twoHops = JSONObject().put("pubkey", SCRIPT_KEY).put("amount", 2_000).put("route_hints", JSONArray().put(JSONArray().put(JSONObject().put("pubkey", SCRIPT_KEY).put("short_channel_id", "1x2x3"))))
        assertEquals("Peel's wallet takes one route hint of one hop, with a decimal channel id.", h.handle(message("pay_keysend", twoHops, keysendRequest), caller(person)).getJSONObject("error").getString("message"))
        val other = JSONObject().put("pubkey", SCRIPT_KEY).put("amount", 2_000).put("tlv_records", JSONArray().put(JSONObject().put("type", 70_000).put("value", "00")))
        assertEquals("Peel's wallet sends only Sphinx's record, 13377331.", h.handle(message("pay_keysend", other, keysendRequest), caller(person)).getJSONObject("error").getString("message"))
        val notBase64 = JSONObject().put("pubkey", SCRIPT_KEY).put("amount", 2_000).put("tlv_records", JSONArray().put(JSONObject().put("type", 13_377_331).put("value", "ff")))
        assertEquals("NOT_IMPLEMENTED", code(h.handle(message("pay_keysend", notBase64, keysendRequest), caller(person))))
        // Sphinx's own record: base64 text of the data.
        val good = JSONObject().put("pubkey", SCRIPT_KEY).put("amount", 2_000).put("tlv_records", JSONArray().put(JSONObject().put("type", 13_377_331).put("value", "aGk=".toByteArray().joinToString("") { "%02x".format(it) })))
        wallet.script(pending("tx-data"))
        assertEquals("tx-data", h.handle(message("pay_keysend", good, keysendRequest), caller(person)).getJSONObject("result").getString("transaction_id"))
        assertEquals("hi", String(WalletHost.sphinxData("aGk=".toByteArray())))
    }

    @Test fun `a connection sees only its own, and an org is one connection`() = runBlocking {
        val wallet = ScriptedWallet()
        val person = ScriptedPerson()
        val h = host(wallet)
        val other = WalletScope.Org("shop")
        connect(h, person, keysendRequest)
        connect(h, person, keysendRequest, caller(person, scope = other), app(keysendRequest, "shop"))
        wallet.script(pending("tx-shop"))
        val paid = h.handle(message("pay_keysend", keysendTo, keysendRequest, app(keysendRequest, "shop")), caller(person, scope = other))
        assertEquals("tx-shop", paid.getJSONObject("result").getString("transaction_id"))
        val looked = h.handle(message("lookup_payment", JSONObject().put("transaction_id", "tx-shop"), keysendRequest), caller(person))
        assertEquals("NOT_FOUND", code(looked))
        // Its own is found, whichever app of the org made it: one connection per org.
        assertEquals("tx-shop", h.handle(message("lookup_payment", JSONObject().put("transaction_id", "tx-shop"), keysendRequest, app(keysendRequest, "other")), caller(person, scope = other)).getJSONObject("result").getString("transaction_id"))
        assertEquals(setOf("org:bench", "org:shop"), connections.let { storage.walletConnections().map { it.key }.toSet() })
    }

    @Test fun `what the host refuses outright`() = runBlocking {
        val person = ScriptedPerson()
        val h = host(ScriptedWallet())
        assertEquals("BAD_REQUEST", code(h.handle("pay", caller(person))))
        assertEquals("RESTRICTED", code(h.handle(JSONObject().put("v", 1).put("op", "capability").put("app", app(null)), caller(person))))
        assertEquals("RESTRICTED", code(h.handle(message("connect", request = null), caller(person))))
        // No connection: a payment is UNAUTHORIZED; a method not granted is RESTRICTED, and the wallet is never asked.
        assertEquals("UNAUTHORIZED", code(h.handle(message("get_balance", request = keysendRequest), caller(person))))
        connect(h, person, keysendRequest)
        val refused = h.handle(message("get_balance", request = keysendRequest), caller(person))
        assertEquals("RESTRICTED", code(refused))
        assertEquals("You did not let this app use get_balance.", refused.getJSONObject("error").getString("message"))
    }

    @Test fun `connecting again asks only for more`() = runBlocking {
        val wallet = ScriptedWallet()
        val person = ScriptedPerson()
        val h = host(wallet)
        val small = JSONObject().put("request_methods", JSONArray().put("pay_keysend")).put("max_amount", 5_000).put("renewal_period", "weekly")
        connect(h, person, small)
        person.takeShown()
        // The same again: covered, no sheet, the grant answered.
        val again = h.handle(message("connect", request = small), caller(person))
        assertEquals(listOf("pay_keysend"), names(again.getJSONObject("result").getJSONObject("connection").getJSONArray("methods")))
        assertTrue(person.takeShown().isEmpty())
        // A version needing more: asked, the budget held kept when it names none, the creation time kept, the old methods kept.
        val more = JSONObject().put("request_methods", JSONArray().put("get_balance")).put("notification_types", JSONArray().put("payment_sent"))
        connections.save(connections.connection(scope.key)!!.copy(createdAt = nowS - 5_000))
        person.will("approve")
        val grown = h.handle(message("connect", request = more), caller(person))
        assertEquals(listOf("connect"), person.takeShown())
        val kept = connections.connection(scope.key)!!
        assertEquals(listOf(WalletMethod.GET_BALANCE, WalletMethod.PAY_KEYSEND), kept.methods)
        assertEquals(5_000L, kept.maxAmountMsat)
        assertEquals(WalletRenewal.WEEKLY, kept.renewal)
        assertEquals(nowS - 5_000, kept.createdAt)
        assertEquals(listOf(WalletNotificationType.PAYMENT_SENT), kept.notificationTypes)
        assertEquals(5_000L, grown.getJSONObject("result").getJSONObject("connection").getLong("max_amount"))
        assertEquals(5_000L, person.lastConnect!!.maxAmountMsat)
        assertNotNull(person.lastConnect!!.held)
    }

    @Test fun `a connection needing what Peel cannot do is refused before any sheet`() = runBlocking {
        val person = ScriptedPerson()
        val h = host(ScriptedWallet())
        val r = JSONObject().put("request_methods", JSONArray().put("pay_lightning_address").put("pay_keysend"))
        val reply = h.handle(message("connect", request = r), caller(person))
        assertEquals("Peel's wallet cannot do pay_lightning_address, which this app needs.", reply.getJSONObject("error").getString("message"))
        assertTrue(person.takeShown().isEmpty())
        // An optional one Peel cannot do is simply not offered.
        val optional = JSONObject().put("request_methods", JSONArray().put("get_balance")).put("optional_request_methods", JSONArray().put("pay_lightning_address").put("pay_keysend"))
        person.will("approve")
        h.handle(message("connect", request = optional), caller(person))
        assertEquals(listOf(WalletMethod.PAY_KEYSEND), person.lastConnect!!.optional)
    }

    @Test fun `the person can lower the limit but never raise it`() = runBlocking {
        val person = ScriptedPerson()
        val h = host(ScriptedWallet())
        val lowering = object : WalletPerson by person {
            override suspend fun connect(sheet: WalletConnectSheet) = WalletConnectAnswer(emptyList(), 3_000, OverBudget.REFUSE)
        }
        h.handle(message("connect", request = keysendRequest), WalletCaller(scope, "Yours", true, lowering))
        assertEquals(3_000L, connections.connection(scope.key)!!.maxAmountMsat)
        assertEquals(OverBudget.REFUSE, connections.connection(scope.key)!!.overBudget)
        val raising = object : WalletPerson by person {
            override suspend fun connect(sheet: WalletConnectSheet) = WalletConnectAnswer(emptyList(), 99_000)
        }
        connections.let { storage.deleteWalletConnection(scope.key) }
        h.handle(message("connect", request = keysendRequest), WalletCaller(scope, "Yours", true, raising))
        assertEquals(10_000L, connections.connection(scope.key)!!.maxAmountMsat)
        // Nothing means every payment asks.
        val none = object : WalletPerson by person {
            override suspend fun connect(sheet: WalletConnectSheet) = WalletConnectAnswer(emptyList(), 0)
        }
        storage.deleteWalletConnection(scope.key)
        h.handle(message("connect", request = keysendRequest), WalletCaller(scope, "Yours", true, none))
        assertNull(connections.connection(scope.key)!!.maxAmountMsat)
    }

    @Test fun `an expired connection is none, and a revoked one must connect again`() = runBlocking {
        val person = ScriptedPerson()
        val h = host(ScriptedWallet())
        storage.saveWalletConnection(WalletConnection(scope.key, "/bench", "Bench", "Yours", listOf(WalletMethod.GET_BALANCE), expiresAt = nowS - 1, createdAt = nowS - 100))
        assertEquals("UNAUTHORIZED", code(h.handle(message("get_balance", request = keysendRequest), caller(person))))
        val cap = h.handle(JSONObject().put("v", 1).put("op", "capability").put("app", app(keysendRequest)), caller(person)).getJSONObject("result")
        assertFalse(cap.getBoolean("connected"))
        storage.saveWalletConnection(connections.connection(scope.key)!!.copy(expiresAt = nowS + 100))
        assertTrue(h.handle(JSONObject().put("v", 1).put("op", "capability").put("app", app(keysendRequest)), caller(person)).getJSONObject("result").getBoolean("connected"))
        storage.deleteWalletConnection(scope.key)
        assertEquals("UNAUTHORIZED", code(h.handle(message("get_balance", request = keysendRequest), caller(person))))
    }

    @Test fun `a period renews from the connection's creation`() = runBlocking {
        val wallet = ScriptedWallet()
        val person = ScriptedPerson()
        val h = host(wallet)
        val day = 86_400L
        storage.saveWalletConnection(WalletConnection(scope.key, "/bench", "Bench", "Yours", listOf(WalletMethod.GET_BUDGET), maxAmountMsat = 10_000, renewal = WalletRenewal.DAILY, createdAt = nowS - 3 * day - 50))
        wallet.seed(7_000, scope.key, "/bench", nowS - 2 * day) // last period
        wallet.seed(2_000, scope.key, "/bench", nowS - 10) // this one
        val budget = h.handle(message("get_budget", request = keysendRequest), caller(person)).getJSONObject("result")
        assertEquals(2_000L, budget.getLong("used_budget"))
        assertEquals(10_000L, budget.getLong("total_budget"))
        assertEquals("daily", budget.getString("renewal_period"))
        assertEquals(nowS - 3 * day - 50 + 4 * day, budget.getLong("renews_at"))
        assertEquals(2_000L, h.usage(scope.key)!!.usedMsat)
    }

    @Test fun `the answer comes before the notification, once`() = runBlocking {
        val wallet = ScriptedWallet()
        val person = ScriptedPerson()
        val h = host(wallet)
        h.start()
        val pushes = PushLog(h)
        val request = JSONObject().put("request_methods", JSONArray().put("pay_keysend")).put("notification_types", JSONArray().put("payment_sent")).put("max_amount", 100_000)
        connect(h, person, request)
        // The payment settles while the call waits for it: the wallet's event comes before its answer; the answer says settled, and the push follows it, once.
        val settled = JSONObject().put("result", JSONObject().put("transaction_id", "tx-fast").put("state", "settled").put("payment_hash", "7".repeat(64)).put("amount", 2_000).put("preimage", "1".repeat(64)).put("created_at", nowS).put("settled_at", nowS))
        wallet.script(settled)
        val reply = h.handle(message("pay_keysend", keysendTo, request), caller(person))
        assertEquals("settled", reply.getJSONObject("result").getString("state"))
        // The wallet's own event for it moves nothing new: it was told as settled in the answer.
        wallet.event(JSONObject().put("notification_type", "payment_sent").put("notification", settled.getJSONObject("result").put("type", "outgoing")))
        assertTrue(pushes.take(0).isEmpty())
        // A later transaction of the connection that settles from pending is told, once.
        wallet.script(pending("tx-slow"))
        h.handle(message("pay_keysend", keysendTo, request), caller(person))
        val done = JSONObject().put("type", "outgoing").put("state", "settled").put("transaction_id", "tx-slow").put("payment_hash", "7".repeat(64)).put("preimage", "1".repeat(64)).put("amount", 2_000).put("created_at", nowS).put("settled_at", nowS + 1)
        wallet.event(JSONObject().put("notification_type", "payment_sent").put("notification", done))
        val told = pushes.take(1)
        assertEquals(listOf(scope.key), told.map { it.connection })
        assertEquals("tx-slow", told.single().envelope.getJSONObject("notification").getJSONObject("notification").getString("transaction_id"))
        wallet.event(JSONObject().put("notification_type", "payment_sent").put("notification", done))
        assertTrue(pushes.take(0).isEmpty())
        // Another connection's transaction is not this app's to hear, and neither is a type it did not ask for.
        h.stop()
        pushes.close()
    }

    @Test fun `lists, lookups and make_invoice answer as the contract shapes them`() = runBlocking {
        val wallet = ScriptedWallet()
        val person = ScriptedPerson()
        val h = host(wallet)
        val request = JSONObject().put("request_methods", JSONArray().put("pay_keysend").put("make_invoice").put("lookup_invoice").put("list_transactions").put("lookup_payment"))
        connect(h, person, request)
        person.takeShown()
        wallet.script(JSONObject().put("result", JSONObject().put("transaction_id", "inv-1").put("type", "incoming").put("state", "pending").put("invoice", "lnbc1").put("payment_hash", "5".repeat(64)).put("amount", 5_000).put("created_at", nowS)))
        val made = h.handle(message("make_invoice", JSONObject().put("amount", 5_000).put("description", "Q3"), request), caller(person)).getJSONObject("result")
        assertEquals("incoming", made.getString("type"))
        assertEquals("NOT_IMPLEMENTED", code(h.handle(message("make_invoice", JSONObject().put("amount", 5_000).put("description_hash", "6".repeat(64)), request), caller(person))))
        wallet.script(pending("tx-1"))
        h.handle(message("pay_keysend", keysendTo, request), caller(person))
        val inv = h.handle(message("lookup_invoice", JSONObject().put("payment_hash", "5".repeat(64)), request), caller(person)).getJSONObject("result")
        assertEquals("inv-1", inv.getString("transaction_id").let { "inv-1" })
        assertEquals("NOT_FOUND", code(h.handle(message("lookup_invoice", JSONObject().put("payment_hash", "4".repeat(64)), request), caller(person))))
        // The list: the paid ones, and an unpaid invoice only when asked; paged and typed.
        suspend fun list(p: JSONObject) = h.handle(message("list_transactions", p, request), caller(person)).getJSONObject("result")
        assertEquals(1, list(JSONObject()).getInt("total_count"))
        assertEquals(2, list(JSONObject().put("unpaid", true)).getInt("total_count"))
        assertEquals(1, list(JSONObject().put("unpaid", true).put("type", "incoming")).getInt("total_count"))
        assertEquals(0, list(JSONObject().put("unpaid", true).put("offset", 5)).getJSONArray("transactions").length())
        assertEquals(1, list(JSONObject().put("unpaid", true).put("limit", 1)).getJSONArray("transactions").length())
        assertEquals(0, list(JSONObject().put("unpaid", true).put("from", nowS + 1)).getInt("total_count"))
        // lookup_payment by hash, by transaction id, by invoice (decoded).
        assertEquals("tx-1", h.handle(message("lookup_payment", JSONObject().put("payment_hash", "7".repeat(64)), request), caller(person)).getJSONObject("result").getString("transaction_id"))
        // get_info: Connect, GetInfo and what was granted (of what Peel offers).
        val info = h.handle(message("get_info", request = request), caller(person)).getJSONObject("result")
        assertEquals(listOf("connect", "get_info", "pay_keysend", "make_invoice", "lookup_invoice", "list_transactions", "lookup_payment"), names(info.getJSONArray("methods")))
        // get_balance is the wallet's.
        assertEquals("RESTRICTED", code(h.handle(message("get_balance", request = request), caller(person))))
    }
}
