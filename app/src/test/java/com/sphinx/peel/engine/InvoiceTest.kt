package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// Robolectric only for org.json, which a plain JVM test gets as stubs.
@RunWith(RobolectricTestRunner::class)
class InvoiceTest {
    private val hash = "abababababababababababababababababababababababababababababababab"

    private fun json(extra: String = "") =
        """{"pubkey":"02aa","value":21000,"payment_hash":"$hash","description":"coffee","expiry":2000000000,"hop_hints":["03bb_123"]$extra}"""

    @Test
    fun `the core's parse is read into an invoice`() {
        val invoice = Invoice.fromParsedJson("lnbc1", json())!!
        assertEquals(21_000L, invoice.amountMsat)
        assertEquals("02aa", invoice.payee)
        assertEquals("coffee", invoice.memo)
        assertEquals(2_000_000_000_000L, invoice.expiresAtMs)
        assertEquals(listOf("03bb_123"), invoice.hopHints)
        assertFalse(invoice.isExpired(nowMs = 1_000L))
        assertTrue(invoice.isExpired(nowMs = 2_000_000_000_000L))
    }

    @Test
    fun `an amountless invoice reads as zero msat`() {
        val invoice = Invoice.fromParsedJson("lnbc1", """{"pubkey":"02aa","value":null,"payment_hash":"$hash"}""")!!
        assertEquals(0L, invoice.amountMsat)
        assertEquals("", invoice.memo)
        assertNull(invoice.expiresAtMs)
        assertFalse(invoice.isExpired())
    }

    @Test
    fun `a bad hash, missing payee or junk reads as nothing`() {
        assertNull(Invoice.fromParsedJson("lnbc1", """{"pubkey":"02aa","payment_hash":"nothex"}"""))
        assertNull(Invoice.fromParsedJson("lnbc1", """{"payment_hash":"$hash"}"""))
        assertNull(Invoice.fromParsedJson("lnbc1", "not json"))
        assertNull(Invoice.fromParsedJson("lnbc1", """{"pubkey":"02aa","value":-1,"payment_hash":"$hash"}"""))
    }

    @Test
    fun `history rows are checked and incoming ones recognised by their index`() {
        val rows = PaymentRecord.decode(
            """[
            {"rhash":"$hash","ts":1700000000000,"amt_msat":5000,"remote":true,"msg_idx":7},
            {"rhash":"${hash.uppercase()}","ts":1700000000001,"amt_msat":6000,"msg_idx":0},
            {"rhash":"$hash","ts":1700000000002,"amt_msat":7000,"error":"no route"},
            {"rhash":"short","ts":1,"amt_msat":1},
            {"rhash":"$hash","ts":-1,"amt_msat":1},
            {"rhash":"$hash","ts":1},
            "junk"
            ]""",
        )
        assertEquals(3, rows.size)
        assertTrue(rows[0].incoming); assertEquals(7L, rows[0].msgIndex); assertTrue(rows[0].remote)
        // An invoice the server made for us comes with index 0: incoming, but no message.
        assertTrue(rows[1].incoming); assertNull(rows[1].msgIndex)
        assertFalse(rows[2].incoming); assertTrue(rows[2].failed); assertEquals("no route", rows[2].error)
        assertEquals(emptyList<PaymentRecord>(), PaymentRecord.decode(null))
        assertEquals(emptyList<PaymentRecord>(), PaymentRecord.decode("{}"))
    }
}
