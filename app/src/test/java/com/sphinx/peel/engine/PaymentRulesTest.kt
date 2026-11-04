package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentRulesTest {
    @Test
    fun `fee reserve is one percent with a ten sat floor`() {
        assertEquals(10_000L, PaymentRules.feeReserveMsat(100_000))
        assertEquals(10_000L, PaymentRules.feeReserveMsat(1_000_000))
        assertEquals(20_000L, PaymentRules.feeReserveMsat(2_000_000))
    }

    @Test
    fun `confirmation is needed over the threshold unless confirmed for that amount`() {
        assertFalse(PaymentRules.needsConfirmation(100_000, null, 100_000))
        assertTrue(PaymentRules.needsConfirmation(100_001, null, 100_000))
        assertFalse(PaymentRules.needsConfirmation(200_000, 200_000, 100_000))
        // A confirmation never covers a total that changed since.
        assertTrue(PaymentRules.needsConfirmation(300_000, 200_000, 100_000))
        // 0 asks every time.
        assertTrue(PaymentRules.needsConfirmation(1_000, null, 0))
    }

    @Test
    fun `fits checks the amount plus reserve against the balance without overflow`() {
        assertTrue(PaymentRules.fits(900, 100, 1_000))
        assertFalse(PaymentRules.fits(901, 100, 1_000))
        assertFalse(PaymentRules.fits(Long.MAX_VALUE, 1, Long.MAX_VALUE))
    }

    @Test
    fun `contact info splits in three or is null`() {
        assertEquals(listOf("aa", "bb", "1"), PaymentRules.contactInfoParts("AA_bb_1"))
        assertNull(PaymentRules.contactInfoParts("aa_bb"))
        assertNull(PaymentRules.contactInfoParts("aa__1"))
        assertNull(PaymentRules.contactInfoParts(null))
    }
}
