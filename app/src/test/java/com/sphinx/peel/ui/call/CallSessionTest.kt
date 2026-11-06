package com.sphinx.peel.ui.call

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CallSessionTest {
    @Test
    fun `formatElapsed shows minutes and seconds under an hour`() {
        assertEquals("0:00", CallSession.formatElapsed(0))
        assertEquals("0:05", CallSession.formatElapsed(5))
        assertEquals("3:18", CallSession.formatElapsed(198))
        assertEquals("59:59", CallSession.formatElapsed(3599))
    }

    @Test
    fun `formatElapsed switches to hours past sixty minutes`() {
        assertEquals("1:00:00", CallSession.formatElapsed(3600))
        assertEquals("1:02:07", CallSession.formatElapsed(3727))
    }

    @Test
    fun `earliestStart picks the earliest join time`() {
        assertEquals(100L, CallSession.earliestStart(null, listOf(300L, 100L, 200L), 999L))
    }

    @Test
    fun `earliestStart only moves earlier`() {
        assertEquals(100L, CallSession.earliestStart(100L, listOf(300L), 999L))
        assertEquals(50L, CallSession.earliestStart(100L, listOf(50L, 300L), 999L))
    }

    @Test
    fun `earliestStart ignores null join times`() {
        assertEquals(200L, CallSession.earliestStart(null, listOf(null, 200L, null), 999L))
    }

    @Test
    fun `earliestStart falls back to the connect time only with nothing else`() {
        assertEquals(999L, CallSession.earliestStart(null, listOf(null, null), 999L))
        assertEquals(999L, CallSession.earliestStart(null, emptyList(), 999L))
        assertEquals(500L, CallSession.earliestStart(500L, listOf(null), 999L))
    }
}
