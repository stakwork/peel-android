package com.sphinx.peel.engine

import org.json.JSONObject

/// One line of the in-call chat side panel.
data class CallChatMessage(
    val id: String,
    /// The LiveKit identity that sent it ([CallChat.sender]); for your own lines, your ticket's.
    val identity: String,
    /// Whose line it is: the sender's name, or the person Jamie relays.
    val name: String,
    val text: String,
    val timestampMs: Long,
    val isYou: Boolean,
)

/// What a chat packet's payload says ([CallChat.decode]). [sender] is a name
/// the payload claims, read only from Jamie ([CallChat.sender]).
data class CallChatLine(val id: String, val timestampMs: Long, val text: String, val sender: String? = null)

/// Someone in a call's LiveKit room as LiveKit has them: the identity, and
/// the name, metadata and attributes they joined with (what
/// [CallServer.fromLiveKitRoom] reads).
data class CallRoomMember(
    val identity: String,
    val name: String?,
    val metadata: String?,
    val attributes: Map<String, String> = emptyMap(),
) {
    /// The call's voice agent (sphinx-voice): named Jamie, with the `tts`
    /// attribute it sets once it joins (PeelCore's `CallChatWire.isJamie`).
    /// Without the attribute it is a person who happens to be called Jamie.
    val isJamie: Boolean get() = name?.trim().equals("Jamie", ignoreCase = true) && attributes.containsKey("tts")
}

/// The in-call chat's wire format: LiveKit's own chat convention, so Peel on
/// every platform and the web call client (meet, `@livekit/components`'
/// `useChat`) read each other's lines. A reliable data packet on the topic
/// `lk-chat-topic` whose payload is the UTF-8 JSON
/// `{"id":"<uuid>","timestamp":<epoch ms>,"message":"<text>"}`. A line is
/// named by who LiveKit's server says sent the packet ([sender]), not by
/// what the payload says (a payload could claim to be anyone), except a
/// line Jamie relays for a person.
///
/// Not ported: PeelCore's `CallChatWire` (iOS/Mac) also sends a `sender`
/// (its own name) and reads `status` (Jamie thinking), `voice` and `strut`
/// (Jamie's deliverables); here a `status` packet is not a line and the rest
/// is ignored. The newer `lk.chat` text-stream topic is not read or written.
object CallChat {
    const val TOPIC = "lk-chat-topic"
    /// The longest line sent or shown.
    const val MAX_LENGTH = 2000
    /// The most lines kept for the panel.
    const val MAX_KEPT = 500

    fun encode(id: String, timestampMs: Long, text: String): ByteArray =
        JSONObject().put("id", id).put("timestamp", timestampMs).put("message", text).toString().toByteArray(Charsets.UTF_8)

    /// What a chat packet says, or null when it is not a chat line (a control
    /// message such as `{"type":"tts-toggle"}` shares the topic, an empty or
    /// oversized body, or not JSON).
    fun decode(bytes: ByteArray): CallChatLine? {
        if (bytes.isEmpty() || bytes.size > 16 * 1024) return null
        val json = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (e: Exception) { return null }
        val text = json.optString("message", "").trim()
        if (text.isEmpty() || text.length > MAX_LENGTH * 2) return null
        val id = json.optString("id", "").take(64)
        val timestamp = json.optLong("timestamp", 0L)
        val sender = (json.opt("sender") as? String)?.trim()?.ifEmpty { null }
        return CallChatLine(id, timestamp, text.take(MAX_LENGTH), sender)
    }

    /// The name of a line whose sender joined with none, or is known only by
    /// identity.
    const val UNNAMED = "Guest"

    /// Who sent a chat packet, named for its line; null when nobody is known,
    /// and the line is dropped.
    ///
    /// [stamped] is the identity LiveKit's server stamps on every data packet
    /// from the sender's own connection (`DataReceived.participantIdentity`):
    /// a sender can't name someone else with it, even on an encrypted call,
    /// whose inner packet the server can't read. [matched] is whom the SDK
    /// matched by the packet's own `participantSid`, which the sender writes:
    /// rtc-node (a box's and Hive's Jamie) and livekit-client (meet) leave it
    /// empty, and on an encrypted call nothing checks it, so it counts only
    /// when the server stamped nothing (a server too old to stamp, or a
    /// packet sent through its API). [inRoom] finds someone in the room by
    /// identity; a stamped sender who isn't there (left already, or not
    /// announced yet) still has the line, as [UNNAMED].
    ///
    /// Jamie relays what a person asked aloud, and replays a joiner what
    /// people asked before, under the speaker's name ([CallChatLine.sender]):
    /// that line is named for the speaker. Anyone else's `sender` is ignored.
    /// Not iOS's rule: PeelCore names every line by its payload's `sender`
    /// and takes a line without one as Jamie's (`CallChatMessage.isFromJamie`),
    /// but anyone can write that field, and meet's lines carry none.
    fun sender(
        stamped: String,
        matched: CallRoomMember?,
        relayed: String? = null,
        inRoom: (String) -> CallRoomMember?,
    ): CallParticipant? {
        val member = when {
            stamped.isEmpty() -> matched ?: return null
            !CallServer.isIdentity(stamped) -> return null
            else -> inRoom(stamped) ?: CallRoomMember(stamped, null, null)
        }
        val known = CallServer.fromLiveKitRoom(member.identity, member.name, member.metadata) ?: return null
        val speaker = relayed?.takeIf { member.isJamie }
            ?.let { CallServer.fromLiveKitRoom(member.identity, it, null)?.nickname?.ifEmpty { null } }
        return when {
            speaker != null -> CallParticipant(member.identity, speaker, null)
            known.nickname.isEmpty() -> known.copy(nickname = UNNAMED)
            else -> known
        }
    }

    /// The text to send for what was typed: trimmed and capped; null when empty.
    fun outgoing(typed: String): String? = typed.trim().take(MAX_LENGTH).ifEmpty { null }

    /// [list] with [next] added at the end: dropped when its id is already
    /// there (a line seen twice), the oldest dropped past [MAX_KEPT].
    fun append(list: List<CallChatMessage>, next: CallChatMessage): List<CallChatMessage> {
        if (next.id.isNotEmpty() && list.any { it.id == next.id && it.identity == next.identity }) return list
        return (list + next).takeLast(MAX_KEPT)
    }
}

/// What joining a call does when you may already be on one: PeelCore's
/// `CallStore` asks first ("Leave the call in #design and join?") when the
/// call you are on is in a different room.
object CallSwitch {
    enum class Action { JOIN, SHOW_CURRENT, CONFIRM }

    /// [currentRoom] is null off a call. The same room: just bring it back.
    fun action(currentRoom: String?, newRoom: String): Action = when (currentRoom) {
        null -> Action.JOIN
        newRoom -> Action.SHOW_CURRENT
        else -> Action.CONFIRM
    }
}
