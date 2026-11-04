package com.sphinx.peel.ui.payments

import com.sphinx.peel.engine.BOOST_MESSAGE_TYPE
import com.sphinx.peel.engine.DIRECT_PAYMENT_MESSAGE_TYPE
import com.sphinx.peel.engine.INVOICE_MESSAGE_TYPE
import com.sphinx.peel.engine.INVOICE_PAYMENT_MESSAGE_TYPE
import com.sphinx.peel.engine.InvoiceRecord
import com.sphinx.peel.engine.PaymentRecord
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

    companion object {
        /// The history joined to messages (`messageOf`, by payment hash) and
        /// invoice records, newest first. A send of ours still pending, with
        /// no history row yet, is listed too; a pending record is greyed,
        /// a failed one struck through, neither red.
        fun build(
            history: List<PaymentRecord>,
            invoices: List<InvoiceRecord>,
            messageOf: (String) -> MessageRow?,
        ): List<PaymentItem> {
            val rows = history.map { p ->
                val message = messageOf(p.rhash)
                val out = invoices.firstOrNull { it.paymentHash == p.rhash && !it.incoming }
                val inv = invoices.firstOrNull { it.paymentHash == p.rhash && it.incoming }
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
                    chat = message?.chat ?: out?.chat ?: inv?.chat,
                    note = noteOf(message) ?: (out ?: inv)?.memo?.takeIf { it.isNotEmpty() },
                    error = p.error,
                )
            }
            val known = history.map { it.incoming to it.rhash }.toSet()
            val pending = invoices.filter { !it.incoming && it.status != InvoiceRecord.Status.PAID && (false to it.paymentHash) !in known }
                .filter { it.status == InvoiceRecord.Status.PENDING || it.status == InvoiceRecord.Status.FAILED }
                .map {
                    PaymentItem(
                        id = "out/" + it.paymentHash, rhash = it.paymentHash, incoming = false, amountMsat = it.amountMsat,
                        ts = it.createdAt,
                        status = if (it.status == InvoiceRecord.Status.FAILED) Status.FAILED else Status.PENDING,
                        kind = Kind.INVOICE, chat = it.chat, note = it.memo.takeIf { m -> m.isNotEmpty() }, error = it.error,
                    )
                }
            return (rows + pending).sortedByDescending { it.ts }
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
