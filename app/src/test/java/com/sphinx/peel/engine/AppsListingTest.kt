package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class AppsListingTest {
    private fun ws(name: String, peer: String? = "w1") = WorkspaceApp(peer, name, name.replaceFirstChar { it.uppercase() })
    private fun link(url: String) = PublicAppLink.of(url)!!
    private val tasks = OpenedApp(link("https://acme.example.com/?app=strut%2Fworker&public=1"), 3, "Acme Tasks")
    private val board = OpenedApp(link("https://board.example.com/?app=board&public=1"), 2)
    private val second = OpenedApp(link("https://acme.example.com/?app=shop&public=1"), 1)
    private val opened = listOf(tasks, board, second)
    private val workspace = listOf(ws("zeta"), ws("alpha"))

    @Test fun workspaceAppsFirstThenOpenedLastOpenedFirst() {
        val rows = AppsListing.merged(workspace, opened, "", null)
        assertEquals(
            listOf(AppsListing.Row.Workspace(workspace[1]), AppsListing.Row.Workspace(workspace[0])) + opened.map { AppsListing.Row.Opened(it) },
            rows,
        )
    }

    @Test fun sourcesIncludeEachHostOnce() {
        assertEquals(listOf("@w1", "acme.example.com", "board.example.com"), AppsListing.sources(workspace, opened))
        assertEquals(listOf("acme.example.com", "board.example.com"), AppsListing.sources(emptyList(), opened))
    }

    @Test fun filterByHost() {
        assertEquals(listOf(tasks, second).map { AppsListing.Row.Opened(it) }, AppsListing.merged(workspace, opened, "", "acme.example.com"))
        assertEquals(workspace.sortedBy { it.title }.map { AppsListing.Row.Workspace(it) }, AppsListing.merged(workspace, opened, "", "@w1"))
    }

    @Test fun queryMatchesBoth() {
        assertEquals(listOf(AppsListing.Row.Workspace(workspace[1])), AppsListing.merged(workspace, opened, "alph", null))
        assertEquals(listOf(AppsListing.Row.Opened(board)), AppsListing.merged(workspace, opened, "board", null))
        assertEquals(listOf(AppsListing.Row.Opened(tasks)), AppsListing.merged(workspace, opened, "Acme Tasks", null))
    }
}
