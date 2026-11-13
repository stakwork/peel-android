package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPageTest {
    /// A group is refused when it holds any item in `bad`.
    private fun refusing(bad: Set<Int>): (List<Int>) -> Boolean = { group -> group.any { it in bad } }

    @Test
    fun `skips exactly the bad items and keeps the rest`() {
        val page = (0 until 10).toList()
        assertEquals(listOf(0, 1, 2, 4, 5, 6, 8, 9), HistoryPage.readable(page, refusing(setOf(3, 7))))
        assertEquals((1 until 10).toList(), HistoryPage.readable(page, refusing(setOf(0))))
        assertEquals((0 until 9).toList(), HistoryPage.readable(page, refusing(setOf(9))))
    }

    @Test
    fun `a page where every item is bad keeps none`() {
        assertEquals(emptyList<Int>(), HistoryPage.readable(listOf(1, 2, 3, 4, 5), refusing(setOf(1, 2, 3, 4, 5))))
    }

    @Test
    fun `a single item or an empty page keeps none`() {
        assertEquals(emptyList<Int>(), HistoryPage.readable(listOf(7), refusing(setOf(7))))
        assertEquals(emptyList<Int>(), HistoryPage.readable(emptyList(), refusing(emptySet())))
    }

    @Test
    fun `a page refused only as a whole keeps both halves`() {
        // Refused as a group of more than two, though no part of it is.
        val page = listOf(1, 2, 3, 4)
        assertEquals(listOf(1, 2, 3, 4), HistoryPage.readable(page) { it.size > 2 })
    }

    private fun onion(index: Long, payment: Boolean = false): ByteArray {
        val bytes = ByteArray(if (payment) 1430 else 1425)
        val at = if (payment) 1366 else 1401
        for (i in 0 until 8) bytes[at + i] = (index ushr (56 - 8 * i)).toByte()
        return bytes
    }

    @Test
    fun `a framed page reads back as its onions with their indices`() {
        val onions = listOf(onion(5), onion(6, payment = true), onion(300))
        val read = HistoryPage.onions(HistoryPage.batch(onions))!!
        assertEquals(3, read.size)
        assertEquals(listOf(5L, 6L, 300L), read.map { HistoryPage.index(it) })
        assertTrue(HistoryPage.onions(HistoryPage.batch(emptyList()))!!.isEmpty())
    }

    @Test
    fun `a payload that is not a page is not read`() {
        assertNull(HistoryPage.onions(byteArrayOf()))
        assertNull(HistoryPage.onions(byteArrayOf(0x91.toByte(), 0xC4.toByte(), 3, 1, 2, 3)))
        assertNull(HistoryPage.onions("hello".toByteArray()))
    }

    @Test
    fun `readablePart leaves out the refused onions and names their indices`() {
        val page = listOf(onion(10), onion(11), onion(12), onion(13))
        val payload = HistoryPage.batch(page)
        val (kept, left) = HistoryPage.readablePart(payload, IllegalStateException("refused")) { framed ->
            val onions = HistoryPage.onions(framed)!!
            if (onions.any { HistoryPage.index(it) == 12L }) throw IllegalStateException("bad")
            onions.map { HistoryPage.index(it) }
        }
        assertEquals(listOf(10L, 11L, 13L), kept)
        assertEquals(listOf(12L), left)
    }

    @Test
    fun `readablePart throws the refusal when the payload is not a page`() {
        val refusal = IllegalStateException("refused")
        try {
            HistoryPage.readablePart("live".toByteArray(), refusal) { it }
            throw AssertionError("expected the refusal")
        } catch (e: IllegalStateException) {
            assertTrue(e === refusal)
        }
    }
}

class RestorePageTest {
    @Test
    fun `an empty page before the end is not complete`() {
        assertEquals(PageVerdict.RETRY, RestorePage.verdict(reached = null, next = 100, end = 500))
        assertEquals(PageVerdict.RETRY, RestorePage.verdict(reached = null, next = 500, end = 500))
    }

    @Test
    fun `an empty page is the end with no end to reach or past it`() {
        assertEquals(PageVerdict.END, RestorePage.verdict(reached = null, next = 100, end = null))
        assertEquals(PageVerdict.END, RestorePage.verdict(reached = null, next = 501, end = 500))
    }

    @Test
    fun `a stale reply is ignored, never moving next backwards`() {
        assertEquals(PageVerdict.RETRY, RestorePage.verdict(reached = 99, next = 100, end = 500))
        assertEquals(PageVerdict.RETRY, RestorePage.verdict(reached = 0, next = 100, end = null))
    }

    @Test
    fun `a page reaching next or beyond advances`() {
        assertEquals(PageVerdict.ADVANCE, RestorePage.verdict(reached = 100, next = 100, end = 500))
        assertEquals(PageVerdict.ADVANCE, RestorePage.verdict(reached = 199, next = 100, end = null))
        assertEquals(PageVerdict.ADVANCE, RestorePage.verdict(reached = 500, next = 400, end = 500))
    }
}
