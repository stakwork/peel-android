package com.sphinx.peel.engine

import com.sphinx.peel.storage.MessageRow
import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import uniffi.sphinxrs.RunReturn
import uniffi.sphinxrs.makeInvoice as coreMakeInvoice
import uniffi.sphinxrs.parseInvoice as coreParseInvoice
import uniffi.sphinxrs.pay as corePay
import uniffi.sphinxrs.payContactInvoice as corePayContactInvoice
import uniffi.sphinxrs.payInvoice as corePayInvoice
import uniffi.sphinxrs.requestInvoice as coreRequestInvoice
import uniffi.sphinxrs.fetchPayments as coreFetchPayments
import uniffi.sphinxrs.send as coreSend

/// Money on the engine: balance checks, the server's payment history,
/// invoices (receive, pay, request in chat) and direct payments. Ported
/// from PeelCore's `Engine+Payments.swift`; what is not ported, and why, is
/// listed in `docs/money-parity.md` (waiting-for-offline sats, the
/// reconcile of unrecorded sends, the not-arrived verdict, paid
/// attachments and templates).

/// A message of this type is a payment request: an invoice in the chat
/// (PeelCore's `MessageType.invoice`).
const val INVOICE_MESSAGE_TYPE = 2

/// The type-3 message a payer's app sends the payee after paying a request
/// (`MessageType.invoicePayment`).
const val INVOICE_PAYMENT_MESSAGE_TYPE = 3

/// Sats sent to a contact in their chat (`MessageType.directPayment`).
const val DIRECT_PAYMENT_MESSAGE_TYPE = 5

/// What a send to a person costs on top of its amount: the core sends the
/// sats the asynchronous way, with a 1-sat ping to their server that the
/// sender pays (`Storage.pingPriceMsat`). Counted against the balance only.
const val PING_MSAT = 1_000L

/// Why a payment was refused before anything was sent. `confirmMsat`, when
/// set, means "ask the person to confirm exactly this amount and call again".
class PaymentRefused(message: String, val confirmMsat: Long? = null) : Exception(message)

/// The rules that need no core: pure so they can be tested. Same numbers
/// as PeelCore.
object PaymentRules {
    /// Payments over 100 sats need confirming unless the person chose
    /// otherwise; 0 asks every time (`defaultPaymentConfirmationMsat`).
    const val DEFAULT_CONFIRMATION_MSAT = 100_000L

    /// Paying an invoice keeps back 1% for routing fees, at least 10 sats.
    fun feeReserveMsat(amountMsat: Long): Long = maxOf(amountMsat / 100, 10_000L)

    /// Whether `amountMsat` must be confirmed first: over the threshold
    /// (0 asks for everything) and not confirmed for exactly this amount.
    fun needsConfirmation(amountMsat: Long, confirmedMsat: Long?, thresholdMsat: Long): Boolean =
        confirmedMsat != amountMsat && amountMsat > thresholdMsat

    /// Whether `amountMsat` plus `reserveMsat` is within `balanceMsat`.
    fun fits(amountMsat: Long, reserveMsat: Long, balanceMsat: Long): Boolean {
        if (amountMsat > Long.MAX_VALUE - reserveMsat) return false
        return amountMsat + reserveMsat <= balanceMsat
    }

    /// `pubkey_lsp_scid` split in three — the account's own pubkey, its
    /// server's node and its channel id (`contactInfoParts`) — or null.
    fun contactInfoParts(contactInfo: String?): List<String>? =
        contactInfo?.lowercase()?.split("_")?.takeIf { it.size == 3 && it.all { part -> part.isNotEmpty() } }
}

fun Engine.paymentConfirmationMsat(): Long =
    storage.value(Setting.PAYMENT_CONFIRMATION_MSAT)?.toLongOrNull() ?: PaymentRules.DEFAULT_CONFIRMATION_MSAT

fun Engine.setPaymentConfirmation(msat: Long) {
    require(msat >= 0) { "the amount can't be negative" }
    val oldDate = storage.value(Setting.PAYMENT_CONFIRMATION_UPDATED_AT)?.toLongOrNull() ?: 0L
    val date = PreferencesMerge.nextDate(System.currentTimeMillis() / 1000, oldDate)
    storage.apply(
        Storage.Commit(
            settings = mapOf(
                Setting.PAYMENT_CONFIRMATION_MSAT to msat.toString(),
                Setting.PAYMENT_CONFIRMATION_UPDATED_AT to date.toString(),
            ),
        ),
    )
    syncPreferencesInBackground()
}

/// Refuses an amount over the last balance the server reported (less
/// `reserveMsat` kept back for routing fees or a ping), or over the
/// confirmation threshold unless the person confirmed exactly this amount.
/// An `unasked` send (a one-tap boost) is checked against the balance only.
/// PeelCore refuses when the balance is not known yet; here that is let
/// through for a boost and refused for everything else, as a balance only
/// arrives with some core call's answer and a one-tap boost has always gone
/// without one.
fun Engine.checkPayment(amountMsat: Long, confirmedMsat: Long?, unasked: Boolean = false, reserveMsat: Long = 0L) {
    if (amountMsat <= 0) throw PaymentRefused("The amount must be more than zero.")
    val balance = balanceMsat()
    if (balance == null) {
        if (!unasked) throw PaymentRefused("Your balance isn't known yet. Try again in a moment.")
    } else if (!PaymentRules.fits(amountMsat, reserveMsat, balance)) {
        throw PaymentRefused("That is more than your balance.")
    }
    if (!unasked && PaymentRules.needsConfirmation(amountMsat, confirmedMsat, paymentConfirmationMsat())) {
        throw PaymentRefused("Confirm ${Sats.format(amountMsat)} to send.", confirmMsat = amountMsat)
    }
}

// MARK: - Reading invoices

/// Reads a pasted or scanned invoice through the core (`parse_invoice`).
/// Throws `PaymentRefused` with words for the person when it isn't one.
fun Engine.decodeInvoice(text: String): Invoice {
    val bolt11 = Invoice.normalize(text) ?: throw PaymentRefused("That isn't an invoice.")
    val json = try {
        coreParseInvoice(bolt11)
    } catch (e: Exception) {
        throw PaymentRefused("Can't read that invoice.")
    }
    return Invoice.fromParsedJson(bolt11, json) ?: throw PaymentRefused("Can't read that invoice.")
}

/// An invoice from a payment request's JSON body (`{"content","invoice"}`), or null.
fun Engine.requestInvoiceOf(body: String?): Invoice? {
    val bolt11 = runCatching { JSONObject(body ?: return null).optString("invoice") }.getOrNull()?.takeIf { it.isNotEmpty() }
    return bolt11?.let { runCatching { decodeInvoice(it) }.getOrNull() }
}

/// What stops paying this invoice from here, in words, as paying it checks
/// (the confirmation threshold aside): no amount, expired, this account's
/// own, pending or paid, or more than the balance less the fee reserve.
/// Null when nothing does. Reads only storage, so a screen can ask before
/// offering Pay. PeelCore also refuses an invoice another device made
/// through the same server (`isOurRequest`) and subtracts sats waiting for
/// people to come online; neither is tracked here.
fun Engine.invoiceProblem(invoice: Invoice): String? {
    if (invoice.amountMsat <= 0) return "This invoice names no amount, so it can't be paid here."
    if (invoice.isExpired()) return "This invoice has expired. Nothing was paid."
    val parts = PaymentRules.contactInfoParts(storage.value(Setting.CONTACT_INFO))
    if (invoice.payee == parts?.get(0)) return "This is your own invoice."
    if (storage.invoice(invoice.paymentHash, incoming = true) != null) return "This is your own invoice."
    when (storage.invoice(invoice.paymentHash, incoming = false)?.status) {
        InvoiceRecord.Status.PENDING -> return "This invoice is being paid."
        InvoiceRecord.Status.PAID -> return "This invoice was already paid."
        else -> {}
    }
    if (storage.payments(Int.MAX_VALUE).any { it.rhash == invoice.paymentHash && !it.incoming && !it.failed }) {
        return "This invoice was already paid."
    }
    val balance = balanceMsat() ?: return "Your balance isn't known yet."
    if (!PaymentRules.fits(invoice.amountMsat, PaymentRules.feeReserveMsat(invoice.amountMsat), balance)) {
        return "That is more than your balance (with room for routing fees)."
    }
    return null
}

// MARK: - Payment history

const val PAYMENT_PAGE_SIZE = 50u

/// Fetches a page of the server's payment history, newest first, older
/// than `beforeMs` (null for the newest), and stores it. Returns how many
/// rows came: fewer than `PAYMENT_PAGE_SIZE` means the end. The server's
/// answer names no request, so one fetch at a time (`moneyLock`).
suspend fun Engine.fetchPaymentHistory(beforeMs: Long? = null): Int = fetchPaymentPage(beforeMs).size

private suspend fun Engine.fetchPaymentPage(beforeMs: Long?): List<PaymentRecord> = moneyLock.withLock {
    if (!isConnected) throw PaymentRefused("Not connected.")
    // The newest page starts past any time, not this phone's clock: one
    // behind the server's would leave out its newest rows.
    val since = beforeMs?.let { maxOf(it - 1, 0L).toULong() } ?: Long.MAX_VALUE.toULong()
    val seed = seed
    val answer = askAndAwait(30_000, expect = { it.payments != null }) {
        run { time, state -> coreFetchPayments(seed, time, state, since, PAYMENT_PAGE_SIZE, null, false, 0uL, true) }
    } ?: throw PaymentRefused("The server did not answer. Try again.")
    PaymentRecord.decode(answer.payments)
}

/// Fetches what is new since the history was last fetched: the newest
/// page, and older ones until they reach what is stored, so a busy spell
/// leaves no gap. At most 20 pages a time.
suspend fun Engine.refreshPaymentHistory() {
    val newestStored = storage.newestPaymentTimestamp()
    var before: Long? = null
    repeat(20) {
        val rows = fetchPaymentPage(before)
        val oldest = rows.minOfOrNull { it.ts }
        if (rows.size < PAYMENT_PAGE_SIZE.toInt() || newestStored == null || oldest == null || oldest <= newestStored) return
        before = oldest
    }
}

/// Fetches the page older than what is stored; returns how many rows came.
suspend fun Engine.fetchOlderPaymentHistory(): Int = fetchPaymentHistory(storage.oldestPaymentTimestamp())

// MARK: - Receive

/// Makes an invoice to receive `amountMsat`, as the official apps do: the
/// server's Lightning node makes it (`request_invoice`) and credits this
/// account when it is paid, so it is paid even while Peel is closed. The
/// payment shows only as a balance change and a row in the server's
/// history, which marks the invoice paid (`storeMoney`). An amount is
/// required.
suspend fun Engine.createInvoice(amountMsat: Long, memo: String = "", chat: String? = null): InvoiceRecord = moneyLock.withLock {
    if (amountMsat <= 0) throw PaymentRefused("The amount must be more than zero.")
    val parts = PaymentRules.contactInfoParts(storage.value(Setting.CONTACT_INFO))
    if (!isRegistered || !isConnected || parts == null) throw PaymentRefused("Not connected.")
    val cleanMemo = memo.trim()
    if (cleanMemo.toByteArray().size > 300) throw PaymentRefused("The memo is too long.")
    val seed = seed
    val server = parts[1]
    val answer = askAndAwait(30_000, expect = { it.invoice != null }) {
        run { time, state -> coreRequestInvoice(seed, time, state, amountMsat.toULong(), cleanMemo) }
    } ?: throw PaymentRefused("The server made no invoice. It may have no Lightning node.")
    val invoice = decodeInvoice(answer.invoice!!)
    // Checked against what was asked: its node is the payee, for exactly the
    // amount and memo, and not expired (refuses a late answer to an earlier request).
    if (invoice.payee != server || invoice.amountMsat != amountMsat || invoice.memo != cleanMemo || invoice.isExpired()) {
        throw PaymentRefused("The server's invoice didn't match what was asked for.")
    }
    val record = InvoiceRecord(
        paymentHash = invoice.paymentHash, incoming = true, bolt11 = invoice.bolt11, amountMsat = amountMsat,
        memo = invoice.memo, payee = invoice.payee, createdAt = System.currentTimeMillis(),
        expiresAt = invoice.expiresAtMs, status = InvoiceRecord.Status.OPEN, chat = chat,
    )
    storage.saveInvoice(record)
    record
}

/// Asks a contact for sats in their chat (type 2) with an invoice the
/// server makes for this account. Returns the invoice.
suspend fun Engine.requestPayment(chat: String, amountMsat: Long, memo: String = ""): InvoiceRecord {
    requirePerson(chat, "request")
    val record = createInvoice(amountMsat, memo, chat)
    val body = JSONObject().put("content", record.memo).put("invoice", record.bolt11).toString()
    deliverMoney(chat, INVOICE_MESSAGE_TYPE, body, 0L, record.paymentHash)
    return record
}

// MARK: - Pay

/// Pays a pasted or scanned invoice. Refuses one that is expired, names no
/// amount, is this account's own, was paid before or is being paid; over the
/// balance less a fee reserve; and over the confirmation threshold unless
/// confirmed. The payment is recorded as pending before anything is
/// published, and stays so, never to be paid again, until the server says
/// how it went.
suspend fun Engine.payInvoice(bolt11: String, confirmedMsat: Long? = null): InvoiceRecord =
    pay(decodeInvoice(bolt11), chat = null, confirmedMsat = confirmedMsat)

/// Pays someone's payment request in their chat. When the invoice pays
/// them, the payment carries the type-3 message that tells their app, as
/// the official apps do; otherwise it pays the invoice as pasted.
suspend fun Engine.payRequest(row: MessageRow, confirmedMsat: Long? = null): InvoiceRecord {
    if (row.type != INVOICE_MESSAGE_TYPE || row.fromMe) throw PaymentRefused("That isn't a payment request.")
    val invoice = requestInvoiceOf(row.body) ?: throw PaymentRefused("Can't read this invoice, so it can't be paid.")
    return pay(invoice, chat = row.chat, confirmedMsat = confirmedMsat)
}

private suspend fun Engine.pay(invoice: Invoice, chat: String?, confirmedMsat: Long?): InvoiceRecord {
    invoiceProblem(invoice)?.let { throw PaymentRefused(it) }
    // The balance and fee reserve are `invoiceProblem`'s; this asks the threshold.
    checkPayment(invoice.amountMsat, confirmedMsat)
    if (!isConnected) throw PaymentRefused("Not connected.")
    val hash = invoice.paymentHash
    if (!sendsInFlight.add(hash)) throw PaymentRefused("This invoice is being paid.")
    try {
        val seed = seed
        val alias = storage.value(Setting.ALIAS) ?: ""
        val photo = storage.value(Setting.PHOTO_URL) ?: ""
        val parts = PaymentRules.contactInfoParts(storage.value(Setting.CONTACT_INFO))
        val toContact = chat != null && invoice.payee == chat && storage.contact(chat)?.isTribe == false
        var record: InvoiceRecord? = null
        fun pending(result: RunReturn): InvoiceRecord {
            val tag = (result.msgs.firstOrNull { it.paymentHash == hash } ?: result.msgs.firstOrNull())?.tag
                ?: throw PaymentRefused("The core returned no tag for the payment.")
            return InvoiceRecord(
                paymentHash = hash, incoming = false, bolt11 = invoice.bolt11, amountMsat = invoice.amountMsat,
                memo = invoice.memo, payee = invoice.payee, createdAt = System.currentTimeMillis(),
                expiresAt = invoice.expiresAtMs, status = InvoiceRecord.Status.PENDING, tag = tag, chat = chat,
            )
        }
        // Written with the state, before publishing; refused (and nothing
        // published) if the invoice is already being paid or paid.
        val record0 = fun(result: RunReturn) {
            val made = pending(result)
            if (!storage.saveInvoice(made)) throw PaymentRefused("This invoice is being paid.")
            record = made
        }
        try {
            when {
                toContact -> run(record0) { time, state -> corePayContactInvoice(seed, time, state, invoice.bolt11, alias, photo, false) }
                // Made by our own server's node (`request_invoice`): the server moves
                // the sats between its accounts. An onion ending at its own node it refuses.
                invoice.payee == parts?.get(1) -> run(record0) { time, state -> corePay(seed, time, state, invoice.bolt11) }
                else -> try {
                    run(record0) { time, state -> corePayInvoice(seed, time, state, invoice.bolt11, invoice.amountMsat.toULong()) }
                } catch (e: uniffi.sphinxrs.SphinxException) {
                    // No route this account can build: the server pays it (its own
                    // node finds the route), as the official apps fall back.
                    if (invoice.hopHints.isNotEmpty()) throw e
                    run(record0) { time, state -> corePay(seed, time, state, invoice.bolt11) }
                }
            }
        } catch (e: PaymentRefused) {
            throw e
        } catch (e: uniffi.sphinxrs.SphinxException) {
            throw PaymentRefused("Could not pay: ${e.message ?: "the core refused"}")
        }
        return record ?: throw PaymentRefused("The core returned no tag for the payment.")
    } finally {
        sendsInFlight.remove(hash)
    }
}

// MARK: - Direct payments

/// Sends sats to a contact in their chat with an optional note (type 5).
/// Only to a person: in a tribe it would pay the tribes server.
suspend fun Engine.sendPayment(chat: String, amountMsat: Long, text: String = "", confirmedMsat: Long? = null): MessageRow {
    requirePerson(chat, "pay")
    checkPayment(amountMsat, confirmedMsat, reserveMsat = PING_MSAT)
    if (!isConnected) throw PaymentRefused("Not connected.")
    val body = JSONObject().put("content", text.trim()).apply {
        timeZoneMetadata(chat, isTribe = false)?.let { put("metadata", it) }
    }.toString()
    return deliverMoney(chat, DIRECT_PAYMENT_MESSAGE_TYPE, body, amountMsat, null)
}

private fun Engine.requirePerson(chat: String, verb: String) {
    val contact = storage.contact(chat)
    if (contact?.isTribe == true) throw PaymentRefused("You can only $verb a person, not a tribe.")
    if (contact == null) throw PaymentRefused("That isn't one of your contacts.")
}

/// One typed message stored as sent (PENDING until its echo or the mixer's
/// word comes), as `Engine.sendMessage` does for text.
private suspend fun Engine.deliverMoney(chat: String, type: Int, body: String, amountMsat: Long, paymentHash: String?): MessageRow {
    if (!isConnected) throw PaymentRefused("Not connected.")
    val seed = seed
    val alias = storage.value(Setting.ALIAS) ?: ""
    val photo = storage.value(Setting.PHOTO_URL) ?: ""
    val result = try {
        run { time, state -> coreSend(seed, time, chat, type.toUByte(), body, state, alias, photo, amountMsat.toULong(), false) }
    } catch (e: uniffi.sphinxrs.SphinxException) {
        throw PaymentRefused("Could not send: ${e.message ?: "the core refused"}")
    }
    val sent = result.msgs.firstOrNull()
    val row = MessageRow(
        uuid = sent?.uuid, serverIndex = null, type = type, chat = chat, fromMe = true, sender = null, body = body,
        msat = amountMsat.takeIf { it > 0 }, timestamp = System.currentTimeMillis(), tag = sent?.tag,
        paymentHash = sent?.paymentHash ?: paymentHash, status = "PENDING",
    )
    storage.insertMessages(listOf(row))
    return row
}

// MARK: - What the server tells us

/// Brings what this account believes about its money in line with what a
/// core answer carries: history rows (stored, and settling the invoices
/// they name: a payment to an invoice made here arrives only as a history
/// row, and our own sends left pending are settled or failed by theirs) and
/// type-3 messages (the payer telling us a request was paid).
internal fun Engine.storeMoney(result: RunReturn, rows: List<MessageRow>) {
    val history = PaymentRecord.decode(result.payments)
    if (history.isNotEmpty()) {
        storage.insertPayments(history)
        for (payment in history) {
            if (payment.incoming) {
                if (!payment.failed) storage.settleInvoice(payment.rhash, true, payment.amountMsat, payment.ts)
            } else if (payment.failed) {
                storage.failPendingInvoice(payment.rhash, payment.error)
            } else {
                storage.settleInvoice(payment.rhash, false, payment.amountMsat, payment.ts)
            }
        }
    }
    for (row in rows) {
        val hash = row.paymentHash
        if (row.type == INVOICE_PAYMENT_MESSAGE_TYPE && !row.fromMe && hash != null) {
            storage.settleInvoice(hash, true, row.msat, row.timestamp ?: System.currentTimeMillis())
        }
    }
}
