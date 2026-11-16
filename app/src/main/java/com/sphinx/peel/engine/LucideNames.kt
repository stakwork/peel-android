package com.sphinx.peel.engine

/// Which lucide icon a manifest's `icon` names (iOS `LucideNames`, `WorkspaceAppIcon.swift`). The manifest's name is
/// strut's kebab-case lucide name; any spelling that differs only in case, dashes or other punctuation is the same
/// icon (`arrow-right`, `arrowRight`), and an older name lucide still takes for a renamed icon reads as the current one
/// (`home` is `house`). A name that is none, or has no icon, is [FALLBACK]: Apps' grid.
///
/// Every icon of lucide is drawn (`LucideIcons`, the bundled `assets/lucide/icons.json`); the set of names is passed in
/// (`LucideIcons.names`) so this stays pure and testable.
object LucideNames {
    /// The icon drawn for no name, or one lucide has not: Apps' grid, four squares.
    const val FALLBACK = "layout-grid"

    /// The lucide name `name` is among `known`: itself, or what a renamed icon is now; null for none, or one lucide has not.
    fun resolve(name: String?, known: Set<String>): String? = resolve(name, index(known))

    /// As [resolve] over an [index] built once (the lookup the icon library keeps).
    fun resolve(name: String?, index: Map<String, String>): String? {
        val written = name?.trim()?.lowercase() ?: return null
        index[key(written)]?.let { return it }
        return ALIASES[written]?.let { index[key(it)] }
    }

    /// The icon to draw for a manifest's name: its own, or the grid's.
    fun shape(name: String?, index: Map<String, String>): String = resolve(name, index) ?: FALLBACK

    /// Letters and digits only, lower case: `arrow-right` and `arrowRight` meet here.
    internal fun key(name: String): String = name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    /// Each name's [key] → the name (iOS's `index`).
    fun index(known: Set<String>): Map<String, String> {
        val m = HashMap<String, String>(known.size * 2)
        // First name wins on a key clash, in sorted order, so the result does not depend on set order.
        for (n in known.sorted().asReversed()) m[key(n)] = n
        return m
    }

    /// The names lucide still takes for an icon it renamed, each as the name it has now (iOS's `aliases`, all of it).
    internal val ALIASES: Map<String, String> = mapOf(
        "activity-square" to "square-activity", "alarm-check" to "alarm-clock-check",
        "alarm-minus" to "alarm-clock-minus", "alarm-plus" to "alarm-clock-plus", "album" to "square-bookmark",
        "alert-circle" to "circle-alert", "alert-octagon" to "octagon-alert", "alert-triangle" to "triangle-alert",
        "align-center" to "text-align-center", "align-justify" to "text-align-justify",
        "align-left" to "text-align-start", "align-right" to "text-align-end", "angry" to "face-angry",
        "annoyed" to "face-expressionless", "archive-restore" to "archive-arrow-up", "area-chart" to "chart-area",
        "arrow-down-circle" to "circle-arrow-down", "arrow-down-left-from-circle" to "circle-arrow-out-down-left",
        "arrow-down-left-from-square" to "square-arrow-out-down-left",
        "arrow-down-left-square" to "square-arrow-down-left",
        "arrow-down-right-from-circle" to "circle-arrow-out-down-right",
        "arrow-down-right-from-square" to "square-arrow-out-down-right",
        "arrow-down-right-square" to "square-arrow-down-right", "arrow-down-square" to "square-arrow-down",
        "arrow-left-circle" to "circle-arrow-left", "arrow-left-square" to "square-arrow-left",
        "arrow-right-circle" to "circle-arrow-right", "arrow-right-square" to "square-arrow-right",
        "arrow-up-circle" to "circle-arrow-up", "arrow-up-left-from-circle" to "circle-arrow-out-up-left",
        "arrow-up-left-from-square" to "square-arrow-out-up-left", "arrow-up-left-square" to "square-arrow-up-left",
        "arrow-up-right-from-circle" to "circle-arrow-out-up-right",
        "arrow-up-right-from-square" to "square-arrow-out-up-right",
        "arrow-up-right-square" to "square-arrow-up-right", "arrow-up-square" to "square-arrow-up",
        "asterisk-square" to "square-asterisk", "badge-help" to "badge-question-mark",
        "bar-chart" to "chart-no-axes-column-increasing", "bar-chart-2" to "chart-no-axes-column",
        "bar-chart-3" to "chart-column", "bar-chart-4" to "chart-column-increasing",
        "bar-chart-big" to "chart-column-big", "bar-chart-horizontal" to "chart-bar",
        "bar-chart-horizontal-big" to "chart-bar-big", "between-horizonal-end" to "between-horizontal-end",
        "between-horizonal-start" to "between-horizontal-start", "book-marked" to "book-bookmark",
        "book-template" to "book-dashed", "box-select" to "square-dashed", "building-2" to "building-complex",
        "candlestick-chart" to "chart-candlestick", "check-circle" to "circle-check-big",
        "check-circle-2" to "circle-check", "check-square" to "square-check-big", "check-square-2" to "square-check",
        "chevron-down-circle" to "circle-chevron-down", "chevron-down-square" to "square-chevron-down",
        "chevron-left-circle" to "circle-chevron-left", "chevron-left-square" to "square-chevron-left",
        "chevron-right-circle" to "circle-chevron-right", "chevron-right-square" to "square-chevron-right",
        "chevron-up-circle" to "circle-chevron-up", "chevron-up-square" to "square-chevron-up",
        "circle-help" to "circle-question-mark", "circle-slashed" to "circle-slash-2",
        "clipboard-edit" to "clipboard-pen", "clipboard-signature" to "clipboard-pen-line", "code-2" to "code-xml",
        "code-square" to "square-code", "columns" to "columns-2", "columns-settings" to "columns-3-cog",
        "contact-2" to "contact-round", "curly-braces" to "braces", "divide-circle" to "circle-divide",
        "divide-square" to "square-divide", "dot-square" to "square-dot", "download-cloud" to "cloud-download",
        "edit" to "square-pen", "edit-2" to "pen", "edit-3" to "pen-line", "equal-square" to "square-equal",
        "file-audio" to "file-headphone", "file-audio-2" to "file-headphone", "file-badge-2" to "file-badge",
        "file-bar-chart" to "file-chart-column-increasing", "file-bar-chart-2" to "file-chart-column",
        "file-check-2" to "file-check-corner", "file-code-2" to "file-code-corner", "file-cog-2" to "file-cog",
        "file-edit" to "file-pen", "file-json" to "file-braces", "file-json-2" to "file-braces-corner",
        "file-key-2" to "file-key", "file-line-chart" to "file-chart-line", "file-lock-2" to "file-lock",
        "file-minus-2" to "file-minus-corner", "file-pie-chart" to "file-chart-pie",
        "file-plus-2" to "file-plus-corner", "file-question" to "file-question-mark",
        "file-search-2" to "file-search-corner", "file-signature" to "file-pen-line",
        "file-type-2" to "file-type-corner", "file-video" to "file-play", "file-video-2" to "file-video-camera",
        "file-volume-2" to "file-signal", "file-warning" to "file-exclamation-point", "file-x-2" to "file-x-corner",
        "filter" to "funnel", "filter-x" to "funnel-x", "fingerprint" to "fingerprint-pattern",
        "flip-horizontal" to "square-centerline-dashed-horizontal",
        "flip-horizontal-2" to "triangles-centerline-dashed-horizontal",
        "flip-vertical" to "square-centerline-dashed-vertical",
        "flip-vertical-2" to "triangles-centerline-dashed-vertical", "folder-cog-2" to "folder-cog",
        "folder-edit" to "folder-pen", "fork-knife" to "utensils", "fork-knife-crossed" to "utensils-crossed",
        "form-input" to "rectangle-ellipsis", "frown" to "face-slightly-frowning",
        "function-square" to "square-function", "gantt-chart" to "chart-no-axes-gantt",
        "gantt-chart-square" to "square-chart-gantt", "gauge-circle" to "circle-gauge",
        "git-commit" to "git-commit-horizontal", "globe-2" to "earth", "grab" to "hand-grab", "grid" to "grid-3-x-3",
        "help-circle" to "circle-question-mark", "helping-hand" to "hand-helping", "history" to "rotate-ccw-clock",
        "home" to "house", "ice-cream" to "ice-cream-cone", "ice-cream-2" to "ice-cream-bowl",
        "indent" to "list-indent-increase", "indent-decrease" to "list-indent-decrease",
        "indent-increase" to "list-indent-increase", "inspect" to "square-mouse-pointer",
        "kanban-square" to "square-kanban", "kanban-square-dashed" to "square-dashed-kanban",
        "laptop-2" to "laptop-minimal", "laugh" to "face-grinning", "layers-3" to "layers",
        "layout" to "panels-top-left", "letter-text" to "text-initial", "library-square" to "square-library",
        "line-chart" to "chart-line", "loader-2" to "loader-circle", "location-edit" to "map-pin-pen",
        "m-square" to "square-m", "mail-question" to "mail-question-mark", "meh" to "face-neutral",
        "menu-square" to "square-menu", "message-circle-question" to "message-circle-question-mark",
        "mic-2" to "mic-vocal", "minus-circle" to "circle-minus", "minus-square" to "square-minus",
        "more-horizontal" to "ellipsis", "more-vertical" to "ellipsis-vertical",
        "mouse-pointer-square-dashed" to "square-dashed-mouse-pointer", "music" to "eighth-notes",
        "music-2" to "eighth-note", "music-3" to "half-note", "music-4" to "sixteenth-notes",
        "outdent" to "list-indent-decrease", "paintbrush-2" to "paintbrush-vertical", "palmtree" to "tree-palm",
        "panel-bottom-inactive" to "panel-bottom-dashed", "panel-left-inactive" to "panel-left-dashed",
        "panel-right-inactive" to "panel-right-dashed", "panel-top-inactive" to "panel-top-dashed",
        "panels-left-right" to "columns-3", "panels-top-bottom" to "rows-3", "parking-circle" to "circle-parking",
        "parking-circle-off" to "circle-parking-off", "parking-square" to "square-parking",
        "parking-square-off" to "square-parking-off", "pause-circle" to "circle-pause",
        "pause-octagon" to "octagon-pause", "pen-box" to "square-pen", "pen-square" to "square-pen",
        "percent-circle" to "circle-percent", "percent-diamond" to "diamond-percent",
        "percent-square" to "square-percent", "pi-square" to "square-pi", "pie-chart" to "chart-pie",
        "pilcrow-square" to "square-pilcrow", "play-circle" to "circle-play", "play-square" to "square-play",
        "plug-zap-2" to "plug-zap", "plus-circle" to "circle-plus", "plus-square" to "square-plus",
        "podcast" to "mic-signal", "power-circle" to "circle-power", "power-square" to "square-power",
        "rows" to "rows-2", "scatter-chart" to "chart-scatter", "school-2" to "university",
        "scissors-square" to "square-scissors", "scissors-square-dashed-bottom" to "square-bottom-dashed-scissors",
        "send-horizonal" to "send-horizontal", "shield-close" to "shield-x",
        "shield-question" to "shield-question-mark", "sidebar" to "panel-left", "sidebar-close" to "panel-left-close",
        "sidebar-open" to "panel-left-open", "sigma-square" to "square-sigma", "slash-square" to "square-slash",
        "sliders" to "sliders-vertical", "smile" to "face-slightly-smiling",
        "smile-plus" to "face-slightly-smiling-plus", "sort-asc" to "arrow-up-narrow-wide",
        "sort-desc" to "arrow-down-wide-narrow", "split-square-horizontal" to "square-split-horizontal",
        "split-square-vertical" to "square-split-vertical", "square-gantt-chart" to "square-chart-gantt",
        "stars" to "sparkles", "stop-circle" to "circle-stop", "subtitles" to "captions",
        "table-config" to "columns-3-cog", "terminal-square" to "square-terminal",
        "test-tube-2" to "test-tube-diagonal", "text" to "text-align-start", "text-select" to "square-dashed-text",
        "text-selection" to "square-dashed-text", "train" to "tram-front", "trash-2" to "trash",
        "tv-2" to "tv-minimal", "unlock" to "lock-open", "unlock-keyhole" to "lock-keyhole-open",
        "upload-cloud" to "cloud-upload", "user-2" to "user-round", "user-check-2" to "user-round-check",
        "user-circle" to "circle-user", "user-circle-2" to "circle-user-round", "user-cog-2" to "user-round-cog",
        "user-minus-2" to "user-round-minus", "user-plus-2" to "user-round-plus", "user-square" to "square-user",
        "user-square-2" to "square-user-round", "user-x-2" to "user-round-x", "users-2" to "users-round",
        "verified" to "badge-check", "wallet-2" to "wallet-minimal", "wand-2" to "wand-sparkles",
        "waves" to "waves-horizontal", "wrap-text" to "text-wrap", "x-circle" to "circle-x",
        "x-octagon" to "octagon-x", "x-square" to "square-x",
    )
}
