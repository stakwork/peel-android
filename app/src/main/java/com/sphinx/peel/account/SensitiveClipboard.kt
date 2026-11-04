package com.sphinx.peel.account

/// Puts a sensitive value on the clipboard and takes it off again after a
/// while — but only if the clipboard still holds OUR clip, so something the
/// person copied meanwhile is never wiped. The clip is recognised by its
/// label (a per-copy nonce), not its text: on Android 10+ a background app
/// cannot read clip text, but the description can always be asked.
///
/// The Android side (`AndroidClipboardAccess`) marks the clip
/// `ClipDescription.EXTRA_IS_SENSITIVE` on API 33+, so the system's clipboard
/// preview hides it, and clears with `clearPrimaryClip` on API 28+.
class SensitiveClipboard(
    private val access: ClipboardAccess,
    private val scheduler: Scheduler,
    private val newNonce: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    interface ClipboardAccess {
        fun put(label: String, text: String, sensitive: Boolean)
        /// The label of whatever is on the clipboard now, null when empty/unknown.
        fun currentLabel(): String?
        fun clear()
    }

    fun interface Scheduler { fun after(delayMs: Long, task: () -> Unit) }

    private var ours: String? = null

    /// Copies `text`, to be cleared after `clearAfterMs`.
    fun copy(text: String, clearAfterMs: Long = DEFAULT_CLEAR_MS) {
        val label = "$LABEL_PREFIX${newNonce()}"
        ours = label
        access.put(label, text, sensitive = true)
        scheduler.after(clearAfterMs) { clearIfStillOurs(label) }
    }

    /// Clears now if the clipboard still holds our latest clip (e.g. right after a paste).
    fun clearNow() { ours?.let(::clearIfStillOurs) }

    private fun clearIfStillOurs(label: String) {
        if (ours == label && access.currentLabel() == label) access.clear()
        if (ours == label) ours = null
    }

    companion object {
        const val DEFAULT_CLEAR_MS = 60_000L
        const val LABEL_PREFIX = "peel-sensitive:"

        /// Whether `pasted` text — read from the clipboard by a paste button for
        /// the twelve words — is a clip to wipe: anything that parsed as words.
        /// (The paste button cannot know whose clip it was, so it wipes what it consumed.)
        fun shouldClearAfterPaste(wordsFound: Int) = wordsFound > 0
    }
}
