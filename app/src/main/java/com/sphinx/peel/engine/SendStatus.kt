package com.sphinx.peel.engine

import org.json.JSONArray
import org.json.JSONObject

/// A sent message's delivery state — PeelCore's `Message.Delivery`. Stored
/// in `message.status` as [raw]; the app's own names (`DELIVERED`) differ
/// from the server's word for it (`COMPLETE`), see [fromServer].
enum class SendStatus(val raw: String) {
    PENDING("PENDING"),
    DELIVERED("DELIVERED"),
    FAILED("FAILED"),

    /// A payment/boost the server handed to the recipient that did not
    /// settle in its window: neither delivered nor failed (never offered
    /// again), a later COMPLETE or FAILED still lands.
    UNSETTLED("UNSETTLED"),
    ;

    /// The statuses a row may be in (besides none) to move to this one.
    val movesFrom: List<String>
        get() = when (this) {
            PENDING -> emptyList()
            UNSETTLED -> listOf("PENDING")
            FAILED -> listOf("PENDING", "UNSETTLED")
            DELIVERED -> listOf("PENDING", "UNSETTLED", "FAILED")
        }

    companion object {
        /// The mixer's words: COMPLETE, FAILED, PENDING.
        fun fromServer(word: String?): SendStatus? = when (word) {
            "COMPLETE" -> DELIVERED
            "FAILED" -> FAILED
            "PENDING" -> PENDING
            else -> null
        }

        /// PeelCore's `Engine.liveStatus`: the one status the mixer
        /// publishes for a send. COMPLETE and FAILED as they are; PENDING
        /// is its last word on a payment that did not settle: unsettled.
        fun live(status: String?): SendStatus? = when (fromServer(status)) {
            DELIVERED -> DELIVERED
            FAILED -> FAILED
            PENDING -> UNSETTLED
            else -> null
        }

        /// One answer of the mixer's records (the `getTags` reply):
        /// `[{"tag", "status", "message"?, "error"?}]`.
        data class Record(val tag: String, val status: SendStatus, val error: String?)

        /// PeelCore's `Engine.recordedStatuses`: only a final status moves
        /// a row, since the mixer also records an async payment as PENDING
        /// while it holds it for an offline recipient.
        fun recorded(json: String?): List<Record> {
            val array = json?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val tag = o.optString("tag").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val status = fromServer(o.optString("status")) ?: return@mapNotNull null
                if (status != DELIVERED && status != FAILED) return@mapNotNull null
                Record(tag, status, reason(o))
            }
        }

        fun reason(o: JSONObject): String? =
            (o.optString("message").ifEmpty { o.optString("error") }).takeIf { it.isNotEmpty() }
    }
}

/// Message types that move sats (payment, invoice payment, boost): the only
/// ones that can be [SendStatus.UNSETTLED], and that may have paid while
/// unconfirmed. Mirrors PeelCore's `MessageType.moneySends`.
val MONEY_SEND_TYPES = listOf(3, 5, 29)
