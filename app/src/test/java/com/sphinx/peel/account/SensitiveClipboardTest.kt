package com.sphinx.peel.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveClipboardTest {
    private class Fake : SensitiveClipboard.ClipboardAccess {
        var label: String? = null
        var text: String? = null
        var sensitive = false
        var clears = 0
        override fun put(label: String, text: String, sensitive: Boolean) { this.label = label; this.text = text; this.sensitive = sensitive }
        override fun currentLabel() = label
        override fun clear() { label = null; text = null; clears++ }
    }

    private val tasks = mutableListOf<Pair<Long, () -> Unit>>()
    private var n = 0
    private val access = Fake()
    private val board = SensitiveClipboard(access, { d, t -> tasks.add(d to t) }, { "n${n++}" })

    @Test fun `copies as sensitive and clears after the timeout`() {
        board.copy("lnbc1...", 5_000)
        assertTrue(access.sensitive)
        assertEquals("lnbc1...", access.text)
        assertEquals(5_000L, tasks.single().first)
        tasks.single().second()
        assertNull(access.text)
    }

    @Test fun `does not clear something the person copied since`() {
        board.copy("secret", 5_000)
        access.put("user", "their own", false) // another app / the user
        tasks.single().second()
        assertEquals("their own", access.text)
        assertEquals(0, access.clears)
    }

    @Test fun `an older timer does not wipe a newer copy`() {
        board.copy("one", 5_000)
        board.copy("two", 5_000)
        tasks[0].second()
        assertEquals("two", access.text)
        tasks[1].second()
        assertNull(access.text)
    }

    @Test fun `clearNow clears our clip only`() {
        board.copy("x")
        board.clearNow()
        assertNull(access.text)
        access.put("user", "mine", false)
        board.clearNow()
        assertEquals("mine", access.text)
    }

    @Test fun `paste clears only when words were found`() {
        assertTrue(SensitiveClipboard.shouldClearAfterPaste(12))
        assertFalse(SensitiveClipboard.shouldClearAfterPaste(0))
    }
}
