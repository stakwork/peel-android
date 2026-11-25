package com.sphinx.peel.engine

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

// Jamie from a box in a group call (stadeum plans/group-calls.md §2, §5, §7 and plans/call-canvas.md §4;
// docs/phases/56-box-group-calls.md). A box's own voice service joins a Peel call, end-to-end encrypted with the call's key, and
// hears and speaks with the box's own models. A chat's call menu offers each linked box ([BoxLink]) whose strut says it can join a
// group call now on the call server's LiveKit (`GET /voice/calls`). Choosing one makes a room and a key, asks the box in first
// (`POST /voice/calls`), then posts the link with the key after `#` and joins encrypted. Anyone linked to the box that is in a call
// joins with their own ticket from it (`POST /voice/calls/ticket`), so what they ask Jamie runs as them. Pure but for the HTTP
// (behind [BoxHttp]), JVM-tested (`BoxCallsTest`). Not ported: Hive's orgs in the call menu (Android never had them:
// `HiveClient.startCall` is kept unused, docs/phases/26-talk-to-jamie.md).

/// What a box says of its group calls (`GET /voice/calls`): whether its Jamie can join one now (true once strut's streaming
/// recognizer has answered), the LiveKit server it joins, and whether it can record a call on the canvas ([record]: false when no
/// package says what a call records onto, plans/call-canvas.md §8; null from a voice service before that, which always could).
data class BoxCallStatus(val group: Boolean, val livekit: String, val record: Boolean? = null) {
    /// Whether "Record on the canvas" is offered with this box: unless it says it can't.
    val records: Boolean get() = record != false

    companion object {
        /// A box that can't join group calls: a strut from before the routes, or one with no voice service.
        val NONE = BoxCallStatus(false, "")

        /// `{ group, livekit, record? }`; null when it is not that. A `record` that is not true or false is as if absent.
        fun parse(body: String): BoxCallStatus? {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
            val group = json.opt("group") as? Boolean ?: return null
            val livekit = json.opt("livekit") as? String ?: return null
            return BoxCallStatus(group, livekit, json.opt("record") as? Boolean)
        }
    }
}

/// The person's own way into a call a box's Jamie is in (`POST /voice/calls/ticket`, group-calls.md §7): the box minted the token
/// under an identity it records against the person for the call, so a turn from it runs as them. In memory for one join, never
/// stored, logged or described with its token.
data class BoxTicket(
    /// The box's address.
    val box: String,
    /// The LiveKit server the box joined.
    val url: String,
    val room: String,
    /// The LiveKit access token (a JWT) for [room].
    val token: String,
    /// `box_` and 32 hex.
    val identity: String,
) {
    override fun toString() = "BoxTicket(${Seller.host(box) ?: box}, room $room, identity $identity, token not shown)"

    /// As a ticket into the call on the call server [callServer]: the LiveKit it names must be one of the call server's
    /// ([CallServer.ticketOn], where everyone else joins), and the token for [room], for [identity] and not yet expired. Throws
    /// [CallServer.TicketException] for anything else.
    fun callTicket(callServer: String, name: String, now: Long = System.currentTimeMillis()): CallTicket {
        val ticket = CallServer.ticketOn(callServer, url, token, room, name, now)
        if (ticket.identity != identity) throw CallServer.TicketException.MalformedToken
        return ticket
    }

    companion object {
        /// What the box's voice service mints: meet's identities always hold `__`, so nobody joining there can take one.
        private val IDENTITY = Regex("^box_[0-9a-f]{32}$")

        /// `{ url, room, token, identity }` for [room] from [box]; null when it is not that.
        fun parse(body: String, room: String, box: String): BoxTicket? {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
            fun text(name: String) = json.opt(name) as? String
            val url = text("url")?.takeIf { it.isNotEmpty() } ?: return null
            if (text("room") != room) return null
            val token = text("token")?.takeIf { it.length in 1..4096 && it.none(Char::isWhitespace) } ?: return null
            val identity = text("identity")?.takeIf { IDENTITY.matches(it) } ?: return null
            return BoxTicket(box, url, room, token, identity)
        }
    }
}

/// strut's group-call routes on one box (strut#881, forwarded to its voice service's `/calls`), with the token Peel holds there
/// ([BoxLink.token]): strut takes the person from it, as the starter or as whom a ticket is minted for.
class BoxCallsClient(private val box: BoxLink, private val http: BoxHttp) {
    /// The box as its messages name it.
    private val name: String get() = box.shortName

    /// Whether the box's Jamie can join a group call now, and on which LiveKit. A strut without the route (`404`), or with no
    /// voice service (`503`), can't.
    suspend fun status(): BoxCallStatus {
        val r = http.call("GET", "${box.url}/voice/calls", null, box.token, LIMIT, STATUS_SECONDS)
        if (r.status == 404 || r.status == 503) return BoxCallStatus.NONE
        refused(r, "say whether Jamie can join a call")?.let { throw it }
        return BoxCallStatus.parse(r.body) ?: throw BoxException("$name's answer about calls could not be read.")
    }

    /// Brings the box's Jamie into [room] with the call's [key], recording it on the canvas with [record]
    /// (`POST /voice/calls { mode: "group", room, key, record? }`). Jamie's identity in the room. Throws why not, in words.
    suspend fun join(room: String, key: String, record: Boolean): String {
        val body = JSONObject().put("mode", "group").put("room", room).put("key", key)
        if (record) body.put("record", true)
        val r = http.call("POST", "${box.url}/voice/calls", body.toString(), box.token, LIMIT, JOIN_SECONDS)
        when (r.status) {
            409 -> throw BoxException("Jamie from $name is already in this call.")
            503 -> throw BoxException("Jamie from $name can't join a call right now" + (said(r.body)?.let { ": $it." } ?: "."))
        }
        refused(r, "bring Jamie into the call")?.let { throw it }
        val json = runCatching { JSONObject(r.body) }.getOrNull() ?: throw BoxException("$name's answer about the call could not be read.")
        // Never a call in the clear: a voice service that did not take the key is refused.
        if (json.opt("encrypted") != true) throw BoxException("$name can't join an encrypted call yet: its voice service needs an update.")
        return (json.opt("agent") as? String)?.takeIf { CallServer.isIdentity(it) }
            ?: throw BoxException("$name's answer about the call could not be read.")
    }

    /// The person's own ticket into [room], seen there as [name]; null when the box's Jamie is not in that call (`404`).
    suspend fun ticket(room: String, name: String): BoxTicket? {
        val body = JSONObject().put("room", room).put("name", name).toString()
        val r = http.call("POST", "${box.url}/voice/calls/ticket", body, box.token, LIMIT, TICKET_SECONDS)
        if (r.status == 404) return null
        refused(r, "let you into the call")?.let { throw it }
        return BoxTicket.parse(r.body, room, box.url) ?: throw BoxException("$name's answer about the call could not be read.")
    }

    /// Null for a 200; else strut's refusal in words, the token's own ([BoxTokenRefused]) for a 401, which
    /// [BoxesModel.asMember] renews once.
    private fun refused(r: BoxResponse, couldNot: String): BoxException? = when (r.status) {
        200 -> null
        401 -> BoxTokenRefused(name)
        429 -> BoxException("$name is busy. Try again in a minute.")
        else -> BoxException("$name could not $couldNot: ${said(r.body) ?: "HTTP ${r.status}"}.")
    }

    companion object {
        /// The most an answer may be: a few short strings and a JWT.
        const val LIMIT = 64 * 1024
        const val STATUS_SECONDS = 20L
        /// strut gives the voice service 15 s to answer, and Jamie joins the room before it does.
        const val JOIN_SECONDS = 30L
        /// Short: joining any encrypted call asks every linked box first.
        const val TICKET_SECONDS = 10L

        private fun said(body: String): String? =
            (runCatching { JSONObject(body) }.getOrNull()?.opt("error") as? String)?.let { TextLine.clean(it, 200) }?.ifEmpty { null }
    }
}

/// The open account's boxes in group calls ([BoxCallsClient]): which can bring Jamie into a call now ([offered]), the box and the
/// "Record on the canvas" choice in each chat's call menu (kept while Peel runs, not stored), and the person's own ticket from a box
/// that is in a call. Observable: its state is Compose state. Its work runs on [dispatcher], the main thread in the app, as
/// [BoxesModel]'s does.
class BoxCalls(
    private val boxes: BoxesModel,
    private val clientFor: (BoxLink) -> BoxCallsClient = { BoxCallsClient(it, sharedHttp) },
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /// One box's last answer: what it said, or null when it could not be asked.
    private data class Read(val status: BoxCallStatus?, val atMs: Long)

    /// Each box's last answer, by [key]: its address and its member.
    private var reads: Map<String, Read> by mutableStateOf(emptyMap())
    /// Whether the boxes are being asked.
    var reading: Boolean by mutableStateOf(false); private set
    /// The box picked for calls in each chat, by its address; none for No Jamie.
    var picks: Map<String, String> by mutableStateOf(emptyMap()); private set
    /// The chats whose calls with a box's Jamie are recorded on the canvas.
    var recorded: Set<String> by mutableStateOf(emptySet()); private set

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var current: Deferred<Unit>? = null

    /// The boxes that can bring Jamie into a call on [callServer] now, in the order they were linked.
    fun offered(callServer: String): List<BoxLink> = boxes.links.filter { offers(reads[key(it)]?.status, callServer) }

    /// Asks every linked box whether its Jamie can join a call, unless every one was asked in the last [ifOlderThanMs]; one ask at
    /// a time, joined. A box that can't be asked is not offered.
    suspend fun read(ifOlderThanMs: Long = 0) {
        val now = nowMs()
        if (ifOlderThanMs > 0 && boxes.links.all { b -> reads[key(b)]?.let { now - it.atMs < ifOlderThanMs } == true }) return
        val running = current?.takeIf { it.isActive } ?: scope.async { readAll() }.also { current = it }
        running.await()
    }

    private suspend fun readAll() {
        reading = true
        try {
            val links = boxes.links
            val answers = coroutineScope {
                links.map { box ->
                    async {
                        try {
                            Read(boxes.asMember(box) { clientFor(it).status() }, nowMs())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Read(null, nowMs())
                        }
                    }
                }.awaitAll()
            }
            reads = links.indices.associate { key(links[it]) to answers[it] }
        } finally {
            reading = false
        }
    }

    /// The box a call in [chat] brings Jamie from: the one picked there, while it is still offered on [callServer]; else none.
    fun pick(chat: String, callServer: String): BoxLink? = picks[chat]?.let { url -> offered(callServer).firstOrNull { it.url == url } }

    /// Calls in [chat] bring Jamie from [box], or none. Another box starts with "Record on the canvas" off.
    fun choose(chat: String, box: BoxLink?) {
        if (picks[chat] != box?.url) recorded = recorded - chat
        picks = if (box == null) picks - chat else picks + (chat to box.url)
    }

    /// Whether [box] can record a call on the canvas, as it last said ([BoxCallStatus.records]): "Record on the canvas" is offered
    /// with it only then.
    fun recordable(box: BoxLink): Boolean = reads[key(box)]?.status?.records ?: false

    /// Whether calls with Jamie in [chat] are recorded on the canvas: chosen there, with a box that can.
    fun records(chat: String): Boolean =
        chat in recorded && picks[chat]?.let { url -> boxes.links.firstOrNull { it.url == url } }?.let(::recordable) == true

    fun record(chat: String, on: Boolean) {
        recorded = if (on) recorded + chat else recorded - chat
    }

    /// Brings Jamie from [box] into [link]'s call, before the link is posted (group-calls.md §5): the box's token makes the room.
    /// [link] must be encrypted; a box never joins a call in the clear. Jamie's identity there; throws why not, in words.
    suspend fun bring(box: BoxLink, link: CallLink, record: Boolean): String {
        val key = link.encryptionKey ?: throw BoxException("Peel brings Jamie from ${box.shortName} only into an encrypted call.")
        return boxes.asMember(box) { clientFor(it).join(link.room, key, record) }
    }

    /// The person's own ticket into [link]'s call as [name], from the linked box whose Jamie is in it (group-calls.md §7): every
    /// box is asked at once, and the first in link order that is in the call answers. Null when none is, one could not be asked, or
    /// [link] is not encrypted (no box's Jamie is in a call in the clear, so no box is told of one).
    suspend fun ticket(link: CallLink, name: String): BoxTicket? {
        if (!link.isEncrypted) return null
        val links = boxes.links
        if (links.isEmpty()) return null
        val shown = ticketName(name)
        val answers = coroutineScope {
            links.map { box ->
                async {
                    try {
                        boxes.asMember(box) { clientFor(it).ticket(link.room, shown) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll()
        }
        return answers.firstOrNull { it != null }
    }

    companion object {
        /// One HTTP client for every box's calls.
        private val sharedHttp: BoxHttp by lazy { OkHttpBox() }

        /// A box's answers are its member's: another account linked to the same box has its own.
        private fun key(box: BoxLink) = "${box.url} ${box.actor}"

        /// Whether a box that said [status] is offered for a call on [callServer]: its Jamie can join a group call now, on a
        /// LiveKit of that call server's (the one the call's other people join; group-calls.md §2).
        fun offers(status: BoxCallStatus?, callServer: String): Boolean =
            status != null && status.group && CallServer.isMediaServer(callServer, status.livekit)

        /// What the others see the person as on a box's ticket: one line the voice service takes (1 to 128 UTF-16 units), so at
        /// most 64 characters, any of which may take two.
        fun ticketName(name: String): String = TextLine.clean(name, 64).trim().ifEmpty { "Peel user" }
    }
}
