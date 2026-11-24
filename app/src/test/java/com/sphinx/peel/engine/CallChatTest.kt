package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CallChatTest {
    @Test
    fun `a line round-trips in LiveKit's chat shape`() {
        val bytes = CallChat.encode("abc", 1730000000000L, "hello there")
        val json = org.json.JSONObject(String(bytes))
        assertEquals("abc", json.getString("id"))
        assertEquals(1730000000000L, json.getLong("timestamp"))
        assertEquals("hello there", json.getString("message"))
        assertEquals(CallChatLine("abc", 1730000000000L, "hello there"), CallChat.decode(bytes))
    }

    @Test
    fun `a web client's line is read`() {
        val web = """{"id":"x1","timestamp":1730000000123,"message":"hi from meet"}""".toByteArray()
        assertEquals(CallChatLine("x1", 1730000000123L, "hi from meet"), CallChat.decode(web))
    }

    @Test
    fun `a payload's sender is read, trimmed`() {
        val relayed = """{"id":"v1","timestamp":1,"message":"@jamie what's next","sender":"  Ada ","voice":true}""".toByteArray()
        assertEquals(CallChatLine("v1", 1, "@jamie what's next", "Ada"), CallChat.decode(relayed))
        assertNull(CallChat.decode("""{"id":"v2","message":"hi","sender":"  "}""".toByteArray())!!.sender)
        assertNull(CallChat.decode("""{"id":"v3","message":"hi","sender":7}""".toByteArray())!!.sender)
    }

    @Test
    fun `control packets and junk are not chat`() {
        assertNull(CallChat.decode("""{"type":"tts-toggle"}""".toByteArray()))
        assertNull(CallChat.decode("not json".toByteArray()))
        assertNull(CallChat.decode(ByteArray(0)))
        assertNull(CallChat.decode("""{"message":"   "}""".toByteArray()))
        assertNull(CallChat.decode(ByteArray(20_000) { 'a'.code.toByte() }))
    }

    @Test
    fun `typed text is trimmed and capped`() {
        assertNull(CallChat.outgoing("   "))
        assertEquals("hi", CallChat.outgoing("  hi \n"))
        assertEquals(CallChat.MAX_LENGTH, CallChat.outgoing("a".repeat(5000))!!.length)
    }

    @Test
    fun `a line seen twice is kept once and the list is capped`() {
        val one = CallChatMessage("1", "a", "A", "x", 1, false)
        val list = CallChat.append(emptyList(), one)
        assertSame(list, CallChat.append(list, one.copy(text = "again")))
        // The same id from someone else is a different line.
        assertEquals(2, CallChat.append(list, one.copy(identity = "b")).size)
        var many = emptyList<CallChatMessage>()
        repeat(CallChat.MAX_KEPT + 10) { many = CallChat.append(many, CallChatMessage("$it", "a", "A", "x", 1, false)) }
        assertEquals(CallChat.MAX_KEPT, many.size)
        assertEquals("10", many.first().id)
    }

    // The room in the sender tests: a box's Jamie (rtc-node), a person on meet, a person on Peel, a person named Jamie.
    private val room = listOf(
        CallRoomMember("agent-jamie", "Jamie", null, mapOf("tts" to "off", "recording" to "true")),
        CallRoomMember("meet-ada", "Ada", """{"profilePictureUrl":"https://example.com/ada.png"}"""),
        CallRoomMember("box_0123456789abcdef0123456789abcdef", "Bob", null),
        CallRoomMember("meet-jamie", "Jamie", null),
    ).associateBy { it.identity }

    @Test
    fun `a line the SDK matched to nobody is named by the identity the server stamped`() {
        // Jamie's lines (rtc-node) and meet's (livekit-client) carry no participant SID, so the SDK matches no one.
        assertEquals(CallParticipant("agent-jamie", "Jamie", null), CallChat.sender("agent-jamie", null, inRoom = room::get))
        val ada = CallChat.sender("meet-ada", null, inRoom = room::get)!!
        assertEquals("Ada", ada.nickname)
        assertEquals(false, ada.isJamie)
    }

    @Test
    fun `the stamped identity wins over a SID the sender wrote`() {
        // On an encrypted call the server can't check the inner packet's SID: Bob's packet claiming to be Jamie's is Bob's.
        val sender = CallChat.sender("box_0123456789abcdef0123456789abcdef", room["agent-jamie"], inRoom = room::get)!!
        assertEquals("Bob", sender.nickname)
        assertEquals("box_0123456789abcdef0123456789abcdef", sender.identity)
    }

    @Test
    fun `without a stamp only the SDK's own match names a line`() {
        assertEquals("Ada", CallChat.sender("", room["meet-ada"]) { error("not asked") }?.nickname)
        // Nobody known: dropped, never taken as Jamie's.
        assertNull(CallChat.sender("", null) { error("not asked") })
        assertNull(CallChat.sender("", null, relayed = "Ada") { error("not asked") })
    }

    @Test
    fun `a stamped sender not in the room keeps the line, unnamed`() {
        assertEquals(CallParticipant("gone", CallChat.UNNAMED, null), CallChat.sender("gone", null, inRoom = room::get))
        assertEquals(CallChat.UNNAMED, CallChat.sender("x", null) { CallRoomMember("x", "  ", null) }?.nickname)
        // An identity with a control character is refused, as everywhere else.
        assertNull(CallChat.sender("bad\u0000id", null, inRoom = room::get))
    }

    @Test
    fun `a line Jamie relays is the speaker's, and only Jamie relays`() {
        // Jamie's echo of what Ada asked aloud: Ada's line, still sent by Jamie.
        assertEquals(CallParticipant("agent-jamie", "Ada", null), CallChat.sender("agent-jamie", null, "Ada", room::get))
        assertEquals("Ada", CallChat.sender("", room["agent-jamie"], "Ada") { error("not asked") }?.nickname)
        // Jamie's own lines carry no sender; a blank one is Jamie's too.
        assertEquals("Jamie", CallChat.sender("agent-jamie", null, null, room::get)?.nickname)
        assertEquals("Jamie", CallChat.sender("agent-jamie", null, "\u0007", room::get)?.nickname)
        // Anyone else's `sender` is ignored: Peel on iOS sends its own name, and a person can write any.
        assertEquals("Bob", CallChat.sender("box_0123456789abcdef0123456789abcdef", null, "Jamie", room::get)?.nickname)
        assertEquals("Ada", CallChat.sender("meet-ada", null, "Bob", room::get)?.nickname)
        // A person named Jamie is not the call's Jamie (no `tts` attribute), nor is a stamped sender not in the room.
        assertEquals("Jamie", CallChat.sender("meet-jamie", null, "Ada", room::get)?.nickname)
        assertEquals(CallChat.UNNAMED, CallChat.sender("gone", null, "Ada", room::get)?.nickname)
    }

    @Test
    fun `the call's Jamie is named Jamie and has the tts attribute`() {
        assertTrue(CallRoomMember("a", " jamie ", null, mapOf("tts" to "on")).isJamie)
        assertEquals(false, CallRoomMember("a", "Jamie", null).isJamie)
        assertEquals(false, CallRoomMember("a", null, null, mapOf("tts" to "on")).isJamie)
        assertEquals(false, CallRoomMember("a", "Jamie B", null, mapOf("tts" to "on")).isJamie)
    }

    @Test
    fun `joining a different room while on a call asks first`() {
        assertEquals(CallSwitch.Action.JOIN, CallSwitch.action(null, "r1"))
        assertEquals(CallSwitch.Action.SHOW_CURRENT, CallSwitch.action("r1", "r1"))
        assertEquals(CallSwitch.Action.CONFIRM, CallSwitch.action("r1", "r2"))
        assertTrue(CallSwitch.Action.values().size == 3)
    }
}
