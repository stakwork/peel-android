package com.sphinx.peel.storage

import androidx.test.core.app.ApplicationProvider
import com.sphinx.peel.engine.SignInKey
import com.sphinx.peel.engine.WalletConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// `wallet_connection` (`Storage.v13`): save, replace, list, revoke.
@RunWith(RobolectricTestRunner::class)
class StorageWalletConnectionTest {
    private fun newStorage() = Storage(ApplicationProvider.getApplicationContext(), account = "test-${System.nanoTime()}")
    private fun connection(key: String, signIn: SignInKey?, createdAt: Long = 1) =
        WalletConnection(key, "moon-game", "Moon Game", "shop.example.com", emptyList(), signIn, createdAt)

    @Test
    fun `a connection is saved, replaced and revoked`() {
        val storage = newStorage()
        val key = "origin:https://shop.example.com"
        assertNull(storage.walletConnection(key))
        storage.saveWalletConnection(connection(key, SignInKey.root))
        assertEquals(SignInKey.root, storage.walletConnection(key)!!.signIn)
        storage.saveWalletConnection(connection(key, SignInKey.domain).copy(methods = listOf("pay")))
        val kept = storage.walletConnection(key)!!
        assertEquals(SignInKey.domain, kept.signIn)
        assertEquals(listOf("pay"), kept.methods)
        assertEquals(1, storage.walletConnections().size)
        storage.deleteWalletConnection(key)
        assertNull(storage.walletConnection(key))
        assertEquals(emptyList<WalletConnection>(), storage.walletConnections())
    }

    @Test
    fun `a sign-in-only connection reads back with no key or methods`() {
        val storage = newStorage()
        storage.saveWalletConnection(connection("origin:http://localhost:3000", null, createdAt = 5))
        storage.saveWalletConnection(connection("origin:https://b.example.com", SignInKey.root, createdAt = 9))
        assertNull(storage.walletConnection("origin:http://localhost:3000")!!.signIn)
        assertEquals(listOf("origin:https://b.example.com", "origin:http://localhost:3000"), storage.walletConnections().map { it.key })
    }
}
