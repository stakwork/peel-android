package com.sphinx.peel.engine

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/// The pure model of an org page: strut's UI showing one thing alone on a workspace's own strut, opened with the person's
/// token (iOS `PeelCore/OrgPage.swift`: `StrutUI`, `StrutAppLink`, `OrgPage`; `PeelUI/Stadeum.swift`: `OrgPageRequest`).
/// Nothing here touches Android, so all of it is JVM-tested (`OrgPageTest`).
///
/// [StrutAppHandle] is the app shape of `AgentArtifact.Handle`, which is all [StrutAppLink] needs; the whole handle (files too,
/// `onOrg`) is [AgentHandle], and `OrgPage.hiveApp` is here too (`docs/phases/30-open-paths.md`).

/// A URL taken apart as RFC 3986 does, without `java.net.URI` (which refuses hosts with an underscore and throws on odd
/// input) or `android.net.Uri` (not on a plain JVM). Query and path stay percent-encoded exactly as written.
internal class UrlParts private constructor(
    val scheme: String,
    val authority: String?,
    val path: String,
    val query: String?,
    val fragment: String?,
) {
    /// The host as written in the address, lower case; an IPv6 literal keeps its brackets (as `location.host` does).
    val host: String?
    /// The explicit port, if any.
    val port: Int?
    /// A port that is not a number: no address.
    private val badPort: Boolean

    init {
        val hostPort = authority?.substringAfterLast('@')
        val portText: String
        if (hostPort == null) {
            host = null
            portText = ""
        } else if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            host = if (end > 0) hostPort.substring(0, end + 1).lowercase() else null
            portText = if (end > 0) hostPort.substring(end + 1).removePrefix(":") else ""
        } else {
            host = hostPort.substringBefore(':').lowercase().ifEmpty { null }
            portText = hostPort.substringAfter(':', "")
        }
        port = portText.toIntOrNull()
        badPort = portText.isNotEmpty() && port == null
    }

    companion object {
        private val SHAPE = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):(?://([^/?#]*))?([^?#]*)(?:\\?([^#]*))?(?:#(.*))?$", RegexOption.DOT_MATCHES_ALL)

        fun parse(text: String): UrlParts? {
            if (text.any { it.code <= 0x20 || it.code == 0x7f }) return null
            val g = SHAPE.matchEntire(text)?.groups ?: return null
            val parts = UrlParts(g[1]!!.value, g[2]?.value, g[3]!!.value, g[4]?.value, g[5]?.value)
            return if (parts.badPort) null else parts
        }

        /// `url`'s origin as a page reads it (`location.origin`): the scheme and host in lower case, and a port only when
        /// not the scheme's own. Null without a scheme and a host.
        fun origin(url: String): String? {
            val p = parse(url) ?: return null
            val host = p.host ?: return null
            val scheme = p.scheme.lowercase()
            val port = p.port?.takeUnless { (it == 443 && scheme == "https") || (it == 80 && scheme == "http") }
            return "$scheme://$host" + (port?.let { ":$it" } ?: "")
        }
    }
}

/// strut's UI showing one thing alone (`&solo=1`, strut's specs/API.md §14.3): a file through its viewer (`?artifact=`) or
/// an app's page (`?app=`), at the UI's own address on a strut: `/`, or a host's mount such as a swarm's `/lab/`.
object StrutUI {
    /// The page at `base` showing `value` as `name` (`artifact` or `app`) alone; with `key`, the strut's key handed over as
    /// `?key=`; with `input`, an app opened at that view (strut's plans/apps.md §7.1, "Deep links"). Null for a base that is
    /// not a URL with a host (iOS also takes one without; nothing here ever builds from one).
    fun page(base: String, name: String, value: String, key: String? = null, input: String? = null): String? {
        val parts = UrlParts.parse(base) ?: return null
        val authority = parts.authority?.takeIf { it.isNotEmpty() } ?: return null
        // The UI's folder with its `/`: without it the host redirects, and a web view does not carry a request's credential
        // across a redirect.
        val path = if (parts.path.endsWith("/")) parts.path else parts.path + "/"
        val query = (key?.let { "key=${encoded(it)}&" } ?: "") + "$name=${encoded(value)}" +
            (input?.let { "&input=${encoded(it)}" } ?: "") + "&solo=1"
        return "${parts.scheme}://$authority$path?$query"
    }

    /// `page`, the same page at the view `input` (null: the app's first), everything else in its address kept as it is.
    /// (An address left with no query at all has none, where Foundation would leave an empty `?`.)
    fun page(page: String, input: String?): String {
        val parts = UrlParts.parse(page) ?: return page
        val items = (parts.query ?: "").split("&").filter { it.isNotEmpty() && !it.startsWith("input=") }.toMutableList()
        if (input != null) {
            // Beside the app it opens, where `page(base,…)` puts it.
            val at = items.indexOfFirst { it.startsWith("app=") }.let { if (it < 0) items.size else it + 1 }
            items.add(at, "input=${encoded(input)}")
        }
        return parts.scheme + ":" + (parts.authority?.let { "//$it" } ?: "") + parts.path +
            (if (items.isEmpty()) "" else "?" + items.joinToString("&")) + (parts.fragment?.let { "#$it" } ?: "")
    }

    /// Whether `url` is strut's UI itself, in its `folder` (the path it is served at, with its `/`), rather than a file the
    /// strut serves.
    fun isPage(url: String, folder: String): Boolean {
        val path = UrlParts.parse(url)?.path ?: return false
        return (if (path.endsWith("/")) path else "$path/") == folder
    }

    /// Only unreserved characters left as they are: `+` would read as a space to strut's UI (`URLSearchParams`).
    internal fun encoded(value: String): String {
        val out = StringBuilder()
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            if (b >= 0 && (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~")) out.append(c)
            else out.append('%').append("0123456789ABCDEF"[(b.toInt() shr 4) and 0xf]).append("0123456789ABCDEF"[b.toInt() and 0xf])
        }
        return out.toString()
    }
}

/// An app as a deliverable names one (`AgentArtifact.Handle.app`): `<name>`, or `@<id>/<name>` for an org's, perhaps with
/// `@vN` after it. Only the app shape of iOS's `Handle` is ported.
data class StrutAppHandle(val app: String) {
    /// The org it lives on (`@<id>/…`), the first hop; null for one of this strut's own.
    val peer: String?
        get() {
            if (!app.startsWith("@")) return null
            val slash = app.indexOf('/')
            return if (slash < 0) null else app.substring(1, slash)
        }

    /// The app's name on its strut and the version it names, if any (`@acme/board@v3` is `board`, `v3`).
    val nameAndVersion: Pair<String, String?>
        get() {
            var name = app
            if (app.startsWith("@")) app.indexOf('/').let { if (it >= 0) name = app.substring(it + 1) }
            val at = name.lastIndexOf('@')
            return if (at < 0) name to null else name.substring(0, at) to name.substring(at + 1)
        }

    companion object {
        private val PEER_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        private val SEGMENT = "[A-Za-z0-9][A-Za-z0-9_.-]*"
        private val APP_NAME = Regex("^$SEGMENT(/$SEGMENT)*$")
        private val VERSION = Regex("^v[0-9]+$")

        /// strut's peer ids: letters, digits, `.`, `_` and `-`, a letter or digit first, at most 64.
        fun isPeerId(id: String) = PEER_ID.matches(id)

        /// strut's app names (`validateAppName`): `/`-separated segments of letters, digits, `_`, `.` and `-`, each starting
        /// with a letter or digit; never `@`, which pins a version, and no segment that reads as a version (`v2`) or is the
        /// app's database (`data.db`).
        fun isAppName(name: String): Boolean =
            name.length <= 120 && APP_NAME.matches(name) && name.split("/").none { it == "data.db" || VERSION.matches(it) }

        /// An app's handle, if `app` is a strut app name, behind `@<id>/` for an org's, and pinned to a version by `@vN`.
        fun of(app: String): StrutAppHandle? {
            var name = app
            if (app.startsWith("@")) {
                val slash = app.indexOf('/')
                if (slash < 0 || !isPeerId(app.substring(1, slash))) return null
                name = app.substring(slash + 1)
            }
            val at = name.lastIndexOf('@')
            if (at >= 0) {
                if (!VERSION.matches(name.substring(at + 1))) return null
                name = name.substring(0, at)
            }
            return if (isAppName(name)) StrutAppHandle(app) else null
        }
    }
}

/// A link to an app that a page in strut's UI opens (strut's plans/apps.md §7.1, "Deep links"; the kit's `appLink`):
/// `?app=<key>&input=<the app's own query>` on that UI's address. strut's page alone follows one to itself in place, and
/// hands one to another app to its host, which is Peel. The input is the app's, carried whole and never read here.
data class StrutAppLink(val app: StrutAppHandle, val input: String?) {
    /// Where the page that links lives, which says what its names mean: a page names apps where it lives (strut's
    /// `peerAppLink`), and an app on an org's own peer is that org's to open, never Peel's.
    sealed interface Source {
        /// An app of Peel's own strut: a name as written (`@<id>/<name>` for an org's, through the read-through).
        data object Own : Source
        /// An org's app read through Peel's strut: a bare name is that org's; one of its own peers' is no link.
        data class Peer(val id: String) : Source
        /// An app on the org's own strut (the phone): its own apps only.
        data object Org : Source
    }

    companion object {
        /// The longest input followed, in bytes: a view's address, not a document.
        const val LONGEST_INPUT = 2_048

        /// The app link `url` is: strut's UI in its `folder` naming an app by `?app=`, read from a page of `source`. Null for
        /// a link to anything else (a run, a job, a file), an app name strut would refuse, an input past [LONGEST_INPUT], and
        /// an `app` or `input` given twice or that does not decode: strut and Peel would each read such a link their own way.
        fun parse(url: String, folder: String, from: Source): StrutAppLink? {
            if (!StrutUI.isPage(url, folder)) return null
            val query = UrlParts.parse(url)?.query ?: ""
            var app: String? = null
            var input: String? = null
            for (item in query.split("&").filter { it.isNotEmpty() }) {
                val eq = item.indexOf('=')
                val rawName = if (eq < 0) item else item.substring(0, eq)
                val rawValue = if (eq < 0) "" else item.substring(eq + 1)
                val name = decoded(rawName)
                if (name != "app" && name != "input") continue
                val value = decoded(rawValue)
                if (value == null || (if (name == "app") app else input) != null) return null
                if (name == "app") app = value else input = value
            }
            val key = app?.takeIf { it.isNotEmpty() } ?: return null
            if (input?.isEmpty() == true) input = null
            if ((input?.toByteArray(Charsets.UTF_8)?.size ?: 0) > LONGEST_INPUT) return null
            val named = when (from) {
                Source.Own -> key
                is Source.Peer -> if (key.startsWith("@")) return null else "@${from.id}/$key"
                Source.Org -> if (key.startsWith("@")) return null else key
            }
            val handle = StrutAppHandle.of(named) ?: return null
            return StrutAppLink(handle, input)
        }

        /// As strut's UI reads its address (`URLSearchParams`): `+` is a space; null when a `%` escape is malformed or the
        /// bytes are not UTF-8 (Swift `removingPercentEncoding`).
        internal fun decoded(part: String): String? = percentDecode(part.replace('+', ' '))

    }
}

/// `s` with its `%XX` escapes read as UTF-8 bytes (Swift `removingPercentEncoding`: `+` stays); null when an escape is malformed or
/// the bytes are not UTF-8.
internal fun percentDecode(s: String): String? {
    val bytes = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < s.length) {
        if (s[i] == '%') {
            val hex = s.substring(i + 1, minOf(i + 3, s.length))
            if (hex.length < 2 || !hex.all { it in "0123456789abcdefABCDEF" }) return null
            bytes.write(hex.toInt(16))
            i += 3
        } else {
            val cp = s.codePointAt(i)
            bytes.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
            i += Character.charCount(cp)
        }
    }
    return try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        null
    }
}

/// One thing on a workspace's own strut, by what strut's UI calls it: `artifact` (a file's path) or `app` (a name).
data class OrgPage(val peer: String, val query: String, val value: String) {
    /// The page on the org's strut whose UI is at `base` (its peer record's `baseUrl`).
    fun url(base: String): String? = StrutUI.page(base, query, value)

    companion object {
        /// A workspace's app, by its name on its own strut.
        fun app(name: String, peer: String) = OrgPage(peer, "app", name)

        /// The app a link to Hive's page for it names ([WorkspaceApp.shareLink]): https on Hive's own host and port, no userinfo,
        /// and exactly `/w/<slug>/apps/<name>`, the slug a peer id and the name one strut takes. Its query and fragment are
        /// ignored. Null for anything else (http, another or a lookalike host, an empty, dotted or version segment, an encoded
        /// `/`, trailing junk): the link is only ever a name, never a way in; the person's own pairing decides whether it opens.
        fun hiveApp(url: String, hive: String = WorkspaceApp.HIVE_PRODUCTION): OrgPage? {
            val parts = UrlParts.parse(url) ?: return null
            val host = parts.host ?: return null
            if (host != UrlParts.parse(hive)?.host || parts.scheme.lowercase() != "https" || parts.port != null ||
                parts.authority?.contains('@') == true
            ) return null
            val segments = parts.path.split("/")
            if (segments.size < 5 || segments[0].isNotEmpty() || segments[1] != "w" || segments[3] != "apps") return null
            val decoded = segments.drop(2).map { segment -> percentDecode(segment)?.takeIf { !it.contains('/') } ?: return null }
            val slug = decoded[0]
            val name = decoded.drop(2).joinToString("/")
            if (!StrutAppHandle.isPeerId(slug) || !AgentHandle.isAppName(name)) return null
            return app(name, slug)
        }
    }
}

/// A request the web view makes for a page: the address, and the person's token as its bearer when the page has one (an
/// org's); a stranger's public page is loaded with no credential at all, so its request has no headers. Never described with
/// its token.
class OrgRequest internal constructor(val url: String, private val token: String? = null) {
    /// The headers to load it with: the bearer, which a swarm's gate wants for the page itself; none for a page with no token.
    val headers: Map<String, String> get() = token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

    override fun toString() = "OrgRequest(url=$url, Authorization=${if (token == null) "none" else "<redacted>"})"
}

/// An org's page as a device with no strut opens it (iOS `Stadeum.opening`, `OrgPageRequest`): strut's UI there showing one
/// thing alone, with the person's token for the org. The token is handed over twice and never in an address (strut's
/// specs/DESKTOP.md, "A device with no strut of its own"): as the first request's bearer, which a swarm's gate wants for the
/// page itself, and in the page's own storage before its script runs, where strut's UI keeps the key it sends on every call
/// ([handOver]). Never described with its token. Two requests are the same only when they are the same request (`id`), as on iOS.
class OrgPageRequest(
    /// strut's UI showing it alone; no credential in it.
    override val url: String,
    /// The person's token for the org: never shown, logged or kept.
    private val token: String,
    /// The org's peer id.
    val peer: String,
    /// The org as the person reads it.
    val workspace: String,
    /// The app's or artifact's own title, when it has one: what a page shows before the workspace.
    val title: String? = null,
) : PhonePage {
    val id: UUID = UUID.randomUUID()

    override fun toString() = "org page on $workspace"
    override fun equals(other: Any?) = other is OrgPageRequest && other.id == id
    override fun hashCode() = id.hashCode()

    /// `url`'s origin as a page reads it (`location.origin`).
    override val origin: String get() = UrlParts.origin(url) ?: ""

    /// The folder strut's UI is served from (`/`, or a swarm's `/lab/`): `url`'s path, which [StrutUI.page] ends with its `/`.
    val folder: String get() = UrlParts.parse(url)?.path ?: ""

    /// Whether `other` is strut's: on `url`'s origin, in its UI's folder (a swarm's `/lab/` shares its host with other services).
    override fun serves(other: String): Boolean {
        val there = UrlParts.origin(other) ?: return false
        return there == origin && (UrlParts.parse(other)?.path ?: "").startsWith(folder)
    }

    /// The request that loads it: the token as its bearer.
    override val request: OrgRequest get() = OrgRequest(url, token)

    /// The request that loads it again where the page went (`current`, its web view's address: strut keeps an app's view
    /// there), when that is still strut's UI: as [request], the token its bearer. Null anywhere else (a file opened in place).
    override fun request(at: String?): OrgRequest? =
        if (at != null && serves(at) && StrutUI.isPage(at, folder)) OrgRequest(at, token) else null

    /// The app a link names (strut's page alone hands a link to another app to its host), one of this org's own, when the page
    /// it came from (`current`) shows one of the org's apps: never from a file, whose links an org or a model wrote and which
    /// is no app's. Null for a link to anything else.
    override fun appLink(other: String, from: String?): StrutAppLink? {
        if (from == null || shownApp(from) == null || !serves(other)) return null
        return StrutAppLink.parse(other, folder, StrutAppLink.Source.Org)
    }

    /// The org's app the page at `current` shows, as its address names it; null for a file, or anything not this strut's UI.
    override fun shownApp(current: String): StrutAppHandle? =
        if (serves(current)) StrutAppLink.parse(current, folder, StrutAppLink.Source.Org)?.app else null

    /// The request for `link`'s app alone on this org's strut, at the view it names, loaded as this page is.
    override fun request(link: StrutAppLink): OrgRequest? {
        val there = StrutUI.page(url, "app", link.app.app, input = link.input) ?: return null
        return OrgRequest(there, token)
    }

    /// The script that hands the token to strut's UI (its `strut/apiKey` in `sessionStorage`), run before the page's own and only
    /// in its folder on the org's origin. The strings as JSON literals, no `/` escaped: the literals are a script's, never inside
    /// a tag. As iOS's `handOver`, character for character.
    val handOver: String
        get() {
            val literals = "[" + listOf(origin, folder, token).joinToString(",") { jsonLiteral(it) } + "]"
            return "(() => { const [origin, folder, key] = $literals;\n" +
                "  if (location.origin === origin && location.pathname.startsWith(folder)) {\n" +
                "    try { sessionStorage.setItem(\"strut/apiKey\", key); } catch (e) {} } })();"
        }

    /// [handOver] for a web view's document-start hook, which (unlike WKWebView's `forMainFrameOnly`) offers frames of the
    /// origin too: only the top window runs it.
    val mainFrameHandOver: String get() = "if (window.top === window) {\n$handOver\n}"

    /// What runs before the page's own script: the token's hand-over, main frame only (iOS `script`).
    override val script: String get() = mainFrameHandOver

    override fun isUIPage(other: String): Boolean = StrutUI.isPage(other, folder)

    /// An org's page: the org is what Peel trusts, whichever of its apps strut's UI names (`WalletScope.Org`).
    override val walletScope: WalletScope get() = WalletScope.Org(peer)
    override val walletPlace: String get() = workspace

    /// The title the page shows: the app's or artifact's own, else the workspace. Another app a link opened here is named by
    /// its workspace: its title is not known here (iOS `OrgPageSheet` navigation title).
    override fun title(showingAnotherApp: Boolean): String =
        if (showingAnotherApp) workspace else title?.takeIf { it.isNotEmpty() } ?: workspace

    /// Whether the workspace the page is on is still live: when it is not, the page closes and the token goes (iOS `MainTabs`).
    fun isLive(live: List<HivePeer>): Boolean = live.any { it.id == peer }

    companion object {
        /// How a workspace's app opens: its own strut's UI, alone (`?app=<name>&solo=1`), with the person's token (iOS
        /// `Stadeum.opening`). Null when the app has no live workspace or the base is not a URL.
        fun opening(app: WorkspaceApp, live: List<HivePeer>): OrgPageRequest? =
            (Openings(live, connected = true).opening(app) as? Opening.Page)?.request

        /// A string as a JSON literal as Foundation's `JSONEncoder` writes it with `.withoutEscapingSlashes`.
        internal fun jsonLiteral(s: String): String {
            val out = StringBuilder("\"")
            for (c in s) when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000c' -> out.append("\\f")
                c.code < 0x20 -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
            return out.append('"').toString()
        }
    }
}

/// What a web view of an org page does with a navigation or a new window, as plain rules (iOS `OrgPageSheet.Coordinator.policy`
/// and `createWebViewWith`). Android's callbacks give less than WebKit's (no source frame of a new window), so where they
/// differ the rule is the stricter one: see `docs/phases/28-org-page-host.md`.
object OrgNavigation {
    /// How long after a touch a link counts as one the person tapped (iOS `TouchWebView.personActed`: one second).
    const val TAP_WINDOW_MS = 1_000L

    /// Whether the person touched the page within [TAP_WINDOW_MS] before `now` (a script cannot make a touch).
    fun personActed(lastTouchAt: Long?, now: Long): Boolean = lastTouchAt != null && now - lastTouchAt in 0 until TAP_WINDOW_MS

    /// What to do with a navigation: load it here, and/or hand it to the system browser.
    data class Decision(val load: Boolean, val openOutside: Boolean = false)

    /// The page's navigation policy: anywhere in the org's strut's UI ([OrgPageRequest.serves]); its own frames (`about:`, and a
    /// data or blob frame, never as the whole page); a link elsewhere out to the system, only from the main frame and only on
    /// the person's own tap (`linkActivated`: a gesture, within a second of a touch); and no other scheme.
    fun decide(page: PhonePage, url: String, isMainFrame: Boolean, hasGesture: Boolean, lastTouchAt: Long?, now: Long): Decision {
        val parts = UrlParts.parse(url) ?: return Decision(false)
        if (page.serves(url)) return Decision(true)
        return when (parts.scheme.lowercase()) {
            "about" -> Decision(true)
            "data", "blob" -> Decision(!isMainFrame)
            "http", "https" -> Decision(false, openOutside = isMainFrame && hasGesture && personActed(lastTouchAt, now))
            else -> Decision(false)
        }
    }

    /// What a new window (`target=_blank`, `window.open`) does: never a window.
    sealed interface Window {
        data object Nothing : Window
        /// Load here: another app of the org's as its page alone with the token; a file as it is, with none.
        data class Here(val request: OrgRequest?, val url: String) : Window
        data class Outside(val url: String) : Window
    }

    /// One on the org's strut opens here, Back returning: a link to another app (strut's page alone hands one to its host) as
    /// that app's page alone, with the token, and only on the person's own tap, since the app is the org's; a file (its viewer's
    /// "open in a new tab") as it is; anything else of strut's UI (a run, a job) nowhere. A link elsewhere goes out as a link
    /// does (http or https, the person's tap). `personActed` is the person's own tap: the opener's frame is not known on
    /// Android, so unlike iOS a file also opens only on one.
    fun window(page: PhonePage, url: String, current: String?, personActed: Boolean): Window {
        val parts = UrlParts.parse(url) ?: return Window.Nothing
        if (page.serves(url)) {
            if (!personActed) return Window.Nothing
            if (page.isUIPage(url)) {
                val link = page.appLink(url, current) ?: return Window.Nothing
                val there = page.request(link) ?: return Window.Nothing
                return Window.Here(there, there.url)
            }
            return Window.Here(null, url)
        }
        val web = parts.scheme.lowercase().let { it == "http" || it == "https" }
        return if (personActed && web) Window.Outside(url) else Window.Nothing
    }
}
