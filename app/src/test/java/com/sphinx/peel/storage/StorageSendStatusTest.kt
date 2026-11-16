package com.sphinx.peel.storage

import androidx.test.core.app.ApplicationProvider
import com.sphinx.peel.engine.InvoiceRecord
import com.sphinx.peel.engine.KeysendRecord
import com.sphinx.peel.engine.PaymentRecord
import com.sphinx.peel.engine.SendRetry
import com.sphinx.peel.engine.SendStatus
import com.sphinx.peel.engine.WalletRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// The one forward-only rule for every send tracked by tag, including `UNSETTLED` (iOS `Storage.applyStatus`, `reconcile`, `pendingSendTags`,
/// `oldestOpenSend`, `waiting`), over the four tables that track one: our message, the invoice we pay, the invite it funds and an app's keysend.
@RunWith(RobolectricTestRunner::class)
class StorageSendStatusTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val storage = Storage(context, account = "ss-${System.nanoTime()}")
    private val now = System.currentTimeMillis()
    private val min = 60_000L
    private fun hashOf(n: Int) = "%064x".format(9000 + n)
    private val peer = "02" + "a".repeat(64)

    private fun keysend(n: Int, async: Boolean = true, minutesAgo: Long = 1, status: InvoiceRecord.Status = InvoiceRecord.Status.PENDING) = KeysendRecord(
        paymentHash = hashOf(n), tag = "k$n", pubkey = peer, routeHint = if (async) "03${"b".repeat(64)}_1" else null, async = async, amountMsat = 10_000,
        createdAt = now - minutesAgo * min, status = status, connection = "org:acme", app = "acme/board",
    )

    private fun message(n: Int, type: Int, status: String? = "PENDING", msat: Long? = null, minutesAgo: Long = 1, hash: String? = hashOf(n), fromMe: Boolean = true) =
        MessageRow(
            uuid = "u$n", serverIndex = null, type = type, chat = peer, fromMe = fromMe, sender = null, body = "x", msat = msat,
            timestamp = now - minutesAgo * min, tag = "m$n", paymentHash = hash, status = status,
        ).also { storage.insertMessages(listOf(it)) }

    private fun statusOf(n: Int) = storage.messages(peer).first { it.tag == "m$n" }.status

    @Test fun `a keysend moves pending to unsettled to failed to settled, never back`() {
        storage.apply(Storage.Commit(keysends = listOf(keysend(1), keysend(2))))
        storage.markSendStatus("k1", SendStatus.UNSETTLED)
        assertEquals(InvoiceRecord.Status.UNSETTLED, storage.keysend("k1")!!.status)
        // Unsettled is the server's last word on it, still pending to the app and holding nothing back.
        assertEquals(WalletRecord.State.PENDING, storage.walletRecords("org:acme").first { it.paymentHash == hashOf(1) }.state)
        assertEquals(10_000L, storage.waitingMsat(now))
        // It never goes back to unsettled from failed or settled, and unsettled does not undo a failure.
        storage.markSendStatus("k1", SendStatus.FAILED, "no route")
        storage.markSendStatus("k1", SendStatus.UNSETTLED)
        assertEquals(InvoiceRecord.Status.FAILED, storage.keysend("k1")!!.status)
        assertEquals("no route", storage.keysend("k1")!!.error)
        // The server had it after all.
        storage.markSendStatus("k1", SendStatus.DELIVERED)
        val k1 = storage.keysend("k1")!!
        assertEquals(InvoiceRecord.Status.PAID, k1.status)
        assertNull(k1.error)
        assertTrue(k1.paidAt != null)
        storage.markSendStatus("k1", SendStatus.FAILED, "late")
        storage.markSendStatus("k1", SendStatus.UNSETTLED)
        assertEquals(InvoiceRecord.Status.PAID, storage.keysend("k1")!!.status)
        // From unsettled straight to settled, and a failure keeps the first reason when it gives none.
        storage.markSendStatus("k2", SendStatus.UNSETTLED)
        storage.markSendStatus("k2", SendStatus.DELIVERED)
        assertEquals(InvoiceRecord.Status.PAID, storage.keysend("k2")!!.status)
    }

    @Test fun `a failure with no reason keeps the one it had`() {
        storage.apply(Storage.Commit(keysends = listOf(keysend(1))))
        storage.markSendStatus("k1", SendStatus.FAILED, "first")
        storage.markSendStatus("k1", SendStatus.DELIVERED)
        storage.markSendStatus("k1", SendStatus.FAILED)
        assertEquals(InvoiceRecord.Status.PAID, storage.keysend("k1")!!.status)
        storage.apply(Storage.Commit(keysends = listOf(keysend(2))))
        storage.markSendStatus("k2", SendStatus.UNSETTLED)
        storage.markSendStatus("k2", SendStatus.FAILED)
        assertNull(storage.keysend("k2")!!.error)
    }

    @Test fun `only a message that moves sats can be unsettled`() {
        message(1, type = 5, msat = 5_000)
        message(2, type = 0)
        message(3, type = 29, msat = 1_000)
        for (n in 1..3) storage.markSendStatus("m$n", SendStatus.UNSETTLED)
        assertEquals("UNSETTLED", statusOf(1))
        assertEquals("PENDING", statusOf(2))
        assertEquals("UNSETTLED", statusOf(3))
        // Unsettled goes to delivered or failed; failed goes to delivered; delivered never moves.
        storage.markSendStatus("m1", SendStatus.FAILED)
        assertEquals("FAILED", statusOf(1))
        storage.markSendStatus("m1", SendStatus.DELIVERED)
        assertEquals("DELIVERED", statusOf(1))
        storage.markSendStatus("m1", SendStatus.FAILED)
        assertEquals("DELIVERED", statusOf(1))
        storage.markSendStatus("m3", SendStatus.DELIVERED)
        assertEquals("DELIVERED", statusOf(3))
    }

    @Test fun `an unsettled payment is neither waiting nor asked about, but its history is still sought`() {
        message(1, type = 5, msat = 7_000, minutesAgo = 90)
        storage.apply(Storage.Commit(keysends = listOf(keysend(2, minutesAgo = 120))))
        storage.markSendStatus("m1", SendStatus.UNSETTLED)
        storage.markSendStatus("k2", SendStatus.UNSETTLED)
        assertEquals(0L, storage.waitingMsat(now))
        assertTrue(storage.pendingSends(now).isEmpty())
        // The history must still reach back to settle them: the older of the two.
        assertEquals(now - 120 * min, storage.oldestOpenSend(0))
        assertNull(storage.oldestOpenSend(now))
        storage.markSendStatus("k2", SendStatus.DELIVERED)
        assertEquals(now - 90 * min, storage.oldestOpenSend(0))
        storage.markSendStatus("m1", SendStatus.DELIVERED)
        assertNull(storage.oldestOpenSend(0))
    }

    @Test fun `the oldest open send needs a payment hash and counts only money sends`() {
        message(1, type = 5, msat = 1, minutesAgo = 10, hash = null)
        message(2, type = 0, minutesAgo = 50)
        message(3, type = 5, msat = 1, minutesAgo = 20, fromMe = false)
        assertNull(storage.oldestOpenSend(0))
        message(4, type = 3, minutesAgo = 30)
        assertEquals(now - 30 * min, storage.oldestOpenSend(0))
        storage.apply(Storage.Commit(keysends = listOf(keysend(5, minutesAgo = 40))))
        assertEquals(now - 40 * min, storage.oldestOpenSend(0))
    }

    @Test fun `pending sends name keysends and say which move money and when they were sent`() {
        storage.apply(Storage.Commit(keysends = listOf(keysend(1, minutesAgo = 45), keysend(2, async = false, minutesAgo = 1), keysend(3, status = InvoiceRecord.Status.PAID))))
        message(1, type = 5, msat = 1_000, minutesAgo = 40)
        message(2, type = 0, minutesAgo = 5)
        message(3, type = 0, minutesAgo = 0)
        message(4, type = 0, status = null, minutesAgo = 3)
        storage.saveInvoice(InvoiceRecord(hashOf(50), false, "lnbc", 1_000, "", peer, now - 2 * min, null, InvoiceRecord.Status.PENDING, tag = "i1"))
        val pending = storage.pendingSends(now - SendRetry.CONFIRMATION_TIMEOUT_MS).associateBy { it.tag }
        assertEquals(setOf("k1", "k2", "m1", "m2", "m4", "i1"), pending.keys)
        assertTrue(pending.getValue("k1").movesMoney)
        assertEquals(now - 45 * min, pending.getValue("k1").createdAt)
        assertTrue(pending.getValue("k2").movesMoney)
        assertTrue(pending.getValue("m1").movesMoney)
        assertTrue(pending.getValue("i1").movesMoney)
        // Text moves nothing, and one with no status came from elsewhere and is never failed for want of a record.
        assertFalse(pending.getValue("m2").movesMoney)
        assertFalse(pending.getValue("m4").movesMoney)
        assertEquals(pending.keys, storage.pendingSendTags(now - SendRetry.CONFIRMATION_TIMEOUT_MS).toSet())
    }

    /// A payment the server has no record of half an hour on never reached it: failed, so a keysend can be sent again and a message can be Retried; never one
    /// the server names, never a text, never one still young.
    @Test fun `a payment the server never recorded fails, and nothing else`() {
        storage.apply(Storage.Commit(keysends = listOf(keysend(1, minutesAgo = 31), keysend(2, minutesAgo = 31), keysend(3, minutesAgo = 5))))
        message(1, type = 5, msat = 1_000, minutesAgo = 31)
        message(2, type = 0, minutesAgo = 31)
        val pending = storage.pendingSends(now)
        val unrecorded = SendStatus.unrecorded(pending, now)
        assertEquals(setOf("k1", "k2", "m1"), unrecorded)
        // The server's answers named k2 (any status, even pending): that one is not failed.
        val failed = SendStatus.failedForWantOfRecord(unrecorded, named = setOf("k2", "m2"))
        assertEquals(listOf("k1", "m1"), failed)
        for (tag in failed) storage.markSendStatus(tag, SendStatus.FAILED)
        assertEquals(InvoiceRecord.Status.FAILED, storage.keysend("k1")!!.status)
        assertEquals(InvoiceRecord.Status.PENDING, storage.keysend("k2")!!.status)
        assertEquals(InvoiceRecord.Status.PENDING, storage.keysend("k3")!!.status)
        assertEquals("FAILED", statusOf(1))
        assertEquals("PENDING", statusOf(2))
        // A delivered one is never failed after the fact.
        storage.markSendStatus("m1", SendStatus.DELIVERED)
        storage.markSendStatus("m1", SendStatus.FAILED)
        assertEquals("DELIVERED", statusOf(1))
    }

    private fun history(vararg rows: String) = PaymentRecord.decode("[${rows.joinToString(",")}]")
    private fun row(hash: String, ts: Long, error: String? = null) =
        """{"amt_msat":10000,"rhash":"$hash","ts":$ts${if (error != null) ",\"error\":\"$error\"" else ""}}"""

    @Test fun `the history settles or fails our open sends by their payment hash`() {
        storage.apply(Storage.Commit(keysends = listOf(keysend(1), keysend(2), keysend(3))))
        message(1, type = 5, msat = 1_000)
        message(2, type = 5, msat = 1_000)
        message(3, type = 29, msat = 1_000)
        message(4, type = 0, hash = hashOf(4))
        storage.markSendStatus("m2", SendStatus.UNSETTLED)
        storage.markSendStatus("k2", SendStatus.UNSETTLED)
        storage.markSendStatus("m3", SendStatus.FAILED)
        storage.markSendStatus("k3", SendStatus.FAILED)
        storage.insertPayments(history(row(hashOf(1), 100, "gone"), row(hashOf(2), 101), row(hashOf(3), 102), row(hashOf(4), 103)))
        // A failed row with no good one fails a pending send (the sats came back); a good row settles an unsettled or failed one after all.
        assertEquals("FAILED", statusOf(1))
        assertEquals(InvoiceRecord.Status.FAILED, storage.keysend("k1")!!.status)
        assertEquals("gone", storage.keysend("k1")!!.error)
        assertEquals("DELIVERED", statusOf(2))
        assertEquals(InvoiceRecord.Status.PAID, storage.keysend("k2")!!.status)
        assertEquals(101L, storage.keysend("k2")!!.paidAt)
        assertEquals("DELIVERED", statusOf(3))
        assertEquals(InvoiceRecord.Status.PAID, storage.keysend("k3")!!.status)
        // A text with that hash is not a money send and is never moved by the history.
        assertEquals("PENDING", statusOf(4))
    }

    @Test fun `a failed row does not fail a send another row of the history paid`() {
        storage.apply(Storage.Commit(keysends = listOf(keysend(1))))
        message(1, type = 5, msat = 1_000)
        storage.insertPayments(history(row(hashOf(1), 100)))
        storage.markSendStatus("m1", SendStatus.UNSETTLED) // moves nothing: already delivered by the history
        assertEquals("DELIVERED", statusOf(1))
        storage.insertPayments(history(row(hashOf(1), 90, "retry failed")))
        assertEquals("DELIVERED", statusOf(1))
        assertEquals(InvoiceRecord.Status.PAID, storage.keysend("k1")!!.status)
    }

    // MARK: The books' view of an invoice

    private fun invoiceRow(n: Int, incoming: Boolean, status: InvoiceRecord.Status) =
        InvoiceRecord(hashOf(n), incoming, "lnbc$n", 1_000, "", peer, now, null, status, tag = "t$n")

    @Test fun `what the books say of an invoice`() {
        storage.saveInvoice(invoiceRow(1, true, InvoiceRecord.Status.OPEN))
        assertEquals(Storage.PaidState.OWN, storage.paidState(hashOf(1)))
        for ((n, status) in listOf(2 to InvoiceRecord.Status.PENDING, 3 to InvoiceRecord.Status.UNSETTLED)) {
            storage.saveInvoice(invoiceRow(n, false, status))
            assertEquals(Storage.PaidState.PENDING, storage.paidState(hashOf(n)))
        }
        storage.saveInvoice(invoiceRow(4, false, InvoiceRecord.Status.PAID))
        assertEquals(Storage.PaidState.PAID, storage.paidState(hashOf(4)))
        storage.saveInvoice(invoiceRow(5, false, InvoiceRecord.Status.FAILED))
        assertNull(storage.paidState(hashOf(5)))
        assertNull(storage.paidState(hashOf(6)))
        // Failed here but paid from another device, or paid with no record of ours at all.
        storage.insertPayments(history(row(hashOf(5), 1), row(hashOf(6), 2), row(hashOf(7), 3, "no route")))
        assertEquals(Storage.PaidState.PAID, storage.paidState(hashOf(5)))
        assertEquals(Storage.PaidState.PAID, storage.paidState(hashOf(6)))
        assertNull(storage.paidState(hashOf(7)))
    }
}
