package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDetailsTest {
    private fun info(price: Long = 0, escrow: Long = 0, owner: String? = null, members: Long? = null) =
        TribeInfo("pk", "T", null, price, escrow, ownerAlias = owner, memberCount = members)

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
    fun `shows owner members and prices, registry over stored`() {
        val facts = ChatDetails.tribeFacts(info(price = 2_000, escrow = 0, owner = "Ann", members = 12), 9_000, 5_000)
        assertEquals(listOf("Owner", "Members", "Each message"), facts.map { it.label })
        assertEquals("Ann", facts[0].value)
        assertEquals("12", facts[1].value)
    }

    @Test
    fun `stored prices are used while the registry has not answered`() {
        val facts = ChatDetails.tribeFacts(null, 3_000, 1_000)
        assertEquals(listOf("Each message", "Escrow"), facts.map { it.label })
    }
}
