package com.sphinx.peel.engine

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Jamie in a tribe, from a box (strut's plans/jamie-in-tribes.md §4, item 7; docs/phases/54-jamie-from-a-box.md): a tribe's owner
// picks, in the tribe's details, which of the boxes Peel is linked to answers `@jamie` there, or No Jamie. A pick links the tribe as a
// place on that box (`POST /people/places`), whose Sphinx daemon follows the list and joins; No Jamie, or another box, takes it off
// the box it was on (`DELETE`). A private tribe's join request from that box's Jamie is approved here, by the key the box answered.
// The Android side of iOS's `TribeJamiePicker` and Stadeum's `jamieOptions`/`jamiePick` (Hive's orgs), for boxes only: Hive's
// tribe linking is not ported (`Hive.kt`). Not ported either: the "Knows who is speaking" switch (`ShowSenders`): Android has no
// way to mark a member to see senders yet.

/// A linked box as a tribe's Jamie row reads it: its places as last read, or why they could not be.
data class JamieBox(val box: BoxLink, val read: BoxJamies.Read?) {
    val places: BoxPlaces? get() = (read as? BoxJamies.Read.Places)?.places
    val problem: String? get() = (read as? BoxJamies.Read.Failed)?.problem
}

/// What a tribe's Jamie row offers and shows picked (pure, JVM-tested in `BoxJamiesTest`).
object TribeJamie {
    /// No Jamie's id.
    const val NONE = ""

    /// A choice in the row: a box (its address) or No Jamie ("").
    data class Option(val id: String, val name: String)

    /// What the row offers: each box whose Sphinx host is ready, by name, in the order they were linked; one the tribe is linked on
    /// even when its host is not ready now, so the row says what is true; then No Jamie. Nothing at all when no box can answer.
    fun options(boxes: List<JamieBox>, tribe: String, noJamie: String): List<Option> {
        val offered = boxes.filter { b -> b.places?.let { it.jamie != null || it.place(tribe) != null } == true }
        if (offered.isEmpty()) return emptyList()
        return offered.map { Option(it.box.url, it.box.host) } + Option(NONE, noJamie)
    }

    /// The box the tribe is answered from (its address), or "" for none: the first box where the person linked it. Null until
    /// every box has answered, while none of those that did names it.
    fun pick(boxes: List<JamieBox>, tribe: String): String? {
        boxes.firstOrNull { it.places?.place(tribe) != null }?.let { return it.box.url }
        return if (boxes.all { it.places != null }) NONE else null
    }

    /// Whether the row shows: a box offers Jamie or holds the tribe, or one could not be read (it may).
    fun shows(boxes: List<JamieBox>, tribe: String): Boolean =
        boxes.any { b -> b.problem != null || b.places?.let { it.jamie != null || it.place(tribe) != null } == true }

    /// The keys of the boxes' Jamies that may join [tribe]: those of the boxes where the person linked it.
    fun jamies(boxes: List<JamieBox>, tribe: String): List<String> =
        boxes.mapNotNull { b -> b.places?.takeIf { it.place(tribe) != null }?.jamie }.distinct()
}

/// Approves a box's Jamie waiting to join a tribe ([Engine.approveWaitingMember] in the app): true once approved, false when it is
/// not waiting (yet).
fun interface JamieApprover {
    suspend fun approve(tribe: String, key: String): Boolean
}

/// The open account's boxes as tribes' Jamies ([TribeJamie]; iOS `Stadeum`'s Jamie links and `TribeJamiePicker`, `AppModel+Jamie`'s
/// approval): each box's places read with its member's token ([BoxesModel.asMember]), a pick linked or unlinked at once, and a join
/// request in a tribe the person linked approved when it is that box's Jamie. Observable: its state is Compose state. Its work runs
/// on [dispatcher], the main thread in the app, as [BoxesModel]'s does.
class BoxJamies(
    private val boxes: BoxesModel,
    private val clientFor: (BoxLink) -> PlacesClient = { PlacesClient(it, sharedHttp) },
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /// One box's places as last read.
    sealed class Read {
        abstract val atMs: Long
        data class Places(val places: BoxPlaces, override val atMs: Long) : Read()
        data class Failed(val problem: String, override val atMs: Long) : Read()
    }

    /// Each box's last read, by [key]: its address and its member, whose places they are.
    private var reads: Map<String, Read> by mutableStateOf(emptyMap())
    /// Whether a read is under way.
    var reading: Boolean by mutableStateOf(false); private set
    /// The tribes a pick is under way in.
    var working: Set<String> by mutableStateOf(emptySet()); private set
    /// Why the last pick in a tribe, or approving its Jamie, did not go through, in words, by tribe.
    var problems: Map<String, String> by mutableStateOf(emptyMap()); private set

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var current: Deferred<Unit>? = null
    /// Counts this device's links and unlinks: a read begun before one does not overwrite what it did.
    private var changes = 0
    /// The tribes a join request is being looked at in, and those a request arrived for meanwhile (looked at again).
    private val checking = mutableSetOf<String>()
    private val again = mutableSetOf<String>()
    /// Approvals sent this run of Peel, as "<tribe> <Jamie's key>": another costs the tribe's price again.
    private val approved = mutableSetOf<String>()

    /// The boxes the open account is linked to ([BoxesModel.links]).
    val links: List<BoxLink> get() = boxes.links

    /// The linked boxes, each with its last read.
    fun boxes(): List<JamieBox> = boxes.links.map { JamieBox(it, reads[key(it)]) }

    /// Reads every linked box's places, unless every one was read in the last [ifOlderThanMs]; one read at a time, joined. A box that
    /// cannot be read keeps why ([JamieBox.problem]).
    suspend fun read(ifOlderThanMs: Long = 0) {
        val now = nowMs()
        if (ifOlderThanMs > 0 && boxes.links.all { b -> reads[key(b)]?.let { it is Read.Places && now - it.atMs < ifOlderThanMs } == true }) return
        val running = current?.takeIf { it.isActive } ?: scope.async { readAll() }.also { current = it }
        running.await()
    }

    private suspend fun readAll() {
        reading = true
        try {
            val links = boxes.links
            val began = changes
            val answers = coroutineScope {
                links.map { box ->
                    async {
                        try {
                            Read.Places(boxes.asMember(box) { clientFor(it).read() }, nowMs())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Read.Failed(BoxesModel.noted(e, "read which tribes have Jamie on ${box.host}"), nowMs())
                        }
                    }
                }.awaitAll()
            }
            // Linked or unlinked here while it was asked: older than what is kept, so the next look asks again.
            if (changes != began) return
            reads = links.indices.associate { key(links[it]) to answers[it] }
        } finally {
            reading = false
        }
    }

    /// [pick], run here: leaving the details meanwhile does not stop it halfway.
    fun choose(id: String, tribe: TribePlace): Job = scope.launch { pick(id, tribe) }

    /// The person picked [id] in [tribe]'s row: a box's address, or No Jamie (""). The tribe is linked on that box, then taken off
    /// every other it was on, so one Jamie answers there. What did not go through is said in [problems]; nothing is retried.
    suspend fun pick(id: String, tribe: TribePlace) {
        val all = boxes()
        if (tribe.key in working || id == TribeJamie.pick(all, tribe.key)) return
        working = working + tribe.key
        problems = problems - tribe.key
        val chosen = all.firstOrNull { it.box.url == id }
        var couldNot = if (id == TribeJamie.NONE) "remove Jamie" else "add Jamie from ${chosen?.box?.host ?: "that Stadeum server"}"
        try {
            if (id != TribeJamie.NONE) {
                chosen ?: throw BoxException("Peel is no longer linked to that Stadeum server.")
                val linked = boxes.asMember(chosen.box) { clientFor(it).link(tribe) }
                changes++
                val was = (reads[key(chosen.box)] as? Read.Places)?.places ?: BoxPlaces.NONE
                reads = reads + (key(chosen.box) to Read.Places(was.linking(linked.place, "${BoxPlaces.SPHINX}:${linked.jamie}"), nowMs()))
            }
            for (other in all) {
                if (other.box.url == id) continue
                val place = other.places?.place(tribe.key) ?: continue
                couldNot = "remove Jamie from ${other.box.host}"
                boxes.asMember(other.box) { clientFor(it).unlink(place.key) }
                changes++
                reads = reads + (key(other.box) to Read.Places(other.places!!.unlinking(tribe.key), nowMs()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problems = problems + (tribe.key to BoxesModel.noted(e, couldNot))
        } finally {
            working = working - tribe.key
        }
    }

    /// A request to join [tribe] arrived, or one waits there unanswered: approved, unasked, when the person linked the tribe on a
    /// box and the one waiting is that box's Jamie (iOS `AppModel.approveJamie`). The request names only an alias, so the tribes
    /// server's list of who waits is asked for the box's key ([JamieApprover]). A request that arrives while a look is under way is
    /// looked at again once it ends.
    fun requested(tribe: String, approver: JamieApprover) {
        if (boxes.links.isEmpty()) return
        if (!checking.add(tribe)) {
            again += tribe
            return
        }
        scope.launch {
            try {
                do {
                    again -= tribe
                    check(tribe, approver)
                } while (tribe in again)
            } finally {
                checking -= tribe
            }
        }
    }

    /// One look at [tribe]'s waiting members for its Jamies. The boxes are read again unless read in the last minute, and again when
    /// none names the tribe (linked on another device just now). The server may list a request a moment after it arrives, so it is
    /// asked up to three times, five seconds apart. Each approval goes once a run of Peel.
    internal suspend fun check(tribe: String, approver: JamieApprover) {
        read(ifOlderThanMs = 60_000)
        var keys = TribeJamie.jamies(boxes(), tribe)
        if (keys.isEmpty()) {
            read()
            keys = TribeJamie.jamies(boxes(), tribe)
        }
        for (jamie in keys) {
            val approval = "${tribe.lowercase()} $jamie"
            if (approval in approved) continue
            for (attempt in 0 until 3) {
                if (attempt > 0) sleep(5_000)
                try {
                    if (approver.approve(tribe, jamie)) {
                        approved += approval
                        break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Still in Members to approve by hand.
                    problems = problems + (tribe to BoxesModel.noted(e, "approve Jamie"))
                    break
                }
            }
        }
    }

    companion object {
        /// One HTTP client for every box's places.
        private val sharedHttp: BoxHttp by lazy { OkHttpBox() }

        /// A box's reads are its member's: another account linked to the same box has its own.
        private fun key(box: BoxLink) = "${box.url} ${box.actor}"
    }
}
