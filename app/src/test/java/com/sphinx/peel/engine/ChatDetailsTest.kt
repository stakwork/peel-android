package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `before the registry answers its facts are dashes`() {
        val facts = ChatDetails.tribeFacts(null, 0, 0)
        assertEquals(listOf("Owner", "To join", "Each message", "Escrow"), facts.map { it.label })
        assertEquals(listOf("\u2014", "\u2014", "3 sats, the minimum", "None"), facts.map { it.value })
        assertEquals("Not given", ChatDetails.tribeFacts(info(), 0, 0)[0].value)
        assertEquals("1 sat, the minimum", ChatDetails.tribeFacts(info(), 0, 0)[1].value)
    }

    @Test
    fun `shows owner, join price and message price, registry over stored`() {
        val facts = ChatDetails.tribeFacts(info(price = 5_000, escrow = 0, owner = "Ann", join = 5_000), 9_000, 5_000)
        assertEquals("Ann", facts[0].value)
        assertEquals("5 sats", facts[1].value)
        assertEquals("5 sats", facts[2].value)
        assertEquals("None", facts[3].value)
    }

    @Test
    fun `message price says why and escrow says for how long`() {
        assertEquals("10 sats, including 4 sats escrow", ChatDetails.messagePrice(6_000, 4_000).described)
        assertEquals("5 sats, all of it escrow", ChatDetails.messagePrice(0, 5_000).described)
        assertEquals("2.5 sats", ChatDetails.Price(2_500, null).text)
        assertEquals("5 sats, back after 2 hours", ChatDetails.escrowLine(5_000, 7_200_000))
        assertEquals("5 sats, back after 1 hour", ChatDetails.escrowLine(5_000, 3_600_000))
        assertEquals("5 sats", ChatDetails.escrowLine(5_000, null))
    }

    @Test
    fun `seen label counts the people seen and this account`() {
        assertEquals("1 seen", ChatDetails.seenLabel(0))
        assertEquals("4 seen", ChatDetails.seenLabel(3))
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
        assertEquals("4 sats, including 1 sat escrow", facts[2].value)
        assertEquals("1 sat", facts[3].value)
    }
}
