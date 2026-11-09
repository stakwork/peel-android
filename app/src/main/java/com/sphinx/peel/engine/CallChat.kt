package com.sphinx.peel.engine

import org.json.JSONObject

/// One line of the in-call chat side panel.
data class CallChatMessage(
    val id: String,
    /// The sender's LiveKit identity; for your own lines, your ticket's.
    val identity: String,
    val name: String,
    val text: String,
    val timestampMs: Long,
    val isYou: Boolean,
)

/// The in-call chat's wire format: LiveKit's own chat convention, so Peel on
/// every platform and the web call client (meet, `@livekit/components`'
/// `useChat`) read each other's lines. A reliable data packet on the topic
/// `lk-chat-topic` whose payload is the UTF-8 JSON
/// `{"id":"<uuid>","timestamp":<epoch ms>,"message":"<text>"}`. The sender's
/// name comes from the packet's participant, not from anything in the
/// payload (a payload could claim to be anyone).
///
/// Not ported/known: iOS's own `CallChat.swift` was not readable when this was
/// written (the `peel` checkout was not in the pod), so the topic and shape
/// follow LiveKit's published convention, which the iOS client is expected to
/// use too; the newer `lk.chat` text-stream topic is not read or written.
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
    /// oversized body, or not JSON). The result is `(id, timestamp, text)`.
    fun decode(bytes: ByteArray): Triple<String, Long, String>? {
        if (bytes.isEmpty() || bytes.size > 16 * 1024) return null
        val json = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (e: Exception) { return null }
        val text = json.optString("message", "").trim()
        if (text.isEmpty() || text.length > MAX_LENGTH * 2) return null
        val id = json.optString("id", "").take(64)
        val timestamp = json.optLong("timestamp", 0L)
        return Triple(id, timestamp, text.take(MAX_LENGTH))
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
