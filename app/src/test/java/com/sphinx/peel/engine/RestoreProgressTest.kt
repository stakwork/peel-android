package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestoreProgressTest {
    @Test
    fun `fraction is reached over end`() {
        assertEquals(2.0 / 3.0, RestoreCount(2, 3).fraction!!, 1e-9)
    }

    @Test
    fun `fraction is clamped to one`() {
        assertEquals(1.0, RestoreCount(5, 3).fraction!!, 0.0)
    }

    @Test
    fun `fraction is null without a positive end`() {
        assertNull(RestoreCount(2, null).fraction)
        assertNull(RestoreCount(0, 0).fraction)
    }
}
