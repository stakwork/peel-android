package com.sphinx.peel.ui.dashboard

import org.json.JSONObject

/// The chat list's saved layout: PeelCore's `ChatSections.Layout`, kept under `Setting.SIDEBAR_LAYOUT`
/// as the same JSON (`combinedView`, `allRecent`). Fields this port does not use (`tribesOpen`,
/// `pinnedApps`, ...) are carried through untouched, so a database shared with the Swift apps loses nothing.
/// Device-local, as in PeelCore (it is not in the preferences file).
data class SidebarLayout(val combinedView: Boolean = false, val allRecent: Boolean = false) {
    companion object {
        fun decode(text: String?): SidebarLayout {
            val json = parse(text)
            return SidebarLayout(json.optBoolean("combinedView", false), json.optBoolean("allRecent", false))
        }

        /// `text` with this layout's two fields set, every other field as it was.
        fun encode(layout: SidebarLayout, into: String?): String =
            parse(into).put("combinedView", layout.combinedView).put("allRecent", layout.allRecent).toString()

        private fun parse(text: String?): JSONObject =
            try { if (text.isNullOrEmpty()) JSONObject() else JSONObject(text) } catch (e: org.json.JSONException) { JSONObject() }
    }
}
