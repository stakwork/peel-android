package com.sphinx.peel.engine

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

/// Where a device's Hive pairing is kept (iOS `SecretItem`, `Profile.hive`):
/// a Keystore-wrapped file per account on a phone (`KeystoreHiveStore`).
interface HiveStore {
    /// The kept text, or null when none is kept.
    fun read(): String?
    fun write(text: String)
    fun delete()
}

/// The person's Stadeum workspaces on this device (iOS `Stadeum`, Hive's
/// device flow): paired once, the credential and each org's token kept with
/// the account in a [HiveStore], renewed whenever due while the account is
/// open. Observable: its state is Compose state, so what reads [live] or
/// [status] recomposes when they change. Not ported: the Mac's `StadeumHost`,
/// Jamie calls and tribes, and reading each workspace's apps here
/// (`readApps`; `AppsScreen` reads them itself).
class StadeumModel(
    private val clientFor: () -> HiveClient = { HiveClient() },
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Instant = { Instant.now() },
    /// How long the pairing and renewal loops wait (milliseconds).
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    sealed class Status {
        object Off : Status()
        /// A code waiting for the person's yes in Stadeum.
        data class Pairing(val pairing: com.sphinx.peel.engine.Pairing) : Status()
        object Connected : Status()
        /// Stadeum no longer knows this device (revoked): connect again.
        object NeedsPairing : Status()
    }

    var status: Status by mutableStateOf(Status.Off); private set
    /// What is kept: the orgs, and those Stadeum gave no record for.
    var link: HiveLink? by mutableStateOf(null); private set
    /// The last thing that went wrong, in words; null once something works.
    var problem: String? by mutableStateOf(null)
    /// A restored account has no pairing: the section says to connect again.
    var reconnectHint: Boolean by mutableStateOf(false); private set

    /// Stadeum answered 401: the orgs stay listed, but their tokens are no longer used ([live]).
    val revoked: Boolean get() = link?.revokedAt != null
    /// The orgs in use: none once revoked.
    val live: List<HivePeer> get() = if (revoked) emptyList() else link?.peers ?: emptyList()
    /// Whether Stadeum knows the person's Sphinx account (a tribe that tells Jamie who is speaking runs their `@jamie` as them).
    val identified: Boolean get() = link?.identified == true
    val isOpen: Boolean get() = store != null

    /// This account's proof of its Sphinx key; null while it has none open.
    /// Without it (or on an engine that cannot sign) identify is skipped.
    var sphinxProof: (suspend () -> SphinxProof?)? = null

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var store: HiveStore? = null
    private var restoredBeforeOpen = false
    private var pairingJob: Job? = null
    private var renewing: Job? = null
    private var identifying: Pair<Job, String>? = null
    private var nap: CompletableDeferred<Unit>? = null
    private var pollSoon = false
    private var generation = 0
    private val identifiedAtOpen = mutableSetOf<String>()
    private val lock = Any()

    /// The account opened: what it kept, renewed whenever due while it stays
    /// open, and with [renewNow] at once too.
    fun open(store: HiveStore, renewNow: Boolean = true) {
        close()
        this.store = store
        try {
            link = store.read()?.let { text -> HiveLink.parse(text) ?: throw HiveException("Peel could not read your Stadeum connection.") }
        } catch (e: Exception) {
            problem = noted(e, "read your Stadeum connection")
        }
        val restored = restoredBeforeOpen
        restoredBeforeOpen = false
        val current = link
        if (current == null) {
            if (restored) reconnectHint = true
            return
        }
        status = if (revoked) Status.NeedsPairing else Status.Connected
        keepRenewed(renewNow)
        dropIfAnotherAccounts()
        if (revoked) return
        if ((current.identified != true || current.sphinxPubkey == null) && identifiedAtOpen.add(current.credential)) identify()
    }

    /// The account was restored from its words: with no pairing to show for
    /// it, the section says to connect again.
    fun accountRestored() {
        if (!isOpen) { restoredBeforeOpen = true; return }
        if (link == null) reconnectHint = true
        dropIfAnotherAccounts()
    }

    fun dismissReconnectHint() { reconnectHint = false }

    private fun dropIfAnotherAccounts() {
        val kept = link ?: return
        val proof = sphinxProof ?: return
        if (kept.sphinxPubkey == null) return
        val gen = generation
        scope.launch {
            val pubkey = runCatching { proof() }.getOrNull()?.pubkey ?: return@launch
            val now = link
            if (gen != generation || now == null || now.belongs(pubkey)) return@launch
            disconnect()
            reconnectHint = true
        }
    }

    /// The account closed (or locked): everything let go. [open] reads it again.
    fun close() {
        reset()
        store = null
    }

    /// Connect Stadeum: a code to approve there, naming this device [name],
    /// polled until the person answers or it runs out.
    fun connect(name: String) {
        if (store == null) return
        pairingJob?.cancel()
        problem = null
        reconnectHint = false
        pollSoon = false
        val client = clientFor()
        val gen = generation
        lateinit var job: Job
        fun settle(text: String) {
            if (!job.isActive || gen != generation) return
            pairingJob = null
            status = if (link == null) Status.Off else Status.NeedsPairing
            problem = text
        }
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val asked = now()
                val pairing = client.start(name)
                if (!isActive) return@launch
                status = Status.Pairing(pairing)
                val deadline = asked.plusSeconds(pairing.expiresIn.toLong())
                when (val poll = client.answer(pairing, name, deadline) { napUntilNextPoll(it) }) {
                    HiveClient.Poll.Pending -> return@launch
                    HiveClient.Poll.Denied -> settle("It was turned down in Stadeum.")
                    HiveClient.Poll.Expired -> {
                        val minutes = maxOf(1, (pairing.expiresIn + 59) / 60)
                        settle(
                            if (!now().isBefore(deadline))
                                "The code ran out before pairing finished. Connect again, then approve it in Stadeum and come back to Peel within $minutes ${if (minutes == 1) "minute" else "minutes"}."
                            else "Stadeum no longer has this code, so pairing didn't finish. Connect again.",
                        )
                    }
                    is HiveClient.Poll.Paired -> {
                        if (!isActive || gen != generation) return@launch
                        // Recorded now, with the account that paired: identify may not get to it.
                        var paired = poll.link
                        sphinxProof?.let { proof -> paired = paired.copy(sphinxPubkey = runCatching { proof() }.getOrNull()?.pubkey ?: paired.sphinxPubkey) }
                        if (!isActive || gen != generation) return@launch
                        keep(paired)
                        status = Status.Connected
                        keepRenewed(false)
                        identify()
                    }
                }
            } catch (e: CancellationException) {
            } catch (e: Exception) {
                settle(noted(e, "connect Stadeum"))
            }
        }
        pairingJob = job
        job.start()
    }

    fun cancelPairing() {
        pairingJob?.cancel()
        pairingJob = null
        status = if (link == null) Status.Off else Status.NeedsPairing
    }

    /// Disconnect: the credential and the tokens gone from this device.
    /// Stadeum keeps the device until it is revoked there.
    fun disconnect() {
        val store = store ?: return
        reset()
        reconnectHint = false
        try { store.delete() } catch (e: Exception) { problem = noted(e, "forget the Stadeum connection") }
    }

    /// Peel came back to the front: a pairing under way polls Hive now (the
    /// person likely just approved it in the browser), and the workspaces are renewed if due.
    fun cameToFront() {
        if (status is Status.Pairing) {
            val waiting = nap
            if (waiting != null) waiting.complete(Unit) else pollSoon = true
        }
        renewIfDue()
    }

    /// The pairing's wait between polls: none when Peel has just come to the
    /// front, and cut short when it does.
    private suspend fun napUntilNextPoll(seconds: Long) {
        if (pollSoon) { pollSoon = false; return }
        val waiting = CompletableDeferred<Unit>()
        nap = waiting
        try {
            coroutineScope {
                val timer = launch { sleep(seconds * 1000); waiting.complete(Unit) }
                waiting.await()
                timer.cancel()
            }
        } finally {
            if (nap === waiting) nap = null
        }
    }

    /// Renews now if due: Peel back in front, where the hourly look may have slept through its turn.
    internal fun renewIfDue() {
        val kept = link ?: return
        if (kept.revokedAt != null || !kept.due(now())) return
        keepRenewed(true)
    }

    private fun keepRenewed(now: Boolean) {
        renewing?.cancel()
        val gen = generation
        renewing = scope.launch {
            var first = now
            while (isActive && gen == generation) {
                val kept = link
                // A revoked device's credential answers nothing but 401: it waits to be paired again.
                if (kept != null && kept.revokedAt == null && (first || kept.due(this@StadeumModel.now()))) renew(kept)
                first = false
                sleep(3_600_000)
            }
        }
    }

    internal suspend fun renew(kept: HiveLink) {
        val gen = generation
        try {
            val renewed = clientFor().renew(kept, now())
            if (gen != generation || link?.credential != kept.credential) return
            if (renewed == null) {
                // Revoked: its tokens stop being used now, and after a restart too (kept with the link).
                val revokedLink = kept.copy(revokedAt = now())
                runCatching { store?.write(revokedLink.toJson()) }
                link = revokedLink
                if (status !is Status.Pairing) status = Status.NeedsPairing
                return
            }
            keep(renewed.copy(sphinxRefused = renewed.sphinxRefused ?: link?.sphinxRefused))
            if (renewed.identified == false) identify()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Tried again within the hour; the tokens last a week.
            if (gen == generation && link?.credential == kept.credential) problem = noted(e, "renew your Stadeum workspaces")
        }
    }

    /// Keeps [next] (the store first).
    private fun keep(next: HiveLink) {
        val store = store ?: return
        store.write(next.toJson())
        link = next
        problem = null
    }

    /// Tells Stadeum this account's Sphinx key, so a tribe that tells Jamie
    /// who is speaking runs the person's `@jamie` as them: after a pairing,
    /// once a launch while not known, and after a renewal that says not.
    /// Skipped without a [sphinxProof]. A refusal is said once and not asked again.
    private fun identify() {
        val kept = link ?: return
        val proof = sphinxProof ?: return
        if (revoked) return
        identifying?.let { (job, credential) -> if (credential == kept.credential) return else job.cancel() }
        val gen = generation
        val job = scope.launch {
            try {
                val made = proof() ?: return@launch
                if ((link ?: kept).sphinxRefused?.pubkey == made.pubkey) return@launch
                val said = clientFor().registerSphinx(kept, made)
                var current = link
                if (!isActive || gen != generation || current == null || current.credential != kept.credential) return@launch
                when (said) {
                    HiveClient.SphinxRegistration.NotYet -> return@launch
                    HiveClient.SphinxRegistration.Linked -> current = current.copy(identified = true, sphinxPubkey = made.pubkey)
                    is HiveClient.SphinxRegistration.Refused -> current = current.copy(sphinxRefused = SphinxRefusal(made.pubkey, said.reason))
                }
                store?.write(current.toJson())
                link = current
                if (said is HiveClient.SphinxRegistration.Refused) problem = said.reason
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Asked again at the next launch or renewal that finds it unknown.
            } finally {
                if (identifying?.second == kept.credential) identifying = null
            }
        }
        identifying = job to kept.credential
    }

    /// An error in words: Hive's own, or what could not be done and why.
    private fun noted(e: Exception, couldNot: String): String =
        if (e is HiveException) e.message ?: "Stadeum could not $couldNot." else "Peel could not $couldNot: ${e.message ?: e.javaClass.simpleName}."

    private fun reset() {
        generation++
        pairingJob?.cancel(); renewing?.cancel(); identifying?.first?.cancel()
        pairingJob = null; renewing = null; identifying = null; nap = null
        pollSoon = false
        status = Status.Off
        link = null
        problem = null
    }
}

/// What Android knows of Stadeum pairing: one [StadeumModel] for the open
/// account (iOS `AppModel.stadeum`). The Dashboard's Apps row and Apps read
/// [live] and [talkHomes] from it; they recompose as the pairing changes.
object Stadeum {
    val model = StadeumModel()

    val live: List<HivePeer> get() = model.live
    /// A development workspace to talk to Jamie on; none is configured here.
    @Volatile var developmentHome: TalkHome? = null

    /// The Dashboard's Apps row shows only with something to show (iOS
    /// `ChatsTab`: `!stadeum.live.isEmpty || talk.developmentHome != nil`).
    val showsApps: Boolean get() = showsApps(live, developmentHome)

    /// One Talk to Jamie row per live workspace, then the development home.
    val talkHomes: List<TalkHome>
        get() = live.map { TalkHome(it.id, it.name, it.baseUrl) } + listOfNotNull(developmentHome)

    /// What the Stadeum rows say once Stadeum knows the person's Sphinx account.
    const val IDENTIFIED_LINE =
        "Stadeum knows your Sphinx account: in a tribe where Jamie knows who is speaking, your @jamie runs as you."

    internal fun showsApps(live: List<HivePeer>, home: TalkHome?) = live.isNotEmpty() || home != null
}
