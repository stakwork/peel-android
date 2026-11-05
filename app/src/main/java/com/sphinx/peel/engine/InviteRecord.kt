package com.sphinx.peel.engine

/// An invite made here with sats (`Engine.makeInvite`). The core keeps no
/// record of invites, and the server only the code and its sats, so this is
/// the only list of them. The nickname and message are for this account
/// alone: they go nowhere on the wire, as in the official apps. Ported from
/// PeelCore's `InviteRecord` (Contacts.swift).
data class InviteRecord(
    /// The code the server knows it by: 64 hex characters.
    val code: String,
    /// The whole invite (`sphinx.chat://?action=i&…`), for the QR code and to copy. Like the
    /// words, never logged.
    val link: String,
    /// What was paid into it; the server may keep part as its fee.
    val amountMsat: Long,
    val nickname: String?,
    val message: String?,
    /// The funding payment's tag, which the server confirms.
    val tag: String?,
    val status: Status,
    /// Milliseconds since 1970.
    val createdAt: Long,
    /// The pubkey of whoever joined with it.
    val usedBy: String? = null,
) {
    enum class Status(val raw: String) {
        /// The payment that funds it is on its way to the server.
        SENDING("sending"),

        /// The server holds its sats: the code can be shared.
        READY("ready"),

        /// The payment failed: no sats moved and the code is worth nothing.
        FAILED("failed"),

        /// Asked the server for the sats back; not answered yet.
        CANCELLING("cancelling"),

        /// The server sent the sats back.
        CANCELLED("cancelled"),

        /// Someone joined with it and added this account (`usedBy`).
        USED("used"),
        ;

        companion object {
            fun fromRaw(raw: String?): Status? = entries.firstOrNull { it.raw == raw }
        }
    }

    /// Still waiting to be used: listed as open, and may be cancelled.
    val isPending: Boolean get() = status in PENDING_STATUSES

    companion object {
        val PENDING_STATUSES = listOf(Status.SENDING, Status.READY, Status.CANCELLING)
        const val MAX_NICKNAME_LENGTH = 100
        const val MAX_MESSAGE_LENGTH = 500
    }
}

/// The rules around invites that need no core or database: pure, so they have tests.
object InviteRules {
    /// An invite's status for its funding payment's: sending while it is on
    /// its way, ready once delivered, failed. An unsettled funding payment
    /// is still on its way: the invite stays sending and the server is asked
    /// about it on every connect, so it has no status of its own
    /// (`Storage+Writes.swift`, `inviteStatus`).
    fun forFunding(status: SendStatus): InviteRecord.Status? = when (status) {
        SendStatus.PENDING -> InviteRecord.Status.SENDING
        SendStatus.DELIVERED -> InviteRecord.Status.READY
        SendStatus.FAILED -> InviteRecord.Status.FAILED
        SendStatus.UNSETTLED -> null
    }

    /// The statuses an invite may be in for its funding payment to move it to
    /// [status]'s own: the same forward-only rule the message follows,
    /// mapped (`SendStatus.movesFrom`). Once ready, cancelled or used no
    /// funding status moves it.
    fun fundingMovesFrom(status: SendStatus): List<InviteRecord.Status> =
        status.movesFrom.mapNotNull { SendStatus.entries.firstOrNull { s -> s.raw == it }?.let(::forFunding) }.distinct()

    /// Whether a balance reading shows a cancelled invite's sats came back:
    /// it rose, by at most the invite's amount (the server may keep a fee).
    fun refunded(before: Long, now: Long, invitedMsat: Long): Boolean = now > before && now - before <= invitedMsat

    /// Whether an invite in [status] may be cancelled (asked again while cancelling).
    fun canCancel(status: InviteRecord.Status): Boolean =
        status == InviteRecord.Status.READY || status == InviteRecord.Status.CANCELLING

    /// Trimmed, with empty as absent; throws for a nickname or message past its limit.
    fun cleanNickname(text: String?): String? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() }
        if (t != null && t.length > InviteRecord.MAX_NICKNAME_LENGTH) throw InviteRefused("The name is too long.")
        return t
    }

    fun cleanMessage(text: String?): String? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() }
        if (t != null && t.length > InviteRecord.MAX_MESSAGE_LENGTH) throw InviteRefused("The message is too long.")
        return t
    }
}

/// An invite or contact action refused before anything was sent, in words for the person. Money
/// checks throw `PaymentRefused` (with the amount to confirm) as everywhere else.
class InviteRefused(message: String) : Exception(message)

/// The answer to a key exchange: the other side's key, which the core sends on its own
/// (PeelCore's `MessageType.contactKeyConfirmation`).
const val CONTACT_KEY_CONFIRMATION_MESSAGE_TYPE = 11
