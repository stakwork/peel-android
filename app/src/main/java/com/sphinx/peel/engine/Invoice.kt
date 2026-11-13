package com.sphinx.peel.engine

import org.json.JSONArray
import org.json.JSONObject

/// A Lightning invoice as the core reads it (`parse_invoice`). Every amount
/// Peel shows or pays for an invoice comes from here, never from what a
/// message says about it. Ported from PeelCore's `Invoice.swift`.
data class Invoice(
    val bolt11: String,
    val paymentHash: String,
    /// What it asks for; 0 when it names no amount.
    val amountMsat: Long,
    /// The node that gets paid.
    val payee: String,
    val memo: String,
    /// Milliseconds since 1970; null when the invoice does not say.
    val expiresAtMs: Long?,
    /// `pubkey_scid` route hints, the last nearest the payee.
    val hopHints: List<String>,
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = expiresAtMs?.let { it <= nowMs } ?: false

    companion object {
        /// The text a BOLT 11 invoice is read from: surrounding space, a
        /// `lightning:` prefix and upper case (a QR's, for density) are
        /// tolerated. Null when it cannot be one. Same checks as
        /// `Invoice.init` in PeelCore: starts with "ln", under 4096 chars.
        fun normalize(text: String): String? {
            val input = PaymentInput.parse(text)
            return (input as? PaymentInput.Bolt11)?.bolt11
        }

        /// The core's `parse_invoice` JSON for `bolt11`, checked: a payment
        /// hash that is 64 hex characters and a payee are required, anything
        /// else missing reads as empty. Null for anything unreadable, so a
        /// wrong amount is never shown. Pure (no native call) so it can be tested.
        fun fromParsedJson(bolt11: String, json: String): Invoice? {
            val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val hash = obj.optString("payment_hash").lowercase()
            if (!isHash(hash)) return null
            val payee = obj.optString("pubkey").takeIf { it.isNotEmpty() } ?: return null
            val value = if (obj.isNull("value")) 0L else obj.optLong("value", 0L)
            if (value < 0) return null
            val expirySeconds = if (obj.isNull("expiry")) null else obj.optLong("expiry", -1L).takeIf { it >= 0 }
            val hints = (obj.optJSONArray("hop_hints") ?: JSONArray()).let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf { hint -> hint.isNotEmpty() } }
            }
            return Invoice(
                bolt11 = bolt11,
                paymentHash = hash,
                amountMsat = value,
                payee = payee,
                memo = if (obj.isNull("description")) "" else obj.optString("description"),
                expiresAtMs = expirySeconds?.let { it * 1000 },
                hopHints = hints,
            )
        }

        /// 32 bytes as lowercase hex, as payment hashes and tags are written.
        fun isHash(text: String): Boolean = text.length == 64 && text.all { it in '0'..'9' || it in 'a'..'f' }
    }
}

/// What a scanned or pasted text turns out to be. The camera and the paste
/// box read untrusted text; only a BOLT 11 invoice can be paid from here.
sealed class PaymentInput {
    data class Bolt11(val bolt11: String) : PaymentInput()

    /// An LNURL or a Lightning address: recognised so the person is told
    /// what it is, but not payable — PeelCore pays only BOLT 11 too
    /// (`docs/money-parity.md`).
    data class Unsupported(val kind: String) : PaymentInput()

    data object Unknown : PaymentInput()

    companion object {
        private const val MAX_LENGTH = 4096
        private val LIGHTNING_ADDRESS = Regex("^[a-z0-9._+-]+@[a-z0-9.-]+\\.[a-z]{2,}$")

        fun parse(raw: String): PaymentInput {
            var text = raw.trim().lowercase()
            if (text.isEmpty() || text.length >= MAX_LENGTH) return Unknown
            // BIP 21 with a Lightning fallback: bitcoin:<addr>?amount=..&lightning=<bolt11>
            if (text.startsWith("bitcoin:")) {
                val query = text.substringAfter('?', "")
                val fallback = query.split('&').firstOrNull { it.startsWith("lightning=") }?.removePrefix("lightning=")
                return if (fallback.isNullOrEmpty()) Unsupported("an on-chain address") else parse(fallback)
            }
            if (text.startsWith("lightning:")) text = text.removePrefix("lightning:").trim()
            if (text.startsWith("lnurl")) return Unsupported("an LNURL")
            if (LIGHTNING_ADDRESS.matches(text)) return Unsupported("a Lightning address")
            if (text.startsWith("ln") && text.none { it.isWhitespace() }) return Bolt11(text)
            return Unknown
        }
    }
}

/// An invoice this account made (to receive) or paid, with where it stands —
/// PeelCore's `InvoiceRecord`. Paying one is tracked like any send of ours:
/// by the tag the server confirms, with statuses that only move forward.
data class InvoiceRecord(
    val paymentHash: String,
    /// True for an invoice this account made, false for one it pays.
    val incoming: Boolean,
    val bolt11: String,
    val amountMsat: Long,
    val memo: String,
    val payee: String,
    val createdAt: Long,
    val expiresAt: Long?,
    val status: Status,
    val paidAt: Long? = null,
    /// For one made here: what actually arrived, which the payer chose.
    val paidMsat: Long? = null,
    /// The send's tag, for one we pay.
    val tag: String? = null,
    /// The chat a payment request went to or came from.
    val chat: String? = null,
    val error: String? = null,
    /// The preimage the server sent with the COMPLETE of a payment of ours, kept only when it hashes to the payment hash (`Storage.keepPreimage`).
    val preimage: String? = null,
    /// The wallet connection ([WalletConnection.key]) an invoice made or paid for an app belongs to, and the app (`<peer>/<name>`); null for our own.
    val connection: String? = null,
    val app: String? = null,
) {
    enum class Status(val raw: String) {
        /// Made here and not paid yet.
        OPEN("OPEN"),
        /// Ours, on its way: it may have paid, so never paid again until the server says.
        PENDING("PENDING"),
        /// Paid: to us (settled) or by us (the server confirmed).
        PAID("COMPLETE"),
        /// Ours, refused or never sent: nothing moved, so it may be paid again.
        FAILED("FAILED"),
        /// Made here, settled for less than it asked. Never shown as paid.
        UNDERPAID("UNDERPAID");

        companion object {
            fun of(raw: String?): Status = entries.firstOrNull { it.raw == raw } ?: OPEN
        }
    }
}

/// One row of the server's payment history (`fetch_payments`). Rows carry no
/// direction field: only incoming ones have a message index. Ported from
/// PeelCore's `PaymentRecord`.
data class PaymentRecord(
    val rhash: String,
    /// Milliseconds since 1970.
    val ts: Long,
    val amountMsat: Long,
    val incoming: Boolean,
    val msgIndex: Long?,
    /// True when it crossed the Lightning network rather than this server.
    val remote: Boolean,
    val error: String?,
) {
    val failed: Boolean get() = !error.isNullOrEmpty()

    companion object {
        /// The server's JSON, checked: anything unreadable or out of range
        /// leaves the row out rather than showing a wrong amount.
        fun decode(json: String?): List<PaymentRecord> {
            if (json == null) return emptyList()
            val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val row = array.optJSONObject(i) ?: return@mapNotNull null
                val rhash = row.optString("rhash").lowercase()
                if (!Invoice.isHash(rhash) || !row.has("ts") || !row.has("amt_msat")) return@mapNotNull null
                val ts = row.optLong("ts", -1)
                val amount = row.optLong("amt_msat", -1)
                if (ts < 0 || amount < 0) return@mapNotNull null
                val msgIdx = if (row.isNull("msg_idx")) null else row.optLong("msg_idx", -1)
                PaymentRecord(
                    rhash = rhash,
                    ts = ts,
                    amountMsat = amount,
                    // A payment to an invoice the server made for us comes with index 0: no message.
                    incoming = msgIdx != null,
                    msgIndex = msgIdx?.takeIf { it > 0 },
                    remote = row.optBoolean("remote", false),
                    error = if (row.isNull("error")) null else row.optString("error").takeIf { it.isNotEmpty() },
                )
            }
        }
    }
}
