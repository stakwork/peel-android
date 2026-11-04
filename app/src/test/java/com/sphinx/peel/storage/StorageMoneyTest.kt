package com.sphinx.peel.storage

import androidx.test.core.app.ApplicationProvider
import com.sphinx.peel.engine.InvoiceRecord
import com.sphinx.peel.engine.PaymentRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StorageMoneyTest {
    private fun newStorage() = Storage(ApplicationProvider.getApplicationContext(), account = "money-${System.nanoTime()}")
    private val hash = "abababababababababababababababababababababababababababababababab"

    private fun record(incoming: Boolean, status: InvoiceRecord.Status, tag: String? = null, amount: Long = 10_000) = InvoiceRecord(
        paymentHash = hash, incoming = incoming, bolt11 = "lnbc1", amountMsat = amount, memo = "m", payee = "02aa",
        createdAt = 1000, expiresAt = null, status = status, tag = tag,
    )

    @Test
    fun `an invoice being paid or paid is never written over`() {
        val storage = newStorage()
        assertTrue(storage.saveInvoice(record(false, InvoiceRecord.Status.PENDING, tag = "t1")))
        assertFalse(storage.saveInvoice(record(false, InvoiceRecord.Status.PENDING, tag = "t2")))
        assertEquals("t1", storage.invoice(hash, incoming = false)?.tag)
    }

    @Test
    fun `a failed payment can be paid again`() {
        val storage = newStorage()
        storage.saveInvoice(record(false, InvoiceRecord.Status.PENDING, tag = "t1"))
        storage.markInvoiceSendStatus("t1", delivered = false, error = "no route")
        assertEquals(InvoiceRecord.Status.FAILED, storage.invoice(hash, false)?.status)
        assertEquals("no route", storage.invoice(hash, false)?.error)
        assertTrue(storage.saveInvoice(record(false, InvoiceRecord.Status.PENDING, tag = "t2")))
    }

    @Test
    fun `the mixer's word settles a pending payment, and only a pending one`() {
        val storage = newStorage()
        storage.saveInvoice(record(false, InvoiceRecord.Status.PENDING, tag = "t1"))
        storage.markInvoiceSendStatus("t1", delivered = true)
        assertEquals(InvoiceRecord.Status.PAID, storage.invoice(hash, false)?.status)
        storage.markInvoiceSendStatus("t1", delivered = false)
        assertEquals(InvoiceRecord.Status.PAID, storage.invoice(hash, false)?.status)
    }

    @Test
    fun `an invoice we made is paid, or underpaid, by what arrived`() {
        val full = newStorage()
        full.saveInvoice(record(true, InvoiceRecord.Status.OPEN))
        full.settleInvoice(hash, true, 10_000, 5_000)
        assertEquals(InvoiceRecord.Status.PAID, full.invoice(hash, true)?.status)
        assertEquals(5_000L, full.invoice(hash, true)?.paidAt)

        val partial = newStorage()
        partial.saveInvoice(record(true, InvoiceRecord.Status.OPEN))
        partial.settleInvoice(hash, true, 4_000, 5_000)
        assertEquals(InvoiceRecord.Status.UNDERPAID, partial.invoice(hash, true)?.status)
        // Never moved backwards or on.
        partial.settleInvoice(hash, true, 10_000, 6_000)
        assertEquals(InvoiceRecord.Status.UNDERPAID, partial.invoice(hash, true)?.status)
    }

    @Test
    fun `history is stored newest first with its edges, and a row seen again is replaced`() {
        val storage = newStorage()
        assertNull(storage.oldestPaymentTimestamp())
        storage.insertPayments(
            listOf(
                PaymentRecord(hash, 100, 1, true, 3, false, null),
                PaymentRecord("cd".repeat(32), 300, 2, false, null, true, null),
            ),
        )
        assertEquals(listOf(300L, 100L), storage.payments(10).map { it.ts })
        assertEquals(100L, storage.oldestPaymentTimestamp())
        assertEquals(300L, storage.newestPaymentTimestamp())
        assertEquals(1, storage.payments(1).size)
        storage.insertPayments(listOf(PaymentRecord(hash, 100, 1, true, 3, false, "late error")))
        assertEquals("late error", storage.payments(10).first { it.rhash == hash }.error)
    }

    @Test
    fun `a history row finds the message that came with it`() {
        val storage = newStorage()
        storage.insertMessages(
            listOf(MessageRow("u1", 1, 5, "chat1", false, null, "{\"content\":\"hi\"}", 5_000, 1, "t", hash)),
        )
        assertEquals("chat1", storage.messageByPaymentHash(hash)?.chat)
        assertNull(storage.messageByPaymentHash("cd".repeat(32)))
    }
}
