package com.sphinx.peel.engine

/// When to tell the server "everything is read" (the core's `readAll`), so the
/// mixer's own, possibly inflated, unread/badge count resets to zero.
///
/// The rule: call only when the LOCAL unread total is 0 (the sum of the real
/// `ConversationRow.unread`, never `shownUnread`), the engine is connected, no
/// restore is running, and at most once per [intervalMs] (30 minutes) on a
/// monotonic clock. A failed attempt does not start the window: only
/// [recordSuccess] does, so the next trigger may retry.
///
/// Pure: the clock is injected so it is JVM-testable. Not thread-safe by
/// itself; the Engine calls it from its own serial paths.
class ReadAllPolicy(
    private val clock: () -> Long,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    private var lastSuccess: Long? = null

    /// Whether a `readAll` should be sent now. [caughtUp]: history finished on
    /// this connection without error or timeout; false never sends.
    fun shouldCall(localUnread: Long, connected: Boolean, restoring: Boolean, caughtUp: Boolean): Boolean {
        if (localUnread != 0L || !connected || restoring || !caughtUp) return false
        val last = lastSuccess ?: return true
        return clock() - last >= intervalMs
    }

    /// A `readAll` went through: starts the throttle window.
    fun recordSuccess() {
        lastSuccess = clock()
    }

    companion object {
        /// Whether a message was stored beyond the index recorded when the
        /// catch-up began. No recorded index counts as moved (never sends).
        fun arrivedSince(recorded: Long?, current: Long): Boolean =
            recorded == null || current > recorded

        const val DEFAULT_INTERVAL_MS = 30L * 60 * 1000
    }
}
