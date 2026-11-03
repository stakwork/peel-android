package com.sphinx.peel.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockoutPolicyTest {
    private val t0 = LockoutPolicy.Clock(elapsedMs = 10_000, wallMs = 1_000_000, bootId = "A")

    @Test fun `schedule escalates and caps`() {
        assertEquals(listOf(0L, 0, 0, 0), (1..4).map { LockoutPolicy.delayMsAfter(it) })
        assertEquals(30_000L, LockoutPolicy.delayMsAfter(5))
        assertEquals(60_000L, LockoutPolicy.delayMsAfter(6))
        assertEquals(300_000L, LockoutPolicy.delayMsAfter(7))
        assertEquals(900_000L, LockoutPolicy.delayMsAfter(8))
        assertEquals(3_600_000L, LockoutPolicy.delayMsAfter(9))
        assertEquals(21_600_000L, LockoutPolicy.delayMsAfter(10))
        assertEquals(21_600_000L, LockoutPolicy.delayMsAfter(500))
        // never decreases
        assertTrue((1..30).map { LockoutPolicy.delayMsAfter(it) }.zipWithNext().all { (a, b) -> b >= a })
    }

    private fun failures(n: Int, clock: LockoutPolicy.Clock): LockoutPolicy.State =
        (1..n).fold(LockoutPolicy.State.CLEAN) { s, _ -> LockoutPolicy.registerAttempt(s, clock) }

    @Test fun `first four failures impose no wait, the fifth does`() {
        assertEquals(0, LockoutPolicy.remainingMs(failures(4, t0), t0))
        assertEquals(30_000, LockoutPolicy.remainingMs(failures(5, t0), t0))
    }

    @Test fun `wait counts down on the monotonic clock in the same boot, ignoring the wall clock`() {
        val s = failures(5, t0)
        assertEquals(20_000, LockoutPolicy.remainingMs(s, t0.copy(elapsedMs = 20_000, wallMs = t0.wallMs - 10_000_000)))
        assertEquals(0, LockoutPolicy.remainingMs(s, t0.copy(elapsedMs = 40_000, wallMs = t0.wallMs - 10_000_000)))
        // wall clock moved forward a day must not shorten it either
        assertEquals(30_000, LockoutPolicy.remainingMs(s, t0.copy(wallMs = t0.wallMs + 86_400_000)))
    }

    @Test fun `after a reboot the wall clock counts, and a clock set back gives the full wait`() {
        val s = failures(6, t0) // 60 s
        val rebooted = LockoutPolicy.Clock(elapsedMs = 500, wallMs = t0.wallMs + 15_000, bootId = "B")
        assertEquals(45_000, LockoutPolicy.remainingMs(s, rebooted))
        assertEquals(60_000, LockoutPolicy.remainingMs(s, rebooted.copy(wallMs = t0.wallMs - 5_000)))
        assertEquals(0, LockoutPolicy.remainingMs(s, rebooted.copy(wallMs = t0.wallMs + 3_600_000)))
    }

    @Test fun `an unknown boot id is treated as a reboot`() {
        val s = failures(5, t0.copy(bootId = ""))
        assertEquals(20_000, LockoutPolicy.remainingMs(s, t0.copy(bootId = "", wallMs = t0.wallMs + 10_000)))
    }

    @Test fun `remaining never exceeds the wait`() {
        val s = failures(5, t0)
        assertTrue(LockoutPolicy.remainingMs(s, LockoutPolicy.Clock(0, t0.wallMs - 1, "A")) <= 30_000)
    }

    @Test fun `tampered state is at least the floor`() {
        val s = LockoutPolicy.tampered(t0)
        assertEquals(LockoutPolicy.TAMPER_FLOOR, s.failures)
        assertEquals(30_000, LockoutPolicy.remainingMs(s, t0))
    }

    @Test fun `state round trips and garbage is refused`() {
        val s = failures(7, t0)
        assertEquals(s, LockoutPolicy.decode(LockoutPolicy.encode(s)))
        assertNull(LockoutPolicy.decode("2|1|1|1|1|x"))
        assertNull(LockoutPolicy.decode("1|-1|1|1|1|x"))
        assertNull(LockoutPolicy.decode("nonsense"))
    }

    @Test fun `describe rounds up`() {
        assertEquals("1 second", LockoutPolicy.describe(1))
        assertEquals("30 seconds", LockoutPolicy.describe(30_000))
        assertEquals("1 minute", LockoutPolicy.describe(60_000))
        assertEquals("5 minutes", LockoutPolicy.describe(240_001))
        assertEquals("1 hour", LockoutPolicy.describe(3_600_000))
        assertEquals("6 hours", LockoutPolicy.describe(21_600_000))
    }
}
