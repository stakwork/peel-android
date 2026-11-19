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

    private val twelve = listOf("abandon", "ability", "able", "about", "above", "absent", "absorb", "abstract", "absurd", "abuse", "access", "accident")

    @Test
    fun `a check passes on the right words in any case and spacing`() {
        assertEquals(emptyList<Int>(), Account.wrongPlaces(mapOf(0 to " Abandon ", 5 to "absent", 11 to "ACCIDENT"), twelve))
    }

    @Test
    fun `a check names its wrong places in order, numbered from 1`() {
        val wrong = Account.wrongPlaces(mapOf(11 to "access", 0 to "abandon", 3 to "about extra"), twelve)
        assertEquals(listOf(3, 11), wrong)
        assertEquals("Words 4, 12 don't match.", Account.wrongPlacesProblem(wrong))
        assertEquals("Word 2 doesn't match.", Account.wrongPlacesProblem(listOf(1)))
    }

    @Test
    fun `only a skipped signup reads as not checked`() {
        assertEquals(false, Account.wordsChecked("0"))
        assertEquals(true, Account.wordsChecked("1"))
        // Restored before restores wrote "1": typed all twelve back in.
        assertEquals(true, Account.wordsChecked(null))
    }
}
