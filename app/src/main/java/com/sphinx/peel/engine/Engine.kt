package com.sphinx.peel.engine

import com.sphinx.peel.account.Account
import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import java.util.UUID
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.sphinxrs.RunReturn
import uniffi.sphinxrs.getTribeManagementTopic as coreGetTribeManagementTopic
import uniffi.sphinxrs.handle as coreHandle
import uniffi.sphinxrs.initialSetup as coreInitialSetup
import uniffi.sphinxrs.pubkeyFromSeed
import uniffi.sphinxrs.rootSignMs as coreRootSignMs
import uniffi.sphinxrs.setNetwork as coreSetNetwork
import uniffi.sphinxrs.xpubFromSeed as coreXpubFromSeed

/// Drives one account against the Sphinx core: the engine foundation
/// described in `peel`'s docs/PLAN.md ("The core contract" — each call
/// takes the seed, a unique time and the whole state, and returns what
/// changed). Ported from PeelCore's Engine.swift, scoped to what Storage
/// holds so far (`coreState` and `setting`): no messages, contacts, tribes
/// or payments, so `run` and `connect` here commit a fraction of what the
/// Mac/iPhone `commit` does.
class Engine(mnemonic: String, val storage: Storage) {
    val seed: String = Account.seedOf(mnemonic)
    val network: String = storage.value(Setting.NETWORK) ?: "bitcoin"

    /// Guards `state` and `clock` the way Swift's `actor` isolates Engine:
    /// only one core call runs at a time, and the next one always sees the
    /// previous one's committed state.
    private val mutex = Mutex()
    private val clock = UniqueClock(storage.value(Setting.CLOCK)?.toLongOrNull() ?: 0)
    private var state: Map<String, ByteArray> = storage.loadCoreState()
    private var transport: Transport? = null

    /// This device among the account's: the core's device, and (once device
    /// requests exist) the one a request names. One per install, made once
    /// and kept. Ported from Engine.swift's `init`.
    val deviceID: String = storage.value(Setting.DEVICE_ID) ?: UUID.randomUUID().toString().also {
        storage.apply(Storage.Commit(settings = mapOf(Setting.DEVICE_ID to it)))
    }

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
        commitLocked(result, time)
        result
    }

    /// The commit half of `run`, usable on its own by `connect` — which
    /// needs to run several core calls (and the network waits between
    /// them) under one hold of `mutex`, the way Swift's actor isolation
    /// covers the whole of `establish()`. `mutex` is not reentrant, so
    /// this must not call `run` itself; callers already hold the lock.
    private fun commitLocked(result: RunReturn, time: String) {
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
    }

    /// The core's own state for a fresh (or re-run) account: no server
    /// connection yet, so nothing is sent anywhere, this only prepares what
    /// a register would need. `isRegistered` stays false until a real
    /// connection completes one: the server, not this call, is what grants
    /// `my_scid`/`server_pubkey`. Ported from PeelCore's `Account.signup`'s
    /// first step.
    suspend fun initialSetup(inviteCode: String?): RunReturn =
        run { time, stateBytes -> coreInitialSetup(seed, time, stateBytes, deviceID, inviteCode) }

    /// Connects to `server` and attempts to register (or, for an account
    /// restored from its words with no local history yet, re-register —
    /// the server answers by its signature, not by what this device
    /// remembers). Ported from the first few steps of PeelCore's
    /// `Engine.establish()`: a fresh signed-timestamp login, `initialSetup`
    /// and the topics its `RunReturn` names, then up to [registerWaitMs] of
    /// whatever the broker sends back, fed through the core's `handle` the
    /// way a live message would be. Everything `establish()` does after
    /// that — `cameOnline`'s ping/read-sync/history-resume pipeline, the
    /// keep-alive loop, reconnect-on-drop — is not here: this proves the
    /// account reaches the server and gets registered, it does not bring
    /// the account online for messaging (there is nowhere to put a message
    /// yet; Storage has no `message` table).
    suspend fun connect(server: ServerAddress, inviteCode: String? = null): RunReturn = mutex.withLock {
        val networkTime = clock.next()
        commitLocked(coreSetNetwork(network), networkTime)

        val loginTime = clock.next()
        val xpub = coreXpubFromSeed(seed, loginTime, network)
        val signature = coreRootSignMs(seed, loginTime, network)
        val connection = Transport(server, clientID = xpub, username = loginTime, password = signature)
        connection.connect()
        transport = connection

        coroutineScope {
            // Started before `initialSetup` is even sent, same order as
            // PeelCore's `establish()` (its `listener` Task precedes its
            // register publish): whatever answers the register — or
            // anything else the broker already has queued — arrives here,
            // not as a return value, the usual MQTT asymmetry. Each one
            // goes through `handle` and gets committed like any other core
            // result; this stops as soon as one of them sets
            // `my_scid`/`server_pubkey`, or after [registerWaitMs],
            // whichever comes first.
            val listening = launch {
                withTimeoutOrNull(registerWaitMs) {
                    connection.inbound()
                        .onEach { message ->
                            val handleTime = clock.next()
                            val handled = coreHandle(
                                message.topic, message.payload, seed, handleTime, StateCodec.encode(state),
                                storage.value(Setting.ALIAS) ?: "", storage.value(Setting.PHOTO_URL) ?: "",
                            )
                            commitLocked(handled, handleTime)
                        }
                        .takeWhile { !isRegistered }
                        .collect {}
                }
            }

            val setupTime = clock.next()
            val setup = coreInitialSetup(seed, setupTime, StateCodec.encode(state), deviceID, inviteCode)
            commitLocked(setup, setupTime)

            if (setup.subscriptionTopics.isNotEmpty()) connection.subscribe(setup.subscriptionTopics)
            val registerTopic = setup.registerTopic
            val registerPayload = setup.registerPayload
            if (registerTopic != null && registerPayload != null) connection.publish(registerTopic, registerPayload)

            val tribeTopicTime = clock.next()
            val tribeTopic = coreGetTribeManagementTopic(seed, tribeTopicTime, StateCodec.encode(state))
            connection.subscribe(listOf(tribeTopic))

            listening.join()
            setup
        }
    }

    suspend fun disconnect() = mutex.withLock {
        transport?.close()
        transport = null
    }

    companion object {
        private const val registerWaitMs = 10_000L
    }
}
