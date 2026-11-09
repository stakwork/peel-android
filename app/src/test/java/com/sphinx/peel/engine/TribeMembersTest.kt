package com.sphinx.peel.engine

import com.sphinx.peel.storage.MessageRow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TribeMembersTest {
    private fun row(uuid: String, type: Int, body: String? = null, fromMe: Boolean = false, replyUuid: String? = null, ts: Long = 1, sender: String? = null) =
        MessageRow(
            uuid = uuid, serverIndex = null, type = type, chat = "tribe", fromMe = fromMe, sender = sender, body = body,
            msat = null, timestamp = ts, tag = null, paymentHash = null, replyUuid = replyUuid,
        )

    @Test
    fun `members are read from an object or an array`() {
        val object_ = TribeMembers.parse("""{"members":[{"pubkey":"a","alias":"Ann","photo_url":"https://p"}],"pending":[{"pubkey":"b","nickname":"Bo"}]}""")
        assertEquals(listOf(TribeMember("a", "Ann", "https://p"), TribeMember("b", "Bo", null, pending = true)), object_)
        val array = TribeMembers.parse("""[{"pubkey":"a"},{"pubkey":"a"},{"alias":"nobody"},{"pubkey":"c","pending":true}]""")
        assertEquals(listOf(TribeMember("a", null, null), TribeMember("c", null, null, pending = true)), array)
    }

    @Test
    fun `nothing readable is no members`() {
        assertTrue(TribeMembers.parse(null).isEmpty())
        assertTrue(TribeMembers.parse("  ").isEmpty())
        assertTrue(TribeMembers.parse("not json").isEmpty())
        assertTrue(TribeMembers.parse("""{"other":1}""").isEmpty())
    }

    @Test
    fun `a request nothing has answered is pending, oldest first`() {
        val sender = JSONObject().put("alias", "Cy").put("photo_url", "https://c").toString()
        val rows = listOf(
            row("r2", MEMBER_REQUEST_MESSAGE_TYPE, JSONObject().put("pubkey", "k2").toString(), ts = 20, sender = sender),
            row("r1", MEMBER_REQUEST_MESSAGE_TYPE, JSONObject().put("pubkey", "k1").toString(), ts = 10),
            row("r3", MEMBER_REQUEST_MESSAGE_TYPE, JSONObject().put("pubkey", "k3").toString(), ts = 30),
            row("a3", MEMBER_APPROVE_MESSAGE_TYPE, replyUuid = "r3", ts = 40),
            row("r4", MEMBER_REQUEST_MESSAGE_TYPE, JSONObject().put("pubkey", "k4").toString(), ts = 50),
            row("x4", MEMBER_REJECT_MESSAGE_TYPE, replyUuid = "r4", ts = 60, fromMe = true),
            row("mine", MEMBER_REQUEST_MESSAGE_TYPE, fromMe = true),
            row("text", 0, "hi"),
        )
        val pending = TribeMembers.pendingRequests(rows)
        assertEquals(listOf("k1", "k2"), pending.map { it.pubkey })
        assertEquals(listOf("r1", "r2"), pending.map { it.requestUuid })
        assertEquals("Cy", pending[1].alias)
        assertEquals("https://c", pending[1].photoUrl)
        assertTrue(pending.all { it.pending })
    }

    @Test
    fun `listed pending members keep their request, others are added`() {
        val listed = listOf(TribeMember("a", "Ann", null), TribeMember("b", "Bo", null, pending = true))
        val requests = listOf(TribeMember("b", null, null, pending = true, requestUuid = "rb"), TribeMember("c", "Cy", null, pending = true, requestUuid = "rc"))
        val merged = TribeMembers.merge(listed, requests)
        assertEquals(listOf("a", "b", "c"), merged.map { it.pubkey })
        assertEquals("rb", merged[1].requestUuid)
        assertEquals("Bo", merged[1].alias)
        assertEquals("rc", merged[2].requestUuid)
    }

    @Test
    fun `message bodies name who they are about`() {
        val answer = JSONObject(TribeMembers.answerBody(TribeMember("k", null, null, pending = true, requestUuid = "r")))
        assertEquals("", answer.getString("content"))
        assertEquals("r", answer.getString("replyUuid"))
        assertEquals("k", answer.getString("pubkey"))
        assertFalse(JSONObject(TribeMembers.answerBody(TribeMember("k", null, null))).has("replyUuid"))
        val kick = JSONObject(TribeMembers.kickBody("m"))
        assertEquals("m", kick.getString("pubkey"))
        assertEquals("", JSONObject(TribeMembers.leaveBody()).getString("content"))
    }

    @Test
    fun `the message types are the relay's`() {
        assertEquals(listOf(14, 15, 16, 19, 20, 21), listOf(GROUP_JOIN_MESSAGE_TYPE, GROUP_LEAVE_MESSAGE_TYPE, GROUP_KICK_MESSAGE_TYPE, MEMBER_REQUEST_MESSAGE_TYPE, MEMBER_APPROVE_MESSAGE_TYPE, MEMBER_REJECT_MESSAGE_TYPE))
    }

    @Test
    fun `ownership is the registry's owner or a tribe created here`() {
        assertEquals(setOf("a", "b"), OwnedTribes.decode(" a, b,,"))
        assertTrue(OwnedTribes.decode(null).isEmpty())
        assertEquals("a,b", OwnedTribes.encode(setOf("b", "a")))
        assertTrue(OwnedTribes.owns("me", "t", "me", emptySet()))
        assertTrue(OwnedTribes.owns("me", "t", null, setOf("t")))
        assertFalse(OwnedTribes.owns("me", "t", "other", setOf("x")))
        assertFalse(OwnedTribes.owns("me", "t", null, emptySet()))
    }
}
