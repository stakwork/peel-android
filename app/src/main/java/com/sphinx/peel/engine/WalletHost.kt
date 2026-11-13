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

/// What the person is asked in the sign-in sheet. [app] is the page's own claim.
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

enum class WalletError {
    BAD_REQUEST, RESTRICTED, USER_REJECTED, RATE_LIMITED, NOT_IMPLEMENTED, NOT_FOUND, INTERNAL
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

    /// Answers one message: `{result: …}` or `{error: {code, message}}`. Never throws (but for cancellation).
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
        val v = body.opt("v")
        if (!((v is Int || v is Long) && (v as Number).toLong() == 1L) || op == null) return unreadable()
        return when (op) {
            "host_capability" -> result(
                JSONObject().put("version", 1).put("ops", JSONArray().put("sign_in")).put("wallet", "Peel"),
            )
            // No wallet here: a page asking for one is simply not connected.
            "capability" -> result(JSONObject().put("version", 1).put("methods", JSONArray()).put("connected", false))
            "call" -> {
                val name = (body.opt("method") as? String)?.takeIf { it.isNotEmpty() } ?: "that"
                error(WalletError.NOT_IMPLEMENTED, "Peel's wallet has no method $name.")
            }
            "sign_in" -> signIn(body, caller)
            else -> unreadable()
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
        val chosen: SignInKey? = try {
            val wait = minOf(challenge.expiresAtMs - nowMs(), sheetTimeoutMs)
            val answer = withTimeoutOrNull(wait.coerceAtLeast(1)) { Answered(person.askSignIn(SignInAsk(caller.place, request.app, offered))) }
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
                ?: WalletConnection(caller.scopeKey, request.app.name, request.app.title, caller.place, emptyList(), chosen, nowMs()),
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

    private fun error(code: WalletError, message: String) =
        JSONObject().put("error", JSONObject().put("code", code.name).put("message", message))

    companion object {
        const val DEFAULT_SHEET_TIMEOUT_MS = 9 * 60 * 1000L
    }
}
