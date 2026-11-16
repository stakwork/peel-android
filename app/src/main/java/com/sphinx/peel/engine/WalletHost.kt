package com.sphinx.peel.engine

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

// The wallet Peel offers a strut app's page (iOS `StrutWallet`), the sign-in slice only: `host_capability` and
// `sign_in`. Android has no app-wallet host, so `buy_credit` and the spending-wallet `call`s are not here
// (docs/phases/27-sign-in-with-sphinx.md). Pure: storage, signer, the person and the clock are injected; JVM-tested.

/// Where connections are kept ([com.sphinx.peel.storage.Storage] implements it through [StorageWalletConnections]).
interface WalletConnections {
    fun connection(key: String): WalletConnection?
    fun save(connection: WalletConnection)
}

/// What the person is asked in the sign-in sheet. [app] is the page's own claim, cleaned for one line.
data class SignInAsk(val host: String, val app: SignInApp, val keys: List<SignInKey>)

/// What the connect sheet shows: who asks, from Peel's own record of the page, and what for (iOS `WalletConnectSheet`). Signing in rides the same sheet ([signIn]).
data class WalletConnectSheet(
    val title: String,
    val app: String,
    val version: String,
    /// Where it lives as the person reads it: an org's name, a host.
    val place: String,
    /// The org (or the box Peel loaded) is who Peel knows; the app is named by its page.
    val byOrg: Boolean,
    val required: List<WalletMethod>,
    val optional: List<WalletMethod>,
    val notifications: List<WalletNotificationType>,
    val maxAmountMsat: Long?,
    val renewal: WalletRenewal,
    val expiresAt: Long?,
    /// What is already granted, when the version asks for more.
    val held: WalletConnection?,
    /// Whether it asks for the wallet (methods and a spending limit): not for a sign-in alone.
    val asksWallet: Boolean = true,
    /// Signing the person in, asked on the same sheet; null when not asked.
    val signIn: SignInOffer? = null,
)

/// Signing in, as the connection sheet asks it: to which host, from Peel's own record, and as which keys (iOS `SignInOffer`).
data class SignInOffer(val host: String, val keys: List<SignInKey>)

/// What the person answered on the connect sheet (iOS `WalletConnectAnswer`): the optional methods they allow, a limit (never above what was asked), what to
/// do over it, and the key to sign in with when the sheet offered that.
data class WalletConnectAnswer(
    val optional: List<WalletMethod>,
    val maxAmountMsat: Long?,
    val overBudget: OverBudget = OverBudget.ASK,
    val signIn: SignInKey? = null,
) {
    companion object {
        /// A yes to the sheet as it asks (the first key to sign in).
        fun asAsked(sheet: WalletConnectSheet) = WalletConnectAnswer(sheet.optional, sheet.maxAmountMsat, OverBudget.ASK, sheet.signIn?.keys?.firstOrNull())
    }
}

/// What the payment sheet shows for one payment over the limit (iOS `WalletPaymentSheet`).
data class WalletPaymentSheet(
    val title: String, val app: String, val version: String, val place: String, val byOrg: Boolean,
    val amountMsat: Long,
    val payee: String,
    /// The payee's name when they are a contact.
    val payeeName: String?,
    val memo: String?,
    val expiresAt: Long?,
    /// The app's own words (`metadata.comment`), marked as its.
    val comment: String?,
    val keysend: Boolean,
    val async: Boolean,
    val usedMsat: Long,
    val maxAmountMsat: Long?,
)

/// The person's side (iOS `WalletPerson`; credit is step 7): the connect sheet, the payment sheet, and the notice of a sign-in that went through silently.
interface WalletPerson {
    /// Shows the connect sheet; the answer, or null for no. Cancelling the coroutine takes the sheet down (the host cancels it on timeout).
    suspend fun connect(sheet: WalletConnectSheet): WalletConnectAnswer?

    /// Shows the payment sheet; whether the person approved it.
    suspend fun pay(sheet: WalletPaymentSheet): Boolean

    /// A sign-in went through without asking (a toast).
    fun signedIn(host: String, key: SignInKey)
}

/// Who a message is from, as Peel itself knows it, never as the page says (iOS `WalletScope`). Only the phone's two scopes
/// exist here: the Mac's `.app` (an app on Peel's own strut) and `.remote` (a stranger's app Peel's own strut hosts) are
/// Mac-only and have no Android counterpart.
sealed class WalletScope {
    /// An org, whose own strut's UI is the main frame and can name any of its apps, so the org is what is trusted.
    data class Org(val peer: String) : WalletScope()

    /// A page Peel loaded itself from a box it is not paired with (strut's R7, "direct open"). The box's own code is the main
    /// frame and can claim anything, so only the origin Peel loaded is trusted: `scheme://host[:port]`.
    data class Origin(val origin: String) : WalletScope()

    /// The connection's key ([WalletConnection.key]).
    val key: String
        get() = when (this) {
            is Org -> "org:$peer"
            is Origin -> "origin:$origin"
        }

    /// The host of a stranger's public app, from Peel's own record (the origin Peel loaded): what a sign-in challenge must
    /// name. Null where the person is no visitor: an org's page knows who is looking.
    val visitorHost: String?
        get() = when (this) {
            is Org -> null
            is Origin -> Seller.host(origin)
        }

    /// Whether the app is named by the page rather than known to Peel (iOS `byOrg`): an org's own UI, or a stranger's box Peel
    /// loaded; true for both scopes here (it is false for the Mac's two).
    val byOrg: Boolean get() = true
}

/// Who is calling: the page's [scope], its [place] as the person reads it (from Peel's own record), whether the person tapped in
/// the page just now (native events, which no script makes; no sheet opens without it), and the [person] to ask (iOS `WalletCaller`).
data class WalletCaller(val scope: WalletScope, val place: String, val personActed: Boolean, val person: WalletPerson)

/// A notification for the pages hosting an app: the whole `window.strutWallet.notify` message ([envelope]) (iOS `WalletPush`).
data class WalletPush(
    /// The connection it is for ([WalletScope.key]): a bridge sends it only to pages of that scope.
    val connection: String,
    /// `<peer>/<name>`, as strut names the app.
    val app: String,
    val envelope: JSONObject,
)

/// strut's wallet error codes: the page reads only `error.data.code`; any other name it reads as `OTHER`.
enum class WalletError {
    USER_REJECTED, BAD_REQUEST, PAYMENT_FAILED, INSUFFICIENT_BALANCE, QUOTA_EXCEEDED, RESTRICTED, UNAUTHORIZED,
    NOT_IMPLEMENTED, RATE_LIMITED, NOT_FOUND, INTERNAL, OTHER
}

/// The error reply the page reads, built the same everywhere (the wallet host, the page's bridge): iOS's
/// `{ error: { code: <-32602 for BAD_REQUEST, else -32000>, message, data: { code: "<NAME>" } } }`.
object WalletErrors {
    fun numeric(code: WalletError): Int = if (code == WalletError.BAD_REQUEST) -32602 else -32000

    /// The whole reply, `{error: {code, message, data: {code}}}`.
    fun body(code: WalletError, message: String): JSONObject = JSONObject().put(
        "error",
        JSONObject().put("code", numeric(code)).put("message", message).put("data", JSONObject().put("code", code.name)),
    )
}

/// What an app asks for with its `app.yaml` `wallet:` block (iOS `WalletRequest`): the methods it cannot work without and those it can, the notifications,
/// a limit with its renewal, an expiry.
data class WalletRequest(
    val required: List<WalletMethod>,
    val optional: List<WalletMethod> = emptyList(),
    val notifications: List<WalletNotificationType> = emptyList(),
    val maxAmountMsat: Long? = null,
    val renewal: WalletRenewal? = null,
    val expiresAt: Long? = null,
) {
    companion object {
        /// Null for none (null, absent) and for anything the contract refuses ([WalletProtocol.connectionRequestProblems]).
        fun read(json: Any?): WalletRequest? {
            if (json == null || json == JSONObject.NULL || WalletProtocol.connectionRequestProblems(json).isNotEmpty()) return null
            val r = json as? JSONObject ?: return null
            fun names(key: String): List<String> = (r.opt(key) as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } } ?: emptyList()
            return WalletRequest(
                required = names("request_methods").mapNotNull { WalletMethod.of(it) }.distinct(),
                optional = names("optional_request_methods").mapNotNull { WalletMethod.of(it) }.distinct(),
                notifications = names("notification_types").mapNotNull { WalletNotificationType.of(it) }.distinct(),
                maxAmountMsat = WalletProtocol.integer(r.opt("max_amount")),
                renewal = WalletRenewal.of(r.opt("renewal_period") as? String),
                expiresAt = WalletProtocol.integer(r.opt("expires_at")),
            )
        }
    }
}

/// The app a wallet message names (iOS `WalletApp`), as the page says it: never trusted.
class WalletApp(val name: String, val version: String, val peer: String?, val title: String, val request: WalletRequest?) {
    /// strut's `nativeAppKey`: `<peer>/<name>`.
    val key: String get() = "${peer ?: ""}/$name"

    val asksForWallet: Boolean get() = request != null

    companion object {
        fun read(raw: Any?): WalletApp? {
            val json = raw as? JSONObject ?: return null
            val name = (json.opt("name") as? String)?.takeIf { it.isNotEmpty() } ?: return null
            val version = json.opt("version") as? String ?: return null
            val peer = json.opt("peer")
            if (peer != null && peer != JSONObject.NULL) {
                if (peer !is String || peer.isEmpty() || peer.contains('/')) return null
            }
            val title = json.opt("title") as? String ?: name
            return WalletApp(name, version, peer as? String, title, WalletRequest.read(json.opt("request")))
        }
    }
}

/// The native wallet host (strut's specs/WALLET.md, Peel's side; docs/app-wallet.md; iOS `WalletHost`): answers strut's UI relaying an app's `strut/wallet/*` calls,
/// and pushes its notifications. Everything is decided here, never in the page: the params checked again, the methods offered, the connection the person
/// granted and its limits, a sheet only after the person acted (one at a time, given up after [sheetTimeoutMs]), the wallet asked only then. Not ported: `buy_credit`
/// (step 7).
class WalletHost(
    private val connections: WalletConnections,
    private val wallet: AppWallet,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sheetTimeoutMs: Long = DEFAULT_SHEET_TIMEOUT_MS,
) {
    private val now: Long get() = nowMs() / 1000

    /// One sheet at a time, across every page.
    private val asking = AtomicBoolean(false)
    private val lock = Any()
    /// What was told of each transaction, by `<connection>|<direction>|<id>`: once per state.
    private val told = mutableMapOf<String, String>()
    /// Payments in flight per connection: a transaction's notification waits for its call's answer.
    private val paying = mutableMapOf<String, Int>()
    private val held = mutableListOf<WalletRecord>()
    /// The app a page's `capability` asked about, by connection: what a sign-in's sheet may ask for beside it.
    private val asked = mutableMapOf<String, WalletApp>()
    private var watching: Job? = null
    private val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val pushes = MutableSharedFlow<WalletPush>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /// Notifications for the pages hosting an app (iOS `notifications`): each page host collects them and hands those of its own scope to the page.
    val notifications: Flow<WalletPush> = pushes.asSharedFlow()

    /// Tells every listening page host about [push]; false when it could not be queued (nothing listening is not a failure).
    fun publish(push: WalletPush): Boolean = pushes.tryEmit(push)

    /// Starts listening to the wallet's transactions, which become notifications ([updated]).
    fun start() {
        if (watching != null) return
        watching = hostScope.launch { wallet.updates().collect { updated(it) } }
    }

    fun stop() {
        watching?.cancel()
        watching = null
    }

    /// What a connection has spent in its current period and when that renews: Connected apps.
    suspend fun usage(key: String): WalletUsage? {
        val connection = connections.connection(key) ?: return null
        val records = runCatching { wallet.records(key) }.getOrNull() ?: return null
        val (start, renewsAt) = WalletBudget.period(connection.createdAt, connection.renewal, now)
        return WalletUsage(WalletBudget.used(records.mapNotNull { it.entry }, start), renewsAt)
    }

    // MARK: Messages

    /// Answers one message: `{result: …}` or `{error: {code, message, data: {code}}}` ([WalletErrors]). Never throws (but for cancellation).
    suspend fun handle(message: Any?, caller: WalletCaller): JSONObject = try {
        read(message, caller)
    } catch (e: CancellationException) {
        throw e
    } catch (e: WalletRefusal) {
        e.body
    } catch (e: Exception) {
        error(WalletError.INTERNAL, e.message?.takeIf { it.isNotBlank() } ?: "Peel could not do that.")
    }

    private suspend fun read(message: Any?, caller: WalletCaller): JSONObject {
        val body = message as? JSONObject ?: return unreadable()
        val op = body.opt("op") as? String
        if (WalletProtocol.integer(body.opt("v")) != 1L || op == null) return unreadable()
        // The host's own (specs/WALLET.md §1.2): no app's call, no app connection named by the page.
        when (op) {
            "host_capability" -> return hostCapability(caller)
            "sign_in" -> return answer { signIn(body, caller) }
            // Credit is bought only for a public app Peel opened itself; Android buys none yet (not advertised).
            "buy_credit", "buy_credit_status", "buy_credit_cancel" ->
                if (caller.scope.visitorHost == null) return error(WalletError.RESTRICTED, "Peel buys credit only for a public app it opened itself.")
        }
        val app = WalletApp.read(body.opt("app")) ?: return unreadable()
        return when (op) {
            "capability" -> {
                if (app.request != null) synchronized(lock) { asked[caller.scope.key] = app }
                capability(app, caller)
            }
            "call" -> {
                val name = body.opt("method") as? String ?: return error(WalletError.BAD_REQUEST, "The call names no method.")
                val method = WalletMethod.of(name)
                    ?: return error(WalletError.NOT_IMPLEMENTED, "Peel's wallet has no method ${TextLine.clean(name, 40)}.")
                answer { call(method, body.opt("params"), app, caller) }
            }
            else -> unreadable()
        }
    }

    private fun capability(app: WalletApp, caller: WalletCaller): JSONObject {
        if (app.request == null) return error(WalletError.RESTRICTED, "This app asks for no wallet.")
        // A connection that only signs the person in holds no wallet.
        val connected = validConnection(caller.scope.key)?.methods?.isNotEmpty() == true
        return result(
            JSONObject().put("version", 1).put("methods", JSONArray(OFFERED.map { it.wire }))
                .put("notifications", JSONArray(WalletNotificationType.entries.map { it.wire })).put("wallet", "sphinx").put("connected", connected),
        )
    }

    /// `{result}`, or the refusal as `{error}`.
    private suspend fun answer(run: suspend () -> JSONObject): JSONObject = try {
        result(run())
    } catch (e: CancellationException) {
        throw e
    } catch (e: WalletRefusal) {
        e.body
    } catch (e: Exception) {
        error(WalletError.INTERNAL, e.message?.takeIf { it.isNotBlank() } ?: "Peel could not do that.")
    }

    private suspend fun call(method: WalletMethod, raw: Any?, app: WalletApp, caller: WalletCaller): JSONObject {
        val params = WalletProtocol.checkParams(method, raw).getOrThrow()
        if (method !in OFFERED) throw WalletRefusal(WalletError.NOT_IMPLEMENTED, "Peel's wallet cannot do ${method.wire} yet.")
        when (method) {
            WalletMethod.CONNECT -> return connect(app, caller)
            WalletMethod.GET_INFO -> return info(validConnection(caller.scope.key))
            else -> {}
        }
        val connection = validConnection(caller.scope.key) ?: throw WalletRefusal(WalletError.UNAUTHORIZED, "This app is not connected to your wallet.")
        if (method !in connection.methods) throw WalletRefusal(WalletError.RESTRICTED, "You did not let this app use ${method.wire}.")
        return when (method) {
            WalletMethod.PAY_INVOICE, WalletMethod.PAY_KEYSEND -> pay(method, params, app, caller, connection)
            WalletMethod.MAKE_INVOICE -> {
                if (params.has("description_hash")) throw WalletRefusal(WalletError.NOT_IMPLEMENTED, "Peel's invoices carry a description, not its hash.")
                val record = wallet.makeInvoice(WalletProtocol.integer(params.opt("amount")) ?: 0, params.optString("description", ""), WalletTag(connection.key, app.key))
                record.transaction
            }
            WalletMethod.LOOKUP_INVOICE -> {
                val records = wallet.records(connection.key)
                val hash = paymentHash(params)
                records.firstOrNull { it.paymentType == WalletRecord.PaymentType.BOLT11 && it.paymentHash == hash }?.transaction
                    ?: throw WalletRefusal(WalletError.NOT_FOUND, "No invoice of this app's is that one.")
            }
            WalletMethod.LOOKUP_PAYMENT -> {
                val records = wallet.records(connection.key)
                val record = (params.opt("transaction_id") as? String)?.let { id -> records.firstOrNull { it.transactionId == id } }
                    ?: if (params.has("transaction_id")) null else paymentHash(params).let { hash -> records.firstOrNull { it.paymentHash == hash } }
                record?.lookup ?: throw WalletRefusal(WalletError.NOT_FOUND, "No payment of this app's is that one.")
            }
            WalletMethod.LIST_TRANSACTIONS -> list(params, connection)
            WalletMethod.GET_BUDGET -> {
                val max = connection.maxAmountMsat ?: return JSONObject()
                val (start, renews) = WalletBudget.period(connection.createdAt, connection.renewal, now)
                val used = WalletBudget.used(entries(connection), start)
                JSONObject().put("used_budget", used).put("total_budget", max).put("renewal_period", connection.renewal.wire).apply { renews?.let { put("renews_at", it) } }
            }
            WalletMethod.GET_BALANCE -> JSONObject().put("balance", wallet.balance())
            WalletMethod.CONNECT, WalletMethod.GET_INFO, WalletMethod.PAY_LIGHTNING_ADDRESS ->
                throw WalletRefusal(WalletError.NOT_IMPLEMENTED, "Peel's wallet cannot do ${method.wire} yet.")
        }
    }

    private fun validConnection(key: String): WalletConnection? = connections.connection(key)?.takeUnless { it.isExpired(now) }

    private suspend fun info(connection: WalletConnection?): JSONObject {
        val granted = connection?.methods?.filter { it in OFFERED } ?: emptyList()
        return JSONObject().put("network", wallet.network())
            .put("methods", JSONArray((listOf(WalletMethod.CONNECT, WalletMethod.GET_INFO) + granted).map { it.wire }))
            .put("notifications", JSONArray((connection?.notificationTypes ?: emptyList()).map { it.wire }))
    }

    private suspend fun entries(connection: WalletConnection) = wallet.records(connection.key).mapNotNull { it.entry }

    private suspend fun paymentHash(p: JSONObject): String {
        (p.opt("payment_hash") as? String)?.let { return it }
        val invoice = p.opt("invoice") as? String ?: throw WalletRefusal(WalletError.BAD_REQUEST, "Give a payment hash or an invoice.")
        return wallet.decode(invoice).paymentHash
    }

    private suspend fun list(p: JSONObject, connection: WalletConnection): JSONObject {
        val from = WalletProtocol.integer(p.opt("from"))
        val until = WalletProtocol.integer(p.opt("until"))
        val type = p.opt("type") as? String
        val unpaid = p.opt("unpaid") == true
        val matching = wallet.records(connection.key).filter { record ->
            when {
                from != null && record.createdAt < from -> false
                until != null && record.createdAt > until -> false
                type != null && record.direction.wire != type -> false
                // NWC-05: an invoice not paid only when asked for.
                record.direction == WalletRecord.Direction.INCOMING && record.state != WalletRecord.State.SETTLED && !unpaid -> false
                else -> true
            }
        }
        val offset = (WalletProtocol.integer(p.opt("offset")) ?: 0).toInt()
        val limit = (WalletProtocol.integer(p.opt("limit")) ?: WalletProtocol.LIST_LIMIT_MAX).toInt()
        val page = matching.drop(offset).take(limit)
        return JSONObject().put("transactions", JSONArray(page.map { it.transaction })).put("total_count", matching.size)
    }

    // MARK: Connect

    private suspend fun connect(app: WalletApp, caller: WalletCaller): JSONObject {
        val request = app.request ?: throw WalletRefusal(WalletError.RESTRICTED, "This app asks for no wallet.")
        val unoffered = request.required.filter { it !in OFFERED }
        if (unoffered.isNotEmpty()) {
            throw WalletRefusal(WalletError.NOT_IMPLEMENTED, "Peel's wallet cannot do ${unoffered.joinToString(", ") { it.wire }}, which this app needs.")
        }
        val held = validConnection(caller.scope.key)
        if (held != null && covers(held, request)) return JSONObject().put("connection", held.granted)
        if (!caller.personActed) {
            throw WalletRefusal(WalletError.RESTRICTED, "Peel asks you about a wallet only right after you click or tap in the app.")
        }
        val sheet = walletSheet(app, request, held, caller)
        val person = caller.person
        val answer = askPerson { person.connect(sheet) } ?: throw WalletRefusal(WalletError.USER_REJECTED, "You declined to connect this app.")
        val connection = grant(answer, sheet, request, app, held, caller)
        connections.save(connection)
        return JSONObject().put("connection", connection.granted)
    }

    private fun covers(held: WalletConnection, request: WalletRequest) =
        request.required.all { it in held.methods } && request.notifications.all { it in held.notificationTypes }

    private fun walletSheet(app: WalletApp, request: WalletRequest, held: WalletConnection?, caller: WalletCaller) = WalletConnectSheet(
        title = TextLine.clean(app.title, 80), app = TextLine.clean(app.name, 80), version = TextLine.clean(app.version, 20),
        place = caller.place, byOrg = caller.scope.byOrg, required = request.required,
        optional = request.optional.filter { it in OFFERED && it !in request.required }, notifications = request.notifications,
        // A version asking for more keeps the budget held when it names none.
        maxAmountMsat = request.maxAmountMsat ?: held?.maxAmountMsat,
        renewal = if (request.maxAmountMsat != null) request.renewal ?: WalletRenewal.NEVER else held?.renewal ?: WalletRenewal.NEVER,
        expiresAt = request.expiresAt ?: held?.expiresAt, held = held,
    )

    /// What the person's yes grants: only what was asked, a limit never above it (lowered, not raised), what they kept of the optional methods, and what was
    /// already held stays (a version asking for more adds to it).
    private fun grant(
        answer: WalletConnectAnswer, sheet: WalletConnectSheet, request: WalletRequest, app: WalletApp, held: WalletConnection?, caller: WalletCaller,
    ): WalletConnection {
        var max = answer.maxAmountMsat?.let { maxOf(it, 0) }
        sheet.maxAmountMsat?.let { asked -> max = max?.let { minOf(it, asked) } }
        val methods = request.required.toMutableList()
        for (m in sheet.optional) if (m in answer.optional && m !in methods) methods += m
        for (m in held?.methods ?: emptyList()) if (m !in methods) methods += m
        val types = request.notifications.toMutableList()
        for (t in held?.notificationTypes ?: emptyList()) if (t !in types) types += t
        return WalletConnection(
            key = caller.scope.key, app = app.key, title = sheet.title, place = caller.place, methods = methods, notificationTypes = types,
            maxAmountMsat = if (max == 0L) null else max, renewal = sheet.renewal, expiresAt = sheet.expiresAt, overBudget = answer.overBudget,
            createdAt = held?.createdAt ?: now, signIn = held?.signIn,
        )
    }

    // MARK: Sign in

    /// What `host_capability` answers: the host's own ops this page may send (only a stranger's public page signs in).
    private fun hostCapability(caller: WalletCaller): JSONObject = result(
        JSONObject().put("version", 1).put("wallet", "Peel").put("ops", JSONArray().apply { if (caller.scope.visitorHost != null) put("sign_in") }),
    )

    /// Signs the visitor in (`sign_in`, strut's plans/app-payments.md §4.4): only a strut visitor challenge, only for the host Peel's own record names (the
    /// origin Peel loaded), only before it ends; with the key the connection holds, or after the person's yes on the connection sheet, which keeps it. The
    /// sheet is the connection's own ([walletSheet]), with the wallet this page asked for beside it when that is not held yet: ONE sheet.
    private suspend fun signIn(body: JSONObject, caller: WalletCaller): JSONObject {
        val host = caller.scope.visitorHost ?: throw WalletRefusal(WalletError.RESTRICTED, "Peel signs you in only to a public app it opened itself.")
        // Judged against the host Peel itself loaded, never against what the page says its seller is.
        val problems = SignInRequest.problems(body, nowMs(), host)
        val request = SignInRequest.of(body, nowMs(), host)
        if (problems.isNotEmpty() || request == null) {
            throw WalletRefusal(WalletError.BAD_REQUEST, "Peel does not sign that: ${problems.firstOrNull() ?: "it is not a sign-in request"}.")
        }
        val challenge = request.challenge
        if (challenge.visitorKey == SignInKey.domain && !wallet.signsPerDomain) {
            throw WalletRefusal(WalletError.NOT_IMPLEMENTED, "This app takes a new identity for it alone, which Peel cannot sign in as yet.")
        }
        val held = validConnection(caller.scope.key)
        // A challenge for the account's key takes either; one for the domain's, that alone.
        val heldKey = held?.signIn
        if (heldKey != null && (challenge.visitorKey == SignInKey.root || heldKey == SignInKey.domain)) {
            val signature = wallet.signVisitor(challenge, heldKey)
            caller.person.signedIn(host, heldKey)
            return signature.toJson()
        }
        if (!caller.personActed) throw WalletRefusal(WalletError.RESTRICTED, "Peel asks you to sign in only right after you click or tap in the app.")
        val keys = if (challenge.visitorKey == SignInKey.domain) listOf(SignInKey.domain) else if (wallet.signsPerDomain) listOf(SignInKey.root, SignInKey.domain) else listOf(SignInKey.root)
        val shown = WalletApp(TextLine.clean(request.app.name, 80), TextLine.clean(request.app.version, 20), null, TextLine.clean(request.app.title, 80), null)
        val wanted = synchronized(lock) { asked[caller.scope.key] }?.let { a ->
            val r = a.request
            if (r != null && r.required.all { it in OFFERED } && (held == null || !covers(held, r))) a to r else null
        }
        val sheet = (wanted?.let { (a, r) -> walletSheet(a, r, held, caller) } ?: WalletConnectSheet(
            title = shown.title, app = shown.name, version = shown.version, place = caller.place, byOrg = caller.scope.byOrg,
            required = emptyList(), optional = emptyList(), notifications = emptyList(), maxAmountMsat = null, renewal = WalletRenewal.NEVER,
            expiresAt = null, held = held, asksWallet = false,
        )).copy(signIn = SignInOffer(host, keys))
        val person = caller.person
        // Taken down when the challenge ends: the page stops waiting then.
        val left = maxOf(challenge.expiresAtMs - nowMs(), 0)
        val answer = askPerson(minOf(left, sheetTimeoutMs)) { person.connect(sheet) }
        val key = answer?.signIn?.takeIf { it in keys } ?: throw WalletRefusal(WalletError.USER_REJECTED, "You declined to sign in.")
        // As it stands now: one the person revoked while the sheet was up stays revoked.
        val current = validConnection(caller.scope.key)
        val connection = (wanted?.let { (a, r) -> grant(answer, sheet, r, a, current, caller) }
            ?: current
            ?: WalletConnection(key = caller.scope.key, app = "/${shown.name}", title = sheet.title, place = caller.place, createdAt = now)).copy(signIn = key)
        connections.save(connection)
        if (challenge.expiresAtMs <= nowMs()) throw WalletRefusal(WalletError.RESTRICTED, "The sign-in ran out of time while you were asked. Sign in again.")
        return wallet.signVisitor(challenge, key).toJson()
    }

    // MARK: Pay

    private suspend fun pay(method: WalletMethod, p: JSONObject, app: WalletApp, caller: WalletCaller, connection: WalletConnection): JSONObject {
        val payment = decodedPayment(method, p)
        wallet.problem(payment)?.let { throw it }
        val amount = maxOf(payment.amountMsat, 0)
        val (start, _) = WalletBudget.period(connection.createdAt, connection.renewal, now)
        connection.maxAmountMsat?.let { max ->
            val within = WalletBudget.Guard(max, start)
            if (within.admits(amount, entries(connection))) {
                try {
                    return paid(payment, connection, app, within)
                } catch (e: WalletRefusal) {
                    // Another payment took the room meanwhile: over budget after all.
                    if (e.code != WalletError.QUOTA_EXCEEDED) throw e
                }
            }
        }
        when (connection.overBudget) {
            OverBudget.REFUSE -> throw WalletRefusal(WalletError.QUOTA_EXCEEDED, "That is over what you let this app spend.")
            OverBudget.ASK -> {
                if (!caller.personActed) throw WalletRefusal(WalletError.RESTRICTED, "Peel asks you to pay only right after you click or tap in the app.")
                val sheet = paymentSheet(payment, p, app, caller, connection, start)
                val person = caller.person
                if (!askPerson { person.pay(sheet) }) throw WalletRefusal(WalletError.USER_REJECTED, "You declined the payment.")
                // Approved on its own sheet: counted, not limited.
                return paid(payment, connection, app, WalletBudget.Guard(null, start))
            }
        }
    }

    private suspend fun paid(payment: WalletPayment, connection: WalletConnection, app: WalletApp, budget: WalletBudget.Guard): JSONObject {
        synchronized(lock) { paying[connection.key] = (paying[connection.key] ?: 0) + 1 }
        try {
            val record = wallet.pay(payment, WalletTag(connection.key, app.key, budget))
            val answer = record.payment
            synchronized(lock) { told[toldKey(record)] = answer.optString("state") }
            return answer
        } finally {
            val waiting = synchronized(lock) {
                val n = (paying[connection.key] ?: 1) - 1
                if (n <= 0) {
                    paying.remove(connection.key)
                    held.filter { it.connection == connection.key }.also { held.removeAll(it.toSet()) }
                } else {
                    paying[connection.key] = n
                    emptyList()
                }
            }
            for (record in waiting) notify(record)
        }
    }

    private suspend fun decodedPayment(method: WalletMethod, p: JSONObject): WalletPayment {
        if (method == WalletMethod.PAY_INVOICE) {
            val invoice = wallet.decode(p.optString("invoice", ""))
            if (invoice.amountMsat <= 0) {
                throw if (p.has("amount")) WalletRefusal(WalletError.NOT_IMPLEMENTED, "Peel pays only invoices that say how much.")
                else WalletRefusal(WalletError.BAD_REQUEST, "The invoice names no amount.")
            }
            if (p.has("amount")) throw WalletRefusal(WalletError.BAD_REQUEST, "The invoice names its own amount.")
            return WalletPayment.OfInvoice(invoice)
        }
        if (p.has("preimage")) throw WalletRefusal(WalletError.NOT_IMPLEMENTED, "Peel's wallet makes a keysend's preimage itself.")
        var hint: String? = null
        (p.opt("route_hints") as? JSONArray)?.let { routes ->
            val hops = routes.takeIf { it.length() == 1 }?.optJSONArray(0)?.takeIf { it.length() == 1 }
            val hop = hops?.optJSONObject(0)
            val sphinx = hop?.let { h ->
                val pk = h.opt("pubkey") as? String
                val scid = h.opt("short_channel_id") as? String
                if (pk != null && scid != null) WalletProtocol.routeHint(pk, scid) else null
            } ?: throw WalletRefusal(WalletError.NOT_IMPLEMENTED, "Peel's wallet takes one route hint of one hop, with a decimal channel id.")
            hint = sphinx
        }
        var data: ByteArray? = null
        (p.opt("tlv_records") as? JSONArray)?.let { records ->
            for (i in 0 until records.length()) {
                val record = records.optJSONObject(i) ?: continue
                val value = record.opt("value") as? String
                val bytes = value?.takeIf { WalletProtocol.integer(record.opt("type")) == WalletProtocol.SPHINX_TLV_TYPE && it.length % 2 == 0 }
                    ?.let { v -> runCatching { ByteArray(v.length / 2) { v.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull() }
                    ?: throw WalletRefusal(WalletError.NOT_IMPLEMENTED, "Peel's wallet sends only Sphinx's record, ${WalletProtocol.SPHINX_TLV_TYPE}.")
                data = sphinxData(bytes)
            }
        }
        return WalletPayment.OfKeysend(p.optString("pubkey", ""), WalletProtocol.integer(p.opt("amount")) ?: 0, hint, data)
    }

    private suspend fun paymentSheet(payment: WalletPayment, p: JSONObject, app: WalletApp, caller: WalletCaller, connection: WalletConnection, since: Long): WalletPaymentSheet {
        val payee: String
        val memo: String?
        val expires: Long?
        val keysend: Boolean
        val async: Boolean
        when (payment) {
            is WalletPayment.OfInvoice -> { payee = payment.invoice.payee; memo = payment.invoice.memo.ifEmpty { null }; expires = payment.invoice.expiresAt; keysend = false; async = false }
            is WalletPayment.OfKeysend -> { payee = payment.pubkey; memo = payment.data?.let { String(it, Charsets.UTF_8) }; expires = null; keysend = true; async = payment.routeHint != null }
        }
        val comment = p.optJSONObject("metadata")?.opt("comment") as? String
        val used = runCatching { WalletBudget.used(entries(connection), since) }.getOrDefault(0)
        return WalletPaymentSheet(
            title = TextLine.clean(app.title, 80), app = TextLine.clean(app.name, 80), version = TextLine.clean(app.version, 20), place = caller.place,
            byOrg = caller.scope.byOrg, amountMsat = payment.amountMsat, payee = payee, payeeName = wallet.payeeName(payee),
            memo = memo?.let { block(it, 500) }?.ifEmpty { null }, expiresAt = expires, comment = comment?.let { block(it, 500) }?.ifEmpty { null },
            keysend = keysend, async = async, usedMsat = used, maxAmountMsat = connection.maxAmountMsat,
        )
    }

    /// The words of a page or a payee as a few lines: trimmed, control characters out, long ones cut (iOS `TextLine.block`).
    private fun block(text: String, max: Int) = text.filter { it == '\n' || !it.isISOControl() }.trim().take(max).trim()

    // MARK: Sheets

    /// Shows [present] to the person: one sheet at a time across every page (`RATE_LIMITED`), given up after [timeoutMs] (`RESTRICTED`, the sheet taken
    /// down because the coroutine is cancelled).
    private suspend fun <T> askPerson(timeoutMs: Long = sheetTimeoutMs, present: suspend () -> T): T {
        if (!asking.compareAndSet(false, true)) throw WalletRefusal(WalletError.RATE_LIMITED, "Peel is already asking you something; answer that first.")
        try {
            val answered = withTimeoutOrNull(maxOf(timeoutMs, 1)) { Answered(present()) } ?: throw WalletRefusal(WalletError.RESTRICTED, "You did not answer in time.")
            return answered.value
        } finally {
            asking.set(false)
        }
    }

    private class Answered<T>(val value: T)

    // MARK: Notifications

    private fun toldKey(record: WalletRecord) = "${record.connection}|${record.direction.wire}|${record.transactionId}"

    /// A transaction moved: told when it settles or fails, once per state, never before the answer of the call that made it ([paid]).
    internal fun updated(record: WalletRecord) {
        val wait = synchronized(lock) { (paying[record.connection] != null).also { if (it) held += record } }
        if (!wait) notify(record)
    }

    private fun notify(record: WalletRecord) {
        val type = when (record.direction to record.state) {
            WalletRecord.Direction.OUTGOING to WalletRecord.State.SETTLED -> WalletNotificationType.PAYMENT_SENT
            WalletRecord.Direction.OUTGOING to WalletRecord.State.FAILED -> WalletNotificationType.PAYMENT_FAILED
            WalletRecord.Direction.INCOMING to WalletRecord.State.SETTLED -> WalletNotificationType.PAYMENT_RECEIVED
            else -> return
        }
        val key = toldKey(record)
        synchronized(lock) {
            if (told[key] == record.state.wire) return
            told[key] = record.state.wire
        }
        val connection = validConnection(record.connection)?.takeIf { type in it.notificationTypes } ?: return
        val notification = JSONObject().put("notification_type", type.wire).put("notification", record.transaction)
        val slash = record.app.indexOf('/')
        val peer = if (slash > 0) record.app.substring(0, slash) else null
        val name = if (slash >= 0) record.app.substring(slash + 1) else record.app
        val appJson = JSONObject().put("name", name).apply { peer?.let { put("peer", it) } }
        publish(WalletPush(connection.key, record.app, JSONObject().put("v", 1).put("app", appJson).put("notification", notification)))
    }

    private fun unreadable() = error(WalletError.BAD_REQUEST, "That is not a wallet message Peel reads.")

    private fun result(value: JSONObject) = JSONObject().put("result", value)

    private fun error(code: WalletError, message: String) = WalletErrors.body(code, message)

    companion object {
        const val DEFAULT_SHEET_TIMEOUT_MS = 9 * 60 * 1000L

        /// The methods Peel's wallet does: not `pay_lightning_address` (iOS lists the same ten).
        val OFFERED = listOf(
            WalletMethod.CONNECT, WalletMethod.GET_INFO, WalletMethod.PAY_INVOICE, WalletMethod.PAY_KEYSEND, WalletMethod.MAKE_INVOICE,
            WalletMethod.LOOKUP_PAYMENT, WalletMethod.LOOKUP_INVOICE, WalletMethod.GET_BUDGET, WalletMethod.GET_BALANCE, WalletMethod.LIST_TRANSACTIONS,
        )

        /// Sphinx's record carries base64 text: the bytes are its UTF-8, and decode to the data the core sends.
        fun sphinxData(value: ByteArray): ByteArray {
            val text = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(value)).toString() }.getOrNull()
            val data = text?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
            if (data == null || java.util.Base64.getEncoder().encodeToString(data) != text) {
                throw WalletRefusal(WalletError.NOT_IMPLEMENTED, "Sphinx's record carries base64 text; Peel cannot send this value as it is.")
            }
            return data
        }
    }
}
