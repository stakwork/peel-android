package com.sphinx.peel.storage

import androidx.test.core.app.ApplicationProvider
import com.sphinx.peel.engine.OpenedApp
import com.sphinx.peel.engine.PublicAppLink
import com.sphinx.peel.engine.SignInKey
import com.sphinx.peel.engine.Stadeum
import com.sphinx.peel.engine.WalletConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// `public_app` (v16, docs/phases/41-worker-onboarding.md): the strangers' public apps opened here, kept for the Apps list.
@RunWith(RobolectricTestRunner::class)
class StoragePublicAppTest {
    private fun newStorage() = Storage(ApplicationProvider.getApplicationContext(), account = "test-${System.nanoTime()}")
    private val worker = PublicAppLink.of("https://acme.example.com/public/?app=worker")!!
    private val board = PublicAppLink.of("https://acme.example.com/public/?app=board")!!
    private val shop = PublicAppLink.of("https://shop.example.com")!!

    @Test fun `opened apps are kept, last opened first, one per app on a seller`() {
        val storage = newStorage()
        assertEquals(emptyList<OpenedApp>(), storage.openedApps())
        val before = storage.publicAppsVersion.value
        storage.rememberPublicApp(worker, at = 10)
        storage.rememberPublicApp(shop, at = 20)
        storage.rememberPublicApp(board, at = 15)
        assertTrue(storage.publicAppsVersion.value > before)
        assertEquals(listOf(shop, board, worker), storage.openedApps().map { it.link })
        // Opened again, from another address of the same app: one row, the new address, first.
        val again = PublicAppLink.of("https://acme.example.com/public/?app=worker&from=link")!!
        storage.rememberPublicApp(again, at = 30)
        assertEquals(listOf(again, shop, board), storage.openedApps().map { it.link })
        assertEquals(30L, storage.openedApps().first().openedAt)
    }

    @Test fun `the title is the origin's connection's, if it has one`() {
        val storage = newStorage()
        storage.rememberPublicApp(worker, at = 10)
        storage.rememberPublicApp(shop, at = 5)
        assertEquals(listOf(null, null), storage.openedApps().map { it.connectionTitle })
        assertEquals("acme.example.com", storage.openedApps().first().title)
        storage.saveWalletConnection(WalletConnection("origin:https://acme.example.com", "/worker", "Tasks", "acme.example.com", signIn = SignInKey.root, createdAt = 1))
        assertEquals(listOf("Tasks", null), storage.openedApps().map { it.connectionTitle })
        assertEquals("Tasks", storage.openedApps().first().title)
        assertEquals("acme.example.com · worker", storage.openedApps().first().caption)
    }

    @Test fun `remove takes one app off the list and leaves its connection`() {
        val storage = newStorage()
        val key = "origin:https://acme.example.com"
        storage.saveWalletConnection(WalletConnection(key, "/worker", "Tasks", "acme.example.com", signIn = SignInKey.root, createdAt = 1))
        storage.rememberPublicApp(worker, at = 10)
        storage.rememberPublicApp(board, at = 11)
        storage.forgetPublicApp(worker)
        assertEquals(listOf(board), storage.openedApps().map { it.link })
        assertEquals("Tasks", storage.walletConnection(key)!!.title)
    }

    @Test fun `disconnect forgets every app of that origin, and nothing for another key`() {
        val storage = newStorage()
        storage.rememberPublicApp(worker, at = 10)
        storage.rememberPublicApp(board, at = 11)
        storage.rememberPublicApp(shop, at = 12)
        storage.forgetPublicAppsOf("org:acme")
        storage.forgetPublicAppsOf("origin:https://other.example.com")
        assertEquals(3, storage.openedApps().size)
        storage.forgetPublicAppsOf("origin:https://acme.example.com")
        assertEquals(listOf(shop), storage.openedApps().map { it.link })
    }

    @Test fun `a search finds an app by its title, host or name`() {
        val app = OpenedApp(worker, 1, "Tasks")
        assertTrue(app.matches(""))
        assertTrue(app.matches(" tasks "))
        assertTrue(app.matches("ACME"))
        assertTrue(app.matches("work"))
        assertFalse(app.matches("shop"))
        assertEquals("shop.example.com", OpenedApp(shop, 1).caption)
        assertEquals("shop.example.com", OpenedApp(shop, 1, " ").title)
    }

    /// A worker (no Hive workspace, no development home) sees the Chats screen's Apps row only once a public app is kept here,
    /// and not after the last one is removed: the gate ChatsScreen reads (`Stadeum.showsApps(openedApps)`).
    @Test fun `a kept public app shows Apps to someone with no workspace`() {
        val storage = newStorage()
        fun showsApps() = Stadeum.showsApps(emptyList(), null, openedApps = storage.openedApps().isNotEmpty())
        assertFalse(showsApps())
        storage.rememberPublicApp(worker, at = 10)
        assertTrue(showsApps())
        storage.forgetPublicApp(worker)
        assertFalse(showsApps())
    }
}
