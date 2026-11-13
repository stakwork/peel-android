package com.sphinx.peel.engine

import org.json.JSONArray
import org.json.JSONObject

// What an agent's message points to (iOS `AgentArtifact`, `AgentArtifact.Handle`, `Message.agentArtifacts`/`strutArtifacts` in
// `PeelCore/ControlEnvelope.swift`; `AgentArtifact(strutEntry:workspace:)` in `CallChat.swift`): shown by its title; opened by an
// https link, or on an org's own strut by its handle. Pure; JVM-tested (`AgentArtifactTest`).
//
// Not ported: `AgentJob` and `AgentToolLine` (the other `metadata.peel` extras of an agent's reply), the control envelope itself
// (`metadata.peel.op`), and `strut.proposals`.

/// A deliverable on a strut, as strut's UI shows one alone (its `?artifact=` and `?app=`, strut's specs/API.md §14.3). Only
/// these shapes: the words are a message's, and the page opened from them is loaded with strut's key.
sealed class AgentHandle {
    /// A file strut serves: `/jobs/<job>/files/<file>` or `/artifacts/<run>/<file>`, behind at most three `/peers/<id>` (an org's,
    /// through strut's read-through: the org, its peer and that peer's `via`), each segment percent-encoded.
    data class File(val path: String) : AgentHandle()

    /// An app: `<name>`, or `@<id>/<name>` for an org's, with `@vN` after it to show that version rather than the live one.
    data class App(val app: String) : AgentHandle()

    /// The org it lives on, which the person reads as `@<id>`: the first hop; null for one of this strut's own.
    val peer: String?
        get() = when (this) {
            is File -> path.split("/").let { parts -> if (parts.size > 2 && parts[1] == "peers") parts[2] else null }
            is App -> StrutAppHandle(app).peer
        }

    /// An app's name on its strut and the version it names, if any (`@acme/board@v3` is `board`, `v3`); null for a file.
    val appNameAndVersion: Pair<String, String?>?
        get() = (this as? App)?.let { StrutAppHandle(it.app).nameAndVersion }

    /// Where this opens on its org's own strut. A handle names a deliverable as the brain's strut reads it through
    /// (`/peers/<org>/…`, `@<org>/<name>`), so that first hop comes off; a second one stays, for the org's own read-through.
    /// Null for one of the brain's own strut, which only the brain can reach.
    val onOrg: OrgPage?
        get() {
            val peer = peer ?: return null
            return when (this) {
                // What follows `/peers/<org>`, starting with its `/`.
                is File -> OrgPage(peer, "artifact", path.drop("/peers/".length + peer.length))
                // What follows `@<org>/`.
                is App -> OrgPage(peer, "app", app.drop(peer.length + 2))
            }
        }

    companion object {
        /// A URL path's characters (RFC 3986's `pchar` and `/`): every other one is percent-encoded by whoever made the path.
        private const val PATH_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,;=:@%/"
        private val ID_SEGMENT = "[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?"
        private val ID = Regex("^$ID_SEGMENT(/$ID_SEGMENT)*$")

        fun isPeerId(id: String) = StrutAppHandle.isPeerId(id)
        fun isAppName(name: String) = StrutAppHandle.isAppName(name)

        /// strut's job and run ids: `/`-separated segments of letters, digits, `.`, `_` and `-`, a letter or digit at each end, at most 120.
        fun isId(id: String) = id.length <= 120 && ID.matches(id)

        /// A file's handle, if `path` is exactly one of those shapes: a path's characters only, no `.` or `..` segment however it is
        /// written, a run id with no `/` (only a job's holds one), and no escaped `/`, `\` or control character in the file's own segments.
        fun file(path: String): File? {
            if (path.toByteArray(Charsets.UTF_8).size > 512 || !path.startsWith("/") || !path.all { it in PATH_CHARACTERS }) return null
            var segments = path.drop(1).split("/")
            var hops = 0
            while (segments.firstOrNull() == "peers") {
                if (hops >= 3 || segments.size <= 1 || !isPeerId(segments[1])) return null
                segments = segments.drop(2)
                hops++
            }
            val file = when (segments.firstOrNull()) {
                "jobs" -> if (segments.size > 3 && segments[2] == "files") segments.drop(3) else return null
                "artifacts" -> if (segments.size > 2) segments.drop(2) else return null
                else -> return null
            }
            // The job or run id; a job's may hold an escaped `/` (`peer%2Fj-1`).
            val id = segments.getOrNull(1)?.let { percentDecode(it) } ?: return null
            if (!isId(id) || !(segments[0] == "jobs" || !id.contains('/'))) return null
            for (segment in file) {
                val plain = percentDecode(segment) ?: return null
                if (plain.isEmpty() || plain == "." || plain == ".." || plain.contains('/') || plain.contains('\\') ||
                    plain.any { Character.getType(it) == Character.CONTROL.toInt() }
                ) return null
            }
            return File(path)
        }

        /// An app's handle, if `app` is a strut app name, behind `@<id>/` for an org's, and pinned to a version by `@vN` after it
        /// (strut's solo page shows that version, specs/API.md §14.3).
        fun app(app: String): App? = StrutAppHandle.of(app)?.let { App(it.app) }
    }
}

/// Something an agent's message points to, as strut's reply shape gives it (`{ id, title, kind?, label?, summary?, url? }`) and the
/// bridge adds to (`path` or `app`, stakwork/sphinx#391): shown by its title; opened by an https link, or on an org's own strut by
/// its handle.
data class AgentArtifact(
    val id: String,
    val title: String,
    /// What it is to the reader: "Plan", "Page", "Screenshot".
    val label: String? = null,
    val summary: String? = null,
    /// An https link with a host and no user name in it, if it has one; anything else is dropped (`https://bank.com@evil.example`
    /// reads as the bank).
    val url: String? = null,
    /// Where an org's own strut shows it, if the bridge named a place strut would take; anything else is dropped.
    val handle: AgentHandle? = null,
) {
    /// Whether opening it asks first: an app sent in a tribe, where anyone could have sent it, and which runs with the reader's own
    /// access to its workspace (docs/hive-tribes.md, "Deliverables").
    fun asksBeforeOpening(inTribe: Boolean): Boolean = inTribe && handle is AgentHandle.App

    /// What that asks: the app and version the link really opens (its title is the sender's words), and the workspace as the person reads it.
    fun tribeAppQuestion(workspace: String): String {
        val app = handle?.appNameAndVersion?.let { (name, version) -> "\u201c$name\u201d" + (version?.let { " $it" } ?: "") } ?: "this app"
        return "Open the app $app from $workspace? Anyone in the tribe could have sent this link."
    }

    companion object {
        /// The most read from one message.
        const val MOST = 20

        /// The longest title, label, summary or id kept (iOS `AgentToolLine.longest`; cut in characters here, iOS cuts in graphemes).
        const val LONGEST = 200

        /// One entry as an agent's message carries it (`{title, id?, label?, summary?, url?, path? | app?}`); null for one with no title.
        fun parse(value: Any?): AgentArtifact? {
            val fields = value as? JSONObject ?: return null
            val title = (fields.opt("title") as? String)?.takeIf { it.isNotEmpty() } ?: return null
            fun text(key: String) = (fields.opt(key) as? String)?.takeIf { it.isNotEmpty() }?.take(LONGEST)
            val handle = when (val path = fields.opt("path")) {
                is String -> AgentHandle.file(path)
                else -> (fields.opt("app") as? String)?.let { AgentHandle.app(it) }
            }
            return AgentArtifact(text("id") ?: title, title.take(LONGEST), text("label"), text("summary"), https(fields.opt("url") as? String), handle)
        }

        /// An https link with a host and no user name or password.
        fun https(link: String?): String? {
            val parts = UrlParts.parse(link ?: return null) ?: return null
            return link.takeIf { parts.scheme == "https" && parts.host != null && parts.authority?.contains('@') != true }
        }

        /// One of strut's own entries (`GET …/runs/:runId/artifacts`, strut's specs/CALLBACKS.md §4) as a call's chat carries it, beside
        /// the workspace whose strut answered: its handle is resolved against that workspace, as strut's UI resolves a peer's (`linkOf`,
        /// `peerArtifactPath`). Null for an entry with no title.
        fun strutEntry(value: Any?, workspace: String): AgentArtifact? {
            val fields = value as? JSONObject ?: return null
            val read = parse(value) ?: return null
            return read.copy(handle = if (AgentHandle.isPeerId(workspace)) handle(fields, workspace) else null)
        }

        /// Where an org's own strut shows the entry, through `/peers/<workspace>`: an app by name (and version), or by its document
        /// link; a peer's file by its peer, `via` and job; a strut-relative url behind the workspace. Null for a peer's app, two hops
        /// off, which strut's `?app=` cannot name.
        private fun handle(fields: JSONObject, workspace: String): AgentHandle? {
            fun text(key: String) = (fields.opt(key) as? String)?.takeIf { it.isNotEmpty() }
            (fields.opt("app") as? JSONObject)?.let { app ->
                if (text("peer") != null) return null
                val name = app.opt("name") as? String ?: return null
                val pinned = (app.opt("version") as? String)?.takeIf { it.isNotEmpty() }?.let { "@$it" } ?: ""
                return AgentHandle.app("@$workspace/$name$pinned")
            }
            text("peer")?.let { peer ->
                val job = text("job") ?: return null
                val path = text("path") ?: return null
                val hops = listOf(workspace, peer) + listOfNotNull(text("via"))
                if (!hops.all(AgentHandle::isPeerId)) return null
                val file = path.split("/").filter { it.isNotEmpty() }.joinToString("/") { encodeComponent(it) }
                return AgentHandle.file(hops.joinToString("") { "/peers/$it" } + "/jobs/${encodeComponent(job)}/files/" + file)
            }
            text("url")?.let { url ->
                if (url.startsWith("/") && !url.startsWith("//")) {
                    appOfDocument(url)?.let { return AgentHandle.app("@$workspace/$it") }
                    return AgentHandle.file("/peers/$workspace$url")
                }
            }
            return null
        }

        private val DOCUMENT = Regex("^/apps/(.+?)(?:/(v\\d{1,6}))?/files/dist/index\\.html$")

        /// An app's document link (`/apps/<name>/<vN>/files/dist/index.html`, strut's `appDocumentUrl`) as the app, `name[@vN]`, as Hive
        /// reads it (`workspaceAppOfKey`): an entry may name an app by it, with no `app`.
        private fun appOfDocument(url: String): String? {
            val match = DOCUMENT.find(url) ?: return null
            val segments = match.groupValues[1].split("/").map { percentDecode(it) }
            if (segments.any { it == null }) return null
            val name = segments.filterNotNull().joinToString("/")
            return match.groups[2]?.value?.let { "$name@$it" } ?: name
        }

        /// `value` as one path segment, as `encodeURIComponent` writes it.
        private fun encodeComponent(value: String): String {
            val out = StringBuilder()
            for (b in value.toByteArray(Charsets.UTF_8)) {
                val c = (b.toInt() and 0xff).toChar()
                if (b >= 0 && (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-_.!~*'()")) out.append(c)
                else out.append('%').append("0123456789ABCDEF"[(b.toInt() shr 4) and 0xf]).append("0123456789ABCDEF"[b.toInt() and 0xf])
            }
            return out.toString()
        }
    }
}

/// A text message's `type` (iOS `MessageType.text`): only text carries an agent's extras.
const val TEXT_MESSAGE_TYPE = 0

/// What a text message's body (`{"content": …, "metadata": "<json text>"}`) says on the agent's device channel
/// (`Message.agentArtifacts`, `Message.strutArtifacts`). Whoever sent the message wrote them: shown as plain words, never acted on.
object AgentMessages {
    /// The longest metadata read from a message.
    const val MAX_METADATA = 64 * 1024

    /// The `metadata` of `body`, an object, if it has one no longer than [MAX_METADATA].
    internal fun metadata(body: String?): JSONObject? {
        val text = runCatching { JSONObject(body ?: return null).opt("metadata") as? String }.getOrNull() ?: return null
        if (text.toByteArray(Charsets.UTF_8).size > MAX_METADATA) return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    /// What an agent's message points to (`metadata.peel.artifacts`): a job's deliverables, a page, a link. `metadata.peel` with an
    /// `op` is a control message's, which has none. Empty for anything else.
    fun artifacts(body: String?): List<AgentArtifact> {
        if (body == null || !(body.contains("artifacts") || body.contains("\\u"))) return emptyList()
        val peel = metadata(body)?.opt("peel") as? JSONObject ?: return emptyList()
        if (peel.has("op")) return emptyList()
        val entries = peel.opt("artifacts") as? JSONArray ?: return emptyList()
        return (0 until minOf(entries.length(), AgentArtifact.MOST)).mapNotNull { AgentArtifact.parse(entries.opt(it)) }
    }

    /// What a message points to from strut's own entries in its metadata, `metadata.strut` (`{ workspace, artifacts }`): Jamie's replies
    /// in a tribe carry them (docs/hive-tribes.md), each resolved against that workspace. Whoever sent the message wrote them, and in a
    /// tribe that is anyone: an entry opens only against the reader's own pairing with that workspace, never by itself.
    fun strutArtifacts(body: String?): List<AgentArtifact> {
        if (body == null || !(body.contains("strut") || body.contains("\\u"))) return emptyList()
        return strutEntries(metadata(body)?.opt("strut"))
    }

    /// strut's entries in `strut` (`{workspace, artifacts}`), each resolved against its workspace (iOS `CallChatWire.artifacts`).
    fun strutEntries(strut: Any?): List<AgentArtifact> {
        val fields = strut as? JSONObject ?: return emptyList()
        val workspace = fields.opt("workspace") as? String ?: return emptyList()
        val entries = fields.opt("artifacts") as? JSONArray ?: return emptyList()
        return (0 until minOf(entries.length(), AgentArtifact.MOST)).mapNotNull { AgentArtifact.strutEntry(entries.opt(it), workspace) }
    }
}
