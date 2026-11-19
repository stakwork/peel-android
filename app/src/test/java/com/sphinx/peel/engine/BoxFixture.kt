package com.sphinx.peel.engine

import java.time.Instant
import java.util.Base64

/// A strut box as the tests stand one in (strut's specs/API.md §13.6; iOS `Tests/PeelTestSupport/BoxFixtures.swift`): its link
/// challenges and its answers, for `BoxLinkTest` and `BoxesTest`.
object BoxFixture {
    /// 2026-11-18, before every challenge here ends.
    val now: Instant = Instant.ofEpochSecond(1_795_000_000)
    const val NOW_MS = 1_795_000_000_000L
    /// An end long after any test runs (2096), for tests on the real clock.
    const val LATER = 4_000_000_000_000L

    /// A challenge as strut's `linkChallenge` writes one: base64url JSON claims, then its HMAC (a stand-in: Peel never checks it).
    /// Its keys in order, as iOS's fixture writes them: the same text every run, so a signature over it is a vector.
    fun challenge(host: String, actor: String? = "ada-3f9a", name: String? = "Ada Lovelace", exp: Long = NOW_MS + 600_000): String {
        val claims = sortedMapOf<String, Any>("host" to host, "nonce" to "00112233445566778899aabbccddeeff", "exp" to exp)
        actor?.let { claims["actor"] = it }
        name?.let { claims["name"] = it }
        val json = claims.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":" + if (v is String) "\"$v\"" else "$v" }
        val part = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
        return BoxChallenge.PREFIX + part + ".Xk2bYWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXo"
    }

    /// What strut answers a link or a renewal with.
    fun answer(
        actor: String = "ada-3f9a", name: String? = "Ada Lovelace", token: String = "strut-person.eyJ.abc",
        exp: Long = NOW_MS + 7 * 86_400_000L,
    ): String = """{"actor":"$actor",${name?.let { "\"name\":\"$it\"," } ?: ""}"token":"$token","exp":$exp}"""

    /// A box kept as linked, its token named by its address.
    fun box(url: String, renewedAt: Instant = now, exp: Instant = now.plusSeconds(7 * 86_400)): BoxLink =
        BoxLink(url, "ada-3f9a", "Ada", "strut-person.secret-${url.length}", exp, linkedAt = renewedAt, renewedAt = renewedAt)

    /// iOS's vector (`BoxLinkTests.vector`): the account key of "abandon … about" (child 0, bitcoin) and its signature over the
    /// [challenge] for box.example.com, made by the core and checked with strut's own `ln-message` verifier
    /// (`VERIFIERS["ln-message"]`, src/visitors.ts at strut 35fbefc0, the one `POST /people/link` runs).
    const val VECTOR_TEXT =
        "strut-link.eyJhY3RvciI6ImFkYS0zZjlhIiwiZXhwIjoxNzk1MDAwNjAwMDAwLCJob3N0IjoiYm94LmV4YW1wbGUuY29tIiwibmFtZSI6IkFkYSBMb3ZlbGFjZSIsIm5vbmNlIjoiMDAxMTIyMzM0NDU1NjY3Nzg4OTlhYWJiY2NkZGVlZmYifQ.Xk2bYWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXo"
    const val VECTOR_KEY = "03216967587493cc956be284fb14c9a5f102d1030b61694d622f368a554828e766"
    const val VECTOR_SIG =
        "20e791ec9f2ec2be6dd6ee0d46564ef8ffc522f3917ff9a77633d63172013bde175b254bf01ef70ea4eea4ef84277d8c66434ed6b8cbb7a386c6ffaac9cfaf1f6d"
}

/// A box that answers by route: each call recorded, [respond] deciding the answer.
class FakeBox(private val respond: (method: String, url: String, json: String?, bearer: String?) -> BoxResponse) : BoxHttp {
    data class Call(val method: String, val url: String, val json: String?, val bearer: String?)

    val calls = mutableListOf<Call>()

    override suspend fun call(method: String, url: String, json: String?, bearer: String?, limit: Int, timeoutSeconds: Long): BoxResponse {
        calls += Call(method, url, json, bearer)
        return respond(method, url, json, bearer)
    }
}

/// An in-memory [BoxStore].
class MemoryBoxStore(var text: String? = null) : BoxStore {
    override fun read() = text
    override fun write(text: String) { this.text = text }
    override fun delete() { text = null }
}
