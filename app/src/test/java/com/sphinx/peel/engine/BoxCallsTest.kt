package com.sphinx.peel.engine

import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// A box's Jamie in a group call ([BoxCallsClient], [BoxCalls]; docs/phases/56-box-group-calls.md, stadeum plans/group-calls.md §5
/// and §7, call-canvas.md §4): which boxes the call menu offers, the box asked in before anything is posted, its refusals in words,
/// and the person's own ticket from the box in a call. Against stand-in struts ([VoiceStrut]) answering as strut#881 forwards the
/// voice service's routes.
@RunWith(RobolectricTestRunner::class)
class BoxCallsTest {
    private val callServer = "chat.sphinx.chat"
    private val a = BoxFixture.box("https://strut.alpha.example.com")
    private val b = BoxFixture.box("https://strut.bee.example.com")
    private val key = TalkHome.newKey()
    private val link = CallLink.new(callServer, audioOnly = false, encryptionKey = key)

    // What a box says

    @Test fun `a status is read, and only a group-ready box on the call server's LiveKit is offered`() {
        assertEquals(BoxCallStatus(true, "wss://livekit.sphinx.chat"), BoxCallStatus.parse("""{"group":true,"livekit":"wss://livekit.sphinx.chat"}"""))
        assertNull(BoxCallStatus.parse("""{"group":"yes","livekit":"wss://livekit.sphinx.chat"}"""))
        assertNull(BoxCallStatus.parse("""{"group":true}"""))
        assertNull(BoxCallStatus.parse("not json"))

        assertTrue(BoxCalls.offers(BoxCallStatus(true, "wss://livekit.sphinx.chat"), callServer))
        // The recognizer still loading.
        assertFalse(BoxCalls.offers(BoxCallStatus(false, "wss://livekit.sphinx.chat"), callServer))
        // A box on its own LiveKit: the call's other people would never meet its Jamie.
        assertFalse(BoxCalls.offers(BoxCallStatus(true, "wss://livekit.box.example.com"), callServer))
        assertFalse(BoxCalls.offers(null, callServer))
        assertFalse(BoxCalls.offers(BoxCallStatus.NONE, callServer))
    }

    @Test fun `the menu offers the boxes that can join now, in link order, and keeps a pick only while it is offered`() = runBlocking {
        val strutA = VoiceStrut(a)
        val strutB = VoiceStrut(b, livekit = "wss://livekit.bee.example.com")
        val calls = boxCalls(a to strutA, b to strutB)
        assertTrue(calls.offered(callServer).isEmpty())

        calls.read()
        assertEquals(listOf(a.url), calls.offered(callServer).map { it.url })
        assertEquals(listOf("GET https://strut.alpha.example.com/voice/calls ${a.token}"), strutA.seen)

        calls.choose("chat1", calls.offered(callServer).first())
        calls.record("chat1", true)
        assertEquals(a.url, calls.pick("chat1", callServer)?.url)
        assertTrue(calls.records("chat1"))
        assertNull(calls.pick("chat2", callServer))

        // Read again within the minute: not asked.
        calls.read(ifOlderThanMs = 60_000)
        assertEquals(1, strutA.seen.size)

        // Not ready any more: no longer offered, and the pick falls back to no Jamie.
        strutA.group = false
        calls.read()
        assertTrue(calls.offered(callServer).isEmpty())
        assertNull(calls.pick("chat1", callServer))

        // No Jamie, or another box, starts again without recording.
        strutA.group = true
        calls.read()
        calls.choose("chat1", null)
        assertFalse(calls.records("chat1"))
        assertNull(calls.pick("chat1", callServer))
    }

    @Test fun `a box records on the canvas unless it says it can't`() = runBlocking {
        assertEquals(true, BoxCallStatus.parse("""{"group":true,"livekit":"wss://l","record":true}""")?.record)
        assertEquals(false, BoxCallStatus.parse("""{"group":true,"livekit":"wss://l","record":false}""")?.record)
        // A voice service from before `record` (or one that says something else): as before, it can.
        assertNull(BoxCallStatus.parse("""{"group":true,"livekit":"wss://l"}""")?.record)
        assertNull(BoxCallStatus.parse("""{"group":true,"livekit":"wss://l","record":"yes"}""")?.record)
        assertTrue(BoxCallStatus(true, "wss://l").records)
        assertTrue(BoxCallStatus(true, "wss://l", record = true).records)
        assertFalse(BoxCallStatus(true, "wss://l", record = false).records)

        val strut = VoiceStrut(a, record = false)
        val calls = boxCalls(a to strut)
        calls.read()
        calls.choose("chat1", a)
        calls.record("chat1", true)
        // Still offered for Jamie, but not for recording: the choice left from before is not sent.
        assertEquals(a.url, calls.pick("chat1", callServer)?.url)
        assertFalse(calls.recordable(a))
        assertFalse(calls.records("chat1"))

        // The box's packages now say what a call records onto.
        strut.record = true
        calls.read()
        assertTrue(calls.recordable(a))
        assertTrue(calls.records("chat1"))
        strut.record = null
        calls.read()
        assertTrue(calls.records("chat1"))
        // A box not asked yet is not offered, and records nothing.
        assertFalse(calls.recordable(b))
    }

    @Test fun `a box from before the routes, or one with no voice service, is not offered, nor one that fails`() = runBlocking {
        val old = VoiceStrut(a).apply { status = 404 }
        val none = VoiceStrut(b).apply { status = 503 }
        val calls = boxCalls(a to old, b to none)
        calls.read()
        assertTrue(calls.offered(callServer).isEmpty())

        old.status = 500
        calls.read()
        assertTrue(calls.offered(callServer).isEmpty())
    }

    // Bringing Jamie in

    @Test fun `the box is asked in with the room, the key and recording, as the person`() = runBlocking {
        val strut = VoiceStrut(a)
        val calls = boxCalls(a to strut)
        assertEquals("agent-7", calls.bring(a, link, record = true))
        val asked = JSONObject(strut.joins.single())
        assertEquals("group", asked.getString("mode"))
        assertEquals(link.room, asked.getString("room"))
        assertEquals(key, asked.getString("key"))
        assertTrue(asked.getBoolean("record"))
        // Not recording: no `record` at all.
        calls.bring(a, CallLink.new(callServer, audioOnly = true, encryptionKey = key), record = false)
        assertFalse(JSONObject(strut.joins.last()).has("record"))
    }

    @Test fun `a box is never asked into a call in the clear`() = runBlocking {
        val strut = VoiceStrut(a)
        val calls = boxCalls(a to strut)
        val clear = CallLink.new(callServer, audioOnly = false)
        val e = assertThrows(BoxException::class.java) { runBlocking { calls.bring(a, clear, record = false) } }
        assertEquals("Peel brings Jamie from alpha only into an encrypted call.", e.message)
        assertTrue(strut.joins.isEmpty())
    }

    @Test fun `the box's refusals are said in words, and nothing else is taken for a join`() {
        fun refusal(status: Int, body: String): String? {
            val strut = VoiceStrut(a).apply { join = status to body }
            return runCatching { runBlocking { boxCalls(a to strut).bring(a, link, record = false) } }.exceptionOrNull()?.message
        }
        assertEquals("Jamie from alpha is already in this call.", refusal(409, """{"error":"Jamie from this box is already in that call"}"""))
        assertEquals(
            "Jamie from alpha can't join a call right now: group calls are not ready yet: the speech recognizer is loading.",
            refusal(503, """{"error":"group calls are not ready yet: the speech recognizer is loading"}"""),
        )
        assertEquals("Jamie from alpha can't join a call right now.", refusal(503, ""))
        assertEquals(
            "alpha could not bring Jamie into the call: key must be 32 bytes, as base64.",
            refusal(400, """{"error":"key must be 32 bytes, as base64"}"""),
        )
        assertEquals("alpha could not bring Jamie into the call: HTTP 403.", refusal(403, ""))
        // A voice service that did not take the key: never a call in the clear.
        assertEquals(
            "alpha can't join an encrypted call yet: its voice service needs an update.",
            refusal(200, """{"agent":"agent-7"}"""),
        )
        assertEquals("alpha's answer about the call could not be read.", refusal(200, """{"encrypted":true}"""))
    }

    @Test fun `a token the box refused is renewed once and the join asked again`() = runBlocking {
        val strut = VoiceStrut(a, token = "strut-person.old")
        val calls = boxCalls(a to strut)
        assertEquals("agent-7", calls.bring(a, link, record = false))
        assertEquals(1, strut.joins.size)
        assertEquals("strut-person.renewed", strut.token)
    }

    // The person's own ticket

    @Test fun `the box in the call gives the person their own ticket, the others say they are not in it`() = runBlocking {
        val strutA = VoiceStrut(a)
        val strutB = VoiceStrut(b).apply { inRoom = link.room }
        val calls = boxCalls(a to strutA, b to strutB)
        val ticket = calls.ticket(link, "Ada")
        assertNotNull(ticket)
        assertEquals(b.url, ticket!!.box)
        assertEquals(VoiceStrut.IDENTITY, ticket.identity)
        assertEquals(link.room, ticket.room)
        assertEquals("""{"room":"${link.room}","name":"Ada"}""", strutB.tickets.single())
        assertEquals(1, strutA.tickets.size)
        // Never described with its token.
        assertFalse(ticket.toString().contains(ticket.token))

        // As a ticket into the call: on the call server's LiveKit, for the identity the box named.
        val joined = ticket.callTicket(callServer, "Ada")
        assertEquals("wss://livekit.sphinx.chat", joined.serverUrl)
        assertEquals(VoiceStrut.IDENTITY, joined.identity)
        assertEquals(link.room, joined.room)
    }

    @Test fun `no box in the call, a box that fails, or a call in the clear gives no ticket, and a box never hears of a clear call`() = runBlocking {
        val strutA = VoiceStrut(a)
        val strutB = VoiceStrut(b).apply { inRoom = link.room; ticketStatus = 500 }
        val calls = boxCalls(a to strutA, b to strutB)
        assertNull(calls.ticket(link, "Ada"))

        val clear = CallLink.new(callServer, audioOnly = false)
        assertNull(calls.ticket(clear, "Ada"))
        assertEquals(1, strutA.tickets.size)
    }

    @Test fun `a ticket that is not the box's, for another room or on another LiveKit is not taken`() {
        val room = link.room
        val token = VoiceStrut.jwt(room)
        val good = """{"url":"wss://livekit.sphinx.chat","room":"$room","token":"$token","identity":"${VoiceStrut.IDENTITY}"}"""
        assertNotNull(BoxTicket.parse(good, room, a.url))
        assertNull(BoxTicket.parse(good.replace(VoiceStrut.IDENTITY, "ada__x1y2"), room, a.url))
        assertNull(BoxTicket.parse(good, "sphinx.call.other", a.url))
        assertNull(BoxTicket.parse("""{"url":"wss://livekit.sphinx.chat","room":"$room","identity":"${VoiceStrut.IDENTITY}"}""", room, a.url))

        val elsewhere = BoxTicket.parse(good.replace("wss://livekit.sphinx.chat", "wss://livekit.box.example.com"), room, a.url)!!
        assertThrows(CallServer.TicketException.MediaHostNotAllowed::class.java) { elsewhere.callTicket(callServer, "Ada") }
        // A token minted for someone else than the identity named.
        val other = BoxTicket.parse(good.replace(token, VoiceStrut.jwt(room, "box_" + "cd".repeat(16))), room, a.url)!!
        assertThrows(CallServer.TicketException.MalformedToken::class.java) { other.callTicket(callServer, "Ada") }
    }

    @Test fun `a ticket's name is one line the voice service takes`() {
        assertEquals("Ada Lovelace", BoxCalls.ticketName("  Ada\nLovelace "))
        assertEquals("Peel user", BoxCalls.ticketName("   "))
        assertTrue(BoxCalls.ticketName("😀".repeat(200)).length <= 128)
    }
}

/// A box's strut as Peel's group calls meet it (strut#881 forwarding sphinx-voice's `/calls`): `GET /voice/calls`,
/// `POST /voice/calls { mode: "group" }` and `POST /voice/calls/ticket`, each with the person's token, which it may refuse once
/// (`401`) until renewed through the link routes.
class VoiceStrut(
    private val box: BoxLink,
    var token: String = box.token,
    var group: Boolean = true,
    private val livekit: String = "wss://livekit.sphinx.chat",
    /// `GET /voice/calls`'s `record` (plans/call-canvas.md §8); null leaves it out, as a voice service before it does.
    var record: Boolean? = null,
) {
    /// `GET /voice/calls`'s status, when not 200.
    var status: Int = 200
    /// `POST /voice/calls`'s answer, when set; else Jamie joins.
    var join: Pair<Int, String>? = null
    /// The room this box's Jamie is in: a ticket for any other is a 404.
    var inRoom: String? = null
    var ticketStatus: Int = 200
    /// Each status read, as "<method> <url> <bearer>"; each join's and ticket's body.
    val seen = mutableListOf<String>()
    val joins = mutableListOf<String>()
    val tickets = mutableListOf<String>()

    val http = FakeBox { method, url, json, bearer ->
        val path = url.removePrefix(box.url)
        when {
            path == "/people/challenge" ->
                BoxResponse(200, """{"challenge":"${BoxFixture.challenge(box.host, actor = null, name = null, exp = BoxFixture.LATER)}","exp":1}""")
            path == "/people/token" -> {
                token = "strut-person.renewed"
                BoxResponse(200, BoxFixture.answer(token = token, exp = BoxFixture.LATER))
            }
            bearer != token -> BoxResponse(401, """{"error":"unauthorized"}""")
            path == "/voice/calls" && method == "GET" -> {
                seen += "$method $url $bearer"
                if (status != 200) BoxResponse(status, """{"error":"no"}""")
                else BoxResponse(200, JSONObject().put("group", group).put("livekit", livekit).also { r -> record?.let { r.put("record", it) } }.toString())
            }
            path == "/voice/calls" && method == "POST" -> {
                joins += json!!
                inRoom = JSONObject(json).getString("room")
                join?.let { (status, body) -> BoxResponse(status, body) } ?: BoxResponse(200, """{"agent":"agent-7","encrypted":true}""")
            }
            path == "/voice/calls/ticket" && method == "POST" -> {
                tickets += json!!
                val room = JSONObject(json).getString("room")
                when {
                    ticketStatus != 200 -> BoxResponse(ticketStatus, """{"error":"down"}""")
                    room != inRoom -> BoxResponse(404, """{"error":"Jamie from this box is not in that call"}""")
                    else -> BoxResponse(
                        200,
                        JSONObject().put("url", livekit).put("room", room).put("token", jwt(room)).put("identity", IDENTITY).toString(),
                    )
                }
            }
            else -> BoxResponse(404, """{"error":"not found"}""")
        }
    }

    companion object {
        val IDENTITY = "box_" + "0123456789abcdef".repeat(2)

        /// A LiveKit token for [room] and [sub], an hour on: what the voice service mints (the signature is LiveKit's to check).
        fun jwt(room: String, sub: String = IDENTITY): String {
            fun enc(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))
            val payload = JSONObject().put("sub", sub).put("exp", System.currentTimeMillis() / 1000 + 3600)
                .put("video", JSONObject().put("room", room).put("roomJoin", true))
            return "${enc("""{"alg":"HS256"}""")}.${enc(payload.toString())}.sig"
        }
    }
}

/// [BoxCalls] over the linked [boxes], each answered by its [VoiceStrut].
fun boxCalls(vararg boxes: Pair<BoxLink, VoiceStrut>): BoxCalls {
    val by = boxes.associate { (box, strut) -> box.url to strut }
    val model = BoxesModel(
        clientFor = { url -> BoxClient(url, by.getValue(url).http) },
        dispatcher = Dispatchers.Unconfined,
        now = { BoxFixture.now },
        sleep = { awaitCancellation() },
    ).apply { sign = { VisitorSignature("02ab", "1f") } }
    val store = MemoryBoxStore()
    BoxLink.write(boxes.map { it.first }, store)
    model.open(store)
    return BoxCalls(
        model,
        clientFor = { box -> BoxCallsClient(box, by.getValue(box.url).http) },
        dispatcher = Dispatchers.Unconfined,
        nowMs = { BoxFixture.NOW_MS },
    )
}
