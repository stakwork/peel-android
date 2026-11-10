package com.sphinx.peel.engine

import android.os.SystemClock
import android.util.Log
import com.sphinx.peel.account.Account
import com.sphinx.peel.account.PinTimeout
import com.sphinx.peel.storage.CONTACT_KEY_MESSAGE_TYPE
import com.sphinx.peel.push.PushApp
import com.sphinx.peel.storage.ContactRow
import com.sphinx.peel.storage.MessageRow
import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import uniffi.sphinxrs.Msg
import uniffi.sphinxrs.RunReturn
import uniffi.sphinxrs.agentSeed as coreAgentSeed
import uniffi.sphinxrs.fetchFirstMsgsPerKey as coreFetchFirstMsgsPerKey
import uniffi.sphinxrs.fetchMsgsBatch as coreFetchMsgsBatch
import uniffi.sphinxrs.getContact as coreGetContact
import uniffi.sphinxrs.getMsgsCounts as coreGetMsgsCounts
import uniffi.sphinxrs.getMutes as coreGetMutes
import uniffi.sphinxrs.mute as coreMute
import uniffi.sphinxrs.getReads as coreGetReads
import uniffi.sphinxrs.getTags as coreGetTags
import uniffi.sphinxrs.getTribeManagementTopic as coreGetTribeManagementTopic
import uniffi.sphinxrs.handle as coreHandle
import uniffi.sphinxrs.initialSetup as coreInitialSetup
import uniffi.sphinxrs.listContacts as coreListContacts
import uniffi.sphinxrs.makeMediaToken as coreMakeMediaToken
import uniffi.sphinxrs.nodeKeys as coreNodeKeys
import uniffi.sphinxrs.pubkeyFromSeed
import uniffi.sphinxrs.read as coreMarkRead
import uniffi.sphinxrs.rootSignMs as coreRootSignMs
import uniffi.sphinxrs.send as coreSend
import uniffi.sphinxrs.signBase64 as coreSignBase64
import uniffi.sphinxrs.setNetwork as coreSetNetwork
import uniffi.sphinxrs.setPushTokenForApp as coreSetPushTokenForApp
import uniffi.sphinxrs.unregisterPushTokenForApp as coreUnregisterPushTokenForApp
import uniffi.sphinxrs.readAll as coreReadAll
import uniffi.sphinxrs.xpubFromSeed as coreXpubFromSeed

/// Drives one account against the Sphinx core: the engine foundation
/// described in `peel`'s docs/PLAN.md ("The core contract" — each call
/// takes the seed, a unique time and the whole state, and returns what
/// changed). Ported from PeelCore's Engine.swift, scoped to what Storage
/// holds so far (`coreState`, `setting`, `contact`, `message`): no tribe
/// membership, payments or reply/thread handling, so `run` and `connect`
/// here commit a fraction of what the Mac/iPhone `commit` does.
class Engine(mnemonic: CharArray, val storage: Storage) {
    /// The native core takes the seed as a `String`, so one lives as long as
    /// the engine does; the words it came from are zeroed here, right away.
    val seed: String = try { Account.seedOf(mnemonic) } finally { mnemonic.fill('\u0000') }
    constructor(mnemonic: String, storage: Storage) : this(mnemonic.toCharArray(), storage)

    val network: String = storage.value(Setting.NETWORK) ?: "bitcoin"

    /// Guards `state` and `clock` the way Swift's `actor` isolates Engine:
    /// only one core call runs at a time, and the next one always sees the
    /// previous one's committed state. `state` is also read from
    /// [isRegistered] by callers that are not holding the lock (the
    /// register wait in `connect`), so it needs to be visible across
    /// threads on its own; `@Volatile` gives it that without needing the
    /// lock just to read it.
    private val mutex = Mutex()

    /// One history fetch or invoice request at a time: the server's answer
    /// names no request, so a second ask while one is out could be taken as
    /// the first's answer (PeelCore's `money.awaitingPayments`/`awaitingInvoice`).
    internal val moneyLock = Mutex()

    /// Payment hashes being paid right now: a second tap on Pay is refused.
    internal val sendsInFlight: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
    private val clock = UniqueClock(storage.value(Setting.CLOCK)?.toLongOrNull() ?: 0)
    @Volatile private var state: Map<String, ByteArray> = storage.loadCoreState()
    private var transport: Transport? = null
    private var listenerStarted = false

    /// The invite a new account signed up with, waiting to be registered with
    /// and then used to add its inviter (`Setting.INVITE`).
    internal val pendingInvites: PendingInviteStore = SettingInviteStore(storage)

    /// A register naming an invite may have reached the server: the account
    /// may exist and hold the invite's sats (`Signup`'s keep-or-delete rule).
    @Volatile var inviteMayBeRedeemed = false
        private set

    /// This connection registered with an invite: the sats it credits are not
    /// in the balance until something asks (`afterConnect`).
    @Volatile private var registeredWithInvite = false

    private val inviterOutcomeFlow = MutableStateFlow<InviterFlow.Outcome?>(null)

    /// What the last attempt to add the inviter came to, for a screen to say so
    /// (`Failed`/`WillRetry` are the ones worth showing).
    val inviterOutcome: StateFlow<InviterFlow.Outcome?> = inviterOutcomeFlow.asStateFlow()

    /// Tags of sends whose async pay the server has not answered yet (false), or has (true):
    /// `publishOrderedLocked`'s wait for it (PeelCore's `money.asyncPays`).
    private val asyncPays = ConcurrentHashMap<String, Boolean>()

    /// Invite codes being cancelled right now: the server answers a cancel with the balance
    /// alone, so one at a time (`cancelInvite`).
    internal val cancelsInFlight: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    internal fun publishInviterOutcome(outcome: InviterFlow.Outcome) {
        inviterOutcomeFlow.value = outcome
    }
    /// True from `start()` until `cancel()`: whether a drop should trigger
    /// [reconnectLoop] at all (PeelCore's own `keepConnected`).
    @Volatile private var keepConnected = false
    private var reconnectJob: Job? = null
    /// The memes server's bearer token, kept until shortly before it
    /// expires — `downloadAttachment`'s own sign-in, independent of the
    /// MQTT connection (`withMemes`).
    private var memesToken: MemesServer.Token? = null

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
    /// A restore is paging right now: a second `start`/`resume` must not run another.
    @Volatile private var restoreRunning = false

    /// Called after each successful (re)connect, once registered and caught
    /// up — where `PushRegistrar` registers the push token (iOS:
    /// `PushCoordinator.connected()`).
    @Volatile var onConnected: (() -> Unit)? = null

    /// Called with each message the live listener receives (not sent ones),
    /// for `MessageNotifier`.
    @Volatile var onIncoming: ((List<MessageRow>) -> Unit)? = null

    private val connectionStateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = connectionStateFlow.asStateFlow()

    /// Why the last connect attempt failed (shown on [ConnectionState.Offline]); cleared on success.
    @Volatile private var lastConnectError: String? = null

    private fun setConnection(state: ConnectionState, reason: String) {
        val old = connectionStateFlow.value
        Log.i(PeelLog.TAG, "connection: $old -> $state ($reason)")
        connectionStateFlow.value = state
    }

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

    /// This account's agent — a second Sphinx account the same twelve
    /// words derive (HKDF over this seed, "agent seed 1"), which every
    /// device of this account already has a contact record for from
    /// signup. Ported from `Engine+AgentAccount.swift`'s `agentPubkey()`:
    /// just the key, to find its chat among `conversations()` and show it
    /// specially — not `agentAccount()`'s full bridge/device-request setup,
    /// which is its own much larger feature.
    val agentPubkey: String = pubkeyFromSeed(coreAgentSeed(seed, 1u), 0u, clock.next(), network)

    /// Runs one core call against the current state, commits what it
    /// returns, and publishes whatever network work the result asks for
    /// (its subscriptions, its register, any topic/payload pairs) —
    /// before this suspends again, so no other call on this engine ever
    /// runs against stale state. Ported from PeelCore's Engine.swift
    /// (`run`, which always both commits and publishes); its
    /// message/contact/tribe/payment bookkeeping is not here.
    ///
    /// `beforePublish` runs after the commit and before anything is
    /// published, still under the lock: a payment writes its own pending
    /// record there, so a send that may have gone is never unrecorded
    /// (PeelCore's `commitAndPublish(invoices:)`).
    ///
    /// `ordered` publishes in PeelCore's `publishPlan` order (the settle and
    /// register first, a new key's messages after its register has landed, an
    /// async pay's after the server took it) instead of everything at once:
    /// what a key exchange and an invite's payment need. Off for every
    /// caller from before those existed, whose behaviour is unchanged.
    suspend fun run(
        beforePublish: (RunReturn) -> Unit = {},
        ordered: Boolean = false,
        call: (time: String, state: ByteArray) -> RunReturn,
    ): RunReturn {
        var afterPay: AfterPay? = null
        val result = mutex.withLock {
            val time = clock.next()
            val result = call(time, StateCodec.encode(state))
            commitLocked(result, time)
            beforePublish(result)
            if (ordered) afterPay = publishOrderedLocked(result, waitForAsyncPay = true) else publishLocked(result)
            result
        }
        // Outside the lock: the server's answer to the async pay is handled by the listener, which needs it.
        afterPay?.let { finishAfterPay(it) }
        return result
    }

    /// What is left to publish once the server has taken an async pay.
    private class AfterPay(val publishes: List<Pair<String, ByteArray>>, val tags: Set<String>)

    private fun publishesOf(result: RunReturn): PublishPlan = PublishPlan.of(
        settle = result.settleTopic?.let { t -> result.settlePayload?.let { t to it } },
        register = result.registerTopic?.let { t -> result.registerPayload?.let { t to it } },
        asyncPay = result.asyncpayTopic?.let { t -> result.asyncpayPayload?.let { t to it } },
        topics = result.topics.indices.map { result.topics[it] to (result.payloads.getOrNull(it) ?: ByteArray(0)) },
        newChildKey = result.newChildIdx != null,
    )

    /// [publishLocked] in [PublishPlan] order: what goes now is published, the rest of the
    /// plan up to the wait for the async pay too; what follows the pay is returned for
    /// [finishAfterPay] to publish once the lock is free. Under the lock like `publishLocked`.
    private suspend fun publishOrderedLocked(result: RunReturn, waitForAsyncPay: Boolean): AfterPay? {
        val connection = transport ?: throw NotConnectedException()
        val plan = publishesOf(result)
        // Listed before the async pay goes, so an answer that comes first counts.
        val paying = if (waitForAsyncPay && plan.afterPay.isNotEmpty()) result.msgs.mapNotNull { it.tag }.toSet() else emptySet()
        for (tag in paying) asyncPays[tag] = false
        try {
            if (result.subscriptionTopics.isNotEmpty()) connection.subscribe(result.subscriptionTopics)
            for ((topic, payload) in plan.now) connection.publish(topic, payload)
            if (plan.later.isNotEmpty()) {
                delay(REGISTER_SETTLE_MS)
                for ((topic, payload) in plan.later) connection.publish(topic, payload)
            }
        } catch (e: Exception) {
            for (tag in paying) asyncPays.remove(tag)
            throw e
        }
        return if (plan.afterPay.isEmpty()) null else AfterPay(plan.afterPay, paying)
    }

    /// Waits (at most [ASYNC_PAY_SETTLE_MS]) for the server to answer the async pay, then publishes the rest on
    /// whatever connection there is by then. A wait that runs out still publishes: the state is committed
    /// and the payment may be held, as the official apps do.
    private suspend fun finishAfterPay(after: AfterPay) {
        try {
            if (after.tags.isNotEmpty()) {
                withTimeoutOrNull(ASYNC_PAY_SETTLE_MS) {
                    while (after.tags.none { asyncPays[it] == true }) delay(100)
                }
            }
            val connection = transport ?: throw NotConnectedException()
            for ((topic, payload) in after.publishes) connection.publish(topic, payload)
        } finally {
            for (tag in after.tags) asyncPays.remove(tag)
        }
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
        result.asyncpayTag?.let { tag -> if (asyncPays.containsKey(tag)) asyncPays[tag] = true }

        // Any call's `RunReturn` can report a new balance, not just a
        // send's — PeelCore's own `Engine.swift` commits `newBalance` the
        // same unconditional way, right alongside the state/clock every
        // result already carries. Its own `followUps.reconcile` (checking
        // a jump against an open invoice) is real Payments-feature work
        // this port has none of yet; storing the number itself needed
        // nothing from that to be worth doing — it's what lets Profile
        // show a real balance instead of nothing at all.
        val settings = mutableMapOf(Setting.CLOCK to time)
        result.newBalance?.let {
            settings[Setting.BALANCE_MSAT] = it.toString()
            balanceFlow.value = it.toLong()
        }

        storage.apply(
            Storage.Commit(
                state = updates,
                deleteState = result.stateToDelete,
                settings = settings,
            ),
        )
    }

    /// The account's own balance, as last reported by any core call —
    /// never asked for on its own (no such call is made for this port
    /// yet), only ever updated as a side effect of `commitLocked`. Null
    /// before the first call that reports one.
    fun balanceMsat(): Long? = storage.value(Setting.BALANCE_MSAT)?.toLongOrNull()

    private val balanceFlow = MutableStateFlow(storage.value(Setting.BALANCE_MSAT)?.toLongOrNull())

    /// The balance as it moves: what the Payments tab and a payment's own
    /// checks watch (PeelUI's `AppModel.balanceMsat`). Null until a core call has reported one.
    val balance: StateFlow<Long?> = balanceFlow.asStateFlow()

    /// Whether a broker connection exists to publish to.
    internal val isConnected: Boolean get() = transport != null

    /// The broker this account last connected to, as `host:port` with its
    /// scheme (`ServerAddress.toString`) — PeelCore's own `Engine.setServer`,
    /// persisted the same way so Profile's "Server" row survives a restart
    /// without a live connection. Null before the first successful connect.
    fun serverLabel(): String? = storage.value(Setting.SERVER)

    /// This account's call server, `host[:port]` — PeelCore's own
    /// `Engine.callServer()`/`configuredHost`: the one explicitly
    /// recorded (`Setting.CALL_SERVER`, no settings screen writes this
    /// yet), else the official apps' own `chat.sphinx.chat`, except when
    /// connected to a local test server (loopback), which has none
    /// unless configured — a local LSP has no matching call server by
    /// default. Null, not an exception: callers (a call-link check, a
    /// "Start a call" button) treat "no call server" as "calls are off
    /// for this account", not a failure.
    fun callServer(): String? {
        storage.value(Setting.CALL_SERVER)?.let { return it }
        val connected = storage.value(Setting.SERVER)?.let { ServerAddress.parse(it) } ?: return null
        return if (ServerAddress.isLoopback(connected.host)) null else DEFAULT_CALL_SERVER
    }

    /// The engine's one connection to its call server, made again when the
    /// link names a different one (the setting changed) — PeelCore's own
    /// `callConnection(for:)`, trimmed: no `Failure.callOnAnotherServer`
    /// check, since [link] only ever comes from [CallLink.inMessage]/
    /// [CallLink.new], both of which already took `callServer()` as their
    /// own `server` argument, so a mismatch can't reach here.
    private var calls: CallServer? = null

    /// The engine's one connection to its call server, made again when
    /// [link] names a different one (the setting changed) — PeelCore's own
    /// `callConnection(for:)`, trimmed: no `Failure.callOnAnotherServer`
    /// check, since [link] only ever comes from [CallLink.inMessage]/
    /// [CallLink.new], both of which already took `callServer()` as their
    /// own `server` argument, so a mismatch can't reach here.
    private fun callConnection(link: CallLink): CallServer {
        val existing = calls
        if (existing != null && existing.host == link.server) return existing
        existing?.close()
        return CallServer(link.server).also { calls = it }
    }

    /// Who is on `link`'s call, from the call server's presence feed, until
    /// the collector stops — PeelCore's own `Engine.callPresence`.
    fun callPresence(link: CallLink): Flow<CallPresence> = callConnection(link).presence(link.room)

    /// A ticket to join `link`'s call: the LiveKit server and a token, asked
    /// of this account's call server under this account's own name and
    /// photo — PeelCore's own `Engine.callTicket(for:in:)`, trimmed: no
    /// tribe-specific sent-alias/sent-photo resolution (nothing else this
    /// port sends uses one either — `sendMessage`/`sendBoost`/`startCall`
    /// all post under the plain account alias, tribe or not), just the same
    /// "a blank name joins as 'Peel user', not the tribes registry's own
    /// 'Unknown'" fallback PeelCore's own version keeps regardless.
    suspend fun callTicket(link: CallLink): CallTicket {
        val alias = storage.value(Setting.ALIAS)?.trim()?.takeIf { it.isNotEmpty() } ?: "Peel user"
        val photo = storage.value(Setting.PHOTO_URL)?.takeIf { it.startsWith("https://") }
        return callConnection(link).ticket(link, alias, photo)
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
        Log.d(PeelLog.TAG, "connect: ${server.host}:${server.port} tls=${server.tls} invite=${inviteCode != null}")
        val connectStartedAt = SystemClock.elapsedRealtime()
        val setup = mutex.withLock {
            val networkTime = clock.next()
            commitLocked(coreSetNetwork(network), networkTime)

            val loginTime = clock.next()
            val xpub = coreXpubFromSeed(seed, loginTime, network)
            val signature = coreRootSignMs(seed, loginTime, network)
            val connection = Transport(
                server, clientID = xpub, username = loginTime, password = signature,
                onUnexpectedDisconnect = ::onConnectionDropped,
            )
            connection.connect()
            transport = connection
            // Before the register publish, not after: the same small race
            // this already had (a very fast answer could still beat the
            // collector actually attaching), not a new one.
            startListening()

            val setupTime = clock.next()
            val setup = coreInitialSetup(seed, setupTime, StateCodec.encode(state), deviceID, inviteCode)
            commitLocked(setup, setupTime)
            // Set before the publish, not after: a register that failed part-way may still have gone.
            if (inviteCode != null) inviteMayBeRedeemed = true
            publishLocked(setup)
            Log.d(PeelLog.TAG, "connect: register publish sent")

            val tribeTopicTime = clock.next()
            val tribeTopic = coreGetTribeManagementTopic(seed, tribeTopicTime, StateCodec.encode(state))
            connection.subscribe(listOf(tribeTopic))

            setup
        }

        // An invite's register is waited for as long as PeelCore does (a refused one is never answered).
        val waitMs = if (inviteCode != null) INVITE_REGISTER_WAIT_MS else registerWaitMs
        Log.d(PeelLog.TAG, "connect: waiting for isRegistered, timeout ${waitMs / 1000.0}s (already registered=$isRegistered)")
        val answered = withTimeoutOrNull(waitMs) { inboundResults.first { isRegistered } }
        Log.d(
            PeelLog.TAG,
            "connect: register wait ${if (answered != null) "succeeded" else "TIMED OUT"} after ${SystemClock.elapsedRealtime() - connectStartedAt}ms " +
                "isRegistered=$isRegistered my_scid present=${state["my_scid"] != null} server_pubkey present=${state["server_pubkey"] != null}",
        )
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
                val startedAt = SystemClock.elapsedRealtime()
                val handled = mutex.withLock {
                    val handleTime = clock.next()
                    val result = coreHandle(
                        message.topic, message.payload, seed, handleTime, StateCodec.encode(state),
                        storage.value(Setting.ALIAS) ?: "", storage.value(Setting.PHOTO_URL) ?: "",
                    )
                    commitLocked(result, handleTime)
                    answerKeyExchange(result)
                    result
                }
                // Every inbound message, live or a restore's own page answer
                // alike: whichever `askAndAwait` is waiting on this one (if
                // any) reads the same already-stored result once it gets it
                // below, not a second copy — `storeMessages` is the one
                // place this happens now.
                val handledAt = SystemClock.elapsedRealtime()
                storeMessages(handled)
                if (handled.msgs.isNotEmpty()) {
                    Log.d("PeelHistory", "${handled.msgs.size} msgs: core handle+commit ${handledAt - startedAt}ms, store ${SystemClock.elapsedRealtime() - handledAt}ms")
                    onIncoming?.let { notify ->
                        val rows = handled.msgs.mapNotNull { mapMessage(it, accountKey) }.filter { !it.fromMe }
                        if (rows.isNotEmpty()) runCatching { notify(rows) }
                    }
                }
                inboundResults.emit(handled)
            }
        }
    }

    /// The core's reply to someone adding this account (their type-10 message): the key
    /// confirmation it made, and the sats it pays back. `handle` returns them but sends
    /// nothing, so without publishing them the other side never gets this account's key.
    /// Only for a live key exchange: never while a restore replays history (PeelCore
    /// publishes every handled result; this port does so only where it is needed, see
    /// docs/phases/21-invites.md). Under the lock, like the commit it follows. A failure
    /// is logged, not thrown: it must not end the listener.
    private suspend fun answerKeyExchange(result: RunReturn) {
        if (restoreProgressFlow.value.restoring) return
        if (result.msgs.none { it.type?.toInt() == CONTACT_KEY_MESSAGE_TYPE && it.fromMe != true }) return
        try {
            publishOrderedLocked(result, waitForAsyncPay = false)?.let { finishAfterPay(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("PeelEngine", "answering a key exchange failed", e)
        }
    }

    /// Marks everyone the core now holds as a contact ready, and tells the dashboard's cache of who is a person.
    internal fun markPeopleReady() {
        runCatching {
            refreshPersonKeys()
            storage.markContactsReady(personKeys.toList())
        }.onFailure { Log.w("PeelEngine", "listing contacts failed", it) }
    }

    /// `result.msgs` as rows, stored — and, for whichever of them are an
    /// incoming message naming its own sender, the contact that reveals.
    /// Shared by the live listener and a restore's pages: a message is a
    /// message either way.
    private fun storeMessages(result: RunReturn) {
        val rows = result.msgs.mapNotNull { mapMessage(it, accountKey) }
        if (rows.isNotEmpty()) {
            // Messages and the contacts they reveal in one transaction.
            storage.insertMessages(rows, rows.mapNotNull { contactHint(it) })
            // A key exchange (someone adding this account, or its answer) makes a contact the
            // core holds: ready, so the chat list shows them. Not while restoring, which
            // marks everyone once from its contacts run.
            if (rows.any { it.type == CONTACT_KEY_MESSAGE_TYPE || it.type == CONTACT_KEY_CONFIRMATION_MESSAGE_TYPE } &&
                !restoreProgressFlow.value.restoring
            ) markPeopleReady()
        }
        // The mixer's one live word on a tag this device sent under —
        // PeelCore's `liveStatus`: FAILED as it comes, anything else
        // (just COMPLETE here) delivered. Arrives on whatever `RunReturn`
        // carries it, restore pages included, same as `msgs` does; most
        // sends never need this at all, since their own echo already
        // marks the row delivered the moment it comes back through `msgs`.
        val sentStatus = result.sentStatus?.let { runCatching { JSONObject(it) }.getOrNull() }
        val tag = sentStatus?.optString("tag")?.takeIf { it.isNotEmpty() }
        if (tag != null) {
            SendStatus.live(sentStatus.optString("status"))?.let { live ->
                storage.markSendStatus(tag, live)
                // Paying an invoice is tracked by its tag the same way.
                if (live != SendStatus.UNSETTLED) {
                    storage.markInvoiceSendStatus(tag, live == SendStatus.DELIVERED, SendStatus.reason(sentStatus))
                }
            }
        }
        // The mixer's records for the tags `getTags` asked about
        // (`checkOwnMessageStatus`, `checkPendingSends`): COMPLETE or
        // FAILED only, through the same forward-only rule.
        val records = SendStatus.recorded(result.tags)
        for (record in records) {
            storage.markSendStatus(record.tag, record.status)
            storage.markInvoiceSendStatus(record.tag, record.status == SendStatus.DELIVERED, record.error)
        }
        if (result.tags != null) {
            val answered = runCatching { JSONArray(result.tags) }.getOrNull()
            if (answered != null) {
                for (i in 0 until answered.length()) {
                    val named = answered.optJSONObject(i)?.optString("tag")?.takeIf { it.isNotEmpty() } ?: continue
                    ownStatusHeard.add(named)
                    // Any status: a send the server names reached it.
                    resendGate?.name(named)
                }
            }
            resendAnswers++
        }
        storeMoney(result, rows)
        // Read markers and mutes, same "whatever `RunReturn` carries it"
        // reasoning as `sentStatus` — PeelCore's `receive(_:)` special-
        // cases the `"getreads"` topic kind to re-trigger
        // `publishPendingReads`; this port has no per-topic dispatch at
        // all (`startListening` reads whatever a `RunReturn` carries,
        // generically), so `fetchReadsAndMutes` triggers that itself once
        // its own `askAndAwait` returns, rather than from here.
        parseIndexMap(result.lastRead)?.let { reads ->
            storage.applyServerReads(reads, except = synchronized(readsSentSinceGetReads) { readsSentSinceGetReads.toSet() })
        }
        parseIndexMap(result.muteLevels)?.let { levels ->
            storage.applyServerMutes(
                levels.mapValues { it.value.toInt() },
                except = synchronized(mutesSetSinceGetMutes) { mutesSetSinceGetMutes.toSet() },
            )
        }
    }

    /// `{"<chat>": <index>}`, as both `getReads`'s and `getMutes`' answers
    /// are shaped (PeelCore's `Engine.reads(_:)`: absent entirely when the
    /// server has none at all).
    private fun parseIndexMap(json: String?): Map<String, Long>? {
        if (json == null) return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val map = mutableMapOf<String, Long>()
        for (key in obj.keys()) map[key] = obj.optLong(key)
        return map
    }

    /// Sends a plain text message to a contact or a tribe, optionally as
    /// a reply to one of its own stored messages (`replyTo`, its `uuid`)
    /// and/or into a thread (`inThread`, the thread's own root uuid —
    /// `ConversationScreen` works out which one the same way PeelCore's
    /// `links(replyTo:inThread:in:)` does, since this port has no
    /// storage-level `threadRoot` lookup of its own). Neither is checked
    /// against the real thing PeelCore's `links` does — any uuid goes in
    /// as given, since this port tracks no deletion and no thread
    /// membership to check against. Ported from PeelCore's
    /// `Engine+Messages.swift` (`sendText`/`deliver`), trimmed to one
    /// message with no device-request or payment-threshold handling —
    /// and no price: every send goes at 0 msat regardless of what a
    /// tribe actually charges, since nothing here reads a tribe's price yet
    /// (`TribeInfo.pricePerMessage`, not fetched); a tribe that charges
    /// for messages may refuse this until that exists.
    ///
    /// Stores the sent row itself, not through `storeMessages`: `send`'s
    /// own `RunReturn.msgs` entry is confirmation, not a message to run
    /// through `contactHint` — PeelCore's own `deliver` reads only its
    /// `uuid`/`tag`/`paymentHash` too, building the rest of the row from
    /// what it just sent (the body, the chat, now), not from that entry.
    /// Stored `PENDING`; moves to `DELIVERED` the moment its own echo
    /// comes back under the same `uuid` (`insertMessages`), or `FAILED`/
    /// `DELIVERED` from the mixer's own word on its `tag` if that arrives
    /// first (`storeMessages`'s `sentStatus` handling) — whichever comes.
    /// A send whose echo never arrives and whose tag the mixer never
    /// mentions again stays `PENDING`, but after 30 s the row offers Retry
    /// and Discard ([SendRetry], [resendMessage]) and a reconnect may send
    /// it again by itself ([resendRecent]).
    /// This account's [SphinxProof] (iOS `Engine.sphinxProof`), signed [now] by
    /// its account key (child 0) with the core's `signed_timestamp`. Signed
    /// half a minute back: Hive refuses a token from its future and takes one
    /// up to five minutes old.
    fun sphinxProof(now: Long = System.currentTimeMillis()): SphinxProof {
        val time = maxOf(0L, now - 30_000)
        return SphinxProof(accountKey, uniffi.sphinxrs.signedTimestamp(seed, 0u, time.toString(), network))
    }

    suspend fun sendMessage(chat: String, text: String, isTribe: Boolean, replyTo: String? = null, inThread: String? = null): MessageRow {
        check(transport != null) { "Not connected." }
        val amountMsat = if (isTribe) tribeMessageAmountMsat(chat) else 0L
        val body = JSONObject().put("content", text).apply {
            if (replyTo != null) put("replyUuid", replyTo)
            if (inThread != null) put("threadUuid", inThread)
            timeZoneMetadata(chat, isTribe)?.let { put("metadata", it) }
        }.toString()
        val (myAlias, myPhoto) = sentIdentity(chat, isTribe)
        val result = run { time, stateBytes ->
            coreSend(seed, time, chat, 0u, body, stateBytes, myAlias, myPhoto, amountMsat.toULong(), isTribe)
        }
        val sent = result.msgs.firstOrNull()
        val row = MessageRow(
            uuid = sent?.uuid,
            serverIndex = null,
            type = 0,
            chat = chat,
            fromMe = true,
            sender = null,
            body = body,
            msat = amountMsat.takeIf { it > 0 },
            timestamp = System.currentTimeMillis(),
            tag = sent?.tag,
            paymentHash = sent?.paymentHash,
            status = "PENDING",
        )
        storage.insertMessages(listOf(row))
        return row
    }

    /// The other side's zone in a direct message, from the newest offset they
    /// sent within 90 days that can be read.
    fun theirTimeZone(chat: String): java.time.ZoneId? =
        TimeZones.newest(storage.timeZoneBodies(chat, System.currentTimeMillis() - TimeZones.FRESHNESS_MS))

    /// What a tribe's member pays for an ordinary message (text or
    /// attachment): the owner's own price plus escrow, or the 3-sat
    /// floor if that's higher — PeelCore's own `Tribe.messageAmountMsat`/
    /// `TribePrice.message`. Every tribe message carries at least the
    /// floor, whether or not the tribe actually charges anything — the
    /// official apps' own convention, not something the tribes server
    /// requires. 0 for a DM, or a tribe whose price was never fetched
    /// (`ContactRow.pricePerMessageMsat`'s own doc comment) — treated
    /// the same as "no price", not an error.
    internal fun tribeMessageAmountMsat(chat: String): Long {
        val tribe = storage.contact(chat) ?: return MESSAGE_FLOOR_MSAT
        return saturatingTribePrice(tribe).coerceAtLeast(MESSAGE_FLOOR_MSAT)
    }

    /// What a boost pays a tribe on top of its own tip: price plus
    /// escrow, with no floor (unlike an ordinary message) — PeelCore's
    /// own `Tribe.paymentPriceMsat`.
    private fun tribePaymentPriceMsat(chat: String): Long {
        val tribe = storage.contact(chat) ?: return 0
        return saturatingTribePrice(tribe).coerceAtLeast(0)
    }

    /// `pricePerMessageMsat + escrowMsat`, saturating at `Long.MAX_VALUE`
    /// instead of wrapping — PeelCore's own `Tribe.messageAmount`'s
    /// `addingReportingOverflow`. Both fields come from the tribes
    /// registry (`TribeInfo.fetch`), not this account's own choice; a
    /// buggy or hostile registry answering with a price near
    /// `Long.MAX_VALUE` would otherwise wrap plain addition into a large
    /// *negative* sum, which `coerceAtLeast` would then quietly clamp
    /// back up to the floor — the wrong number for the wrong reason,
    /// even though it happens to land somewhere harmless here.
    private fun saturatingTribePrice(tribe: ContactRow): Long {
        val price = tribe.pricePerMessageMsat
        val escrow = tribe.escrowMsat
        return if (price > Long.MAX_VALUE - escrow) Long.MAX_VALUE else price + escrow
    }

    /// Boosts someone else's message: a type 29 message naming it by
    /// `replyUuid`, carrying this account's own boost amount
    /// (`boostAmountMsat()`) plus, in a tribe, its own price and escrow
    /// (`tribePaymentPriceMsat`, no floor) — PeelCore's own
    /// `quickBoost`, trimmed to its everyday one-tap path. No
    /// confirmation threshold, as PeelCore's one-tap boost has none; the
    /// balance is checked when known (`checkPayment`, `Payments.kt`), so a
    /// boost that can't be afforded is refused with words instead of failing at the server.
    suspend fun sendBoost(chat: String, targetUuid: String, isTribe: Boolean): MessageRow {
        check(transport != null) { "Not connected." }
        val amountMsat = boostAmountMsat() + (if (isTribe) tribePaymentPriceMsat(chat) else 0L)
        // One tap sends unasked (PeelCore's `.unasked`): only the balance
        // is checked, here when one is known (a ping is added in a DM).
        checkPayment(amountMsat, confirmedMsat = null, unasked = true, reserveMsat = if (isTribe) 0L else PING_MSAT)
        val body = JSONObject().apply {
            put("content", "")
            put("replyUuid", targetUuid)
        }.toString()
        val (myAlias, myPhoto) = sentIdentity(chat, isTribe)
        val result = run { time, stateBytes ->
            coreSend(seed, time, chat, BOOST_MESSAGE_TYPE.toUByte(), body, stateBytes, myAlias, myPhoto, amountMsat.toULong(), isTribe)
        }
        val sent = result.msgs.firstOrNull()
        val row = MessageRow(
            uuid = sent?.uuid,
            serverIndex = null,
            type = BOOST_MESSAGE_TYPE,
            chat = chat,
            fromMe = true,
            sender = null,
            body = body,
            msat = amountMsat,
            timestamp = System.currentTimeMillis(),
            tag = sent?.tag,
            paymentHash = sent?.paymentHash,
            status = "PENDING",
        )
        storage.insertMessages(listOf(row))
        return row
    }

    /// Starts a call in a contact's chat or a tribe: a new room with a
    /// random name on the call server, posted as a call message in the
    /// `call::` form — PeelCore's own `Engine.startCall`. A tribe takes
    /// only the 3-sat floor for it, never its own real per-message price
    /// (`tribeMessageAmountMsat`'s own price+escrow) — PeelCore's own
    /// comment on `startCall` is explicit about this: a call itself isn't
    /// the thing the tribe charges for, the floor alone still applies.
    /// Joining is the caller's — this round has no in-app call UI, so the
    /// link is only ever handed off externally (an `Intent.ACTION_VIEW`),
    /// same as `callServer()`'s own doc comment implies by returning a
    /// plain string, not a connection.
    suspend fun startCall(chat: String, isTribe: Boolean, audioOnly: Boolean): MessageRow {
        check(transport != null) { "Not connected." }
        val server = callServer() ?: error("No call server is configured for this account.")
        val link = CallLink.new(server, audioOnly)
        val amountMsat = if (isTribe) MESSAGE_FLOOR_MSAT else 0L
        val body = JSONObject().apply { put("content", link.message) }.toString()
        val (myAlias, myPhoto) = sentIdentity(chat, isTribe)
        val result = run { time, stateBytes ->
            coreSend(seed, time, chat, CALL_MESSAGE_TYPE.toUByte(), body, stateBytes, myAlias, myPhoto, amountMsat.toULong(), isTribe)
        }
        val sent = result.msgs.firstOrNull()
        val row = MessageRow(
            uuid = sent?.uuid,
            serverIndex = null,
            type = CALL_MESSAGE_TYPE,
            chat = chat,
            fromMe = true,
            sender = null,
            body = body,
            msat = amountMsat,
            timestamp = System.currentTimeMillis(),
            tag = sent?.tag,
            paymentHash = sent?.paymentHash,
            status = "PENDING",
        )
        storage.insertMessages(listOf(row))
        return row
    }

    /// Deletes a message for everyone: a type 17 message naming it in
    /// `replyUuid` — PeelCore's own `Engine.deleteMessage(id:)`. Whether
    /// this account may is the caller's own job (`canDelete` in
    /// `ConversationScreen.kt` — the same shape `canBoost` already has,
    /// checked before the menu even offers it, not re-checked here); a
    /// tribe delete pays the tribe's own price like any ordinary message
    /// (never the no-floor boost-style add-on), PeelCore's own comment on
    /// `deleteMessage` is explicit about this. The row is only actually
    /// hidden once `Storage.insertMessages`'s own apply-on-receipt step
    /// sees this echoed back confirmed — the same "wait for the server to
    /// have it" rule `startCall`'s own call-site doesn't need but a
    /// delete, which other devices and other people's clients must also
    /// come to agree on, does.
    suspend fun deleteMessage(chat: String, targetUuid: String, isTribe: Boolean): MessageRow {
        check(transport != null) { "Not connected." }
        val amountMsat = if (isTribe) tribeMessageAmountMsat(chat) else 0L
        val body = JSONObject().apply {
            put("content", "")
            put("replyUuid", targetUuid)
        }.toString()
        val (myAlias, myPhoto) = sentIdentity(chat, isTribe)
        val result = run { time, stateBytes ->
            coreSend(seed, time, chat, DELETE_MESSAGE_TYPE.toUByte(), body, stateBytes, myAlias, myPhoto, amountMsat.toULong(), isTribe)
        }
        val sent = result.msgs.firstOrNull()
        val row = MessageRow(
            uuid = sent?.uuid,
            serverIndex = null,
            type = DELETE_MESSAGE_TYPE,
            chat = chat,
            fromMe = true,
            sender = null,
            body = body,
            msat = amountMsat,
            timestamp = System.currentTimeMillis(),
            tag = sent?.tag,
            paymentHash = sent?.paymentHash,
            status = "PENDING",
        )
        storage.insertMessages(listOf(row))
        return row
    }

    /// How this account appears in a tribe, if it has its own name or photo there.
    fun tribeIdentity(chat: String): TribeIdentity.Identity = identityStore()[chat] ?: TribeIdentity.Identity()

    private fun identityStore(): Map<String, TribeIdentity.Identity> = TribeIdentity.decode(storage.value(Setting.TRIBE_IDENTITIES))

    /// Saves a tribe's own name and photo (empty = the account's), as iOS `setTribeIdentity`;
    /// a photo that is not https is refused. Sent from the next message on.
    fun setTribeIdentity(chat: String, alias: String?, photoUrl: String?) {
        val photo = TribeIdentity.cleanPhoto(photoUrl).getOrThrow()
        val store = identityStore().toMutableMap()
        store[chat] = TribeIdentity.Identity(alias?.trim()?.takeIf { it.isNotEmpty() }, photo)
        storage.apply(Storage.Commit(settings = mapOf(Setting.TRIBE_IDENTITIES to TribeIdentity.encode(store))))
    }

    /// The alias and photo a message to `chat` goes out with: a tribe's own where set, else the account's.
    internal fun sentIdentity(chat: String, isTribe: Boolean): Pair<String, String> {
        val alias = storage.value(Setting.ALIAS) ?: ""
        val photo = storage.value(Setting.PHOTO_URL) ?: ""
        return if (isTribe) TribeIdentity.sent(identityStore()[chat], alias, photo) else alias to photo
    }

    /// Puts a picture on the memes server's public route, as iOS `uploadPicture` (a profile
    /// or tribe photo): `jpeg` in the clear. Returns its public https link.
    suspend fun uploadPicture(jpeg: ByteArray): String {
        val memes = MemesServer(MEMES_HOST)
        val muid = withMemes(memes) { auth ->
            memes.upload(jpeg, fieldName = "image", fileName = "image.jpg", mediaType = "image/jpg", token = auth, route = "public")
        }
        return memes.publicUrl(muid)
    }

    /// The photo others see beside this account's messages: an https link, or empty for none
    /// (iOS `setPhotoURL`). It travels like the name: every message sent from now on carries it.
    fun setPhotoUrl(url: String) {
        val clean = ProfilePhoto.clean(url).getOrThrow()
        storage.apply(Storage.Commit(settings = mapOf(Setting.PHOTO_URL to clean)))
    }

    /// Makes `jpeg` this account's photo (iOS `uploadPhoto`): uploads it, then stores its link.
    suspend fun uploadPhoto(jpeg: ByteArray): String {
        val url = uploadPicture(jpeg)
        setPhotoUrl(url)
        return url
    }

    /// The people seen posting in a tribe (not this account), as iOS `tribeSenders`.
    fun tribeSeenCount(chat: String): Int = storage.tribeSenderCount(chat)

    /// A chat's "Share my time zone" choice: on, with the device's zone, until chosen otherwise.
    fun timeZoneChoice(chat: String): TimeZoneSharing.Choice = timeZoneStore()[chat] ?: TimeZoneSharing.Choice()

    private fun timeZoneStore(): Map<String, TimeZoneSharing.Choice> =
        TimeZoneSharing.decodeStore(storage.value(Setting.CHAT_TIME_ZONES))

    /// Saves a chat's choice right away (dated strictly after what it replaces, as
    /// `setPreference`) and syncs it in the background. `identifier` "" = the device's zone.
    fun setTimeZoneChoice(chat: String, enabled: Boolean, identifier: String) {
        val store = timeZoneStore().toMutableMap()
        val date = PreferencesMerge.nextDate(System.currentTimeMillis() / 1000, store[chat]?.date ?: 0L)
        store[chat] = TimeZoneSharing.Choice(enabled, identifier, date)
        storage.apply(Storage.Commit(settings = mapOf(Setting.CHAT_TIME_ZONES to TimeZoneSharing.encodeStore(store))))
        syncPreferencesInBackground()
    }

    /// Whether the time zone setting is offered in this chat: a person, or a tribe the
    /// registry called private. A public tribe (or one not known yet) never gets the offset.
    fun offersTimeZone(chat: String, isTribe: Boolean): Boolean =
        !isTribe || TimeZoneSharing.decodeFlags(storage.value(Setting.TRIBE_PRIVACY))[chat] == true

    /// What the registry said about a tribe's privacy, kept for `offersTimeZone`.
    fun recordTribePrivacy(chat: String, isPrivate: Boolean) {
        val flags = TimeZoneSharing.decodeFlags(storage.value(Setting.TRIBE_PRIVACY))
        if (flags[chat] == isPrivate) return
        storage.apply(Storage.Commit(settings = mapOf(Setting.TRIBE_PRIVACY to TimeZoneSharing.encodeFlags(flags + (chat to isPrivate)))))
    }

    /// The `metadata` field of a text, attachment or direct-payment message: `{"tz":"GMT+1"}`
    /// when this chat shares its time zone, else null (iOS `TimeZoneOffset`).
    fun timeZoneMetadata(chat: String, isTribe: Boolean): String? =
        TimeZoneSharing.metadata(timeZoneChoice(chat), offersTimeZone(chat, isTribe), System.currentTimeMillis())

    /// Merges the per-chat `timezone` items with the file, as `syncPreferences` does the
    /// account-wide ones. True when something must go up (written into `target`).
    private fun syncTimeZones(file: PreferencesFile?, target: PreferencesFile): Boolean {
        val local = timeZoneStore()
        var upload = false
        val taken = mutableMapOf<String, TimeZoneSharing.Choice>()
        for (chat in (local.keys + (file?.chatIdentifiers(TimeZoneSharing.KEY) ?: emptyList())).toSet()) {
            when (val outcome = TimeZoneSharing.decide(file?.timeZone(chat), local[chat])) {
                is TimeZoneSharing.Outcome.Upload -> {
                    target.setTimeZone(chat, outcome.choice, outcome.choice.date)
                    upload = true
                }
                is TimeZoneSharing.Outcome.Take -> taken[chat] = outcome.choice
                TimeZoneSharing.Outcome.Nothing -> {}
            }
        }
        if (taken.isNotEmpty()) {
            // Only over what was merged against: a choice made here meanwhile goes up next time.
            val now = timeZoneStore().toMutableMap()
            for ((chat, choice) in taken) if (now[chat] == local[chat]) now[chat] = choice
            storage.apply(Storage.Commit(settings = mapOf(Setting.CHAT_TIME_ZONES to TimeZoneSharing.encodeStore(now))))
        }
        return upload
    }

    /// This account's own one-tap boost amount, in msat — whatever was
    /// last chosen here or taken from another device
    /// (`syncPreferences`), else `BOOST_AMOUNT_MSAT`'s own default.
    fun boostAmountMsat(): Long = storage.value(Setting.BOOST_AMOUNT_MSAT)?.toLongOrNull() ?: BOOST_AMOUNT_MSAT

    /// The person chose a new boost amount in Profile: written here,
    /// dated now, and synced to the account's other devices right away
    /// (best effort — a sync failure here is silent, the same way a
    /// background sync failing after `start()`/`resume()` is; the
    /// value is already correct on this device either way, and the
    /// next successful sync carries it).
    ///
    /// Dated strictly after whatever was stored before (`max(now, old +
    /// 1)`, never `now` alone) — PeelCore's own `setPreference` keeps
    /// this same guarantee. Without it, two choices made back to back
    /// (tap Save, immediately change your mind, tap Save again) can
    /// land in the same whole second; `syncPreferences`'s own
    /// strictly-greater comparison against the file would then treat
    /// that tie as "nothing new to upload," and the very next sync —
    /// which fetches the file this device's own first choice already
    /// reached — would silently revert the second, later choice back
    /// to the first, on this one device, no network race needed at
    /// all. A found-and-fixed bug, not a hypothetical.
    suspend fun setBoostAmount(sats: Long) {
        require(sats > 0) { "the boost amount must be more than zero" }
        val oldDate = storage.value(Setting.BOOST_AMOUNT_UPDATED_AT)?.toLongOrNull() ?: 0L
        val date = PreferencesMerge.nextDate(System.currentTimeMillis() / 1000, oldDate)
        storage.apply(
            Storage.Commit(
                settings = mapOf(
                    Setting.BOOST_AMOUNT_MSAT to (sats * 1000).toString(),
                    Setting.BOOST_AMOUNT_UPDATED_AT to date.toString(),
                ),
            ),
        )
        runCatching { syncPreferences() }
    }

    /// Where a synced preference lives here: its value and date settings, and
    /// the stored string for a value in the spec's unit (the boost amount is
    /// kept in msat, the rest as the spec holds them).
    private class PrefSlot(
        val value: Setting,
        val date: Setting,
        val toStored: (Long) -> String = { it.toString() },
        val fromStored: (String) -> Long? = { it.toLongOrNull() },
    )

    private fun slot(spec: PreferencesMerge.Spec): PrefSlot = when (spec.key) {
        PreferencesFile.TIP_AMOUNT_KEY -> PrefSlot(
            Setting.BOOST_AMOUNT_MSAT, Setting.BOOST_AMOUNT_UPDATED_AT,
            toStored = { (it * 1000).toString() }, fromStored = { it.toLongOrNull()?.div(1000) },
        )
        PreferencesFile.PIN_TIMEOUT_KEY -> PrefSlot(Setting.PIN_TIMEOUT, Setting.PIN_TIMEOUT_UPDATED_AT)
        PreferencesFile.BIOMETRIC_KEY -> PrefSlot(Setting.BIOMETRIC_ENABLED, Setting.BIOMETRIC_ENABLED_UPDATED_AT)
        PreferencesFile.AUTO_DOWNLOAD_MEDIA_KEY -> PrefSlot(Setting.AUTO_DOWNLOAD_MEDIA, Setting.AUTO_DOWNLOAD_MEDIA_UPDATED_AT)
        else -> PrefSlot(Setting.PAYMENT_CONFIRMATION_MSAT, Setting.PAYMENT_CONFIRMATION_UPDATED_AT)
    }

    /// This device's value and date for `spec`, in the spec's unit.
    private fun localPreference(spec: PreferencesMerge.Spec): PreferencesMerge.Local? {
        val slot = slot(spec)
        return storage.value(slot.value)?.let(slot.fromStored)?.let {
            PreferencesMerge.Local(it, storage.value(slot.date)?.toLongOrNull() ?: 0L)
        }
    }

    /// Whether the account's other devices' value for `spec` is to be synced here at all.
    /// `biometric_enabled` only where this device has biometrics to offer
    /// (`syncsBiometrics`, set by the app), as PeelCore's `PreferenceCapabilities`.
    private fun syncs(spec: PreferencesMerge.Spec): Boolean =
        spec.key != PreferencesFile.BIOMETRIC_KEY || syncsBiometrics

    /// Set by the app once it knows this device can offer biometric unlock.
    @Volatile var syncsBiometrics: Boolean = false

    /// The official `pin_timeout` as stored (hours 0..25), if ever set or synced.
    fun pinTimeoutOfficialHours(): Int? = storage.value(Setting.PIN_TIMEOUT)?.toIntOrNull()?.let(PinTimeout::clampOfficial)

    /// "Ask for the PIN", on Peel's scale (`PinTimeout`); default is at launch (0).
    fun pinTimeoutHours(): Int = pinTimeoutOfficialHours()?.let(PinTimeout::peelHours) ?: 0

    fun setPinTimeout(peelHours: Int) = setPreference(PreferencesMerge.PIN_TIMEOUT, PinTimeout.officialHours(peelHours).toLong())

    /// What the account's devices say about biometric unlock: a WISH, never
    /// an enrolment (PeelCore decision 10) — null when never set or this
    /// device has none to offer.
    fun biometricWish(): Boolean? =
        if (!syncsBiometrics) null else storage.value(Setting.BIOMETRIC_ENABLED)?.let { it == "1" }

    fun setBiometricEnabled(on: Boolean) {
        if (syncsBiometrics) setPreference(PreferencesMerge.BIOMETRIC, if (on) 1 else 0)
    }

    /// "Auto download media" (PeelCore's `autoDownloadsMedia`): on unless turned off.
    fun autoDownloadsMedia(): Boolean = storage.value(Setting.AUTO_DOWNLOAD_MEDIA) != "0"

    fun setAutoDownloadsMedia(on: Boolean) = setPreference(PreferencesMerge.AUTO_DOWNLOAD_MEDIA, if (on) 1 else 0)

    /// Writes a choice made here: dated strictly after what it replaces, synced right away.
    private fun setPreference(spec: PreferencesMerge.Spec, value: Long) {
        val slot = slot(spec)
        val oldDate = storage.value(slot.date)?.toLongOrNull() ?: 0L
        val date = PreferencesMerge.nextDate(System.currentTimeMillis() / 1000, oldDate)
        storage.apply(Storage.Commit(settings = mapOf(slot.value to slot.toStored(value), slot.date to date.toString())))
        syncPreferencesInBackground()
    }

    private fun takePreference(spec: PreferencesMerge.Spec, local: PreferencesMerge.Local): Pair<Setting, String> =
        slot(spec).let { it.value to it.toStored(local.value) }

    private fun dateSetting(spec: PreferencesMerge.Spec): Setting = slot(spec).date

    /// Syncs in the background (a change made in Profile, which isn't suspending).
    fun syncPreferencesInBackground() {
        scope.launch { runCatching { syncPreferences() } }
    }

    /// Merges every preference this port syncs (`PreferencesMerge.SPECS`:
    /// the boost amount `tip_amount` and `peel.payment_confirmation_msat`)
    /// with the memes-server preferences file: fetches and decrypts it,
    /// takes what the file's items say where they win (newest date, then
    /// each key's apply policy), uploads what this device chose later —
    /// PeelCore's `syncPreferences`. Every other item and top-level field
    /// goes back byte for byte. Best-effort: offline or without a
    /// transport it does nothing, and a file the official apps couldn't
    /// read is neither read nor written.
    suspend fun syncPreferences() {
        if (transport == null) return
        val memes = MemesServer(MEMES_HOST)
        val secret = coreNodeKeys("bitcoin", seed).secret
        val fetched = withMemes(memes) { auth -> memes.preferences(auth) }
        val now = System.currentTimeMillis() / 1000

        val file: PreferencesFile?
        if (fetched == null) {
            // Only a device that may start the file does; never on a mere "not found".
            if (!mayStartPreferencesFile(MEMES_HOST)) return
            file = null
        } else {
            val sealed = android.util.Base64.decode(fetched.trim(), android.util.Base64.DEFAULT)
            file = PreferencesFile.parse(String(MediaCrypto.decrypt(sealed, secret), Charsets.UTF_8))
            markPreferencesFileSeen()
        }

        val target = file ?: PreferencesFile.empty()
        var upload = false
        for (spec in PreferencesMerge.SPECS.filter(::syncs)) {
            val local = localPreference(spec)
            when (val outcome = PreferencesMerge.decide(spec, file, local, now)) {
                is PreferencesMerge.Outcome.Upload -> {
                    target.setEntry(spec.key, spec.format(outcome.local.value), outcome.local.date)
                    upload = true
                }
                is PreferencesMerge.Outcome.Take -> {
                    // Only over what was merged against: a change made here while
                    // the fetch was out goes up on the next sync instead.
                    if (localPreference(spec) == local) {
                        val (setting, value) = takePreference(spec, outcome.local)
                        storage.apply(
                            Storage.Commit(settings = mapOf(setting to value, dateSetting(spec) to outcome.local.date.toString())),
                        )
                    }
                }
                PreferencesMerge.Outcome.Nothing -> {}
            }
        }
        if (syncTimeZones(file, target)) upload = true
        if (upload) {
            uploadPreferencesFile(memes, secret, target)
            if (file == null) markPreferencesFileSeen()
        }
    }

    private suspend fun uploadPreferencesFile(memes: MemesServer, secret: String, file: PreferencesFile) {
        val sealed = MediaCrypto.encrypt(file.text().toByteArray(Charsets.UTF_8), secret)
        val sealedBase64 = android.util.Base64.encodeToString(sealed, android.util.Base64.NO_WRAP)
        // The account's pubkey, base64url without padding — PeelCore's
        // own `MediaToken.base64URLEncode(key).replacingOccurrences(of: "=", with: "")`.
        val pubkeyField = base64UrlEncode(hexToBytes(accountKey)).trimEnd('=')
        withMemes(memes) { auth -> memes.uploadPreferences(sealedBase64, pubkeyField, auth) }
    }

    private fun markPreferencesFileSeen() {
        storage.apply(Storage.Commit(settings = mapOf(Setting.PREFERENCES_FILE_SEEN to MEMES_HOST.lowercase())))
    }

    /// Whether a "not found" from the memes server may start this
    /// account's preferences file here — PeelCore's own
    /// `mayStartPreferencesFile`: only for an account created fresh on
    /// this device (`Setting.NEEDS_RESTORE` was never set at all — a
    /// restored account always has one, whatever happened before this
    /// device opened it, is never safe to start over on a "not found"
    /// that could just as well be a server hiccup), and only on a
    /// server this device has never already confirmed having one on.
    private fun mayStartPreferencesFile(host: String): Boolean =
        storage.value(Setting.NEEDS_RESTORE) == null && storage.value(Setting.PREFERENCES_FILE_SEEN) != host.lowercase()

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((Character.digit(hex[it * 2], 16) shl 4) + Character.digit(hex[it * 2 + 1], 16)).toByte() }

    /// Downloads and decrypts an attachment into this account's cache,
    /// or finds it there already — PeelCore's `downloadAttachment`,
    /// trimmed to receiving (this port never sends one yet,
    /// `docs/phases/08-attachments.md`): no upload, no price, no "sell
    /// it" path, and no overall cache-size eviction (PeelCore prunes its
    /// cache at a 500MB total; Android's own cache directory is already
    /// OS-managed under storage pressure, and every file here is always
    /// re-derivable from the server plus its key, so that's an accepted
    /// gap, not a silent one).
    ///
    /// `cachedOnly` never touches the network: a file not already cached
    /// throws `AttachmentNotCached` (Auto download media off). `upTo`
    /// holds a larger download back with `AttachmentHeld` (PeelCore's
    /// `autoDownloadLimit` for videos and recordings); a tap on the
    /// held tile downloads it with neither.
    suspend fun downloadAttachment(row: MessageRow, cachedOnly: Boolean = false, upTo: Int? = null): DownloadedAttachment {
        val fields = AttachmentFields.decode(row.body) ?: throw IllegalStateException("Not an attachment.")
        val mediaToken = fields.mediaToken ?: throw IllegalStateException("This attachment has nowhere to come from yet.")
        val token = MediaToken.parse(mediaToken)?.takeIf { MediaToken.isSafeMuid(it.muid) }
            ?: throw IllegalStateException("This attachment is unreadable.")
        val key = fields.ownKey ?: throw IllegalStateException("This attachment has no key.")
        // Only ever from this account's own memes server, never a host a
        // message happens to name — same guard as a tribe's `host`
        // (`Engine.contactHint`'s own reasoning), applied to a server
        // that would otherwise just be told to hand over a bearer token.
        if (token.host != MEMES_HOST) throw IllegalStateException("This attachment is on an untrusted host.")
        val declared = attachmentKind(fields.mediaType)

        val cacheDir = storage.attachmentCacheDir()
        val cacheFile = java.io.File(cacheDir, token.muid)
        val nameFile = java.io.File(cacheDir, "${token.muid}.name")
        if (cacheFile.exists()) {
            val plain = cacheFile.readBytes()
            val fileName = nameFile.takeIf { it.exists() }?.readText()?.takeIf { it.isNotEmpty() }
            return DownloadedAttachment(cacheFile, verifiedKind(plain, declared), fileName, plain.size)
        }

        if (cachedOnly) throw AttachmentNotCached()
        val memes = MemesServer(MEMES_HOST)
        val limit = (upTo ?: ATTACHMENT_LIMIT) + 128 // Encryption adds a header, padding and a MAC.
        val (sealed, fileName) = try {
            withMemes(memes) { auth -> memes.download(mediaToken, auth, limit) }
        } catch (e: MemesServer.TooLargeException) {
            if (upTo != null) throw AttachmentHeld() else throw e
        }
        val plain = MediaCrypto.decrypt(sealed, key)
        cacheFile.writeBytes(plain)
        if (fileName != null) nameFile.writeText(fileName)
        return DownloadedAttachment(cacheFile, verifiedKind(plain, declared), fileName, plain.size)
    }

    /// Sends a file as an attachment: encrypts it with a fresh key,
    /// uploads it to the memes server, then a type 6 message naming it
    /// — PeelCore's `sendAttachment`, trimmed to no price, no "sell it"
    /// path (`Engine+Attachments.swift`'s own price plumbing), and no
    /// retry-the-upload-later: a failure here throws, same as a text
    /// send, rather than leaving a stored draft row behind to resume.
    /// `bytes`/`fileName`/`mediaType` are exactly what goes — any
    /// re-encoding (images) or classifying (`classifyOutgoingFile`,
    /// for a generic picked file or a video) is the caller's job, same
    /// as PeelCore's own `sendAttachment` takes an already-decided
    /// `OutgoingFile`. Shared by every kind this port can send
    /// (`sendImageAttachment`/`sendVoiceMessage`/`sendFileAttachment`),
    /// the way PeelCore's own `sendAttachment` is one function under
    /// all of its kinds too. There's no separate "send a video"
    /// function — PeelCore's own iPhone app has no dedicated video
    /// picker either; a video only ever reaches `sendAttachment`
    /// through the generic Files picker, same as here through
    /// `sendFileAttachment`.
    private suspend fun sendAttachment(
        chat: String, bytes: ByteArray, fileName: String, mediaType: String, caption: String, isTribe: Boolean,
        replyTo: String?, inThread: String?,
    ): MessageRow {
        check(transport != null) { "Not connected." }
        if (bytes.size > ATTACHMENT_LIMIT) throw IllegalStateException("That file is too large to send.")
        val amountMsat = if (isTribe) tribeMessageAmountMsat(chat) else 0L
        val key = MediaCrypto.newKey()
        val sealed = MediaCrypto.encrypt(bytes, key)
        val memes = MemesServer(MEMES_HOST)
        val muid = withMemes(memes) { auth ->
            memes.upload(sealed, fieldName = uploadFieldName(mediaType), fileName = fileName, mediaType = mediaType, token = auth)
        }
        val expiry = (System.currentTimeMillis() / 1000 + 365L * 24 * 3600).toUInt()
        val mediaToken = coreMakeMediaToken(seed, clock.next(), StateCodec.encode(state), MEMES_HOST, muid, chat, expiry)
        val body = JSONObject().apply {
            put("content", caption)
            put("mediaKeyForSelf", key)
            put("mediaKey", key)
            put("mediaType", mediaType)
            put("mediaToken", mediaToken)
            if (replyTo != null) put("replyUuid", replyTo)
            if (inThread != null) put("threadUuid", inThread)
            timeZoneMetadata(chat, isTribe)?.let { put("metadata", it) }
        }.toString()
        val (myAlias, myPhoto) = sentIdentity(chat, isTribe)
        val result = run { time, stateBytes ->
            coreSend(seed, time, chat, ATTACHMENT_MESSAGE_TYPE.toUByte(), body, stateBytes, myAlias, myPhoto, amountMsat.toULong(), isTribe)
        }
        val sent = result.msgs.firstOrNull()
        val row = MessageRow(
            uuid = sent?.uuid,
            serverIndex = null,
            type = ATTACHMENT_MESSAGE_TYPE,
            chat = chat,
            fromMe = true,
            sender = null,
            body = body,
            msat = amountMsat.takeIf { it > 0 },
            timestamp = System.currentTimeMillis(),
            tag = sent?.tag,
            paymentHash = sent?.paymentHash,
            status = "PENDING",
        )
        storage.insertMessages(listOf(row))
        // Our own copy goes straight into the cache, the same place a
        // download would put it — this device never needs to download
        // back what it just encrypted and sent. The name sidecar is
        // what lets this device's own row show the right file name
        // immediately too, the same file `downloadAttachment` writes
        // once a download names one.
        val cacheDir = storage.attachmentCacheDir()
        java.io.File(cacheDir, muid).writeBytes(bytes)
        java.io.File(cacheDir, "$muid.name").writeText(fileName)
        return row
    }

    /// The multipart field name a kind uploads under — PeelCore's own
    /// `OutgoingFile.field`.
    private fun uploadFieldName(mediaType: String): String = when (mediaType) {
        "image/jpg", "image/gif" -> "image"
        "video/mov" -> "video"
        "audio/wav" -> "audio"
        else -> "file"
    }

    /// `jpeg` is already re-encoded, orientation corrected, metadata
    /// gone — the caller's job (`ConversationScreen`'s picker), not
    /// this function's: it only ever sends exactly the bytes it's given.
    suspend fun sendImageAttachment(
        chat: String, jpeg: ByteArray, caption: String, isTribe: Boolean, replyTo: String? = null, inThread: String? = null,
    ): MessageRow = sendAttachment(chat, jpeg, "image.jpg", "image/jpg", caption, isTribe, replyTo, inThread)

    /// A voice recording — always exactly `"audio.wav"`/`"audio/wav"`,
    /// PeelCore's own `VoiceMessage.fileName`/`mediaType`: the WAV
    /// container `VoiceRecorder` already wrote, never re-labeled by
    /// `classifyOutgoingFile` (that's for a generic picked file, not a
    /// recording whose format this app controls completely itself).
    /// No caption — a voice message never has one, on any Sphinx client.
    suspend fun sendVoiceMessage(
        chat: String, wav: ByteArray, isTribe: Boolean, replyTo: String? = null, inThread: String? = null,
    ): MessageRow = sendAttachment(chat, wav, "audio.wav", "audio/wav", "", isTribe, replyTo, inThread)

    /// Any file picked through the system document picker — classified
    /// by `classifyOutgoingFile` (a video is relabeled `"video.mov"`/
    /// `"video/mov"`, the cross-client convention every Sphinx client
    /// uses for one whatever its real container; anything else keeps
    /// its own name and declared type). Sent exactly as read, no
    /// re-encoding — PeelCore's own generic `sendAttachment` doesn't
    /// either.
    suspend fun sendFileAttachment(
        chat: String, bytes: ByteArray, pickedName: String, declaredMediaType: String?, caption: String, isTribe: Boolean,
        replyTo: String? = null, inThread: String? = null,
    ): MessageRow {
        val (fileName, mediaType) = classifyOutgoingFile(pickedName, declaredMediaType)
        return sendAttachment(chat, bytes, fileName, mediaType, caption, isTribe, replyTo, inThread)
    }

    /// What the bytes really are, given what the sender said — PeelCore's
    /// `AttachmentKind.verified`: the declared kind when the bytes look
    /// like it, else a plain file, which nothing renders specially.
    /// Video and audio are not independently checked (no container-
    /// signature reader here yet, unlike PeelCore's own) — the declared
    /// kind is trusted for those two.
    private fun verifiedKind(data: ByteArray, declared: AttachmentKind): AttachmentKind {
        val matches = when (declared) {
            AttachmentKind.IMAGE -> runCatching {
                android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
            }.getOrNull() != null
            AttachmentKind.PDF -> data.size >= 5 && data.copyOfRange(0, 5).toString(Charsets.US_ASCII) == "%PDF-"
            AttachmentKind.VIDEO, AttachmentKind.AUDIO, AttachmentKind.FILE -> true
        }
        return if (matches) declared else AttachmentKind.FILE
    }

    /// The bearer token for `memes`, signing in again once if the server
    /// no longer takes the one kept — PeelCore's `withMemes`.
    private suspend fun <T> withMemes(memes: MemesServer, call: suspend (MemesServer.Token) -> T): T = try {
        call(memesSignIn(memes, refresh = false))
    } catch (e: MemesServer.RefusedException) {
        if (e.isUnauthorized) call(memesSignIn(memes, refresh = true)) else throw e
    }

    /// A fresh bearer token: signing the server's challenge with the
    /// account's own key (child index 0, the same one that signs this
    /// account's login) — PeelCore's `memesSignIn`.
    private suspend fun memesSignIn(memes: MemesServer, refresh: Boolean): MemesServer.Token {
        val kept = memesToken
        if (!refresh && kept != null && kept.isFresh) return kept
        val (id, challengeText) = memes.challenge()
        val bytes = challengeBytes(challengeText) ?: throw MemesServer.UnreadableException("sign-in")
        val signature = coreSignBase64(seed, 0u, clock.next(), network, base64UrlEncode(bytes))
        val token = memes.verify(id, signature, accountKey)
        memesToken = token
        return token
    }

    /// The challenge's bytes, read as PeelCore's own `challengeBytes`
    /// reads them: cut to a multiple of four characters, either base64
    /// alphabet.
    private fun challengeBytes(challenge: String): ByteArray? {
        val cut = challenge.substring(0, challenge.length - challenge.length % 4)
        val standard = cut.replace('-', '+').replace('_', '/')
        return runCatching { java.util.Base64.getDecoder().decode(standard) }.getOrNull()
    }

    private fun base64UrlEncode(data: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(data).replace('+', '-').replace('/', '_')

    /// Marks a chat read up to its newest stored message, here and on the
    /// server so the account's other devices agree — PeelCore's
    /// `markRead`, trimmed (no new-contact clearing; see [markUnread]). Offline, the marker is kept
    /// as pending (`Storage.setRead`) rather than lost, and
    /// `fetchReadsAndMutes`'s `publishPendingReads` sends it once a
    /// connection answers `getReads`.
    suspend fun markRead(chat: String) {
        val index = storage.highestServerIndex(chat) ?: return
        val connected = transport != null
        if (!storage.setRead(chat, index, pending = !connected)) return
        if (connected) {
            run { time, stateBytes -> coreMarkRead(seed, time, stateBytes, chat, index.toULong()) }
            maybeReadAll()
        }
    }

    /// Marks a chat unread: its marker goes just below its newest message
    /// from someone else, so that one counts, as the official apps' "Mark as
    /// Unread" does — PeelCore's `markUnread` (without its "from this
    /// message" variant: no message menu entry for it here). The server
    /// takes the lower marker as it is. Offline, the marker is kept as
    /// pending like `markRead`'s. Returns whether a marker moved.
    suspend fun markUnread(chat: String): Boolean {
        // The server keeps markers only for core contacts (tribes included).
        check(state["c/$chat"] != null) { "That is not a chat the server keeps a marker for." }
        val index = storage.newestIncomingIndex(chat)?.takeIf { it > 0 }
            ?: throw IllegalStateException("There is no message to mark unread.")
        val connected = transport != null
        if (!storage.setRead(chat, index - 1, pending = !connected, allowBack = true)) return false
        if (connected) {
            run { time, stateBytes -> coreMarkRead(seed, time, stateBytes, chat, (index - 1).toULong()) }
            synchronized(readsSentSinceGetReads) { readsSentSinceGetReads += chat }
        }
        return true
    }

    /// Sets how loud a chat may be. The level lives on the server, which uses
    /// it for push notifications (the tribes server marks "mentions only"),
    /// so this needs a connection — PeelCore's `setMute`: sent first, stored
    /// only once the core took it, never a local-only mute the server does
    /// not know about.
    suspend fun setMute(chat: String, level: MuteLevel) {
        check(transport != null) { "Not connected." }
        run { time, stateBytes -> coreMute(seed, time, stateBytes, chat, level.raw.toUByte()) }
        synchronized(mutesSetSinceGetMutes) { mutesSetSinceGetMutes += chat }
        storage.setMute(chat, level.raw)
    }

    /// Chats whose read marker / mute level this device set since `getReads`
    /// / `getMutes` was last asked: an answer may predate the change, so it
    /// is not applied to them (PeelCore's `readsSentSinceGetReads`/`mutesSetSinceGetMutes`).
    private val readsSentSinceGetReads = mutableSetOf<String>()
    private val mutesSetSinceGetMutes = mutableSetOf<String>()

    // Resending an undelivered message of ours: see [resendMessage].
    /// Row ids being sent again right now: a second Retry (or a reconnect's
    /// own resend) for the same row while one runs is refused, not queued.
    private val resending: MutableSet<Long> = java.util.Collections.synchronizedSet(mutableSetOf())

    /// What a reconnect's resend of recent messages knows about the server's
    /// records (`checkPendingSends`), and how many `getTags` answers came.
    @Volatile private var resendGate: SendRetry.Gate? = null
    @Volatile private var resendAnswers = 0

    /// Sends an undelivered message of ours again, on the same row: PeelCore's
    /// `resend`. Safe to call twice, or from Retry and a reconnect at once:
    ///  * one run per row at a time ([resending]);
    ///  * the row must be retryable *as stored now* ([SendRetry.isRetryable]:
    ///    failed, or pending past 30 s and unable to have paid) — a delivered
    ///    one, or one a status/echo settled meanwhile, is refused;
    ///  * the new uuid/tag/time go onto the *same* row in one conditional
    ///    UPDATE (`Storage.commitResend`) run after the core made the message
    ///    and before it is published (`beforePublish`): if the row stopped
    ///    being eligible the throw abandons the publish, so nothing goes out
    ///    that the row does not record. The row is `PENDING` again, and the
    ///    same rule keeps it so for another 30 s;
    ///  * a tribe message pays the tribe's price again: refused if it rose
    ///    over what was paid (`Failure.tribePriceRose`).
    /// [auto] marks a reconnect's own send (`autoResentAt`), shown nowhere.
    suspend fun resendMessage(id: Long, auto: Boolean = false): MessageRow {
        check(transport != null) { "Not connected." }
        if (!resending.add(id)) throw IllegalStateException("That message is already being sent.")
        try {
            val message = storage.message(id)
            if (message == null || !SendRetry.isRetryable(message, System.currentTimeMillis())) {
                throw IllegalStateException("That message is no longer waiting to be sent.")
            }
            val body = message.body ?: throw IllegalStateException("That message has nothing to send.")
            val chat = message.chat
            val isTribe = storage.contact(chat)?.isTribe == true
            val amountMsat = if (isTribe) tribeMessageAmountMsat(chat) else 0L
            if (isTribe && amountMsat > (message.msat ?: 0L)) {
                throw IllegalStateException("The tribe's price rose since this was written. Send it again as a new message.")
            }
            val (myAlias, myPhoto) = sentIdentity(chat, isTribe)
            val now = System.currentTimeMillis()
            var sent: uniffi.sphinxrs.Msg? = null
            run(beforePublish = { result ->
                sent = result.msgs.firstOrNull()
                val moved = storage.commitResend(
                    id, chat, sent?.uuid, sent?.tag, sent?.paymentHash, amountMsat.takeIf { it > 0 }, now, auto,
                )
                if (!moved) throw IllegalStateException("That message is no longer waiting to be sent.")
            }) { time, stateBytes ->
                coreSend(seed, time, chat, message.type.toUByte(), body, stateBytes, myAlias, myPhoto, amountMsat.toULong(), isTribe)
            }
            return storage.message(id) ?: message
        } finally {
            resending.remove(id)
        }
    }

    /// Drops an undelivered message of ours from this device
    /// (`Storage.discardUndelivered`); nothing was delivered, so nothing is
    /// deleted anywhere else. False if it is not discardable (any more).
    fun discardMessage(id: Long): Boolean {
        if (id in resending) return false
        return storage.discardUndelivered(id)
    }

    /// After a connection comes back, on that connection (PeelCore's
    /// `resendRecent`): sends each recent undelivered message of ours again
    /// by itself, once and oldest first, through [resendMessage]'s checks
    /// ([SendRetry.autoResends]): one that reached the server however this
    /// device marked it is never sent twice; a failure leaves the row failed,
    /// for Retry (`autoResentAt` is stored, so a relaunch does not try again).
    private suspend fun resendRecent(connection: Transport) {
        val gate = resendGate
        val now = System.currentTimeMillis()
        val candidates = runCatching { storage.undeliveredSends(now - SendRetry.AUTO_RESEND_WINDOW_MS) }.getOrNull() ?: return
        for (candidate in candidates) {
            if (transport !== connection) return
            // As stored now: a record or an echo may have landed since.
            val message = storage.message(candidate.id) ?: continue
            if (!SendRetry.autoResends(message, gate, System.currentTimeMillis())) continue
            runCatching { resendMessage(message.id, auto = true) }
                .onFailure { if (it is CancellationException) throw it else Log.w("PeelEngine", "automatic resend failed", it) }
        }
    }

    /// The chat open on screen, if any (set by the conversation screen):
    /// history pages and a foreground check its own sends' statuses.
    @Volatile var openChat: String? = null

    private var cameOnlineHere = false
    private val ownStatusCheckedAt = mutableMapOf<String, Long>()
    private val ownStatusAsked = mutableSetOf<String>()
    private val ownStatusHeard: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
    private val unknownTags = mutableSetOf<String>()

    /// Asks the server what became of this chat's own messages it has not
    /// confirmed (no status, or pending), [OWN_STATUS_CHUNK] tags at a time:
    /// the answers land as statuses (`storeMessages`) and the chat's bolts
    /// follow. PeelCore's `checkOwnMessageStatus(in:)`, called when the chat
    /// is opened, the app comes to the front and history pages arrive; at
    /// most once per [OWN_STATUS_WAIT_MS] for a chat, and not for a tag the
    /// server had no record of at the last ask. Best effort: a failure is
    /// left to the next ask or connect ([checkPendingSends]).
    suspend fun checkOwnMessageStatus(chat: String) {
        if (transport == null || !cameOnlineHere) return
        val now = SystemClock.elapsedRealtime()
        synchronized(ownStatusCheckedAt) {
            val asked = ownStatusCheckedAt[chat]
            if (asked != null && now - asked < OWN_STATUS_WAIT_MS) return
        }
        // A reply to an earlier ask has come by now.
        val tags = synchronized(unknownTags) {
            unknownTags += ownStatusAsked - ownStatusHeard
            ownStatusAsked.clear()
            ownStatusHeard.clear()
            val unconfirmed = runCatching { storage.unconfirmedTags(chat) }.getOrNull() ?: return
            unconfirmed.filter { it !in unknownTags }
        }
        if (tags.isEmpty()) return
        synchronized(ownStatusCheckedAt) { ownStatusCheckedAt[chat] = now }
        synchronized(unknownTags) { ownStatusAsked += tags }
        for (chunk in tags.chunked(OWN_STATUS_CHUNK)) {
            val ok = runCatching { run { time, stateBytes -> coreGetTags(seed, time, stateBytes, chunk, null) } }.isSuccess
            if (!ok) return
        }
    }

    /// Asks the server what became of sends still pending here, on every
    /// connect and after a restore — PeelCore's `checkPendingSends`: a
    /// status published while this device was offline is not missed, and a
    /// restored send (no status at all) learns how it went. Also asks about
    /// the tags of the messages [resendRecent] may send again, and keeps what
    /// the server's records name (`resendGate`): the reconnect's automatic
    /// resend only sends what the server has no record of. Not ported: the
    /// 30-minute "never recorded" failing of payments.
    private suspend fun checkPendingSends() {
        val now = System.currentTimeMillis()
        val candidates = runCatching { storage.undeliveredSends(now - SendRetry.AUTO_RESEND_WINDOW_MS) }.getOrDefault(emptyList())
            .filter { SendRetry.isAutoResendable(it, now) }.mapNotNull { it.tag }
        val tags = (storage.pendingSendTags(now - SendRetry.CONFIRMATION_TIMEOUT_MS) + candidates).distinct()
        val gate = SendRetry.Gate(asked = candidates.toSet())
        resendAnswers = 0
        resendGate = gate
        val chunks = tags.chunked(OWN_STATUS_CHUNK)
        for (chunk in chunks) {
            run { time, stateBytes -> coreGetTags(seed, time, stateBytes, chunk, null) }
        }
        // Nothing to ask: nothing is recorded. Otherwise wait (bounded) for
        // every answer, which lands in `storeMessages`; without them a
        // candidate with a tag is simply not sent (`SendRetry.autoResends`).
        if (chunks.isEmpty()) return gate.settle()
        val deadline = SystemClock.elapsedRealtime() + RECORDS_WAIT_MS
        while (resendAnswers < chunks.size && SystemClock.elapsedRealtime() < deadline) delay(100)
        if (resendAnswers >= chunks.size) gate.settle()
    }

    /// Read markers and mutes live on the server, shared by every device
    /// — PeelCore's `fetchReadsAndMutes`, called once per connection
    /// (`start`/`resume`), same as a restore's catch-up. `getReads`'s and
    /// `getMutes`' own answers arrive asynchronously, same asymmetry as
    /// every other history ask, and land in `storeMessages` (which
    /// `askAndAwait`'s listener already runs every inbound `RunReturn`
    /// through) — this only sends the asks (the wait and the flush of
    /// whatever this device moved locally while it had nothing to send it to
    /// run in the background).
    private fun fetchReadsAndMutes() {
        readsJob = fetchReadsAndMutesJob()
    }

    /// The reads fetch's own job, so [afterConnect] can join it before
    /// [maybeReadAll] without delaying the restore or catch-up.
    @Volatile private var readsJob: Job? = null

    private fun fetchReadsAndMutesJob(): Job {
        // Not awaited by the caller (iOS only sends the two asks): each
        // wait is up to `historyWaitMs`, and a fresh restore has no reads or
        // mutes to answer, so awaiting here stalled the contacts restore for
        // up to a minute after "Connected". Answers land through the listener.
        // Its own independent launch, so [afterConnect]'s own runCatching
        // does not reach a failure in here — caught here instead, same reason.
        return scope.launch {
            runCatching { fetchReadsAndMutesAwaiting() }
                .onFailure { Log.w("PeelEngine", "fetchReadsAndMutes failed", it) }
        }
    }

    private suspend fun fetchReadsAndMutesAwaiting() {
        // The answers to what is asked now postdate every change made so far.
        synchronized(readsSentSinceGetReads) { readsSentSinceGetReads.clear() }
        synchronized(mutesSetSinceGetMutes) { mutesSetSinceGetMutes.clear() }
        askAndAwait(historyWaitMs, expect = { it.lastRead != null }) {
            run { time, stateBytes -> coreGetReads(seed, time, stateBytes) }
        }
        askAndAwait(historyWaitMs, expect = { it.muteLevels != null }) {
            run { time, stateBytes -> coreGetMutes(seed, time, stateBytes) }
        }
        for ((chat, index) in storage.pendingReads()) {
            run { time, stateBytes -> coreMarkRead(seed, time, stateBytes, chat, index.toULong()) }
            storage.setRead(chat, index, pending = false)
        }
    }

    /// Re-derives `contact` rows (tribes especially, and any wrongly-mixed
    /// alias/photo a tribe's own messages left behind) from `message`
    /// history that was stored before `contactHint` knew about tribes at
    /// all — once per account per `BACKFILL_VERSION`, gated by
    /// `Setting.CONTACTS_BACKFILLED`. A restore made from here on needs no
    /// backfill, it goes through the current `contactHint` as it happens.
    private fun backfillContactsIfNeeded() {
        if (storage.value(Setting.CONTACTS_BACKFILLED) == BACKFILL_VERSION) return
        for (row in storage.messageSenders()) {
            val hint = contactHint(row) ?: continue
            storage.upsertContact(hint.pubkey, hint.alias, hint.photoUrl, hint.isTribe, hint.ready)
        }
        storage.apply(Storage.Commit(settings = mapOf(Setting.CONTACTS_BACKFILLED to BACKFILL_VERSION)))
    }

    /// Sends one ask (under the lock, like any `run`) and waits, outside
    /// it, for an inbound result matching `expect` — the shape every
    /// restore call takes: the real answer is not the ask's own return
    /// value, it is whatever the server publishes back later. Null on
    /// [timeoutMs] with nothing matching.
    internal suspend fun askAndAwait(
        timeoutMs: Long,
        expect: (RunReturn) -> Boolean,
        ask: suspend () -> RunReturn,
    ): RunReturn? = withTimeoutOrNull(timeoutMs) {
        coroutineScope {
            // Subscribed before the ask goes out: the flow has no replay, so an
            // answer beating a lazily scheduled collector would be lost (30 s wait).
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { inboundResults.first(expect) }
            ask()
            waiter.await()
        }
    }

    /// [askAndAwait] for a core call whose answer may come back in the call's own `RunReturn`
    /// (`create_tribe`, `list_tribe_members`) as well as later, from the server: whichever
    /// matches `expect` first. Null on [timeoutMs] with nothing matching.
    internal suspend fun askAndAwaitOrNow(
        timeoutMs: Long,
        expect: (RunReturn) -> Boolean,
        ask: suspend () -> RunReturn,
    ): RunReturn? = withTimeoutOrNull(timeoutMs) {
        coroutineScope {
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { inboundResults.first(expect) }
            val now = ask()
            if (expect(now)) {
                waiter.cancel()
                now
            } else {
                waiter.await()
            }
        }
    }

    suspend fun disconnect() = mutex.withLock {
        // Already dead when this runs from `reconnectLoop` (closing what
        // just dropped on its own) — a second close on it must not throw.
        transport?.let { runCatching { it.close() } }
        transport = null
        // So the next `connect()` starts a fresh collector on the new
        // Transport: without this, `startListening` stayed a no-op after
        // the first connection forever, and a reconnect's own
        // `askAndAwait` calls (`resume`'s catch-up) would wait out their
        // full timeout for an answer nothing was ever listening to deliver.
        listenerStarted = false
        caughtUp = false
        catchUpIndex = null
    }

    /// Stops whatever `start` has running — "Forget this account" needs
    /// this so a wiped `Storage` is never written to by a restore page
    /// still in flight.
    fun cancel() {
        keepConnected = false
        calls?.close()
        scope.cancel()
    }

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
        keepConnected = true
        Log.i(PeelLog.TAG, "Engine.start: entered; app ${com.sphinx.peel.BuildConfig.VERSION_NAME} (${com.sphinx.peel.BuildConfig.VERSION_CODE}) " +
            "buildType=${com.sphinx.peel.BuildConfig.BUILD_TYPE} debug=${com.sphinx.peel.BuildConfig.DEBUG}; sphinxrs: version not exposed")
        scope.launch {
            Log.d(PeelLog.TAG, "Engine.start: backfill")
            backfillContactsIfNeeded()
            val firstOk = connectAndRegister()
            Log.i(PeelLog.TAG, "Engine.start: first connect ${if (firstOk) "succeeded" else "failed"}")
            if (!firstOk) {
                Log.i(PeelLog.TAG, "Engine.start: entering reconnectLoop")
                // The first connect failed: keep trying with backoff instead of staying offline.
                if (keepConnected && reconnectJob?.isActive != true) reconnectJob = scope.launch { reconnectLoop() }
                return@launch
            }
            afterConnect()
            refreshTribeNames()
            refreshTribePrivacy()
            runCatching { syncPreferences() }
        }
    }

    /// Resolves the broker and connects, exactly as `start()`'s own first
    /// connection does — shared so `resume()` can do the same thing
    /// without duplicating it. Updates [connectionState] either way; false
    /// on any failure (already reported through [connectionState]), so
    /// the caller knows not to go on to a catch-up with nothing to ask.
    private suspend fun connectAndRegister(): Boolean {
        setConnection(ConnectionState.Connecting, "connectAndRegister")
        // The invite a new account registers with, until the register is known to have worked
        // (the server answers `already_registered` if an earlier one landed, and needs the
        // invite if it did not).
        val invite = if (isRegistered) null else pendingInvites.peek()
        Log.d(PeelLog.TAG, "connectAndRegister: invite present=${invite != null} isRegistered=$isRegistered")
        return try {
            val server = resolveServer(invite)
            Log.d(PeelLog.TAG, "connectAndRegister: server ${server.host}:${server.port} tls=${server.tls}")
            storage.apply(Storage.Commit(settings = mapOf(Setting.SERVER to server.toString())))
            connect(server, inviteCode = invite?.code)
            registeredWithInvite = invite != null && isRegistered
            if (isRegistered) {
                lastConnectError = null
                setConnection(ConnectionState.Connected, "registered")
                Log.i(PeelLog.TAG, "connectAndRegister: success")
            } else {
                val message = if (invite != null) SignupRules.NOT_CONFIRMED else "No registration answer came back in time."
                lastConnectError = message
                Log.w(PeelLog.TAG, "connectAndRegister: failed: $message")
                setConnection(ConnectionState.Failed(message), "not registered")
            }
            isRegistered
        } catch (e: Exception) {
            lastConnectError = e.javaClass.simpleName + ": " + e.message
            Log.e(PeelLog.TAG, "connectAndRegister: failed: ${e.javaClass.simpleName}: ${e.message}", e)
            setConnection(ConnectionState.Failed(e.message ?: "Could not connect to the server."), "exception")
            false
        }
    }

    /// The broker to connect to: the one a waiting invite names; the one an invite
    /// signup was made on (`Setting.SERVER_PINNED`); otherwise the public config's
    /// default, as ever for a restored account. The tribes host comes from the config
    /// where it is not known yet (never failing the connect for it on a named server).
    private suspend fun resolveServer(invite: Invite?): ServerAddress {
        invite?.server?.let {
            Log.d(PeelLog.TAG, "resolveServer: branch=invite")
            return ServerAddress.named(it).also { s -> Log.d(PeelLog.TAG, "resolveServer: ${s.host}:${s.port} tls=${s.tls}") }
        }
        val pinned = if (storage.value(Setting.SERVER_PINNED) == "1") storage.value(Setting.SERVER) else null
        if (pinned != null) {
            Log.d(PeelLog.TAG, "resolveServer: branch=pinned")
            val server = ServerAddress.parse(pinned) ?: throw IllegalStateException("the stored server address is unreadable")
            if (storage.value(Setting.TRIBE_HOST) == null && !ServerAddress.isLoopback(server.host)) {
                runCatching { ServerConfig.fetch(network).tribeHost }.getOrNull()
                    ?.let { storage.apply(Storage.Commit(settings = mapOf(Setting.TRIBE_HOST to it))) }
            }
            return server
        }
        Log.d(PeelLog.TAG, "resolveServer: branch=public config; fetching network=$network")
        val fetchStartedAt = SystemClock.elapsedRealtime()
        val config = ServerConfig.fetch(network)
        Log.d(PeelLog.TAG, "resolveServer: config defaultLsp=${config.defaultLsp} tribeHost=${config.tribeHost} in ${SystemClock.elapsedRealtime() - fetchStartedAt}ms")
        config.tribeHost?.let { storage.apply(Storage.Commit(settings = mapOf(Setting.TRIBE_HOST to it))) }
        return ServerAddress.named(config.defaultLsp)
    }

    /// Called whenever the app returns to the foreground, after `start()`
    /// has already run once (`MainActivity`'s lifecycle observer) —
    /// PeelCore's own reconnect-on-foreground plus `cameOnline`'s catch-up.
    /// Nothing here can tell a connection that is still good from one the
    /// OS quietly killed while backgrounded without actually trying it
    /// (HiveMQ's own disconnected-listener only fires for a drop it saw;
    /// a frozen/killed process never gets the chance), so this always
    /// reconnects first rather than trust [Transport.isConnected]. A
    /// no-op before `start()` has ever run. Supersedes a drop
    /// [reconnectLoop] is already retrying, not a second one alongside it.
    fun resume() {
        if (!started || restoreRunning) return
        reconnectJob?.cancel()
        scope.launch {
            disconnect()
            if (!connectAndRegister()) return@launch
            afterConnect()
            runCatching { syncPreferences() }
        }
    }

    /// A still-open `Transport` just dropped on its own — PeelCore's own
    /// `connectionClosed`. Starts [reconnectLoop] unless one is already
    /// running: a second drop signal while already reconnecting (or one
    /// that raced `resume()`'s own reconnect) is not a second loop.
    private fun onConnectionDropped() {
        if (!keepConnected || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch { reconnectLoop() }
    }

    /// Reconnects with backoff — 1, 2, 4, 8, 16s, then 30s and capped
    /// there, PeelCore's own `reconnectLoop` — until it succeeds or
    /// [keepConnected] goes false. Each attempt is a full
    /// `disconnect()` + `connectAndRegister()`, the same pair `resume()`
    /// already does, so the new Transport's subscriptions (tribe
    /// management, whatever the core's own `RunReturn`s ask for) are
    /// always redone from scratch, never assumed to have survived the
    /// drop — see `Transport`'s own doc comment on why HiveMQ's built-in
    /// `automaticReconnect()` is not used instead.
    private suspend fun reconnectLoop() {
        var attempt = 1
        while (keepConnected) {
            val retryInSeconds = minOf(30, 1 shl minOf(attempt - 1, 5))
            Log.i(PeelLog.TAG, "reconnectLoop: attempt $attempt, retry in ${retryInSeconds}s, last failure: $lastConnectError")
            setConnection(ConnectionState.Offline(retryInSeconds, lastConnectError), "reconnect attempt $attempt backoff")
            delay(retryInSeconds * 1_000L)
            if (!keepConnected) return
            disconnect()
            Log.d(PeelLog.TAG, "reconnectLoop: attempt $attempt starting")
            val ok = connectAndRegister()
            Log.i(PeelLog.TAG, "reconnectLoop: attempt $attempt ${if (ok) "succeeded" else "failed"}")
            if (ok) {
                afterConnect()
                runCatching { syncPreferences() }
                return
            }
            attempt++
        }
    }

    /// The catch-up every successful (re)connect needs — `start()`'s own
    /// first one, and `resume()`/`reconnectLoop()`'s after each later
    /// one. Wrapped here, not left to each caller: a drop mid-catch-up
    /// (the same risk `publishLocked` always had, now a plain
    /// [IllegalStateException] from `Transport` instead of HiveMQ's own
    /// uncaught one) must not crash whatever launched this — a fresh
    /// drop is [onConnectionDropped]'s own job to retry, not a reason to
    /// kill the coroutine that called this.
    private suspend fun afterConnect() {
        cameOnlineHere = false
        caughtUp = false
        catchUpIndex = null
        readsJob = null
        runCatching {
            // A restoring account asks once, after its contacts exist (settleReadsAfterRestore):
            // an earlier answer cannot be used.
            if (!restoreProgressFlow.value.restoring) fetchReadsAndMutes()
            // Best effort, before history (PeelCore's `cameOnline`): catching up
            // must not wait on it.
            runCatching { checkPendingSends() }.onFailure { Log.w("PeelEngine", "checking pending sends failed", it) }
            // What the server has no record of goes again by itself, on this
            // connection only; History does not wait on it.
            transport?.let { connection -> scope.launch { runCatching { resendRecent(connection) } } }
            // A restore that failed or was cut short resumes from its cursor.
            catchUpIndex = storage.maxServerIndex() ?: -1L
            caughtUp = if (restoreProgressFlow.value.restoring) runRestore() else catchUpMessages()
        }.onFailure {
            caughtUp = false
            Log.w("PeelEngine", "catch-up after connect failed", it)
        }
        // Local unread is settled (reads synced, history caught up): reset the mixer's badge if it is 0.
        // Reads must have synced too (failures are only logged by the job).
        runCatching { readsJob?.join() }
        runCatching { maybeReadAll() }
        // PeelCore's `cameOnline`: whoever sent the invite this account signed up with is
        // added now, as its first contact. Never fails the catch-up it follows.
        runCatching { addInviterIfPending() }.onFailure { Log.w("PeelEngine", "adding the inviter failed", it) }
        if (registeredWithInvite) {
            registeredWithInvite = false
            // The invite's sats were credited before the register answered; nothing reported
            // the balance. Best effort, in the background: the inviter above does not wait on it.
            scope.launch { runCatching { fetchPaymentHistory() }.onFailure { Log.w("PeelEngine", "balance refresh failed", it) } }
        }
        cameOnlineHere = true
        // The open chat's own sends, now that this connection can answer
        // (a foreground or a reconnect with a chat on screen).
        openChat?.let { runCatching { checkOwnMessageStatus(it) } }
        // Connected and caught up: `PushRegistrar` registers the push token here.
        onConnected?.let { runCatching { it() } }
    }

    /// The device came online or changed network (`ConnectionWatcher`): a
    /// connection that lived through a network change is usually dead
    /// though nothing has said so yet, so this reconnects, now. Not while
    /// a connect is already running.
    fun networkChanged() {
        if (connectionStateFlow.value == ConnectionState.Connecting) return
        resume()
    }

    /// Keeps no connection from here on (a deliberate sign-out).
    fun stop() {
        keepConnected = false
        reconnectJob?.cancel()
    }

    /// Whether the broker connection is up and registered right now (what a
    /// push checks before deciding to reconnect); [isConnected] only says a
    /// transport exists.
    val isOnline: Boolean get() = connectionStateFlow.value == ConnectionState.Connected

    /// Registers the FCM token with the server through the core
    /// (`setPushTokenForApp`, as iOS' `Engine.registerPushToken`), under this
    /// app's own id (`PushApp.APP_ID`, never the official app's "sphinx") in
    /// the "production" environment (FCM has no sandbox). Sends nothing, and
    /// answers false, unless connected, with a valid push key and a token.
    fun registerPushToken(token: String, pushKey: String, done: (Boolean) -> Unit = {}) {
        scope.launch {
            val ok = try {
                if (transport == null || token.isEmpty() || pushKey.length != 64) {
                    false
                } else {
                    run { time, state -> coreSetPushTokenForApp(seed, time, state, token, pushKey, PushApp.APP_ID, PushApp.ENVIRONMENT) }
                    true
                }
            } catch (e: Exception) {
                Log.w("Engine", "push registration failed: ${e.javaClass.simpleName}")
                false
            }
            done(ok)
        }
    }

    /// Removes this install's push token from the server through the core
    /// (`unregisterPushTokenForApp`), under the same app id as registration
    /// ([PushApp.APP_ID]). For "Forget this account", called before [cancel]
    /// and any wipe. Answers false, never throws, when not connected or on any
    /// failure. Logs only the exception class.
    suspend fun unregisterPushToken(): Boolean = try {
        if (transport == null) {
            false
        } else {
            run { time, state -> coreUnregisterPushTokenForApp(seed, time, state, PushApp.APP_ID) }
            true
        }
    } catch (e: Exception) {
        Log.w("Engine", "push unregister failed: ${e.javaClass.simpleName}")
        false
    }

    /// Tells the server everything is read (the core's `readAll`) so the
    /// mixer's badge count resets. Answers false, never throws, when not
    /// connected or on failure. Only [maybeReadAll] decides when to call it.
    suspend fun readAllOnServer(): Boolean = try {
        if (transport == null) {
            false
        } else {
            run { time, state -> coreReadAll(seed, time, state) }
            true
        }
    } catch (e: Exception) {
        Log.w("Engine", "readAll failed: ${e.javaClass.simpleName}")
        false
    }

    /// History caught up on this connection with no error or timeout.
    @Volatile private var caughtUp = false

    /// Highest stored server index when this connection's catch-up began.
    @Volatile private var catchUpIndex: Long? = null

    private val readAllPolicy = ReadAllPolicy(SystemClock::elapsedRealtime)

    /// Calls [readAllOnServer] when [ReadAllPolicy] allows: local real unread
    /// total (sum of `ConversationRow.unread`) is 0, connected, not restoring,
    /// throttled to once per 30 minutes. Run after connect's read sync and after
    /// a chat is marked read, the two places the local total can become 0.
    private suspend fun maybeReadAll() {
        val restoring = restoreProgressFlow.value.restoring
        val connected = transport != null
        if (restoring || !connected) return
        val unread = storage.conversations().sumOf { it.unread }
        if (!readAllPolicy.shouldCall(unread, connected, restoring, caughtUp)) return
        val current = storage.maxServerIndex() ?: -1L
        if (ReadAllPolicy.arrivedSince(catchUpIndex, current)) {
            // Messages came in during or after the fetch: not sent, throttle
            // untouched; a later trigger may proceed once unread is 0 again.
            catchUpIndex = current
            return
        }
        if (readAllOnServer()) readAllPolicy.recordSuccess()
    }

    /// Each known tribe's real name and photo, from its own registry —
    /// `TribeInfo.fetch`, PeelCore's `refreshTribes` trimmed to a single
    /// pass over whatever is missing a name, not the periodic background
    /// recheck that keeps a joined tribe's edits current (not built yet,
    /// so a renamed/re-pictured tribe here stays stale until the next
    /// restart). No tribe host recorded (offline on first run, or the
    /// config call failed) leaves every tribe showing by its pubkey, same
    /// as before this existed.
    private suspend fun refreshTribeNames() {
        // Only the account's own tribes host, never one a message names:
        // a stranger must not be able to make Peel fetch from a host of
        // their choosing (`Engine.discoveredTribe`'s same rule).
        val host = storage.value(Setting.TRIBE_HOST)?.takeIf { it.isNotEmpty() } ?: return
        val failed = mutableListOf<String>()
        suspend fun attempt(chat: String): Boolean = try {
            val info = TribeInfo.fetch(chat, host)
            storage.updateTribeInfo(chat, info.name, info.img, info.pricePerMessageMsat, info.escrowMsat)
            info.isPrivate?.let { recordTribePrivacy(chat, it) }
            true
        } catch (e: Exception) {
            false
        }
        for (tribe in storage.conversations()) {
            if (!tribe.isTribe || tribe.alias != null) continue
            if (!attempt(tribe.chat)) failed.add(tribe.chat)
        }
        if (failed.isEmpty()) return
        delay(2_000)
        for (chat in failed) {
            if (!attempt(chat)) Log.w("PeelTribes", "no tribe info for $chat from $host")
        }
    }

    /// Asks the registry whether each named tribe is private, once per tribe, best effort
    /// (`refreshTribeNames` only fetches tribes still without a name, and records theirs).
    private suspend fun refreshTribePrivacy() {
        val host = storage.value(Setting.TRIBE_HOST)?.takeIf { it.isNotEmpty() } ?: return
        val known = TimeZoneSharing.decodeFlags(storage.value(Setting.TRIBE_PRIVACY)).keys
        for (tribe in storage.conversations()) {
            if (!tribe.isTribe || tribe.chat in known) continue
            try {
                TribeInfo.fetch(tribe.chat, host).isPrivate?.let { recordTribePrivacy(tribe.chat, it) }
            } catch (e: Exception) {
                Log.w("PeelTribes", "no privacy for ${tribe.chat} from $host")
            }
        }
    }

    private enum class HistoryPhase { CONTACTS, MESSAGES }

    /// Contacts first, then every message from the beginning — PeelCore's
    /// `HistoryCursor.plan` and resume, minus the live/stall machinery.
    /// Resumable: the cursor (`RestoreCursor`) is kept in
    /// `Setting.RESTORE_CURSOR` after every page, so a failure (a page that
    /// never came, a dropped connection) or a restart picks up at the page
    /// it stopped on instead of asking for everything again, and is removed
    /// in the commit that marks the restore finished. A failure leaves
    /// `NEEDS_RESTORE` set and is reported through [restoreProgress]; the
    /// next `start()`/`resume()` runs it again from the cursor. Everything
    /// after a successful finish is the same for a fresh and a resumed one.
    private suspend fun runRestore(): Boolean {
        if (restoreRunning) return false
        restoreRunning = true
        restoreProgressFlow.update { it.copy(outcome = null) }
        var finished = false
        try {
            var cursor = RestoreCursor.decode(storage.value(Setting.RESTORE_CURSOR))
            if (cursor != null) {
                // Resumed: who the core knows as a person, for `contactHint`
                // (a fresh restore learns it after its contacts run).
                runCatching { refreshPersonKeys() }.onFailure { Log.w("PeelHistory", "listing contacts failed", it) }
            } else {
                cursor = planRestore()
            }
            while (cursor != null) cursor = restoreRun(cursor)
            storage.apply(
                Storage.Commit(
                    settings = mapOf(Setting.NEEDS_RESTORE to "0"),
                    deleteSettings = listOf(Setting.RESTORE_CURSOR),
                ),
            )
            restoreProgressFlow.update { it.copy(restoring = false, outcome = RestoreOutcome.Finished) }
            finished = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            restoreProgressFlow.update { it.copy(outcome = RestoreOutcome.Failed(e.message ?: "Restoring stopped.")) }
        } finally {
            restoreRunning = false
        }
        if (finished) {
            // History is stored: the account's own name/photo first (best
            // effort), then the reads pass, which needs the rebuilt contacts.
            runCatching { recoverOwnProfile() }.onFailure { Log.w("PeelHistory", "recovering own profile failed", it) }
            runCatching { settleReadsAfterRestore() }.onFailure { Log.w("PeelHistory", "settling reads failed", it) }
            // Our restored sends carry no status: the server's record of
            // each, by its tag, says how it went (PeelCore's `historyEnded`).
            runCatching { checkPendingSends() }.onFailure { Log.w("PeelHistory", "checking restored sends failed", it) }
            runCatching { refreshTribeNames() }.onFailure { Log.w("PeelHistory", "refreshing tribes failed", it) }
        }
        return finished
    }

    /// Asks the server what it holds and plans the restore from that; the
    /// plan is kept at once so even the first page's failure resumes.
    private suspend fun planRestore(): RestoreCursor? {
        restoreProgressFlow.update { it.copy(contacts = RestoreCount(0, null)) }
        val countsAnswer = askAndAwait(historyWaitMs, expect = { it.msgsCounts != null }) {
            run { time, stateBytes -> coreGetMsgsCounts(seed, time, stateBytes) }
        } ?: throw IllegalStateException("The server did not report your message history. Will retry.")
        val counts = parseCounts(countsAnswer.msgsCounts)
            ?: throw IllegalStateException("The server's counts could not be read. Will retry.")
        // An item count, not the highest server index (that only says when
        // to stop paging) — peel's `HistoryCursor` fix.
        restoreProgressFlow.update { it.copy(contacts = RestoreCount(0, counts.firstForEachKey)) }
        val plan = RestoreCursor.plan(counts)
        if (plan != null) keepCursor(plan)
        return plan
    }

    private fun keepCursor(cursor: RestoreCursor) {
        storage.apply(Storage.Commit(settings = mapOf(Setting.RESTORE_CURSOR to cursor.encode())))
    }

    /// One run of a restore from `cursor`; the run that follows it (kept
    /// before it starts), or null when this was the last. A page that never
    /// comes throws: a restore must never be marked finished with history
    /// missing.
    private suspend fun restoreRun(cursor: RestoreCursor): RestoreCursor? {
        val contactsRun = cursor.phase == RestoreCursor.Phase.CONTACTS
        val phase = if (contactsRun) HistoryPhase.CONTACTS else HistoryPhase.MESSAGES
        val total = cursor.contactsTotal
        // The Contacts line is right however the run was reached, fresh or
        // resumed: done once the messages run is in flight (unknown total:
        // RestoreCount(0, 0), which the screen treats as complete), and
        // continuing from what was already fetched in a contacts run.
        restoreProgressFlow.update {
            if (contactsRun) it.copy(contacts = RestoreCount(minOf(cursor.fetched, total ?: cursor.fetched), total))
            else it.copy(contacts = RestoreCount(total ?: 0, total ?: 0))
        }
        var current = cursor
        val complete = page(
            phase, cursor.end, cursor.next.toULong(),
            total = if (contactsRun) total else null,
            restored = if (contactsRun) cursor.fetched else 0,
            onPage = { next, items ->
                current = current.advanced(next.toLong() - 1, items)
                keepCursor(current)
            },
        ) { time, stateBytes, next ->
            if (contactsRun) {
                coreFetchFirstMsgsPerKey(seed, time, stateBytes, next, HISTORY_PAGE_SIZE, false)
            } else {
                coreFetchMsgsBatch(seed, time, stateBytes, next, HISTORY_PAGE_SIZE, false)
            }
        }
        if (!complete) throw IllegalStateException("Restoring ${if (contactsRun) "contacts" else "messages"} timed out. Will retry.")
        if (contactsRun) {
            // Once, with every contacts page's own state already folded in
            // — not per page: `listContacts` decodes the core's *entire*
            // contact list every time it's asked.
            val people = parseContactPubkeys(coreListContacts(StateCodec.encode(state)))
            personKeys = people.toSet()
            storage.markContactsReady(people)
            val done = total ?: current.fetched
            restoreProgressFlow.update { it.copy(contacts = RestoreCount(done, done)) }
        }
        return cursor.following()?.also { keepCursor(it) }
    }

    /// The core's direct contacts (persons; tribes are not in its registry),
    /// cached for `contactHint`'s isPerson check.
    private fun refreshPersonKeys() {
        personKeys = parseContactPubkeys(coreListContacts(StateCodec.encode(state))).toSet()
    }

    /// Each contact's child index, the core's `my_idx` (what a push seals,
    /// `decryptChildIndex` opens): pubkey -> index, for `PushNameSync`. Entries
    /// without a readable `my_idx` are left out. The value may arrive as a
    /// JSON number or a string.
    fun contactChildIndexes(): Map<String, ULong> {
        val array = JSONArray(coreListContacts(StateCodec.encode(state)))
        val result = mutableMapOf<String, ULong>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val pubkey = obj.optString("pubkey").takeIf { it.isNotEmpty() } ?: continue
            val index = obj.opt("my_idx")?.toString()?.toULongOrNull() ?: continue
            result[pubkey] = index
        }
        return result
    }

    /// The core's JSON for one contact (`get_contact` over just its `c/` entry), or null when the
    /// state has none (PeelCore's `Engine.contact(_:in:)`).
    internal fun contactEntryJson(pubkey: String): String? {
        val key = "c/$pubkey"
        val entry = state[key] ?: return null
        return runCatching { coreGetContact(StateCodec.encode(mapOf(key to entry)), pubkey) }.getOrNull()
    }

    /// Forgets a contact, the one state change no core call makes: the core's `c/` entry goes as the
    /// official apps drop it (`deleteContactFromState`), with the chat's rows in the same transaction
    /// ([Storage.deleteContact]); then the per-chat caches and the attachments' files (decrypted
    /// copies in the cache). Android keeps no outbox of uploads by message id, so there are none to
    /// remove. PeelCore's `forgetContact`.
    internal suspend fun forgetContact(pubkey: String) {
        val attachments = mutex.withLock {
            val next = state - "c/$pubkey"
            // Read first, like iOS: a state the core cannot list throws before anything is removed.
            val people = parseContactPubkeys(coreListContacts(StateCodec.encode(next))).toSet()
            val removed = storage.deleteContact(pubkey)
            state = next
            personKeys = people
            removed
        }
        synchronized(readsSentSinceGetReads) { readsSentSinceGetReads -= pubkey }
        synchronized(mutesSetSinceGetMutes) { mutesSetSinceGetMutes -= pubkey }
        removeAttachmentFiles(attachments)
    }

    private fun removeAttachmentFiles(rows: List<com.sphinx.peel.storage.MessageRow>) {
        val dir = storage.attachmentCacheDir()
        for (row in rows) {
            val muid = AttachmentFields.decode(row.body)?.mediaToken?.let { MediaToken.parse(it) }
                ?.takeIf { MediaToken.isSafeMuid(it.muid) }?.muid ?: continue
            runCatching { java.io.File(dir, muid).delete() }
            runCatching { java.io.File(dir, "$muid.name").delete() }
        }
    }

    @Volatile private var personKeys: Set<String> = emptySet()

    /// The account's own name and photo, from the messages it sent (peel's
    /// `recoverOwnProfile`): the server keeps no profile. Only what is not
    /// stored yet: an alias that is empty/absent, a photo that is absent (an
    /// empty stored value means "set or removed here" and wins).
    private fun recoverOwnProfile() {
        val settings = mutableMapOf<Setting, String>()
        if (storage.value(Setting.ALIAS).isNullOrBlank()) {
            storage.newestOwnAlias()?.let { settings[Setting.ALIAS] = it }
        }
        if (storage.value(Setting.PHOTO_URL) == null) {
            storage.newestOwnPhotoUrl()?.let { settings[Setting.PHOTO_URL] = it }
        }
        // The profile screen reads these straight from storage (same
        // `Commit(settings=...)` it writes), so there is no flow to update.
        if (settings.isNotEmpty()) storage.apply(Storage.Commit(settings = settings))
    }

    /// iOS's post-restore read pass (`historyEnded`): the first `getReads`
    /// came before the restore rebuilt the contacts, and the core drops
    /// markers for contacts it does not know — the root cause of every
    /// chat showing unread after a restore. So ask `getReads` again now
    /// that history is stored, then mark every chat whose newest message is ours as read
    /// (`setLastMessagesOnChats`). Chats whose newest message is incoming
    /// are left alone.
    private suspend fun settleReadsAfterRestore() {
        fetchReadsAndMutesAwaiting()
        for (conversation in storage.conversations()) {
            if (storage.newestMessageFromMe(conversation.chat)) markRead(conversation.chat)
        }
    }

    /// Fetches every message from where this device's own history stands
    /// forward — PeelCore's own `syncMessages`, run on *every* connection
    /// (`cameOnline` → `resumeHistory`), not only a fresh restore's: a
    /// message that arrived while this device had no live connection to
    /// hear it on (closed, backgrounded, or just between `start()` calls)
    /// is otherwise never seen again, since nothing besides a restore's
    /// own pipeline ever asked the server for history before this existed.
    /// Where history stands is read back from storage itself
    /// (`Storage.maxServerIndex`) rather than kept as its own setting
    /// (PeelCore's `Setting.historyFrom`): every message already carries
    /// its own server index, so the highest one stored *is* that marker,
    /// with nothing to keep in sync by hand. Unlike PeelCore's
    /// `HistoryCursor`, nothing here tracks a gap a live message arriving
    /// ahead of a catch-up might leave below it — not reachable yet
    /// without the reconnect/live-overlap machinery this port doesn't have.
    private suspend fun catchUpMessages(): Boolean {
        val start = ((storage.maxServerIndex() ?: -1L) + 1L).toULong()
        return page(HistoryPhase.MESSAGES, end = null, start = start) { time, stateBytes, next ->
            coreFetchMsgsBatch(seed, time, stateBytes, next, HISTORY_PAGE_SIZE, false)
        }
    }

    /// One phase's pages, back to back, each one committed (state, the
    /// messages it carried, the contacts they reveal) before the next is
    /// asked for. With [total] (contacts: an item count), progress is the
    /// number of messages fetched so far out of it, not a server index. Starts at [start] (0 for a restore's own phases; where
    /// this device's history already stands, for a catch-up — see
    /// `catchUpMessages`). Stops on no answer in [historyWaitMs] (PeelCore's
    /// watchdog, simplified to "stop" rather than "resume later"), an
    /// empty page (the server has nothing more for this phase), or once
    /// `next` passes `end`.
    private suspend fun page(
        phase: HistoryPhase,
        end: Long?,
        start: ULong = 0uL,
        total: Long? = null,
        restored: Long = 0,
        onPage: (next: ULong, items: Long) -> Unit = { _, _ -> },
        call: (String, ByteArray, ULong) -> RunReturn,
    ): Boolean {
        var next = start
        var restored = restored
        if (end != null && next > end.toULong()) return true
        while (true) {
            // Timed deliberately: the protocol gives a history page no
            // request id (`HistoryCursor.advance`'s own comment on
            // PeelCore's side), so a reply can only ever be matched to
            // *some* outstanding ask, never to a specific one — pipelining
            // several pages at once is not a missed optimization here,
            // it is unsafe for either client. Logging each page's own
            // round-trip cost is how to find out whether Android's restore
            // actually is slower than the iPhone's for a reason that *can*
            // be fixed (docs/phases/03-restoring.md, "Open").
            val askedAt = SystemClock.elapsedRealtime()
            val result = askAndAwait(historyWaitMs, expect = { it.msgsTotal != null }) {
                run { time, stateBytes -> call(time, stateBytes, next) }
            }
            Log.d("PeelHistory", "$phase page at $next: ${if (result == null) "timed out" else "${result.msgs.size} msgs"} in ${SystemClock.elapsedRealtime() - askedAt}ms")
            if (result == null) return false
            // Already stored: the same listener that delivered this result
            // to askAndAwait ran it through `storeMessages` first.
            val reached = result.msgs.mapNotNull { it.index?.toULongOrNull() }.maxOrNull()
            restored += result.msgs.size
            val progress = if (total != null) RestoreCount(minOf(restored, total), total) else RestoreCount((reached ?: next).toLong(), end)
            restoreProgressFlow.update {
                if (phase == HistoryPhase.CONTACTS) it.copy(contacts = progress) else it.copy(messages = progress)
            }
            // A page of history may carry the open chat's echoes: ask about
            // the rest (PeelCore's `.history` event -> `checkOwnMessageStatus`).
            openChat?.let { runCatching { checkOwnMessageStatus(it) } }
            if (reached == null) break
            next = reached + 1uL
            onPage(next, result.msgs.size.toLong())
            if (end != null && next > end.toULong()) break
        }
        return true
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
        // A tribe echoes our own boost back with no `msat`; the amount is
        // only in the body's `amount` (iOS does the same, also for another
        // member's tribe boost, recognised by the sender's `host`).
        val inTribe = !sender?.optString("host").isNullOrEmpty()
        val msat = msg.msat?.toLong()
            ?: if (type.toInt() == BOOST_MESSAGE_TYPE && (fromMe || inTribe)) declaredAmountMsat(msg.message) else null
        return MessageRow(
            uuid = msg.uuid,
            serverIndex = msg.index?.toLongOrNull(),
            type = type.toInt(),
            chat = chat,
            fromMe = fromMe,
            sender = msg.sender,
            body = msg.message,
            msat = msat,
            timestamp = msg.timestamp?.toLong(),
            tag = msg.tag,
            paymentHash = msg.paymentHash,
        )
    }

    /// What `row` reveals about the chat it's filed under. Ported from
    /// PeelCore's `Engine+Tribes.swift` (`discoveredTribe`): a tribes
    /// server, not a person, sets `host` on what it forwards — "Set by a
    /// tribes server on the joins it forwards" is that file's own comment
    /// on the field — so its mere presence is the signal, nobody else ever
    /// sends one. `host` has to be checked *first*: a tribe's own messages
    /// also carry `sender.pubkey == chat` (every member's message does,
    /// since the tribe forwards them all under its own channel identity —
    /// confirmed on a real restore: one chat, messages from "Tom_T", then
    /// "Paul", then others, all sharing one pubkey), so without checking
    /// `host` first this read a tribe exactly like a direct contact and
    /// took whichever member happened to post last as if it were the
    /// tribe's own name and photo. A tribe's *real* name and photo are not
    /// in any message at all — PeelCore reads those from the tribes server
    /// itself (`TribeInfo.record`, its own HTTP call).
    ///
    /// Called on `fromMe` rows too, same as `discoveredTribe`'s own
    /// `trustSender || message.fromMe` — a tribe echoing one of *our own*
    /// messages back to us carries `host` exactly the way a member's does,
    /// and on a real account that is sometimes the *only* message a given
    /// tribe chat has (confirmed directly: a handful of this account's
    /// tribes had exactly one stored message, `fromMe`, carrying `host`).
    /// Skipping every `fromMe` row, as an earlier version of both callers
    /// did, missed those entirely. The direct-contact branch below stays
    /// safe for a `fromMe` row too: `sender.pubkey == chat` is actually
    /// common for our own sent messages (confirmed against a real
    /// account's history — a `fromMe` row's `sender` field seems to carry
    /// the *chat partner's* own cached identity, not ours), but that just
    /// extracts a direct contact's own alias/photo from our side of the
    /// conversation, the same information a received message would also
    /// give — nothing a tribe's own forwarding identity would produce
    /// without `host`, which the branch above already claims first.
    private fun contactHint(row: MessageRow): ContactRow? {
        val sender = row.sender?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val host = sender.optString("host").takeIf { it.isNotEmpty() }
        // Peel's isPerson: a chat the core holds a contact key for is a
        // person, never a tribe — no new tribe flag for it (existing rows
        // are not demoted).
        val person = row.chat in personKeys
        if (host != null && !person) {
            return ContactRow(pubkey = row.chat, alias = null, photoUrl = null, isTribe = true, ready = true)
        }
        // iOS: a join/approve's sender pubkey is the tribe's, host or not.
        if (!person && (row.type == GROUP_JOIN_MESSAGE_TYPE || row.type == MEMBER_APPROVE_MESSAGE_TYPE)) {
            return ContactRow(pubkey = row.chat, alias = null, photoUrl = null, isTribe = true, ready = true)
        }
        val pubkey = sender.optString("pubkey").takeIf { it.isNotEmpty() }
        if (pubkey == row.chat) {
            return ContactRow(
                pubkey = row.chat,
                alias = sender.optString("alias").takeIf { it.isNotEmpty() },
                photoUrl = sender.optString("photo_url").takeIf { it.isNotEmpty() },
                isTribe = false,
                ready = false,
            )
        }
        return null
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
        /// How long a chat's own messages are left before they are asked
        /// about again (PeelCore's `ownStatusWait`), and how many tags one
        /// `getTags` carries (`ownStatusChunk`).
        private const val OWN_STATUS_WAIT_MS = 60_000L
        private const val OWN_STATUS_CHUNK = 50

        /// How long a pending text may wait before `checkPendingSends` asks
        /// about it (PeelCore's `Message.confirmationTimeout`).
        private const val RECORDS_WAIT_MS = 10_000L

        /// The official apps' own call server, same host as the main API —
        /// PeelCore's own `Engine.defaultCallServer`.
        private const val DEFAULT_CALL_SERVER = "chat.sphinx.chat"

        /// Bump whenever `backfillContactsIfNeeded`'s own logic changes in
        /// a way that fixes data an older version could have gotten
        /// wrong — this is the third such fix (see `Setting.CONTACTS_BACKFILLED`).
        private const val BACKFILL_VERSION = "4"

        private const val registerWaitMs = 10_000L
        private const val INVITE_REGISTER_WAIT_MS = 30_000L

        /// How long one history ask (counts, or a page) waits for its
        /// answer — PeelCore's own watchdog is 30s (`Engine.historyStall`).
        private const val historyWaitMs = 30_000L

        /// How long a new key's register is given to land before the messages addressed to it go
        /// (PeelCore's `registerSettle`).
        private const val REGISTER_SETTLE_MS = 750L

        /// How long a send waits for the server to take its async pay before publishing the rest
        /// all the same (PeelCore's `asyncPaySettle`).
        private const val ASYNC_PAY_SETTLE_MS = 10_000L
        private val HISTORY_PAGE_SIZE: UInt = 100u

        /// The memes server attachments come from — PeelCore's own
        /// `defaultMemesHost`. Always this fallback: there is no
        /// onboarding step here that lets an account record its own
        /// (`Setting.memesHost` on PeelCore's side), same as this port's
        /// tribe host before `Engine.start` first reads the public
        /// config — except a memes host never comes from there either,
        /// it is simply the one constant every account not on a local
        /// test server uses.
        private const val MEMES_HOST = "memes.sphinx.chat"

        /// The largest attachment this device downloads — PeelCore's
        /// own `attachmentLimit`.
        private const val ATTACHMENT_LIMIT = 100_000_000

        /// The largest video or recording downloaded unasked — PeelCore's
        /// `Engine.autoDownloadLimit`; bigger ones wait for a tap.
        const val AUTO_DOWNLOAD_LIMIT = 25_000_000

        /// The official apps always send a tribe message with at least
        /// this many msat (3 sats), whatever the tribe's own price —
        /// PeelCore's own `TribePrice.messageFloorMsat`.
        private const val MESSAGE_FLOOR_MSAT = 3_000L
    }
}
