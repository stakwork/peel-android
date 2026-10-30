package com.sphinx.peel.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CallServerTest {
    private fun p(identity: String, nickname: String = "") = CallParticipant(identity, nickname, null)

    @Test
    fun `parseUpdate reads current_participants`() {
        val text = """{"type":"current_participants","roomName":"sphinx.call.a",
            "participants":[{"identity":"1","nickname":"Alice"},{"identity":"2","nickname":"Bob"}]}"""
        val update = CallServer.parseUpdate(text)
        assertEquals("sphinx.call.a", update!!.room)
        val change = update.change as CallServer.Change.Current
        assertEquals(listOf(p("1", "Alice"), p("2", "Bob")), change.participants)
    }

    @Test
    fun `parseUpdate reads participant_joined nested or at the top level`() {
        val nested = """{"type":"participant_joined","roomName":"sphinx.call.a","participant":{"identity":"1","nickname":"Alice"}}"""
        val flat = """{"type":"participant_joined","roomName":"sphinx.call.a","identity":"1","nickname":"Alice"}"""
        val a = CallServer.parseUpdate(nested)!!.change as CallServer.Change.Joined
        val b = CallServer.parseUpdate(flat)!!.change as CallServer.Change.Joined
        assertEquals(p("1", "Alice"), a.participant)
        assertEquals(p("1", "Alice"), b.participant)
    }

    @Test
    fun `parseUpdate reads participant_left and room_finished`() {
        val left = CallServer.parseUpdate("""{"type":"participant_left","roomName":"sphinx.call.a","identity":"1"}""")
        assertEquals(CallServer.Change.Left("1"), left!!.change)
        val finished = CallServer.parseUpdate("""{"type":"room_finished","roomName":"sphinx.call.a"}""")
        assertEquals(CallServer.Change.Finished, finished!!.change)
    }

    @Test
    fun `parseUpdate refuses an unknown type, a bad room, or unparseable json`() {
        assertNull(CallServer.parseUpdate("""{"type":"something_else","roomName":"sphinx.call.a"}"""))
        assertNull(CallServer.parseUpdate("""{"type":"room_finished","roomName":"not-a-room"}"""))
        assertNull(CallServer.parseUpdate("not json at all"))
    }

    @Test
    fun `participant with no identity or a control character is refused`() {
        assertNull(CallServer.participant(JSONObject("""{"nickname":"Alice"}""")))
        assertNull(CallServer.participant(JSONObject("""{"identity":"has\u0007control"}""")))
    }

    @Test
    fun `participant nickname is trimmed and control characters are stripped`() {
        val person = CallServer.participant(JSONObject("""{"identity":"1","nickname":"  Al\u0007ice  "}"""))
        assertEquals("Alice", person!!.nickname)
    }

    @Test
    fun `participant avatarUrl is kept only for a real public https host`() {
        val https = CallServer.participant(JSONObject("""{"identity":"1","avatarUrl":"https://example.com/a.png"}"""))
        assertEquals("https://example.com/a.png", https!!.avatarUrl)
        val http = CallServer.participant(JSONObject("""{"identity":"1","avatarUrl":"http://example.com/a.png"}"""))
        assertNull(http!!.avatarUrl)
        val loopback = CallServer.participant(JSONObject("""{"identity":"1","avatarUrl":"https://127.0.0.1/a.png"}"""))
        assertNull(loopback!!.avatarUrl)
        val privateNet = CallServer.participant(JSONObject("""{"identity":"1","avatarUrl":"https://192.168.1.5/a.png"}"""))
        assertNull(privateNet!!.avatarUrl)
    }

    @Test
    fun `applying current_participants keeps a known name for someone who rejoined unnamed`() {
        val before = CallPresence.Live(listOf(p("1", "Alice")))
        val update = CallServer.Change.Current(listOf(p("1", ""), p("2", "Bob")))
        val after = CallServer.applying(update, before) as CallPresence.Live
        assertEquals(listOf(p("1", "Alice"), p("2", "Bob")), after.people)
    }

    @Test
    fun `applying a nameless join changes nothing, a named join replaces the same identity`() {
        val before = CallPresence.Live(listOf(p("1", "Alice")))
        val nameless = CallServer.applying(CallServer.Change.Joined(p("2", "")), before)
        assertEquals(before, nameless)
        val renamed = CallServer.applying(CallServer.Change.Joined(p("1", "Alicia")), before) as CallPresence.Live
        assertEquals(listOf(p("1", "Alicia")), renamed.people)
    }

    @Test
    fun `applying a join to an ended room reopens it with just that person`() {
        val reopened = CallServer.applying(CallServer.Change.Joined(p("1", "Alice")), CallPresence.Ended) as CallPresence.Live
        assertEquals(listOf(p("1", "Alice")), reopened.people)
    }

    @Test
    fun `applying a leave removes only that identity`() {
        val before = CallPresence.Live(listOf(p("1", "Alice"), p("2", "Bob")))
        val after = CallServer.applying(CallServer.Change.Left("1"), before) as CallPresence.Live
        assertEquals(listOf(p("2", "Bob")), after.people)
    }

    @Test
    fun `applying finished ends the room, and a join or leave before any list is a no-op`() {
        assertEquals(CallPresence.Ended, CallServer.applying(CallServer.Change.Finished, CallPresence.Live(listOf(p("1", "Alice")))))
        assertEquals(CallPresence.Unknown, CallServer.applying(CallServer.Change.Joined(p("1", "Alice")), CallPresence.Unknown))
        assertEquals(CallPresence.Unknown, CallServer.applying(CallServer.Change.Left("1"), CallPresence.Unknown))
    }
}
