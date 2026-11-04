package com.sphinx.peel.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/// Same table as PeelCore's PINTimeoutTests.
class PinTimeoutTest {
    @Test fun `official to peel`() {
        assertEquals(PinTimeout.NEVER, PinTimeout.peelHours(25))
        assertEquals(PinTimeout.NEVER, PinTimeout.peelHours(99))
        assertEquals(0, PinTimeout.peelHours(0))
        assertEquals(0, PinTimeout.peelHours(-3))
        assertEquals(1, PinTimeout.peelHours(2))
        assertEquals(4, PinTimeout.peelHours(3))
        assertEquals(4, PinTimeout.peelHours(8))
        assertEquals(12, PinTimeout.peelHours(18))
        assertEquals(24, PinTimeout.peelHours(24))
    }

    @Test fun `peel to official never reads as never`() {
        assertEquals(25, PinTimeout.officialHours(PinTimeout.NEVER))
        assertEquals(24, PinTimeout.officialHours(24))
        assertEquals(24, PinTimeout.officialHours(100))
        assertEquals(0, PinTimeout.officialHours(0))
    }

    @Test fun `only a positive timeout re-locks, and only past it`() {
        val hour = 3_600_000L
        assertFalse(PinTimeout.isLockDue(PinTimeout.NEVER, 1000 * hour))
        assertFalse(PinTimeout.isLockDue(0, 1000 * hour))
        assertFalse(PinTimeout.isLockDue(4, 4 * hour))
        assertTrue(PinTimeout.isLockDue(4, 4 * hour + 1))
    }
}
