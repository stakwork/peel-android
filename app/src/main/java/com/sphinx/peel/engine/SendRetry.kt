package com.sphinx.peel.engine

import com.sphinx.peel.storage.MessageRow

/// When a send of ours may go again or be dropped — the pure rules of
/// PeelCore's `Message.isRetryable`/`isAutoResendable`/`isDiscardable` and
/// `Engine.autoResends`, so they can be tested without the native core.
///
/// Not ported: payments and boosts (Retry would pay again; Android has no
/// `PaymentAttempt` confirmation flow for it), payment requests (an
/// invoice's expiry check) and an attachment whose upload failed (this port
/// stores no draft row: `Engine.sendAttachment` throws before one exists).
/// An attachment already uploaded goes again like text, its token intact.
object SendRetry {
    /// Text and an attachment (already uploaded, so its body is complete) —
    /// PeelCore's `MessageType.resendable` minus requests.
    val RESENDABLE_TYPES = listOf(0, ATTACHMENT_MESSAGE_TYPE)

    /// How long a send may wait for the server before it can be sent again
    /// (`Message.confirmationTimeout`: 30 s, as in the official apps).
    const val CONFIRMATION_TIMEOUT_MS = 30_000L

    /// How old an undelivered message may be and still go again by itself
    /// when the connection comes back (`Message.autoResendWindow`).
    const val AUTO_RESEND_WINDOW_MS = 60 * 60 * 1000L

    /// How long a send may be in flight with the server holding no record of
    /// it: the mixer records a send only once it finished
    /// (`Message.inFlightLimit`). A pending one younger than this is not
    /// sent again by itself.
    const val IN_FLIGHT_LIMIT_MS = 2 * 60 * 1000L

    /// Sent with sats that may have moved: a message that paid a tribe's
    /// price or a payment/boost that left (it has a tag). Never sent again
    /// while only pending (`Message.mayHavePaid`).
    fun mayHavePaid(row: MessageRow): Boolean =
        row.fromMe && row.tag != null && ((row.msat ?: 0) > 0 || row.type in MONEY_SEND_TYPES)

    /// One of ours that failed, or that the server has not confirmed within
    /// the timeout, and so may be sent again or discarded
    /// (`Message.isRetryable`). A pending one that may have paid waits.
    fun isRetryable(row: MessageRow, now: Long): Boolean {
        if (!row.fromMe || row.deleted || row.type !in RESENDABLE_TYPES) return false
        return when (row.status) {
            SendStatus.FAILED.raw -> true
            SendStatus.PENDING.raw -> !mayHavePaid(row) && now - (row.timestamp ?: 0L) > CONFIRMATION_TIMEOUT_MS
            else -> false
        }
    }

    /// Ours on its way: faint until the server has it, unless it may be sent
    /// again (that shows why instead) (`Message.isSending`).
    fun isSending(row: MessageRow, now: Long): Boolean =
        row.fromMe && row.status == SendStatus.PENDING.raw && !isRetryable(row, now)

    /// One of ours that never went and may be dropped: a retryable send, or a
    /// failed call, never sent again (its room would be stale)
    /// (`Message.isDiscardable`).
    fun isDiscardable(row: MessageRow, now: Long): Boolean =
        isRetryable(row, now) ||
            (row.fromMe && !row.deleted && row.status == SendStatus.FAILED.raw && row.type == CALL_MESSAGE_TYPE)

    /// What an undelivered row says (`Message.undeliveredLabel`, trimmed).
    fun undeliveredLabel(row: MessageRow): String =
        if (row.status == SendStatus.FAILED.raw) "Not delivered" else "Not confirmed"

    /// One of ours that a reconnect may send again by itself, once: retryable,
    /// never sent again that way, first sent at most an hour ago, carrying no
    /// sats (`Message.isAutoResendable`). Never in a tribe: the caller checks.
    fun isAutoResendable(row: MessageRow, now: Long): Boolean {
        if (!isRetryable(row, now) || row.autoResentAt != null || (row.msat ?: 0) != 0L) return false
        return now - firstSent(row) <= AUTO_RESEND_WINDOW_MS
    }

    /// When this send was first sent (a resend moves `timestamp`).
    fun firstSent(row: MessageRow): Long = row.firstSentAt ?: row.timestamp ?: 0L

    /// The tags of a reconnect's candidates the server was asked about, and
    /// those its records named, any status (null until it answers) —
    /// `Engine.ResendGate`.
    class Gate(val asked: Set<String>, @Volatile var recorded: Set<String>? = null) {
        /// Tags named by the answers so far; becomes [recorded] once all came.
        private val named = mutableSetOf<String>()

        fun name(tag: String) = synchronized(named) { named += tag }

        fun settle() { recorded = synchronized(named) { named.toSet() } }
    }

    /// Whether a reconnect sends [row] again by itself (`Engine.autoResends`).
    /// With no tag it never left, so only a failed one goes. With a tag, only
    /// if the server was asked about it and its records do not name it,
    /// whatever their status (one that reached the server is never sent
    /// twice); and a pending one only once first sent over [IN_FLIGHT_LIMIT_MS] ago.
    fun autoResends(row: MessageRow, gate: Gate?, now: Long): Boolean {
        if (!isAutoResendable(row, now)) return false
        val tag = row.tag ?: return row.status == SendStatus.FAILED.raw
        val recorded = gate?.recorded ?: return false
        if (tag !in gate.asked || tag in recorded) return false
        return row.status != SendStatus.PENDING.raw || now - firstSent(row) > IN_FLIGHT_LIMIT_MS
    }
}
