package com.sphinx.peel.ui

import com.sphinx.peel.ui.components.SecureRefCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureRefCountTest {
    @Test fun nestedUsesOnlyClearOnLastRelease() {
        val c = SecureRefCount()
        assertTrue(c.acquire())
        assertFalse(c.acquire())
        assertFalse(c.release())
        assertTrue(c.release())
        assertEquals(0, c.active)
    }

    @Test fun extraReleaseIsIgnored() {
        val c = SecureRefCount()
        assertFalse(c.release())
        assertTrue(c.acquire())
    }
}
