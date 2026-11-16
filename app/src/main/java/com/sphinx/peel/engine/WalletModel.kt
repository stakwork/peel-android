package com.sphinx.peel.engine

import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import org.json.JSONArray
import org.json.JSONObject

// The app wallet's records and its budget (iOS `Storage+Wallet.swift`, `WalletLedger.swift`, and the enums of `WalletProtocol.swift`): what
// the person granted a page, the transactions made through a grant, and what a spending limit counts. Pure; JVM-tested (`WalletModelTest`).
// The storage that keeps them is `storage/Storage.kt` (v14, `docs/phases/31-wallet-storage.md`).

/// A wallet method strut knows (iOS `WalletMethod`).
enum class WalletMethod(val wire: String) {
    CONNECT("connect"), GET_INFO("get_info"), PAY_INVOICE("pay_invoice"), PAY_KEYSEND("pay_keysend"), MAKE_INVOICE("make_invoice"),
    LOOKUP_PAYMENT("lookup_payment"), LOOKUP_INVOICE("lookup_invoice"), GET_BUDGET("get_budget"), GET_BALANCE("get_balance"),
    LIST_TRANSACTIONS("list_transactions"), PAY_LIGHTNING_ADDRESS("pay_lightning_address");

    /// What a connection request may name: everything but `connect` and `get_info`.
    val isRequestable: Boolean get() = this != CONNECT && this != GET_INFO

    /// The methods that move money out, which a budget counts.
    val pays: Boolean get() = this == PAY_INVOICE || this == PAY_KEYSEND || this == PAY_LIGHTNING_ADDRESS

    companion object {
        fun of(wire: String?): WalletMethod? = entries.firstOrNull { it.wire == wire }
    }
}

enum class WalletNotificationType(val wire: String) {
    PAYMENT_RECEIVED("payment_received"), PAYMENT_SENT("payment_sent"), PAYMENT_FAILED("payment_failed");

    companion object {
        fun of(wire: String?): WalletNotificationType? = entries.firstOrNull { it.wire == wire }
    }
}

enum class WalletRenewal(val wire: String) {
    NEVER("never"), DAILY("daily"), WEEKLY("weekly"), MONTHLY("monthly"), YEARLY("yearly");

    companion object {
        fun of(wire: String?): WalletRenewal? = entries.firstOrNull { it.wire == wire }
    }
}

/// What to do with a payment over a connection's budget (iOS `WalletConnection.OverBudget`).
enum class OverBudget(val wire: String) {
    /// A payment sheet for each payment over budget (the default).
    ASK("ask"),
    /// `QUOTA_EXCEEDED`, and the wallet is never asked.
    REFUSE("refuse");

    companion object {
        fun of(wire: String?): OverBudget? = entries.firstOrNull { it.wire == wire }
    }
}

/// What the person granted an app, or an org, or a page Peel opened (strut's specs/WALLET.md §5): the methods, the notification types, a budget and its
/// renewal, an expiry, and what to do over budget. Kept on this device in the account's database, beside the payments its budget counts, so the count
/// and the payment are one write (docs/app-wallet.md, "Decided"). All times are unix seconds, as iOS keeps them.
data class WalletConnection(
    /// [WalletScope.key]: `org:<peer>`, or `origin:<origin>` for a page Peel loaded itself.
    val key: String,
    /// The app as strut names it (`<peer>/<name>`), when it was granted.
    val app: String,
    /// The title shown for it in Connected apps.
    val title: String,
    /// Where it lives, as the person reads it: an org, a host.
    val place: String,
    val methods: List<WalletMethod> = emptyList(),
    val notificationTypes: List<WalletNotificationType> = emptyList(),
    /// msat per period, fees included; null asks for every payment.
    val maxAmountMsat: Long? = null,
    val renewal: WalletRenewal = WalletRenewal.NEVER,
    /// Unix seconds; null for none.
    val expiresAt: Long? = null,
    val overBudget: OverBudget = OverBudget.ASK,
    /// Unix seconds: a renewal is counted from here.
    val createdAt: Long,
    /// The key the person let it sign them in with, to a public app on a box Peel is not paired with; null for none. It only signs in: payments
    /// still come from the account.
    val signIn: SignInKey? = null,
) {
    fun isExpired(now: Long): Boolean = expiresAt?.let { it <= now } ?: false

    /// What `connect` answers: the grant as the contract shapes it.
    val granted: JSONObject
        get() = JSONObject().apply {
            put("methods", JSONArray(methods.map { it.wire }))
            put("notification_types", JSONArray(notificationTypes.map { it.wire }))
            if (maxAmountMsat != null) {
                put("max_amount", maxAmountMsat)
                put("renewal_period", renewal.wire)
            }
            if (expiresAt != null) put("expires_at", expiresAt)
        }
}

/// A keysend this account sent for an app (docs/app-wallet.md): a payment to any node or Sphinx address, tracked like every send of ours by the tag the
/// server confirms, with the same forward-only statuses. Its preimage is the mixer's on COMPLETE, kept only when it hashes to the payment hash.
data class KeysendRecord(
    val paymentHash: String,
    val tag: String,
    /// The node paid.
    val pubkey: String,
    /// The core's route hint, `<lsp_pubkey>_<scid>`; null for none.
    val routeHint: String? = null,
    /// Sent the asynchronous way (a non-legacy route hint): the server holds it until the payee comes online, and a 1-sat ping went with it.
    val async: Boolean,
    val amountMsat: Long,
    /// Milliseconds since 1970.
    val createdAt: Long,
    val status: InvoiceRecord.Status,
    /// When it settled, in milliseconds.
    val paidAt: Long? = null,
    val preimage: String? = null,
    /// Why it failed, as the server put it.
    val error: String? = null,
    /// The wallet connection it was made through ([WalletConnection.key]).
    val connection: String? = null,
    /// The app it was made for, as strut names it (`<peer>/<name>`).
    val app: String? = null,
)

/// What a 1-sat ping costs a keysend sent the asynchronous way (iOS `Storage.pingPriceMsat`).
const val PING_PRICE_MSAT = 1_000L

/// One of an app's transactions as the wallet host reads it: a keysend or an invoice paid (outgoing), or an invoice made (incoming), with the connection
/// and app it was made for. The wallet contract's shapes are made from it ([payment], [transaction], [lookup]), and the budget counts it ([entry]).
data class WalletRecord(
    /// The host's id: unique per connection, stable across lookups and notifications. Peel's is the payment hash.
    val transactionId: String,
    val direction: Direction,
    val paymentType: PaymentType,
    val state: State,
    val paymentHash: String,
    /// msat: what was paid out, or what an invoice made received (else asked).
    val amountMsat: Long,
    /// Known only once settled, if at all.
    val feesMsat: Long? = null,
    /// Only a verified one, and only once settled.
    val preimage: String? = null,
    /// Unix seconds.
    val createdAt: Long,
    val settledAt: Long? = null,
    val expiresAt: Long? = null,
    val failureReason: String? = null,
    val invoice: String? = null,
    val description: String? = null,
    /// A keysend's payee.
    val pubkey: String? = null,
    val connection: String,
    /// `<peer>/<name>`, as strut names the app (`nativeAppKey`).
    val app: String,
) {
    enum class Direction(val wire: String) { INCOMING("incoming"), OUTGOING("outgoing") }
    enum class PaymentType(val wire: String) { BOLT11("bolt11"), KEYSEND("keysend") }
    enum class State(val wire: String) {
        PENDING("pending"), SETTLED("settled"), FAILED("failed"), EXPIRED("expired");

        companion object {
            fun of(wire: String?): State? = entries.firstOrNull { it.wire == wire }
        }
    }

    /// Settled with its proof: the only settled a pay method answers ([payment]). The mixer's records and its history carry no preimage, so a payment
    /// that settled while Peel was not listening has none.
    val provenSettled: Boolean get() = state == State.SETTLED && preimage != null

    /// A pay method's answer (`Payment`): settled only with its preimage, and pending otherwise until a notification or a lookup says more; the
    /// preimage only once settled; the reason only on failure.
    val payment: JSONObject
        get() = JSONObject().apply {
            put("transaction_id", transactionId); put("payment_hash", paymentHash)
            put("amount", amountMsat); put("created_at", createdAt)
            when {
                state == State.SETTLED && preimage != null -> {
                    put("state", "settled")
                    put("preimage", preimage)
                    put("settled_at", settledAt ?: createdAt)
                    feesMsat?.let { put("fees_paid", it) }
                }
                state == State.FAILED -> {
                    put("state", "failed")
                    failureReason?.let { put("failure_reason", it) }
                }
                else -> put("state", "pending")
            }
        }

    /// NWC's transaction (`lookup_invoice`, `list_transactions`, a notification's payload).
    val transaction: JSONObject
        get() = JSONObject().apply {
            put("type", direction.wire); put("state", state.wire)
            put("transaction_id", transactionId); put("payment_hash", paymentHash)
            put("amount", amountMsat); put("created_at", createdAt)
            if (state == State.SETTLED) {
                preimage?.let { put("preimage", it) }
                settledAt?.let { put("settled_at", it) }
                feesMsat?.let { put("fees_paid", it) }
            }
            if (state == State.FAILED) failureReason?.let { put("failure_reason", it) }
            invoice?.let { put("invoice", it) }
            description?.let { put("description", it) }
            expiresAt?.let { put("expires_at", it) }
        }

    /// NWC-09's lookup (`lookup_payment`), with strut's `keysend` type.
    val lookup: JSONObject
        get() {
            val details = JSONObject().put("payment_hash", paymentHash)
            if (state == State.SETTLED) preimage?.let { details.put("preimage", it) }
            if (paymentType == PaymentType.KEYSEND) {
                pubkey?.let { details.put("pubkey", it) }
            } else {
                invoice?.let { details.put("invoice", it) }
                description?.let { details.put("description", it) }
            }
            return JSONObject().apply {
                put("transaction_id", transactionId); put("type", direction.wire)
                put("state", state.wire); put("payment_type", paymentType.wire)
                put("amount", amountMsat); put("created_at", createdAt); put("details", details)
                if (state == State.SETTLED) {
                    settledAt?.let { put("settled_at", it) }
                    feesMsat?.let { put("fees_paid", it) }
                }
                if (state == State.FAILED) failureReason?.let { put("failure_reason", it) }
                expiresAt?.let { put("expires_at", it) }
            }
        }

    /// What a budget counts of it: an outgoing payment's amount while it is pending, with its fees once settled, nothing once it failed.
    val entry: WalletBudget.Entry?
        get() {
            if (direction != Direction.OUTGOING) return null
            val counted = when (state) {
                State.PENDING -> amountMsat
                State.SETTLED -> amountMsat + maxOf(feesMsat ?: 0, 0)
                State.FAILED, State.EXPIRED -> 0
            }
            return WalletBudget.Entry(maxOf(counted, 0), createdAt)
        }
}

/// A connection's budget (strut's specs/WALLET.md §5.3), counted from its transactions so it can never drift from what moved: a pending payment counts its
/// amount, a settled one its amount and fees, a failed one nothing (the refund). Counted in the same database transaction as the payment it lets through
/// (`Storage.Commit.walletBudget`). Amounts are msat in a `Long`: iOS's `UInt64` sums saturate here instead of wrapping, which no real amount reaches.
object WalletBudget {
    data class Entry(val countedMsat: Long, /* Unix seconds. */ val createdAt: Long)

    /// What a payment through a connection must fit in, checked inside the commit that writes it: nothing is published when it does not.
    data class Guard(
        /// The most the period may count, the payment included; null when the person approved this payment on its own sheet (it is counted, not limited).
        val limitMsat: Long?,
        /// The period's start, unix seconds: earlier payments do not count.
        val since: Long,
    ) {
        /// Whether [amountMsat] fits beside what [entries] already count.
        fun admits(amountMsat: Long, entries: List<Entry>): Boolean {
            val limit = limitMsat ?: return true
            val total = used(entries, since)
            return total <= Long.MAX_VALUE - amountMsat && total + amountMsat <= limit
        }
    }

    /// What the period starting at [since] (unix seconds) counts.
    fun used(entries: List<Entry>, since: Long): Long =
        entries.fold(0L) { sum, entry ->
            if (entry.createdAt < since) sum
            else if (sum > Long.MAX_VALUE - maxOf(entry.countedMsat, 0)) Long.MAX_VALUE
            else sum + maxOf(entry.countedMsat, 0)
        }

    /// The current period of a budget renewed every [renewal] from [created] (all unix seconds): its start, and when it renews (null for `never`). Months and
    /// years are calendar ones in UTC, a short month ending them early (a budget made on the 31st renews on the 30th).
    fun period(created: Long, renewal: WalletRenewal, now: Long): Pair<Long, Long?> {
        if (renewal == WalletRenewal.NEVER || now < created) return created to null
        val step = when (renewal) {
            WalletRenewal.DAILY -> 86_400L
            WalletRenewal.WEEKLY -> 7 * 86_400L
            else -> {
                val origin = Instant.ofEpochSecond(created).atZone(ZoneOffset.UTC)
                val unit = if (renewal == WalletRenewal.MONTHLY) ChronoUnit.MONTHS else ChronoUnit.YEARS
                fun boundary(k: Long) = origin.plus(k, unit).toEpochSecond()
                var n = maxOf(unit.between(origin, Instant.ofEpochSecond(now).atZone(ZoneOffset.UTC)), 0)
                while (boundary(n + 1) <= now) n++
                while (n > 0 && boundary(n) > now) n--
                return boundary(n) to boundary(n + 1)
            }
        }
        val k = (now - created) / step
        return (created + k * step) to (created + (k + 1) * step)
    }
}

/// What a connection has spent in its current period and when that renews: Connected apps (iOS `WalletHost.usage`).
data class WalletUsage(val usedMsat: Long, val renewsAt: Long?)

/// A payment through a connection would take it past its budget ([Storage.Commit.walletBudget], iOS `Engine.Failure.overBudget`): nothing was written or
/// sent. The contract's `QUOTA_EXCEEDED`.
class OverBudgetException : Exception("that payment is over the app's spending limit")
