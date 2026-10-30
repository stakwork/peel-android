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
}
