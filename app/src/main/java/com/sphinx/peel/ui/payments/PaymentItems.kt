package com.sphinx.peel.ui.payments

import com.sphinx.peel.engine.BOOST_MESSAGE_TYPE
import com.sphinx.peel.engine.DIRECT_PAYMENT_MESSAGE_TYPE
import com.sphinx.peel.engine.INVOICE_MESSAGE_TYPE
import com.sphinx.peel.engine.INVOICE_PAYMENT_MESSAGE_TYPE
import com.sphinx.peel.engine.InvoiceRecord
import com.sphinx.peel.engine.PaymentRecord
import com.sphinx.peel.storage.ContactRow
import com.sphinx.peel.storage.MessageRow
import java.util.Calendar

/// One row of the Payments list: the server's history joined to what this
/// phone knows about it (the message it came with, the invoice record).
/// Ported from PeelUI's `PaymentItem` (`PaymentItem+Display.swift`), trimmed:
/// no routing fee, no "not arrived" verdict, no paid-attachment or
/// template kinds (`docs/money-parity.md`).
data class PaymentItem(
    val id: String,
    val rhash: String,
    val incoming: Boolean,
    val amountMsat: Long,
    /// Milliseconds since 1970.
    val ts: Long,
    val status: Status,
    val kind: Kind,
    /// The chat it belongs to, when a stored message names one.
    val chat: String?,
    val note: String?,
    val error: String?,
    /// The chat's name (a tribe's, or the contact's alias) and whether it is a tribe.
    val chatName: String? = null,
    val isTribe: Boolean = false,
    /// Who paid or was paid: the contact, or the tribe member. Null when nobody is known.
    val counterpart: String? = null,
    /// The stored message the payment came with, when there is one.
    val message: MessageRow? = null,
) {
    enum class Status { COMPLETE, PENDING, FAILED }

    enum class Kind {
        /// A boost (type 29).
        BOOST,
        /// Sats sent in a direct message (type 5).
        PAYMENT,
        /// A request or an invoice paid (types 2 and 3), or one pasted.
        INVOICE,
        /// A payment with no message of ours: a bare keysend, or the ping a send adds.
        KEYSEND,
        /// Anything else ours in a tribe: what posting it cost.
        MESSAGE_FEE,
    }

    /// The row's name: who, or what it was when nobody is known (iOS `title`).
    val title: String
        get() = counterpart ?: when (kind) {
            Kind.INVOICE -> if (incoming) "Invoice paid" else "Lightning invoice"
            Kind.KEYSEND -> "Keysend"
            else -> chatName ?: "Someone"
        }

    /// The one line under the name (iOS `subtitle`).
    val subtitle: String
        get() {
            if (status == Status.FAILED && error != null) return error
            if (kind == Kind.BOOST) return if (isTribe) "Boost in #${chatName.orEmpty()}" else "Boost"
            note?.let { return it }
            return when (kind) {
                Kind.KEYSEND -> if (incoming) "Received, not tied to a chat" else "Sent, not tied to a chat"
                Kind.INVOICE -> if (incoming) "Your invoice" else "Invoice"
                Kind.PAYMENT, Kind.BOOST -> if (isTribe) "In #${chatName.orEmpty()}" else "Payment"
                Kind.MESSAGE_FEE -> "Message fee in #${chatName.orEmpty()}"
            }
        }

    /// "Boost received", "Message fee sent" (iOS `headline`).
    val headline: String
        get() {
            val what = when (kind) {
                Kind.BOOST -> "Boost"
                Kind.PAYMENT -> "Payment"
                Kind.INVOICE -> "Invoice payment"
                Kind.KEYSEND -> "Keysend"
                Kind.MESSAGE_FEE -> "Message fee"
            }
            val how = when {
                status == Status.PENDING && !incoming -> "on its way"
                status == Status.FAILED && incoming -> "not received"
                else -> if (incoming) "received" else "sent"
            }
            return "$what $how"
        }

    /// Who it was with, under their name in the detail (iOS `context`).
    val context: String
        get() = when {
            isTribe -> if (counterpart == null) "In #${chatName.orEmpty()}" else "Member of #${chatName.orEmpty()}"
            chat != null -> "Contact"
            else -> "Not tied to a contact"
        }

    /// A row's time with what is still to happen: "Pending · 14:22" (iOS `timeWithStatus`).
    fun timeWithStatus(time: String): String = when (status) {
        Status.COMPLETE -> time
        Status.PENDING -> "${if (isToContact) "Waiting" else "Pending"} · $time"
        Status.FAILED -> "${if (incoming) "Not received" else "Failed"} · $time"
    }

    /// Sats of ours to a contact, which wait for the contact's app to come online.
    val isToContact: Boolean
        get() = !incoming && !isTribe && chat != null && (kind == Kind.PAYMENT || kind == Kind.BOOST)

    /// The message a fee was for (iOS `quoted`, trimmed: no boosted message).
    val quotedMessage: MessageRow?
        get() = if (kind == Kind.MESSAGE_FEE) message else null

    companion object {
        /// The history joined to messages (`messageOf`, by payment hash) and
        /// invoice records, newest first. A send of ours still pending, with
        /// no history row yet, is listed too; a pending record is greyed,
        /// a failed one struck through, neither red.
        fun build(
            history: List<PaymentRecord>,
            invoices: List<InvoiceRecord>,
            contactOf: (String) -> ContactRow? = { null },
            messageOf: (String) -> MessageRow?,
        ): List<PaymentItem> {
            val rows = history.map { p ->
                val message = messageOf(p.rhash)
                val out = invoices.firstOrNull { it.paymentHash == p.rhash && !it.incoming }
                val inv = invoices.firstOrNull { it.paymentHash == p.rhash && it.incoming }
                val chat = message?.chat ?: out?.chat ?: inv?.chat
                val names = namesOf(chat, message, contactOf)
                PaymentItem(
                    id = (if (p.incoming) "in/" else "out/") + p.rhash,
                    rhash = p.rhash,
                    incoming = p.incoming,
                    amountMsat = p.amountMsat,
                    ts = p.ts,
                    status = when {
                        p.failed -> Status.FAILED
                        message?.fromMe == true && message.status == "PENDING" -> Status.PENDING
                        message?.fromMe == true && message.status == "FAILED" -> Status.FAILED
                        else -> Status.COMPLETE
                    },
                    kind = kindOf(message, out ?: inv),
                    chat = chat,
                    note = noteOf(message) ?: (out ?: inv)?.memo?.takeIf { it.isNotEmpty() },
                    error = p.error,
                    chatName = names.chatName, isTribe = names.isTribe, counterpart = names.counterpart, message = message,
                )
            }
            val known = history.map { it.incoming to it.rhash }.toSet()
            val pending = invoices.filter { !it.incoming && it.status != InvoiceRecord.Status.PAID && (false to it.paymentHash) !in known }
                .filter { it.status == InvoiceRecord.Status.PENDING || it.status == InvoiceRecord.Status.FAILED }
                .map {
                    val names = namesOf(it.chat, null, contactOf)
                    PaymentItem(
                        id = "out/" + it.paymentHash, rhash = it.paymentHash, incoming = false, amountMsat = it.amountMsat,
                        ts = it.createdAt,
                        status = if (it.status == InvoiceRecord.Status.FAILED) Status.FAILED else Status.PENDING,
                        kind = Kind.INVOICE, chat = it.chat, note = it.memo.takeIf { m -> m.isNotEmpty() }, error = it.error,
                        chatName = names.chatName, isTribe = names.isTribe, counterpart = names.counterpart,
                    )
                }
            return (rows + pending).sortedByDescending { it.ts }
        }

        private data class Names(val chatName: String?, val isTribe: Boolean, val counterpart: String?)

        /// PeelCore's chat naming: a tribe's name with the member as the
        /// counterpart (the sender of a message not ours); a contact's alias
        /// (or the start of its key) as both.
        private fun namesOf(chat: String?, message: MessageRow?, contactOf: (String) -> ContactRow?): Names {
            if (chat == null) return Names(null, false, null)
            val contact = contactOf(chat)
            if (contact?.isTribe == true) {
                val member = message?.takeIf { !it.fromMe }?.sender
                    ?.let { runCatching { org.json.JSONObject(it).optString("alias") }.getOrNull() }?.takeIf { it.isNotEmpty() }
                return Names(contact.alias?.takeIf { it.isNotEmpty() } ?: chat.take(12), true, member)
            }
            val name = contact?.alias?.takeIf { it.isNotEmpty() } ?: chat.take(12)
            return Names(name, false, name)
        }

        private fun kindOf(message: MessageRow?, invoice: InvoiceRecord?): Kind = when {
            message == null -> if (invoice != null) Kind.INVOICE else Kind.KEYSEND
            message.type == BOOST_MESSAGE_TYPE -> Kind.BOOST
            message.type == DIRECT_PAYMENT_MESSAGE_TYPE -> Kind.PAYMENT
            message.type == INVOICE_MESSAGE_TYPE || message.type == INVOICE_PAYMENT_MESSAGE_TYPE -> Kind.INVOICE
            else -> Kind.MESSAGE_FEE
        }

        /// A payment's own words, never a fee's message shown as a note.
        private fun noteOf(message: MessageRow?): String? {
            if (message == null || message.type == INVOICE_PAYMENT_MESSAGE_TYPE) return null
            if (message.type != BOOST_MESSAGE_TYPE && message.type != DIRECT_PAYMENT_MESSAGE_TYPE &&
                message.type != INVOICE_MESSAGE_TYPE
            ) return null
            return runCatching { org.json.JSONObject(message.body ?: return null).optString("content") }.getOrNull()
                ?.trim()?.takeIf { it.isNotEmpty() }
        }

        /// "Hash…tail" for a long key or hash (iOS `Formatting.shortKey`).
        fun shortKey(key: String): String = if (key.length > 12) "${key.take(6)}…${key.takeLast(4)}" else key

        /// Items by calendar day, in the list's order (newest first): the
        /// start of each day in milliseconds, then its items.
        fun byDay(items: List<PaymentItem>, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): List<Pair<Long, List<PaymentItem>>> {
            val days = mutableListOf<Pair<Long, MutableList<PaymentItem>>>()
            for (item in items) {
                val day = Calendar.getInstance(zone).apply {
                    timeInMillis = item.ts
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                if (days.lastOrNull()?.first == day) days.last().second.add(item) else days.add(day to mutableListOf(item))
            }
            return days
        }
    }
}
