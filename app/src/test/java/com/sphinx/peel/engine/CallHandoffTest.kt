package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallHandoffTest {
    private val mine = CallHandoff("seed-a")

    private fun leaves(
        data: ByteArray, room: String = "room-1", sender: String = "tom-2", senderJoinedAt: Long? = null,
        me: String = "tom-1", myJoinedAt: Long? = null,
    ) = mine.shouldLeave(data, room, sender, senderJoinedAt, me, myJoinedAt)

    @Test
    fun `a newer device on the account takes the call`() {
        val phone = CallHandoff("seed-a").announcement("room-1", "tom-2")
        assertTrue(leaves(phone, senderJoinedAt = 2000, myJoinedAt = 1000))
        assertTrue(leaves(phone))
        assertTrue(leaves(phone, senderJoinedAt = 2000))
        assertFalse(leaves(phone, senderJoinedAt = 1000, myJoinedAt = 2000))
    }

    @Test
    fun `a stay announcement never makes an earlier device leave`() {
        val stay = mine.announcement("room-1", "tom-2", stay = true)
        assertFalse(leaves(stay, senderJoinedAt = 2000, myJoinedAt = 1000))
        assertFalse(leaves(stay))
    }

    @Test
    fun `replays, itself and other accounts are refused`() {
        val phone = mine.announcement("room-1", "tom-2")
        assertFalse(leaves(phone, sender = "eve-3"))
        assertFalse(leaves(phone, room = "room-2"))
        assertFalse(leaves(mine.announcement("room-1", "tom-1"), sender = "tom-1"))
        assertFalse(leaves(CallHandoff("seed-b").announcement("room-1", "tom-2")))
        assertFalse(leaves("not a mac".toByteArray()))
        assertFalse(leaves(ByteArray(0)))
    }

    @Test
    fun `two devices joining at once leave only one`() {
        val a = mine.announcement("room-1", "tom-a")
        val b = mine.announcement("room-1", "tom-b")
        val aLeaves = leaves(b, sender = "tom-b", senderJoinedAt = 5, me = "tom-a", myJoinedAt = 5)
        val bLeaves = leaves(a, sender = "tom-a", senderJoinedAt = 5, me = "tom-b", myJoinedAt = 5)
        assertTrue(aLeaves != bLeaves)
    }

    @Test
    fun `the wire format is the iOS one`() {
        assertEquals("6:room-15:tom-2", String(CallHandoff.message("room-1", "tom-2", false)))
        assertEquals("stay:6:room-15:tom-2", String(CallHandoff.message("room-1", "tom-2", true)))
        assertEquals(44, mine.announcementText("room-1", "tom-2").length)
    }

    @Test
    fun `hkdf matches RFC 5869 test case 3 (empty salt)`() {
        val ikm = ByteArray(22) { 0x0b }
        val okm = CallHandoff.hkdf(ikm, ByteArray(0), 42)
        val expected = "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"
        assertEquals(expected, okm.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `another device is a different identity with this side's name, not Jamie`() {
        val ticket = CallTicket("wss://x", "t", "room-1", "tom-1", "Tom", 0, false)
        fun p(id: String, name: String) = CallParticipant(id, name, null)
        assertTrue(CallDevices.otherDevice(listOf(p("tom-2", "Tom")), ticket, null))
        assertFalse(CallDevices.otherDevice(listOf(p("tom-1", "Tom")), ticket, null))
        assertFalse(CallDevices.otherDevice(listOf(p("tom-0", "Tom")), ticket, "tom-0"))
        assertFalse(CallDevices.otherDevice(listOf(p("eve", "Eve")), ticket, null))
        assertFalse(CallDevices.otherDevice(listOf(p("j", "Jamie")), CallTicket("w", "t", "r", "tom-1", "Jamie", 0, false), null))
        assertFalse(CallDevices.otherDevice(null, ticket, null))
    }
}
