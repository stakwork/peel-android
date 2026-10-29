package com.sphinx.peel.engine

import android.os.SystemClock
import android.util.Log
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
import uniffi.sphinxrs.agentSeed as coreAgentSeed
import uniffi.sphinxrs.fetchFirstMsgsPerKey as coreFetchFirstMsgsPerKey
import uniffi.sphinxrs.fetchMsgsBatch as coreFetchMsgsBatch
import uniffi.sphinxrs.getMsgsCounts as coreGetMsgsCounts
import uniffi.sphinxrs.getMutes as coreGetMutes
import uniffi.sphinxrs.getReads as coreGetReads
import uniffi.sphinxrs.getTribeManagementTopic as coreGetTribeManagementTopic
import uniffi.sphinxrs.handle as coreHandle
import uniffi.sphinxrs.initialSetup as coreInitialSetup
import uniffi.sphinxrs.listContacts as coreListContacts
import uniffi.sphinxrs.makeMediaToken as coreMakeMediaToken
import uniffi.sphinxrs.pubkeyFromSeed
import uniffi.sphinxrs.read as coreMarkRead
import uniffi.sphinxrs.rootSignMs as coreRootSignMs
import uniffi.sphinxrs.send as coreSend
import uniffi.sphinxrs.signBase64 as coreSignBase64
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
                // Every inbound message, live or a restore's own page answer
                // alike: whichever `askAndAwait` is waiting on this one (if
                // any) reads the same already-stored result once it gets it
                // below, not a second copy — `storeMessages` is the one
                // place this happens now.
                storeMessages(handled)
                inboundResults.emit(handled)
            }
        }
    }

    /// `result.msgs` as rows, stored — and, for whichever of them are an
    /// incoming message naming its own sender, the contact that reveals.
    /// Shared by the live listener and a restore's pages: a message is a
    /// message either way.
    private fun storeMessages(result: RunReturn) {
        val rows = result.msgs.mapNotNull { mapMessage(it, accountKey) }
        if (rows.isNotEmpty()) {
            storage.insertMessages(rows)
            for (row in rows) {
                val hint = contactHint(row) ?: continue
                storage.upsertContact(hint.pubkey, hint.alias, hint.photoUrl, hint.isTribe, hint.ready)
            }
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
            storage.markSendStatus(tag, delivered = sentStatus.optString("status") != "FAILED")
        }
        // Read markers and mutes, same "whatever `RunReturn` carries it"
        // reasoning as `sentStatus` — PeelCore's `receive(_:)` special-
        // cases the `"getreads"` topic kind to re-trigger
        // `publishPendingReads`; this port has no per-topic dispatch at
        // all (`startListening` reads whatever a `RunReturn` carries,
        // generically), so `fetchReadsAndMutes` triggers that itself once
        // its own `askAndAwait` returns, rather than from here.
        parseIndexMap(result.lastRead)?.let { storage.applyServerReads(it) }
        parseIndexMap(result.muteLevels)?.let { levels -> storage.applyServerMutes(levels.mapValues { it.value.toInt() }) }
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
    /// mentions again stays `PENDING` forever: no retry, no timeout.
    suspend fun sendMessage(chat: String, text: String, isTribe: Boolean, replyTo: String? = null, inThread: String? = null): MessageRow {
        check(transport != null) { "Not connected." }
        val body = JSONObject().put("content", text).apply {
            if (replyTo != null) put("replyUuid", replyTo)
            if (inThread != null) put("threadUuid", inThread)
        }.toString()
        val myAlias = storage.value(Setting.ALIAS) ?: ""
        val myPhoto = storage.value(Setting.PHOTO_URL) ?: ""
        val result = run { time, stateBytes ->
            coreSend(seed, time, chat, 0u, body, stateBytes, myAlias, myPhoto, 0u, isTribe)
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
            msat = null,
            timestamp = System.currentTimeMillis(),
            tag = sent?.tag,
            paymentHash = sent?.paymentHash,
            status = "PENDING",
        )
        storage.insertMessages(listOf(row))
        return row
    }

    /// Boosts someone else's message: a type 29 message naming it by
    /// `replyUuid`, carrying `BOOST_AMOUNT_MSAT` — PeelCore's own
    /// `quickBoost`, trimmed to its everyday one-tap path. No amount
    /// picker and no confirmation threshold: both depend on a balance
    /// this port doesn't track (no Payments tab, no payment history —
    /// see `BOOST_AMOUNT_MSAT`'s own doc comment), so there's nothing
    /// to check the amount against here; a boost that can't actually
    /// be afforded fails the same way any other send can, surfaced as
    /// a plain error, not predicted ahead of time. Unlike PeelCore,
    /// never adds a tribe's own price-per-message to the total either:
    /// that needs a `tribe` table (price, escrow) this port has never
    /// had (`docs/PLAN.md`'s own "No tribe... tables yet") — a tribe
    /// that charges for messages may refuse an underpriced boost the
    /// same way it would refuse an underpriced text send.
    suspend fun sendBoost(chat: String, targetUuid: String, isTribe: Boolean): MessageRow {
        check(transport != null) { "Not connected." }
        val body = JSONObject().apply {
            put("content", "")
            put("replyUuid", targetUuid)
        }.toString()
        val myAlias = storage.value(Setting.ALIAS) ?: ""
        val myPhoto = storage.value(Setting.PHOTO_URL) ?: ""
        val result = run { time, stateBytes ->
            coreSend(seed, time, chat, BOOST_MESSAGE_TYPE.toUByte(), body, stateBytes, myAlias, myPhoto, BOOST_AMOUNT_MSAT.toULong(), isTribe)
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
            msat = BOOST_AMOUNT_MSAT,
            timestamp = System.currentTimeMillis(),
            tag = sent?.tag,
            paymentHash = sent?.paymentHash,
            status = "PENDING",
        )
        storage.insertMessages(listOf(row))
        return row
    }

    /// Downloads and decrypts an attachment into this account's cache,
    /// or finds it there already — PeelCore's `downloadAttachment`,
    /// trimmed to receiving (this port never sends one yet,
    /// `docs/phases/08-attachments.md`): no upload, no price, no "sell
    /// it" path, and no overall cache-size eviction (PeelCore prunes its
    /// cache at a 500MB total; Android's own cache directory is already
    /// OS-managed under storage pressure, and every file here is always
    /// re-derivable from the server plus its key, so that's an accepted
    /// gap, not a silent one).
    suspend fun downloadAttachment(row: MessageRow): DownloadedAttachment {
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

        val memes = MemesServer(MEMES_HOST)
        val limit = ATTACHMENT_LIMIT + 128 // Encryption adds a header, padding and a MAC.
        val (sealed, fileName) = withMemes(memes) { auth -> memes.download(mediaToken, auth, limit) }
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
        }.toString()
        val myAlias = storage.value(Setting.ALIAS) ?: ""
        val myPhoto = storage.value(Setting.PHOTO_URL) ?: ""
        val result = run { time, stateBytes ->
            coreSend(seed, time, chat, ATTACHMENT_MESSAGE_TYPE.toUByte(), body, stateBytes, myAlias, myPhoto, 0u, isTribe)
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
            msat = null,
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
    /// `markRead`, trimmed to its one case this port needs (no
    /// `markUnread`, no new-contact clearing). Offline, the marker is kept
    /// as pending (`Storage.setRead`) rather than lost, and
    /// `fetchReadsAndMutes`'s `publishPendingReads` sends it once a
    /// connection answers `getReads`.
    suspend fun markRead(chat: String) {
        val index = storage.highestServerIndex(chat) ?: return
        val connected = transport != null
        if (!storage.setRead(chat, index, pending = !connected)) return
        if (connected) {
            run { time, stateBytes -> coreMarkRead(seed, time, stateBytes, chat, index.toULong()) }
        }
    }

    /// Read markers and mutes live on the server, shared by every device
    /// — PeelCore's `fetchReadsAndMutes`, called once per connection
    /// (`start`/`resume`), same as a restore's catch-up. `getReads`'s and
    /// `getMutes`' own answers arrive asynchronously, same asymmetry as
    /// every other history ask, and land in `storeMessages` (which
    /// `askAndAwait`'s listener already runs every inbound `RunReturn`
    /// through) — this only sends the asks and then flushes whatever this
    /// device moved locally while it had nothing to send it to.
    private suspend fun fetchReadsAndMutes() {
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
        // So the next `connect()` starts a fresh collector on the new
        // Transport: without this, `startListening` stayed a no-op after
        // the first connection forever, and a reconnect's own
        // `askAndAwait` calls (`resume`'s catch-up) would wait out their
        // full timeout for an answer nothing was ever listening to deliver.
        listenerStarted = false
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
            backfillContactsIfNeeded()
            if (!connectAndRegister()) return@launch
            fetchReadsAndMutes()
            if (restoreProgressFlow.value.restoring) runRestore() else catchUpMessages()
            refreshTribeNames()
        }
    }

    /// Resolves the broker and connects, exactly as `start()`'s own first
    /// connection does — shared so `resume()` can do the same thing
    /// without duplicating it. Updates [connectionState] either way; false
    /// on any failure (already reported through [connectionState]), so
    /// the caller knows not to go on to a catch-up with nothing to ask.
    private suspend fun connectAndRegister(): Boolean {
        connectionStateFlow.value = ConnectionState.Connecting
        return try {
            val config = ServerConfig.fetch(network)
            config.tribeHost?.let { storage.apply(Storage.Commit(settings = mapOf(Setting.TRIBE_HOST to it))) }
            connect(ServerAddress.named(config.defaultLsp))
            connectionStateFlow.value = if (isRegistered) {
                ConnectionState.Connected
            } else {
                ConnectionState.Failed("No registration answer came back in time.")
            }
            isRegistered
        } catch (e: Exception) {
            connectionStateFlow.value = ConnectionState.Failed(e.message ?: "Could not connect to the server.")
            false
        }
    }

    /// Called whenever the app returns to the foreground, after `start()`
    /// has already run once (`MainActivity`'s lifecycle observer) —
    /// PeelCore's own reconnect-on-foreground plus `cameOnline`'s catch-up,
    /// trimmed to what this port can actually tell: nothing here can tell
    /// a connection that is still good from one the OS quietly killed
    /// while backgrounded (no ping/keep-alive loop exists yet to notice),
    /// so this always reconnects first rather than guess from `transport`
    /// being non-null. A no-op before `start()` has ever run.
    fun resume() {
        if (!started) return
        scope.launch {
            disconnect()
            if (!connectAndRegister()) return@launch
            fetchReadsAndMutes()
            if (!restoreProgressFlow.value.restoring) catchUpMessages()
        }
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
        val host = storage.value(Setting.TRIBE_HOST) ?: return
        for (tribe in storage.conversations()) {
            if (!tribe.isTribe || tribe.alias != null) continue
            val info = try {
                TribeInfo.fetch(tribe.chat, host)
            } catch (e: Exception) {
                continue
            }
            storage.updateTribeInfo(tribe.chat, info.name, info.img)
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
                // Once, with every contacts page's own state already
                // folded in — not per page: `listContacts` decodes the
                // core's *entire* contact list every time it's asked, and
                // the final state after this phase already includes
                // everything any one of its pages revealed.
                storage.markContactsReady(parseContactPubkeys(coreListContacts(StateCodec.encode(state))))
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
    private suspend fun catchUpMessages() {
        val start = ((storage.maxServerIndex() ?: -1L) + 1L).toULong()
        page(HistoryPhase.MESSAGES, end = null, start = start) { time, stateBytes, next ->
            coreFetchMsgsBatch(seed, time, stateBytes, next, HISTORY_PAGE_SIZE, false)
        }
    }

    /// One phase's pages, back to back, each one committed (state, the
    /// messages it carried, the contacts they reveal) before the next is
    /// asked for. Starts at [start] (0 for a restore's own phases; where
    /// this device's history already stands, for a catch-up — see
    /// `catchUpMessages`). Stops on no answer in [historyWaitMs] (PeelCore's
    /// watchdog, simplified to "stop" rather than "resume later"), an
    /// empty page (the server has nothing more for this phase), or once
    /// `next` passes `end`.
    private suspend fun page(phase: HistoryPhase, end: Long?, start: ULong = 0uL, call: (String, ByteArray, ULong) -> RunReturn) {
        var next = start
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
            if (result == null) break
            // Already stored: the same listener that delivered this result
            // to askAndAwait ran it through `storeMessages` first.
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
        if (host != null) {
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
        /// Bump whenever `backfillContactsIfNeeded`'s own logic changes in
        /// a way that fixes data an older version could have gotten
        /// wrong — this is the third such fix (see `Setting.CONTACTS_BACKFILLED`).
        private const val BACKFILL_VERSION = "3"

        private const val registerWaitMs = 10_000L

        /// How long one history ask (counts, or a page) waits for its
        /// answer — PeelCore's own watchdog is 30s (`Engine.historyStall`).
        private const val historyWaitMs = 30_000L
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
    }
}
