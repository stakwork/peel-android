package com.sphinx.peel.ui.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sphinx.peel.engine.WorkspaceApp
import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import org.json.JSONArray
import org.json.JSONObject

/// The chat list's saved layout: PeelCore's `ChatSections.Layout`, kept under `Setting.SIDEBAR_LAYOUT` as the same JSON
/// (`pinnedPages`, `recentApps`). Fields this port does not set (`tribesOpen`, `combinedView`, `allRecent`, `appsOpen`, `pinnedApps`,
/// ...) are carried through untouched, so a database shared with the Swift apps loses nothing, and a layout saved before a field
/// existed keeps that field's default. Device-local, as in PeelCore (it is not in the preferences file).
///
/// Since Chats became one list (docs/phases/58-chats-one-list.md, the iPhone's 2026-11-27 change), Android no longer pins apps:
/// [pinnedApps] is the pins as saved (by the Mac, or by Android before), read only for Stadeum's launch records (`PinnedAppRecord`,
/// as on the iPhone, where `Layout.pinnedApps` stays for the Mac), never shown as pins and never changed here.
data class SidebarLayout(
    /// The pages pinned above the chats from Chats' More menu, by key ([ChatsPage.key]: mentions, threads), in the order pinned.
    val pinnedPages: List<String> = emptyList(),
    /// The apps opened last, by key (`WorkspaceApp.id`), newest first, at most [RECENT_APPS_KEPT]: Apps shows them as Recent.
    val recentApps: List<String> = emptyList(),
    /// The apps pinned (PeelCore's `pinnedApps`), as saved; read only, see above.
    val pinnedApps: List<String> = emptyList(),
) {
    /// Whether the page [key] is pinned above the chats.
    fun isPagePinned(key: String): Boolean = key in pinnedPages

    /// Pins the page [key] above the chats (last) or takes it off; a repeat changes nothing (`Layout.setPagePinned`).
    fun withPagePinned(key: String, pinned: Boolean): SidebarLayout {
        val pages = pinnedPages.filter { it != key }.toMutableList()
        if (pinned) pages += key
        return if (pages == pinnedPages) this else copy(pinnedPages = pages)
    }

    /// The app with key [id] was opened: first in [recentApps], once, the list kept to [RECENT_APPS_KEPT] (`Layout.noteOpened`).
    fun withOpened(id: String): SidebarLayout {
        if (recentApps.firstOrNull() == id) return this
        return copy(recentApps = (listOf(id) + recentApps.filter { it != id }).take(RECENT_APPS_KEPT))
    }

    /// The apps opened last that are among [apps] (those listed), newest first, at most [limit]: Recent in Apps
    /// (`AppModel.recentApps`).
    fun recent(apps: List<WorkspaceApp>, limit: Int = RECENT_APPS_SHOWN): List<WorkspaceApp> {
        val listed = apps.associateBy { it.id }
        return recentApps.mapNotNull { listed[it] }.take(limit)
    }

    companion object {
        /// How many opened apps [recentApps] keeps (`Layout.recentAppsKept`).
        const val RECENT_APPS_KEPT = 8

        /// How many Recent shows (`AppModel.recentApps(limit: 5)`).
        const val RECENT_APPS_SHOWN = 5

        fun decode(text: String?): SidebarLayout {
            val json = parse(text)
            return SidebarLayout(
                pinnedPages = strings(json, "pinnedPages"),
                recentApps = strings(json, "recentApps").take(RECENT_APPS_KEPT),
                pinnedApps = strings(json, "pinnedApps"),
            )
        }

        /// `text` with this layout's fields set, every other field (`pinnedApps` among them) as it was.
        fun encode(layout: SidebarLayout, into: String?): String =
            parse(into).put("pinnedPages", JSONArray(layout.pinnedPages)).put("recentApps", JSONArray(layout.recentApps)).toString()

        /// The strings of the array [key], once each; anything else there (a number, a null, not an array) is skipped.
        private fun strings(json: JSONObject, key: String): List<String> =
            json.optJSONArray(key)?.let { array -> (0 until array.length()).mapNotNull { array.opt(it) as? String }.distinct() } ?: emptyList()

        private fun parse(text: String?): JSONObject =
            try { if (text.isNullOrEmpty()) JSONObject() else JSONObject(text) } catch (e: org.json.JSONException) { JSONObject() }
    }
}

/// The one holder of the saved layout for the open account, observable, so Chats and Apps see the same layout at once. `change`
/// saves it.
class SidebarLayoutState(private val storage: Storage) {
    var layout: SidebarLayout by mutableStateOf(SidebarLayout.decode(storage.value(Setting.SIDEBAR_LAYOUT))); private set

    fun change(next: SidebarLayout) {
        if (next == layout) return
        layout = next
        storage.apply(Storage.Commit(settings = mapOf(Setting.SIDEBAR_LAYOUT to SidebarLayout.encode(next, storage.value(Setting.SIDEBAR_LAYOUT)))))
    }

    companion object {
        private var held: SidebarLayoutState? = null

        /// The holder for [storage] (a new one when the account changed).
        @Synchronized fun of(storage: Storage): SidebarLayoutState =
            held?.takeIf { it.storage === storage } ?: SidebarLayoutState(storage).also { held = it }
    }
}
