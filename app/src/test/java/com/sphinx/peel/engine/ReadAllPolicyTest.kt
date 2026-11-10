package com.sphinx.peel.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAllPolicyTest {
    private var now = 1_000L
    private val policy = ReadAllPolicy({ now })
    private val window = ReadAllPolicy.DEFAULT_INTERVAL_MS

    private fun ok() = policy.shouldCall(localUnread = 0, connected = true, restoring = false, caughtUp = true)

    @Test fun `zero unread is allowed`() = assertTrue(ok())

    @Test fun `nonzero unread is blocked`() =
        assertFalse(policy.shouldCall(localUnread = 1, connected = true, restoring = false, caughtUp = true))

    @Test fun `throttled within the window and allowed after`() {
        policy.recordSuccess()
        now += window - 1
        assertFalse(ok())
        now += 1
        assertTrue(ok())
    }

    @Test fun `a failed attempt does not start the window`() {
        // No recordSuccess: a failure leaves the policy untouched.
        assertTrue(ok())
        now += 1
        assertTrue(ok())
    }

    @Test fun `restoring is blocked`() =
        assertFalse(policy.shouldCall(localUnread = 0, connected = true, restoring = true, caughtUp = true))

    @Test fun `offline is blocked`() =
        assertFalse(policy.shouldCall(localUnread = 0, connected = false, restoring = false, caughtUp = true))

    @Test fun `not caught up is blocked even with zero unread and connected`() =
        assertFalse(policy.shouldCall(localUnread = 0, connected = true, restoring = false, caughtUp = false))

    @Test fun `a completed catch-up sends once then the throttle holds`() {
        assertTrue(ok())
        policy.recordSuccess()
        now += 1
        assertFalse(ok())
    }

    @Test fun `an index that moved skips and none recorded skips`() {
        assertFalse(ReadAllPolicy.arrivedSince(5L, 5L))
        assertTrue(ReadAllPolicy.arrivedSince(5L, 6L))
        assertTrue(ReadAllPolicy.arrivedSince(null, -1L))
    }
}
