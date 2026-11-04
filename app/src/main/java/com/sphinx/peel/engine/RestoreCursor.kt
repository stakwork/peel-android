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
/// queue, no live-message gap (`liveAbove`) and no stall watchdog.
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
) {
    enum class Phase(val code: String) { CONTACTS("C"), MESSAGES("M") }

    /// No more pages to ask for: `next` is past the highest index.
    val isPastEnd: Boolean get() = end != null && next > end

    /// The same run after a page whose highest index was `reached`.
    /// Never moves backwards: a stale or duplicate page changes nothing.
    fun advanced(reached: Long): RestoreCursor = if (reached + 1 > next) copy(next = reached + 1) else this

    /// The run that follows this one finishing, or null when it was the last.
    fun following(): RestoreCursor? =
        if (phase == Phase.CONTACTS && thenMessages) RestoreCursor(Phase.MESSAGES, 0, messagesEnd) else null

    fun encode(): String = listOf(
        VERSION, phase.code, next, end ?: "", if (thenMessages) "1" else "0", messagesEnd ?: "",
    ).joinToString("|")

    companion object {
        private const val VERSION = "1"

        /// Null for nothing stored or anything this version cannot read
        /// (a restore then starts from its counts, as it always did).
        fun decode(text: String?): RestoreCursor? {
            if (text.isNullOrEmpty()) return null
            val parts = text.split("|")
            if (parts.size != 6 || parts[0] != VERSION) return null
            val phase = Phase.values().firstOrNull { it.code == parts[1] } ?: return null
            val next = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val end = parts[3].takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: return null }
            val then = when (parts[4]) { "1" -> true; "0" -> false; else -> return null }
            val messagesEnd = parts[5].takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: return null }
            return RestoreCursor(phase, next, end, then, messagesEnd)
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
                )
                hasMessages -> RestoreCursor(Phase.MESSAGES, 0, counts.totalHighestIndex)
                else -> null
            }
        }
    }
}
