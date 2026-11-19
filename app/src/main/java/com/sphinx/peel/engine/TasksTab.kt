package com.sphinx.peel.engine

/// The Tasks tab (docs/phases/48-tasks-tab.md): a worker's one work page per employer. Each employer's Stadeum serves its Tasks app
/// (strut's `strut/worker`) publicly, and opening it here keeps a `public_app` row like any public app (`Storage.openedApps`,
/// docs/phases/41-worker-onboarding.md). Those rows are the tab's; every other public app stays in Apps. Pure, JVM-tested.
object TasksTab {
    /// strut's Tasks app, the one page a worker's invite link opens.
    const val APP = "strut/worker"

    /// Whether [link] is an employer's Tasks page.
    fun isTasks(link: PublicAppLink): Boolean = link.app == APP

    /// The Tasks pages among the public apps opened here, in their order (last opened first): one per employer.
    fun pages(opened: List<OpenedApp>): List<OpenedApp> = opened.filter { isTasks(it.link) }

    /// Every other public app opened here: what Apps lists under "Apps you opened" (the tab holds the Tasks pages).
    fun others(opened: List<OpenedApp>): List<OpenedApp> = opened.filterNot { isTasks(it.link) }

    /// Whether the account has the tab: it has opened a Tasks page.
    fun shows(opened: List<OpenedApp>): Boolean = opened.any { isTasks(it.link) }

    /// What the tab shows.
    sealed interface Content {
        /// No Tasks page: no tab.
        data object None : Content

        /// One page, in the tab itself.
        data class Page(val link: PublicAppLink) : Content

        /// Several employers and none picked: a row each.
        data class Pick(val pages: List<OpenedApp>) : Content
    }

    /// What the tab shows for [pages] (the Tasks rows) and [chosen] (a page picked from the list, or opened by a link): the chosen
    /// page while its row is kept (a row forgotten by Disconnect takes its page with it); else the one employer's page, at the
    /// address it was last opened at; else, with several, the list.
    fun content(pages: List<OpenedApp>, chosen: PublicAppLink?): Content {
        val kept = chosen?.takeIf { c -> pages.any { sameApp(it.link, c) } }
        return when {
            pages.isEmpty() -> Content.None
            kept != null -> Content.Page(kept)
            pages.size == 1 -> Content.Page(pages[0].link)
            else -> Content.Pick(pages)
        }
    }

    /// Whether [content] is a page picked from a list (Back returns to it), not the one employer's page that is the tab.
    fun picked(pages: List<OpenedApp>, content: Content): Boolean = content is Content.Page && pages.size > 1

    /// One row's line under its title: the host (every row is the same app).
    fun caption(app: OpenedApp): String = app.link.host

    /// The same app on the same seller: what `public_app` keeps one row of.
    fun sameApp(a: PublicAppLink, b: PublicAppLink): Boolean = a.seller == b.seller && (a.app ?: "") == (b.app ?: "")
}
