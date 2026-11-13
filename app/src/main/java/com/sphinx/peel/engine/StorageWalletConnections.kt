package com.sphinx.peel.engine

import com.sphinx.peel.storage.Storage

/// [WalletConnections] over the account's own database.
class StorageWalletConnections(private val storage: Storage) : WalletConnections {
    override fun connection(key: String): WalletConnection? = storage.walletConnection(key)
    override fun save(connection: WalletConnection) = storage.saveWalletConnection(connection)
}
