package com.sphinx.peel.storage

import androidx.test.core.app.ApplicationProvider
import com.sphinx.peel.engine.PublicAppLink
import com.sphinx.peel.engine.SignInKey
import com.sphinx.peel.engine.Stadeum
import com.sphinx.peel.engine.TasksTab
import com.sphinx.peel.engine.WalletConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// The Tasks tab over real `public_app` rows (docs/phases/48-tasks-tab.md): the gate `MainTabs` reads, one page or the list, and what
/// Apps and the Chats › Apps row keep (`AppsScreen`, `ChatsScreen`: `TasksTab.others`).
@RunWith(RobolectricTestRunner::class)
class StorageTasksTabTest {
    private fun newStorage() = Storage(ApplicationProvider.getApplicationContext(), account = "test-${System.nanoTime()}")
    private val acme = PublicAppLink.of("https://acme.example.com/?app=strut%2Fworker&solo=1&public=1")!!
    private val bolt = PublicAppLink.of("https://bolt.example.com/?app=strut%2Fworker&solo=1&public=1")!!
    private val board = PublicAppLink.of("https://acme.example.com/?app=board&solo=1&public=1")!!

    /// What ChatsScreen's Apps row reads for a worker (no workspace, no development home).
    private fun appsRow(storage: Storage) = Stadeum.showsApps(emptyList(), null, openedApps = TasksTab.others(storage.openedApps()).isNotEmpty())

    @Test fun `a kept Tasks page shows the tab, and none hides it`() {
        val storage = newStorage()
        assertFalse(TasksTab.shows(storage.openedApps()))
        storage.rememberPublicApp(board, at = 5)
        assertFalse("another public app is not a Tasks page", TasksTab.shows(storage.openedApps()))
        storage.rememberPublicApp(acme, at = 10)
        assertTrue(TasksTab.shows(storage.openedApps()))
        storage.forgetPublicApp(acme)
        assertFalse(TasksTab.shows(storage.openedApps()))
    }

    @Test fun `one employer is its page, several the list, last opened first`() {
        val storage = newStorage()
        storage.rememberPublicApp(acme, at = 10)
        assertEquals(TasksTab.Content.Page(acme), TasksTab.content(TasksTab.pages(storage.openedApps()), null))
        storage.rememberPublicApp(bolt, at = 20)
        val pages = TasksTab.pages(storage.openedApps())
        assertEquals(listOf(bolt, acme), pages.map { it.link })
        assertEquals(TasksTab.Content.Pick(pages), TasksTab.content(pages, null))
        // The list's rows are titled by the employer's connection, once its page signed in.
        storage.saveWalletConnection(WalletConnection("origin:https://bolt.example.com", "/strut/worker", "Bolt Tasks", "bolt.example.com", signIn = SignInKey.root, createdAt = 1))
        assertEquals(listOf("Bolt Tasks", "acme.example.com"), TasksTab.pages(storage.openedApps()).map { it.title })
    }

    @Test fun `Apps keeps every other public app and no Tasks page`() {
        val storage = newStorage()
        storage.rememberPublicApp(acme, at = 10)
        assertFalse("only a Tasks page: no Apps row", appsRow(storage))
        storage.rememberPublicApp(board, at = 11)
        assertEquals(listOf(board), TasksTab.others(storage.openedApps()).map { it.link })
        assertTrue(appsRow(storage))
        storage.forgetPublicApp(board)
        assertFalse(appsRow(storage))
        assertTrue("the tab still has its page", TasksTab.shows(storage.openedApps()))
    }

    @Test fun `disconnecting an employer takes its page from the tab`() {
        val storage = newStorage()
        storage.rememberPublicApp(acme, at = 10)
        storage.rememberPublicApp(bolt, at = 20)
        storage.forgetPublicAppsOf("origin:https://bolt.example.com")
        val pages = TasksTab.pages(storage.openedApps())
        assertEquals(listOf(acme), pages.map { it.link })
        // Bolt was picked: its page goes, and the one left is the tab.
        assertEquals(TasksTab.Content.Page(acme), TasksTab.content(pages, bolt))
    }
}
