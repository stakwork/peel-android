package com.sphinx.peel.engine

import java.net.URLEncoder
import java.text.Collator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/// A Stadeum workspace the person paired: the iOS `HivePeer`, trimmed to what
/// Apps needs. [baseUrl] is the workspace's own strut.
data class HivePeer(val id: String, val baseUrl: String, val token: String, val label: String?) {
    /// What the person reads: its label, or its id.
    val name: String get() = WorkspaceApp.clean(label ?: "", 64).ifEmpty { WorkspaceApp.clean(id, 64) }

    override fun toString() = "HivePeer(id=$id, baseUrl=$baseUrl, token=<redacted>)"
}

/// An app a Stadeum workspace pinned as a tab. Ported from PeelCore's
/// `WorkspaceApps.swift`; not ported: the icon table, `waiting`/own-strut apps
/// (the Mac's), and `AgentArtifact.Handle.isAppName` (approximated by [isAppName]).
data class WorkspaceApp(
    val peer: String?,
    val name: String,
    val title: String,
    val about: String? = null,
    val icon: String? = null,
    val version: String? = null,
    val release: String? = null,
    val problems: Int = 0,
) {
    /// strut's key for it: `@<peer>/<name>`.
    val id: String get() = peer?.let { "@$it/$name" } ?: name

    /// Where it comes from as the person reads it: `@workspace`, or Yours.
    val source: String get() = peer?.let { "@$it" } ?: YOURS

    /// Under the title: where it comes from (with several workspaces) and its version.
    fun caption(showsSource: Boolean): String? =
        listOfNotNull(if (showsSource) source else null, version).takeIf { it.isNotEmpty() }?.joinToString(" · ")

    /// Whether a search finds it: title, what it is for, or where it comes from.
    fun matches(query: String): Boolean =
        listOf(title, about ?: "", source).any { it.contains(query.trim(), ignoreCase = true) }

    /// Hive's page for the app, `/w/<peer>/apps/<name>`; no token, grants
    /// nothing. Nil for an app with no workspace.
    fun shareLink(hive: String = HIVE_PRODUCTION): String? {
        val peer = peer ?: return null
        if (name.isEmpty()) return null
        val path = name.split("/").joinToString("/") { segment(it) }
        return "${hive.trimEnd('/')}/w/${segment(peer)}/apps/$path"
    }

    companion object {
        const val YOURS = "Yours"
        /// TODO: confirm against `HiveClient.production` (Hive.swift).
        const val HIVE_PRODUCTION = "https://hive.sphinx.chat"
        private const val MAX_TITLE = 64
        private const val MAX_ABOUT = 400

        private fun segment(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /// Single-lined, trimmed and capped (iOS `TextLine.clean`).
        internal fun clean(text: String, max: Int): String =
            text.map { if (it.isISOControl() || it.isWhitespace()) ' ' else it }.joinToString("")
                .replace(Regex(" {2,}"), " ").trim().take(max).trim()

        /// A name strut would give an app: `/`-separated segments of
        /// letters, digits, `.`, `_`, `-`, none empty or starting with a dot.
        fun isAppName(name: String): Boolean =
            name.length <= 128 && name.split("/").all { it.isNotEmpty() && !it.startsWith(".") && it.all { c -> c.isLetterOrDigit() && c.code < 128 || c in "._-" } }

        /// One entry of strut's `GET /apps`; null when not pinned as a tab or
        /// with a name strut would not have given it.
        fun parse(peer: String, json: JSONObject): WorkspaceApp? {
            if (json.opt("pinned") != true) return null
            val name = json.optString("name", "").takeIf { it.isNotEmpty() && isAppName(it) } ?: return null
            fun text(key: String) = clean(json.optString(key, ""), 64).ifEmpty { null }
            return WorkspaceApp(
                peer = peer,
                name = name,
                title = clean(json.optString("title", ""), MAX_TITLE).ifEmpty { name },
                about = clean(json.optString("description", ""), MAX_ABOUT).ifEmpty { null },
                icon = text("icon"),
                version = text("activeVersion"),
                release = text("live"),
                problems = (json.opt("warnings") as? Int)?.coerceAtLeast(0) ?: 0,
            )
        }

        /// The apps in strut's `{ apps: [...] }` answer, in its order.
        fun listing(answer: String, peer: String): List<WorkspaceApp> {
            val apps = JSONObject(answer).optJSONArray("apps") ?: return emptyList()
            return (0 until apps.length()).mapNotNull { apps.optJSONObject(it)?.let { e -> parse(peer, e) } }
        }

        /// A–Z by title (natural number order, like `localizedStandardCompare`);
        /// one title in two places always the same way round.
        fun byTitle(apps: List<WorkspaceApp>): List<WorkspaceApp> {
            val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
            return apps.sortedWith { a, b -> compareStandard(collator, a.title, b.title).takeIf { it != 0 } ?: a.id.compareTo(b.id) }
        }

        private val digits = Regex("(\\d+)")

        private fun compareStandard(collator: Collator, a: String, b: String): Int {
            val pa = digits.split(a).zip(digits.findAll(a).map { it.value }.toList() + "")
            val pb = digits.split(b).zip(digits.findAll(b).map { it.value }.toList() + "")
            for (i in 0 until minOf(pa.size, pb.size)) {
                collator.compare(pa[i].first, pb[i].first).let { if (it != 0) return it }
                val na = pa[i].second.toBigIntegerOrNull()
                val nb = pb[i].second.toBigIntegerOrNull()
                if (na != null && nb != null) na.compareTo(nb).let { if (it != 0) return it }
            }
            return pa.size.compareTo(pb.size)
        }

        /// The sources `apps` come from: Yours first when any, then `@peer` A–Z.
        fun sources(apps: List<WorkspaceApp>): List<String> =
            (if (apps.any { it.peer == null }) listOf(YOURS) else emptyList()) +
                apps.mapNotNull { it.peer }.toSortedSet().map { "@$it" }

        /// `apps` from `source` (all when null) that `query` finds, in order.
        fun filter(apps: List<WorkspaceApp>, query: String, source: String?): List<WorkspaceApp> =
            apps.filter { (source == null || it.source == source) && it.matches(query) }

        /// The apps `peer` pinned, read on its own strut with the person's
        /// token: `GET {strut}/apps`, bearer auth, only `pinned == true`.
        suspend fun pinned(peer: HivePeer, client: OkHttpClient = OkHttpClient()): List<WorkspaceApp> =
            withContext(Dispatchers.IO) {
                val url = peer.baseUrl.trimEnd('/') + "/apps"
                val request = Request.Builder().url(url).header("Authorization", "Bearer ${peer.token}").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw java.io.IOException("${peer.name} did not list its apps (HTTP ${response.code}).")
                    val body = response.body?.source()?.let { it.request(MAX_BODY + 1L); it.buffer.snapshot().utf8() } ?: ""
                    if (body.length > MAX_BODY) throw java.io.IOException("${peer.name}'s app list is too large.")
                    listing(body, peer.id)
                }
            }

        private const val MAX_BODY = 2 * 1024 * 1024
    }
}
