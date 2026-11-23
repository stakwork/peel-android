package com.sphinx.peel.engine

/// The Apps tab's one list (docs/phases/48-tasks-tab.md): the workspace apps (Stadeum, A–Z) first, then every public app opened here
/// (`Storage.openedApps`, an employer's Tasks page too), last opened first. An opened app's source is its host. Pure, JVM-tested.
/// iOS's `AppsListing`.
object AppsListing {
    sealed interface Row {
        data class Workspace(val app: WorkspaceApp) : Row
        data class Opened(val app: OpenedApp) : Row
    }

    /// The sources the filter offers: the workspaces' (`WorkspaceApp.sources`), then each opened app's host once, in list order.
    fun sources(workspace: List<WorkspaceApp>, opened: List<OpenedApp>): List<String> =
        (WorkspaceApp.sources(workspace) + opened.map { it.link.host }).distinct()

    /// The rows [query] finds from [source] (all when null): workspace apps by title, then opened apps in their given order.
    fun merged(workspace: List<WorkspaceApp>, opened: List<OpenedApp>, query: String, source: String?): List<Row> =
        WorkspaceApp.byTitle(WorkspaceApp.filter(workspace, query, source)).map { Row.Workspace(it) } +
            opened.filter { (source == null || it.link.host == source) && it.matches(query) }.map { Row.Opened(it) }
}
