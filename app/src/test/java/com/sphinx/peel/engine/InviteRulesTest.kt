package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private typealias S = InviteRecord.Status

class InviteRulesTest {


    @Test
    fun `a funding payment moves its invite the way PeelCore maps it`() {
        assertEquals(S.SENDING, InviteRules.forFunding(SendStatus.PENDING))
        assertEquals(S.READY, InviteRules.forFunding(SendStatus.DELIVERED))
        assertEquals(S.FAILED, InviteRules.forFunding(SendStatus.FAILED))
        assertNull(InviteRules.forFunding(SendStatus.UNSETTLED))
    }

    @Test
    fun `only a sending invite moves to ready or failed, and nothing moves a ready, cancelled or used one`() {
        assertEquals(listOf(S.SENDING, S.FAILED), InviteRules.fundingMovesFrom(SendStatus.DELIVERED).sortedBy { it.ordinal })
        assertEquals(listOf(S.SENDING), InviteRules.fundingMovesFrom(SendStatus.FAILED))
        for (status in SendStatus.entries) {
            val from = InviteRules.fundingMovesFrom(status)
            assertFalse(S.CANCELLED in from || S.USED in from || S.CANCELLING in from)
        }
    }

    @Test
    fun `only a ready or cancelling invite can be cancelled`() {
        assertTrue(InviteRules.canCancel(S.READY))
        assertTrue(InviteRules.canCancel(S.CANCELLING))
        for (s in listOf(S.SENDING, S.FAILED, S.CANCELLED, S.USED)) assertFalse(s.name, InviteRules.canCancel(s))
    }

    @Test
    fun `a cancel counts once the balance rises by at most the invite`() {
        assertTrue(InviteRules.refunded(before = 1_000, now = 11_000, invitedMsat = 10_000))
        assertTrue(InviteRules.refunded(before = 1_000, now = 9_000, invitedMsat = 10_000))
        assertFalse(InviteRules.refunded(before = 1_000, now = 1_000, invitedMsat = 10_000))
        assertFalse(InviteRules.refunded(before = 1_000, now = 500, invitedMsat = 10_000))
        assertFalse(InviteRules.refunded(before = 1_000, now = 20_000, invitedMsat = 10_000))
    }

    @Test
    fun `names and notes are trimmed and bounded`() {
        assertNull(InviteRules.cleanNickname("   "))
        assertEquals("Bob", InviteRules.cleanNickname(" Bob "))
        try { InviteRules.cleanNickname("x".repeat(101)); fail() } catch (e: InviteRefused) {}
        try { InviteRules.cleanMessage("x".repeat(501)); fail() } catch (e: InviteRefused) {}
        assertEquals("x".repeat(500), InviteRules.cleanMessage("x".repeat(500)))
    }

    @Test
    fun `pending invites are the ones still waiting`() {
        val record = InviteRecord("c", "l", 1, null, null, "t", S.SENDING, 0)
        assertTrue(record.isPending)
        assertTrue(record.copy(status = S.CANCELLING).isPending)
        assertFalse(record.copy(status = S.USED).isPending)
        assertEquals(S.USED, S.fromRaw("used"))
        assertNull(S.fromRaw("nope"))
    }
}
