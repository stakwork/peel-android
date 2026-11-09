package com.sphinx.peel.engine

import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/// Someone on a call, as the call server's presence feed reports them.
/// Everything here is what the joiner sent: names are self-declared and
/// never an identity, and the photo is kept only when it looks like a
/// real public https URL. Ported from PeelCore's `CallParticipant`.
data class CallParticipant(val identity: String, val nickname: String, val avatarUrl: String?)

/// Who is on a call, as far as the call server says — PeelCore's own
/// `CallPresence`.
sealed class CallPresence {
    object Unknown : CallPresence()
    data class Live(val people: List<CallParticipant>) : CallPresence()
    object Ended : CallPresence()

    fun people(): List<CallParticipant>? = (this as? Live)?.people
}

/// What a LiveKit client needs to join one call: the media server's address
/// and an access token for the room. Lives only as long as one join —
/// PeelCore's own `CallTicket`: never stored, logged, or shown (its own
/// `toString`/`description` leaves `token` out, same reason).
data class CallTicket(
    val serverUrl: String,
    val token: String,
    val room: String,
    val identity: String,
    val participantName: String,
    val expiresAtMs: Long,
    val roomAdmin: Boolean,
) {
    override fun toString(): String =
        "CallTicket($serverUrl, room $room, identity $identity, name $participantName, token not shown)"
}

/// One account's call server: the ticket request (`GET
/// /api/connection-details`) and the presence feed (`/ws`), as the official
/// apps use them. Ported from PeelCore's `CallServer` actor. Not
/// authenticated: the room name is the only secret. The ticket request goes
/// through the same `OkHttpClient` the presence socket uses — no cache, no
/// cookies to worry about either (`OkHttpClient`'s own defaults have
/// neither unless explicitly added, unlike `URLSession`'s own ephemeral-
/// session requirement, which exists specifically to turn a default *on*
/// that's already off here).
///
/// Unlike the Swift actor (`URLSessionWebSocketTask`'s own explicit
/// 30-second ping loop), OkHttp's own `pingInterval` does the keep-alive
/// and the dead-connection detection for the presence socket in one place —
/// nothing here re-sends pings by hand.
class CallServer(val host: String) {
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // a WebSocket has no fixed read timeout
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class RoomState {
        val listeners = mutableMapOf<String, (CallPresence) -> Unit>()
        var presence: CallPresence = CallPresence.Unknown
    }

    private val lock = Any()
    private val rooms = mutableMapOf<String, RoomState>()
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var failures = 0
    private var closed = false
    private val askingAgain = mutableSetOf<String>()

    // MARK: - The ticket

    sealed class TicketException(message: String) : Exception(message) {
        object Unreachable : TicketException("the call server did not answer")
        class Refused(val status: Int) : TicketException("the call server refused the call ticket (HTTP $status)")
        object UnreadableReply : TicketException("the call server's reply was unreadable")
        class MediaHostNotAllowed(val host: String?) :
            TicketException("the call server named a media server Peel does not know (${host ?: "an unreadable address"})")
        object MalformedToken : TicketException("the call server's token was unreadable")
        object TokenForAnotherRoom : TicketException("the call server's token is for another room")
        object TokenExpired : TicketException("the call server's token has already expired")
    }

    /// Asks for a token to join `link`'s room as `participantName`, with the
    /// account's photo passed on — PeelCore's own `CallServer.ticket(for:
    /// participantName:photoURL:)`.
    suspend fun ticket(link: CallLink, participantName: String, photoUrl: String?): CallTicket {
        val url = ticketUrl(link, participantName, photoUrl) ?: throw IllegalStateException("could not build the call ticket request")
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        val response = try {
            withContext(Dispatchers.IO) { client.newCall(request).execute() }
        } catch (e: Exception) {
            throw TicketException.Unreachable
        }
        response.use {
            if (!it.isSuccessful) throw TicketException.Refused(it.code)
            val body = try {
                it.body?.string()
            } catch (e: Exception) {
                null
            } ?: throw TicketException.UnreadableReply
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw TicketException.UnreadableReply
            }
            val serverUrlText = json.optString("serverUrl").takeIf { s -> s.isNotEmpty() } ?: throw TicketException.UnreadableReply
            val participantToken = json.optString("participantToken").takeIf { s -> s.isNotEmpty() } ?: throw TicketException.UnreadableReply
            return buildTicket(serverUrlText, participantToken, link.room, participantName)
        }
    }

    /// `GET /api/connection-details` with every value percent-encoded to
    /// unreserved characters, as the official apps' `urlEncode` does, so a
    /// `+` arrives as `+` and never as a space.
    private fun ticketUrl(link: CallLink, participantName: String, photoUrl: String?): String? {
        val (scheme, callHost, port) = CallLink.baseURL(host) ?: return null
        val authority = if (port == CallLink.defaultPort(scheme)) callHost else "$callHost:$port"
        val items = mutableListOf("roomName=${CallLink.encode(link.room)}", "participantName=${CallLink.encode(participantName)}")
        if (photoUrl != null) metadata(photoUrl)?.let { items.add("metadata=${CallLink.encode(it)}") }
        link.callKey?.let { items.add("callKey=${CallLink.encode(it)}") }
        link.hiveToken?.let { items.add("hiveToken=${CallLink.encode(it)}") }
        return "$scheme://$authority/api/connection-details?${items.joinToString("&")}"
    }

    /// The LiveKit media servers each call server may name besides itself,
    /// on port 443, by exact host, never a suffix (`*.livekit.cloud` would
    /// admit any LiveKit tenant) — PeelCore's own `mediaHosts`. The official
    /// apps' call server names `livekit.sphinx.chat`.
    private val mediaHosts: Map<String, List<String>> = mapOf("chat.sphinx.chat" to listOf("livekit.sphinx.chat"))

    /// The ticket in a reply, checked: the media server must be `wss://`
    /// (or `https://`, read as it) on the call server's own host or one
    /// recorded for it in [mediaHosts] (`ws://` only when the call server is
    /// this device), and the token must be for `room` and not yet expired.
    /// Its signature is the media server's to check.
    internal fun buildTicket(serverUrlText: String, token: String, room: String, participantName: String, now: Long = System.currentTimeMillis()): CallTicket {
        val serverUrl = mediaUrl(serverUrlText)
        val claims = claimsOf(token)
        if (claims.room != room) throw TicketException.TokenForAnotherRoom
        val identity = claims.sub?.takeIf { isIdentity(it) } ?: throw TicketException.MalformedToken
        val expMs = claims.expMs ?: throw TicketException.MalformedToken
        if (expMs <= now) throw TicketException.TokenExpired
        return CallTicket(serverUrl, token, room, identity, participantName, expMs, claims.roomAdmin)
    }

    internal fun mediaUrl(text: String): String {
        if (text.toByteArray(Charsets.UTF_8).size > 2048) throw TicketException.MediaHostNotAllowed(null)
        val uri = try {
            java.net.URI(text)
        } catch (e: Exception) {
            throw TicketException.MediaHostNotAllowed(null)
        }
        val givenScheme = uri.scheme?.lowercase() ?: throw TicketException.MediaHostNotAllowed(null)
        val mediaHost = uri.host?.lowercase() ?: throw TicketException.MediaHostNotAllowed(null)
        if (uri.userInfo != null || uri.rawQuery != null || uri.fragment != null) throw TicketException.MediaHostNotAllowed(null)
        // LiveKit reads `https://` as `wss://` and `http://` as `ws://`;
        // the official call server has answered with `https://` since the
        // same date PeelCore's own comment on this names.
        val scheme = when (givenScheme) { "https" -> "wss"; "http" -> "ws"; else -> givenScheme }
        val mediaPort = if (uri.port == -1) CallLink.defaultPort(if (scheme == "wss") "https" else "http") else uri.port
        val named = endpoint(mediaHost, mediaPort, secure = scheme != "ws")
        val (ownScheme, ownHost, ownPort) = CallLink.baseURL(host) ?: throw TicketException.MediaHostNotAllowed(null)
        val loopback = ownScheme == "http"
        // Release builds accept only wss/https, even for a loopback call server.
        if (loopback && !NetworkPolicy.plainLoopbackAllowed(com.sphinx.peel.BuildConfig.DEBUG)) throw TicketException.MediaHostNotAllowed(null)
        val allowed = listOf(endpoint(ownHost, ownPort, secure = !loopback)) +
            // Only the call server itself on its default port: not another one on the same host.
            (if (!host.contains(':')) mediaHosts[ownHost] ?: emptyList() else emptyList()).map { endpoint(it, null, secure = true) }
        val plainHostOrNull = named.takeIf { it.all { c -> c.isLetterOrDigit() || c in ".-:" } }
        if (!NetworkPolicy.mediaSchemeAllowed(scheme, loopback, com.sphinx.peel.BuildConfig.DEBUG) || named !in allowed) throw TicketException.MediaHostNotAllowed(plainHostOrNull)
        val authority = if (mediaPort == CallLink.defaultPort(if (scheme == "wss") "https" else "http")) mediaHost else "$mediaHost:$mediaPort"
        return "$scheme://$authority"
    }

    /// `host:port`, with the scheme's default port written out.
    private fun endpoint(endpointHost: String, port: Int?, secure: Boolean): String {
        val p = port ?: (if (secure) 443 else 80)
        return if (endpointHost.contains(':') && port == null) endpointHost else "$endpointHost:$p"
    }

    internal data class Claims(val sub: String?, val expMs: Long?, val room: String?, val roomAdmin: Boolean)

    /// The parts of a LiveKit token this port reads, decoded without
    /// checking the signature (Peel has no key) — a bare base64url JWT
    /// payload decode, not a general JWT library: this account did not
    /// issue the token, the call server did, and nothing here trusts it
    /// for anything beyond "which room, whose identity, until when."
    internal fun claimsOf(token: String): Claims = parseClaims(token)

    /// An identity is matched exactly (a leave names it), so one with a
    /// control character is refused rather than cleaned.
    private fun isIdentity(identity: String): Boolean =
        identity.length in 1..256 && identity == identity.filter { !it.isISOControl() }

    /// `{"profilePictureUrl":"<url>"}`, the only metadata Peel sends (no
    /// pubkey: others could read it but not check it).
    private fun metadata(photoUrl: String): String? {
        val json = JSONObject()
        json.put("profilePictureUrl", photoUrl)
        return json.toString()
    }

    // MARK: - Presence

    /// Who is on the call in `room`, now and as it changes, starting with
    /// [CallPresence.Unknown]. The first collector for a room subscribes on
    /// the one socket (opening it if need be); the last one to stop
    /// unsubscribes, and the socket closes when no room is left — PeelCore's
    /// own `presence(room:)`.
    fun presence(room: String): Flow<CallPresence> = callbackFlow {
        if (!CallLink.isRoom(room)) {
            close()
            return@callbackFlow
        }
        val id = UUID.randomUUID().toString()
        var subscribeNow: WebSocket? = null
        var shouldConnect = false
        synchronized(lock) {
            if (closed) return@synchronized
            val entry = rooms.getOrPut(room) { RoomState() }
            val isNew = entry.listeners.isEmpty()
            entry.listeners[id] = { presence -> trySend(presence) }
            trySend(entry.presence)
            if (isNew) {
                val current = socket
                if (current != null) subscribeNow = current else if (reconnectJob == null) shouldConnect = true
            }
        }
        subscribeNow?.let { send(it, mapOf("action" to "subscribe", "roomName" to room)) }
        if (shouldConnect) connect()
        awaitClose { stopListening(id, room) }
    }

    /// Closes the socket and ends every stream. The server drops the
    /// subscriptions with the connection.
    fun close() {
        val toFinish: List<(CallPresence) -> Unit>
        synchronized(lock) {
            if (closed) return
            closed = true
            toFinish = rooms.values.flatMap { it.listeners.values }
            rooms.clear()
        }
        disconnect()
        for (listener in toFinish) listener(CallPresence.Ended)
    }

    private fun stopListening(id: String, room: String) {
        var socketToUnsubscribe: WebSocket? = null
        var shouldDisconnect = false
        synchronized(lock) {
            val entry = rooms[room] ?: return
            if (entry.listeners.remove(id) == null || entry.listeners.isNotEmpty()) return
            rooms.remove(room)
            val current = socket ?: return
            socketToUnsubscribe = current
            if (rooms.isEmpty()) shouldDisconnect = true
        }
        socketToUnsubscribe?.let { send(it, mapOf("action" to "unsubscribe", "roomName" to room)) }
        if (shouldDisconnect) disconnect()
    }

    private fun connect() {
        synchronized(lock) { reconnectJob = null }
        val roomNames: List<String>
        synchronized(lock) {
            if (closed || socket != null || rooms.isEmpty()) return
            roomNames = rooms.keys.toList()
        }
        val scheme = if (ServerAddress.isLoopback(host.substringBefore(":"))) "ws" else "wss"
        val url = "$scheme://$host/ws"
        val request = Request.Builder().url(url).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(lock) { failures = 0 }
                for (room in roomNames) send(webSocket, mapOf("action" to "subscribe", "roomName" to room))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(webSocket, text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                dropped(webSocket)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                dropped(webSocket)
            }
        }
        val newSocket = client.newWebSocket(request, listener)
        synchronized(lock) { socket = newSocket }
    }

    private fun handleMessage(webSocket: WebSocket, text: String) {
        synchronized(lock) { if (socket !== webSocket) return }
        val update = parseUpdate(text) ?: return
        val current: CallPresence
        synchronized(lock) { current = rooms[update.room]?.presence ?: return }
        val next = applying(update.change, current)
        set(update.room, next)
        if (update.change is Change.Joined && update.change.participant.nickname.isEmpty() && next != CallPresence.Unknown) {
            askAgain(update.room, webSocket)
        }
    }

    /// The room's list again in two seconds, for someone who joined with no
    /// name yet, as the official Mac app does: a second subscribe answers
    /// with `current_participants`.
    private fun askAgain(room: String, webSocket: WebSocket) {
        synchronized(lock) { if (!askingAgain.add(room)) return }
        scope.launch {
            delay(2000)
            synchronized(lock) { askingAgain.remove(room) }
            val stillWatched: Boolean
            synchronized(lock) { stillWatched = socket === webSocket && rooms.containsKey(room) }
            if (stillWatched) send(webSocket, mapOf("action" to "subscribe", "roomName" to room))
        }
    }

    /// Every room is unknown until the socket is back; it reconnects after
    /// 1, 2, 4 … up to 30 seconds and subscribes to them all again.
    private fun dropped(webSocket: WebSocket) {
        val roomNames: List<String>
        var shouldReconnect = false
        var delaySeconds = 0
        synchronized(lock) {
            if (socket !== webSocket) return
            socket = null
            roomNames = rooms.keys.toList()
            for (room in roomNames) rooms[room]?.presence = CallPresence.Unknown
            if (!closed && rooms.isNotEmpty()) {
                delaySeconds = minOf(30, 1 shl minOf(failures, 5))
                failures += 1
                shouldReconnect = true
            }
        }
        for (room in roomNames) {
            val listeners: List<(CallPresence) -> Unit>
            synchronized(lock) { listeners = rooms[room]?.listeners?.values?.toList() ?: emptyList() }
            for (listener in listeners) listener(CallPresence.Unknown)
        }
        if (shouldReconnect) {
            synchronized(lock) {
                reconnectJob = scope.launch {
                    delay(delaySeconds * 1000L)
                    connect()
                }
            }
        }
    }

    private fun disconnect() {
        val job: Job?
        val currentSocket: WebSocket?
        synchronized(lock) {
            job = reconnectJob
            reconnectJob = null
            currentSocket = socket
            socket = null
        }
        job?.cancel()
        currentSocket?.close(1000, null)
    }

    private fun send(webSocket: WebSocket, fields: Map<String, String>) {
        val json = JSONObject()
        for ((key, value) in fields) json.put(key, value)
        webSocket.send(json.toString())
    }

    private fun set(room: String, presence: CallPresence) {
        val listeners: List<(CallPresence) -> Unit>
        synchronized(lock) {
            val entry = rooms[room] ?: return
            if (entry.presence == presence) return
            entry.presence = presence
            listeners = entry.listeners.values.toList()
        }
        for (listener in listeners) listener(presence)
    }

    // MARK: - Reading the feed

    internal sealed class Change {
        data class Current(val participants: List<CallParticipant>) : Change()
        data class Joined(val participant: CallParticipant) : Change()
        data class Left(val identity: String) : Change()
        object Finished : Change()
    }

    internal data class Update(val room: String, val change: Change)

    /// Visibility relaxed from `private` on the pure parsing/merge functions
    /// below (not on the stateful socket management above) so this round's
    /// own `CallServerTest` can exercise the presence feed's actual parsing
    /// rules directly, the same way `PreferencesFile`'s pure functions are
    /// tested, without needing a real WebSocket.
    companion object {
        private const val MAX_PARTICIPANTS = 500
        private const val MAX_NAME = 100

        /// [claimsOf] without a server: Talk (`Talk.kt`) reads a home's token the same way.
        internal fun parseClaims(token: String): Claims {
            val parts = token.split(".")
            if (parts.size < 2) throw TicketException.MalformedToken
            val json = try {
                JSONObject(String(base64UrlDecode(parts[1]), Charsets.UTF_8))
            } catch (e: Exception) {
                throw TicketException.MalformedToken
            }
            val sub = json.optString("sub").takeIf { it.isNotEmpty() }
            val exp = if (json.has("exp")) json.optDouble("exp") else null
            val expMs = exp?.let { (it * 1000).toLong() }
            val video = json.optJSONObject("video")
            val room = video?.optString("room")?.takeIf { it.isNotEmpty() }
            val roomAdmin = video?.optBoolean("roomAdmin", false) ?: false
            return Claims(sub, expMs, room, roomAdmin)
        }

        private fun base64UrlDecode(text: String): ByteArray {
            val padded = text.replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }
            return java.util.Base64.getDecoder().decode(padded)
        }

        /// One message of the presence feed, as the official Mac app reads
        /// it: `current_participants`, `participant_joined` (the
        /// participant nested or at the top level), `participant_left` and
        /// `room_finished`, each naming its room. Null for anything else.
        internal fun parseUpdate(text: String): Update? {
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                return null
            }
            val type = json.optString("type", "")
            val room = json.optString("roomName", "")
            if (!CallLink.isRoom(room)) return null
            return when (type) {
                "current_participants" -> {
                    val array = json.optJSONArray("participants") ?: JSONArray()
                    val people = (0 until minOf(array.length(), MAX_PARTICIPANTS))
                        .mapNotNull { participant(array.optJSONObject(it)) }
                    Update(room, Change.Current(people))
                }
                "participant_joined" -> {
                    val fields = json.optJSONObject("participant") ?: json
                    participant(fields)?.let { Update(room, Change.Joined(it)) }
                }
                "participant_left" -> {
                    val identity = json.optString("identity", "")
                    if (isIdentity(identity)) Update(room, Change.Left(identity)) else null
                }
                "room_finished" -> Update(room, Change.Finished)
                else -> null
            }
        }

        internal fun participant(fields: JSONObject?): CallParticipant? {
            fields ?: return null
            val identity = fields.optString("identity", "")
            if (!isIdentity(identity)) return null
            val rawNickname = fields.optString("nickname", "")
            val nickname = withoutControlCharacters(rawNickname).trim().take(MAX_NAME)
            val rawAvatar = fields.optString("avatarUrl", "").takeIf { it.isNotEmpty() }
            val avatarUrl = rawAvatar?.takeIf { it.length <= 2048 && isPublicHttps(it) }
            return CallParticipant(identity, nickname, avatarUrl)
        }

        /// Someone in the LiveKit room itself, from the name and metadata
        /// (`{"profilePictureUrl":…}`) they joined with — PeelCore's own
        /// `CallParticipant.init(identity:name:metadata:)`: the exact same
        /// sanitizing [participant] already does for the presence feed,
        /// reused rather than re-implemented, so a name is trimmed/control-
        /// stripped/length-capped and a photo is public-https-checked the
        /// same way regardless of which of the two feeds it arrived on.
        fun fromLiveKitRoom(identity: String, name: String?, metadata: String?): CallParticipant? {
            val fields = JSONObject().put("identity", identity)
            if (name != null) fields.put("nickname", name)
            if (metadata != null && metadata.toByteArray(Charsets.UTF_8).size <= 4096) {
                try {
                    JSONObject(metadata).optString("profilePictureUrl").takeIf { it.isNotEmpty() }
                        ?.let { fields.put("avatarUrl", it) }
                } catch (e: Exception) {
                    // Not JSON, or not an object: no photo, same as none sent.
                }
            }
            return participant(fields)
        }

        /// An identity is matched exactly (a leave names it), so one with a
        /// control character is refused rather than cleaned.
        internal fun isIdentity(identity: String): Boolean =
            identity.length in 1..256 && identity == withoutControlCharacters(identity)

        private fun withoutControlCharacters(text: String): String = text.filter { !it.isISOControl() }

        /// https, a real host, not a loopback/private address a server-side
        /// fetch could be tricked into reaching.
        private fun isPublicHttps(url: String): Boolean {
            val parsed = try {
                java.net.URI(url)
            } catch (e: Exception) {
                return false
            }
            val host = parsed.host?.lowercase() ?: return false
            return parsed.scheme?.lowercase() == "https" && !ServerAddress.isLoopback(host) &&
                host != "0.0.0.0" && !host.startsWith("10.") && !host.startsWith("192.168.") &&
                !host.startsWith("169.254.") && !Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host)
        }

        /// What a room's presence becomes after one message of the feed. A
        /// participant with no name is left out, as the official Mac app
        /// leaves them out: the room's recorder or agent, or someone whose
        /// name LiveKit has not passed on yet (`askAgain` asks again for
        /// those).
        internal fun applying(change: Change, presence: CallPresence): CallPresence = when (change) {
            is Change.Current -> {
                // Someone unnamed here keeps the name they had: earlier in
                // this list (a second session), or before it (their client
                // reconnected).
                val known = ((presence.people() ?: emptyList()) + change.participants.filter { it.nickname.isNotEmpty() })
                    .associateBy { it.identity }
                val seen = mutableSetOf<String>()
                val resolved = change.participants.asReversed().mapNotNull { person ->
                    if (!seen.add(person.identity)) null else if (person.nickname.isEmpty()) known[person.identity] else person
                }
                CallPresence.Live(resolved.asReversed())
            }
            is Change.Joined -> when {
                change.participant.nickname.isEmpty() -> presence
                presence is CallPresence.Live -> {
                    val without = presence.people.filter { it.identity != change.participant.identity }
                    CallPresence.Live(if (without.size < MAX_PARTICIPANTS) without + change.participant else without)
                }
                presence is CallPresence.Ended -> CallPresence.Live(listOf(change.participant))
                else -> presence
            }
            is Change.Left -> if (presence is CallPresence.Live) {
                CallPresence.Live(presence.people.filter { it.identity != change.identity })
            } else {
                presence
            }
            Change.Finished -> CallPresence.Ended
        }
    }
}
