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

/// One account's call server: the presence feed (`/ws`), as the official
/// apps use it (`GET /api/connection-details`, the ticket request, is not
/// ported — this round has no in-app join, so nothing here ever needs a
/// LiveKit token). Ported from PeelCore's `CallServer` actor, trimmed to
/// presence alone. Not authenticated: the room name is the only secret.
///
/// Unlike the Swift actor (`URLSessionWebSocketTask`'s own explicit
/// 30-second ping loop), OkHttp's own `pingInterval` does the keep-alive
/// and the dead-connection detection in one place — nothing here re-sends
/// pings by hand.
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

        /// An identity is matched exactly (a leave names it), so one with a
        /// control character is refused rather than cleaned.
        private fun isIdentity(identity: String): Boolean =
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
