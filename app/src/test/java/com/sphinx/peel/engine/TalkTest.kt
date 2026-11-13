package com.sphinx.peel.engine

import android.content.Context
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/// Records the call and answers with a canned reply.
private class FakeTalkHttp(private val reply: HiveResponse? = null, private val failure: IOException? = null) : TalkHttp {
    var url: String? = null
    var json: String? = null
    var bearer: String? = null
    var timeout: Long? = null
    var limit: Int? = null
    override suspend fun post(url: String, json: String, bearer: String, timeoutSeconds: Long, limit: Int, what: String): HiveResponse {
        this.url = url; this.json = json; this.bearer = bearer; this.timeout = timeoutSeconds; this.limit = limit
        failure?.let { throw it }
        return reply!!
    }
}

@RunWith(RobolectricTestRunner::class)
class TalkTest {
    private val home = TalkHome("w1", "Acme", "https://acme.example.com", "person-token")

    private fun jwt(room: String? = "room-1"): String {
        fun enc(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))
        val payload = JSONObject().apply {
            put("sub", "me-1")
            if (room != null) put("video", JSONObject().put("room", room))
        }.toString()
        return "${enc("{\"alg\":\"none\"}")}.${enc(payload)}.sig"
    }

    private fun reply(
        url: String = "wss://lk.example.com", room: String = "room-1", token: String = jwt(room), identity: String = "me-1",
        agent: String = "agent-1", encrypted: Boolean? = true, voice: String? = "af_heart",
    ): String = JSONObject().apply {
        put("url", url); put("room", room); put("token", token); put("identity", identity); put("agent", agent)
        if (encrypted != null) put("encrypted", encrypted)
        if (voice != null) put("voice", voice)
    }.toString()

    private fun ticket(body: String) = TalkTicket.parse(body, "Acme", "key")

    private fun refusal(body: String, home: String = "Acme"): HiveException =
        assertThrows(HiveException::class.java) { TalkTicket.parse(body, home, "key") }

    // MARK: - The home

    @Test
    fun `a paired org's home is its strut and the person's token`() {
        val peer = HivePeer("w1", "https://w1.example.com", "tok", "Acme")
        val made = TalkHome.of(peer)!!
        assertEquals(TalkHome("w1", "Acme", "https://w1.example.com", "tok"), made)
        assertNull(TalkHome.of(HivePeer("w1", "https://w1.example.com", "", null)))
        assertNull(TalkHome.of(HivePeer("w1", "http://w1.example.com", "tok", null)))
        assertNull(TalkHome.of(HivePeer("w1", "ftp://w1.example.com", "tok", null)))
        assertNotNull(TalkHome.of(HivePeer("w1", "http://127.0.0.1:3000", "tok", null)))
        assertNotNull(TalkHome.of(HivePeer("w1", "http://localhost:3000", "tok", null)))
        assertNull(TalkHome.of(HivePeer("w1", "https://user:pw@w1.example.com", "tok", null)))
    }

    @Test
    fun `a home never shows its token`() {
        assertFalse("person-token" in home.toString())
        assertFalse("secret-tok" in TalkHome.of(HivePeer("w1", "https://w1.example.com", "secret-tok", null))!!.toString())
    }

    @Test
    fun `the development home id is not a peer id and none is configured`() {
        assertEquals("@development", TalkHome.DEVELOPMENT)
        assertNull(Stadeum.developmentHome)
    }

    // MARK: - startCall

    @Test
    fun `startCall posts the key, voice and artifacts capability with the token`() = runBlocking {
        val http = FakeTalkHttp(HiveResponse(200, reply()))
        val got = home.startCall(TalkVoice.MALE, http, key = "k3y")
        assertEquals("https://acme.example.com/voice/calls", http.url)
        assertEquals("person-token", http.bearer)
        assertEquals(30L, http.timeout)
        assertEquals(64 * 1024, http.limit)
        val body = JSONObject(http.json!!)
        assertEquals("k3y", body.getString("key"))
        assertEquals("am_michael", body.getString("voice"))
        assertTrue(body.getBoolean("artifacts"))
        assertEquals("k3y", got.key)
        assertEquals("room-1", got.room)
    }

    @Test
    fun `startCall leaves the voice out when none is asked for`() {
        assertFalse(JSONObject(TalkHome.callBody("k", null)).has("voice"))
        assertEquals("af_heart", JSONObject(TalkHome.callBody("k", TalkVoice.FEMALE)).getString("voice"))
    }

    @Test
    fun `a trailing slash on the strut does not double up`() = runBlocking {
        val http = FakeTalkHttp(HiveResponse(200, reply()))
        TalkHome("w1", "Acme", "https://acme.example.com/lab/", "t").startCall(null, http, "k")
        assertEquals("https://acme.example.com/lab/voice/calls", http.url)
    }

    @Test
    fun `startCall never sends the token over plain http off this device`() {
        val http = FakeTalkHttp(HiveResponse(200, reply()))
        assertThrows(HiveException::class.java) {
            runBlocking { TalkHome("w1", "Acme", "http://acme.example.com", "t").startCall(null, http, "k") }
        }
        assertNull(http.url)
        runBlocking { TalkHome("dev", "Dev", "http://127.0.0.1:3000", "t").startCall(null, http, "k") }
        assertEquals("http://127.0.0.1:3000/voice/calls", http.url)
    }

    @Test
    fun `startCall turns a refusal into the home's words`() {
        val http = FakeTalkHttp(HiveResponse(401, """{"error":"bad  token"}"""))
        val e = assertThrows(TalkRefusal::class.java) { runBlocking { home.startCall(null, http, "k") } }
        assertEquals("Acme did not accept your sign-in: bad token.", e.message)
    }

    @Test
    fun `no answer is an IOException`() {
        assertThrows(IOException::class.java) { runBlocking { home.startCall(null, FakeTalkHttp(failure = IOException("down")), "k") } }
    }

    @Test
    fun `a key is 32 random bytes as base64, fresh each time`() {
        val a = TalkHome.newKey()
        val b = TalkHome.newKey()
        assertEquals(32, Base64.getDecoder().decode(a).size)
        assertEquals(32, Base64.getDecoder().decode(b).size)
        assertNotEquals(a, b)
    }

    @Test
    fun `each call makes its own key and sends it`() = runBlocking {
        val http = FakeTalkHttp(HiveResponse(200, reply()))
        val first = home.startCall(null, http)
        val sent = JSONObject(http.json!!).getString("key")
        val second = home.startCall(null, http)
        assertEquals(first.key, sent)
        assertNotEquals(first.key, second.key)
    }

    // MARK: - The ticket

    @Test
    fun `a good reply reads`() {
        val t = ticket(reply())
        assertEquals("wss://lk.example.com", t.url)
        assertEquals("room-1", t.room)
        assertEquals("me-1", t.identity)
        assertEquals("agent-1", t.agent)
        assertEquals(TalkVoice.FEMALE, t.voice)
        assertEquals("key", t.key)
    }

    @Test
    fun `the call server may be on any host`() {
        assertEquals("wss://anywhere.example.org:7443", ticket(reply(url = "wss://anywhere.example.org:7443")).url)
    }

    @Test
    fun `https and http read as wss and ws, ws only on this device`() {
        assertEquals("wss://lk.example.com", ticket(reply(url = "https://lk.example.com")).url)
        assertEquals("ws://127.0.0.1:7880", ticket(reply(url = "ws://127.0.0.1:7880")).url)
        assertEquals("ws://localhost:7880", ticket(reply(url = "http://localhost:7880")).url)
        assertEquals("ws://[::1]:7880", ticket(reply(url = "ws://[::1]:7880")).url)
        assertTrue(refusal(reply(url = "ws://lk.example.com")).message!!.startsWith("Acme named a call server Peel can't use"))
        assertTrue(refusal(reply(url = "http://lk.example.com")).message!!.contains("can't use"))
        assertTrue(refusal(reply(url = "ftp://lk.example.com")).message!!.contains("can't use"))
    }

    @Test
    fun `a media url with a user, query, fragment or too long is refused`() {
        for (url in listOf("wss://u:p@lk.example.com", "wss://lk.example.com?x=1", "wss://lk.example.com#f", "wss://" + "a".repeat(2050) + ".com", "wss://", "lk.example.com")) {
            assertNull(url, TalkTicket.mediaUrl(url))
        }
    }

    @Test
    fun `a call that is not encrypted is refused`() {
        val expected = "Acme can't make an encrypted call yet: its voice service needs an update."
        assertEquals(expected, refusal(reply(encrypted = null)).message)
        assertEquals(expected, refusal(reply(encrypted = false)).message)
        assertEquals(expected, refusal(JSONObject(reply()).put("encrypted", "true").toString()).message)
    }

    @Test
    fun `a token for another room is refused`() {
        assertEquals("Acme's call token is not for the room it named.", refusal(reply(token = jwt("other"))).message)
        assertEquals("Acme's call token is not for the room it named.", refusal(reply(token = jwt(null))).message)
        assertEquals("Acme's call token is not for the room it named.", refusal(reply(token = "not a jwt")).message)
    }

    @Test
    fun `bad identities are refused`() {
        val unreadable = "Acme's answer could not be read."
        assertEquals(unreadable, refusal(reply(identity = "")).message)
        assertEquals(unreadable, refusal(reply(identity = "me\u0007")).message)
        assertEquals(unreadable, refusal(reply(agent = "a".repeat(257))).message)
        assertEquals(unreadable, refusal(reply(room = "r\n1", token = jwt("r\n1"))).message)
    }

    @Test
    fun `an unreadable reply is refused`() {
        val unreadable = "Acme's answer could not be read."
        assertEquals(unreadable, refusal("nope").message)
        assertEquals(unreadable, refusal("""{"url":"wss://x.example.com"}""").message)
        assertEquals(unreadable, refusal(JSONObject(reply()).put("room", 5).toString()).message)
    }

    @Test
    fun `an unknown voice is none`() {
        assertNull(ticket(reply(voice = "bm_george")).voice)
        assertNull(ticket(reply(voice = null)).voice)
        assertEquals(TalkVoice.MALE, ticket(reply(voice = "am_michael")).voice)
    }

    @Test
    fun `a ticket never shows its token or key`() {
        val t = TalkTicket.parse(reply(), "Acme", "sekret-key")
        assertFalse("sekret-key" in t.toString())
        assertFalse(t.token in t.toString())
    }

    // MARK: - Refusals

    @Test
    fun `402 says the balance and the minimum`() {
        val r = TalkRefusal.of(402, """{"balance":5,"minimum":1000}""", "Acme")
        assertEquals("Not enough sats to talk: you have 5 sats, and a call needs 1,000 sats.", r.message)
        assertTrue(r is TalkRefusal.BalanceTooLow)
    }

    @Test
    fun `402 without numbers is an ordinary refusal`() {
        assertEquals("Acme could not start the call (HTTP 402): pay up.", TalkRefusal.of(402, """{"error":"pay up"}""", "Acme").message)
    }

    @Test
    fun `statuses lead the home's words`() {
        fun say(status: Int, body: String) = TalkRefusal.of(status, body, "Acme").message
        assertEquals("Acme could not start the call: unknown voice.", say(400, """{"error":"unknown voice"}"""))
        assertEquals("Acme did not accept your sign-in.", say(401, ""))
        assertEquals("Acme takes calls only from a person's sign-in.", say(403, "{}"))
        assertEquals("Jamie can't take calls on Acme right now.", say(503, "<html>"))
        assertEquals("Acme could not start the call (HTTP 500).", say(500, """{"error":"   "}"""))
        assertEquals("Acme could not start the call (HTTP 418): short and stout.", say(418, """{"error":"short\nand   stout"}"""))
    }

    @Test
    fun `the home's words are capped at 300 characters`() {
        val message = TalkRefusal.of(400, JSONObject().put("error", "x".repeat(900)).toString(), "Acme").message!!
        assertEquals("Acme could not start the call: ".length + 300 + 1, message.length)
    }

    // MARK: - The voice

    @Test
    fun `the voice is kept per device, female by default`() {
        val prefs = TalkVoice.prefs(RuntimeEnvironment.getApplication())
        prefs.edit().clear().commit()
        assertEquals(TalkVoice.FEMALE, TalkVoice.stored(prefs))
        TalkVoice.MALE.store(prefs)
        assertEquals(TalkVoice.MALE, TalkVoice.stored(prefs))
        assertEquals("am_michael", prefs.getString("talkVoice", null))
        prefs.edit().putString("talkVoice", "zz").commit()
        assertEquals(TalkVoice.FEMALE, TalkVoice.stored(prefs))
        // The file is the app's, not an account's.
        assertNotNull(RuntimeEnvironment.getApplication().getSharedPreferences("peel.talk", Context.MODE_PRIVATE))
    }

    @Test
    fun `the toggle flips, with a text glyph and a label`() {
        assertEquals(TalkVoice.MALE, TalkVoice.FEMALE.flipped)
        assertEquals(TalkVoice.FEMALE, TalkVoice.MALE.flipped)
        assertEquals("\u2640\uFE0E", TalkVoice.FEMALE.glyph)
        assertEquals("\u2642\uFE0E", TalkVoice.MALE.glyph)
        assertEquals("Jamie's voice: female", TalkVoice.FEMALE.accessibilityLabel)
        assertEquals("Jamie's voice: male", TalkVoice.MALE.accessibilityLabel)
        assertEquals("voice", TalkVoice.ATTRIBUTE)
    }

    // MARK: - Phase

    private fun phase(connection: TalkPhase.Connection, once: Boolean = false, agent: String? = null, seen: Boolean = false) =
        TalkPhase.of(connection, once, agent, seen)

    @Test
    fun `the room's connection comes first`() {
        assertEquals("Connecting…", phase(TalkPhase.Connection.CONNECTING).words)
        assertEquals("Reconnecting…", phase(TalkPhase.Connection.CONNECTING, once = true).words)
        assertEquals("Reconnecting…", phase(TalkPhase.Connection.RECONNECTING).words)
        assertEquals("The call ended", phase(TalkPhase.Connection.DISCONNECTED, once = true).words)
        assertEquals("Could not connect to the call", phase(TalkPhase.Connection.DISCONNECTED).words)
        assertTrue(phase(TalkPhase.Connection.DISCONNECTED).isOver)
        assertFalse(phase(TalkPhase.Connection.CONNECTED).isOver)
    }

    @Test
    fun `a connected room reads the agent's state`() {
        val on = TalkPhase.Connection.CONNECTED
        assertEquals("Waiting for Jamie…", phase(on).words)
        assertEquals("Jamie left the call", phase(on, seen = true).words)
        assertEquals("Jamie is getting ready…", phase(on, agent = "initializing", seen = true).words)
        assertEquals("Jamie is getting ready…", phase(on, agent = "", seen = true).words)
        assertEquals("Jamie is getting ready…", phase(on, agent = "who-knows", seen = true).words)
        assertEquals("Listening", phase(on, agent = "idle", seen = true).words)
        assertEquals("Listening", phase(on, agent = "listening", seen = true).words)
        assertEquals("Thinking…", phase(on, agent = "thinking", seen = true).words)
        assertEquals("Speaking", phase(on, agent = "speaking", seen = true).words)
        assertEquals(TalkPhase.Speaking, phase(on, agent = "speaking"))
    }

    @Test
    fun `an agent's state does not hide a room that dropped`() {
        assertEquals(TalkPhase.Reconnecting, phase(TalkPhase.Connection.RECONNECTING, once = true, agent = "speaking", seen = true))
    }

    @Test
    fun `the waveform mode follows the phase`() {
        assertEquals(TalkWave.Mode.BREATHING, TalkWave.Mode.of(null, false))
        assertEquals(TalkWave.Mode.MEMBER, TalkWave.Mode.of(TalkPhase.Listening, false))
        assertEquals(TalkWave.Mode.MUTED, TalkWave.Mode.of(TalkPhase.Listening, true))
        assertEquals(TalkWave.Mode.AGENT, TalkWave.Mode.of(TalkPhase.Speaking, true))
        assertEquals(TalkWave.Mode.THINKING, TalkWave.Mode.of(TalkPhase.Thinking, false))
        assertEquals(TalkWave.Mode.STILL, TalkWave.Mode.of(TalkPhase.Waiting(left = true), false))
        assertEquals(TalkWave.Mode.STILL, TalkWave.Mode.of(TalkPhase.Ended(true), false))
        for (mode in TalkWave.Mode.entries) {
            val bars = TalkWave.heights(mode, 0.7, 1.3)
            assertEquals(TalkWave.BAR_COUNT, bars.size)
            assertTrue(bars.all { it in TalkWave.FLOOR..1.0 })
        }
        val smoother = LevelSmoother()
        assertTrue(smoother.update(1.0, 0.03) > 0.5)
    }

    // MARK: - Encryption

    @Test
    fun `an encryption failure is said, the agent unheard first`() {
        var e = TalkEncryption()
        assertNull(e.notice)
        e = e.track("mine", local = true, failed = true)
        assertEquals(TalkEncryption.YOU_UNHEARD, e.notice)
        e = e.track("theirs", local = false, failed = true)
        assertEquals("This call's encryption failed: Jamie can't be heard.", e.notice)
        e = e.track("theirs", local = false, failed = false).track("mine", local = true, failed = false)
        assertNull(e.notice)
    }

    // MARK: - Transcript

    private fun feed(
        t: TalkTranscript, chunk: String, stream: String = "s1", sender: String = "agent-1",
        segment: String? = "seg1", track: String? = null, final: Boolean = false, tracks: Set<String> = setOf("TR_me"),
    ): TalkTranscript {
        val attributes = mutableMapOf<String, String>()
        segment?.let { attributes["lk.segment_id"] = it }
        track?.let { attributes["lk.transcribed_track_id"] = it }
        if (final) attributes["lk.transcription_final"] = "true"
        return t.receive(chunk, stream, sender, attributes, you = "me-1", yourTracks = tracks)
    }

    @Test
    fun `chunks of one stream add up into one line`() {
        var t = TalkTranscript()
        t = feed(t, "Hel")
        t = feed(t, "lo  there")
        assertEquals(1, t.lines.size)
        assertEquals("Hello there", t.lines[0].text)
        assertEquals(TalkLine.Speaker.AGENT, t.lines[0].speaker)
        assertFalse(t.lines[0].isFinal)
        t = feed(t, "", final = true)
        assertTrue(t.lines[0].isFinal)
    }

    @Test
    fun `a new stream for a segment replaces its line`() {
        var t = feed(TalkTranscript(), "one", stream = "s1", sender = "me-1")
        t = feed(t, "one two", stream = "s2", sender = "me-1")
        assertEquals(1, t.lines.size)
        assertEquals("one two", t.lines[0].text)
    }

    @Test
    fun `a line is the member's by their identity or one of their tracks`() {
        var t = feed(TalkTranscript(), "hi", stream = "a", sender = "me-1", segment = "x")
        t = feed(t, "hello", stream = "b", sender = "agent-1", segment = "y", track = "TR_me")
        t = feed(t, "greetings", stream = "c", sender = "agent-1", segment = "z", track = "TR_other")
        t = feed(t, "no segment", stream = "d", sender = "agent-1", segment = null)
        assertEquals(
            listOf(TalkLine.Speaker.YOU, TalkLine.Speaker.YOU, TalkLine.Speaker.AGENT, TalkLine.Speaker.AGENT),
            t.lines.map { it.speaker },
        )
        assertEquals(listOf("you/x", "you/y", "agent/z", "agent/d"), t.lines.map { it.id })
    }

    @Test
    fun `the transcript keeps the newest 200 lines`() {
        var t = TalkTranscript()
        for (i in 0 until 205) t = feed(t, "line $i", stream = "s$i", segment = "g$i")
        assertEquals(200, t.lines.size)
        assertEquals("agent/g5", t.lines.first().id)
        assertEquals("agent/g4", t.lastDropped)
        assertEquals("line 204", t.lines.last().text)
    }

    // MARK: - Artifacts (iOS `TalkArtifactsTests`)

    private fun payload(entries: String, id: String = "p-1") = """{"id":"$id","timestamp":1760000000000,"artifacts":[$entries]}"""

    @Test
    fun `a plan on the homes strut`() {
        val text = payload("""{"id":"a1","kind":"plan","title":"Reminders for Jamie, plan","label":"Plan","url":"/jobs/j1/files/plan.md","path":"plan.md"}""")
        val card = TalkArtifacts.decode(text, "acme").first()
        assertEquals("Reminders for Jamie, plan", card.title)
        assertEquals("Plan", card.label)
        assertEquals("a1", card.id)
        assertEquals(AgentHandle.file("/peers/acme/jobs/j1/files/plan.md"), card.handle)
        assertEquals("p-1", TalkArtifacts.batch(text, "acme")?.id)
    }

    @Test
    fun `an app and a peers file`() {
        val text = payload(
            """{"id":"a2","kind":"app","title":"Board","url":"/apps/board/v2/files/dist/index.html","app":{"name":"board","version":"v2","hash":"h","builtAt":1}},
            {"id":"a3","kind":"file","title":"Notes","url":"/jobs/j9/files/n.md","peer":"beta","job":"j9","path":"n.md"}""",
        )
        assertEquals(
            listOf(AgentHandle.app("@acme/board@v2"), AgentHandle.file("/peers/acme/peers/beta/jobs/j9/files/n.md")),
            TalkArtifacts.decode(text, "acme").map { it.handle },
        )
    }

    @Test
    fun `an https link without a handle`() {
        val text = payload("""{"id":"a4","kind":"pull_request","title":"Fix it","url":"https://github.com/acme/repo/pull/7"}""")
        val card = TalkArtifacts.decode(text, TalkHome.DEVELOPMENT).first()
        assertNull(card.handle)
        assertEquals("https://github.com/acme/repo/pull/7", card.url)
    }

    @Test
    fun `malformed gives nothing`() {
        for (text in listOf("", "not json", "[]", """{"id":"p"}""", """{"artifacts":[{"title":"x"}]}""", """{"id":"p","artifacts":"x"}""")) {
            assertTrue(text, TalkArtifacts.decode(text, "acme").isEmpty())
        }
        assertNull(TalkArtifacts.batch("nope", "acme"))
        assertNull(TalkArtifacts.batch("""{"id":"","artifacts":[]}""", "acme"))
        assertTrue(TalkArtifacts.decode(payload("1, null, \"x\""), "acme").isEmpty())
        assertEquals(0, TalkArtifacts.batch("""{"id":"p","artifacts":[]}""", "acme")!!.artifacts.size)
    }

    @Test
    fun `an entry with no title is skipped and a stream is capped`() {
        val text = payload("""{"id":"a5","kind":"plan","url":"/jobs/j1/files/p.md"},{"id":"a6","kind":"plan","title":"Kept","url":"/jobs/j1/files/p.md"}""")
        assertEquals(listOf("Kept"), TalkArtifacts.decode(text, "acme").map { it.title })
        val many = (0 until 30).joinToString(",") { """{"id":"e$it","title":"T$it","url":"/jobs/j/files/f"}""" }
        assertEquals(AgentArtifact.MOST, TalkArtifacts.decode(payload(many), "acme").size)
    }

    @Test
    fun `a card with no home that strut gives opens nothing`() {
        val entry = """{"id":"x","title":"X","url":"/jobs/j/files/x"}"""
        assertNull(TalkArtifacts.decode(payload(entry), TalkHome.DEVELOPMENT).first().handle)
        assertNull(TalkArtifacts.decode(payload(entry), "../x").first().handle)
    }

    // MARK: - Cards under replies

    @Test
    fun `a card goes under the reply being spoken`() {
        var t = feed(TalkTranscript(), "Here you go", segment = "r1")
        val anchor = TalkCardAnchor.of(t)
        assertEquals(TalkCardAnchor.Under("agent/r1"), anchor)
        t = feed(t, "", segment = "r1", final = true)
        t = feed(t, "thanks", stream = "s2", sender = "me-1", segment = "m1")
        assertEquals(1, anchor.linesAbove(t))
    }

    @Test
    fun `a card that comes before its reply waits for the next line`() {
        var t = feed(TalkTranscript(), "show me", stream = "s0", sender = "me-1", segment = "m0", final = true)
        val anchor = TalkCardAnchor.of(t)
        assertEquals(TalkCardAnchor.Next("you/m0"), anchor)
        assertEquals(1, anchor.linesAbove(t))
        // Next is Jamie's: the card goes under it.
        t = feed(t, "Sure", stream = "s1", segment = "r1")
        assertEquals(2, anchor.linesAbove(t))
        // The first card of a call, before any line.
        val first = TalkCardAnchor.of(TalkTranscript())
        assertEquals(0, first.linesAbove(TalkTranscript()))
        // Next is the member's: the card stays above it.
        val t2 = feed(TalkTranscript(), "hm", stream = "q", sender = "me-1", segment = "m9")
        assertEquals(0, first.linesAbove(t2))
    }
}
