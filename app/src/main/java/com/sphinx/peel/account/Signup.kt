package com.sphinx.peel.account

import com.sphinx.peel.engine.ConnectionState
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.Invite
import com.sphinx.peel.engine.InviteProblem
import com.sphinx.peel.engine.ServerAddress
import com.sphinx.peel.engine.ServerConfig
import com.sphinx.peel.engine.SignupRules
import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/// A new account made from an invite: the only way into the app for someone without
/// words to restore. Ported from PeelCore's `Account.signup` (Account.swift).
object Signup {
    /// A new account, with the words the person must write down.
    class Created(
        val engine: Engine,
        val mnemonic: String,
        /// False when the server has not confirmed the register (yet): the account is kept and
        /// registers again on each connect; say so ([SignupRules.NOT_CONFIRMED]).
        val confirmed: Boolean,
    )

    /// Why a signup ended with no account; the message is for the person.
    class Failed(message: String) : Exception(message)

    /// Longer than the register's own 30 s wait plus connecting to the broker.
    private const val CONNECT_WAIT_MS = 60_000L

    /// Creates the account and connects, registering with [invite]. In PeelCore's order: the
    /// server comes from the invite (never the default config); the tribes host from the config
    /// (a failure is ignored); the words are made and sealed under [pin]; the settings are
    /// written (the name, the server — kept for every later connect —, the invite) BEFORE
    /// anything connects, so an app killed mid-way still registers with the invite when it opens;
    /// then the engine connects. The profile is removed again only if no register can have
    /// reached the server ([SignupRules.keepAccount]).
    suspend fun create(
        secretStore: SecretStore,
        storage: Storage,
        invite: Invite,
        alias: String,
        pin: String,
        network: String = "bitcoin",
    ): Created = withContext(Dispatchers.Default) {
        val address = try {
            ServerAddress.named(invite.server ?: throw InviteProblem.NO_SERVER)
        } catch (e: IllegalArgumentException) {
            throw Failed(e.message ?: "The invite names a server Peel can't use.")
        }
        if (invite.inviterContactInfo == null) throw InviteProblem.NO_INVITER
        val name = alias.trim()
        if (name.isEmpty()) throw Failed("Enter a name.")
        // Only for a public server: a local test stack is not described by the production config.
        val tribeHost = if (ServerAddress.isLoopback(address.host)) null
        else runCatching { ServerConfig.fetch(network).tribeHost }.getOrNull()

        val mnemonic = Account.generateMnemonic()
        try {
            secretStore.save(mnemonic, pin)
            val settings = mutableMapOf(
                Setting.NETWORK to network,
                Setting.WORDS_CONFIRMED to "0",
                Setting.ALIAS to name,
                Setting.SERVER to address.toString(),
                Setting.SERVER_PINNED to "1",
                Setting.INVITE to invite.toJson(),
            )
            tribeHost?.let { settings[Setting.TRIBE_HOST] = it }
            storage.apply(Storage.Commit(settings = settings))
        } catch (e: Exception) {
            discard(secretStore, storage, null)
            throw Failed(e.message ?: "Could not save the account.")
        }

        val engine = Engine(mnemonic, storage)
        engine.start()
        val outcome = withTimeoutOrNull(CONNECT_WAIT_MS) {
            engine.connectionState.first { it is ConnectionState.Connected || it is ConnectionState.Failed }
        }
        if (outcome is ConnectionState.Connected) return@withContext Created(engine, mnemonic, confirmed = true)

        if (SignupRules.keepAccount(engine.isRegistered, engine.inviteMayBeRedeemed)) {
            // Kept unconfirmed: a register may have landed. Disconnected, as PeelCore does; it is made
            // again, with the stored invite, as the account connects next.
            runCatching { engine.disconnect() }
            return@withContext Created(engine, mnemonic, confirmed = engine.isRegistered)
        }
        val reason = (outcome as? ConnectionState.Failed)?.message ?: "The server did not answer in time."
        discard(secretStore, storage, engine)
        throw Failed(reason)
    }

    /// Takes back everything a failed signup stored.
    private suspend fun discard(secretStore: SecretStore, storage: Storage, engine: Engine?) {
        if (engine != null) {
            engine.cancel()
            runCatching { engine.disconnect() }
        }
        storage.deleteAccount()
        secretStore.deleteMnemonic()
    }
}
