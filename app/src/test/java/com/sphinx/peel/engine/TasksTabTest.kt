package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/// The Tasks tab's rules (docs/phases/48-tasks-tab.md): which opened public apps are an employer's Tasks page, whether the tab shows,
/// and one page or the list.
class TasksTabTest {
    // strut's Tasks share link, as a worker's invite opens it.
    private val acme = PublicAppLink.of("https://acme.example.com/?app=strut%2Fworker&solo=1&public=1")!!
    private val bolt = PublicAppLink.of("https://bolt.example.com/?app=strut%2Fworker&solo=1&public=1")!!
    private val board = PublicAppLink.of("https://acme.example.com/?app=board&solo=1&public=1")!!
    private val shop = PublicAppLink.of("https://shop.example.com")!!

    private fun row(link: PublicAppLink, at: Long = 1, title: String? = null) = OpenedApp(link, at, title)

    @Test fun `a Tasks page is strut's worker app, on any seller, at any address`() {
        assertTrue(TasksTab.isTasks(acme))
        assertTrue(TasksTab.isTasks(PublicAppLink.of("https://acme.example.com/public/?app=strut/worker")!!))
        assertFalse(TasksTab.isTasks(board))
        assertFalse(TasksTab.isTasks(shop))
        assertFalse(TasksTab.isTasks(PublicAppLink.of("https://acme.example.com/?app=worker")!!))
        assertFalse(TasksTab.isTasks(PublicAppLink.of("https://acme.example.com/?app=strut/worker2")!!))
    }

    @Test fun `the tab shows once a Tasks page was opened, and takes those rows from Apps`() {
        assertFalse(TasksTab.shows(emptyList()))
        assertFalse(TasksTab.shows(listOf(row(board), row(shop))))
        val opened = listOf(row(shop, 30), row(acme, 20), row(board, 15), row(bolt, 10))
        assertTrue(TasksTab.shows(opened))
        assertEquals(listOf(acme, bolt), TasksTab.pages(opened).map { it.link })
        assertEquals(listOf(shop, board), TasksTab.others(opened).map { it.link })
    }

    @Test fun `no Tasks page is no tab`() {
        assertEquals(TasksTab.Content.None, TasksTab.content(emptyList(), null))
        assertEquals(TasksTab.Content.None, TasksTab.content(emptyList(), acme))
    }

    @Test fun `one employer is the tab, its page at the address a link last opened`() {
        val pages = listOf(row(acme))
        assertEquals(TasksTab.Content.Page(acme), TasksTab.content(pages, null))
        val deep = PublicAppLink.of("https://acme.example.com/?app=strut%2Fworker&solo=1&public=1&task=7")!!
        assertEquals(TasksTab.Content.Page(deep), TasksTab.content(pages, deep))
        // A page chosen whose row was forgotten (Disconnect) is not shown.
        assertEquals(TasksTab.Content.Page(acme), TasksTab.content(pages, bolt))
        assertFalse(TasksTab.picked(pages, TasksTab.content(pages, null)))
    }

    @Test fun `several employers are the list, and a picked one is its page`() {
        val pages = listOf(row(acme, 2), row(bolt, 1))
        assertEquals(TasksTab.Content.Pick(pages), TasksTab.content(pages, null))
        val picked = TasksTab.content(pages, bolt)
        assertEquals(TasksTab.Content.Page(bolt), picked)
        assertTrue("Back returns to the list", TasksTab.picked(pages, picked))
        // A third employer, never kept: the list.
        val other = PublicAppLink.of("https://other.example.com/?app=strut%2Fworker")!!
        assertEquals(TasksTab.Content.Pick(pages), TasksTab.content(pages, other))
    }

    @Test fun `a row is titled by its connection, else the host, with the host under it`() {
        assertEquals("acme.example.com", row(acme).title)
        assertEquals("Acme Tasks", row(acme, title = "Acme Tasks").title)
        assertEquals("acme.example.com", TasksTab.caption(row(acme, title = "Acme Tasks")))
    }
}
