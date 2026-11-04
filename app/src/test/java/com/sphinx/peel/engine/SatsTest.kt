package com.sphinx.peel.engine

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SatsTest {
    private val us = Locale.US

    @Test
    fun `format drops the rest of a sat and pluralises`() {
        assertEquals("1 sat", Sats.format(1_000, us))
        assertEquals("1 sat", Sats.format(1_999, us))
        assertEquals("0 sats", Sats.format(999, us))
        assertEquals("0 sats", Sats.format(-5_000, us))
        assertEquals("1,210 sats", Sats.format(1_210_000, us))
    }

    @Test
    fun `signed uses a true minus and no sign for zero`() {
        assertEquals("+500", Sats.signed(500_000, incoming = true, locale = us))
        assertEquals("\u22122,000", Sats.signed(2_000_000, incoming = false, locale = us))
        assertEquals("0", Sats.signed(400, incoming = true, locale = us))
    }

    @Test
    fun `msat reads whole sats above zero only`() {
        assertEquals(1_000L, Sats.msat("1"))
        assertEquals(1_000_000L, Sats.msat(" 1,000 "))
        assertNull(Sats.msat("0"))
        assertNull(Sats.msat(""))
        assertNull(Sats.msat("1.5"))
        assertNull(Sats.msat("-3"))
        assertNull(Sats.msat("abc"))
        assertNull(Sats.msat("99999999999999999999"))
        assertNull(Sats.msat((Long.MAX_VALUE / 1000 + 1).toString()))
    }
}
