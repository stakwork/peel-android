package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentInputTest {
    private val invoice = "lnbc1u1pj0abcdefghijklmnopqrstuvwxyz0123456789"

    @Test
    fun `a bare invoice is taken lower-cased`() {
        assertEquals(PaymentInput.Bolt11(invoice), PaymentInput.parse("  ${invoice.uppercase()}\n"))
    }

    @Test
    fun `a lightning prefix is tolerated in any case`() {
        assertEquals(PaymentInput.Bolt11(invoice), PaymentInput.parse("lightning:$invoice"))
        assertEquals(PaymentInput.Bolt11(invoice), PaymentInput.parse("LIGHTNING:${invoice.uppercase()}"))
    }

    @Test
    fun `a bip21 uri with a lightning fallback yields the invoice`() {
        val uri = "bitcoin:bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq?amount=0.001&lightning=${invoice.uppercase()}"
        assertEquals(PaymentInput.Bolt11(invoice), PaymentInput.parse(uri))
    }

    @Test
    fun `an on-chain uri without a fallback is recognised but not payable`() {
        assertTrue(PaymentInput.parse("bitcoin:bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq") is PaymentInput.Unsupported)
    }

    @Test
    fun `lnurl and lightning addresses are recognised but not payable`() {
        assertEquals(PaymentInput.Unsupported("an LNURL"), PaymentInput.parse("LNURL1DP68GURN8GHJ7"))
        assertEquals(PaymentInput.Unsupported("an LNURL"), PaymentInput.parse("lightning:lnurl1dp68gurn8ghj7"))
        assertEquals(PaymentInput.Unsupported("a Lightning address"), PaymentInput.parse("Tom@example.com"))
    }

    @Test
    fun `anything else, or too long, is unknown`() {
        assertEquals(PaymentInput.Unknown, PaymentInput.parse(""))
        assertEquals(PaymentInput.Unknown, PaymentInput.parse("hello world"))
        assertEquals(PaymentInput.Unknown, PaymentInput.parse("https://example.com/ln"))
        assertEquals(PaymentInput.Unknown, PaymentInput.parse("ln" + "a".repeat(4096)))
        assertEquals(PaymentInput.Unknown, PaymentInput.parse("ln abc"))
    }

    @Test
    fun `normalize returns only an invoice`() {
        assertEquals(invoice, Invoice.normalize("lightning:$invoice"))
        assertEquals(null, Invoice.normalize("tom@example.com"))
    }
}
