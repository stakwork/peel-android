package com.sphinx.peel.engine

/// Tells the speech Peel reads aloud apart from the user, when the microphone hears the speaker (docs/phases/55-voice-mode.md,
/// "Echo"/barge-in). Pure: no audio, the clock is a parameter (milliseconds since epoch, so a test can pick its own). Ported
/// from peel's `Sources/PeelUI/Voice/EchoFilter.swift`.
///
/// A transcript is an echo when its normalised words contain a run of three words (the whole transcript if it has fewer)
/// that is a contiguous run of the normalised text being spoken, or spoken in the last few seconds. [isGuarding] says when
/// the filter applies: while speaking, and for a short tail after, when the room still rings.
class EchoFilter(
    /// How long spoken text still counts as heard back, in ms.
    private val recentWindowMs: Long = 3_000,
    /// How long after the speech ends transcripts are still checked, in ms.
    private val tailWindowMs: Long = 1_500,
) {
    var speaking: Boolean = false
        private set
    private var current: List<String> = emptyList()
    private val recent = mutableListOf<Pair<List<String>, Long>>()
    private var endedAt: Long? = null

    /// [chunks] are about to be spoken (queued after any earlier ones that are replaced: the controller stops the speaker
    /// first).
    fun speechStarted(chunks: List<String>, now: Long) {
        if (speaking) retire(now)
        current = chunks.flatMap { words(it) }
        speaking = true
        endedAt = null
    }

    /// The speech ended or was stopped.
    fun speechEnded(now: Long) {
        if (!speaking) return
        retire(now)
        speaking = false
        endedAt = now
    }

    /// Forgets everything (voice mode ended).
    fun reset() {
        speaking = false
        current = emptyList()
        recent.clear()
        endedAt = null
    }

    /// Whether transcripts should be checked now: speaking, or in the tail.
    fun isGuarding(now: Long): Boolean {
        if (speaking) return true
        val ended = endedAt ?: return false
        return now - ended <= tailWindowMs
    }

    /// [transcript] is what is being (or was just) spoken, not the user. False when the filter is not guarding.
    fun isEcho(transcript: String, now: Long): Boolean {
        if (!isGuarding(now)) return false
        val spoken = current + recent.filter { (_, at) -> now - at <= recentWindowMs }.flatMap { it.first }
        return matches(words(transcript), spoken)
    }

    /// [transcript], normalised, is wholly a run of the text being spoken now.
    fun currentlyContains(transcript: String): Boolean {
        val said = words(transcript)
        return said.isNotEmpty() && contains(current, said)
    }

    private fun retire(now: Long) {
        if (current.isNotEmpty()) recent.add(current to now)
        current = emptyList()
        recent.removeAll { (_, at) -> now - at > recentWindowMs }
    }

    companion object {
        /// Words of a run that make an echo.
        const val RUN_LENGTH = 3

        /// The pure rule: some run of [RUN_LENGTH] words of [words] (all of them when fewer) is a contiguous run of
        /// [spoken]. Nothing said is no echo.
        fun matches(words: List<String>, spoken: List<String>): Boolean {
            if (words.isEmpty() || spoken.isEmpty()) return false
            val size = minOf(RUN_LENGTH, words.size)
            for (start in 0..(words.size - size)) if (contains(spoken, words.subList(start, start + size))) return true
            return false
        }

        private fun contains(haystack: List<String>, needle: List<String>): Boolean {
            if (needle.isEmpty() || needle.size > haystack.size) return false
            for (start in 0..(haystack.size - needle.size)) if (haystack.subList(start, start + needle.size) == needle) return true
            return false
        }

        private fun words(text: String): List<String> = VoiceCommandParser.words(text)
    }
}
