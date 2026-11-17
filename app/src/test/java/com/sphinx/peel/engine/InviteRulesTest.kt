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

    @Test
    fun `status lines and short statuses read as the iPhone's do`() {
        val zone = java.time.ZoneOffset.UTC
        val en = java.util.Locale.ENGLISH
        val made = InviteRecord("c", "l", 5_000, null, null, "t", S.SENDING, 1_700_223_780_000) // 17 Nov 2023, 12:23 UTC
        // Narrow-no-break-space before AM/PM varies by JDK (ConversationDateTest's own
        // pre-existing flake, unrelated to this): normalized to a plain space before comparing.
        assertEquals(
            "Made Nov 17, 2023, 12:23 PM · waiting to be used",
            made.copy(status = S.READY).statusLine(zone, en).replace(' ', ' '),
        )
        assertEquals("Setting up…", made.copy(status = S.SENDING).statusLine(zone, en))
        assertEquals("Cancelling, sats not back yet", made.copy(status = S.CANCELLING).statusLine(zone, en))
        assertEquals("Cancelled, sats returned", made.copy(status = S.CANCELLED).statusLine(zone, en))
        assertEquals("Used", made.copy(status = S.USED).statusLine(zone, en))
        assertEquals("Payment failed", made.copy(status = S.FAILED).statusLine(zone, en))
        assertEquals("Sending…", made.copy(status = S.SENDING).shortStatus)
        assertEquals("Cancelling…", made.copy(status = S.CANCELLING).shortStatus)
        assertEquals(Sats.format(made.amountMsat), made.copy(status = S.READY).shortStatus)
    }

    /// The date/time used to be a fixed "d MMM 'at' HH:mm" pattern, which hardcoded the English
    /// word "at" into every locale (and every clock convention) regardless of the device's own.
    @Test
    fun `status line is locale-formatted, not a fixed English pattern`() {
        val zone = java.time.ZoneOffset.UTC
        val made = InviteRecord("c", "l", 5_000, null, null, "t", S.READY, 1_700_223_780_000)
        val french = made.statusLine(zone, java.util.Locale.FRENCH)
        assertFalse(french.contains(" at "))
        assertTrue(french.contains("2023"))
    }
}
