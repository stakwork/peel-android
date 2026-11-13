package com.sphinx.peel.engine

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

// The wallet Peel offers a strut app's page (iOS `StrutWallet`), the sign-in slice only: `host_capability` and
// `sign_in`. Android has no app-wallet host, so `buy_credit` and the spending-wallet `call`s are not here
// (docs/phases/27-sign-in-with-sphinx.md). Pure: storage, signer, the person and the clock are injected; JVM-tested.

/// What a page was allowed to do, kept per [key] (iOS `walletConnection`). A sign-in-only connection has no [methods].
data class WalletConnection(
    /// The scope: `origin:<origin>` for a stranger's page Peel opened.
    val key: String,
    val app: String,
    val title: String,
    /// Where it lives, as the person reads it (the host).
    val place: String,
    val methods: List<String>,
    /// The key the person chose to sign in to this place with; null = never.
    val signIn: SignInKey?,
    val createdAt: Long,
)

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

/// Who is calling: the page's scope [key] (`origin:<origin>`), its [place] (the host of the origin Peel loaded) and
/// whether the person tapped in the page just now.
data class WalletCaller(val scopeKey: String, val place: String, val personActed: Boolean) {
    companion object {
        /// A stranger's page Peel loaded from [origin].
        fun page(origin: String, personActed: Boolean): WalletCaller =
            WalletCaller("origin:$origin", Seller.host(origin) ?: origin, personActed)
    }
}

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
    private val person: WalletPerson,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sheetTimeoutMs: Long = DEFAULT_SHEET_TIMEOUT_MS,
) {
    /// One sheet at a time, across every page.
    private val asking = AtomicBoolean(false)

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
            "host_capability" -> return result(
                JSONObject().put("version", 1).put("ops", JSONArray().put("sign_in")).put("wallet", "Peel"),
            )
            "sign_in" -> return signIn(body, caller)
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

    private suspend fun signIn(body: JSONObject, caller: WalletCaller): JSONObject {
        // Judged against the host Peel itself loaded, never against what the page says its seller is.
        val problems = SignInRequest.problems(body, nowMs(), caller.place)
        val request = SignInRequest.of(body, nowMs(), caller.place)
        if (problems.isNotEmpty() || request == null) {
            return error(WalletError.BAD_REQUEST, "Peel does not sign that: ${problems.firstOrNull() ?: "it is not a sign-in request"}.")
        }
        val challenge = request.challenge
        val held = connections.connection(caller.scopeKey)?.signIn
        if (held != null && (challenge.visitorKey == SignInKey.root || held == SignInKey.domain)) {
            val signature = signer.sign(challenge, held)
            person.signedIn(caller.place, held)
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
            val answer = withTimeoutOrNull(wait.coerceAtLeast(1)) { Answered(person.askSignIn(SignInAsk(caller.place, shown, offered))) }
                ?: return error(WalletError.RESTRICTED, "You did not answer in time.")
            answer.key
        } finally {
            asking.set(false)
        }
        if (chosen == null || chosen !in offered) return error(WalletError.USER_REJECTED, "You declined to sign in.")
        // Read again: a connection revoked while the sheet was up stays revoked, so this one starts fresh.
        val current = connections.connection(caller.scopeKey)
        connections.save(
            current?.copy(signIn = chosen)
                ?: WalletConnection(caller.scopeKey, "/${shown.name}", shown.title, caller.place, emptyList(), chosen, nowMs()),
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
