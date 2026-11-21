package com.sphinx.peel.engine

/// The phrases for each command, normalised (lowercase, no punctuation). Every command but [VoiceCommand.ACTIVATE] is
/// introduced by the wake word ("peel send"): [phrases] holds what follows the wake word, and the command must be the last
/// words of the utterance, so the text before it is kept as dictation. Without the wake word everything is dictation.
/// Ported from `Sources/PeelUI/Voice/VoiceCommand.swift`.
data class VoiceCommandPhrases(
    val phrases: Map<VoiceCommand, List<String>>,
    /// Commands (only ACTIVATE uses it) that also fire as the last words of an utterance; the others are matched whole.
    val trailing: Set<VoiceCommand> = emptySet(),
    /// The wake word that introduces a command.
    val wakeWord: String = "peel",
    /// What the recogniser mishears the wake word as; accepted only when a known command follows.
    val wakeVariants: List<String> = listOf("pill", "peal", "feel"),
) {
    /// The wake word and its variants.
    val wakeWords: List<String> get() = listOf(wakeWord) + wakeVariants

    /// What the user says for [command]'s [phrase], with the wake word.
    fun spoken(phrase: String, command: VoiceCommand): String = if (command == VoiceCommand.ACTIVATE) phrase else "$wakeWord $phrase"

    /// Every phrase as said, prefixed, for the recogniser's contextual strings.
    val all: List<String> get() = phrases.flatMap { (command, list) -> list.map { spoken(it, command) } }

    companion object {
        /// English. Edit here to add a phrase.
        val ENGLISH = VoiceCommandPhrases(
            phrases = mapOf(
                // Not prefixed: the opt-in wake listener's (not built on Android).
                VoiceCommand.ACTIVATE to listOf("hey peel", "voice mode", "start voice mode", "peel start voice mode"),
                // Everything below follows the wake word.
                VoiceCommand.DEACTIVATE to listOf("end voice mode", "end voice session", "stop voice mode", "exit voice mode", "goodbye"),
                // "sent": how the recogniser often writes "send" at the end of a sentence.
                VoiceCommand.SEND to listOf("send", "send it", "send message", "over", "sent", "sent it"),
                VoiceCommand.DISCARD to listOf("scratch that", "cancel message", "never mind"),
                VoiceCommand.STOP_SPEAKING to listOf("stop"),
                VoiceCommand.REPEAT_REPLY to listOf("repeat that", "say that again", "repeat"),
                VoiceCommand.SKIP to listOf("skip"),
                VoiceCommand.READ_ALL to listOf("read it all", "read everything"),
                VoiceCommand.STOP_AGENT to listOf("stop the agent", "cancel the agent"),
                VoiceCommand.MUTE to listOf("mute"),
                VoiceCommand.UNMUTE to listOf("unmute"),
            ),
        )
    }
}

/// What the parser found.
sealed class VoiceCommandMatch {
    object None : VoiceCommandMatch()

    /// [textBefore] is what was said before a trailing command, as spoken (case and punctuation kept, trimmed); empty for a
    /// whole-utterance one.
    data class Command(val command: VoiceCommand, val textBefore: String) : VoiceCommandMatch()
}

/// The commands a transcript heard in this [VoiceContext] may fire.
val VoiceContext.accepts: Set<VoiceCommand>
    get() = when (this) {
        VoiceContext.ARMED -> setOf(VoiceCommand.ACTIVATE)
        VoiceContext.DICTATING -> setOf(
            VoiceCommand.SEND, VoiceCommand.DISCARD, VoiceCommand.DEACTIVATE, VoiceCommand.STOP_AGENT,
            VoiceCommand.REPEAT_REPLY, VoiceCommand.READ_ALL, VoiceCommand.MUTE,
        )
        VoiceContext.WAITING -> setOf(VoiceCommand.DEACTIVATE, VoiceCommand.STOP_AGENT, VoiceCommand.REPEAT_REPLY, VoiceCommand.READ_ALL, VoiceCommand.MUTE)
        VoiceContext.SPEAKING -> setOf(
            VoiceCommand.STOP_SPEAKING, VoiceCommand.REPEAT_REPLY, VoiceCommand.SKIP, VoiceCommand.READ_ALL,
            VoiceCommand.STOP_AGENT, VoiceCommand.MUTE, VoiceCommand.DEACTIVATE,
        )
        VoiceContext.MUTED -> setOf(VoiceCommand.UNMUTE, VoiceCommand.DEACTIVATE)
    }

/// Finds a command in a transcript. Pure. Ported from `VoiceCommandParser` (`Sources/PeelUI/Voice/VoiceCommand.swift`).
class VoiceCommandParser(private val table: VoiceCommandPhrases = VoiceCommandPhrases.ENGLISH) {

    /// The command [transcript] ends with, among those [accepted]: the wake word and the command phrase are its last words
    /// ("add a screen peel send"). Only ACTIVATE needs no wake word. A bare "send it" is [VoiceCommandMatch.None]: it is
    /// dictation. Longer phrases win.
    fun parse(transcript: String, accepted: Set<VoiceCommand>): VoiceCommandMatch {
        // Whitespace-separated words as spoken, with their normalised form; a word of pure punctuation has none and is
        // skipped in matching.
        val spoken = transcript.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val tokens = mutableListOf<Pair<Int, String>>()
        for ((index, raw) in spoken.withIndex()) for (word in words(raw)) tokens.add(index to word)
        if (tokens.isEmpty()) return VoiceCommandMatch.None
        val said = tokens.map { it.second }

        var best: Pair<VoiceCommand, Int>? = null
        for (command in VoiceCommand.entries) {
            if (command !in accepted) continue
            for (phrase in table.phrases[command].orEmpty()) {
                val phraseWords = words(phrase)
                if (phraseWords.isEmpty()) continue
                val candidates: List<List<String>> =
                    if (command == VoiceCommand.ACTIVATE) listOf(phraseWords) else table.wakeWords.map { words(it) + phraseWords }
                for (candidate in candidates) {
                    if (candidate.size > said.size) continue
                    val ending = said.takeLast(candidate.size) == candidate
                    val whole = candidate == said
                    val allowed = whole || (ending && (command != VoiceCommand.ACTIVATE || VoiceCommand.ACTIVATE in table.trailing))
                    if (!allowed) continue
                    if (best == null || candidate.size > best.second) best = command to candidate.size
                }
            }
        }
        val (bestCommand, bestCount) = best ?: return VoiceCommandMatch.None
        val firstIndex = tokens[said.size - bestCount].first
        val before = spoken.take(firstIndex).joinToString(" ").trim(' ', ',', ';', ':', '-', '–', '—')
        return VoiceCommandMatch.Command(bestCommand, before)
    }

    companion object {
        /// Lowercase, punctuation to spaces, one space between words.
        fun normalize(text: String): String = words(text).joinToString(" ")

        /// Lowercased words: letters and digits only, so "Send it!" is ["send", "it"] and "that's" is ["thats"].
        fun words(text: String): List<String> =
            text.lowercase().split(Regex("[^\\p{L}\\p{N}'’]+")).map { it.replace("'", "").replace("’", "") }.filter { it.isNotEmpty() }
    }
}
