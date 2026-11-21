package com.sphinx.peel.engine

import java.net.URI
import java.text.BreakIterator
import java.util.Locale

/// An agent reply (markdown) made into something sensible to read aloud (docs/phases/55-voice-mode.md, "Long replies, markdown,
/// code and links"): code, tables and links become a short phrase, markup goes, and a long reply is cut to a short version with
/// the rest kept for "read it all". Pure. Ported from peel's `Sources/PeelUI/Voice/SpokenText.swift`.
class SpokenText(markdown: String, budget: Budget = Budget()) {
    /// Sentence-sized pieces to speak now, so speech can be stopped and queued quickly. Ends with [SHORT_VERSION_NOTE] when [isShortened].
    val chunks: List<String>

    /// Every sentence of the cleaned reply, for "read it all".
    val full: List<String>
    val isShortened: Boolean

    /// How much is read before the short version ends: characters, and sentences (a list item is one).
    data class Budget(val characters: Int = 600, val sentences: Int = 8)

    init {
        val paragraphs = paragraphs(markdown)
        full = paragraphs.flatten()
        val taken = mutableListOf<String>()
        var characters = 0
        var boundary = 0
        var cut = false
        run outer@{
            for (paragraph in paragraphs) {
                for (sentence in paragraph) {
                    if (taken.isNotEmpty() && (characters + sentence.length > budget.characters || taken.size >= budget.sentences)) {
                        cut = true
                        return@outer
                    }
                    taken.add(sentence)
                    characters += sentence.length + 1
                }
                boundary = taken.size
            }
        }
        val finalTaken = if (cut && boundary > 0) taken.take(boundary) else taken
        isShortened = cut
        chunks = if (cut) finalTaken + SHORT_VERSION_NOTE else finalTaken
    }

    companion object {
        /// Said after a short version.
        const val SHORT_VERSION_NOTE = "That is the short version. Say read it all for the rest."

        // MARK: Paragraphs of sentences

        /// The reply as paragraphs, each a list of cleaned sentences.
        private fun paragraphs(markdown: String): List<List<String>> {
            val result = mutableListOf<List<String>>()
            var current = mutableListOf<String>()
            var plain = mutableListOf<String>()

            fun flushPlain() {
                if (plain.isEmpty()) return
                current.addAll(sentencesIn(plain.joinToString(" ")))
                plain = mutableListOf()
            }
            fun endParagraph() {
                flushPlain()
                if (current.isNotEmpty()) result.add(current)
                current = mutableListOf()
            }

            var fence: String? = null
            var codeLines = 0
            var inTable = false

            for (raw in markdown.replace("\r\n", "\n").split("\n")) {
                val line = raw.trim()
                val activeFence = fence
                if (activeFence != null) {
                    if (line.startsWith(activeFence)) {
                        fence = null
                        current.add(codeBlockPhrase(codeLines))
                    } else {
                        codeLines++
                    }
                    continue
                }
                if (line.startsWith("```") || line.startsWith("~~~")) {
                    flushPlain()
                    fence = line.take(3)
                    codeLines = 0
                    continue
                }
                if (isTableLine(line)) {
                    if (!inTable) {
                        flushPlain()
                        current.add("A table, skipped.")
                        inTable = true
                    }
                    continue
                }
                inTable = false
                if (line.isEmpty()) {
                    endParagraph()
                    continue
                }
                if (RULE.matches(line)) continue
                val heading = HEADING.find(line)
                if (heading != null) {
                    flushPlain()
                    val text = clean(line.substring(heading.range.last + 1))
                    if (text.isNotEmpty()) current.add(terminated(text))
                    continue
                }
                var body = line
                val isQuote = body.startsWith(">")
                while (true) {
                    val quote = QUOTE.find(body) ?: break
                    body = body.substring(quote.range.last + 1)
                }
                val list = LIST_MARKER.find(body)
                if (list != null) {
                    flushPlain()
                    val text = clean(body.substring(list.range.last + 1))
                    if (text.isNotEmpty()) current.add(terminated(text))
                    continue
                }
                val text = clean(body)
                // A quoted line stands alone: the sentence splitter would not break before a lowercase word that follows a full stop.
                if (isQuote) flushPlain()
                if (text.isNotEmpty()) plain.add(text)
                if (isQuote) flushPlain()
            }
            if (fence != null) current.add(codeBlockPhrase(codeLines))
            endParagraph()
            return result
        }

        private fun codeBlockPhrase(lines: Int): String = when {
            lines <= 0 -> "A code block, skipped."
            lines == 1 -> "A code block of one line, skipped."
            else -> "A code block of $lines lines, skipped."
        }

        private fun isTableLine(line: String): Boolean {
            if (!line.contains("|")) return false
            return line.startsWith("|") || TABLE_RULE.matches(line)
        }

        private fun terminated(text: String): String {
            val last = text.lastOrNull() ?: return text
            return if (last in ".!?…:;") text else "$text."
        }

        private fun sentencesIn(text: String): List<String> {
            val boundary = BreakIterator.getSentenceInstance(Locale.US)
            boundary.setText(text)
            val found = mutableListOf<String>()
            var start = boundary.first()
            var end = boundary.next()
            while (end != BreakIterator.DONE) {
                val sentence = text.substring(start, end).trim()
                if (sentence.isNotEmpty()) found.add(sentence)
                start = end
                end = boundary.next()
            }
            return if (found.isEmpty() && text.isNotEmpty()) listOf(text) else found
        }

        // MARK: Inline markup

        /// One line's inline markup removed: links, URLs, identifiers, code ticks, emphasis, HTML and emoji.
        private fun clean(text: String): String {
            var s = text
            s = IMAGE.replace(s, "an image")
            s = LINK.replace(s, "$1")
            s = ANGLE_URL.replace(s, "$1")
            s = s.replace("`", "")
            s = replaceURLs(s)
            s = UUID_PATTERN.replace(s, "an identifier")
            s = replaceMatches(HEX_RUN, s) { if (it.any(Char::isDigit)) "an identifier" else it }
            s = PATH.replace(s, "an identifier")
            s = SLASH_FILENAME.replace(s, "an identifier")
            s = LONG_TOKEN.replace(s, "an identifier")
            s = BOLD_STAR.replace(s, "$1")
            s = BOLD_UNDERSCORE.replace(s, "$1")
            s = STRIKE.replace(s, "$1")
            s = ITALIC_STAR.replace(s, "$1")
            s = ITALIC_UNDERSCORE.replace(s, "$1")
            s = HTML_TAG.replace(s, "")
            s = stripEmoji(s)
            s = WHITESPACE.replace(s, " ")
            return s.trim()
        }

        /// A bare URL is "a link to host"; punctuation after it stays outside.
        private fun replaceURLs(text: String): String = replaceMatches(URL_PATTERN, text) { url ->
            var link = url
            var tail = ""
            while (link.isNotEmpty() && link.last() in ".,;:!?)]}'") {
                tail = link.last() + tail
                link = link.dropLast(1)
            }
            var host = runCatching { URI(link).host }.getOrNull().orEmpty()
            if (host.startsWith("www.")) host = host.removePrefix("www.")
            (if (host.isEmpty()) "a link" else "a link to $host") + tail
        }

        /// Replaces each match of [pattern] with what [transform] makes of it.
        private fun replaceMatches(pattern: Regex, text: String, transform: (String) -> String): String {
            val sb = StringBuilder()
            var last = 0
            for (match in pattern.findAll(text)) {
                sb.append(text, last, match.range.first)
                sb.append(transform(match.value))
                last = match.range.last + 1
            }
            sb.append(text, last, text.length)
            return sb.toString()
        }

        private fun stripEmoji(text: String): String {
            val sb = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val count = Character.charCount(cp)
                if (!isEmoji(cp)) sb.appendCodePoint(cp)
                i += count
            }
            return sb.toString()
        }

        private fun isEmoji(cp: Int): Boolean = when {
            cp == 0x200D || cp == 0xFE0F || cp == 0x20E3 -> true
            cp in 0x1F300..0x1FAFF -> true
            cp in 0x2600..0x27BF -> true
            cp == 0x2B50 || cp == 0x2B55 -> true
            else -> false
        }

        private val RULE = Regex("""^([-*_])(\s*\1){2,}$""")
        private val HEADING = Regex("""^#{1,6}\s+""")
        private val QUOTE = Regex("""^>\s?""")
        private val LIST_MARKER = Regex("""^([-*+•]|\d{1,3}[.)])\s+""")
        private val TABLE_RULE = Regex("""^[\s|:\-]+$""")
        private val IMAGE = Regex("""!\[[^\]]*]\([^)]*\)""")
        private val LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
        private val ANGLE_URL = Regex("""<(https?://[^>\s]+)>""")
        private val URL_PATTERN = Regex("""https?://[^\s<>"]+""")
        private val UUID_PATTERN = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
        private val HEX_RUN = Regex("""\b[0-9a-fA-F]{12,}\b""")
        private val PATH = Regex("""(?<![\w/])(?:~|\.{1,2})?/[\w.\-]+(?:/[\w.\-]+)+""")
        private val SLASH_FILENAME = Regex("""[A-Za-z0-9_\-]+(?:/[A-Za-z0-9_.\-]+)+\.[A-Za-z0-9]{1,6}\b""")
        private val LONG_TOKEN = Regex("""\S{24,}""")
        private val BOLD_STAR = Regex("""\*\*(.+?)\*\*""")
        private val BOLD_UNDERSCORE = Regex("""__(.+?)__""")
        private val STRIKE = Regex("""~~(.+?)~~""")
        private val ITALIC_STAR = Regex("""\*(.+?)\*""")
        private val ITALIC_UNDERSCORE = Regex("""(?<![\w])_(.+?)_(?![\w])""")
        private val HTML_TAG = Regex("""</?[A-Za-z][^>]*>""")
        private val WHITESPACE = Regex("""\s+""")
    }
}
