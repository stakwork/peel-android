package com.sphinx.peel.storage

import androidx.test.core.app.ApplicationProvider
import com.sphinx.peel.engine.OverBudget
import com.sphinx.peel.engine.SignInKey
import com.sphinx.peel.engine.WalletConnection
import com.sphinx.peel.engine.WalletMethod
import com.sphinx.peel.engine.WalletNotificationType
import com.sphinx.peel.engine.WalletRenewal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// `wallet_connection`: save, replace, list, revoke (the full shape and its other readers are `StorageWalletTest`).
@RunWith(RobolectricTestRunner::class)
class StorageWalletConnectionTest {
    private fun newStorage() = Storage(ApplicationProvider.getApplicationContext(), account = "test-${System.nanoTime()}")
    private fun connection(key: String, signIn: SignInKey?, createdAt: Long = 1) =
        WalletConnection(key, "moon-game", "Moon Game", "shop.example.com", signIn = signIn, createdAt = createdAt)

    @Test
    fun `a connection is saved, replaced and revoked`() {
        val storage = newStorage()
        val key = "origin:https://shop.example.com"
        assertNull(storage.walletConnection(key))
        storage.saveWalletConnection(connection(key, SignInKey.root))
        assertEquals(SignInKey.root, storage.walletConnection(key)!!.signIn)
        storage.saveWalletConnection(connection(key, SignInKey.domain).copy(methods = listOf(WalletMethod.PAY_KEYSEND)))
        val kept = storage.walletConnection(key)!!
        assertEquals(SignInKey.domain, kept.signIn)
        assertEquals(listOf(WalletMethod.PAY_KEYSEND), kept.methods)
        assertEquals(1, storage.walletConnections().size)
        storage.deleteWalletConnection(key)
        assertNull(storage.walletConnection(key))
        assertEquals(emptyList<WalletConnection>(), storage.walletConnections())
    }

    @Test
    fun `a sign-in-only connection reads back with no key or methods, oldest first`() {
        val storage = newStorage()
        storage.saveWalletConnection(connection("origin:http://localhost:3000", null, createdAt = 5))
        storage.saveWalletConnection(connection("origin:https://b.example.com", SignInKey.root, createdAt = 9))
        assertNull(storage.walletConnection("origin:http://localhost:3000")!!.signIn)
        assertEquals(listOf("origin:http://localhost:3000", "origin:https://b.example.com"), storage.walletConnections().map { it.key })
        val plain = storage.walletConnection("origin:http://localhost:3000")!!
        assertEquals(emptyList<WalletNotificationType>(), plain.notificationTypes)
        assertEquals(WalletRenewal.NEVER, plain.renewal)
        assertEquals(OverBudget.ASK, plain.overBudget)
        assertNull(plain.maxAmountMsat)
    }
}
