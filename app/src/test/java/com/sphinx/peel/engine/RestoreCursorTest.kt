package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreCursorTest {
    private val counts = MessageCounts(total = 11653, firstForEachKey = 214, totalHighestIndex = 12000, firstForEachKeyHighestIndex = 900)

    @Test
    fun `a restore plans contacts first, then messages`() {
        val plan = RestoreCursor.plan(counts)!!
        assertEquals(RestoreCursor(RestoreCursor.Phase.CONTACTS, 0, 900, true, 12000), plan)
        assertEquals(RestoreCursor(RestoreCursor.Phase.MESSAGES, 0, 12000), plan.following())
        assertNull(plan.following()!!.following())
    }

    @Test
    fun `plans with only one side, or none`() {
        assertEquals(
            RestoreCursor(RestoreCursor.Phase.MESSAGES, 0, 5),
            RestoreCursor.plan(MessageCounts(3, 0, 5, null)),
        )
        val contactsOnly = RestoreCursor.plan(MessageCounts(0, 2, null, 7))!!
        assertEquals(RestoreCursor.Phase.CONTACTS, contactsOnly.phase)
        assertFalse(contactsOnly.thenMessages)
        assertNull(contactsOnly.following())
        assertNull(RestoreCursor.plan(MessageCounts(0, 0, null, null)))
    }

    @Test
    fun `encode and decode round trip, with and without unknown ends`() {
        for (c in listOf(
            RestoreCursor(RestoreCursor.Phase.CONTACTS, 42, 900, true, 12000),
            RestoreCursor(RestoreCursor.Phase.MESSAGES, 0, null),
            RestoreCursor(RestoreCursor.Phase.CONTACTS, 7, null, true, null),
        )) {
            assertEquals(c, RestoreCursor.decode(c.encode()))
        }
    }

    @Test
    fun `unreadable text is no cursor`() {
        for (bad in listOf(null, "", "garbage", "2|M|0||0|", "1|X|0||0|", "1|M|-1||0|", "1|M|a||0|", "1|M|0|b|0|", "1|M|0||2|", "1|M|0||0")) {
            assertNull(bad, RestoreCursor.decode(bad))
        }
    }

    @Test
    fun `advancing moves past the highest index and never backwards`() {
        val c = RestoreCursor(RestoreCursor.Phase.MESSAGES, 100, 500)
        assertEquals(200, c.advanced(199).next)
        assertEquals(100, c.advanced(50).next)
        assertEquals(100, c.advanced(99).next)
    }

    @Test
    fun `past the end only when the end is known and exceeded`() {
        assertFalse(RestoreCursor(RestoreCursor.Phase.MESSAGES, 500, 500).isPastEnd)
        assertTrue(RestoreCursor(RestoreCursor.Phase.MESSAGES, 501, 500).isPastEnd)
        assertFalse(RestoreCursor(RestoreCursor.Phase.MESSAGES, 10_000, null).isPastEnd)
    }
}
