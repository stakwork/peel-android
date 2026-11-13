package com.sphinx.peel.storage

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.sphinx.peel.engine.InvoiceRecord
import com.sphinx.peel.engine.KeysendRecord
import com.sphinx.peel.engine.OverBudget
import com.sphinx.peel.engine.OverBudgetException
import com.sphinx.peel.engine.PaymentRecord
import com.sphinx.peel.engine.SignInKey
import com.sphinx.peel.engine.WalletBudget
import com.sphinx.peel.engine.WalletConnection
import com.sphinx.peel.engine.WalletMethod
import com.sphinx.peel.engine.WalletNotificationType
import com.sphinx.peel.engine.WalletRecord
import com.sphinx.peel.engine.WalletRenewal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// The app wallet's storage (iOS `Storage+Wallet.swift`, and `WalletEngineTests`' storage half): a connection in full, its limit and what it spent, the
/// transactions made through it, the preimage kept only when it proves the payment, the history settling a keysend and the budget counted inside the
/// payment's own write. The v13 → v14 migration is `StorageWalletMigrationTest`.
@RunWith(RobolectricTestRunner::class)
class StorageWalletTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun newStorage() = Storage(context, account = "test-${System.nanoTime()}")

    private val hash1 = "66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925"
    private val zeros = "0".repeat(64)
    private val key = "org:acme"

    private fun hashOf(n: Int) = "%064x".format(1000 + n)

    private fun keysend(
        n: Int, status: InvoiceRecord.Status = InvoiceRecord.Status.PENDING, amount: Long = 10_000, async: Boolean = true,
        hash: String? = null, connection: String? = key, minutesAgo: Long = 1,
    ) = KeysendRecord(
        paymentHash = hash ?: hashOf(n), tag = "k$n", pubkey = "02" + "a".repeat(64), routeHint = if (async) "03${"b".repeat(64)}_1" else null,
        async = async, amountMsat = amount, createdAt = System.currentTimeMillis() - minutesAgo * 60_000, status = status,
        connection = connection, app = if (connection == null) null else "acme/board",
    )

    private fun Storage.with(vararg rows: KeysendRecord): Storage = also { apply(Storage.Commit(keysends = rows.toList())) }

    private fun history(json: String) = PaymentRecord.decode(json)

    // MARK: Connections

    @Test fun `a connection round trips with every field`() {
        val storage = newStorage()
        val full = WalletConnection(
            key, "acme/board", "Board", "Acme", listOf(WalletMethod.PAY_KEYSEND, WalletMethod.GET_BUDGET, WalletMethod.LOOKUP_PAYMENT),
            listOf(WalletNotificationType.PAYMENT_SENT, WalletNotificationType.PAYMENT_FAILED), 5_000_000, WalletRenewal.WEEKLY, 1_794_000_000,
            OverBudget.REFUSE, 1_793_800_000, SignInKey.domain,
        )
        storage.saveWalletConnection(full)
        assertEquals(full, storage.walletConnection(key))
        assertEquals(listOf(full), storage.walletConnections())
    }

    @Test fun `names this build does not know are left out of a connection, never a failure`() {
        val storage = newStorage()
        storage.saveWalletConnection(WalletConnection(key, "a", "A", "p", createdAt = 1))
        val db = SQLiteDatabase.openDatabase(context.getDatabasePath("peel-${dbAccount(storage)}.db").path, null, SQLiteDatabase.OPEN_READWRITE)
        db.execSQL("UPDATE wallet_connection SET methods = '[\"pay_keysend\",\"teleport\"]', notificationTypes = '[\"payment_sent\",\"x\"]', renewal = 'hourly', overBudget = '?'")
        db.close()
        val read = storage.walletConnection(key)!!
        assertEquals(listOf(WalletMethod.PAY_KEYSEND), read.methods)
        assertEquals(listOf(WalletNotificationType.PAYMENT_SENT), read.notificationTypes)
        assertEquals(WalletRenewal.NEVER, read.renewal)
        assertEquals(OverBudget.ASK, read.overBudget)
    }

    private fun dbAccount(storage: Storage): String {
        val field = Storage::class.java.getDeclaredField("dbName").apply { isAccessible = true }
        return (field.get(storage) as String).removePrefix("peel-").removeSuffix(".db")
    }

    @Test fun `connections are listed oldest first and a revoked one is gone`() {
        val storage = newStorage()
        storage.saveWalletConnection(WalletConnection("org:b", "a", "B", "p", createdAt = 20))
        storage.saveWalletConnection(WalletConnection("org:a", "a", "A", "p", createdAt = 10))
        storage.saveWalletConnection(WalletConnection("org:c", "a", "C", "p", createdAt = 10))
        assertEquals(listOf("org:a", "org:c", "org:b"), storage.walletConnections().map { it.key })
        storage.deleteWalletConnection("org:c")
        assertEquals(listOf("org:a", "org:b"), storage.walletConnections().map { it.key })
        storage.deleteWalletConnection("org:nobody")
    }

    @Test fun `the limit and what to do over it are changed in place, and nothing is made up`() {
        val storage = newStorage()
        storage.saveWalletConnection(WalletConnection(key, "a", "A", "p", methods = listOf(WalletMethod.PAY_KEYSEND), createdAt = 5, signIn = SignInKey.root))
        assertTrue(storage.setSpendingLimit(key, 4_000_000))
        assertEquals(4_000_000L, storage.walletConnection(key)!!.maxAmountMsat)
        assertTrue(storage.setOverLimit(key, OverBudget.REFUSE))
        val changed = storage.walletConnection(key)!!
        assertEquals(OverBudget.REFUSE, changed.overBudget)
        // Nothing else moved.
        assertEquals(WalletConnection(key, "a", "A", "p", listOf(WalletMethod.PAY_KEYSEND), maxAmountMsat = 4_000_000, overBudget = OverBudget.REFUSE, createdAt = 5, signIn = SignInKey.root), changed)
        // Zero or less, or none, asks for every payment.
        storage.setSpendingLimit(key, 0)
        assertNull(storage.walletConnection(key)!!.maxAmountMsat)
        storage.setSpendingLimit(key, 100)
        storage.setSpendingLimit(key, -5)
        assertNull(storage.walletConnection(key)!!.maxAmountMsat)
        storage.setSpendingLimit(key, 100)
        storage.setSpendingLimit(key, null)
        assertNull(storage.walletConnection(key)!!.maxAmountMsat)
        // A connection that is not there is not made.
        assertFalse(storage.setSpendingLimit("org:nobody", 1))
        assertFalse(storage.setOverLimit("org:nobody", OverBudget.ASK))
        assertNull(storage.walletConnection("org:nobody"))
    }

    @Test fun `the connections are told again as they change`() = runBlocking {
        val storage = newStorage()
        val seen = mutableListOf<List<String>>()
        val job = launch(Dispatchers.Unconfined) { storage.walletConnectionUpdates().collect { seen += it.map { c -> c.key } } }
        storage.saveWalletConnection(WalletConnection("org:a", "a", "A", "p", createdAt = 1))
        storage.saveWalletConnection(WalletConnection("org:b", "a", "B", "p", createdAt = 2))
        storage.deleteWalletConnection("org:a")
        job.cancel()
        assertEquals(listOf(emptyList(), listOf("org:a"), listOf("org:a", "org:b"), listOf("org:b")), seen)
    }

    @Test fun `what a connection spent this period and when it renews`() {
        val day = 86_400L
        val storage = newStorage().with(
            keysend(1, amount = 6_000).copy(createdAt = (1_000 + day + 5) * 1000), // this period
            keysend(2, amount = 9_000).copy(createdAt = (1_000 + 5) * 1000), // the last one
        )
        storage.saveWalletConnection(WalletConnection(key, "a", "A", "p", maxAmountMsat = 10_000, renewal = WalletRenewal.DAILY, createdAt = 1_000))
        val usage = storage.walletUsage(key, now = 1_000 + day + 100)!!
        assertEquals(6_000L, usage.usedMsat)
        assertEquals(1_000 + 2 * day, usage.renewsAt)
        // Never renewed: everything counts, and nothing renews.
        storage.saveWalletConnection(WalletConnection(key, "a", "A", "p", createdAt = 1_000))
        assertEquals(15_000L, storage.walletUsage(key, now = 1_000 + day + 100)!!.usedMsat)
        assertNull(storage.walletUsage(key, now = 5)!!.renewsAt)
        assertNull(storage.walletUsage("org:nobody"))
    }

    // MARK: The preimage

    @Test fun `a complete keeps its preimage only when it proves the payment`() {
        val storage = newStorage().with(keysend(1, hash = hash1), keysend(2))
        storage.saveInvoice(
            InvoiceRecord(hash1, false, "lnbc1", 1_000, "", "02" + "a".repeat(64), 1, null, InvoiceRecord.Status.PENDING, tag = "i1", connection = key, app = "acme/board"),
        )
        storage.keepPreimage(zeros, "k1")
        // A preimage that hashes to something else proves nothing.
        storage.keepPreimage(zeros, "k2")
        storage.keepPreimage(zeros, "i1")
        assertEquals(zeros, storage.keysend("k1")!!.preimage)
        assertNull(storage.keysend("k2")!!.preimage)
        assertEquals(zeros, storage.invoice(hash1, false)!!.preimage)
        // One kept is never written over; the case is the proof's, not the writer's.
        storage.keepPreimage("1".repeat(64), "k1")
        assertEquals(zeros, storage.keysend("k1")!!.preimage)
        // An invoice made (incoming) is no payment of ours: nothing is kept on it.
        storage.saveInvoice(InvoiceRecord(hash1, true, "lnbc2", 1_000, "", "me", 1, null, InvoiceRecord.Status.OPEN, tag = "i1"))
        storage.keepPreimage(zeros, "i1")
        assertNull(storage.invoice(hash1, true)!!.preimage)
        // A tag that names nothing keeps nothing.
        storage.keepPreimage(zeros, "nobody")
    }

    @Test fun `a settled payment is proven only with its preimage`() {
        val storage = newStorage().with(keysend(1, hash = hash1), keysend(2))
        storage.markInvoiceSendStatus("k1", delivered = true)
        storage.markInvoiceSendStatus("k2", delivered = true)
        storage.keepPreimage(zeros, "k1")
        storage.keepPreimage(zeros, "k2")
        val records = storage.walletRecords(key)
        assertTrue(records.first { it.paymentHash == hash1 }.provenSettled)
        val unproven = records.first { it.paymentHash == hashOf(2) }
        assertFalse(unproven.provenSettled)
        assertEquals("pending", unproven.payment.getString("state"))
    }

    // MARK: A send moving by the server's word

    @Test fun `a keysend moves by the servers word, forward only`() {
        val storage = newStorage().with(keysend(1), keysend(2))
        storage.markInvoiceSendStatus("k1", delivered = false, error = "no route")
        storage.markInvoiceSendStatus("k2", delivered = true)
        val k1 = storage.keysend("k1")!!
        assertEquals(InvoiceRecord.Status.FAILED, k1.status)
        assertEquals("no route", k1.error)
        val k2 = storage.keysend("k2")!!
        assertEquals(InvoiceRecord.Status.PAID, k2.status)
        assertNotNull(k2.paidAt)
        val records = storage.walletRecords(key)
        assertEquals("no route", records.first { it.paymentHash == hashOf(1) }.failureReason)
        assertEquals(WalletRecord.State.SETTLED, records.first { it.paymentHash == hashOf(2) }.state)
        // A failure after it settled never moves it back; a word after a failure does not move it either.
        storage.markInvoiceSendStatus("k2", delivered = false, error = "late")
        assertEquals(InvoiceRecord.Status.PAID, storage.keysend("k2")!!.status)
        storage.markInvoiceSendStatus("k1", delivered = true)
        assertEquals(InvoiceRecord.Status.FAILED, storage.keysend("k1")!!.status)
    }

    /// The history settles or fails a keysend left open, by its payment hash; settled there, it has no preimage, and its fee is what left beyond its amount
    /// plus the asynchronous way's ping.
    @Test fun `the history settles a keysend`() {
        val storage = newStorage().with(keysend(1), keysend(2), keysend(3, async = false))
        storage.insertPayments(
            history(
                """[{"amt_msat":10010,"rhash":"${hashOf(1)}","ts":5000},
                    {"amt_msat":10000,"rhash":"${hashOf(2)}","ts":5000,"error":"timed out"},
                    {"amt_msat":10000,"rhash":"${hashOf(3)}","ts":5000}]""",
            ),
        )
        val records = storage.walletRecords(key)
        val settled = records.first { it.paymentHash == hashOf(1) }
        assertEquals(WalletRecord.State.SETTLED, settled.state)
        assertNull(settled.preimage)
        assertEquals(5L, settled.settledAt)
        assertEquals(10L + 1_000, settled.feesMsat)
        val failed = records.first { it.paymentHash == hashOf(2) }
        assertEquals(WalletRecord.State.FAILED, failed.state)
        assertEquals("timed out", failed.failureReason)
        assertEquals(0L, records.first { it.paymentHash == hashOf(3) }.feesMsat)
    }

    @Test fun `a row that failed beside one that went through settles it`() {
        val storage = newStorage().with(keysend(1), keysend(2))
        // The failed row alone fails it; the same hash with a good row too (a retry) goes through.
        storage.insertPayments(history("""[{"amt_msat":10000,"rhash":"${hashOf(1)}","ts":5000,"error":"gone"}]"""))
        assertEquals(InvoiceRecord.Status.FAILED, storage.keysend("k1")!!.status)
        storage.insertPayments(history("""[{"amt_msat":10000,"rhash":"${hashOf(1)}","ts":6000}]"""))
        assertEquals(InvoiceRecord.Status.PAID, storage.keysend("k1")!!.status)
        assertNull(storage.keysend("k1")!!.error)
        assertEquals(6000L, storage.keysend("k1")!!.paidAt)
        // An incoming row names no keysend of ours.
        storage.insertPayments(history("""[{"amt_msat":10000,"rhash":"${hashOf(2)}","ts":7000,"msg_idx":3}]"""))
        assertEquals(InvoiceRecord.Status.PENDING, storage.keysend("k2")!!.status)
    }

    // MARK: Records

    @Test fun `records are a connections own, newest first, and every app's for none`() {
        val storage = newStorage().with(
            keysend(1).copy(createdAt = 1_000_000), keysend(2).copy(createdAt = 3_000_000),
            keysend(3, connection = "org:globex").copy(createdAt = 2_000_000), keysend(4, connection = null),
        )
        assertEquals(listOf(hashOf(2), hashOf(1)), storage.walletRecords(key).map { it.paymentHash })
        assertEquals(listOf(hashOf(2), hashOf(3), hashOf(1)), storage.walletRecords(null).map { it.paymentHash })
        assertEquals(listOf("org:globex"), storage.walletRecords("org:globex").map { it.connection })
        assertEquals(emptyList<WalletRecord>(), storage.walletRecords("org:nobody"))
        assertEquals("acme/board", storage.walletRecords(key).first().app)
    }

    @Test fun `invoices made and paid for an app are records with their fees and expiry`() {
        val storage = newStorage()
        val now = System.currentTimeMillis()
        val to = "02" + "a".repeat(64)
        storage.apply(
            Storage.Commit(
                invoices = listOf(
                    InvoiceRecord(hashOf(1), false, "lnbc1", 50_000, "for the board", to, now - 10_000, null, InvoiceRecord.Status.PENDING, tag = "i1", connection = key, app = "acme/board"),
                    InvoiceRecord(hashOf(2), true, "lnbc2", 30_000, "invoice made", "me", now - 20_000, now - 5_000, InvoiceRecord.Status.OPEN, connection = key, app = "acme/board"),
                    InvoiceRecord(hashOf(3), true, "lnbc3", 30_000, "still open", "me", now - 30_000, now + 600_000, InvoiceRecord.Status.OPEN, connection = key, app = "acme/board"),
                    InvoiceRecord(hashOf(4), true, "lnbc4", 30_000, "under", "me", now - 40_000, null, InvoiceRecord.Status.UNDERPAID, connection = key, app = "acme/board"),
                    InvoiceRecord(hashOf(5), true, "lnbc5", 30_000, "", "me", now - 50_000, null, InvoiceRecord.Status.PAID, paidAt = now - 1_000, paidMsat = 31_000, connection = key, app = "acme/board"),
                    InvoiceRecord(hashOf(6), false, "lnbc6", 1_000, "", to, now, null, InvoiceRecord.Status.PENDING, tag = "i6"),
                ),
            ),
        )
        // Paid by us: its fee is what left beyond its amount, from the history.
        storage.markInvoiceSendStatus("i1", delivered = true)
        storage.insertPayments(history("""[{"amt_msat":50300,"rhash":"${hashOf(1)}","ts":${now - 5000}}]"""))
        val records = storage.walletRecords(key).associateBy { it.paymentHash }
        assertEquals(5, records.size)
        val paid = records.getValue(hashOf(1))
        assertEquals(WalletRecord.Direction.OUTGOING, paid.direction)
        assertEquals(WalletRecord.PaymentType.BOLT11, paid.paymentType)
        assertEquals(WalletRecord.State.SETTLED, paid.state)
        assertEquals(300L, paid.feesMsat)
        assertEquals("lnbc1", paid.invoice)
        assertEquals("for the board", paid.description)
        assertEquals(WalletRecord.State.EXPIRED, records.getValue(hashOf(2)).state)
        assertEquals(WalletRecord.State.PENDING, records.getValue(hashOf(3)).state)
        assertEquals((now + 600_000) / 1000, records.getValue(hashOf(3)).expiresAt)
        assertEquals(WalletRecord.State.FAILED, records.getValue(hashOf(4)).state)
        // An invoice made that was paid shows what arrived, not what it asked.
        assertEquals(31_000L, records.getValue(hashOf(5)).amountMsat)
        // Only incoming-for-us entries never count against a budget.
        assertEquals(50_300L, WalletBudget.used(storage.walletRecords(key).mapNotNull { it.entry }, 0))
    }

    @Test fun `the records are told again as a payment moves`() = runBlocking {
        val storage = newStorage().with(keysend(1))
        val seen = mutableListOf<List<WalletRecord.State>>()
        val job = launch(Dispatchers.Unconfined) { storage.walletRecordUpdates().collect { seen += it.map { r -> r.state } } }
        storage.markInvoiceSendStatus("k1", delivered = true)
        // The same again moves nothing: not told.
        storage.markInvoiceSendStatus("k1", delivered = true)
        storage.insertPayments(history("""[{"amt_msat":10000,"rhash":"${hashOf(1)}","ts":5000}]"""))
        job.cancel()
        assertEquals(listOf(listOf(WalletRecord.State.PENDING), listOf(WalletRecord.State.SETTLED)), seen.distinct())
        assertEquals(seen.distinct(), listOf(listOf(WalletRecord.State.PENDING), listOf(WalletRecord.State.SETTLED)))
    }

    // MARK: The budget, counted in the payment's own write

    /// One that does not fit is refused and nothing is written; pending counts, failed does not; the person's approval counts without a limit.
    @Test fun `the budget is counted in the payments own write`() {
        val storage = newStorage().with(keysend(1, amount = 6_000), keysend(2, status = InvoiceRecord.Status.FAILED, amount = 50_000))
        val within = WalletBudget.Guard(10_000, 0)
        assertThrows(OverBudgetException::class.java) {
            storage.apply(Storage.Commit(keysends = listOf(keysend(3, amount = 5_000)), walletBudget = Storage.Commit.WalletBudgetCheck(key, 5_000, within)))
        }
        assertNull(storage.keysend("k3"))
        storage.apply(Storage.Commit(keysends = listOf(keysend(4, amount = 4_000)), walletBudget = Storage.Commit.WalletBudgetCheck(key, 4_000, within)))
        storage.apply(
            Storage.Commit(keysends = listOf(keysend(5, amount = 90_000)), walletBudget = Storage.Commit.WalletBudgetCheck(key, 90_000, WalletBudget.Guard(null, 0))),
        )
        assertEquals(100_000L, WalletBudget.used(storage.walletRecords(key).mapNotNull { it.entry }, 0))
        // Another connection's payments are not counted.
        storage.apply(
            Storage.Commit(
                keysends = listOf(keysend(6, amount = 1_000, connection = "org:globex")),
                walletBudget = Storage.Commit.WalletBudgetCheck("org:globex", 1_000, WalletBudget.Guard(1_000, 0)),
            ),
        )
    }

    @Test fun `a refused commit writes nothing at all`() {
        val storage = newStorage().with(keysend(1, amount = 6_000))
        val refused = Storage.Commit(
            settings = mapOf(Setting.BALANCE_MSAT to "123"),
            payments = history("""[{"amt_msat":10000,"rhash":"${hashOf(77)}","ts":5000}]"""),
            invoices = listOf(InvoiceRecord(hashOf(8), false, "lnbc", 5_000, "", "p", 1, null, InvoiceRecord.Status.PENDING, tag = "i8", connection = key, app = "a")),
            keysends = listOf(keysend(9, amount = 5_000)),
            walletBudget = Storage.Commit.WalletBudgetCheck(key, 5_000, WalletBudget.Guard(10_000, 0)),
        )
        assertThrows(OverBudgetException::class.java) { storage.apply(refused) }
        assertNull(storage.value(Setting.BALANCE_MSAT))
        assertTrue(storage.payments(10).isEmpty())
        assertNull(storage.invoice(hashOf(8), false))
        assertNull(storage.keysend("k9"))
    }

    @Test fun `a keysend repeating a hash or a tag abandons the commit`() {
        val storage = newStorage().with(keysend(1))
        assertThrows(Exception::class.java) { storage.apply(Storage.Commit(keysends = listOf(keysend(2, hash = hashOf(1))))) }
        assertThrows(Exception::class.java) { storage.apply(Storage.Commit(keysends = listOf(keysend(3).copy(tag = "k1")))) }
        assertThrows(Exception::class.java) {
            storage.apply(Storage.Commit(settings = mapOf(Setting.BALANCE_MSAT to "9"), keysends = listOf(keysend(4), keysend(4))))
        }
        assertNull(storage.value(Setting.BALANCE_MSAT))
        assertNull(storage.keysend("k4"))
        assertEquals(1, storage.walletRecords(null).size)
    }

    @Test fun `an invoice being paid is never written over, a failed one may be tried again`() {
        val storage = newStorage()
        fun invoice(status: InvoiceRecord.Status, connection: String?) =
            InvoiceRecord(hashOf(1), false, "lnbc", 1_000, "", "p", 1, null, status, tag = "t-$status", connection = connection, app = connection?.let { "a" })
        storage.apply(Storage.Commit(invoices = listOf(invoice(InvoiceRecord.Status.PENDING, key))))
        assertThrows(IllegalStateException::class.java) { storage.apply(Storage.Commit(invoices = listOf(invoice(InvoiceRecord.Status.PENDING, "org:globex")))) }
        assertEquals(key, storage.invoice(hashOf(1), false)!!.connection)
        storage.markInvoiceSendStatus("t-PENDING", delivered = false, error = "no")
        // Failed: tried again, through another connection this time.
        storage.apply(Storage.Commit(invoices = listOf(invoice(InvoiceRecord.Status.PENDING, "org:globex"))))
        assertEquals("org:globex", storage.invoice(hashOf(1), false)!!.connection)
        assertEquals(listOf("org:globex"), storage.walletRecords(null).map { it.connection })
    }
}
