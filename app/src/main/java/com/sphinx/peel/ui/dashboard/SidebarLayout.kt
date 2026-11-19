package com.sphinx.peel.ui.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sphinx.peel.engine.WorkspaceApp
import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import org.json.JSONArray
import org.json.JSONObject

/// The chat list's saved layout: PeelCore's `ChatSections.Layout`, kept under `Setting.SIDEBAR_LAYOUT`
/// as the same JSON (`combinedView`, `allRecent`, `pinnedApps`, `appsOpen`). Fields this port does not use (`tribesOpen`, ...)
/// are carried through untouched, so a database shared with the Swift apps loses nothing.
/// Device-local, as in PeelCore (it is not in the preferences file).
///
/// `pinnedApps` are the apps pinned under the Apps row, by key (`WorkspaceApp.id`, `@<workspace>/<name>`), in the order pinned;
/// `appsOpen` is the fold over them (docs/phases/36-apps-cosmetics.md, "Pinned apps").
data class SidebarLayout(
    val combinedView: Boolean = false,
    val allRecent: Boolean = false,
    val pinnedApps: List<String> = emptyList(),
    val appsOpen: Boolean = true,
) {
    /// Whether the app with key [id] is pinned.
    fun isPinned(id: String): Boolean = id in pinnedApps

    /// Pins the app with key [id] (last, in the order pinned) or takes it off. The first pin opens a closed fold, which was no
    /// longer there to open while nothing was pinned (`Layout.setPinned`).
    fun withPinned(id: String, pinned: Boolean): SidebarLayout {
        val pins = pinnedApps.filter { it != id }.toMutableList()
        var open = appsOpen
        if (pinned) {
            if (pins.isEmpty()) open = true
            pins += id
        }
        return if (pins == pinnedApps && open == appsOpen) this else copy(pinnedApps = pins, appsOpen = open)
    }

    /// The pinned ones of [apps], in their order.
    fun pinned(apps: List<WorkspaceApp>): List<WorkspaceApp> = apps.filter { isPinned(it.id) }

    companion object {
        fun decode(text: String?): SidebarLayout {
            val json = parse(text)
            val pins = json.optJSONArray("pinnedApps")?.let { array ->
                (0 until array.length()).mapNotNull { array.opt(it) as? String }
            } ?: emptyList()
            return SidebarLayout(
                json.optBoolean("combinedView", false), json.optBoolean("allRecent", false),
                pins.distinct(), json.optBoolean("appsOpen", true),
            )
        }

        /// `text` with this layout's fields set, every other field as it was.
        fun encode(layout: SidebarLayout, into: String?): String =
            parse(into).put("combinedView", layout.combinedView).put("allRecent", layout.allRecent)
                .put("pinnedApps", JSONArray(layout.pinnedApps)).put("appsOpen", layout.appsOpen).toString()

        private fun parse(text: String?): JSONObject =
            try { if (text.isNullOrEmpty()) JSONObject() else JSONObject(text) } catch (e: org.json.JSONException) { JSONObject() }
    }
}

/// The one holder of the saved layout for the open account, observable, so the Dashboard and Apps (which pins from its menu) see the
/// same pins at once. `change` saves it as the Dashboard always did.
class SidebarLayoutState(private val storage: Storage) {
    var layout: SidebarLayout by mutableStateOf(SidebarLayout.decode(storage.value(Setting.SIDEBAR_LAYOUT))); private set

    fun change(next: SidebarLayout) {
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
