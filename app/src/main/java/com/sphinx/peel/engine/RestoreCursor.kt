package com.sphinx.peel.engine

/// What `getMsgsCounts` reports, mirrored from PeelCore's
/// `Engine.MessageCounts`.
internal data class MessageCounts(
    val total: Long,
    val firstForEachKey: Long,
    val totalHighestIndex: Long?,
    val firstForEachKeyHighestIndex: Long?,
)

/// Where a restore stands, small enough to keep in `Setting.RESTORE_CURSOR`
/// so a restart or a failure resumes instead of starting from zero. The
/// pure half of PeelCore's `HistoryCursor` (its `Run`, `plan` and the
/// "past the end" rule), trimmed to what a sequential restore needs: no
/// queue and no live-message gap (`liveAbove`); the stall watchdog is `Engine`'s.
///
/// A restore is up to two runs back to back, contacts then messages
/// (`plan`); the cursor names the run in flight (`phase`, `next`, `end`)
/// and, while it is the contacts run, whether a messages run follows and
/// where that one ends (`messagesEnd`), so a resume needs no second
/// `getMsgsCounts`.
///
/// It is written after the page it names is stored, so on a crash it can
/// lag by one page: that page is asked for again, and storing it again is
/// idempotent (messages by uuid, contacts upserted).
internal data class RestoreCursor(
    val phase: Phase,
    /// The next server index to ask for.
    val next: Long,
    /// The highest server index of this run, when the server said.
    val end: Long?,
    /// Contacts run only: a messages run follows.
    val thenMessages: Boolean = false,
    /// Contacts run only: that messages run's highest index.
    val messagesEnd: Long? = null,
    /// How many contacts the server said it holds (an item count, not an
    /// index), kept so a resume can show the Contacts progress. Null: unknown.
    val contactsTotal: Long? = null,
    /// Items the contacts run has fetched so far (a resumed run's progress
    /// starts here, not at 0).
    val fetched: Long = 0,
) {
    enum class Phase(val code: String) { CONTACTS("C"), MESSAGES("M") }

    /// No more pages to ask for: `next` is past the highest index.
    val isPastEnd: Boolean get() = end != null && next > end

    /// The same run after a page whose highest index was `reached` and which
    /// carried `items` items. Never moves backwards: a stale or duplicate
    /// page changes nothing, and its items are not counted twice.
    fun advanced(reached: Long, items: Long = 0): RestoreCursor =
        if (reached + 1 > next) copy(next = reached + 1, fetched = fetched + items) else this

    /// The run that follows this one finishing, or null when it was the last.
    fun following(): RestoreCursor? =
        if (phase == Phase.CONTACTS && thenMessages) RestoreCursor(Phase.MESSAGES, 0, messagesEnd, contactsTotal = contactsTotal, fetched = fetched) else null

    fun encode(): String = listOf(
        VERSION, phase.code, next, end ?: "", if (thenMessages) "1" else "0", messagesEnd ?: "",
        contactsTotal ?: "", fetched,
    ).joinToString("|")

    companion object {
        private const val VERSION = "2"

        /// Null for nothing stored or anything this version cannot read
        /// (a restore then starts from its counts, as it always did).
        fun decode(text: String?): RestoreCursor? {
            if (text.isNullOrEmpty()) return null
            val parts = text.split("|")
            if (parts.size != 8 || parts[0] != VERSION) return null
            val phase = Phase.values().firstOrNull { it.code == parts[1] } ?: return null
            val next = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val end = parts[3].takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: return null }
            val then = when (parts[4]) { "1" -> true; "0" -> false; else -> return null }
            val messagesEnd = parts[5].takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: return null }
            val contactsTotal = parts[6].takeIf { it.isNotEmpty() }?.let { it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: return null }
            val fetched = parts[7].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            return RestoreCursor(phase, next, end, then, messagesEnd, contactsTotal, fetched)
        }

        /// A restore: contacts first (one message per contact key), then
        /// every message from the beginning. Null when the server holds
        /// nothing. PeelCore's `HistoryCursor.plan`.
        fun plan(counts: MessageCounts): RestoreCursor? {
            val hasMessages = counts.total > 0
            return when {
                counts.firstForEachKey > 0 -> RestoreCursor(
                    Phase.CONTACTS, 0, counts.firstForEachKeyHighestIndex,
                    thenMessages = hasMessages, messagesEnd = counts.totalHighestIndex.takeIf { hasMessages },
                    contactsTotal = counts.firstForEachKey,
                )
                hasMessages -> RestoreCursor(Phase.MESSAGES, 0, counts.totalHighestIndex, contactsTotal = 0)
                else -> null
            }
        }
    }
}

/// What one page's answer means for a run that asked from `next`: the pure
/// rules `Engine.page` follows, so they can be tested.
internal enum class PageVerdict {
    /// Store it and move `next` past `reached`.
    ADVANCE,

    /// The run is complete.
    END,

    /// An empty page before the end, or a stale reply: ask again, a few
    /// times (`RestorePage.MAX_RETRIES`), then fail so the run resumes.
    RETRY,
}

internal object RestorePage {
    /// How many times in a row an empty-before-the-end or stale reply is
    /// asked again before the run fails (and resumes from its cursor).
    const val MAX_RETRIES = 2

    /// [reached] is the highest server index on the page (null: none).
    /// An empty page ends the run only when there is no `end` to reach
    /// (a catch-up) or `next` is past it: before that it is a page lost,
    /// never "complete" (it would mark history done with a gap). A reply
    /// whose highest index is below `next` is stale (the answer to an
    /// earlier ask): ignored, so `next` never moves backwards.
    fun verdict(reached: Long?, next: Long, end: Long?): PageVerdict = when {
        reached == null -> if (end == null || next > end) PageVerdict.END else PageVerdict.RETRY
        reached < next -> PageVerdict.RETRY
        else -> PageVerdict.ADVANCE
    }
}
