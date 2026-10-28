package com.sphinx.peel.engine

import com.sphinx.peel.account.Account
import com.sphinx.peel.storage.ContactRow
import com.sphinx.peel.storage.MessageRow
import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import uniffi.sphinxrs.Msg
import uniffi.sphinxrs.RunReturn
import uniffi.sphinxrs.fetchFirstMsgsPerKey as coreFetchFirstMsgsPerKey
import uniffi.sphinxrs.fetchMsgsBatch as coreFetchMsgsBatch
import uniffi.sphinxrs.getMsgsCounts as coreGetMsgsCounts
import uniffi.sphinxrs.getTribeManagementTopic as coreGetTribeManagementTopic
import uniffi.sphinxrs.handle as coreHandle
import uniffi.sphinxrs.initialSetup as coreInitialSetup
import uniffi.sphinxrs.listContacts as coreListContacts
import uniffi.sphinxrs.pubkeyFromSeed
import uniffi.sphinxrs.rootSignMs as coreRootSignMs
import uniffi.sphinxrs.setNetwork as coreSetNetwork
import uniffi.sphinxrs.xpubFromSeed as coreXpubFromSeed

/// Drives one account against the Sphinx core: the engine foundation
/// described in `peel`'s docs/PLAN.md ("The core contract" — each call
/// takes the seed, a unique time and the whole state, and returns what
/// changed). Ported from PeelCore's Engine.swift, scoped to what Storage
/// holds so far (`coreState`, `setting`, `contact`, `message`): no tribe
/// membership, payments or reply/thread handling, so `run` and `connect`
/// here commit a fraction of what the Mac/iPhone `commit` does.
class Engine(mnemonic: String, val storage: Storage) {
    val seed: String = Account.seedOf(mnemonic)
    val network: String = storage.value(Setting.NETWORK) ?: "bitcoin"

    /// Guards `state` and `clock` the way Swift's `actor` isolates Engine:
    /// only one core call runs at a time, and the next one always sees the
    /// previous one's committed state. `state` is also read from
    /// [isRegistered] by callers that are not holding the lock (the
    /// register wait in `connect`), so it needs to be visible across
    /// threads on its own; `@Volatile` gives it that without needing the
    /// lock just to read it.
    private val mutex = Mutex()
    private val clock = UniqueClock(storage.value(Setting.CLOCK)?.toLongOrNull() ?: 0)
    @Volatile private var state: Map<String, ByteArray> = storage.loadCoreState()
    private var transport: Transport? = null
    private var listenerStarted = false

    /// Every `handle`d inbound message, for whoever is waiting on one —
    /// `connect`'s register wait, and `askAndAwait`'s history/counts
    /// waits. One persistent collector of `Transport.inbound()`
    /// (`startListening`) feeds this for as long as the connection lives,
    /// rather than each waiter collecting the transport itself: MQTT
    /// publishes the server sends before a given collector attaches are
    /// gone for good, so there can only be the one.
    private val inboundResults = MutableSharedFlow<RunReturn>(extraBufferCapacity = 64)

    /// Owns `start`'s background work so it outlives whatever screen
    /// launched it — the restore screen's "Open Peel" leaves it running,
    /// same as PeelUI's `AppModel.attach` does with its own account tasks.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    private val connectionStateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = connectionStateFlow.asStateFlow()

    private val restoreProgressFlow = MutableStateFlow(
        RestoreProgress(restoring = storage.value(Setting.NEEDS_RESTORE) == "1"),
    )
    val restoreProgress: StateFlow<RestoreProgress> = restoreProgressFlow.asStateFlow()

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

    /// Runs one core call against the current state, commits what it
    /// returns, and publishes whatever network work the result asks for
    /// (its subscriptions, its register, any topic/payload pairs) —
    /// before this suspends again, so no other call on this engine ever
    /// runs against stale state. Ported from PeelCore's Engine.swift
    /// (`run`, which always both commits and publishes); its
    /// message/contact/tribe/payment bookkeeping is not here.
    suspend fun run(call: (time: String, state: ByteArray) -> RunReturn): RunReturn = mutex.withLock {
        val time = clock.next()
        val result = call(time, StateCodec.encode(state))
        commitLocked(result, time)
        publishLocked(result)
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

    /// The publish half of `run`: whatever a result's `subscriptionTopics`,
    /// `registerTopic`/`registerPayload` and generic `topics`/`payloads`
    /// ask for, in that order. Ported from PeelCore's `Engine.publish(_
    /// result:)`, trimmed to its "now" case — the `later`/`afterPay`
    /// timing `publishPlan` otherwise adds is specific to money sends,
    /// which nothing here makes yet. A no-op before any connection exists
    /// (`transport` is nil): `initialSetup` run offline during onboarding
    /// has nothing to publish to.
    private suspend fun publishLocked(result: RunReturn) {
        val connection = transport ?: return
        if (result.subscriptionTopics.isNotEmpty()) connection.subscribe(result.subscriptionTopics)
        val registerTopic = result.registerTopic
        val registerPayload = result.registerPayload
        if (registerTopic != null && registerPayload != null) connection.publish(registerTopic, registerPayload)
        for (i in result.topics.indices) {
            val payload = result.payloads.getOrNull(i) ?: continue
            connection.publish(result.topics[i], payload)
        }
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
    /// remembers). Ported from PeelCore's `Engine.establish()`: a fresh
    /// signed-timestamp login, `initialSetup` and the topics its
    /// `RunReturn` names, then up to [registerWaitMs] of whatever the
    /// broker sends back. Starts the one persistent inbound listener
    /// (`startListening`) this engine ever has — not just for the
    /// register wait: `askAndAwait` (the restore pipeline) depends on it
    /// still running long after this function returns. Everything
    /// `establish()` does past registering — `cameOnline`'s ping/read-sync
    /// half, the keep-alive loop, reconnect-on-drop — is not here.
    suspend fun connect(server: ServerAddress, inviteCode: String? = null): RunReturn {
        val setup = mutex.withLock {
            val networkTime = clock.next()
            commitLocked(coreSetNetwork(network), networkTime)

            val loginTime = clock.next()
            val xpub = coreXpubFromSeed(seed, loginTime, network)
            val signature = coreRootSignMs(seed, loginTime, network)
            val connection = Transport(server, clientID = xpub, username = loginTime, password = signature)
            connection.connect()
            transport = connection
            // Before the register publish, not after: the same small race
            // this already had (a very fast answer could still beat the
            // collector actually attaching), not a new one.
            startListening()

            val setupTime = clock.next()
            val setup = coreInitialSetup(seed, setupTime, StateCodec.encode(state), deviceID, inviteCode)
            commitLocked(setup, setupTime)
            publishLocked(setup)

            val tribeTopicTime = clock.next()
            val tribeTopic = coreGetTribeManagementTopic(seed, tribeTopicTime, StateCodec.encode(state))
            connection.subscribe(listOf(tribeTopic))

            setup
        }

        withTimeoutOrNull(registerWaitMs) { inboundResults.first { isRegistered } }
        return setup
    }

    /// Starts the one collector `Transport.inbound()` ever gets for this
    /// engine: each message goes through the core's `handle` and gets
    /// committed like any other result, then broadcast on
    /// [inboundResults]. Idempotent, and safe to call while already
    /// holding `mutex` — it only launches the collector, the collector
    /// itself acquires the lock per message, same as any other caller.
    private fun startListening() {
        if (listenerStarted) return
        listenerStarted = true
        val connection = transport ?: return
        scope.launch {
            connection.inbound().collect { message ->
                val handled = mutex.withLock {
                    val handleTime = clock.next()
                    val result = coreHandle(
                        message.topic, message.payload, seed, handleTime, StateCodec.encode(state),
                        storage.value(Setting.ALIAS) ?: "", storage.value(Setting.PHOTO_URL) ?: "",
                    )
                    commitLocked(result, handleTime)
                    result
                }
                inboundResults.emit(handled)
            }
        }
    }

    /// Sends one ask (under the lock, like any `run`) and waits, outside
    /// it, for an inbound result matching `expect` — the shape every
    /// restore call takes: the real answer is not the ask's own return
    /// value, it is whatever the server publishes back later. Null on
    /// [timeoutMs] with nothing matching.
    private suspend fun askAndAwait(
        timeoutMs: Long,
        expect: (RunReturn) -> Boolean,
        ask: suspend () -> RunReturn,
    ): RunReturn? = withTimeoutOrNull(timeoutMs) {
        coroutineScope {
            val waiter = async { inboundResults.first(expect) }
            ask()
            waiter.await()
        }
    }

    suspend fun disconnect() = mutex.withLock {
        transport?.close()
        transport = null
    }

    /// Stops whatever `start` has running — "Forget this account" needs
    /// this so a wiped `Storage` is never written to by a restore page
    /// still in flight.
    fun cancel() = scope.cancel()

    /// Resolves the broker from the public config and connects, then —
    /// only for an account restored from its words with nothing local yet
    /// (`Setting.NEEDS_RESTORE`) — pages through its history. Runs on
    /// [scope], not the caller's: call this once, right after the engine
    /// is made, and read [connectionState]/[restoreProgress] from
    /// wherever needs them. Mirrors PeelUI's `AppModel.attach` +
    /// `Engine.start()`, minus the keep-alive/reconnect loop and the
    /// ping/read-sync half of `cameOnline` — this gets an account
    /// connected and, if needed, restored; staying online is later work.
    fun start() {
        if (started) return
        started = true
        scope.launch {
            connectionStateFlow.value = ConnectionState.Connecting
            try {
                val config = ServerConfig.fetch(network)
                val server = ServerAddress.named(config.defaultLsp)
                connect(server)
                connectionStateFlow.value = if (isRegistered) {
                    ConnectionState.Connected
                } else {
                    ConnectionState.Failed("No registration answer came back in time.")
                }
            } catch (e: Exception) {
                connectionStateFlow.value = ConnectionState.Failed(e.message ?: "Could not connect to the server.")
                return@launch
            }
            if (restoreProgressFlow.value.restoring) runRestore()
        }
    }

    private enum class HistoryPhase { CONTACTS, MESSAGES }

    /// What `getMsgsCounts` reports, mirrored from PeelCore's
    /// `Engine.MessageCounts`.
    private data class MessageCounts(
        val total: Long,
        val firstForEachKey: Long,
        val totalHighestIndex: Long?,
        val firstForEachKeyHighestIndex: Long?,
    )

    /// Contacts first, then every message from the beginning — PeelCore's
    /// `HistoryCursor.plan`, minus the live/catch-up/stall machinery
    /// `HistoryCursor` otherwise carries: this is a single straight-through
    /// restore, not a resumable general-purpose history cursor.
    private suspend fun runRestore() {
        try {
            val countsAnswer = askAndAwait(historyWaitMs, expect = { it.msgsCounts != null }) {
                run { time, stateBytes -> coreGetMsgsCounts(seed, time, stateBytes) }
            }
            val counts = parseCounts(countsAnswer?.msgsCounts)
            if (counts != null && counts.firstForEachKey > 0) {
                page(HistoryPhase.CONTACTS, counts.firstForEachKeyHighestIndex) { time, stateBytes, next ->
                    coreFetchFirstMsgsPerKey(seed, time, stateBytes, next, HISTORY_PAGE_SIZE, false)
                }
            }
            if (counts != null && counts.total > 0) {
                page(HistoryPhase.MESSAGES, counts.totalHighestIndex) { time, stateBytes, next ->
                    coreFetchMsgsBatch(seed, time, stateBytes, next, HISTORY_PAGE_SIZE, false)
                }
            }
            storage.apply(Storage.Commit(settings = mapOf(Setting.NEEDS_RESTORE to "0")))
            restoreProgressFlow.update { it.copy(restoring = false, outcome = RestoreOutcome.Finished) }
        } catch (e: Exception) {
            restoreProgressFlow.update { it.copy(outcome = RestoreOutcome.Failed(e.message ?: "Restoring stopped.")) }
        }
    }

    /// One phase's pages, back to back, each one committed (state, the
    /// messages it carried, the contacts they reveal) before the next is
    /// asked for. Stops on no answer in [historyWaitMs] (PeelCore's
    /// watchdog, simplified to "stop" rather than "resume later"), an
    /// empty page (the server has nothing more for this phase), or once
    /// `next` passes `end`.
    private suspend fun page(phase: HistoryPhase, end: Long?, call: (String, ByteArray, ULong) -> RunReturn) {
        var next = 0uL
        while (true) {
            val result = askAndAwait(historyWaitMs, expect = { it.msgsTotal != null }) {
                run { time, stateBytes -> call(time, stateBytes, next) }
            } ?: break
            val rows = result.msgs.mapNotNull { mapMessage(it, accountKey) }
            if (rows.isNotEmpty()) storage.insertMessages(rows)
            for (row in rows) if (!row.fromMe) contactHint(row)?.let { storage.upsertContact(it.pubkey, it.alias, it.photoUrl) }
            if (phase == HistoryPhase.CONTACTS) {
                storage.markContactsReady(parseContactPubkeys(coreListContacts(StateCodec.encode(state))))
            }
            val reached = result.msgs.mapNotNull { it.index?.toULongOrNull() }.maxOrNull()
            val progress = RestoreCount((reached ?: next).toLong(), end)
            restoreProgressFlow.update {
                if (phase == HistoryPhase.CONTACTS) it.copy(contacts = progress) else it.copy(messages = progress)
            }
            if (reached == null) break
            next = reached + 1uL
            if (end != null && next > end.toULong()) break
        }
    }

    /// One core `Msg` as a stored row, or null for one this device cannot
    /// file anywhere. Trimmed from PeelCore's `Engine.message(from:...)`:
    /// the `fromMe` chat check is the same (a copy of ours names who it
    /// went to, and must be signed by us or by that chat itself); an
    /// incoming message's claimed sender is trusted directly rather than
    /// checked against `state["c/<pubkey>"]` first, since this only runs
    /// over a restore's own authenticated history fetch, not a live,
    /// unauthenticated publish stream the way PeelCore's check guards
    /// against. No tribe membership, replay or reply/thread handling yet.
    private fun mapMessage(msg: Msg, ownKey: String): MessageRow? {
        val type = msg.type ?: return null
        val fromMe = msg.fromMe ?: false
        val sender = msg.sender?.let { runCatching { JSONObject(it) }.getOrNull() }
        val claimed = sender?.optString("pubkey")?.takeIf { it.isNotEmpty() }
        val chat = if (fromMe) {
            val to = msg.sentTo ?: claimed?.takeIf { it != ownKey } ?: return null
            if (claimed != ownKey && claimed != to) return null
            to
        } else {
            claimed ?: return null
        }
        return MessageRow(
            uuid = msg.uuid,
            serverIndex = msg.index?.toLongOrNull(),
            type = type.toInt(),
            chat = chat,
            fromMe = fromMe,
            sender = msg.sender,
            body = msg.message,
            msat = msg.msat?.toLong(),
            timestamp = msg.timestamp?.toLong(),
            tag = msg.tag,
            paymentHash = msg.paymentHash,
        )
    }

    /// A contact's name/photo, if `row` is their own message naming
    /// themself — the same shape PeelCore's `contact` v2 migration derives
    /// from the message table (`sender.pubkey == chat`).
    private fun contactHint(row: MessageRow): ContactRow? {
        val sender = row.sender?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val pubkey = sender.optString("pubkey").takeIf { it.isNotEmpty() } ?: return null
        if (pubkey != row.chat) return null
        return ContactRow(
            pubkey = pubkey,
            alias = sender.optString("alias").takeIf { it.isNotEmpty() },
            photoUrl = sender.optString("photo_url").takeIf { it.isNotEmpty() },
            ready = false,
        )
    }

    private fun parseCounts(json: String?): MessageCounts? {
        if (json == null) return null
        val obj = JSONObject(json)
        return MessageCounts(
            total = obj.optLong("total"),
            firstForEachKey = obj.optLong("first_for_each_scid"),
            totalHighestIndex = if (obj.has("total_highest_index")) obj.optLong("total_highest_index") else null,
            firstForEachKeyHighestIndex =
                if (obj.has("first_for_each_scid_highest_index")) obj.optLong("first_for_each_scid_highest_index") else null,
        )
    }

    private fun parseContactPubkeys(json: String): List<String> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.optString("pubkey")?.takeIf { it.isNotEmpty() }
        }
    }

    companion object {
        private const val registerWaitMs = 10_000L

        /// How long one history ask (counts, or a page) waits for its
        /// answer — PeelCore's own watchdog is 30s (`Engine.historyStall`).
        private const val historyWaitMs = 30_000L
        private val HISTORY_PAGE_SIZE: UInt = 100u
    }
}
