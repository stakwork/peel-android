package com.sphinx.peel.account

import org.junit.Assert.assertEquals
import org.junit.Test

/// Only the pure string helpers: anything touching sphinxrs needs the
/// Android native lib, which a plain JVM unit test can't load.
class AccountTest {
    @Test
    fun `splits on whitespace and commas, any case`() {
        assertEquals(listOf("one", "two", "three"), Account.wordsIn("One,  two\nThree"))
    }

    @Test
    fun `normalises to single-space-joined lowercase`() {
        assertEquals("one two three", Account.normalise(" One,Two  three "))
    }
}
