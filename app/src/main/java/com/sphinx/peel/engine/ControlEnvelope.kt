package com.sphinx.peel.engine

import org.json.JSONArray
import org.json.JSONObject

// The device channel's control messages (iOS `ControlEnvelope.swift`, docs/agent-on-strut.md, "The device channel"): the agent and a person's
// devices talk in the agent's DM with ordinary text messages whose `metadata` carries a `peel` envelope beside the time zone's `tz`. Their
// content is one readable line, which the official apps show; Peel never shows a control message as a bubble. Pure; JVM-tested.
// Not ported: the writing side (`ControlEnvelope.adding`, the device's ack/result) and `Engine+DeviceRequests`: the phone never answers a
// device request (docs/agent-on-strut.md, decision 9: only the Mac does), so it only reads and draws them.

/// One control message's envelope, `metadata.peel`. Whoever sent the message wrote it: read it with [ControlMessage.read], which checks the
/// version and the fields each [op] needs.
data class ControlEnvelope(
    val v: Int,
    val op: Op,
    /// On a request: its id, which every answer names in `re`.
    val id: String? = null,
    /// On a request: the device it is for.
    val device: String? = null,
    /// On a request: what to do (`chats.unseen`, `send`, …).
    val kind: String? = null,
    /// A JSON value (object, array, string, number…) or null.
    val args: Any? = null,
    /// On a request: seconds since 1970 after which it is never done (`not_after`).
    val notAfter: Long? = null,
    /// On an answer or a cancel: the request it is about.
    val re: String? = null,
    val status: Status? = null,
    val result: Any? = null,
    val error: String? = null,
    /// On a cancel or a status line: the agent's turn.
    val run: String? = null,
    val state: String? = null,
    val text: String? = null,
) {
    enum class Op(val raw: String) {
        /// Agent to device: do this.
        REQUEST("request"),
        /// Device to agent: stored, working on it.
        ACK("ack"),
        /// Device to agent: done, with `status`.
        RESULT("result"),
        /// Either way: the agent no longer needs a request (`re`), or you stop a turn.
        CANCEL("cancel"),
        /// Agent to device: a line for a person ("Waiting for your Mac").
        STATUS("status"),
    }

    /// How a request ended, on its result.
    enum class Status(val raw: String) { DONE("done"), DENIED("denied"), EXPIRED("expired"), FAILED("failed"), UNKNOWN("unknown") }

    /// Whether the fields [op] needs are there: a request names its id, device, kind and expiry; an ack or result its request (a result also its
    /// status); a status line its turn, state and text. A cancel names what it stops, or nothing for the turn in flight (Stop).
    val isComplete: Boolean
        get() = when (op) {
            Op.REQUEST -> id != null && device != null && kind != null && notAfter != null
            Op.ACK -> re != null
            Op.RESULT -> re != null && status != null
            Op.CANCEL -> true
            Op.STATUS -> run != null && state != null && text != null
        }

    /// Whether this Peel reads it: its version, and the fields its `op` needs.
    val isReadable: Boolean get() = v == VERSION && isComplete

    companion object {
        /// The version this Peel reads.
        const val VERSION = 1

        /// Whether a request with this `not_after` is past it at [nowSeconds]: its second gone by.
        fun isPast(notAfter: Long, nowSeconds: Long): Boolean = notAfter < nowSeconds
    }
}

/// What a control message says, as this Peel reads it.
sealed interface ControlMessage {
    data class Envelope(val envelope: ControlEnvelope) : ControlMessage

    /// A control message this Peel cannot read: a version or `op` it does not know, or fields that do not fit. Shown as its text, never acted on.
    data object Unsupported : ControlMessage

    companion object {
        /// The control message in [body]: one whose metadata's `peel` is an object with an `op`. A `peel` without one is a talk message's extras
        /// (its tool rows, an automation's name), and null.
        fun read(body: String?): ControlMessage? {
            // Cheap first: the keys as they appear inside the metadata string, or an escape that could spell them.
            if (body == null) return null
            if (!((body.contains("\\\"peel\\\"") && body.contains("\\\"op\\\"")) || body.contains("\\u"))) return null
            val peel = peel(body) ?: return null
            if (!peel.has("op")) return null
            // Typed only once it is one.
            val envelope = decode(peel) ?: return Unsupported
            return if (envelope.isReadable) Envelope(envelope) else Unsupported
        }

        /// The `peel` object in [body]'s metadata, untyped.
        fun peel(body: String?): JSONObject? = MessageMetadata.json(body)?.opt("peel") as? JSONObject

        private class Mismatch : Exception()

        private fun text(o: JSONObject, key: String): String? {
            if (!o.has(key) || o.isNull(key)) return null
            return o.get(key) as? String ?: throw Mismatch()
        }

        private fun whole(value: Any): Long {
            if (value is Int) return value.toLong()
            if (value is Long) return value
            if (value is Double && value.isFinite() && value == Math.floor(value) && Math.abs(value) < 9.0e18) return value.toLong()
            throw Mismatch()
        }

        private fun decode(o: JSONObject): ControlEnvelope? = try {
            val v = if (o.has("v") && !o.isNull("v")) whole(o.get("v")) else throw Mismatch()
            val op = ControlEnvelope.Op.entries.firstOrNull { it.raw == text(o, "op") } ?: throw Mismatch()
            val status = text(o, "status")?.let { raw -> ControlEnvelope.Status.entries.firstOrNull { it.raw == raw } ?: throw Mismatch() }
            ControlEnvelope(
                v = v.toInt().takeIf { it.toLong() == v } ?: Int.MAX_VALUE,
                op = op,
                id = text(o, "id"), device = text(o, "device"), kind = text(o, "kind"),
                args = o.opt("args")?.takeIf { it != JSONObject.NULL },
                notAfter = if (o.has("not_after") && !o.isNull("not_after")) whole(o.get("not_after")) else null,
                re = text(o, "re"), status = status,
                result = o.opt("result")?.takeIf { it != JSONObject.NULL },
                error = text(o, "error"), run = text(o, "run"), state = text(o, "state"), text = text(o, "text"),
            )
        } catch (_: Mismatch) {
            null
        }
    }
}

/// A message's `metadata`: a JSON object the sender wrote into the body as a string (`sphinx/src/msg/mod.rs`), which the official apps carry along.
object MessageMetadata {
    /// The longest metadata read from a message; a device's answer with a catch-up in it is about 20 KB.
    const val MAX_LENGTH = 64 * 1024

    /// The metadata string in [body], if it has one no longer than [MAX_LENGTH] bytes.
    fun string(body: String?): String? {
        val text = runCatching { JSONObject(body ?: return null).opt("metadata") as? String }.getOrNull() ?: return null
        return text.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_LENGTH }
    }

    /// The metadata as an object; null when it is none, too long, malformed or nested past what the reader allows.
    fun json(body: String?): JSONObject? {
        val text = string(body) ?: return null
        return try {
            JSONObject(text)
        } catch (_: Throwable) { // a JSONException, or a StackOverflowError on hostile nesting
            null
        }
    }
}

/// What a stored message says on the device channel (iOS `Message.control` and `Message.isControlLine`/`controlLine`).
object ControlMessages {
    /// The longest control line shown; the sender means it to be short.
    const val LINE_LENGTH = 200

    /// What this message says on the device channel, if it is a control message: only text carries one, and not a deleted one.
    fun control(type: Int, deleted: Boolean, body: String?): ControlMessage? =
        if (type == TEXT_MESSAGE_TYPE && !deleted) ControlMessage.read(body) else null

    /// The message's `content`, trimmed (iOS `shownText` of a text message; a GIF shows its caption).
    fun shownText(body: String?): String {
        val content = runCatching { JSONObject(body ?: return "").optString("content") }.getOrNull().orEmpty()
        return content.trim { it.isWhitespace() || it == '\u00A0' }
    }

    /// Whether this is drawn as a control message's quiet line (docs/agent-on-strut.md, "Two kinds of message in the DM"), never a bubble: only
    /// in a direct message, the device channel's (in a tribe the same metadata leaves a member's message a message), and not one of ours that
    /// failed, which keeps its bubble for Retry and Discard.
    fun isControlLine(type: Int, deleted: Boolean, body: String?, fromMe: Boolean, failed: Boolean, inTribe: Boolean): Boolean =
        !inTribe && control(type, deleted, body) != null && !(fromMe && failed)

    /// A control message's line: who sent it, so nobody's text passes for Peel's own, and its text as the official apps show it, on one line as a
    /// preview shows it; empty when it has no words.
    fun line(body: String?, sender: String): String {
        val text = MessageMarkup.plainText(shownText(body))
        if (text.isEmpty()) return ""
        val line = "$sender: $text"
        return if (line.length > LINE_LENGTH) line.take(LINE_LENGTH - 1) + "…" else line
    }
}

/// One tool an agent's reply ran: its name as strut gives it (`sphinx_unseen`), a line of what it was asked, and whether it failed.
data class AgentToolLine(val name: String, val summary: String = "", val failed: Boolean = false) {
    companion object {
        /// The most read from one reply (the bridge caps the list at 2 KB).
        const val MOST = 50

        /// The longest name or summary kept.
        const val LONGEST = 200

        fun parse(value: Any?): AgentToolLine? {
            val fields = value as? JSONObject ?: return null
            val name = fields.opt("name") as? String ?: return null
            if (name.isEmpty()) return null
            val summary = fields.opt("summary") as? String ?: ""
            return AgentToolLine(name.take(LONGEST), summary.take(LONGEST), failed = fields.opt("failed") == true)
        }
    }
}

/// A job an agent's message is about: strut's job id, its title, and where it stands as of this message.
data class AgentJob(val id: String, val title: String? = null, val state: State? = null) {
    enum class State(val raw: String) {
        /// Started, or going on (a progress note).
        WORKING("working"),
        /// It needs your answer; your next message continues it.
        ASKING("asking"),
        DONE("done"),
        FAILED("failed"),
    }

    companion object {
        fun parse(value: Any?): AgentJob? {
            val fields = value as? JSONObject ?: return null
            val id = fields.opt("id") as? String ?: return null
            if (id.isEmpty()) return null
            val title = (fields.opt("title") as? String)?.takeIf { it.isNotEmpty() }?.take(AgentToolLine.LONGEST)
            val state = (fields.opt("state") as? String)?.let { raw -> State.entries.firstOrNull { it.raw == raw } }
            return AgentJob(id.take(AgentToolLine.LONGEST), title, state)
        }
    }
}

/// `key` in a talk message's extras (`metadata.peel` with no `op`): the tool rows and the job (iOS `Message.agentTools`, `Message.agentJob`).
object AgentExtras {
    private fun extra(type: Int, deleted: Boolean, body: String?, key: String): Any? {
        if (type != TEXT_MESSAGE_TYPE || deleted || body == null || !body.contains(key)) return null
        val peel = ControlMessage.peel(body) ?: return null
        if (peel.has("op")) return null
        return peel.opt(key)
    }

    /// The tools an agent's reply ran, as the bridge lists them from the run's events (`metadata.peel.tools`); empty for anything else. Whoever
    /// sent the message wrote them: shown as plain words, never acted on.
    fun tools(type: Int, deleted: Boolean, body: String?): List<AgentToolLine> {
        val tools = extra(type, deleted, body, "tools") as? JSONArray ?: return emptyList()
        return (0 until minOf(tools.length(), AgentToolLine.MOST)).mapNotNull { AgentToolLine.parse(tools.opt(it)) }
    }

    /// The job an agent's message is about (`metadata.peel.job`): its result, its question, or that it failed. Null for anything else.
    fun job(type: Int, deleted: Boolean, body: String?): AgentJob? = AgentJob.parse(extra(type, deleted, body, "job"))
}
