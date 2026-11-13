package com.sphinx.peel.engine

import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import kotlinx.coroutines.CancellationException

// The engine's side of the app wallet (iOS `Engine+Wallet.swift`; docs/phases/32-app-payments.md): a keysend to any node or Sphinx address, an invoice paid or
// made for an app, each tagged with the wallet connection it went through and counted against that connection's budget in the same commit. The logic is here,
// over small seams ([PaymentRunner]) so it runs on the JVM with fakes; what needs the native core is [Engine]'s implementation of the seam (`Engine.paymentRunner`).

/// An app's payment or invoice: the wallet connection it goes through ([WalletConnection.key]), the app (`<peer>/<name>`), and for a payment the budget it
/// must fit in (iOS `Engine.WalletTag`).
data class WalletTag(
    val connection: String,
    val app: String,
    /// Checked in the commit that writes the payment; null for an invoice made.
    val budget: WalletBudget.Guard? = null,
)

/// Why an app's payment was refused before or in its own write (iOS `Engine.Failure`, the cases the wallet reaches), with the words it says and the code it
/// becomes (`EngineWallet.walletError`).
enum class AppFailure(val description: String, val code: WalletError) {
    INSUFFICIENT_BALANCE("the balance is not enough for that", WalletError.INSUFFICIENT_BALANCE),
    OVER_BUDGET("that is over what this app may spend without asking", WalletError.QUOTA_EXCEEDED),
    INVALID_AMOUNT("the amount must be more than nothing", WalletError.BAD_REQUEST),
    NOT_A_NODE_KEY("that is not a node's key", WalletError.BAD_REQUEST),
    KEYSEND_TO_SELF("that is this account's own key", WalletError.BAD_REQUEST),
    UNREADABLE_INVOICE("that is not an invoice Peel can read", WalletError.BAD_REQUEST),
    MEMO_TOO_LONG("the memo is too long", WalletError.BAD_REQUEST),
    INVOICE_WITHOUT_AMOUNT("that invoice names no amount; Peel only pays invoices that say how much", WalletError.NOT_IMPLEMENTED),
    BALANCE_UNKNOWN("the balance is not known yet; connect first", WalletError.OTHER),
    NOT_CONNECTED("not connected to the server", WalletError.PAYMENT_FAILED),
    INVOICE_EXPIRED("that invoice has expired", WalletError.PAYMENT_FAILED),
    INVOICE_ALREADY_PAID("that invoice has already been paid", WalletError.PAYMENT_FAILED),
    INVOICE_PENDING("a payment of that invoice is still on its way", WalletError.PAYMENT_FAILED),
    OWN_INVOICE("that is this account's own invoice", WalletError.PAYMENT_FAILED),
    CORE_RETURNED_NOTHING("the core returned nothing for the payment", WalletError.PAYMENT_FAILED),
    ;

    /// The contract's error, in the engine's words: the description capitalised, with a full stop (iOS `EngineWallet.walletError`).
    val refusal: WalletRefusal get() = WalletRefusal(code, description.replaceFirstChar { it.uppercase() } + ".")
}

/// An app's payment that did not go: why ([failure]).
class AppPaymentFailure(val failure: AppFailure) : Exception(failure.description)

/// How a core call's result was published (iOS `commitAndPublish`'s `.all`/`.unknown`; nothing at all published is [AppFailure.NOT_CONNECTED], thrown before).
sealed interface Published {
    data object All : Published

    /// Published, but a write may or may not have left: the payment stays pending and is never sent again; the server's word settles it.
    data class Unknown(val error: Throwable) : Published
}

/// What the engine gives the app wallet's payments (iOS `Engine` itself): the account, the connection, and one core call run, committed with the rows the
/// payment needs **in the same write as the core's own state**, and only then published. [Engine.paymentRunner] is the real one.
interface PaymentRunner {
    /// The account's own key (a keysend to it is refused).
    val accountKey: String

    /// This account's own pubkey as a payee of an invoice, and its server's (`contact info` `<pubkey>_<server>_<scid>`); null before it is registered.
    val ownKeys: Pair<String, String>?
    val isConnected: Boolean
    val network: String
    fun nowMs(): Long

    /// Reads an invoice through the core (`parse_invoice`); throws on one it cannot read.
    fun decodeInvoice(bolt11: String): Invoice

    /// Runs the core's keysend, then [extra] with the payment's tag and hash, committed together with the core's state; a refusal thrown by that write
    /// ([OverBudgetException], a repeated hash) abandons both and publishes nothing. Then publishes.
    suspend fun sendKeysend(to: String, amountMsat: Long, data: ByteArray?, routeHint: String?, extra: (tag: String?, hash: String?) -> Storage.Commit): Published

    /// The same for paying [invoice] (its tag and hash from the core's message for it).
    suspend fun sendInvoice(invoice: Invoice, extra: (tag: String?, hash: String?) -> Storage.Commit): Published
}

class AppPayments(private val storage: Storage, private val runner: PaymentRunner) {
    private val inFlight: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    /// What the balance must cover for an app's payment: the amount and its reserve, from the balance less what waits; never the person's confirmation
    /// threshold (iOS `checkPayment(_, .unasked, reserve:)`).
    private fun checkPayment(amountMsat: Long, reserveMsat: Long) {
        val balance = storage.spendableMsat(runner.nowMs()) ?: throw AppPaymentFailure(AppFailure.BALANCE_UNKNOWN)
        if (amountMsat > Long.MAX_VALUE - reserveMsat || amountMsat + reserveMsat > balance) throw AppPaymentFailure(AppFailure.INSUFFICIENT_BALANCE)
    }

    /// Sends [amountMsat] to [pubkey] as a keysend, over the core's own `keysend`: to any node, or with [routeHint] (`<lsp_pubkey>_<scid>`, a Sphinx
    /// address's tail) the asynchronous way, held by the server until the payee comes online, with a 1-sat ping. [data] rides in the core's record 13377331, as
    /// base64. Checked as every send of ours (the balance less what waits, less a fee reserve and the ping) but never against the person's confirmation
    /// threshold: an app's payments are held to their connection's budget instead, in the commit ([WalletTag.budget]), which refuses and publishes nothing
    /// when it does not fit. Recorded as pending before anything is published, so it is never sent twice; the server's word settles it, with its preimage
    /// when it gives one.
    suspend fun keysend(to: String, amountMsat: Long, routeHint: String? = null, data: ByteArray? = null, wallet: WalletTag): KeysendRecord {
        if (amountMsat <= 0) throw AppPaymentFailure(AppFailure.INVALID_AMOUNT)
        val pubkey = to.lowercase()
        if (!WalletProtocol.isPubkey(pubkey)) throw AppPaymentFailure(AppFailure.NOT_A_NODE_KEY)
        if (pubkey == runner.accountKey) throw AppPaymentFailure(AppFailure.KEYSEND_TO_SELF)
        if (routeHint != null) {
            val parts = routeHint.split("_").filter { it.isNotEmpty() }
            if (parts.size != 2 || !WalletProtocol.isPubkey(parts[0]) || parts[1].toULongOrNull() == null || !parts[1].all { it in '0'..'9' }) {
                throw AppPaymentFailure(AppFailure.NOT_A_NODE_KEY)
            }
        }
        // The core pays the asynchronous way with a hint that is not legacy (`pk:scid`).
        val async = routeHint != null
        checkPayment(amountMsat, PaymentRules.feeReserveMsat(amountMsat) + if (async) PING_PRICE_MSAT else 0)
        if (!runner.isConnected) throw AppPaymentFailure(AppFailure.NOT_CONNECTED)
        var record: KeysendRecord? = null
        val published = try {
            runner.sendKeysend(pubkey, amountMsat, data, routeHint) { tag, rawHash ->
                val hash = rawHash?.lowercase()?.takeIf { WalletProtocol.isHash(it) }
                if (tag == null || hash == null) throw AppPaymentFailure(AppFailure.CORE_RETURNED_NOTHING)
                val made = KeysendRecord(
                    paymentHash = hash, tag = tag, pubkey = pubkey, routeHint = routeHint, async = async, amountMsat = amountMsat,
                    createdAt = runner.nowMs(), status = InvoiceRecord.Status.PENDING, connection = wallet.connection, app = wallet.app,
                )
                record = made
                Storage.Commit(keysends = listOf(made), walletBudget = wallet.budget?.let { Storage.Commit.WalletBudgetCheck(wallet.connection, amountMsat, it) })
            }
        } catch (e: OverBudgetException) {
            throw AppPaymentFailure(AppFailure.OVER_BUDGET)
        }
        val made = record ?: throw AppPaymentFailure(AppFailure.CORE_RETURNED_NOTHING)
        // `Unknown`: it may have gone, so it stays pending (never sent again) until the server says how it went.
        check(published is Published.All || published is Published.Unknown)
        return storage.keysendByHash(made.paymentHash) ?: made
    }

    /// What stops paying this invoice for an app, before anyone is asked (iOS `invoiceProblem`): no amount, expired, this account's own, pending or paid, or
    /// more than the balance (less what waits) with room for routing fees. Null when nothing does.
    fun invoiceProblem(invoice: Invoice): AppFailure? {
        if (invoice.amountMsat <= 0) return AppFailure.INVOICE_WITHOUT_AMOUNT
        if (invoice.isExpired(runner.nowMs())) return AppFailure.INVOICE_EXPIRED
        if (invoice.payee == runner.ownKeys?.first) return AppFailure.OWN_INVOICE
        if (storage.invoice(invoice.paymentHash, incoming = true) != null) return AppFailure.OWN_INVOICE
        when (storage.invoice(invoice.paymentHash, incoming = false)?.status) {
            InvoiceRecord.Status.PENDING -> return AppFailure.INVOICE_PENDING
            InvoiceRecord.Status.PAID -> return AppFailure.INVOICE_ALREADY_PAID
            else -> {}
        }
        if (storage.payments(Int.MAX_VALUE).any { it.rhash == invoice.paymentHash && !it.incoming && !it.failed }) return AppFailure.INVOICE_ALREADY_PAID
        val balance = storage.spendableMsat(runner.nowMs()) ?: return AppFailure.BALANCE_UNKNOWN
        val reserve = PaymentRules.feeReserveMsat(invoice.amountMsat)
        return if (invoice.amountMsat > Long.MAX_VALUE - reserve || invoice.amountMsat + reserve > balance) AppFailure.INSUFFICIENT_BALANCE else null
    }

    /// Pays an invoice for an app: never asked against the person's threshold, held to the connection's budget in the commit (iOS `payInvoice(_:for:)`).
    suspend fun payInvoice(invoice: Invoice, wallet: WalletTag): InvoiceRecord {
        invoiceProblem(invoice)?.let { throw AppPaymentFailure(it) }
        if (!runner.isConnected) throw AppPaymentFailure(AppFailure.NOT_CONNECTED)
        val hash = invoice.paymentHash
        if (!inFlight.add(hash)) throw AppPaymentFailure(AppFailure.INVOICE_PENDING)
        try {
            var record: InvoiceRecord? = null
            try {
                runner.sendInvoice(invoice) { tag, _ ->
                    if (tag == null) throw AppPaymentFailure(AppFailure.CORE_RETURNED_NOTHING)
                    val made = InvoiceRecord(
                        paymentHash = hash, incoming = false, bolt11 = invoice.bolt11, amountMsat = invoice.amountMsat, memo = invoice.memo,
                        payee = invoice.payee, createdAt = runner.nowMs(), expiresAt = invoice.expiresAtMs, status = InvoiceRecord.Status.PENDING,
                        tag = tag, connection = wallet.connection, app = wallet.app,
                    )
                    record = made
                    Storage.Commit(invoices = listOf(made), walletBudget = wallet.budget?.let { Storage.Commit.WalletBudgetCheck(wallet.connection, invoice.amountMsat, it) })
                }
            } catch (e: OverBudgetException) {
                throw AppPaymentFailure(AppFailure.OVER_BUDGET)
            } catch (e: IllegalStateException) {
                // The write refused an invoice already being paid or paid.
                throw AppPaymentFailure(AppFailure.INVOICE_PENDING)
            }
            return record ?: throw AppPaymentFailure(AppFailure.CORE_RETURNED_NOTHING)
        } finally {
            inFlight.remove(hash)
        }
    }
}

/// What the wallet host's wallet needs of a payment (iOS `WalletInvoice`).
data class WalletInvoice(
    val bolt11: String, val paymentHash: String, val amountMsat: Long, val payee: String, val memo: String, val expiresAt: Long?,
) {
    constructor(invoice: Invoice) : this(
        invoice.bolt11, invoice.paymentHash, invoice.amountMsat, invoice.payee, invoice.memo, invoice.expiresAtMs?.let { it / 1000 },
    )
}

sealed interface WalletPayment {
    val amountMsat: Long

    data class OfInvoice(val invoice: WalletInvoice) : WalletPayment { override val amountMsat get() = invoice.amountMsat }
    data class OfKeysend(val pubkey: String, override val amountMsat: Long, val routeHint: String?, val data: ByteArray?) : WalletPayment
}

/// The wallet behind the host (iOS `AppWallet`): Peel's Sphinx account, or a script in tests.
interface AppWallet {
    suspend fun network(): String
    suspend fun decode(invoice: String): WalletInvoice
    suspend fun problem(payment: WalletPayment): WalletRefusal?
    suspend fun pay(payment: WalletPayment, tag: WalletTag): WalletRecord
    suspend fun makeInvoice(amountMsat: Long, description: String, tag: WalletTag): WalletRecord
    suspend fun records(connection: String): List<WalletRecord>
    suspend fun balance(): Long
    suspend fun payeeName(pubkey: String): String?
    suspend fun signVisitor(challenge: VisitorChallenge, key: SignInKey): VisitorSignature
    val signsPerDomain: Boolean
    fun updates(): kotlinx.coroutines.flow.Flow<WalletRecord>
}

/// What makes an invoice for an app (iOS `Engine.createInvoice(amountMsat:memo:for:)`): the server's node makes it, so it settles while Peel is closed.
fun interface InvoiceMaker {
    suspend fun make(amountMsat: Long, memo: String, wallet: WalletTag): InvoiceRecord
}

/// The wallet host's wallet over an engine (iOS `EngineWallet`): Peel's Sphinx account (strut's specs/WALLET.md §6, "Sphinx (Peel)").
class EngineWallet(
    private val storage: Storage,
    private val payments: AppPayments,
    private val runner: PaymentRunner,
    private val invoices: InvoiceMaker,
    private val signer: VisitorSigner,
    /// How long a payment's answer waits for the server's word before it is told as pending (and notified later).
    private val settleWaitMs: Long = 5_000,
) : AppWallet {
    /// The account's network, as the contract names networks.
    override suspend fun network() = runner.network.takeIf { it in listOf("bitcoin", "testnet", "signet", "regtest") } ?: "bitcoin"

    override suspend fun decode(invoice: String): WalletInvoice =
        try { WalletInvoice(runner.decodeInvoice(invoice)) } catch (e: Exception) { throw WalletRefusal(WalletError.BAD_REQUEST, "Peel could not read that invoice.") }

    override suspend fun problem(payment: WalletPayment): WalletRefusal? = when (payment) {
        is WalletPayment.OfInvoice -> {
            val read = try { runner.decodeInvoice(payment.invoice.bolt11) } catch (e: Exception) { return WalletRefusal(WalletError.BAD_REQUEST, "Peel could not read that invoice.") }
            payments.invoiceProblem(read)?.refusal
        }
        is WalletPayment.OfKeysend -> {
            val balance = storage.spendableMsat(runner.nowMs())
            if (balance == null) {
                WalletRefusal(WalletError.OTHER, AppFailure.BALANCE_UNKNOWN.description)
            } else {
                val reserve = PaymentRules.feeReserveMsat(payment.amountMsat) + if (payment.routeHint != null) PING_PRICE_MSAT else 0
                when {
                    payment.amountMsat + reserve > balance -> WalletRefusal(WalletError.INSUFFICIENT_BALANCE, AppFailure.INSUFFICIENT_BALANCE.description)
                    payment.pubkey == runner.accountKey -> AppFailure.KEYSEND_TO_SELF.refusal
                    else -> null
                }
            }
        }
    }

    override suspend fun pay(payment: WalletPayment, tag: WalletTag): WalletRecord {
        val sentTag: String
        val hash: String
        try {
            when (payment) {
                is WalletPayment.OfInvoice -> {
                    val record = payments.payInvoice(runner.decodeInvoice(payment.invoice.bolt11), tag)
                    sentTag = record.tag ?: ""
                    hash = record.paymentHash
                }
                is WalletPayment.OfKeysend -> {
                    val record = payments.keysend(payment.pubkey, payment.amountMsat, payment.routeHint, payment.data, tag)
                    sentTag = record.tag
                    hash = record.paymentHash
                }
            }
        } catch (e: AppPaymentFailure) {
            throw e.failure.refusal
        } catch (e: WalletRefusal) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw WalletRefusal(WalletError.PAYMENT_FAILED, e.message ?: "The payment failed.")
        }
        if (sentTag.isNotEmpty()) awaitFinal(sentTag)
        return storage.walletRecords(tag.connection).firstOrNull { it.paymentHash == hash && it.direction == WalletRecord.Direction.OUTGOING }
            ?: throw WalletRefusal(WalletError.INTERNAL, "the payment was sent but Peel could not read it back")
    }

    /// Waits up to the settle wait for the send [tag] names to settle or fail, for an answer that can say how it went: a payment the server answers at once (a
    /// payee online) is told as settled with its proof. Returns as soon as it has; a wait that runs out returns too.
    private suspend fun awaitFinal(tag: String) {
        // The wait is real time, whatever the account's clock says.
        val deadline = System.nanoTime() + settleWaitMs * 1_000_000
        while (System.nanoTime() < deadline) {
            val status = (storage.keysend(tag)?.status ?: storage.invoiceByTag(tag)?.status)
            if (status == InvoiceRecord.Status.PAID || status == InvoiceRecord.Status.FAILED) return
            kotlinx.coroutines.delay(25)
        }
    }

    override suspend fun makeInvoice(amountMsat: Long, description: String, tag: WalletTag): WalletRecord {
        val record = try {
            invoices.make(amountMsat, description, tag)
        } catch (e: AppPaymentFailure) {
            throw e.failure.refusal
        }
        return storage.walletRecords(tag.connection).firstOrNull { it.paymentHash == record.paymentHash && it.direction == WalletRecord.Direction.INCOMING }
            ?: throw WalletRefusal(WalletError.INTERNAL, "the invoice was made but Peel could not read it back")
    }

    override suspend fun records(connection: String): List<WalletRecord> = storage.walletRecords(connection)

    override suspend fun balance(): Long = storage.spendableMsat(runner.nowMs()) ?: throw WalletRefusal(WalletError.OTHER, AppFailure.BALANCE_UNKNOWN.description)

    override suspend fun payeeName(pubkey: String): String? = storage.contactAlias(pubkey)

    override suspend fun signVisitor(challenge: VisitorChallenge, key: SignInKey): VisitorSignature = signer.sign(challenge, key)

    /// A key of one domain alone: LUD-05's linking key from the words.
    override val signsPerDomain: Boolean get() = true

    /// Each change of an app's transaction to settled or failed (or an invoice made to settled or expired), whichever way it came: the server's live word, its
    /// records, its history. The first read is where it starts; nothing before it is told.
    override fun updates(): kotlinx.coroutines.flow.Flow<WalletRecord> = kotlinx.coroutines.flow.flow {
        val known = mutableMapOf<String, WalletRecord.State>()
        var first = true
        storage.walletRecordUpdates().collect { records ->
            for (record in records) {
                val key = "${record.connection}|${record.direction.wire}|${record.transactionId}"
                val before = known[key]
                known[key] = record.state
                if (!first && before != record.state && record.state != WalletRecord.State.PENDING) emit(record)
            }
            first = false
        }
    }
}
