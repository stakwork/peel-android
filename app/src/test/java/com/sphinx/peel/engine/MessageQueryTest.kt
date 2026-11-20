package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageQueryTest {
    @Test
    fun `words must all appear, as separate terms`() {
        val q = MessageQuery.parse("  lunch   tomorrow ")
        assertEquals(listOf("lunch", "tomorrow"), q.terms)
        assertNull(q.from)
        assertFalse(q.isEmpty)
    }

    @Test
    fun `a quoted phrase is one term`() {
        val q = MessageQuery.parse("\"see you soon\" lunch")
        assertEquals(listOf("see you soon", "lunch"), q.terms)
    }

    @Test
    fun `an unclosed quote runs to the end`() {
        assertEquals(listOf("see you"), MessageQuery.parse("\"see you").terms)
    }

    @Test
    fun `from takes a word or a quoted name`() {
        assertEquals("ann", MessageQuery.parse("from:ann").from)
        assertEquals("ann lee", MessageQuery.parse("lunch FROM:\"ann lee\"").from)
        assertEquals(listOf("lunch"), MessageQuery.parse("lunch FROM:\"ann lee\"").terms)
        assertFalse(MessageQuery.parse("from:ann").isEmpty)
        assertTrue(MessageQuery.parse("from:annlee").terms.isEmpty())
    }

    @Test
    fun `an empty from is nothing`() {
        assertTrue(MessageQuery.parse("from:").isEmpty)
    }

    @Test
    fun `terms shorter than the minimum are left out and flagged`() {
        assertEquals(3, MessageQuery.minimum)
        val q = MessageQuery.parse("hi lunch ok")
        assertEquals(listOf("lunch"), q.terms)
        assertTrue(q.hasShortTerms)
        val onlyShort = MessageQuery.parse("hi")
        assertTrue(onlyShort.isEmpty)
        assertTrue(onlyShort.hasShortTerms)
        assertFalse(MessageQuery.parse("abc").hasShortTerms)
    }

    @Test
    fun `NULs are stripped`() {
        assertEquals(listOf("lunch"), MessageQuery.parse("lu\u0000nch").terms)
    }

    @Test
    fun `ftsMatch quotes every term and never passes user text through`() {
        assertEquals("\"lunch\" AND \"see you\"", MessageQuery.parse("lunch \"see you\"").ftsMatch)
        assertEquals("\"a\"\"bc\"", MessageQuery.parse("a\"bc").ftsMatch)
        assertEquals("\"AND\" AND \"NEAR(x\"", MessageQuery.parse("AND NEAR(x").ftsMatch)
        assertEquals("", MessageQuery.parse("from:ann").ftsMatch)
    }

    @Test
    fun `snippet keeps a short text whole`() {
        assertEquals("lunch at noon", MessageQuery.parse("lunch").snippet("lunch at noon"))
    }

    @Test
    fun `snippet starts at a word boundary before the first term with an ellipsis`() {
        val text = "one two three four five six seven eight nine ten eleven twelve thirteen fourteen LUNCH today"
        val snippet = MessageQuery.parse("lunch").snippet(text, lead = 20)
        assertTrue(snippet, snippet.startsWith("…"))
        assertTrue(snippet, snippet.endsWith("LUNCH today"))
        // Starts on a whole word.
        assertTrue(snippet, text.contains(snippet.removePrefix("…")))
        assertTrue(snippet, text.contains(" " + snippet.removePrefix("…")))
    }

    @Test
    fun `snippet is one line and ignores case and accents`() {
        val q = MessageQuery.parse("cafe")
        assertEquals("a Café near", q.snippet("a Café\nnear"))
    }

    @Test
    fun `highlights find every term, merged`() {
        val q = MessageQuery.parse("lunch lun")
        assertEquals(listOf(0..4, 10..14), q.highlights("Lunch and Lunch"))
        assertEquals(listOf(0..3), MessageQuery.parse("café").highlights("CAFE x"))
    }
}
