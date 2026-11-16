package com.sphinx.peel.engine

import androidx.test.core.app.ApplicationProvider
import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// The engine's side of the app wallet (iOS `WalletEngineTests`), over a real `Storage` and a fake core ([PaymentRunner]): a keysend, an invoice paid or made for an
/// app, the budget counted in the payment's own write, the balance less what waits, refusals before anything moves, history fees and the state stream.
/// What the native core does (`Engine.paymentRunner`: the core's keysend/pay, the one write with its state, the publish) is the runner's and is not run here.
@RunWith(RobolectricTestRunner::class)
class AppPaymentsTest {
    private val storage = Storage(ApplicationProvider.getApplicationContext(), account = "ap-${System.nanoTime()}")
    private val self = "02" + "c".repeat(64)
    private val peer = "02" + "a".repeat(64)
    private val lsp = "03" + "b".repeat(64)
    private val tag = WalletTag("org:acme", "acme/board")
    private fun hashOf(n: Int) = "%064x".format(5000 + n)
    private val zeros = "0".repeat(64)
    private val zerosHash = "66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925"

    /// The core, as far as payments see it: it answers a tag and a hash, writes what the engine writes with them in one transaction, and publishes after.
    private inner class FakeRunner : PaymentRunner {
        var connected = true
        var clock = 1_800_000_000_000L
        var sent = 0
        val published = mutableListOf<String>()
        val invoices = mutableMapOf<String, Invoice>()
        var ownKeys0: Pair<String, String>? = self to lsp
        var next: String? = null // a hash the next keysend is made with
        override val accountKey get() = self
        override val ownKeys get() = ownKeys0
        override val isConnected get() = connected
        override val network = "bitcoin"
        override fun nowMs() = clock
        override fun decodeInvoice(bolt11: String) = invoices[bolt11] ?: error("unreadable")
        private fun commit(tag: String, hash: String, extra: (String?, String?) -> Storage.Commit): Published {
            storage.apply(extra(tag, hash))
            published += tag
            return Published.All
        }
        override suspend fun sendKeysend(to: String, amountMsat: Long, data: ByteArray?, routeHint: String?, extra: (String?, String?) -> Storage.Commit): Published {
            sent++
            return commit("k$sent", next ?: hashOf(sent), extra)
        }
        override suspend fun sendInvoice(invoice: Invoice, extra: (String?, String?) -> Storage.Commit): Published {
            sent++
            return commit("i$sent", invoice.paymentHash, extra)
        }
    }

    private val runner = FakeRunner()
    private val payments = AppPayments(storage, runner)
    private fun balance(msat: Long) = storage.apply(Storage.Commit(settings = mapOf(Setting.BALANCE_MSAT to msat.toString())))
    private fun failure(block: suspend () -> Unit): AppFailure =
        assertThrows(AppPaymentFailure::class.java) { runBlocking { block() } }.failure
    private val hint get() = "${lsp}_1"

    private fun pendingKeysend(n: Int, amount: Long = 10_000, async: Boolean = true, minutesAgo: Long = 1) = KeysendRecord(
        hashOf(100 + n), "w$n", peer, if (async) hint else null, async, amount, runner.clock - minutesAgo * 60_000, InvoiceRecord.Status.PENDING, connection = tag.connection, app = tag.app,
    )

    private fun invoice(n: Int, amount: Long = 50_000, payee: String = peer, expiresAtMs: Long? = runner.clock + 600_000) =
        Invoice("lnbc$n", hashOf(200 + n), amount, payee, "memo", expiresAtMs, emptyList()).also { runner.invoices[it.bolt11] = it }

    // MARK: A keysend

    /// Written pending with its tag, connection and app before anything is published, published, and holding its sats.
    @Test fun `a keysend goes through the core the asynchronous way`() = runBlocking<Unit> {
        balance(1_000_000)
        val record = payments.keysend(peer, 20_000, routeHint = hint, data = "hi".toByteArray(), wallet = tag)
        assertEquals(InvoiceRecord.Status.PENDING, record.status)
        assertTrue(record.async)
        assertEquals(tag.connection, record.connection)
        assertEquals(tag.app, record.app)
        assertEquals(20_000L, record.amountMsat)
        assertEquals(listOf("k1"), runner.published)
        assertEquals(20_000L, storage.waitingMsat(runner.clock))
        assertEquals(WalletRecord.State.PENDING, storage.walletRecords(tag.connection).first().state)
        // Not async without a hint: it holds nothing back.
        val direct = payments.keysend(peer.uppercase(), 5_000, wallet = tag)
        assertFalse(direct.async)
        assertEquals(peer, direct.pubkey)
        assertEquals(20_000L, storage.waitingMsat(runner.clock))
    }

    /// Refused before the core: over the balance (with its reserve), to this account, a key that is no key, a hint that is not Sphinx's; and over budget, in the
    /// write itself, so nothing is written or sent.
    @Test fun `a keysend is refused before anything moves`() {
        balance(20_000)
        storage.apply(Storage.Commit(keysends = listOf(pendingKeysend(1, amount = 9_000))))
        // 20,000 less the 9,000 that waits is 11,000: 1,000 and its 10 sat reserve and the ping do fit, 5,000 does not.
        assertEquals(AppFailure.INSUFFICIENT_BALANCE, failure { payments.keysend(peer, 5_000, routeHint = hint, wallet = tag) })
        assertEquals(AppFailure.INVALID_AMOUNT, failure { payments.keysend(peer, 0, wallet = tag) })
        balance(1_000_000)
        assertEquals(AppFailure.NOT_A_NODE_KEY, failure { payments.keysend("nobody", 1_000, wallet = tag) })
        assertEquals(AppFailure.NOT_A_NODE_KEY, failure { payments.keysend(peer, 1_000, routeHint = "${lsp}:1", wallet = tag) })
        assertEquals(AppFailure.NOT_A_NODE_KEY, failure { payments.keysend(peer, 1_000, routeHint = "${lsp}_x", wallet = tag) })
        assertEquals(AppFailure.NOT_A_NODE_KEY, failure { payments.keysend(peer, 1_000, routeHint = "${lsp}_1_2", wallet = tag) })
        assertEquals(AppFailure.KEYSEND_TO_SELF, failure { payments.keysend(self, 1_000, wallet = tag) })
        val tight = tag.copy(budget = WalletBudget.Guard(10_000, 0))
        assertEquals(AppFailure.OVER_BUDGET, failure { payments.keysend(peer, 2_000, routeHint = hint, wallet = tight) })
        assertTrue(runner.published.isEmpty())
        assertEquals(1, storage.walletRecords(tag.connection).size)
    }

    @Test fun `a balance not known yet, or no connection, refuses`() {
        assertEquals(AppFailure.BALANCE_UNKNOWN, failure { payments.keysend(peer, 1_000, wallet = tag) })
        balance(1_000_000)
        runner.connected = false
        assertEquals(AppFailure.NOT_CONNECTED, failure { payments.keysend(peer, 1_000, wallet = tag) })
        assertTrue(runner.published.isEmpty() && storage.walletRecords(null).isEmpty())
    }

    @Test fun `a core that returns no hash writes nothing`() {
        balance(1_000_000)
        runner.next = "not a hash"
        assertEquals(AppFailure.CORE_RETURNED_NOTHING, failure { payments.keysend(peer, 1_000, wallet = tag) })
        assertTrue(storage.walletRecords(null).isEmpty())
    }

    /// The budget is counted in the payment's own write: pending counts, failed does not, another connection's is not counted, an approved one is counted without
    /// a limit (the person's yes on its own sheet).
    @Test fun `the limit is enforced in the payments own write`() = runBlocking<Unit> {
        balance(10_000_000)
        val limited = tag.copy(budget = WalletBudget.Guard(10_000, 0))
        payments.keysend(peer, 6_000, wallet = limited)
        assertEquals(AppFailure.OVER_BUDGET, failure { payments.keysend(peer, 5_000, wallet = limited) })
        // The refused one left nothing: not a row, not a send.
        assertEquals(1, storage.walletRecords(tag.connection).size)
        assertEquals(1, runner.published.size)
        payments.keysend(peer, 4_000, wallet = limited)
        // Failed counts nothing: the sats are back.
        storage.markInvoiceSendStatus("k1", delivered = false, error = "no route")
        payments.keysend(peer, 6_000, wallet = limited)
        // Approved on its own sheet: counted, not limited.
        payments.keysend(peer, 90_000, wallet = tag.copy(budget = WalletBudget.Guard(null, 0)))
        assertEquals(100_000L, WalletBudget.used(storage.walletRecords(tag.connection).mapNotNull { it.entry }, 0))
        payments.keysend(peer, 1_000, wallet = WalletTag("org:globex", "globex/x", WalletBudget.Guard(1_000, 0)))
    }

    // MARK: Waiting

    /// An app's keysend waits like any send to someone offline: sent the asynchronous way it holds sats back until it settles.
    @Test fun `an open keysend is waiting`() {
        balance(1_000_000)
        storage.apply(
            Storage.Commit(
                keysends = listOf(
                    pendingKeysend(1), pendingKeysend(2, async = false), pendingKeysend(3).copy(status = InvoiceRecord.Status.PAID),
                    pendingKeysend(4, minutesAgo = 8L * 24 * 60),
                ),
            ),
        )
        assertEquals(10_000L, storage.waitingMsat(runner.clock))
        assertEquals(990_000L, storage.spendableMsat(runner.clock))
        // More waiting than the balance leaves nothing, never less than none.
        balance(5_000)
        assertEquals(0L, storage.spendableMsat(runner.clock))
        storage.apply(Storage.Commit(settings = mapOf(Setting.BALANCE_MSAT to "")))
        assertNull(storage.spendableMsat(runner.clock))
    }

    // MARK: An invoice

    /// An app's invoice is paid without the person's threshold, and tagged.
    @Test fun `an apps invoice is paid unasked and tagged`() = runBlocking<Unit> {
        balance(10_000_000)
        storage.apply(Storage.Commit(settings = mapOf(Setting.PAYMENT_CONFIRMATION_MSAT to "0")))
        val paid = invoice(1)
        val record = payments.payInvoice(paid, tag)
        assertEquals(tag.connection, record.connection)
        assertEquals(tag.app, record.app)
        assertEquals(InvoiceRecord.Status.PENDING, record.status)
        assertEquals("i1", record.tag)
        val wallet = storage.walletRecords(tag.connection).first()
        assertEquals(WalletRecord.PaymentType.BOLT11, wallet.paymentType)
        assertEquals(paid.bolt11, wallet.invoice)
        assertEquals(50_000L, wallet.amountMsat)
        // The same again is being paid: refused, nothing sent twice.
        assertEquals(AppFailure.INVOICE_PENDING, failure { payments.payInvoice(paid, tag) })
        assertEquals(1, runner.published.size)
    }

    @Test fun `an invoice over the limit writes and sends nothing`() {
        balance(10_000_000)
        val tight = tag.copy(budget = WalletBudget.Guard(10_000, 0))
        assertEquals(AppFailure.OVER_BUDGET, failure { payments.payInvoice(invoice(1, 50_000), tight) })
        assertNull(storage.invoice(hashOf(201), false))
        assertTrue(runner.published.isEmpty())
        // Inside it, and the failed payment's refund frees it.
        runBlocking { payments.payInvoice(invoice(2, 8_000), tight) }
        assertEquals(AppFailure.OVER_BUDGET, failure { payments.payInvoice(invoice(3, 8_000), tight) })
        storage.markInvoiceSendStatus("i2", delivered = false, error = "no route")
        runBlocking { payments.payInvoice(invoice(3, 8_000), tight) }
    }

    @Test fun `what stops paying an invoice is said before anyone is asked`() {
        balance(1_000_000)
        assertNull(payments.invoiceProblem(invoice(1)))
        assertEquals(AppFailure.INVOICE_WITHOUT_AMOUNT, payments.invoiceProblem(invoice(2, amount = 0)))
        assertEquals(AppFailure.INVOICE_EXPIRED, payments.invoiceProblem(invoice(3, expiresAtMs = runner.clock - 1)))
        assertEquals(AppFailure.OWN_INVOICE, payments.invoiceProblem(invoice(4, payee = self)))
        // One this account made.
        storage.saveInvoice(InvoiceRecord(hashOf(205), true, "lnbc5", 1_000, "", self, 1, null, InvoiceRecord.Status.OPEN))
        assertEquals(AppFailure.OWN_INVOICE, payments.invoiceProblem(invoice(5)))
        // Pending, paid, paid by the history.
        storage.saveInvoice(InvoiceRecord(hashOf(206), false, "lnbc6", 1_000, "", peer, 1, null, InvoiceRecord.Status.PENDING, tag = "t6"))
        assertEquals(AppFailure.INVOICE_PENDING, payments.invoiceProblem(invoice(6)))
        storage.markInvoiceSendStatus("t6", delivered = true)
        assertEquals(AppFailure.INVOICE_ALREADY_PAID, payments.invoiceProblem(invoice(6)))
        storage.insertPayments(PaymentRecord.decode("""[{"amt_msat":1000,"rhash":"${hashOf(207)}","ts":5000}]"""))
        assertEquals(AppFailure.INVOICE_ALREADY_PAID, payments.invoiceProblem(invoice(7)))
        // Too much for the balance with its fee reserve (1% or 10 sats), less what waits.
        assertEquals(AppFailure.INSUFFICIENT_BALANCE, payments.invoiceProblem(invoice(8, amount = 995_000)))
        assertNull(payments.invoiceProblem(invoice(9, amount = 980_000)))
        storage.apply(Storage.Commit(keysends = listOf(pendingKeysend(1, amount = 100_000))))
        assertEquals(AppFailure.INSUFFICIENT_BALANCE, payments.invoiceProblem(invoice(10, amount = 980_000)))
        storage.apply(Storage.Commit(settings = mapOf(Setting.BALANCE_MSAT to "")))
        assertEquals(AppFailure.BALANCE_UNKNOWN, payments.invoiceProblem(invoice(11)))
    }

    // MARK: The wallet over it

    private fun wallet(settleWaitMs: Long = 100, made: InvoiceMaker = InvoiceMaker { _, _, _ -> error("no invoices") }) =
        EngineWallet(storage, payments, runner, made, VisitorSigner { _, key -> VisitorSignature("pub", "sig-${key.wire}") }, settleWaitMs)

    /// A payment the server answers at once is told as settled with its proof; one it does not answer yet, as pending.
    @Test fun `a payment answers settled when the server's word comes in time, else pending`() = runBlocking<Unit> {
        balance(10_000_000)
        val w = wallet(settleWaitMs = 3_000)
        runner.next = zerosHash
        val settling = launch(Dispatchers.Default) {
            delay(150)
            storage.keepPreimage(zeros, "k1")
            storage.markInvoiceSendStatus("k1", delivered = true)
        }
        val settled = w.pay(WalletPayment.OfKeysend(peer, 10_000, hint, null), tag)
        settling.join()
        runner.next = null
        assertEquals(WalletRecord.State.SETTLED, settled.state)
        assertEquals(zeros, settled.preimage)
        assertTrue(settled.provenSettled)
        assertEquals("settled", settled.payment.getString("state"))
        // Nobody answered: pending after the wait, and the answer says pending.
        val slow = wallet(settleWaitMs = 80).pay(WalletPayment.OfKeysend(peer, 10_000, null, null), tag)
        assertEquals(WalletRecord.State.PENDING, slow.state)
        assertEquals("pending", slow.payment.getString("state"))
    }

    @Test fun `a payment refused is the contracts error in the engines words`() = runBlocking<Unit> {
        balance(10_000_000)
        val w = wallet()
        fun refusal(block: suspend () -> Unit) = assertThrows(WalletRefusal::class.java) { runBlocking { block() } }
        val over = refusal { w.pay(WalletPayment.OfKeysend(peer, 2_000, null, null), tag.copy(budget = WalletBudget.Guard(1_000, 0))) }
        assertEquals(WalletError.QUOTA_EXCEEDED, over.code)
        assertEquals("That is over what this app may spend without asking.", over.message)
        assertEquals(WalletError.BAD_REQUEST, refusal { w.pay(WalletPayment.OfKeysend(self, 2_000, null, null), tag) }.code)
        runner.connected = false
        val gone = refusal { w.pay(WalletPayment.OfKeysend(peer, 2_000, null, null), tag) }
        assertEquals("Not connected to the server.", gone.message)
        assertEquals(WalletError.PAYMENT_FAILED, gone.code)
        assertEquals(WalletError.INSUFFICIENT_BALANCE, AppFailure.INSUFFICIENT_BALANCE.refusal.code)
        assertEquals(WalletError.PAYMENT_FAILED, AppFailure.INVOICE_EXPIRED.refusal.code)
        assertEquals(WalletError.NOT_IMPLEMENTED, AppFailure.INVOICE_WITHOUT_AMOUNT.refusal.code)
        assertEquals(WalletError.OTHER, AppFailure.BALANCE_UNKNOWN.refusal.code)
        assertEquals(WalletError.BAD_REQUEST, AppFailure.NOT_A_NODE_KEY.refusal.code)
    }

    @Test fun `problems are found before anyone is asked, with the same codes`() = runBlocking<Unit> {
        val w = wallet()
        assertEquals(WalletError.OTHER, w.problem(WalletPayment.OfKeysend(peer, 1_000, null, null))!!.code)
        balance(100_000)
        assertNull(w.problem(WalletPayment.OfKeysend(peer, 1_000, null, null)))
        assertEquals(WalletError.INSUFFICIENT_BALANCE, w.problem(WalletPayment.OfKeysend(peer, 95_000, null, null))!!.code)
        // The ping counts only for an async one.
        assertNull(w.problem(WalletPayment.OfKeysend(peer, 89_500, null, null)))
        assertEquals(WalletError.INSUFFICIENT_BALANCE, w.problem(WalletPayment.OfKeysend(peer, 89_500, hint, null))!!.code)
        assertEquals(WalletError.BAD_REQUEST, w.problem(WalletPayment.OfKeysend(self, 1_000, null, null))!!.code)
        val unread = w.problem(WalletPayment.OfInvoice(WalletInvoice("lnbc-unknown", hashOf(1), 1_000, peer, "", null)))
        assertEquals("Peel could not read that invoice.", unread!!.message)
        val expired = invoice(1, expiresAtMs = runner.clock - 1)
        assertEquals("That invoice has expired.", w.problem(WalletPayment.OfInvoice(WalletInvoice(expired)))!!.message)
        assertNull(w.problem(WalletPayment.OfInvoice(WalletInvoice(invoice(2, amount = 1_000)))))
        assertEquals(WalletError.BAD_REQUEST, assertThrows(WalletRefusal::class.java) { runBlocking { w.decode("lnbc-unknown") } }.code)
        assertEquals(hashOf(202), w.decode(invoice(2).bolt11).paymentHash)
        assertEquals(runner.clock / 1000 + 600, WalletInvoice(invoice(2)).expiresAt)
    }

    @Test fun `the balance, a payee's name, the network and signing`() = runBlocking<Unit> {
        val w = wallet()
        assertEquals(WalletError.OTHER, assertThrows(WalletRefusal::class.java) { runBlocking { w.balance() } }.code)
        balance(80_000)
        storage.apply(Storage.Commit(keysends = listOf(pendingKeysend(1, amount = 10_000))))
        assertEquals(70_000L, w.balance())
        assertNull(w.payeeName(peer))
        storage.insertMessages(emptyList(), listOf(com.sphinx.peel.storage.ContactRow(peer, "Ana", null, false, true)))
        assertEquals("Ana", w.payeeName(peer))
        assertEquals("bitcoin", w.network())
        assertTrue(w.signsPerDomain)
        val signature = w.signVisitor(VisitorChallenge.parse(ROOT_CHALLENGE)!!, SignInKey.domain)
        assertEquals("sig-domain", signature.sig)
        assertEquals(listOf(WalletRecord.State.PENDING), w.records(tag.connection).map { it.state })
    }

    @Test fun `an invoice made for an app is read back as an incoming record`() = runBlocking<Unit> {
        val made = InvoiceMaker { amount, memo, t ->
            InvoiceRecord(hashOf(300), true, "lnbc300", amount, memo, lsp, runner.clock, runner.clock + 3_600_000, InvoiceRecord.Status.OPEN, connection = t.connection, app = t.app)
                .also { storage.saveInvoice(it) }
        }
        val record = wallet(made = made).makeInvoice(500_000, "Q3 report", tag)
        assertEquals(WalletRecord.Direction.INCOMING, record.direction)
        assertEquals("Q3 report", record.description)
        assertEquals("lnbc300", record.invoice)
        assertEquals(WalletRecord.State.PENDING, record.state)
        assertEquals((runner.clock + 3_600_000) / 1000, record.expiresAt)
        // An incoming invoice is no spending: a budget counts nothing of it.
        assertNull(record.entry)
    }

    // MARK: The state stream

    /// Each move of an app's transaction to settled or failed is told once, whichever way it came; what stood before the first read is not.
    @Test fun `the state stream tells each move once`() = runBlocking<Unit> {
        storage.apply(Storage.Commit(keysends = listOf(pendingKeysend(1).copy(paymentHash = zerosHash, tag = "k1"), pendingKeysend(2), pendingKeysend(3).copy(status = InvoiceRecord.Status.PAID))))
        val told = mutableListOf<WalletRecord>()
        val watching = launch(Dispatchers.Unconfined) { wallet().updates().collect { told += it } }
        // The mixer's COMPLETE with its proof; then the history failing another.
        storage.keepPreimage(zeros, "k1")
        storage.markInvoiceSendStatus("k1", delivered = true)
        storage.insertPayments(PaymentRecord.decode("""[{"amt_msat":10000,"rhash":"${hashOf(102)}","ts":5000,"error":"gone"}]"""))
        // The same again moves nothing.
        storage.keepPreimage(zeros, "k1")
        storage.markInvoiceSendStatus("k1", delivered = true)
        watching.cancel()
        assertEquals(listOf(zerosHash, hashOf(102)), told.map { it.paymentHash })
        assertEquals(WalletRecord.State.SETTLED, told.first().state)
        assertEquals(zeros, told.first().preimage)
        assertEquals(WalletRecord.State.FAILED, told.last().state)
    }
}
