package com.sphinx.peel.engine

import android.content.Context
import android.content.SharedPreferences
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

// Talk to Jamie (iOS `TalkCall.swift`, `TalkStore.swift`; docs/phases/25-talk-to-jamie.md):
// a live voice call with an org's agent. Peel asks the org's own strut, its
// home, with the person's token (`POST /voice/calls`), and joins the LiveKit
// room the home names. Not a call link: no call server, no chat message, no
// `CallLink`, and none of `CallServer`'s rule that media sits on the call
// server's own host, since the home named it in answer to the person's own
// authenticated request. The media is `ui/talk/TalkSession`.
//
// Every call is end-to-end encrypted: Peel makes a fresh key for it, sends it
// to the home with the call, and joins the room with it, as the home's agent
// does. The LiveKit server forwards what it cannot read.

/// How Talk reaches a home; a test's stand-in answers without a network.
fun interface TalkHttp {
    /// Posts [json] to [url] with the person's [bearer] token. The reply is
    /// capped at [limit] bytes: a longer one throws [HiveException] naming
    /// [what]. Throws `java.io.IOException` when no answer came.
    suspend fun post(url: String, json: String, bearer: String, timeoutSeconds: Long, limit: Int, what: String): HiveResponse
}

/// OkHttp's [TalkHttp]: no redirects followed (the token must not follow one).
class OkHttpTalk(
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(),
) : TalkHttp {
    override suspend fun post(url: String, json: String, bearer: String, timeoutSeconds: Long, limit: Int, what: String): HiveResponse =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("Accept", "application/json")
                .header("Authorization", "Bearer $bearer")
                .post(json.toRequestBody("application/json".toMediaType())).build()
            client.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).build().newCall(request).execute().use { r ->
                val source = r.body?.source()
                val bytes = source?.let { it.request(limit + 1L); it.buffer.snapshot().toByteArray() } ?: ByteArray(0)
                if (bytes.size > limit) throw HiveException("$what was too large.")
                HiveResponse(r.code, String(bytes, Charsets.UTF_8))
            }
        }
}

/// Where Talk calls: an org's strut and the person's token for it (iOS
/// `TalkHome`). Never described with its token.
data class TalkHome(
    /// The org's peer id ([HivePeer.id]), or [DEVELOPMENT].
    val id: String,
    /// What the person reads.
    val name: String,
    /// The home's strut: https anywhere, http only on this device.
    val url: String,
    /// The person token for it (strut's `peer:person` scope).
    internal val token: String,
) {
    override fun toString() = "TalkHome($id, $url, token not shown)"

    /// Starts a call with the home's agent in [voice]: `POST /voice/calls`
    /// with the person's token and a fresh [key] for the call, answered with
    /// the room to join. A refusal throws [TalkRefusal] in the home's own
    /// words (400 for a voice it does not know).
    suspend fun startCall(voice: TalkVoice? = null, http: TalkHttp = OkHttpTalk(), key: String = newKey()): TalkTicket {
        val base = strutUrl(url) ?: throw HiveException("$name's address is not one Peel sends your sign-in to.")
        val reply = http.post(base.trimEnd('/') + "/voice/calls", callBody(key, voice), token, TIMEOUT_SECONDS, REPLY_LIMIT, "$name's answer")
        if (reply.status != 200) throw TalkRefusal.of(reply.status, reply.body, name)
        return TalkTicket.parse(reply.body, name, key)
    }

    companion object {
        /// The dev setting's id, never a peer id strut gives (those are slugs). Android has no dev setting.
        const val DEVELOPMENT = "@development"

        /// The most `POST /voice/calls` may answer: a few short strings and a JWT.
        const val REPLY_LIMIT = 64 * 1024
        const val TIMEOUT_SECONDS = 30L

        /// A paired org's home: its strut and the person's token from Stadeum.
        /// Null when its address is not one Peel may send the token to, or it has no token.
        fun of(peer: HivePeer): TalkHome? {
            if (peer.token.isEmpty() || strutUrl(peer.baseUrl) == null) return null
            return TalkHome(peer.id, peer.name, peer.baseUrl, peer.token)
        }

        /// A strut's address: https anywhere, plain http only on this device
        /// (iOS `Web.isStrut`); no user, query or fragment. Null for anything else.
        internal fun strutUrl(text: String): String? {
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
            val loopback = ServerAddress.isLoopback(host.trim('[', ']'))
            return when (uri.scheme?.lowercase()) {
                "https" -> text.trim()
                "http" -> if (loopback) text.trim() else null
                else -> null
            }
        }

        /// A call's key: 32 random bytes as base64, made for one call and never
        /// kept. LiveKit keys the room with this string's UTF-8 bytes, on the
        /// phone (`BaseKeyProvider.setSharedKey`) and in the home's agent alike.
        fun newKey(random: SecureRandom = SecureRandom()): String =
            Base64.getEncoder().encodeToString(ByteArray(32).also { random.nextBytes(it) })

        /// `POST /voice/calls`'s body: `{"key":"<base64>","voice":"am_michael","artifacts":true}`.
        /// `artifacts` says this Peel shows a turn's artifact cards ([TalkArtifacts]).
        fun callBody(key: String, voice: TalkVoice?): String = JSONObject().apply {
            put("key", key)
            voice?.let { put("voice", it.wire) }
            put("artifacts", true)
        }.toString()
    }
}

/// Jamie's voice on a Talk call: one of the voice service's, female
/// `af_heart` (the default) or male `am_michael`. The member picks it on the
/// call screen; it is kept on this device (`SharedPreferences`, `talkVoice`),
/// asked for in `POST /voice/calls`, and switched mid-call by the member's
/// participant attribute `voice`, from the agent's next sentence.
enum class TalkVoice(val wire: String) {
    FEMALE("af_heart"),
    MALE("am_michael");

    /// The other voice: what the toggle switches to.
    val flipped: TalkVoice get() = if (this == FEMALE) MALE else FEMALE

    /// The toggle's face: ♀ or ♂, with the text variation selector (U+FE0E)
    /// so it never shows as an emoji.
    val glyph: String get() = if (this == FEMALE) "\u2640\uFE0E" else "\u2642\uFE0E"

    /// What TalkBack says of the toggle.
    val accessibilityLabel: String get() = "Jamie's voice: ${if (this == FEMALE) "female" else "male"}"

    /// Keeps the choice on this device.
    fun store(prefs: SharedPreferences) {
        prefs.edit().putString(KEY, wire).apply()
    }

    companion object {
        /// Where the choice is kept, per device.
        const val KEY = "talkVoice"
        /// The member's participant attribute that switches it mid-call.
        const val ATTRIBUTE = "voice"
        private const val FILE = "peel.talk"

        fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        /// The voice named [wire]; null for one this Peel does not know.
        fun of(wire: String?): TalkVoice? = entries.firstOrNull { it.wire == wire }

        /// The voice kept on this device: female when none is, or one this Peel does not know.
        fun stored(prefs: SharedPreferences): TalkVoice = of(prefs.getString(KEY, null)) ?: FEMALE
    }
}

/// What a home answers to `POST /voice/calls`: the LiveKit room to join and
/// the person's token for it, with the call's key. In memory for one join,
/// never stored, logged or described with its token or key (iOS `TalkTicket`).
data class TalkTicket(
    /// The LiveKit server: `wss://`, or `ws://` on this device.
    val url: String,
    val room: String,
    /// The LiveKit access token (a JWT) for [room].
    val token: String,
    /// The person's identity in the room, random per call.
    val identity: String,
    /// The agent's identity in the room, random per call.
    val agent: String,
    /// The voice in effect, as the home answered; null when it named none, or one this Peel does not know.
    val voice: TalkVoice?,
    /// The call's end-to-end encryption key, as sent to the home: the room's shared key.
    val key: String,
) {
    override fun toString() = "TalkTicket($url, room $room, token and key not shown)"

    companion object {
        /// The reply read and checked: a LiveKit server Peel may connect to, a
        /// token for the room named, two identities, and the home's word that
        /// its agent joined with [key].
        fun parse(body: String, home: String, key: String): TalkTicket {
            val unreadable = HiveException("$home's answer could not be read.")
            val json = runCatching { JSONObject(body) }.getOrNull() ?: throw unreadable
            fun text(name: String) = json.opt(name) as? String
            val given = text("url"); val room = text("room"); val token = text("token")
            val identity = text("identity"); val agent = text("agent")
            if (given == null || room == null || token == null || identity == null || agent == null) throw unreadable
            val url = mediaUrl(given)
                ?: throw HiveException("$home named a call server Peel can't use: only wss://, or ws:// on this device.")
            // A home whose agent did not take the key (a voice service from
            // before end-to-end encryption) answers without `encrypted: true`.
            // Never a call in the clear: refused.
            if (json.opt("encrypted") != true) {
                throw HiveException("$home can't make an encrypted call yet: its voice service needs an update.")
            }
            if (!listOf(room, identity, agent).all { CallServer.isIdentity(it) }) throw unreadable
            val claimed = try { CallServer.parseClaims(token).room } catch (e: CallServer.TicketException) { null }
            if (claimed != room) throw HiveException("$home's call token is not for the room it named.")
            return TalkTicket(url, room, token, identity, agent, TalkVoice.of(text("voice")), key)
        }

        /// The LiveKit server, as LiveKit takes it: `wss://`, or `ws://` only on
        /// this device (LiveKit reads `https://` and `http://` as those). No
        /// user, query or fragment. Null for anything else.
        internal fun mediaUrl(text: String): String? {
            if (text.toByteArray(Charsets.UTF_8).size > 2048) return null
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            val given = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
            val scheme = mapOf("https" to "wss", "http" to "ws")[given] ?: given
            // An IPv6 host comes bracketed ("[::1]").
            val loopback = ServerAddress.isLoopback(host.trim('[', ']'))
            if (!(scheme == "wss" || (scheme == "ws" && loopback))) return null
            return scheme + text.substring(given.length)
        }
    }
}

/// A home's refusal of a call (`POST /voice/calls`), in its own words (iOS `TalkRefusal`).
sealed class TalkRefusal(message: String) : Exception(message) {
    /// 402: the person's balance is below the call's minimum, in sats.
    class BalanceTooLow(val balance: Long, val minimum: Long) : TalkRefusal(
        "Not enough sats to talk: you have ${sats(balance)}, and a call needs ${sats(minimum)}.",
    )

    /// Any other status, with the home's `error` cleaned, if it gave one.
    class Refused(val status: Int, val words: String?, val home: String) : TalkRefusal(
        lead(status, home).let { lead -> words?.let { "$lead: $it." } ?: "$lead." },
    )

    companion object {
        fun of(status: Int, body: String, home: String): TalkRefusal {
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (status == 402 && json != null && json.opt("balance") is Number && json.opt("minimum") is Number) {
                return BalanceTooLow(json.getLong("balance"), json.getLong("minimum"))
            }
            val words = (json?.opt("error") as? String)?.let { WorkspaceApp.clean(it, 300) }
            return Refused(status, words?.ifEmpty { null }, home)
        }

        private fun sats(value: Long) = Sats.format(minOf(maxOf(value, 0L), Long.MAX_VALUE / 1000) * 1000)

        private fun lead(status: Int, home: String) = when (status) {
            400 -> "$home could not start the call"
            401 -> "$home did not accept your sign-in"
            403 -> "$home takes calls only from a person's sign-in"
            503 -> "Jamie can't take calls on $home right now"
            else -> "$home could not start the call (HTTP $status)"
        }
    }
}

/// What Jamie made or found in a turn of a Talk call, as the voice agent
/// sends it to the member alone: one text stream on [TOPIC] (in the room's
/// end-to-end encryption, as the transcript is) whose text is
/// `{ "id", "timestamp" (epoch ms), "artifacts": [strut's ArtifactRef…] }`.
/// Android has no artifact model beyond this (iOS `AgentArtifact`): each entry
/// is its title, summary and an https link; see the phase doc.
object TalkArtifacts {
    const val TOPIC = "sphinx.artifacts"
    /// The most entries read from one stream.
    const val MOST = 20
    /// The most cards a call keeps.
    const val MAX_CARDS = 30
    /// The longest payload read, in bytes.
    const val MAX_PAYLOAD = 256 * 1024

    /// One of Jamie's artifacts: shown as a row; [url] is set only for an absolute https link.
    data class Card(val id: String, val title: String, val summary: String?, val url: String?)

    /// A stream's payload: its id and its entries, capped.
    data class Batch(val id: String, val artifacts: List<Card>)

    /// The payload in [text]; null for malformed JSON or a payload with no id or list.
    fun batch(text: String): Batch? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val id = (json.opt("id") as? String)?.takeIf { it.isNotEmpty() } ?: return null
        val entries = json.opt("artifacts") as? JSONArray ?: return null
        val cards = (0 until minOf(entries.length(), MOST)).mapNotNull { i -> card(entries.optJSONObject(i), "$id/$i") }
        return Batch(id, cards)
    }

    /// An entry with a title (strut's `ArtifactRef`: `title` or `label`, `summary`, `url`); null for one without.
    private fun card(entry: JSONObject?, id: String): Card? {
        entry ?: return null
        fun text(key: String, max: Int) = (entry.opt(key) as? String)?.let { WorkspaceApp.clean(it, max) }?.ifEmpty { null }
        val title = text("title", 200) ?: text("label", 200) ?: return null
        val url = text("url", 2048)?.takeIf { it.startsWith("https://", ignoreCase = true) && runCatching { URI(it).host }.getOrNull() != null }
        return Card(id, title, text("summary", 400), url)
    }
}

/// The agent's turn, as LiveKit Agents publish it on their participant (`lk.agent.state`).
enum class TalkAgentState(val wire: String) {
    INITIALIZING("initializing"), IDLE("idle"), LISTENING("listening"), THINKING("thinking"), SPEAKING("speaking");

    companion object {
        /// The participant attribute it is read from.
        const val ATTRIBUTE = "lk.agent.state"
        fun of(wire: String?): TalkAgentState? = entries.firstOrNull { it.wire == wire }
    }
}

/// Where a Talk call stands, as the call screen says it: from the room's
/// connection and the agent's own state (iOS `TalkPhase`). The member
/// interrupts by speaking, so nothing here waits for a turn.
sealed class TalkPhase {
    object Connecting : TalkPhase()
    object Reconnecting : TalkPhase()
    /// In the room, the agent not there: not yet, or [left] once it was.
    data class Waiting(val left: Boolean) : TalkPhase()
    object GettingReady : TalkPhase()
    object Listening : TalkPhase()
    object Thinking : TalkPhase()
    object Speaking : TalkPhase()
    /// The room is gone: [connected] once it was, else it never connected.
    data class Ended(val connected: Boolean) : TalkPhase()

    /// The room's connection, as LiveKit reports it.
    enum class Connection { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED }

    val words: String
        get() = when (this) {
            Connecting -> "Connecting…"
            Reconnecting -> "Reconnecting…"
            is Waiting -> if (left) "Jamie left the call" else "Waiting for Jamie…"
            GettingReady -> "Jamie is getting ready…"
            Listening -> "Listening"
            Thinking -> "Thinking…"
            Speaking -> "Speaking"
            is Ended -> if (connected) "The call ended" else "Could not connect to the call"
        }

    /// Whether the call is over: the screen offers Try again and Close.
    val isOver: Boolean get() = this is Ended

    companion object {
        /// [agentState] is the agent participant's `lk.agent.state`, null while
        /// it is not in the room; [agentSeen] whether it has been.
        fun of(connection: Connection, connectedOnce: Boolean, agentState: String?, agentSeen: Boolean): TalkPhase =
            when (connection) {
                Connection.CONNECTING -> if (connectedOnce) Reconnecting else Connecting
                Connection.RECONNECTING -> Reconnecting
                Connection.DISCONNECTED -> Ended(connectedOnce)
                Connection.CONNECTED -> when {
                    agentState == null -> Waiting(left = agentSeen)
                    else -> when (TalkAgentState.of(agentState)) {
                        // Idle is between turns: the member may speak.
                        TalkAgentState.IDLE, TalkAgentState.LISTENING -> Listening
                        TalkAgentState.THINKING -> Thinking
                        TalkAgentState.SPEAKING -> Speaking
                        // An agent that has not said yet is still starting.
                        TalkAgentState.INITIALIZING, null -> GettingReady
                    }
                }
            }
    }
}

/// A Talk call's encryption as it goes: the tracks LiveKit says will not
/// encrypt or decrypt. The agent's state comes over LiveKit's signaling, which
/// is not encrypted, so a call whose key does not match would otherwise say
/// "Speaking" over silence. [notice] is what the call screen says instead.
/// (iOS `TalkEncryption`; Android's SDK reports no failed text packet, so
/// there is no `textFailedToDecrypt`.)
data class TalkEncryption(
    /// The tracks failing now, by publication id: true for the member's own
    /// (it will not encrypt), false for the agent's (it will not decrypt).
    private val failing: Map<String, Boolean> = emptyMap(),
) {
    /// A track's state from LiveKit: [failed] for a missing key or frames that
    /// will not encrypt or decrypt, [local] for the member's own track.
    fun track(id: String, local: Boolean, failed: Boolean): TalkEncryption =
        copy(failing = if (failed) failing + (id to local) else failing - id)

    /// What the call screen says, null while nothing fails. The agent unheard
    /// first: then nothing of the call comes through.
    val notice: String?
        get() = when {
            failing.values.contains(false) -> AGENT_UNHEARD
            failing.values.contains(true) -> YOU_UNHEARD
            else -> null
        }

    companion object {
        const val AGENT_UNHEARD = "This call's encryption failed: Jamie can't be heard."
        const val YOU_UNHEARD = "This call's encryption failed: Jamie can't hear you."
    }
}

/// One line of a Talk call's live text: what the member said or what the agent says, as it comes.
data class TalkLine(
    val id: String,
    val speaker: Speaker,
    /// Cleaned for showing.
    val text: String,
    /// Whether this segment is done; a line still coming may change.
    val isFinal: Boolean,
) {
    enum class Speaker { YOU, AGENT }
}

/// A Talk call's live text, folded from the text streams LiveKit Agents
/// publish on [TOPIC], as LiveKit's own `TranscriptionStreamReceiver` folds
/// them: one line per speaker and `lk.segment_id`; chunks of one stream add
/// up, and a new stream for a segment (the member's words, sent whole each
/// time they change) replaces it. Whose words they are is
/// `lk.transcribed_track_id`: one of the member's tracks, else the agent's.
data class TalkTranscript(
    val lines: List<TalkLine> = emptyList(),
    /// The newest line the cap dropped, by id: the one before the first kept.
    val lastDropped: String? = null,
    /// Each line's text as it came, and the stream that last wrote it.
    internal val raw: Map<String, Pair<String, String>> = emptyMap(),
) {
    /// One chunk of a text stream on [TOPIC]: [stream] the stream's id,
    /// [sender] the identity it came from, [attributes] the stream's; [you]
    /// the member's identity and [yourTracks] their tracks' ids.
    fun receive(
        chunk: String, stream: String, sender: String, attributes: Map<String, String>,
        you: String, yourTracks: Set<String>,
    ): TalkTranscript {
        val track = attributes["lk.transcribed_track_id"]
        val speaker = if (sender == you || (track != null && track in yourTracks)) TalkLine.Speaker.YOU else TalkLine.Speaker.AGENT
        val segment = attributes["lk.segment_id"] ?: stream
        val id = "${if (speaker == TalkLine.Speaker.YOU) "you" else "agent"}/$segment"
        val isFinal = attributes["lk.transcription_final"] == "true"
        val earlier = raw[id]
        val text = (if (earlier != null && earlier.second == stream) earlier.first + chunk else chunk).take(MAX_TEXT)
        var nextRaw = raw + (id to (text to stream))
        val shown = WorkspaceApp.clean(text, MAX_TEXT)
        val index = lines.indexOfFirst { it.id == id }
        if (index >= 0) {
            val next = lines.toMutableList()
            next[index] = next[index].copy(text = shown, isFinal = isFinal)
            return copy(lines = next, raw = nextRaw)
        }
        var next = lines + TalkLine(id, speaker, shown, isFinal)
        var dropped = lastDropped
        if (next.size > MAX_LINES) {
            val over = next.size - MAX_LINES
            for (gone in next.take(over)) nextRaw = nextRaw - gone.id
            dropped = next[over - 1].id
            next = next.drop(over)
        }
        return copy(lines = next, lastDropped = dropped, raw = nextRaw)
    }

    companion object {
        const val TOPIC = "lk.transcription"
        /// The newest lines kept; older ones go.
        const val MAX_LINES = 200
        /// The most characters of a line's text.
        const val MAX_TEXT = 4000
    }
}

/// Where one of Jamie's cards goes in a Talk call's live text: under the reply
/// it came with. A card comes as strut's run for a turn ends, usually before
/// Jamie's line: LiveKit Agents send that in time with the audio. So it goes
/// under Jamie's line being spoken when it came; else it waits at the end,
/// then goes under the next line if that is Jamie's, or stays above it if it
/// is the member's. It never moves.
sealed class TalkCardAnchor {
    /// Under this line of Jamie's.
    data class Under(val id: String) : TalkCardAnchor()
    /// Decided by the first line after this one (null: the call's first line).
    data class Next(val after: String?) : TalkCardAnchor()

    /// Where the card goes: how many of the transcript's lines are above it.
    /// A card whose lines the cap dropped goes at the top, where they were.
    fun linesAbove(transcript: TalkTranscript): Int {
        val lines = transcript.lines
        when (this) {
            is Under -> return lines.indexOfFirst { it.id == id }.let { if (it >= 0) it + 1 else 0 }
            is Next -> {
                val at = lines.indexOfFirst { it.id == after }
                // Past the cap: the first kept when the anchor is the newest
                // line dropped (null, before any drop: the call's first line), else gone too.
                val next = when {
                    at >= 0 -> at + 1
                    after == transcript.lastDropped -> 0
                    else -> return 0
                }
                if (next >= lines.size) return lines.size
                return if (lines[next].speaker == TalkLine.Speaker.AGENT) next + 1 else next
            }
        }
    }

    companion object {
        /// A card's anchor as it comes, from the live text so far.
        fun of(transcript: TalkTranscript): TalkCardAnchor {
            val last = transcript.lines.lastOrNull()
            return if (last != null && last.speaker == TalkLine.Speaker.AGENT && !last.isFinal) Under(last.id) else Next(last?.id)
        }
    }
}
