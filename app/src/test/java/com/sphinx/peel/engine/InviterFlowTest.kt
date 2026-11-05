package com.sphinx.peel.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InviterFlowTest {
    private val invite = Invite("c0de", "lsp:8883", "02aa_03bb_1", "Alice")

    private class FakeStore(var stored: Invite?, val failToClear: Boolean = false) : PendingInviteStore {
        override fun peek() = stored
        override fun take(): Invite? {
            val invite = stored ?: return null
            if (failToClear) return null
            stored = null
            return invite
        }
        override fun save(invite: Invite) { stored = invite }
    }

    @Test
    fun `nothing pending does nothing`() = runBlocking {
        var called = false
        val outcome = InviterFlow.addIfPending(FakeStore(null)) { _, _, _ -> called = true }
        assertEquals(InviterFlow.Outcome.NothingPending, outcome)
        assertFalse(called)
    }

    @Test
    fun `the invite is forgotten before the key exchange starts`() = runBlocking {
        val store = FakeStore(invite)
        var seenDuring: Invite? = invite
        val outcome = InviterFlow.addIfPending(store) { info, alias, code ->
            seenDuring = store.peek()
            assertEquals("02aa_03bb_1", info)
            assertEquals("Alice", alias)
            assertEquals("c0de", code)
        }
        assertNull(seenDuring)
        assertNull(store.stored)
        assertEquals(InviterFlow.Outcome.Started("02aa_03bb_1"), outcome)
    }

    @Test
    fun `it goes at most once`() = runBlocking {
        val store = FakeStore(invite)
        var calls = 0
        InviterFlow.addIfPending(store) { _, _, _ -> calls++ }
        InviterFlow.addIfPending(store) { _, _, _ -> calls++ }
        assertEquals(1, calls)
    }

    @Test
    fun `an invite that cannot be forgotten is not sent`() = runBlocking {
        var called = false
        val outcome = InviterFlow.addIfPending(FakeStore(invite, failToClear = true)) { _, _, _ -> called = true }
        assertEquals(InviterFlow.Outcome.NothingPending, outcome)
        assertFalse(called)
    }

    @Test
    fun `not connected keeps the invite for the next connection`() = runBlocking {
        val store = FakeStore(invite)
        val outcome = InviterFlow.addIfPending(store) { _, _, _ -> throw NotConnectedException() }
        assertEquals(InviterFlow.Outcome.KeptForLater, outcome)
        assertEquals(invite, store.stored)
    }

    @Test
    fun `another failure is reported and the invite stays forgotten`() = runBlocking {
        val store = FakeStore(invite)
        val outcome = InviterFlow.addIfPending(store) { _, _, _ -> throw IllegalStateException("boom") }
        assertEquals(InviterFlow.Outcome.Failed("boom"), outcome)
        assertNull(store.stored)
    }

    @Test
    fun `a failure where nothing was sent is retried on the next connection`() = runBlocking {
        val store = FakeStore(invite)
        val outcome = InviterFlow.addIfPending(store, retryable = { it is IllegalArgumentException }) { _, _, _ ->
            throw IllegalArgumentException("core refused")
        }
        assertEquals(InviterFlow.Outcome.WillRetry("core refused"), outcome)
        assertEquals(invite, store.stored)
    }

    @Test
    fun `cancelling puts the invite back and propagates`() {
        val store = FakeStore(invite)
        try {
            runBlocking { InviterFlow.addIfPending(store) { _, _, _ -> throw CancellationException("stop") } }
            throw AssertionError("expected cancellation")
        } catch (e: CancellationException) {
            assertEquals(invite, store.stored)
        }
    }

    @Test
    fun `an invite naming nobody is forgotten and nothing is sent`() = runBlocking {
        val store = FakeStore(invite.copy(inviterContactInfo = null))
        var called = false
        val outcome = InviterFlow.addIfPending(store) { _, _, _ -> called = true }
        assertEquals(InviterFlow.Outcome.NoInviter, outcome)
        assertFalse(called)
        assertNull(store.stored)
    }

    @Test
    fun `a signup keeps its account when a register may have reached the server`() {
        // registered, redeemed -> keep?
        assertTrue(SignupRules.keepAccount(registered = true, inviteMayBeRedeemed = true))
        assertTrue(SignupRules.keepAccount(registered = true, inviteMayBeRedeemed = false))
        assertTrue(SignupRules.keepAccount(registered = false, inviteMayBeRedeemed = true))
        assertFalse(SignupRules.keepAccount(registered = false, inviteMayBeRedeemed = false))
    }

    @Test
    fun `publishes go settle then register then the rest, and a new key rest waits`() {
        fun p(name: String) = name to name.toByteArray()
        val plain = PublishPlan.of(p("settle"), p("register"), null, listOf(p("a"), p("b")), newChildKey = false)
        assertEquals(listOf("settle", "register", "a", "b"), plain.now.map { it.first })
        assertTrue(plain.later.isEmpty() && plain.afterPay.isEmpty())

        val newKey = PublishPlan.of(null, p("register"), null, listOf(p("a")), newChildKey = true)
        assertEquals(listOf("register"), newKey.now.map { it.first })
        assertEquals(listOf("a"), newKey.later.map { it.first })

        val paying = PublishPlan.of(null, null, p("pay"), listOf(p("a"), p("b")), newChildKey = false)
        assertEquals(listOf("pay"), paying.now.map { it.first })
        assertEquals(listOf("a", "b"), paying.afterPay.map { it.first })

        // A register without a new child key is not delayed.
        val sameKey = PublishPlan.of(null, p("register"), null, listOf(p("a")), newChildKey = false)
        assertEquals(listOf("register", "a"), sameKey.now.map { it.first })
    }
}
