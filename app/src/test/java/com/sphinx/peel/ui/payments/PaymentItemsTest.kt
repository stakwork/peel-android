package com.sphinx.peel.ui.payments

import com.sphinx.peel.engine.InvoiceRecord
import com.sphinx.peel.engine.PaymentRecord
import com.sphinx.peel.storage.MessageRow
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PaymentItemsTest {
    private val a = "abababababababababababababababababababababababababababababababab"
    private val b = "cd".repeat(32)

    private fun message(type: Int, fromMe: Boolean, body: String, status: String? = null) =
        MessageRow("u", 1, type, "chat1", fromMe, null, body, 1, 1, "t", null, status = status)

    @Test
    fun `a history row is joined to its message for kind, chat and note`() {
        val items = PaymentItem.build(
            listOf(PaymentRecord(a, 2_000, 5_000, false, null, false, null)),
            emptyList(),
        ) { message(5, true, "{\"content\":\"thanks\"}") }
        assertEquals(1, items.size)
        assertEquals(PaymentItem.Kind.PAYMENT, items[0].kind)
        assertEquals("chat1", items[0].chat)
        assertEquals("thanks", items[0].note)
        assertEquals(PaymentItem.Status.COMPLETE, items[0].status)
    }

    @Test
    fun `a bare keysend has no chat, a fee is not shown as a note, a failed row is failed`() {
        val bare = PaymentItem.build(listOf(PaymentRecord(a, 1, 1, true, 2, false, null)), emptyList()) { null }.single()
        assertEquals(PaymentItem.Kind.KEYSEND, bare.kind)
        assertNull(bare.chat)

        val fee = PaymentItem.build(listOf(PaymentRecord(a, 1, 3_000, false, null, false, null)), emptyList()) {
            message(0, true, "{\"content\":\"hello tribe\"}")
        }.single()
        assertEquals(PaymentItem.Kind.MESSAGE_FEE, fee.kind)
        assertNull(fee.note)

        val failed = PaymentItem.build(listOf(PaymentRecord(a, 1, 1, false, null, false, "no route")), emptyList()) { null }.single()
        assertEquals(PaymentItem.Status.FAILED, failed.status)
    }

    @Test
    fun `a payment of ours still pending is listed before the history has it, newest first`() {
        val pending = InvoiceRecord(b, false, "lnbc1", 9_000, "tea", "02aa", 5_000, null, InvoiceRecord.Status.PENDING, tag = "t")
        val items = PaymentItem.build(listOf(PaymentRecord(a, 1_000, 1, true, 2, false, null)), listOf(pending)) { null }
        assertEquals(listOf("out/$b", "in/$a"), items.map { it.id })
        assertEquals(PaymentItem.Status.PENDING, items[0].status)
        assertEquals("tea", items[0].note)
    }

    @Test
    fun `a sent message still going out reads pending`() {
        val item = PaymentItem.build(listOf(PaymentRecord(a, 1, 1, false, null, false, null)), emptyList()) {
            message(5, true, "{}", status = "PENDING")
        }.single()
        assertEquals(PaymentItem.Status.PENDING, item.status)
    }

    @Test
    fun `items group by calendar day in order`() {
        val utc = TimeZone.getTimeZone("UTC")
        val day = 86_400_000L
        val items = listOf(3 * day + 5, 3 * day + 1, 2 * day + 9).mapIndexed { i, ts ->
            PaymentItem("i$i", a, true, 1, ts, PaymentItem.Status.COMPLETE, PaymentItem.Kind.KEYSEND, null, null, null)
        }
        val days = PaymentItem.byDay(items, utc)
        assertEquals(listOf(3 * day, 2 * day), days.map { it.first })
        assertEquals(listOf(2, 1), days.map { it.second.size })
    }

    @Test
    fun `display words follow the iPhone`() {
        val tribe = com.sphinx.peel.storage.ContactRow("chat1", "Sphinx & Stak", null, true, true)
        val fee = PaymentItem.build(listOf(PaymentRecord(a, 1, 3_000, false, null, false, null)), emptyList(), { tribe }, { message(0, true, "{}") }).single()
        assertEquals("Sphinx & Stak", fee.title)
        assertEquals("Message fee in #Sphinx & Stak", fee.subtitle)
        assertEquals("Message fee sent", fee.headline)
        assertEquals("In #Sphinx & Stak", fee.context)
        val bare = PaymentItem.build(listOf(PaymentRecord(a, 1, 3_000, false, null, false, null)), emptyList()) { null }.single()
        assertEquals("Keysend", bare.title)
        assertEquals("Sent, not tied to a chat", bare.subtitle)
        assertEquals("62c22a\u2026" + "27d6", PaymentItem.shortKey("62c22a" + "0".repeat(52) + "27d6"))
    }
}
