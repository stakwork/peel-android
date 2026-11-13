package com.sphinx.peel.engine

/// How something opens on its org's own strut, for a device with no strut of its own, or why it does not (iOS `Stadeum.Opening`).
sealed class Opening {
    data class Page(val request: OrgPageRequest) : Opening() {
        override fun toString() = "Page(${request})"
    }

    /// No Stadeum on this device, or Stadeum no longer knows it.
    data object ConnectFirst : Opening()

    /// An org this device holds no token for.
    data class NotPaired(val peer: String) : Opening()

    /// On the brain's own strut, which only the Mac reaches.
    data object OnMac : Opening()
}

/// What a tapped link does here (iOS `Stadeum.LinkTap`): a Hive app link for a workspace this device has paired opens that app; one
/// for a workspace it has not (or with Stadeum not connected) says to connect it first; any other link is not Peel's to open.
sealed class LinkTap {
    data class Open(val page: OrgPage, val request: OrgPageRequest) : LinkTap()

    /// The workspace's slug.
    data class ConnectFirst(val slug: String) : LinkTap()

    data object NotOurs : LinkTap()
}

/// The ways things open in the page host, over the person's Stadeum workspaces (iOS `Stadeum.opening(_:)`, `tap(_:)`,
/// `opensFromTribe`, `workspaceName`). Pure: [live] is the workspaces in use, [connected] whether Stadeum is paired and not revoked
/// (what tells "connect first" from "not paired"). [StadeumModel] and [Stadeum] call it with their own state.
class Openings(private val live: List<HivePeer>, private val connected: Boolean) {
    /// How an org's page opens: on its own strut. [title] is what the person calls it (the app's or artifact's), for the page's title.
    fun opening(page: OrgPage, title: String? = null): Opening {
        val peer = live.firstOrNull { it.id == page.peer } ?: return if (connected) Opening.NotPaired(page.peer) else Opening.ConnectFirst
        val url = page.url(peer.baseUrl) ?: return Opening.NotPaired(page.peer)
        return Opening.Page(OrgPageRequest(url, peer.token, peer.id, peer.name, title))
    }

    /// How a deliverable from the agent's DM opens: on its org's strut. [title] is what the person calls it (the artifact's).
    fun opening(handle: AgentHandle, title: String? = null): Opening = handle.onOrg?.let { opening(it, title) } ?: Opening.OnMac

    /// How a workspace's app opens: on its own strut.
    fun opening(app: WorkspaceApp): Opening = app.peer?.let { opening(OrgPage.app(app.name, it), app.title) } ?: Opening.OnMac

    /// What a tapped link does here.
    fun tap(url: String, hive: String = WorkspaceApp.HIVE_PRODUCTION): LinkTap {
        val page = OrgPage.hiveApp(url, hive) ?: return LinkTap.NotOurs
        return when (val opened = opening(page)) {
            is Opening.Page -> LinkTap.Open(page, opened.request)
            Opening.ConnectFirst, is Opening.NotPaired -> LinkTap.ConnectFirst(page.peer)
            Opening.OnMac -> LinkTap.NotOurs
        }
    }

    /// Whether a deliverable sent in a tribe opens here: on one of the person's own live workspaces, with their own token. Anyone in a
    /// tribe could have sent it (docs/hive-tribes.md, "Deliverables"), so what it names anywhere else does not open.
    fun opensFromTribe(handle: AgentHandle): Boolean = handle.peer?.let { peer -> live.any { it.id == peer } } ?: false

    /// A workspace as the person reads it: a live one's name, else `@id`.
    fun workspaceName(id: String): String = live.firstOrNull { it.id == id }?.name ?: "@$id"
}

/// What a tapped link does in the app, app-wide (iOS `.environment(\.openURL, OpenURLAction { … })` in `MainTabs`): a Hive app link
/// for a paired workspace opens its page; one for a workspace not paired here says to connect it first and still goes to the browser,
/// so Hive shows its own sign-in or not-found; any other link is the system's, as before.
class AppLinkHandler(
    private val tap: (String) -> LinkTap,
    /// The page opened again as it is tapped: the tokens may have been renewed since.
    private val reopen: (OrgPage) -> Opening,
    private val openPage: (OrgPageRequest) -> Unit,
    private val announce: (String) -> Unit,
    private val system: (String) -> Unit,
) {
    fun open(url: String) {
        when (val tapped = tap(url)) {
            is LinkTap.Open -> (reopen(tapped.page) as? Opening.Page)?.let { openPage(it.request) }
            is LinkTap.ConnectFirst -> {
                announce("Connect @${tapped.slug} first")
                system(url)
            }
            LinkTap.NotOurs -> system(url)
        }
    }
}
