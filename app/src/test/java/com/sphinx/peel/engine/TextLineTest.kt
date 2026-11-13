package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class TextLineTest {
    @Test fun controlsAndSeparatorsBecomeSpaces() {
        assertEquals("a b c d", TextLine.clean("a\nb\tc\u2028d", 80))
        assertEquals("a b", TextLine.clean("a\u2029b", 80))
    }

    @Test fun hiddenCharactersAreDropped() {
        assertEquals("abc", TextLine.clean("a\u200Eb\u202E\u2066\u200B\uFEFF\u00AD\u061Cc\u2060\u200F", 80))
        assertEquals("ab", TextLine.clean("a" + String(Character.toChars(0xE0041)) + "b", 80))
    }

    @Test fun whitespaceIsOneSpaceAndTrimmed() {
        assertEquals("a b c", TextLine.clean("  a   b \u00A0 c  ", 80))
        assertEquals("", TextLine.clean(" \n ", 80))
    }

    @Test fun markupOpenerIsBrokenUp() {
        assertEquals("<< <", TextLine.clean("<<<", 80))
        assertEquals("<< <<", TextLine.clean("<<<<", 80))
        assertEquals("<< <<", TextLine.clean("<<<<<", 80).take(5))
    }

    @Test fun longTextIsCutWithAnEllipsis() {
        assertEquals("abcd", TextLine.clean("abcd", 4))
        assertEquals("abc…", TextLine.clean("abcde", 4))
        assertEquals("😀😀…", TextLine.clean("😀😀😀😀", 3))
    }
}
