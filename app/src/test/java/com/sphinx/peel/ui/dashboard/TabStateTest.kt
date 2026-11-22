package com.sphinx.peel.ui.dashboard

import com.sphinx.peel.engine.OpenedApp
import com.sphinx.peel.engine.PublicAppLink
import com.sphinx.peel.engine.TasksTab
import com.sphinx.peel.ui.page.PublicPageRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// The tab bar with a Tasks tab (docs/phases/48-tasks-tab.md): its place and order, the tab Peel opens on, and where a Tasks link
/// lands, the way `MainTabs` reads them (`MainTab`, `TabState`, `TasksTab.content`).
class TabStateTest {
    private val acme = PublicAppLink.of("https://acme.example.com/?app=strut%2Fworker&solo=1&public=1")!!
    private val bolt = PublicAppLink.of("https://bolt.example.com/?app=strut%2Fworker&solo=1&public=1")!!

    @Test fun `Tasks comes first, only for an account that has it`() {
        assertEquals(listOf(MainTab.CHATS, MainTab.PAYMENTS, MainTab.YOU), MainTab.pill(hasTasks = false))
        assertEquals(listOf(MainTab.TASKS, MainTab.CHATS, MainTab.PAYMENTS, MainTab.YOU), MainTab.pill(hasTasks = true))
    }

    @Test fun `Peel opens on Chats whether or not Tasks exists`() {
        assertEquals(MainTab.CHATS, TabState(hasTasks = true).tab)
        assertEquals(MainTab.CHATS, TabState(hasTasks = false).tab)
        // Its last page forgotten while it was in front: Chats.
        assertEquals(MainTab.CHATS, MainTab.shown(MainTab.TASKS, hasTasks = false))
        assertEquals(MainTab.PAYMENTS, MainTab.shown(MainTab.PAYMENTS, hasTasks = false))
        assertEquals(MainTab.TASKS, MainTab.shown(MainTab.TASKS, hasTasks = true))
    }

    @Test fun `a Tasks link puts Tasks in front of the Chats Peel opened on`() {
        val tabs = TabState(hasTasks = true)
        assertEquals(MainTab.CHATS, tabs.tab)
        tabs.openTasks(acme)
        assertEquals(MainTab.TASKS, tabs.tab)
        assertEquals(acme, tabs.chosen)
    }

    /// A new worker's signup: no Tasks page yet when the tabs open (Chats), then the invite's `a=` (a Tasks page) waits for the
    /// unlock, the router hands it to the tab, and the tab shows that page, in front: not a page over Chats.
    @Test fun `after an invite signup Peel lands on the Tasks tab with that page`() {
        val kept = mutableListOf<OpenedApp>()
        val router = PublicPageRouter(opened = { link, _ -> kept += OpenedApp(link, 1) })
        router.open(acme, ready = false)
        val tabs = TabState(hasTasks = TasksTab.shows(kept))
        assertEquals(MainTab.CHATS, tabs.tab)

        router.openWaitingLink()
        assertNull("not over everything", router.page)
        val link = router.tasksPage!!
        tabs.openTasks(link)
        router.tasksTaken()

        val pages = TasksTab.pages(kept)
        assertEquals(MainTab.TASKS, MainTab.shown(tabs.tab, pages.isNotEmpty()))
        assertEquals(TasksTab.Content.Page(acme), TasksTab.content(pages, tabs.chosen))
        assertNull(router.tasksPage)
    }

    @Test fun `a second employer's link opens its page, and Back returns to the list`() {
        val pages = listOf(OpenedApp(bolt, 2), OpenedApp(acme, 1))
        val tabs = TabState(hasTasks = true)
        assertEquals(TasksTab.Content.Pick(pages), TasksTab.content(pages, tabs.chosen))
        tabs.tab = MainTab.PAYMENTS
        tabs.openTasks(bolt)
        assertEquals(MainTab.TASKS, tabs.tab)
        assertEquals(TasksTab.Content.Page(bolt), TasksTab.content(pages, tabs.chosen))
        tabs.backToList()
        assertEquals(TasksTab.Content.Pick(pages), TasksTab.content(pages, tabs.chosen))
        tabs.pick(acme)
        assertEquals(TasksTab.Content.Page(acme), TasksTab.content(pages, tabs.chosen))
    }
}
