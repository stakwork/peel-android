package com.sphinx.peel.engine

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

/// The person's side: the sheet, and the notice of a silent sign-in.
interface WalletPerson {
    /// Shows the sheet; the key the person chose, or null for "Don't sign in". Cancelling the coroutine takes the
    /// sheet down (the host cancels it on timeout).
    suspend fun askSignIn(ask: SignInAsk): SignInKey?

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

/// The app a wallet message names (iOS `WalletApp`), as the page says it: never trusted.
private class WalletApp(val name: String, val version: String, val title: String, val asksForWallet: Boolean) {
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
            return WalletApp(name, version, title, json.opt("request") is JSONObject)
        }
    }
}

class WalletHost(
    private val connections: WalletConnections,
    private val signer: VisitorSigner,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sheetTimeoutMs: Long = DEFAULT_SHEET_TIMEOUT_MS,
) {
    /// One sheet at a time, across every page.
    private val asking = AtomicBoolean(false)

    private val pushes = MutableSharedFlow<WalletPush>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /// Notifications for the pages hosting an app (iOS `notifications`): each page host collects them and hands those of its
    /// own scope to the page ([com.sphinx.peel.ui.apps.OrgWalletBridge]). The producers (an app's payment that settled or
    /// failed) come with the spending wallet, which Android does not have yet: [publish] is where they will put them.
    val notifications: Flow<WalletPush> = pushes.asSharedFlow()

    /// Tells every listening page host about [push]; false when it could not be queued (nothing is listening is not a failure).
    fun publish(push: WalletPush): Boolean = pushes.tryEmit(push)

    /// Answers one message: `{result: …}` or `{error: {code, message, data: {code}}}` ([WalletErrors]). Never throws (but for cancellation).
    suspend fun handle(message: Any?, caller: WalletCaller): JSONObject = try {
        read(message, caller)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        error(WalletError.INTERNAL, e.message?.takeIf { it.isNotBlank() } ?: "Peel could not do that.")
    }

    private suspend fun read(message: Any?, caller: WalletCaller): JSONObject {
        val body = message as? JSONObject ?: return unreadable()
        val op = body.opt("op") as? String
        if (WalletProtocol.integer(body.opt("v")) != 1L || op == null) return unreadable()
        // The host's own ops need no app of the wallet's kind.
        when (op) {
            "host_capability" -> return hostCapability(caller)
            "sign_in" -> return signIn(body, caller)
            // Credit is bought only for a public app Peel opened itself; Android buys none (not advertised), and an org's page is told why.
            "buy_credit", "buy_credit_status", "buy_credit_cancel" ->
                if (caller.scope.visitorHost == null) return error(WalletError.RESTRICTED, "Peel buys credit only for a public app it opened itself.")
        }
        val app = WalletApp.read(body.opt("app")) ?: return unreadable()
        return when (op) {
            "capability" ->
                if (!app.asksForWallet) error(WalletError.RESTRICTED, "This app asks for no wallet.")
                // Peel offers no wallet methods, and a sign-in-only connection holds no wallet. strut's capability
                // check wants every one of these fields; an empty `methods` is valid.
                else result(
                    JSONObject().put("version", 1).put("methods", JSONArray()).put("notifications", JSONArray())
                        .put("wallet", "sphinx").put("connected", false),
                )
            "call" -> call(body)
            else -> unreadable()
        }
    }

    /// No wallet method is offered, so every call is refused. iOS first checks a known method's parameters (a
    /// `BAD_REQUEST` for bad ones); with nothing offered here that check is not ported.
    private fun call(body: JSONObject): JSONObject {
        val name = body.opt("method") as? String ?: return error(WalletError.BAD_REQUEST, "The call names no method.")
        return if (name !in KNOWN_METHODS) {
            error(WalletError.NOT_IMPLEMENTED, "Peel's wallet has no method ${TextLine.clean(name, 40)}.")
        } else {
            error(WalletError.NOT_IMPLEMENTED, "Peel's wallet cannot do $name yet.")
        }
    }

    /// What `host_capability` answers: the host's own ops this page may send (only a stranger's public page signs in).
    private fun hostCapability(caller: WalletCaller): JSONObject = result(
        JSONObject().put("version", 1).put("wallet", "Peel").put(
            "ops", JSONArray().apply { if (caller.scope.visitorHost != null) put("sign_in") },
        ),
    )

    private suspend fun signIn(body: JSONObject, caller: WalletCaller): JSONObject {
        val host = caller.scope.visitorHost
            ?: return error(WalletError.RESTRICTED, "Peel signs you in only to a public app it opened itself.")
        // Judged against the host Peel itself loaded, never against what the page says its seller is.
        val problems = SignInRequest.problems(body, nowMs(), host)
        val request = SignInRequest.of(body, nowMs(), host)
        if (problems.isNotEmpty() || request == null) {
            return error(WalletError.BAD_REQUEST, "Peel does not sign that: ${problems.firstOrNull() ?: "it is not a sign-in request"}.")
        }
        val challenge = request.challenge
        val held = connections.connection(caller.scope.key)?.signIn
        if (held != null && (challenge.visitorKey == SignInKey.root || held == SignInKey.domain)) {
            val signature = signer.sign(challenge, held)
            caller.person.signedIn(host, held)
            return result(signature.toJson())
        }
        if (!caller.personActed) {
            return error(WalletError.RESTRICTED, "Peel asks you to sign in only right after you click or tap in the app.")
        }
        val offered = if (challenge.visitorKey == SignInKey.domain) listOf(SignInKey.domain) else listOf(SignInKey.root, SignInKey.domain)
        if (!asking.compareAndSet(false, true)) {
            return error(WalletError.RATE_LIMITED, "Peel is already asking you something; answer that first.")
        }
        // What the sheet and the connection keep of the page's claims: one line each, not too long.
        val shown = SignInApp(
            TextLine.clean(request.app.name, 80), TextLine.clean(request.app.version, 20), TextLine.clean(request.app.title, 80),
        )
        val chosen: SignInKey? = try {
            val wait = minOf(challenge.expiresAtMs - nowMs(), sheetTimeoutMs)
            val answer = withTimeoutOrNull(wait.coerceAtLeast(1)) { Answered(caller.person.askSignIn(SignInAsk(host, shown, offered))) }
                ?: return error(WalletError.RESTRICTED, "You did not answer in time.")
            answer.key
        } finally {
            asking.set(false)
        }
        if (chosen == null || chosen !in offered) return error(WalletError.USER_REJECTED, "You declined to sign in.")
        // Read again: a connection revoked while the sheet was up stays revoked, so this one starts fresh.
        val current = connections.connection(caller.scope.key)
        connections.save(
            current?.copy(signIn = chosen)
                ?: WalletConnection(
                    key = caller.scope.key, app = "/${shown.name}", title = shown.title, place = caller.place, signIn = chosen, createdAt = nowMs() / 1000,
                ),
        )
        if (challenge.expiresAtMs <= nowMs()) {
            return error(WalletError.RESTRICTED, "The sign-in ran out of time while you were asked. Sign in again.")
        }
        return result(signer.sign(challenge, chosen).toJson())
    }

    /// Tells a timeout (null) from a "no" (an [Answered] with no key).
    private class Answered(val key: SignInKey?)

    private fun unreadable() = error(WalletError.BAD_REQUEST, "That is not a wallet message Peel reads.")

    private fun result(value: JSONObject) = JSONObject().put("result", value)

    private fun error(code: WalletError, message: String) = WalletErrors.body(code, message)

    companion object {
        const val DEFAULT_SHEET_TIMEOUT_MS = 9 * 60 * 1000L

        /// The wallet methods strut knows (iOS lists these too); none is offered.
        private val KNOWN_METHODS = setOf(
            "connect", "get_info", "pay_invoice", "pay_keysend", "make_invoice", "lookup_payment", "lookup_invoice",
            "get_budget", "get_balance", "list_transactions", "pay_lightning_address",
        )
    }
}
