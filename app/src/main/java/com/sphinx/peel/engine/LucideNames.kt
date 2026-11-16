package com.sphinx.peel.engine

/// Which lucide icon a manifest's `icon` names (iOS `LucideNames`, `WorkspaceAppIcon.swift`). The manifest's name is
/// strut's kebab-case lucide name; any spelling that differs only in case, dashes or other punctuation is the same
/// icon (`arrow-right`, `arrowRight`), and an older name lucide still takes for a renamed icon reads as the current one
/// (`home` is `house`). A name that is none, or has no icon, is [FALLBACK]: Apps' grid.
///
/// Not the whole of lucide: iOS draws every icon of the LucideSwift package, Android only [SUPPORTED] (the ones with a
/// Material icon, in `ui/apps/AppIcons.kt`). A real lucide name outside it falls back to the grid, as an unknown one
/// does on iOS.
object LucideNames {
    /// The icon drawn for no name, or one that has none: Apps' grid, four squares.
    const val FALLBACK = "layout-grid"

    /// The lucide names Android has an icon for (the keys of `AppIcons.table`; a test holds the two together).
    val SUPPORTED: Set<String> = setOf(
        "activity", "alarm-clock", "bell", "book", "book-open", "brain", "briefcase", "building", "building-complex",
        "calendar", "camera", "chart-column", "chart-line", "chart-pie", "square-check", "square-check-big",
        "circle-dollar-sign", "clock", "code", "cpu", "credit-card", "database", "dollar-sign", "download", "eye", "file",
        "file-text", "flag", "flask-conical", "flask-round", "folder", "funnel", "gauge", "git-branch", "globe",
        "graduation-cap", "heart", "house", "image", "inbox", "kanban", "layers", "layout-grid", "lightbulb", "link",
        "list", "list-checks", "list-todo", "lock", "mail", "map", "map-pin", "message-circle", "message-square", "music",
        "network", "newspaper", "package", "palette", "pen", "pencil", "phone", "plus", "puzzle", "receipt", "scale",
        "search", "server", "settings", "share", "shield", "shield-check", "shopping-cart", "sparkles", "star", "table",
        "tag", "target", "terminal", "test-tube", "trash", "trending-up", "trophy", "truck", "upload", "user", "users",
        "utensils", "wallet", "workflow", "zap",
    )

    /// The lucide name `name` is, as [SUPPORTED] spells it: itself or what a renamed icon is now; null for none, or
    /// one Android has no icon for.
    fun resolve(name: String?): String? {
        val written = name?.trim()?.lowercase() ?: return null
        index[key(written)]?.let { return it }
        return ALIASES[written]?.let { index[key(it)] }
    }

    /// The icon to draw for a manifest's name: its own, or the grid's.
    fun shape(name: String?): String = resolve(name) ?: FALLBACK

    /// Letters and digits only, lower case: `arrow-right` and `arrowRight` meet here.
    internal fun key(name: String): String = name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    private val index: Map<String, String> = SUPPORTED.associateBy { key(it) }

    /// The names lucide still takes for an icon it renamed, each as the name it has now (the part of iOS's `aliases`
    /// that lands on an icon Android has).
    private val ALIASES = mapOf(
        "home" to "house", "bar-chart" to "chart-column", "bar-chart-2" to "chart-column", "bar-chart-3" to "chart-column",
        "bar-chart-4" to "chart-column", "bar-chart-horizontal" to "chart-column", "chart-bar" to "chart-column",
        "line-chart" to "chart-line", "pie-chart" to "chart-pie", "area-chart" to "chart-line",
        "check-square" to "square-check-big", "check-square-2" to "square-check", "building-2" to "building-complex",
        "edit-2" to "pen", "edit" to "pen", "trash-2" to "trash", "filter" to "funnel", "globe-2" to "globe",
        "user-2" to "user", "user-round" to "user", "users-2" to "users", "users-round" to "users",
        "wallet-2" to "wallet", "wallet-minimal" to "wallet", "kanban-square" to "kanban", "square-kanban" to "kanban",
        "layout" to "layout-grid", "grid" to "layout-grid", "stars" to "sparkles", "upload-cloud" to "upload",
        "download-cloud" to "download", "unlock" to "lock", "mail-question" to "mail", "terminal-square" to "terminal",
        "test-tube-2" to "test-tube", "test-tube-diagonal" to "test-tube", "fork-knife" to "utensils",
        "file-edit" to "file", "history" to "clock", "contact-2" to "user", "help-circle" to "sparkles",
    ).filterValues { it in SUPPORTED }
}
