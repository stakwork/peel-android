package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDetailsTest {
    private fun info(price: Long = 0, escrow: Long = 0, owner: String? = null, members: Long? = null, join: Long = 0) =
        TribeInfo("pk", "T", null, price, escrow, ownerAlias = owner, memberCount = members, priceToJoinMsat = join)

    @Test
    fun `link is in the official format and needs a host`() {
        assertEquals("sphinx.chat://?action=tribeV2&pubkey=pk&host=tribes.example", ChatDetails.tribeLink("pk", "tribes.example"))
        assertNull(ChatDetails.tribeLink("pk", null))
        assertNull(ChatDetails.tribeLink("pk", ""))
    }

    @Test
    fun `empty facts are hidden`() {
        assertTrue(ChatDetails.tribeFacts(null, 0, 0).isEmpty())
        assertTrue(ChatDetails.tribeFacts(info(), 0, 0).isEmpty())
    }

    @Test
    fun `shows owner, join price and message price, registry over stored`() {
        val facts = ChatDetails.tribeFacts(info(price = 2_000, escrow = 0, owner = "Ann", members = 12, join = 5_000), 9_000, 5_000)
        assertEquals(listOf("Owner", "To join", "Each message"), facts.map { it.label })
        assertEquals("Ann", facts[0].value)
        assertEquals("5 sats", facts[1].value)
    }

    @Test
    fun `seen here is the member count`() {
        assertEquals("12 members", ChatDetails.seenHere(info(members = 12)))
        assertEquals("1 member", ChatDetails.seenHere(info(members = 1)))
        assertNull(ChatDetails.seenHere(info()))
        assertNull(ChatDetails.seenHere(null))
    }

    @Test
    fun `hero subtitle names the kind and the creation date`() {
        val zone = java.time.ZoneId.of("UTC")
        val withBoth = TribeInfo("pk", "T", null, 0, 0, isPrivate = true, created = "2021-03-10T14:21:08.123Z")
        assertEquals("Private tribe · Created Mar 10, 2021", ChatDetails.heroSubtitle(withBoth, java.util.Locale.US, zone))
        assertEquals("Public tribe", ChatDetails.heroSubtitle(TribeInfo("pk", "T", null, 0, 0, isPrivate = false), java.util.Locale.US, zone))
        assertEquals("Created Mar 10, 2021", ChatDetails.heroSubtitle(TribeInfo("pk", "T", null, 0, 0, created = "2021-03-10T00:00:00Z"), java.util.Locale.US, zone))
        assertNull(ChatDetails.heroSubtitle(TribeInfo("pk", "T", null, 0, 0, created = "garbage"), java.util.Locale.US, zone))
        assertNull(ChatDetails.heroSubtitle(null))
    }

    @Test
    fun `stored prices are used while the registry has not answered`() {
        val facts = ChatDetails.tribeFacts(null, 3_000, 1_000)
        assertEquals(listOf("Each message", "Escrow"), facts.map { it.label })
    }
}
