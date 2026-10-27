package com.sphinx.peel.engine

import com.sphinx.peel.account.Account
import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.sphinxrs.RunReturn
import uniffi.sphinxrs.initialSetup as coreInitialSetup
import uniffi.sphinxrs.pubkeyFromSeed

/// Drives one account against the Sphinx core: the engine foundation
/// described in `peel`'s docs/PLAN.md ("The core contract" — each call
/// takes the seed, a unique time and the whole state, and returns what
/// changed). Ported from PeelCore's Engine.swift, scoped to what Storage
/// holds so far (`coreState` and `setting`): no messages, contacts, tribes
/// or payments, so `run` here commits a fraction of what the Mac/iPhone
/// `commit` does. Nothing here talks to a server: Transport exists
/// (engine/Transport.kt) but no engine call uses it yet.
class Engine(mnemonic: String, val storage: Storage) {
    val seed: String = Account.seedOf(mnemonic)
    val network: String = storage.value(Setting.NETWORK) ?: "bitcoin"

    /// Guards `state` and `clock` the way Swift's `actor` isolates Engine:
    /// only one core call runs at a time, and the next one always sees the
    /// previous one's committed state.
    private val mutex = Mutex()
    private val clock = UniqueClock(storage.value(Setting.CLOCK)?.toLongOrNull() ?: 0)
    private var state: Map<String, ByteArray> = storage.loadCoreState()

    /// This account's key (child index 0), which will sign every message
    /// it sends and its connection to the broker, once there is one.
    val accountKey: String = pubkeyFromSeed(seed, 0u, clock.next(), network)

    /// A `my_scid` and a `server_pubkey` in state mean this account signed
    /// up with a server and the core accepted it, whatever a connection is
    /// doing right now. Ported from Engine+Account.swift (`isRegistered`).
    val isRegistered: Boolean
        get() = state.containsKey("my_scid") && state.containsKey("server_pubkey")

    /// Runs one core call against the current state and commits what it
    /// returns before this suspends again, so no other call on this engine
    /// ever runs against stale state. Ported from PeelCore's Engine.swift
    /// (`run`); its message/contact/tribe/payment bookkeeping is not here,
    /// Storage doesn't hold those tables yet.
    suspend fun run(call: (time: String, state: ByteArray) -> RunReturn): RunReturn = mutex.withLock {
        val time = clock.next()
        val result = call(time, StateCodec.encode(state))

        val updates = result.stateMp?.let { StateCodec.decode(it) } ?: emptyMap()
        val newState = state.toMutableMap()
        newState.putAll(updates)
        for (key in result.stateToDelete) newState.remove(key)
        state = newState

        storage.apply(
            Storage.Commit(
                state = updates,
                deleteState = result.stateToDelete,
                settings = mapOf(Setting.CLOCK to time),
            ),
        )
        result
    }

    /// The core's own state for a fresh (or re-run) account: no server
    /// connection yet, so nothing is sent anywhere, this only prepares what
    /// a register would need. Ported from PeelCore's `Account.signup`'s
    /// first step.
    suspend fun initialSetup(device: String, inviteCode: String?): RunReturn =
        run { time, stateBytes -> coreInitialSetup(seed, time, stateBytes, device, inviteCode) }
}
