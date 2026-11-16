package com.sphinx.peel.engine

/// What the Apps list decides from the workspaces in use (iOS `AppsScreen`): which apps show, what a tap opens, the words for an empty
/// list and the note of a workspace that did not answer. Pure, so the rules are tested without the screen.
object AppsOpenDecision {
    /// iOS: `if case .page(let page) = stadeum.opening(app) { router.orgPage = page }`. A tap on an app whose workspace is not
    /// connected (connect-first, not paired, Mac only) opens nothing and says nothing: null.
    fun page(opening: Opening): OrgPageRequest? = (opening as? Opening.Page)?.request

    /// iOS: `stadeum.apps.filter { $0.peer.map(live.contains) ?? false }` - only apps of workspaces still in use.
    fun visible(apps: List<WorkspaceApp>, liveIds: Set<String>): List<WorkspaceApp> = apps.filter { app -> app.peer?.let { it in liveIds } ?: false }

    /// iOS: `unreadApps.compactMap { id in live.first { $0.id == id }?.name }` - the names of the workspaces that did not answer, as far as
    /// they are still in use.
    fun unreadNames(unreadIds: List<String>, live: List<HivePeer>): List<String> = unreadIds.mapNotNull { id -> live.firstOrNull { it.id == id }?.name }

    const val CONNECT_STADEUM = "Connect Stadeum under You to open the apps your workspaces pin."
    const val NO_APPS = "Apps your Stadeum workspaces pin show here."

    /// The words under the Talk rows when the apps are empty (iOS footer).
    fun emptyText(liveEmpty: Boolean, unread: List<String>): String = when {
        liveEmpty -> CONNECT_STADEUM
        unread.isEmpty() -> NO_APPS
        else -> didNotAnswer(unread)
    }

    fun didNotAnswer(unread: List<String>) = "${unread.joinToString(", ")} did not answer. Pull to try again."

    /// iOS `UnreadNote`: shown under the apps when some are listed.
    fun unreadNote(unread: List<String>) = "Couldn't read ${unread.joinToString(", ")} just now. Pull to try again."
}
