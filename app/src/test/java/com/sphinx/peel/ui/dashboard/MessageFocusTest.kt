package com.sphinx.peel.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageFocusTest {
    @Test
    fun `an item below the middle scrolls forward by the gap between centres`() {
        // Viewport 0..1000 (centre 500); item 700..800 (centre 750).
        assertEquals(250f, centerScrollDelta(700, 100, 0, 1000), 0.001f)
    }

    @Test
    fun `an item above the middle scrolls back`() {
        assertEquals(-450f, centerScrollDelta(0, 100, 0, 1000), 0.001f)
    }

    @Test
    fun `a centred item does not move`() {
        assertEquals(0f, centerScrollDelta(450, 100, 0, 1000), 0.001f)
    }

    @Test
    fun `an item taller than the viewport lines its top up`() {
        assertEquals(300f, centerScrollDelta(300, 1500, 0, 1000), 0.001f)
    }
}
