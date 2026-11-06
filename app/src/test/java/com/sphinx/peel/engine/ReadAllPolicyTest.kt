package com.sphinx.peel.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAllPolicyTest {
    private var now = 1_000L
    private val policy = ReadAllPolicy({ now })
    private val window = ReadAllPolicy.DEFAULT_INTERVAL_MS

    private fun ok() = policy.shouldCall(localUnread = 0, connected = true, restoring = false)

    @Test fun `zero unread is allowed`() = assertTrue(ok())

    @Test fun `nonzero unread is blocked`() =
        assertFalse(policy.shouldCall(localUnread = 1, connected = true, restoring = false))

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
        assertFalse(policy.shouldCall(localUnread = 0, connected = true, restoring = true))

    @Test fun `offline is blocked`() =
        assertFalse(policy.shouldCall(localUnread = 0, connected = false, restoring = false))
}
