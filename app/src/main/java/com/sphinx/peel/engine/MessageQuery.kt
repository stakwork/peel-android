package com.sphinx.peel.engine

import java.text.Normalizer

/// What someone typed into a chat's search field (port of `peel`'s `MessageQuery`, PR #576): words and
/// "quoted phrases" that must ALL appear in a message, in any order, and `from:name` / `from:"ann lee"`
/// for a sender. A term shorter than [minimum] characters is left out of [terms] (the index cannot find
/// it) and sets [hasShortTerms], so the bar can say why nothing happened. Pure; JVM-tested.
class MessageQuery(
    /// What must appear (a word, or a phrase kept whole), as typed, at least [minimum] characters each.
    val terms: List<String>,
    /// The sender's name to filter by, when `from:` was used.
    val from: String?,
    /// Whether a term was left out for being shorter than [minimum].
    val hasShortTerms: Boolean = false,
) {
    /// Nothing to search for: no terms and no sender.
    val isEmpty: Boolean get() = terms.isEmpty() && from == null

    /// The FTS5 MATCH expression: every term a double-quoted FTS string (an inner quote doubled), joined
    /// with ` AND `. Never raw user text, so no FTS operator can be typed in. Empty when there are no terms.
    val ftsMatch: String
        get() = terms.joinToString(" AND ") { "\"" + it.replace("\"", "\"\"") + "\"" }

    /// One line of [text] starting at a word boundary about [lead] characters before the first term found
    /// (case- and diacritic-insensitive), prefixed with "…" when the start was cut. A text with no term in
    /// it (a `from:`-only search) starts at its beginning.
    fun snippet(text: String, lead: Int = 40): String {
        val flat = text.replace(Whitespace, " ").trim()
        val folded = fold(flat)
        var hit = -1
        for (term in terms) {
            val at = folded.indexOf(fold(term))
            if (at >= 0 && (hit < 0 || at < hit)) hit = at
        }
        if (hit <= lead) return flat
        var start = hit - lead
        if (flat[start - 1] != ' ') {
            val space = flat.indexOf(' ', start)
            start = if (space in 0 until hit) space + 1 else hit
        }
        return "…" + flat.substring(start)
    }

    /// Where the terms occur in [text] (case- and diacritic-insensitive), sorted and merged, for highlighting.
    fun highlights(text: String): List<IntRange> {
        val folded = fold(text)
        val found = mutableListOf<IntRange>()
        for (term in terms) {
            val needle = fold(term)
            if (needle.isEmpty()) continue
            var from = 0
            while (true) {
                val at = folded.indexOf(needle, from)
                if (at < 0) break
                found += at until at + needle.length
                from = at + needle.length
            }
        }
        found.sortBy { it.first }
        val merged = mutableListOf<IntRange>()
        for (range in found) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last + 1) merged[merged.size - 1] = last.first..maxOf(last.last, range.last)
            else merged += range
        }
        return merged
    }

    /// The same words and sender, however they were typed.
    override fun equals(other: Any?): Boolean = other is MessageQuery && terms == other.terms && from == other.from
    override fun hashCode(): Int = 31 * terms.hashCode() + (from?.hashCode() ?: 0)
    override fun toString(): String = "MessageQuery(terms=$terms, from=$from)"

    companion object {
        /// The shortest term the trigram index can find.
        const val minimum = 3

        private val Whitespace = Regex("\\s+")

        fun parse(text: String): MessageQuery {
            val s = text.replace("\u0000", "")
            val terms = mutableListOf<String>()
            var from: String? = null
            var short = false
            var i = 0
            val n = s.length

            fun quoted(): String {
                i++
                val start = i
                while (i < n && s[i] != '"') i++
                val value = s.substring(start, i)
                if (i < n) i++
                return value
            }

            fun word(): String {
                val start = i
                while (i < n && !s[i].isWhitespace()) i++
                return s.substring(start, i)
            }

            while (i < n) {
                if (s[i].isWhitespace()) {
                    i++
                    continue
                }
                if (s.startsWith("from:", i, ignoreCase = true)) {
                    i += 5
                    val value = (if (i < n && s[i] == '"') quoted() else word()).replace(Whitespace, " ").trim()
                    if (value.isNotEmpty()) from = value
                    continue
                }
                val term = (if (s[i] == '"') quoted() else word()).replace(Whitespace, " ").trim()
                if (term.isEmpty()) continue
                if (term.length < minimum) short = true else terms += term
            }
            return MessageQuery(terms, from, short)
        }

        /// Lower-cased and without accents, one character for one, so an index in the result is an index in [text].
        internal fun fold(text: String): String {
            val out = StringBuilder(text.length)
            for (c in text) {
                val plain = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
                    .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
                out.append((if (plain.length == 1) plain[0] else c).lowercaseChar())
            }
            return out.toString()
        }
    }
}
