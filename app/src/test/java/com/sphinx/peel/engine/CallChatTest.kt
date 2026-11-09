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
        assertEquals(Triple("abc", 1730000000000L, "hello there"), CallChat.decode(bytes))
    }

    @Test
    fun `a web client's line is read`() {
        val web = """{"id":"x1","timestamp":1730000000123,"message":"hi from meet"}""".toByteArray()
        assertEquals(Triple("x1", 1730000000123L, "hi from meet"), CallChat.decode(web))
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

    @Test
    fun `joining a different room while on a call asks first`() {
        assertEquals(CallSwitch.Action.JOIN, CallSwitch.action(null, "r1"))
        assertEquals(CallSwitch.Action.SHOW_CURRENT, CallSwitch.action("r1", "r1"))
        assertEquals(CallSwitch.Action.CONFIRM, CallSwitch.action("r1", "r2"))
        assertTrue(CallSwitch.Action.values().size == 3)
    }
}
