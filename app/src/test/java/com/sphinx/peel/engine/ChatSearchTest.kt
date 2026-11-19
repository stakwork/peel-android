package com.sphinx.peel.engine

import com.sphinx.peel.storage.MessageRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSearchTest {
    private fun row(n: Int) = MessageRow(
        uuid = "m$n", serverIndex = n.toLong(), type = 0, chat = "alice", fromMe = false, sender = null, body = "hello $n",
        msat = null, timestamp = n.toLong(), tag = null, paymentHash = null, id = n.toLong(),
    )

    private fun rows(count: Int) = (count downTo 1).map(::row)
    private val hello = MessageQuery.parse("hello")

    @Test
    fun `status words`() {
        val s = ChatSearch("alice")
        assertNull(s.status)
        s.found(emptyList(), hello)
        assertEquals("No matches", s.status)
        s.found(rows(3), hello)
        assertEquals("3 matches", s.status)
        s.stepOlder()
        assertEquals("1 of 3", s.status)
        s.found(rows(1), hello)
        assertEquals("1 match", s.status)
        s.found(emptyList(), hello, failed = true)
        assertEquals("Could not search", s.status)
    }

    @Test
    fun `no status while searching with nothing yet`() {
        val s = ChatSearch("alice")
        s.found(emptyList(), hello)
        s.searching = true
        assertNull(s.status)
        s.found(rows(2), hello)
        s.searching = true
        assertEquals("2 matches", s.status)
    }

    @Test
    fun `the limit shows as 1,000+`() {
        val s = ChatSearch("alice")
        s.found(rows(ChatSearch.LIMIT), hello)
        assertEquals("1,000+ matches", s.status)
        s.stepOlder()
        assertEquals("1 of 1,000+", s.status)
    }

    @Test
    fun `short words get a hint`() {
        val s = ChatSearch("alice")
        s.text = "hi"
        assertEquals("3 or more characters", s.countText)
        s.text = "hello"
        assertNull(s.countText)
    }

    @Test
    fun `stepping stops at both ends`() {
        val s = ChatSearch("alice")
        s.found(rows(3), hello)
        assertFalse(s.canStepNewer)
        assertTrue(s.canStepOlder)
        assertNull(s.stepNewer())
        assertEquals("m3", s.stepOlder()?.uuid) // the first is the newest
        assertEquals(0, s.current)
        assertFalse(s.canStepNewer)
        assertEquals("m2", s.stepOlder()?.uuid)
        assertEquals("m1", s.stepOlder()?.uuid)
        assertFalse(s.canStepOlder)
        assertNull(s.stepOlder())
        assertEquals(2, s.current)
        assertEquals("m2", s.stepNewer()?.uuid)
        assertTrue(s.canStepNewer)
    }

    @Test
    fun `nothing to step with no matches`() {
        val s = ChatSearch("alice")
        s.found(emptyList(), hello)
        assertFalse(s.canStepOlder)
        assertFalse(s.canStepNewer)
    }

    @Test
    fun `history note belongs to the search that ran while history synced`() {
        val s = ChatSearch("alice")
        assertNull(s.historyNote)
        s.found(rows(1), hello, historySyncing = true)
        assertEquals("Still loading older messages", s.historyNote)
        s.found(rows(1), hello, historySyncing = false)
        assertNull(s.historyNote)
        s.found(emptyList(), null, historySyncing = true)
        assertNull(s.historyNote)
    }

    @Test
    fun `found resets the current match and searching`() {
        val s = ChatSearch("alice")
        s.found(rows(3), hello)
        s.stepOlder()
        s.searching = true
        s.found(rows(2), hello)
        assertNull(s.current)
        assertFalse(s.searching)
        assertEquals(2, s.matches.size)
    }

    @Test
    fun `Return waits for matches of what is typed and then shows the first`() {
        val s = ChatSearch("alice")
        assertFalse(s.waitForMatches()) // nothing typed
        s.text = "hello"
        assertTrue(s.waitForMatches())
        assertEquals(hello, s.returnAwaits)
        assertTrue(s.found(rows(2), hello))
        assertNull(s.returnAwaits)
        // Already searched: Return steps instead.
        assertFalse(s.waitForMatches())
    }

    @Test
    fun `Return finds nothing to show for no matches, other matches or a failure`() {
        val s = ChatSearch("alice")
        s.text = "hello"
        s.waitForMatches()
        assertFalse(s.found(emptyList(), hello))
        s.waitForMatches() // typed == searched: not waiting
        s.text = "hello there"
        assertTrue(s.waitForMatches())
        assertFalse(s.found(rows(2), hello)) // matches for an older query
        assertTrue(s.found(rows(2), MessageQuery.parse("hello there")))
        s.text = "other"
        s.waitForMatches()
        assertFalse(s.found(rows(2), MessageQuery.parse("other"), failed = true))
    }

    @Test
    fun `the Search menu item again asks for focus`() {
        val s = ChatSearch("alice")
        assertEquals(0, s.focusRequests)
        s.requestFocus()
        assertEquals(1, s.focusRequests)
    }

    @Test
    fun `select picks a match by index`() {
        val s = ChatSearch("alice")
        s.found(rows(3), hello)
        assertEquals("m1", s.select(2)?.uuid)
        assertEquals("3 of 3", s.status)
        assertNull(s.select(7))
    }
}
