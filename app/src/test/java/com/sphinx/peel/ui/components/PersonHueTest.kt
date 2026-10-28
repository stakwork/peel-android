package com.sphinx.peel.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/// `PersonHue.of` is a hand-rolled FNV-1a; a mistake in it still compiles
/// and still picks *a* hue, just not the same one every device agrees on
/// (the whole point, per PeelCore's `PersonHue.swift`). Expected values
/// cross-checked against an independent FNV-1a-32 implementation, not
/// derived from this one.
class PersonHueTest {
    @Test
    fun `hashes the known test vectors to the expected hue`() {
        // "abc" -> FNV-1a("abc-color") = 3274258853, mod 8 = 5 -> VIOLET
        assertEquals(PersonHue.VIOLET, PersonHue.of("abc"))
        // "03abcdef" -> 3800848349, mod 8 = 5 -> VIOLET
        assertEquals(PersonHue.VIOLET, PersonHue.of("03abcdef"))
        // "pubkey123" -> 2078856599, mod 8 = 7 -> PINK
        assertEquals(PersonHue.PINK, PersonHue.of("pubkey123"))
    }

    @Test
    fun `the same pubkey always gets the same hue`() {
        val pubkey = "03" + "a".repeat(64)
        assertSame(PersonHue.of(pubkey), PersonHue.of(pubkey))
    }
}
