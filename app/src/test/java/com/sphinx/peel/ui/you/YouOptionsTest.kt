package com.sphinx.peel.ui.you

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouOptionsTest {
    @Test fun groupsThousandsWithDots() {
        assertEquals("0", YouOptions.groupedSats(0))
        assertEquals("999", YouOptions.groupedSats(999))
        assertEquals("1.000", YouOptions.groupedSats(1000))
        assertEquals("5.179", YouOptions.groupedSats(5179))
        assertEquals("1.234.567", YouOptions.groupedSats(1234567))
        assertEquals("-1.000", YouOptions.groupedSats(-1000))
    }

    @Test fun unsetPinTimeoutIsADay() {
        assertEquals("24", YouOptions.pinTimeoutOf(null))
        assertEquals("24", YouOptions.pinTimeoutOf("7"))
        assertEquals("4", YouOptions.pinTimeoutOf("4"))
    }

    @Test fun unsetPaymentConfirmationIsOver100Sats() {
        assertEquals(100_000L, YouOptions.paymentConfirmationOf(null))
        assertEquals(0L, YouOptions.paymentConfirmationOf("0"))
        assertEquals(1_000_000L, YouOptions.paymentConfirmationOf("1000000"))
        assertEquals(100_000L, YouOptions.paymentConfirmationOf("5"))
    }

    @Test fun boostAmountMustBeAWholePositiveNumber() {
        assertEquals(7L, YouOptions.parseBoostSats("7"))
        assertNull(YouOptions.parseBoostSats("0"))
        assertNull(YouOptions.parseBoostSats(""))
        assertNull(YouOptions.parseBoostSats("1.5"))
    }
}
