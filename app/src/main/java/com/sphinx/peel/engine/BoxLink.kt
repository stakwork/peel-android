package com.sphinx.peel.engine

import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

// Peel linked to a strut box (a self-hosted Stadeum box; strut's plans/people-and-hosts.md §2.1, specs/API.md §13.6;
// docs/phases/45-box-link.md). Ported from `peel`'s PR #574, `Sources/PeelCore/BoxLink.swift`. A member signed in there asks for
// a link challenge naming them (the Jamie app's Connect › Link Peel), Peel signs it with the account's own key and posts it, and
// the box links that key to the member and answers a person token for seven days. The key is what lasts: Peel renews the token
// by signing again, and removing the link in Connect ends it at once. No device flow, no device record; beside Hive's pairing
// ([HiveLink]), never in its place. Pure (the HTTP is behind [BoxHttp]), JVM-tested (`BoxLinkTest`).

/// A failure said in words for the person (iOS `PeelError`), from a box or about one.
open class BoxException(message: String) : Exception(message)

/// The values of a `peel://<host><path>?…` link that names exactly `names`, each once, and nothing else (iOS `PeelLink`): no
/// user, port or fragment, and `path` as given ("" for none, which takes "/" too). Null for any other link. Any page can open
/// one, so a `peel://` link is read through here. (`PublicAppLink.parse` keeps its own reader, as it was before this port.)
object PeelLink {
    fun values(link: String, host: String, path: String = "", names: Set<String>): Map<String, String>? {
        val parts = UrlParts.parse(link) ?: return null
        if (parts.scheme.lowercase() != "peel" || parts.authority?.lowercase() != host) return null
        val pathMatches = if (path.isEmpty()) parts.path.isEmpty() || parts.path == "/" else parts.path == path
        if (!pathMatches || parts.fragment != null) return null
        val items = (parts.query ?: return null).split('&')
        if (items.size != names.size) return null
        val values = mutableMapOf<String, String>()
        for (item in items) {
            val eq = item.indexOf('=')
            if (eq < 0) return null
            val name = percentDecode(item.substring(0, eq)) ?: return null
            val value = percentDecode(item.substring(eq + 1)) ?: return null
            if (name !in names || values.put(name, value) != null) return null
        }
        return values
    }
}

/// A box's link challenge (strut's `strut-link.<claims>.<hmac>`, claims `{ host, actor?, name?, nonce, exp }`; iOS `BoxChallenge`).
/// Read, never verified: the HMAC is the box's to check. What it names is shown to the person before Peel signs it; it is the only
/// thing Peel signs for a box.
data class BoxChallenge(
    /// The whole challenge, as signed: its UTF-8 bytes.
    val text: String,
    /// The box's host name, lower case, no port: where it may be signed for.
    val host: String,
    /// The person it links; null for a renewal's, which names nobody.
    val actor: String?,
    /// Their name as the box shows it, for the question.
    val name: String?,
    val nonce: String,
    /// When it ends, unix MILLISECONDS.
    val expiresAtMs: Long,
) {
    /// Who it links, as the person reads it: the name, else the actor.
    val person: String? get() = BoxLink.person(name, actor)

    fun expired(nowMs: Long): Boolean = expiresAtMs <= nowMs

    companion object {
        const val PREFIX = "strut-link."
        /// The longest challenge read; strut's are a few hundred characters.
        const val MAX_LENGTH = 4_096

        /// Null for anything that is not a box's link challenge.
        fun parse(text: String): BoxChallenge? {
            val c = StrutClaims.read(text, PREFIX, MAX_LENGTH) ?: return null
            val host = (c.opt("host") as? String)?.takeIf { it.isNotEmpty() } ?: return null
            val nonce = (c.opt("nonce") as? String)?.takeIf { it.isNotEmpty() } ?: return null
            val exp = WalletProtocol.integer(c.opt("exp"))?.takeIf { it >= 1 } ?: return null
            // As strut reads one: an actor or a name, when there, is a string.
            if (c.has("actor") && c.opt("actor") !is String) return null
            if (c.has("name") && c.opt("name") !is String) return null
            return BoxChallenge(
                text, host.lowercase(), (c.opt("actor") as? String)?.ifEmpty { null }, (c.opt("name") as? String)?.ifEmpty { null },
                nonce, exp,
            )
        }
    }
}

/// A box's link as Peel takes it, `peel://strut/link?url=<box>&challenge=<…>` (from the box's QR code, or its Open in Peel; iOS
/// `BoxLinkOffer`): the box's address, which may carry a mount path (`/lab`), and the challenge to sign for it.
data class BoxLinkOffer(
    /// The box's base URL as strut names one ([Seller.base]): where Peel posts.
    val box: String,
    val challenge: BoxChallenge,
) {
    val id: String get() = challenge.nonce

    /// The box's host as the person reads it, and as its challenge names it.
    val host: String get() = Seller.host(box) ?: ""

    /// Why Peel will not sign it at [nowMs], in words; null when it may ask. It must link someone, be for this box's own host, not
    /// have run out, and be at an address Peel sends a token to ([BoxLink.reachable]).
    fun problem(nowMs: Long = System.currentTimeMillis(), allowsLoopback: Boolean): String? {
        val host = host
        if (challenge.actor == null) return "This link doesn't name anyone to link Peel to. Choose Link Peel in Connect on $host again."
        if (challenge.host != host) return "This link is for ${TextLine.clean(challenge.host, 80)}, not $host, so Peel won't sign it."
        if (challenge.expired(nowMs)) return "This link ran out. Choose Link Peel in Connect on $host again."
        if (!BoxLink.reachable(box, allowsLoopback)) return "Peel links only to a Stadeum server at an https address, not $host."
        return null
    }

    companion object {
        /// `peel://strut/link?url=…&challenge=…` and nothing else.
        fun parse(link: String): BoxLinkOffer? {
            val values = PeelLink.values(link, host = "strut", path = "/link", names = setOf("url", "challenge")) ?: return null
            return of(values.getValue("url"), values.getValue("challenge"))
        }

        /// A box's address (https, or http on loopback) and its challenge.
        fun of(box: String, challenge: String): BoxLinkOffer? {
            val base = Seller.base(box) ?: return null
            return BoxLinkOffer(base, BoxChallenge.parse(challenge) ?: return null)
        }

        /// What is said of a `peel://strut/…` link that is not one (the Mac's words; iOS ignores it).
        const val BROKEN = "That link to a Stadeum server doesn't work. Choose Link Peel in Connect on the server again."
    }
}

/// Where the boxes are kept (iOS `Profile.boxes`, a Keychain item): a Keystore-wrapped file per account on a phone
/// (`KeystoreBoxStore`), beside Hive's pairing ([HiveStore]) and never in it.
interface BoxStore {
    /// The kept text, or null when none is kept.
    fun read(): String?
    fun write(text: String)
    fun delete()
}

/// A box Peel is linked to, as kept ([BoxStore]; never in a database, never logged; iOS `BoxLink`): its address, the member it is
/// linked to, and their token, renewed by signing again. Never described with its token.
data class BoxLink(
    /// The box's base URL ([Seller.base]): strut's UI and routes under it.
    val url: String,
    /// The member, as the box names them.
    val actor: String,
    /// Their name as the box shows it.
    val name: String?,
    /// The person token (`strut-person.…`): a bearer credential.
    val token: String,
    /// When the token ends.
    val exp: Instant,
    val linkedAt: Instant,
    /// When the token was last made: at linking, or renewed.
    val renewedAt: Instant,
) {
    /// The box's host as the person reads it.
    val host: String get() = Seller.host(url) ?: url
    /// Who Peel is linked as there: their name, else their actor.
    val person: String get() = person(name, actor) ?: ""
    /// The id its page carries ([home]), which says whose page it is ([BoxesModel.shows]).
    val peer: String get() = "box:$url"

    override fun toString() = "box link to $host"

    /// Due for renewal: a day since the last, or the token within a day of ending.
    fun due(now: Instant = Instant.now()): Boolean =
        now.toEpochMilli() - renewedAt.toEpochMilli() >= RENEW_EVERY_MS || exp.toEpochMilli() - now.toEpochMilli() < RENEW_EVERY_MS

    /// The box's own strut UI as its member (an [OrgPageRequest], iOS `BoxLink.home`): its token as the first request's bearer and
    /// in the page's storage, never in an address. strut's UI lists the box's apps and opens them in place.
    val home: OrgPageRequest get() = OrgPageRequest(url = "$url/", token = token, peer = peer, workspace = host, title = host)

    fun toJson(): JSONObject = JSONObject().apply {
        put("url", url); put("actor", actor); name?.let { put("name", it) }; put("token", token)
        put("exp", exp.toString()); put("linkedAt", linkedAt.toString()); put("renewedAt", renewedAt.toString())
    }

    companion object {
        /// How often the token is renewed: it lasts a week.
        const val RENEW_EVERY_MS = 24L * 3600 * 1000
        /// iOS `TextLine.maxName`.
        private const val MAX_NAME = 60

        /// A member as the person reads them: their name, else their actor, cleaned.
        fun person(name: String?, actor: String?): String? =
            listOf(name, actor).firstNotNullOfOrNull { it?.let { text -> TextLine.clean(text, MAX_NAME) }?.ifEmpty { null } }

        /// Whether Peel sends a token to the box at [url]: https off this device; this device (http or https) only with
        /// [allowsLoopback], a debug build's local strut (iOS: a development profile).
        fun reachable(url: String, allowsLoopback: Boolean): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            if (uri.rawUserInfo != null) return false
            val host = Seller.host(url) ?: return false
            val scheme = uri.scheme?.lowercase()
            if (Seller.isLoopback(host)) return allowsLoopback && scheme in listOf("http", "https")
            return scheme == "https"
        }

        /// The links kept in [store], none when nothing is. Throws when what is kept cannot be read: it is never written over then.
        fun read(store: BoxStore): List<BoxLink> {
            val text = store.read() ?: return emptyList()
            return parse(text) ?: throw BoxException("Peel could not read the Stadeum servers it keeps.")
        }

        /// Keeps [links] in [store]; none deletes it.
        fun write(links: List<BoxLink>, store: BoxStore) {
            if (links.isEmpty()) return store.delete()
            store.write(JSONObject().put("boxes", JSONArray(links.map { it.toJson() })).toString())
        }

        /// The kept form, every box or none: null when any of it cannot be read (iOS decodes the whole list or fails).
        internal fun parse(text: String): List<BoxLink>? = try {
            val boxes = JSONObject(text).getJSONArray("boxes")
            (0 until boxes.length()).map { i ->
                val b = boxes.getJSONObject(i)
                BoxLink(
                    url = b.getString("url"), actor = b.getString("actor"), name = (b.opt("name") as? String)?.ifEmpty { null },
                    token = b.getString("token"), exp = Instant.parse(b.getString("exp")),
                    linkedAt = Instant.parse(b.getString("linkedAt")), renewedAt = Instant.parse(b.getString("renewedAt")),
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}

/// One HTTP answer from a box: status and body.
class BoxResponse(val status: Int, val body: String)

/// How Peel reaches a box; a test's stand-in answers without a network.
fun interface BoxHttp {
    /// Throws `java.io.IOException` when no answer came, [BoxException] for one past [limit] bytes.
    suspend fun call(method: String, url: String, json: String?, bearer: String?, limit: Int, timeoutSeconds: Long): BoxResponse
}

/// OkHttp's [BoxHttp] (iOS `Web.requestStrut`): JSON in, the answer capped at its limit, no redirects followed.
class OkHttpBox(
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(),
) : BoxHttp {
    override suspend fun call(method: String, url: String, json: String?, bearer: String?, limit: Int, timeoutSeconds: Long): BoxResponse =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).header("Accept", "application/json")
            bearer?.let { builder.header("Authorization", "Bearer $it") }
            val body = json?.toRequestBody("application/json".toMediaType())
            builder.method(method, body ?: if (method == "GET") null else "".toRequestBody())
            client.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).build().newCall(builder.build()).execute().use { r ->
                val source = r.body?.source()
                val bytes = source?.let { it.request(limit + 1L); it.buffer.snapshot().toByteArray() } ?: ByteArray(0)
                if (bytes.size > limit) throw BoxException("${Seller.host(url) ?: "The Stadeum server"}'s answer was too large.")
                BoxResponse(r.code, String(bytes, Charsets.UTF_8))
            }
        }
}

/// strut's three routes for Peel on a box (strut's specs/API.md §13.6; iOS `BoxClient`), none with a credential but the
/// signature, and a token's check on use.
class BoxClient(val box: String, private val http: BoxHttp = OkHttpBox()) {
    val host: String get() = Seller.host(box) ?: "the Stadeum server"

    /// What a link or a renewal answers: the member and their token. Never described with its token.
    class Answer(val actor: String, val name: String?, val token: String, val exp: Instant) {
        override fun toString() = "Answer(actor=$actor, token=<redacted>)"
    }

    /// What a renewal comes to.
    sealed class Renewal {
        data class Renewed(val link: BoxLink) : Renewal()
        /// The member removed the link (`403 not_linked`): the box is let go.
        data object Unlinked : Renewal()
    }

    /// Links this account's key to the member [challenge] names (`POST /people/link`), with the account's [signed] signature over
    /// it, its [name] (its alias) and its Sphinx address. Answers the link to keep.
    suspend fun link(challenge: BoxChallenge, signed: VisitorSignature, name: String?, now: Instant = Instant.now()): BoxLink {
        val body = JSONObject().put("challenge", challenge.text).put("key", signed.key).put("sig", signed.sig)
        // One line of at most 80 characters, as strut keeps a name.
        name?.let { TextLine.clean(it, 80).trim() }?.takeIf { it.isNotEmpty() }?.let { body.put("name", it) }
        signed.address?.let { body.put("address", it) }
        val r = request("POST", "people/link", body.toString())
        if (r.status != 200) throw failure(r.status, r.body, linking = true)
        val answer = answer(r.body, now, host)
        return BoxLink(box, answer.actor, answer.name, answer.token, answer.exp, linkedAt = now, renewedAt = now)
    }

    /// A new token for [link]: a fresh challenge from the box (`GET /people/challenge`), for its own host, signed with [sign] (the
    /// account's key) and posted (`POST /people/token`). Its end is the box's to check: it lives a minute, and this device's clock
    /// may be off by more.
    suspend fun renew(link: BoxLink, now: Instant = Instant.now(), sign: suspend (BoxChallenge) -> VisitorSignature): Renewal {
        val r = request("GET", "people/challenge", null)
        if (r.status != 200) throw failure(r.status, r.body, linking = false)
        val challenge = (json(r.body)?.opt("challenge") as? String)?.let(BoxChallenge::parse)
            ?: throw BoxException("$host's answer for renewing Peel's link could not be read.")
        if (challenge.host != host) {
            throw BoxException("$host gave a challenge for ${TextLine.clean(challenge.host, 80)}, so Peel didn't sign it.")
        }
        val signed = sign(challenge)
        val renewed = request(
            "POST", "people/token", JSONObject().put("challenge", challenge.text).put("key", signed.key).put("sig", signed.sig).toString(),
        )
        if (renewed.status == 403) return Renewal.Unlinked
        if (renewed.status != 200) throw failure(renewed.status, renewed.body, linking = false)
        val answer = answer(renewed.body, now, host)
        return Renewal.Renewed(link.copy(actor = answer.actor, name = answer.name, token = answer.token, exp = answer.exp, renewedAt = now))
    }

    /// Whether the box takes [link]'s token: a read any member may make (`GET /apps`). False for a 401, which is the token no
    /// longer held (expired, or the link removed); throws for anything else.
    suspend fun accepts(link: BoxLink): Boolean {
        val r = http.call("GET", "$box/apps", null, link.token, APPS_LIMIT, 20)
        if (r.status == 401) return false
        if (r.status != 200) throw failure(r.status, r.body, linking = false, couldNot = "check Peel's link")
        return true
    }

    private suspend fun request(method: String, path: String, json: String?): BoxResponse =
        http.call(method, "$box/$path", json, null, LIMIT, 30)

    /// strut's refusal, in words the person can act on (strut's words beside them only where they say more).
    internal fun failure(
        status: Int, body: String, linking: Boolean, couldNot: String = if (linking) "link Peel" else "renew Peel's link",
    ): BoxException {
        val said = (json(body)?.opt("error") as? String)?.let { TextLine.clean(it, 200) }
        return BoxException(
            when {
                status == 401 && linking -> "$host didn't take the link: it ran out or was already used. Choose Link Peel in Connect there again."
                status == 401 -> "$host didn't take Peel's signature."
                status == 409 -> "Your Sphinx account is already linked to someone else on $host. They remove it in Connect there first."
                status == 413 -> "$host refused the link as too large."
                status == 429 -> "$host is busy. Try again in a minute."
                else -> "$host could not $couldNot: ${said ?: "HTTP $status"}."
            },
        )
    }

    companion object {
        /// The most a link or renewal answer may be.
        const val LIMIT = 64 * 1024
        /// The most the token check's app list may be.
        const val APPS_LIMIT = 4 * 1024 * 1024

        private fun json(body: String): JSONObject? = runCatching { JSONObject(body) }.getOrNull()

        /// `{ actor, name?, token, exp }`, checked: a person token, an actor to show, and an end still ahead.
        internal fun answer(body: String, now: Instant, host: String): Answer {
            val unread = BoxException("$host's answer could not be read.")
            val a = json(body) ?: throw unread
            val actor = (a.opt("actor") as? String)?.takeIf { it.length in 1..200 } ?: throw unread
            val token = (a.opt("token") as? String)
                ?.takeIf { it.startsWith("strut-person.") && it.toByteArray(Charsets.UTF_8).size <= 4_096 && it.none(Char::isWhitespace) }
                ?: throw unread
            val ms = WalletProtocol.integer(a.opt("exp")) ?: throw unread
            val exp = Instant.ofEpochMilli(ms)
            if (!exp.isAfter(now)) throw BoxException("$host answered a token that had already run out.")
            return Answer(actor, (a.opt("name") as? String)?.ifEmpty { null }, token, exp)
        }
    }
}
