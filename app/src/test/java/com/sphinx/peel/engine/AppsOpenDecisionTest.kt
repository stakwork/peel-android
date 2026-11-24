package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/// iOS `AppsScreen`: what a tap opens, which apps show, the words of an empty list.
class AppsOpenDecisionTest {
    private val acme = HivePeer("acme", "https://acme.sphinx.chat:3355/lab", "tok", "Acme")
    private fun app(name: String, peer: String?) = WorkspaceApp(peer, name, name)

    @Test fun `an app on a live workspace opens its page`() {
        val request = Openings(listOf(acme), true).opening(app("board", "acme")).let { AppsOpenDecision.page(it) }
        assertEquals("acme", request?.peer)
    }

    @Test fun `an app whose workspace is not connected opens nothing`() {
        for (opening in listOf(Opening.ConnectFirst, Opening.OnMac, Opening.NotPaired("acme"))) assertNull(AppsOpenDecision.page(opening))
        assertNull(AppsOpenDecision.page(Openings(emptyList(), false).opening(app("board", "acme"))))
        assertNull(AppsOpenDecision.page(Openings(listOf(acme), true).opening(app("board", "gone"))))
    }

    @Test fun `only the apps of workspaces in use are listed`() {
        val apps = listOf(app("a", "acme"), app("b", "gone"), app("c", null))
        assertEquals(listOf("a"), AppsOpenDecision.visible(apps, setOf("acme")).map { it.name })
        assertEquals(emptyList<WorkspaceApp>(), AppsOpenDecision.visible(apps, emptySet()))
    }

    @Test fun `unread names are those of live workspaces that did not answer`() {
        assertEquals(listOf("Acme"), AppsOpenDecision.unreadNames(listOf("acme", "gone"), listOf(acme)))
    }

    @Test fun `the empty words`() {
        assertEquals("Connect Hive under You to open the apps your workspaces pin.", AppsOpenDecision.emptyText(true, emptyList()))
        assertEquals("Connect Hive under You to open the apps your workspaces pin.", AppsOpenDecision.emptyText(true, listOf("Acme")))
        assertEquals("Apps your Hive workspaces pin show here.", AppsOpenDecision.emptyText(false, emptyList()))
        assertEquals("Acme, Beta did not answer. Pull to try again.", AppsOpenDecision.emptyText(false, listOf("Acme", "Beta")))
        assertEquals("Couldn't read Acme, Beta just now. Pull to try again.", AppsOpenDecision.unreadNote(listOf("Acme", "Beta")))
    }
}
