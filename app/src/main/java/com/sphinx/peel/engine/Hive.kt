package com.sphinx.peel.engine

import java.net.URI
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/// A failure said in words for the person (iOS `PeelError`).
class HiveException(message: String) : Exception(message)

/// A poll Hive could not answer this time (429, 5xx, no network): asked again.
internal class Unanswered(val failure: HiveException) : Exception(failure.message)

/// An org Hive gave no record for this time, and why, in Hive's words.
data class HiveOrgError(val org: String, val error: String) {
    val name: String get() = WorkspaceApp.clean(org, 64)
    val reason: String get() = WorkspaceApp.clean(error, 200)
}

/// A Sphinx key Hive would not link to the person, and why in Hive's words.
data class SphinxRefusal(val pubkey: String, val reason: String)

/// What proves to Hive that this device's account holds its Sphinx key
/// (iOS `SphinxProof`, `Engine.sphinxProof`). Never logged.
class SphinxProof(val pubkey: String, val token: String) {
    override fun toString() = "SphinxProof(pubkey=$pubkey, token=<redacted>)"
}

/// A code for the person to approve in Hive, signed in there.
data class Pairing(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    /// The page with the code filled in: the button opens it.
    val verificationUriComplete: String,
    val expiresIn: Int,
    val interval: Int,
) {
    override fun toString() = "Pairing(userCode=$userCode, deviceCode=<redacted>)"
}

/// What this device keeps of its Hive pairing (iOS `HiveLink`): the device
/// credential, the person's actor, and the peer records last renewed. Kept
/// only in the Keystore-wrapped store (`HiveStore`), never logged.
data class HiveLink(
    val hive: String,
    val credential: String,
    val deviceId: String,
    val deviceName: String,
    val actor: String,
    val peers: List<HivePeer>,
    val errors: List<HiveOrgError> = emptyList(),
    val expiresAt: Instant? = null,
    val renewedAt: Instant,
    /// When Hive answered 401 on renewal: kept, so its tokens stay unused until paired again.
    val revokedAt: Instant? = null,
    /// Whether Hive knows the person's Sphinx account; null until Hive has said.
    val identified: Boolean? = null,
    val sphinxRefused: SphinxRefusal? = null,
    /// The account key this pairing was made or identified for; null: unknown.
    val sphinxPubkey: String? = null,
) {
    /// Whether this pairing may be kept for the account with [pubkey]: yes unless recorded for another.
    fun belongs(to: String): Boolean = sphinxPubkey?.let { it == to } ?: true

    /// Due for renewal: a day since the last, or its tokens within a day of expiring.
    fun due(now: Instant = Instant.now()): Boolean =
        now.epochSecond - renewedAt.epochSecond >= HiveClient.RENEW_EVERY_SECONDS ||
            (expiresAt?.let { it.epochSecond - now.epochSecond < HiveClient.RENEW_EVERY_SECONDS } ?: false)

    fun toJson(): String = JSONObject().apply {
        put("hive", hive); put("credential", credential); put("deviceID", deviceId); put("deviceName", deviceName)
        put("actor", actor)
        put("peers", JSONArray(peers.map { p ->
            JSONObject().put("id", p.id).put("baseUrl", p.baseUrl).put("token", p.token).apply { p.label?.let { put("label", it) } }
        }))
        put("errors", JSONArray(errors.map { JSONObject().put("org", it.org).put("error", it.error) }))
        expiresAt?.let { put("expiresAt", it.toString()) }
        put("renewedAt", renewedAt.toString())
        revokedAt?.let { put("revokedAt", it.toString()) }
        identified?.let { put("identified", it) }
        sphinxRefused?.let { put("sphinxRefused", JSONObject().put("pubkey", it.pubkey).put("reason", it.reason)) }
        sphinxPubkey?.let { put("sphinxPubkey", it) }
    }.toString()

    override fun toString() = "HiveLink(hive=$hive, actor=$actor, peers=${peers.size}, credential=<redacted>)"

    companion object {
        /// The kept form, or null for one that cannot be read.
        fun parse(text: String): HiveLink? = try {
            val j = JSONObject(text)
            HiveLink(
                hive = j.getString("hive"), credential = j.getString("credential"),
                deviceId = j.getString("deviceID"), deviceName = j.optString("deviceName", ""),
                actor = j.getString("actor"), peers = peers(j.optJSONArray("peers")),
                errors = errors(j.optJSONArray("errors")),
                expiresAt = date(j, "expiresAt"), renewedAt = date(j, "renewedAt") ?: return null,
                revokedAt = date(j, "revokedAt"),
                identified = if (j.has("identified")) j.getBoolean("identified") else null,
                sphinxRefused = j.optJSONObject("sphinxRefused")?.let { SphinxRefusal(it.getString("pubkey"), it.getString("reason")) },
                sphinxPubkey = j.optString("sphinxPubkey", "").ifEmpty { null },
            )
        } catch (_: Exception) { null }

        internal fun date(j: JSONObject, key: String): Instant? =
            j.optString(key, "").takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

        internal fun peers(a: JSONArray?): List<HivePeer> = (0 until (a?.length() ?: 0)).mapNotNull { i ->
            a!!.optJSONObject(i)?.let { p ->
                val id = p.optString("id", "").takeIf { it.isNotEmpty() } ?: return@let null
                val base = p.optString("baseUrl", "").takeIf { it.isNotEmpty() } ?: return@let null
                val token = p.optString("token", "").takeIf { it.isNotEmpty() } ?: return@let null
                HivePeer(id, base, token, p.optString("label", "").ifEmpty { null })
            }
        }

        internal fun errors(a: JSONArray?): List<HiveOrgError> = (0 until (a?.length() ?: 0)).mapNotNull { i ->
            a!!.optJSONObject(i)?.let { HiveOrgError(it.optString("org", ""), it.optString("error", "")) }
        }
    }
}

/// One HTTP answer: status and body.
class HiveResponse(val status: Int, val body: String)

/// How the client reaches Hive; a test's stand-in answers without a network.
fun interface HiveHttp {
    /// Throws `java.io.IOException` when no answer came.
    suspend fun call(method: String, url: String, json: String?, bearer: String?, timeoutSeconds: Long): HiveResponse
}

/// OkHttp's [HiveHttp]: answers capped at 512 KB, no redirects followed.
class OkHttpHive(
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(),
) : HiveHttp {
    override suspend fun call(method: String, url: String, json: String?, bearer: String?, timeoutSeconds: Long): HiveResponse =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).header("Accept", "application/json")
            bearer?.let { builder.header("Authorization", "Bearer $it") }
            val body = json?.toRequestBody("application/json".toMediaType())
            builder.method(method, body ?: if (method == "GET") null else "".toRequestBody())
            client.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).build().newCall(builder.build()).execute().use { r ->
                val source = r.body?.source()
                val bytes = source?.let { it.request(HiveClient.LIMIT + 1L); it.buffer.snapshot().toByteArray() } ?: ByteArray(0)
                if (bytes.size > HiveClient.LIMIT) throw HiveException("Stadeum's answer was too large.")
                HiveResponse(r.code, String(bytes, Charsets.UTF_8))
            }
        }
}

/// Hive's device-flow API for a strut desktop app (iOS `HiveClient`). Not
/// ported: Jamie-in-tribes (`jamieLinks`, `linkTribe`, `unlinkTribe`). A call
/// with Jamie is [startCall].
class HiveClient(val hive: String = PRODUCTION, private val http: HiveHttp = OkHttpHive()) {
    /// Where one poll of a [Pairing] stands.
    sealed class Poll {
        object Pending : Poll()
        object Denied : Poll()
        /// Expired, unknown or already collected: start over.
        object Expired : Poll()
        data class Paired(val link: HiveLink) : Poll()
    }

    /// What Hive said to [registerSphinx].
    sealed class SphinxRegistration {
        object Linked : SphinxRegistration()
        /// A Hive from before the route: asked again at the next renewal.
        object NotYet : SphinxRegistration()
        data class Refused(val reason: String) : SphinxRegistration()
    }

    /// Asks Hive for a code, naming this device.
    suspend fun start(name: String): Pairing {
        val (status, body) = request("POST", "api/strut-devices/pair", JSONObject().put("name", name).toString())
        if (status != 200) throw failure(status, body, "start pairing")
        val j = json(body) ?: throw HiveException("Stadeum's pairing code could not be read.")
        val pairing = Pairing(
            deviceCode = j.optString("deviceCode"), userCode = j.optString("userCode"),
            verificationUri = j.optString("verificationUri"), verificationUriComplete = j.optString("verificationUriComplete"),
            expiresIn = j.optInt("expiresIn", 0), interval = j.optInt("interval", 0),
        )
        // Only Hive's own page is ever shown or opened.
        for (page in listOf(pairing.verificationUri, pairing.verificationUriComplete)) {
            if (!isHive(page)) throw HiveException("Stadeum gave a pairing page on another site (${hostOf(page) ?: "none"}); nothing was opened.")
        }
        if (pairing.interval !in 1..600 || pairing.expiresIn !in 1..3600 || pairing.deviceCode.isEmpty() ||
            pairing.userCode.isEmpty() || pairing.userCode.length > 32
        ) throw HiveException("Stadeum's pairing code could not be read.")
        return pairing
    }

    /// Starts a call with Jamie in [workspace] as the paired person: Hive's
    /// `POST /api/strut-devices/calls` (device credential as the bearer, body
    /// `{"workspace":"<slug>"}`) mints the call's Hive token, dispatches Jamie
    /// into a new room and answers `{"url":"<call link>"}` (the token stays
    /// behind Hive's own opaque callKey, never in the link). The link is
    /// returned unchecked: the caller checks it against the account's call
    /// server (`CallLink.hive`).
    suspend fun startCall(link: HiveLink, workspace: String): String {
        if (link.revokedAt != null) throw HiveException("Stadeum no longer knows this device; connect again in You.")
        val (status, body) = request("POST", "api/strut-devices/calls", JSONObject().put("workspace", workspace).toString(), link.credential)
        if (status != 200) throw failure(status, body, "start the call")
        val url = json(body)?.optString("url", "")?.takeIf { it.isNotEmpty() }
            ?: throw HiveException("Stadeum's call link could not be read.")
        return url
    }

    /// One poll of [pairing]: still waiting, an answer, or the device.
    suspend fun poll(pairing: Pairing, name: String, now: Instant = Instant.now()): Poll {
        val (status, body) = request("POST", "api/strut-devices/pair/token", JSONObject().put("deviceCode", pairing.deviceCode).toString())
        if (status == 400) {
            return when (json(body)?.optString("error")) {
                "authorization_pending" -> Poll.Pending
                "access_denied" -> Poll.Denied
                "expired_token" -> Poll.Expired
                else -> throw failure(status, body, "pair")
            }
        }
        if (status != 200) {
            val failure = failure(status, body, "pair")
            throw if (status == 429 || status >= 500) Unanswered(failure) else failure
        }
        val j = json(body) ?: throw HiveException("Stadeum's device credential could not be read.")
        val credential = j.optString("credential")
        if (!credential.startsWith("hivedev_")) throw HiveException("Stadeum's device credential could not be read.")
        val device = j.optJSONObject("device") ?: throw HiveException("Stadeum's device credential could not be read.")
        val (peers, refused) = reachable(HiveLink.peers(j.optJSONArray("peers")))
        return Poll.Paired(
            HiveLink(
                hive = hive, credential = credential, deviceId = device.optString("id"),
                deviceName = device.optString("name", "").ifEmpty { name }, actor = actor(j.optString("actor")),
                peers = peers, errors = HiveLink.errors(j.optJSONArray("errors")) + refused,
                expiresAt = HiveLink.date(j, "expiresAt"), renewedAt = now,
            ),
        )
    }

    /// Polls [pairing] every `interval` until the person answers or the code
    /// runs out: never Pending. A poll that gets no answer is tried again
    /// until [deadline]. [wait] is how it waits between polls (seconds).
    suspend fun answer(
        pairing: Pairing, name: String, deadline: Instant = Instant.now().plusSeconds(pairing.expiresIn.toLong()),
        wait: suspend (Long) -> Unit = { delay(it * 1000) },
    ): Poll {
        while (true) {
            wait(pairing.interval.toLong())
            coroutineContext.ensureActive()
            try {
                val poll = poll(pairing, name)
                if (poll != Poll.Pending) return poll
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val retry = e is Unanswered || e is java.io.IOException
                if (!retry) throw e
                if (!Instant.now().isBefore(deadline)) throw (e as? Unanswered)?.failure ?: e
            }
        }
    }

    /// [link] with Hive's peers as they are now; null once Hive no longer knows the device (revoked).
    suspend fun renew(link: HiveLink, now: Instant = Instant.now()): HiveLink? {
        val (status, body) = request("GET", "api/strut-devices/peers", null, bearer = link.credential)
        if (status == 401) return null
        if (status != 200) throw failure(status, body, "renew the Stadeum workspaces")
        val j = json(body) ?: throw HiveException("Stadeum could not renew the Stadeum workspaces: unreadable answer.")
        val (peers, refused) = reachable(HiveLink.peers(j.optJSONArray("peers")))
        return link.copy(
            actor = actor(j.optString("actor")), peers = peers, errors = HiveLink.errors(j.optJSONArray("errors")) + refused,
            expiresAt = HiveLink.date(j, "expiresAt"), renewedAt = now,
            identified = if (j.has("sphinx")) j.optBoolean("sphinx") else null,
        )
    }

    /// Tells Hive the Sphinx account of [link]'s person, proved by [proof].
    suspend fun registerSphinx(link: HiveLink, proof: SphinxProof): SphinxRegistration {
        val (status, body) = request(
            "POST", "api/strut-devices/sphinx",
            JSONObject().put("pubkey", proof.pubkey).put("token", proof.token).toString(), bearer = link.credential,
        )
        return when (status) {
            200 -> SphinxRegistration.Linked
            401, 404 -> SphinxRegistration.NotYet
            409 -> SphinxRegistration.Refused(said(body) ?: "This Sphinx account is linked to another Stadeum user.")
            else -> throw failure(status, body, "link your Sphinx account")
        }
    }

    /// The peers whose token may go to their address: https off this machine
    /// (plain http on loopback only from a test Hive there), each id once and
    /// in strut's form; the rest as errors, never used.
    internal fun reachable(peers: List<HivePeer>): Pair<List<HivePeer>, List<HiveOrgError>> {
        val testHive = isLoopback(hostOf(hive))
        val kept = mutableListOf<HivePeer>()
        val refused = mutableListOf<HiveOrgError>()
        for (peer in peers) {
            if (!PEER_ID.matches(peer.id) || kept.any { it.id == peer.id }) {
                refused += HiveOrgError(peer.id, "its id is not one Peel can use")
                continue
            }
            val uri = runCatching { URI(peer.baseUrl) }.getOrNull()
            val local = isLoopback(uri?.host)
            val fine = uri != null && uri.userInfo == null && ((uri.scheme == "https" && !local) || (testHive && local && uri.scheme in listOf("http", "https")))
            if (fine) kept += peer else refused += HiveOrgError(peer.id, "its address is not one Peel sends a token to")
        }
        return kept to refused
    }

    /// A page on this Hive: its host, over its scheme.
    internal fun isHive(url: String): Boolean {
        val page = runCatching { URI(url) }.getOrNull() ?: return false
        val own = runCatching { URI(hive) }.getOrNull() ?: return false
        return page.host?.lowercase() == own.host?.lowercase() && page.scheme == own.scheme && page.userInfo == null &&
            (page.scheme == "https" || isLoopback(page.host))
    }

    private suspend fun request(method: String, path: String, json: String?, bearer: String? = null): Pair<Int, String> {
        val r = http.call(method, hive.trimEnd('/') + "/" + path, json, bearer, 30)
        return r.status to r.body
    }

    private fun failure(status: Int, body: String, couldNot: String) =
        HiveException("Stadeum could not $couldNot: ${said(body) ?: "HTTP $status"}.")

    /// Hive's own words in an answer (`{ "error": … }`), cleaned for showing.
    private fun said(body: String): String? = json(body)?.optString("error", "")?.let { WorkspaceApp.clean(it, 200) }?.ifEmpty { null }

    private fun json(body: String): JSONObject? = runCatching { JSONObject(body) }.getOrNull()

    private fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()

    companion object {
        const val PRODUCTION = "https://hive.sphinx.chat"
        /// How often the peers are renewed: their tokens last a week.
        const val RENEW_EVERY_SECONDS = 24L * 3600
        /// The most a Hive answer may be.
        const val LIMIT = 512 * 1024
        private val PEER_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        private val ACTOR = Regex("^[A-Za-z0-9._-]{1,120}$")

        internal fun isLoopback(host: String?): Boolean =
            host != null && (host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1")

        /// The person's actor: the first part of a strut session id, so plain.
        internal fun actor(actor: String): String {
            if (!ACTOR.matches(actor)) throw HiveException("Stadeum named this account in a way Peel cannot use (${WorkspaceApp.clean(actor, 40)}).")
            return actor
        }
    }
}
