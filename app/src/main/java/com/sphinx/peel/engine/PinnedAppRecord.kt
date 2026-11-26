package com.sphinx.peel.engine

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/// A workspace's apps could not be read: the HTTP status it answered with, or null when it never did. Ported from PeelCore's
/// `AppsReadFailure`. An [IOException], so code that only cares that the read failed is unchanged.
class AppsReadException(val status: Int?) : IOException(if (status != null) "HTTP $status" else "no answer") {
    /// The workspace no longer lets this device in, or no longer has the list (401, 403, 404): what was listed for it goes. A
    /// timeout, a 5xx or no network may pass, so what it listed stays.
    val lostAccess: Boolean get() = isLostAccess(status)

    companion object {
        fun isLostAccess(status: Int?): Boolean = status == 401 || status == 403 || status == 404
    }
}

/// What one workspace's read came to (PeelCore's `Result<[WorkspaceApp], AppsReadFailure>`).
sealed class PeerRead {
    data class Answered(val apps: List<WorkspaceApp>) : PeerRead()
    data class Failed(val status: Int?) : PeerRead() {
        val lostAccess: Boolean get() = AppsReadException.isLostAccess(status)
    }

    companion object {
        /// The failure [error] is: a refusal that carried its status, else a transient one.
        fun failed(error: Throwable): Failed = Failed((error as? AppsReadException)?.status)
    }
}

/// What is kept of one pinned workspace app (PeelCore's `PinnedAppRecord`): the one deliberate exception to "the app list is not
/// kept". Only id, name, title, icon and version, in the account's settings ([com.sphinx.peel.storage.Setting.PINNED_APP_RECORDS]);
/// never a manifest, description, token or the whole list. It shows the pinned app at launch before a workspace answers, every read
/// replaces it ([reconcile]), and disconnecting Stadeum, a revoked workspace and forgetting the account wipe it.
data class PinnedAppRecord(
    val id: String,
    val peer: String,
    val name: String,
    val title: String,
    val icon: String? = null,
    val version: String? = null,
) {
    /// The app it stands for, [WorkspaceApp.refreshing] until a read answers for its workspace.
    val app: WorkspaceApp get() = WorkspaceApp(peer = peer, name = name, title = title, icon = icon, version = version, refreshing = true)

    companion object {
        /// The record of [app], or null for one with no workspace.
        fun of(app: WorkspaceApp): PinnedAppRecord? =
            app.peer?.let { PinnedAppRecord(app.id, it, app.name, app.title, app.icon, app.version) }

        /// The records after a pin or unpin: one per id in [pinnedIds], from the listed app when there is one, else the record kept.
        fun following(pinnedIds: List<String>, old: List<PinnedAppRecord>, apps: List<WorkspaceApp>): List<PinnedAppRecord> =
            pinnedIds.distinct().mapNotNull { id ->
                apps.firstOrNull { it.id == id && !it.refreshing }?.let { of(it) } ?: old.firstOrNull { it.id == id }
            }

        /// The records after reads of the workspaces: one that answered replaces its records with its pinned apps (removed ones drop,
        /// renamed and newer ones update); one that lost access (401, 403, 404) loses them; one that did not answer keeps them. Only
        /// apps in [pinnedIds] of workspaces in [live] are kept, in the order pinned.
        fun reconcile(old: List<PinnedAppRecord>, read: Map<String, PeerRead>, pinnedIds: List<String>, live: Set<String>): List<PinnedAppRecord> {
            val kept = LinkedHashMap<String, PinnedAppRecord>()
            for (record in old) if (record.peer in live && record.id in pinnedIds) kept[record.id] = record
            for ((peer, result) in read) {
                if (peer !in live) continue
                when (result) {
                    is PeerRead.Answered -> {
                        kept.values.removeAll { it.peer == peer }
                        for (app in result.apps) if (app.peer == peer && app.id in pinnedIds) of(app)?.let { kept[app.id] = it }
                    }
                    is PeerRead.Failed -> if (result.lostAccess) kept.values.removeAll { it.peer == peer }
                }
            }
            return pinnedIds.distinct().mapNotNull { kept[it] }
        }

        fun encode(records: List<PinnedAppRecord>): String = JSONArray().also { array ->
            for (r in records) array.put(
                JSONObject().put("id", r.id).put("peer", r.peer).put("name", r.name).put("title", r.title)
                    .put("icon", r.icon ?: JSONObject.NULL).put("version", r.version ?: JSONObject.NULL),
            )
        }.toString()

        /// The records in [text]; none when there is none or it does not fit.
        fun decode(text: String?): List<PinnedAppRecord> {
            if (text.isNullOrEmpty()) return emptyList()
            return try {
                val array = JSONArray(text)
                (0 until array.length()).mapNotNull { i ->
                    val o = array.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("id", ""); val peer = o.optString("peer", ""); val name = o.optString("name", "")
                    if (id.isEmpty() || peer.isEmpty() || name.isEmpty()) return@mapNotNull null
                    PinnedAppRecord(
                        id, peer, name, o.optString("title", "").ifEmpty { name },
                        if (o.isNull("icon")) null else o.optString("icon").ifEmpty { null },
                        if (o.isNull("version")) null else o.optString("version").ifEmpty { null },
                    )
                }
            } catch (e: org.json.JSONException) {
                emptyList()
            }
        }
    }
}
