package com.sphinx.peel.engine

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONObject

// Sign in with Sphinx: what a strut app's `sign_in` message asks Peel to sign, and when Peel refuses to
// (docs/phases/27-sign-in-with-sphinx.md). Pure, JVM-tested. Ported from `peel`'s PR #551 and strut's
// specs/WALLET.md. The seller (not Peel) verifies the challenge's HMAC, so it is never checked here.

/// Which key signs a visitor challenge. The wire strings are strut's.
enum class SignInKey(val wire: String) {
    /// The person's own Sphinx account key (child 0): every app sees the same person.
    root("root"),
    /// A key for one domain alone (LUD-05), so no other app can tell it is the same person.
    domain("domain");

    companion object {
        fun fromWire(text: String?): SignInKey? = entries.firstOrNull { it.wire == text }
    }
}

/// A seller's `strut-visitor.<claims>.<hmac>` challenge. The whole [text] is what gets signed, as UTF-8.
data class VisitorChallenge(
    val text: String,
    val host: String,
    val visitorKey: SignInKey,
    val nonce: String,
    /// Unix milliseconds.
    val expiresAtMs: Long,
) {
    companion object {
        const val PREFIX = "strut-visitor."
        const val MAX_LENGTH = 2048
        private val BASE64URL = Regex("[A-Za-z0-9_-]+")

        fun parse(text: String): VisitorChallenge? {
            if (text.length > MAX_LENGTH || !text.startsWith(PREFIX)) return null
            val parts = text.removePrefix(PREFIX).split('.')
            if (parts.size != 2 || parts.any { it.isEmpty() || !BASE64URL.matches(it) }) return null
            val json = try {
                val bytes = java.util.Base64.getUrlDecoder().decode(parts[0])
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                JSONObject(decoder.decode(ByteBuffer.wrap(bytes)).toString())
            } catch (e: Exception) {
                return null
            }
            val host = (json.opt("host") as? String)?.takeIf { it.isNotEmpty() } ?: return null
            val nonce = (json.opt("nonce") as? String)?.takeIf { it.isNotEmpty() } ?: return null
            val exp = json.opt("exp").let { if (it is Int) it.toLong() else it as? Long } ?: return null
            if (exp < 1) return null
            val key = SignInKey.fromWire(json.opt("visitorKey") as? String) ?: return null
            return VisitorChallenge(text, host, key, nonce, exp)
        }
    }
}

/// What the page names about itself. Shown to the person as the page's own claim, never trusted.
data class SignInApp(val name: String, val version: String, val title: String)

/// A checked `sign_in` message.
data class SignInRequest(val app: SignInApp, val seller: String, val challenge: VisitorChallenge) {
    companion object {
        private const val MAX_FIELD = 1000

        /// Why Peel would not sign [raw] (a parsed JSON value); empty when it would. [trustedHost] is the host of
        /// the origin Peel itself loaded: when given, the challenge is judged against it, never against what the page
        /// says its seller is.
        fun problems(raw: Any?, nowMs: Long, trustedHost: String? = null): List<String> = check(raw, nowMs, trustedHost).second

        /// The request when it has no problems.
        fun of(raw: Any?, nowMs: Long, trustedHost: String? = null): SignInRequest? = check(raw, nowMs, trustedHost).first

        private fun isInteger(value: Any?, expected: Long) = (value is Int || value is Long) && (value as Number).toLong() == expected

        private fun check(raw: Any?, nowMs: Long, trustedHost: String?): Pair<SignInRequest?, List<String>> {
            if (raw !is JSONObject) return null to listOf("a sign-in request must be an object")
            val problems = mutableListOf<String>()
            if (!isInteger(raw.opt("v"), 1) || raw.opt("op") != "sign_in") {
                problems += "a sign-in request is { v: 1, op: \"sign_in\", … }"
            }
            val seller = (raw.opt("seller") as? String)?.takeIf { Seller.isSellerURL(it) }
            if (seller == null) {
                problems += "seller must be a strut server's base URL: https, or http on loopback, with no credentials, query or fragment"
            }
            var app: SignInApp? = null
            val appJson = raw.opt("app") as? JSONObject
            if (appJson == null) {
                problems += "app is missing"
            } else {
                val fields = listOf("name", "version", "title").map { key ->
                    val value = (appJson.opt(key) as? String)?.takeIf { it.isNotEmpty() }
                    if (value == null) problems += "app.$key must be a string"
                    else if (value.length > MAX_FIELD) problems += "app.$key is longer than $MAX_FIELD characters"
                    value
                }
                if (fields.all { it != null }) app = SignInApp(fields[0]!!, fields[1]!!, fields[2]!!)
            }
            val challenge = (raw.opt("challenge") as? String)?.let(VisitorChallenge::parse)
            if (challenge == null) {
                problems += "challenge must be a strut visitor challenge (strut-visitor.<claims>.<hmac>): nothing else is signed"
                return null to problems
            }
            val host = trustedHost ?: seller?.let(Seller::host)
            if (host != null && host != challenge.host) {
                problems += "the challenge is for ${WorkspaceApp.clean(challenge.host, 80)}, not $host: never signed"
            }
            if (challenge.expiresAtMs <= nowMs) problems += "the challenge has ended"
            if (problems.isNotEmpty() || seller == null || app == null) return null to problems
            return SignInRequest(app, seller, challenge) to problems
        }
    }
}

/// What a sign-in answers with: the public key that signed, and its signature (hex). The `result` JSON is `{key, sig}`.
data class VisitorSignature(val key: String, val sig: String) {
    fun toJson(): JSONObject = JSONObject().put("key", key).put("sig", sig)
}

/// Signs a challenge with the account (the engine's seed); a test's stand-in signs without the native library.
fun interface VisitorSigner {
    suspend fun sign(challenge: VisitorChallenge, key: SignInKey): VisitorSignature
}
