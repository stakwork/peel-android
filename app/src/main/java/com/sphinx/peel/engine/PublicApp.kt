package com.sphinx.peel.engine

import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

// Sign in with Sphinx, the page side (docs/phases/27-sign-in-with-sphinx.md): a stranger's public strut app
// opened in a WebView by a `peel://open?url=<address>` link. Ported from `peel`'s PR #551; pure, JVM-tested.

/// A strut server ("seller") address: https, or http only on this device. Never with credentials, a query
/// or a fragment. [base] is the form a link's address is reduced to.
object Seller {
    /// Whether [text] is exactly a seller's base URL (iOS `Seller.isSellerURL`).
    fun isSellerURL(text: String): Boolean {
        val uri = parse(text) ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = hostOf(uri) ?: return false
        if (uri.rawUserInfo != null) return false
        if (!uri.rawQuery.isNullOrEmpty() || !uri.rawFragment.isNullOrEmpty()) return false
        return scheme == "https" || (scheme == "http" && isLoopback(host))
    }

    /// The host of [url]: lower-case, no port, no brackets. Null when it has none.
    fun host(url: String): String? = parse(url)?.let(::hostOf)

    /// The seller [text] names: trimmed, no credentials, no trailing `/` or `/public`, no query or fragment,
    /// scheme and host lower-case, a default port dropped. Null unless that is a seller URL.
    fun base(text: String): String? {
        val uri = parse(text.trim()) ?: return null
        if (uri.rawUserInfo != null) return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = hostOf(uri) ?: return null
        var path = (uri.rawPath ?: "").trimEnd('/')
        if (path.endsWith("/public")) path = path.removeSuffix("/public").trimEnd('/')
        val built = "$scheme://${authority(scheme, host, uri.port)}$path"
        return built.takeIf(::isSellerURL)
    }

    /// `scheme://host[:port]`, lower-case, a default port omitted: the origin a page loaded from. Null with no scheme or host.
    fun origin(url: String): String? {
        val uri = parse(url) ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = hostOf(uri) ?: return null
        return "$scheme://${authority(scheme, host, uri.port)}"
    }

    fun isLoopback(host: String): Boolean {
        if (host == "localhost" || host == "::1") return true
        if (host.endsWith(".localhost")) return Regex("[a-z0-9-]+").matches(host.removeSuffix(".localhost"))
        val octets = host.split('.')
        return octets.size == 4 && octets[0] == "127" &&
            octets.all { o -> o.isNotEmpty() && o.length <= 3 && o.all { it in '0'..'9' } && o.toInt() <= 255 }
    }

    private fun parse(text: String): URI? = runCatching { URI(text) }.getOrNull()

    private fun hostOf(uri: URI): String? =
        uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotEmpty() }

    private fun authority(scheme: String, host: String, port: Int): String {
        val shown = if (host.contains(':')) "[$host]" else host
        val default = (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
        return if (port == -1 || default) shown else "$shown:$port"
    }
}

/// How the page reads a seller's own pages; a test's stand-in answers without a network.
fun interface PublicAppHttp {
    /// GETs [url]. The reply is capped at [limit] bytes: a longer one is `null`. Throws `java.io.IOException` when
    /// no answer came.
    suspend fun get(url: String, limit: Int): PublicAppResponse?
}

data class PublicAppResponse(val status: Int, val body: String)

/// OkHttp's [PublicAppHttp]: no redirects followed (a seller does not get to send the page elsewhere).
class OkHttpPublicApp(
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build(),
) : PublicAppHttp {
    override suspend fun get(url: String, limit: Int): PublicAppResponse? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "text/html, application/json").build()
        client.newCall(request).execute().use { r ->
            val source = r.body?.source()
            val bytes = source?.let { it.request(limit + 1L); it.buffer.snapshot().toByteArray() } ?: ByteArray(0)
            if (bytes.size > limit) null else PublicAppResponse(r.code, String(bytes, Charsets.UTF_8))
        }
    }
}

/// `peel://open?url=<address>`: open a stranger's public strut app (iOS `PublicAppLink`).
data class PublicAppLink(
    /// The address as given: what the page loads.
    val url: String,
    /// The strut server it is on ([Seller.base] of the address).
    val seller: String,
    /// `?app=` of the address, when it names one.
    val app: String?,
    /// `scheme://host[:port]`, lower-case: the only origin the page may answer to and stay on.
    val origin: String,
    val host: String,
) {
    /// The app's name: `?app=`; else `<meta name="strut-serve">` of the seller's home page; else the seller's
    /// only public app. Throws [IOException] when none of those says (`<host> does not say which app to open.`).
    suspend fun appName(http: PublicAppHttp = OkHttpPublicApp()): String {
        app?.let { return it }
        runCatching { http.get("$seller/", PAGE_LIMIT) }.getOrNull()?.takeIf { it.status == 200 }?.let { page ->
            serveName(page.body)?.let { return it }
        }
        runCatching { http.get("$seller/public/apps", PAGE_LIMIT) }.getOrNull()?.takeIf { it.status == 200 }?.let { reply ->
            onlyApp(reply.body)?.let { return it }
        }
        throw IOException("$host does not say which app to open.")
    }

    companion object {
        const val PAGE_LIMIT = 1_000_000
        private val SERVE = Regex("""<meta\s+name="strut-serve"\s+content="([^"<>]{1,120})"\s*/?>""")
        private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*(/[A-Za-z0-9][A-Za-z0-9_.-]*)*")
        private val VERSION_SEGMENT = Regex("v[0-9]+")

        /// A name strut gives an app (iOS `AgentArtifact.Handle.isAppName`): at most 120 characters, `/`-separated
        /// segments of letters, digits, `.`, `_` and `-` that start with a letter or digit, and no segment that is
        /// `data.db` or a version (`v2`). Not [WorkspaceApp.isAppName], which differs.
        fun isAppName(name: String): Boolean =
            name.length <= 120 && NAME.matches(name) &&
                name.split('/').none { it == "data.db" || VERSION_SEGMENT.matches(it) }

        /// The name a seller's home page says it serves.
        fun serveName(html: String): String? = SERVE.find(html)?.groupValues?.get(1)?.takeIf(::isAppName)

        /// The name in `GET /public/apps` when it lists exactly one app.
        fun onlyApp(json: String): String? {
            val array = runCatching { JSONArray(json) }.getOrNull() ?: return null
            if (array.length() != 1) return null
            val name = (array.opt(0) as? JSONObject)?.opt("name") as? String ?: return null
            return name.takeIf(::isAppName)
        }

        /// Reads a link strictly; null for anything else.
        fun parse(text: String): PublicAppLink? {
            val link = runCatching { URI(text) }.getOrNull() ?: return null
            if (link.scheme?.lowercase() != "peel" || link.host?.lowercase() != "open") return null
            if (link.rawUserInfo != null) return null
            if (!link.rawPath.isNullOrEmpty() && link.rawPath != "/") return null
            if (!link.rawFragment.isNullOrEmpty()) return null
            val items = queryItems(link.rawQuery) ?: return null
            if (items.size != 1 || items[0].first != "url") return null
            return of(items[0].second)
        }

        /// The link for an address: https (http on loopback), one `app` at most, no fragment.
        fun of(address: String): PublicAppLink? {
            val uri = runCatching { URI(address) }.getOrNull() ?: return null
            if (!uri.rawFragment.isNullOrEmpty()) return null
            if (uri.rawAuthority == null || uri.host == null) return null
            val apps = (queryItems(uri.rawQuery) ?: return null).filter { it.first == "app" }
            if (apps.size > 1) return null
            val app = apps.firstOrNull()?.second
            if (app != null && !isAppName(app)) return null
            val seller = Seller.base(address.substringBefore('#').substringBefore('?')) ?: return null
            val origin = Seller.origin(address) ?: return null
            val host = Seller.host(address) ?: return null
            return PublicAppLink(address, seller, app, origin, host)
        }

        /// A raw query as (name, value) pairs, percent-decoded (`+` stays a plus). Null when malformed.
        private fun queryItems(raw: String?): List<Pair<String, String>>? {
            if (raw.isNullOrEmpty()) return emptyList()
            return try {
                raw.split('&').map { part ->
                    val name = part.substringBefore('=')
                    val value = if (part.contains('=')) part.substringAfter('=') else ""
                    decode(name) to decode(value)
                }
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        private fun decode(s: String): String = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    }
}
