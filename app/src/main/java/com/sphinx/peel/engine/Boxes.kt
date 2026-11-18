package com.sphinx.peel.engine

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/// The strut boxes this account is linked to (strut's plans/people-and-hosts.md §2.1; [BoxLink]; iOS `PeelUI/Boxes.swift`,
/// docs/phases/45-box-link.md): a box's `peel://strut/link` waits for the person's yes ("Link Peel to <host> as <name>?"), is signed
/// with the account's key and kept beside Stadeum's pairing ([BoxStore]), its token renewed by signing again about daily while the
/// account is open. A box whose member removed the link (`403 not_linked`) is let go and said once. Opening one shows its own strut
/// UI as the member, where its apps are listed and opened ([home]). Observable: its state is Compose state.
///
/// [dispatcher] is where its work runs and its state changes: the main thread in the app (iOS's `@MainActor`), so nothing here
/// races the screens that read it.
class BoxesModel(
    private val clientFor: (String) -> BoxClient = { BoxClient(it, sharedHttp) },
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Instant = { Instant.now() },
    /// How long the renewal loop waits (milliseconds).
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /// Whether a box on this device's own address may be linked and sent a token: a debug build's local strut (iOS: a
    /// development profile).
    private val allowsLoopback: Boolean = false,
) {
    /// The boxes linked, in the order they were linked.
    var links: List<BoxLink> by mutableStateOf(emptyList()); private set
    /// A box's link waiting for the person's yes ([take]).
    var asking: BoxLinkOffer? by mutableStateOf(null); private set
    /// The link being signed and posted, after the person's yes ([accept]).
    var linking: BoxLinkOffer? by mutableStateOf(null); private set
    /// The last thing that went wrong, in words; null once something works.
    var problem: String? by mutableStateOf(null)
    /// A box let go because its member removed the link there: said once.
    var notice: String? by mutableStateOf(null)

    /// Signs a box's challenge with the open account's key ([Engine.signBoxLink]); null while no account is open.
    var sign: (suspend (BoxChallenge) -> VisitorSignature)? = null
    /// The open account's own name (its alias), sent with a link.
    var accountName: (() -> String?)? = null

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var store: BoxStore? = null
    /// What the account kept could not be read: nothing is written over it (a new link would replace every box kept) until it is
    /// read again.
    private var unreadable = false
    private var renewing: Job? = null
    /// Whether the renewal loop is renewing right now (not sleeping): Peel coming to the front leaves it be.
    private var renewingNow = false
    /// Counts closes: what was begun before the last one keeps and says nothing.
    private var generation = 0

    /// Whether an account is open ([open]): linking needs one.
    val isOpen: Boolean get() = store != null

    /// The account opened: what it kept, renewed whenever due while it stays open.
    fun open(store: BoxStore) {
        // Why a link opened before the account did was refused, still said.
        val said = if (this.store == null) problem else null
        close()
        this.store = store
        problem = said
        try {
            links = BoxLink.read(store)
        } catch (e: Exception) {
            unreadable = true
            problem = noted(e, "read your linked boxes")
        }
        keepRenewed()
    }

    /// The account closed (or locked): its boxes let go here, read again when it opens. A link waiting for a yes stays: one opened
    /// while Peel was locked waits for it.
    fun close() {
        generation++
        renewing?.cancel()
        renewing = null
        renewingNow = false
        store = null
        unreadable = false
        links = emptyList()
        linking = null
        problem = null
        notice = null
    }

    /// The account is forgotten: its boxes go with it, since the links are its key's (iOS `Profile.delete`). A restore links again
    /// from the box.
    fun forgetAll() {
        val store = store
        close()
        asking = null
        try { store?.delete() } catch (e: Exception) { problem = noted(e, "forget your linked boxes") }
    }

    // Linking

    /// A box's `peel://strut/link?…`: it waits for the person's yes, unless Peel will not sign it ([BoxLinkOffer.problem]), which is
    /// said instead.
    fun take(offer: BoxLinkOffer) {
        problem = null
        notice = null
        val refusal = offer.problem(now().toEpochMilli(), allowsLoopback)
        if (refusal != null) {
            asking = null
            problem = refusal
            return
        }
        asking = offer
    }

    /// A `peel://strut/…` link that is not a box's link: said, nothing asked.
    fun takeBroken() {
        asking = null
        notice = null
        problem = BoxLinkOffer.BROKEN
    }

    /// No to the waiting link: nothing is signed.
    fun decline() {
        asking = null
    }

    /// Yes to the waiting link, taken at once ([asking] is null from here): signed with the account's key and posted to the box,
    /// and the link it answers kept. A challenge is good once, so a refused one is not offered again: the box's Connect makes
    /// another. The work, for a caller that waits for it; null when there was nothing to link.
    fun accept(): Job? {
        val offer = asking ?: return null
        asking = null
        linking?.let { busy ->
            problem = "Peel is still linking to ${busy.host}. Open the link from ${offer.host} again once it's done."
            return null
        }
        val store = store
        val sign = sign
        if (store == null || sign == null) {
            problem = "Unlock Peel to link it to ${offer.host}."
            return null
        }
        if (unreadable) {
            problem = "Peel couldn't read the boxes it keeps, so it won't change them now. Quit and reopen Peel, then link again."
            return null
        }
        offer.problem(now().toEpochMilli(), allowsLoopback)?.let { problem = it; return null }
        val gen = generation
        linking = offer
        problem = null
        notice = null
        val name = accountName?.invoke()
        return scope.launch {
            try {
                val signed = sign(offer.challenge)
                val link = clientFor(offer.box).link(offer.challenge, signed, name, now())
                if (gen != generation) return@launch
                keep(link)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) problem = noted(e, "link Peel to ${offer.host}")
            } finally {
                if (gen == generation) linking = null
            }
        }
    }

    /// Forget: the box's link and token gone from this device. The box keeps the link until its member removes it in Connect there.
    fun forget(box: BoxLink) {
        val store = store ?: return
        if (unreadable) return
        val kept = links.filter { it.url != box.url }
        try {
            BoxLink.write(kept, store)
            links = kept
            problem = null
        } catch (e: Exception) {
            problem = noted(e, "forget ${box.host}")
        }
    }

    // Opening

    /// The box's own strut UI as its member, to open ([BoxLink.home]): its token checked first. One the box no longer takes (a 401)
    /// is renewed once; a box that no longer links the key is let go ([notice]), as is one that refuses even a renewed token.
    /// Throws why it does not open.
    suspend fun home(box: BoxLink): OrgPageRequest {
        val kept = links.firstOrNull { it.url == box.url } ?: throw BoxException("Peel is no longer linked to ${box.host}.")
        if (clientFor(kept.url).accepts(kept)) return kept.home
        val renewed = renew(kept) ?: throw BoxException(unlinked(kept))
        if (!clientFor(renewed.url).accepts(renewed)) {
            drop(renewed)
            throw BoxException(unlinked(renewed))
        }
        return renewed.home
    }

    /// [home], or null with why it does not open in [problem] (or [notice], a box let go).
    suspend fun page(box: BoxLink): OrgPageRequest? {
        val gen = generation
        return try {
            val page = home(box)
            // Closed meanwhile: its token is not handed on.
            if (gen == generation) page else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Superseded) {
            null
        } catch (e: Exception) {
            if (gen == generation && links.any { it.url == box.url }) problem = noted(e, "open ${box.host}")
            null
        }
    }

    /// Whether [page] is a box's this account is linked to: it stays open while the box does.
    fun shows(page: OrgPageRequest): Boolean = links.any { it.peer == page.peer }

    // Renewing

    /// Peel came back to the front: a box due is renewed now, where the hourly look may have slept through its turn (Android's own;
    /// `StadeumModel.cameToFront` does the same for Stadeum).
    fun cameToFront() {
        if (store == null || renewingNow || links.none { it.due(now()) }) return
        keepRenewed()
    }

    /// Renews each box when due, looking every hour while the account is open.
    private fun keepRenewed() {
        renewing?.cancel()
        val gen = generation
        renewing = scope.launch {
            while (isActive && gen == generation) {
                renewingNow = true
                try { renewDue() } finally { if (gen == generation) renewingNow = false }
                sleep(3_600_000)
            }
        }
    }

    /// Renews every box that is due ([BoxLink.due]). A failure is said and tried again within the hour: the token lasts a week, and
    /// the key, which renews it, lasts.
    internal suspend fun renewDue(at: Instant = now()) {
        val gen = generation
        for (box in links.filter { it.due(at) }) {
            try {
                renew(box, at)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Superseded) {
                // Forgotten meanwhile: the others are still renewed.
                if (gen != generation) return
            } catch (e: Exception) {
                if (gen != generation) return
                problem = noted(e, "renew Peel's link to ${box.host}")
            }
        }
    }

    /// A new token for [box], kept; null when the box no longer links the key (`403 not_linked`): it is let go and [notice] says so.
    /// Throws [Superseded] when the account closed or the box was forgotten meanwhile.
    internal suspend fun renew(box: BoxLink, at: Instant = now()): BoxLink? {
        val sign = sign ?: throw BoxException("Peel's account is not open.")
        val gen = generation
        val renewal = clientFor(box.url).renew(box, at, sign)
        if (gen != generation || links.none { it.url == box.url }) throw Superseded()
        return when (renewal) {
            is BoxClient.Renewal.Renewed -> { keep(renewal.link); renewal.link }
            BoxClient.Renewal.Unlinked -> { drop(box); null }
        }
    }

    /// Keeps [link] (the store first), in place of the box's earlier one.
    private fun keep(link: BoxLink) {
        val store = store ?: return
        if (unreadable) return
        val kept = links.toMutableList()
        val at = kept.indexOfFirst { it.url == link.url }
        if (at >= 0) kept[at] = link else kept += link
        BoxLink.write(kept, store)
        links = kept
        problem = null
    }

    /// Lets [box] go: its member removed the link there.
    private fun drop(box: BoxLink) {
        val store = store ?: return
        if (unreadable) return
        val kept = links.filter { it.url != box.url }
        runCatching { BoxLink.write(kept, store) }
        links = kept
        notice = unlinked(box)
    }

    /// What began before the account closed, or for a box forgotten since: it keeps and says nothing.
    internal class Superseded : Exception("superseded")

    companion object {
        /// What is said of a box that no longer links the key.
        fun unlinked(box: BoxLink): String = "${box.host} removed Peel's link, so Peel let it go. Link it again from Connect there."

        /// One HTTP client for every box: its connections and threads shared, as one `URLSession` is on iOS.
        private val sharedHttp: BoxHttp by lazy { OkHttpBox() }

        /// An error in words (iOS `PeelError.noted(_:couldNot:)`): noted in Diagnostics by its kind, never its text, and said as
        /// what could not be done, then why, as a sentence.
        internal fun noted(e: Exception, couldNot: String): String {
            com.sphinx.peel.diagnostics.Diagnostics.note(com.sphinx.peel.diagnostics.ErrorKinds.of(e))
            val why = sentence(e.message ?: e.javaClass.simpleName)
            return if (why.isEmpty()) "Could not $couldNot." else "Could not $couldNot. $why"
        }

        /// Words as a sentence (iOS `PeelError.sentence`): a capital first letter unless the first word is a name, and a full stop.
        internal fun sentence(words: String): String {
            var text = words.trim().trimEnd(',', ';', ':')
            if (text.isEmpty()) return ""
            val first = text.takeWhile { !it.isWhitespace() }.trim { !it.isLetterOrDigit() }
            if (first.isNotEmpty() && first.all { it.isLowerCase() || it in "'’-" }) text = text.substring(0, 1).uppercase() + text.substring(1)
            val ends = text.last() in ".!?…" || listOf(".)", "!)", "?)", ".\"", ".”").any { text.endsWith(it) }
            return if (ends) text else "$text."
        }
    }
}

/// A box's link in words (iOS `BoxWords`).
object BoxWords {
    /// The question: "Link Peel to <host> as <name>?".
    fun title(offer: BoxLinkOffer): String = "Link Peel to ${offer.host} as ${offer.challenge.person ?: "you"}?"

    /// Under it: what the yes does.
    fun question(offer: BoxLinkOffer): String {
        val who = offer.challenge.person ?: "you"
        return "${offer.host} will know your Sphinx account as $who: your @jamie in its tribes runs as $who, " +
            "and Peel opens its apps as $who. Link only if you just asked for this there."
    }

    /// Where a box is linked from, for the rows.
    const val HOW_TO_LINK = "To link Peel to a box, choose Link Peel in Connect in its Jamie app."
}

/// The boxes of the open account: one [BoxesModel] (iOS `AppModel.boxes`), beside [Stadeum], never in it. Its work runs on the
/// main thread, and a box on this device's own address is linked only in a debug build ([NetworkPolicy.plainLoopbackAllowed]).
object Boxes {
    val model: BoxesModel by lazy {
        BoxesModel(
            dispatcher = Dispatchers.Main.immediate,
            allowsLoopback = NetworkPolicy.plainLoopbackAllowed(com.sphinx.peel.BuildConfig.DEBUG),
        )
    }
}
