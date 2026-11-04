package com.sphinx.peel.engine

import java.net.IDN
import java.util.regex.Pattern

/// What a message's text marks up: blocks (paragraphs, list items, headings,
/// quotes, fenced code) and, inside the text of a block, inline spans (code,
/// https links, `[text](https://…)` links, mentions) and emphasis (bold,
/// italic, strikethrough). Parsing only; how each looks is the UI's
/// (`ui/components/MessageText.kt`).
///
/// A port of peel's `Sources/PeelCore/MessageMarkup.swift`, pure Kotlin so it
/// runs in plain JVM unit tests. Differences from the Swift original:
///  - Offsets are UTF-16 indices into the string, not `Character`s. Only
///    matters for the flanking rules (code points are used there, so an emoji
///    before a mark counts as one symbol) and `@x` mention length.
///  - There is no `NSDataDetector`: https links are found by `findLinks`, a
///    hand-written scanner (see there for what it accepts).
///  - Links are plain `String` URLs (no `URL`), with a non-ASCII host already
///    in its `xn--` form.
///  - `codeRanges` is not ported: it exists for iOS's mention-scan in
///    `Contacts.swift`, which has no Android counterpart yet.
object MessageMarkup {
    sealed interface Block {
        data class Paragraph(val text: String) : Block
        /// A line starting with "- ", "• " or "* ", without its marker.
        data class Item(val text: String) : Block
        /// A line starting with "1. " or "1) ": its number and the rest.
        data class Numbered(val number: Int, val text: String) : Block
        /// A line starting with one to six "#" and a space: how many, and the rest.
        data class Heading(val level: Int, val text: String) : Block
        /// Lines in a row starting with ">", without it.
        data class Quote(val text: String) : Block
        /// The lines between ``` fences, as typed.
        data class Code(val text: String) : Block
    }

    sealed interface Kind {
        data object Code : Kind
        data class Link(val url: String) : Kind
        /// `[text](https://…)`: the text shows, the link opens.
        data class NamedLink(val url: String) : Kind
        data object Mention : Kind
    }

    /// `start`/`end` (exclusive) is where it sits in the text, backticks and
    /// brackets included. `text` is what shows: the text between the backticks
    /// for code, between the brackets for a named link, else the span itself.
    data class Span(val kind: Kind, val start: Int, val end: Int, val text: String)

    @JvmInline
    value class Emphasis(val bits: Int) {
        operator fun contains(other: Emphasis) = bits and other.bits == other.bits && other.bits != 0
        val isEmpty get() = bits == 0

        companion object {
            val NONE = Emphasis(0)
            val BOLD = Emphasis(1)
            val ITALIC = Emphasis(2)
            val STRIKETHROUGH = Emphasis(4)
        }
    }

    /// A piece of a block's text as it shows: plain text or a span, with the
    /// emphasis around it and the marks that made it taken out. `kind` is null
    /// for plain text.
    data class Inline(val text: String, val kind: Kind?, val emphasis: Emphasis)

    private const val FENCE = "```"
    private val itemMarkers = listOf("- ", "• ", "* ")

    // MARK: Blocks

    /// The text as paragraphs, list items, headings, quotes and code blocks.
    /// A fence nothing closes stays text, as an unmatched backtick does.
    fun blocks(text: String): List<Block> {
        val lines = text.replace("\r\n", "\n").split("\n")
        val closers = closers(lines, text)
        val blocks = ArrayList<Block>()
        val paragraph = ArrayList<String>()
        val quote = ArrayList<String>()
        fun joined(list: ArrayList<String>): String? {
            // Blank lines beside a block are the gap the blocks already have.
            val start = list.indexOfFirst { !isBlank(it) }
            val end = list.indexOfLast { !isBlank(it) }
            val result = if (start < 0) null else list.subList(start, end + 1).joinToString("\n")
            list.clear()
            return result
        }
        fun flush() {
            joined(quote)?.let { blocks.add(Block.Quote(it)) }
            joined(paragraph)?.let { blocks.add(Block.Paragraph(it)) }
        }
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = trimSpaces(line)
            if (trimmed.startsWith(FENCE)) {
                val found = fencedCode(lines, index, closers[index + 1])
                if (found != null) {
                    flush()
                    blocks.add(Block.Code(found.first))
                    index = found.second + 1
                    continue
                }
            }
            index += 1
            if (trimmed.startsWith(">")) {
                if (quote.isEmpty()) flush()
                val rest = trimmed.substring(1)
                quote.add(if (rest.startsWith(" ")) rest.substring(1) else rest)
                continue
            }
            if (quote.isNotEmpty()) flush()
            val marker = itemMarkers.firstOrNull { trimmed.startsWith(it) }
            if (marker != null) {
                flush()
                blocks.add(Block.Item(trimmed.substring(marker.length)))
                continue
            }
            val head = marked(trimmed, headingPattern)
            if (head != null) {
                flush()
                blocks.add(Block.Heading(head.first, head.second))
                continue
            }
            val number = marked(trimmed, numberedPattern)
            if (number != null) {
                flush()
                blocks.add(Block.Numbered(number.first, number.second))
            } else {
                paragraph.add(line)
            }
        }
        flush()
        return blocks
    }

    /// For each line, the first from it on that could close a fence, found
    /// once, so a message of many unclosed fences is not searched again.
    private fun closers(lines: List<String>, text: String): Array<Int?> {
        val closers = arrayOfNulls<Int>(lines.size + 1)
        if (text.contains(FENCE)) {
            for (index in lines.indices.reversed()) {
                closers[index] = if (trimSpaces(lines[index]).endsWith(FENCE)) index else closers[index + 1]
            }
        }
        return closers
    }

    /// "## Notes": one to six "#", a space, then text.
    private val headingPattern = Pattern.compile("""^(#{1,6}) +(\S.*)$""", Pattern.UNICODE_CHARACTER_CLASS)
    /// "12. Ship it" or "12) Ship it".
    private val numberedPattern = Pattern.compile("""^([0-9]{1,9})[.)] +(\S.*)$""", Pattern.UNICODE_CHARACTER_CLASS)

    /// The number a line's marker holds (a heading's "#" counted) and the text
    /// after it, when `pattern` matches the whole line.
    private fun marked(line: String, pattern: Pattern): Pair<Int, String>? {
        val first = line.firstOrNull() ?: return null
        if (first != '#' && first !in '0'..'9') return null
        val m = pattern.matcher(line)
        if (!m.find()) return null
        val marker = m.group(1)!!
        val number = if (first == '#') marker.length else marker.toIntOrNull() ?: 0
        return number to m.group(2)!!
    }

    /// The code of a block opened on `lines[start]` and the index of the line
    /// that closes it: the same line for ```code```, else `closer`, the next
    /// line ending in ```. Null when nothing closes it or it holds nothing.
    private fun fencedCode(lines: List<String>, start: Int, closer: Int?): Pair<String, Int>? {
        val opening = trimSpaces(lines[start]).substring(FENCE.length)
        val code = ArrayList<String>()
        val end: Int
        if (opening.length > FENCE.length && opening.endsWith(FENCE)) {
            code.add(opening.dropLast(FENCE.length))
            end = start
        } else {
            val close = closer ?: return null
            end = close
            // A language name after the fence is dropped, as the official apps
            // do; other text there is the first line of code.
            val first = trimSpaces(opening)
            if (first.isNotEmpty() && !isLanguageName(first)) code.add(first)
            for (i in (start + 1) until close) code.add(lines[i])
            val closing = lines[close].trimEnd { it.isWhitespace() }.dropLast(FENCE.length)
            if (!isBlank(closing)) code.add(closing)
        }
        val joined = code.joinToString("\n").trim { isNewline(it) }
        return if (isBlank(joined)) null else joined to end
    }

    /// "swift", "ts", "c++", "objective-c".
    private fun isLanguageName(word: String): Boolean =
        word.codePointCount(0, word.length) <= 20 &&
            word.all { it.isLetter() || it.isDigit() || it in "+-#._" }

    // MARK: Plain text

    /// The text on one line without its markup, for previews: inline code as
    /// its text, a named link as its text, emphasis and block marks dropped (a
    /// numbered item keeps its number), a code block's lines joined, every
    /// line break a space.
    fun plainText(text: String): String {
        val trimmed = trimSpaces(text)
        // Previews are drawn often, and most text has no markup to parse.
        if (!text.any { isNewline(it) || it in "`*_~[" } && itemMarkers.none { trimmed.startsWith(it) } &&
            trimmed.firstOrNull()?.let { it in "#>" || it in '0'..'9' } != true
        ) {
            return trimmed
        }
        return blocks(text).map { block ->
            when (block) {
                is Block.Paragraph -> plain(block.text)
                is Block.Item -> plain(block.text)
                is Block.Heading -> plain(block.text)
                is Block.Quote -> plain(block.text)
                is Block.Numbered -> "${block.number}. " + plain(block.text)
                is Block.Code -> block.text
            }
        }
            .flatMap { it.split(newlineRegex) }
            .map { trimSpaces(it) }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    private val newlineRegex = Regex("[\n\r\u000B\u000C\u0085\u2028\u2029]")

    private fun plain(text: String): String = inlines(text).joinToString("") { it.text }

    // MARK: Spans

    /// The Mac app's mention pattern: `@` at the start of a word, up to the next space.
    private val mentionPattern = Pattern.compile("""\B@[^\s]+""", Pattern.UNICODE_CHARACTER_CLASS)
    /// A run of backticks, text on one line with none in it, then the same
    /// run again: `code`, ``code``, ```code``` inside a line.
    private val codePattern = Pattern.compile("""(?<!`)(`+)([^`\n]+)\1(?!`)""")
    /// `[text](address)`: text on one line with no brackets, an address with
    /// no space or parentheses.
    private val namedLinkPattern = Pattern.compile("""\[([^\[\]\n]++)\]\(([^\s()]++)\)""", Pattern.UNICODE_CHARACTER_CLASS)
    /// What marks emphasis: **bold**, __bold__, *italic*, _italic_, ~~struck~~.
    private val emphasisMarks = setOf('*', '_', '~')

    /// Inline code, https links (anything else stays text), `[text](https://…)`
    /// links and mentions, in order and never overlapping. A backtick inside a
    /// link is part of the link; a link or mention inside code is code.
    fun spans(text: String): List<Span> {
        var links = findLinks(text)
        // A named link opens only an address read as an https link, the whole
        // of it, so it follows the same rules as one typed out.
        if (links.isNotEmpty() && text.contains("](")) {
            var next = 0
            val named = ArrayList<Span>()
            val m = namedLinkPattern.matcher(text)
            while (m.find()) {
                val addressStart = m.start(2)
                while (next < links.size && links[next].start < addressStart) next++
                val link = links.getOrNull(next) ?: continue
                val kind = link.kind
                if (link.start != addressStart || link.end != m.end(2) || kind !is Kind.Link) continue
                named.add(Span(Kind.NamedLink(kind.url), m.start(), m.end(), m.group(1)!!))
            }
            links = (named + links.filter(outside(named))).sortedBy { it.start }
        }
        // Links, codes and mentions each come in order and never overlap their
        // own kind, so every check walks forward once.
        val codes = ArrayList<Span>()
        var from = 0
        var nextLink = 0
        val cm = codePattern.matcher(text)
        cm.useTransparentBounds(true)
        cm.useAnchoringBounds(false)
        while (from < text.length && text.indexOf('`', from) >= 0) {
            cm.region(from, text.length)
            if (!cm.find()) break
            val start = cm.start()
            // Opening inside a link: the backtick is the link's, and the
            // search goes on after it, so a later pair is still found.
            while (nextLink < links.size && links[nextLink].end <= start) nextLink++
            if (nextLink < links.size && links[nextLink].start < start) {
                from = links[nextLink].end
                continue
            }
            codes.add(Span(Kind.Code, start, cm.end(), cm.group(2)!!))
            from = cm.end()
        }
        var spans = (codes + links.filter(outside(codes))).sortedBy { it.start }
        if (text.contains('@')) {
            val mentions = ArrayList<Span>()
            val mm = mentionPattern.matcher(text)
            while (mm.find()) {
                val end = withoutClosingMarks(text, mm.start(), mm.end())
                if (end - mm.start() > 1) mentions.add(Span(Kind.Mention, mm.start(), end, text.substring(mm.start(), end)))
            }
            spans = (spans + mentions.filter(outside(spans))).sortedBy { it.start }
        }
        return spans
    }

    /// `end` moved back past the emphasis marks that close what is around a
    /// link or mention rather than belong to it ("**https://a.co**").
    private fun withoutClosingMarks(text: String, start: Int, end: Int): Int {
        var e = end
        while (e > start && text[e - 1] in emphasisMarks) e--
        return e
    }

    /// Whether a span misses every one of `taken` (in order, none
    /// overlapping), for spans asked about in order.
    private fun outside(taken: List<Span>): (Span) -> Boolean {
        var next = 0
        return { span ->
            while (next < taken.size && taken[next].end <= span.start) next++
            next == taken.size || !(taken[next].start < span.end && span.start < taken[next].end)
        }
    }

    // MARK: Links

    private val schemeRegex = Regex("https://", RegexOption.IGNORE_CASE)
    /// What ends a link's address: white space and characters no address holds.
    private const val LINK_STOPS = "<>\"[]{}|\\^"

    /// The https links in `text`, in order. Stands in for iOS's
    /// `NSDataDetector`, which is not available here, so it is simpler and
    /// stricter: a link is `https://` (any case) not glued to a word before
    /// it, then everything up to white space or one of `<>"[]{}|\^`, less
    /// trailing sentence punctuation (`.,;:!?'`), an unbalanced `)` and
    /// closing emphasis marks. Its host (up to `/?#`) must be letters,
    /// digits, `-`, `_` and dots (or non-ASCII letters, shown in `xn--`
    /// form), may carry a numeric port, and no `user@` or password: those
    /// stay text. Not read: IPv6 hosts, and schemes other than https (as on
    /// iOS, where the detector's other links are dropped).
    internal fun findLinks(text: String): List<Span> {
        if (!text.contains("http", ignoreCase = true)) return emptyList()
        val links = ArrayList<Span>()
        var from = 0
        while (true) {
            val m = schemeRegex.find(text, from) ?: break
            val start = m.range.first
            from = start + "https://".length
            if (start > 0 && text[start - 1].isLetterOrDigit()) continue
            var end = from
            while (end < text.length && !text[end].isWhitespace() && text[end] !in LINK_STOPS) end++
            while (end > from) {
                val c = text[end - 1]
                val drop = when {
                    c in ".,;:!?'" || c in emphasisMarks -> true
                    c == ')' -> text.substring(start, end).let { s -> s.count { it == ')' } > s.count { it == '(' } }
                    else -> false
                }
                if (!drop) break
                end--
            }
            val url = address(text.substring(start + "https://".length, end)) ?: continue
            links.add(Span(Kind.Link(url), start, end, text.substring(start, end)))
            from = end
        }
        return links
    }

    /// The URL for what follows `https://`, or null when it is not one we open.
    private fun address(rest: String): String? {
        val authorityEnd = rest.indexOfFirst { it in "/?#" }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        val colon = authority.lastIndexOf(':')
        val host = if (colon >= 0) authority.substring(0, colon) else authority
        val port = if (colon >= 0) authority.substring(colon + 1) else ""
        if (colon >= 0 && (port.isEmpty() || !port.all { it in '0'..'9' } || port.length > 5)) return null
        if (host.isEmpty() || host.startsWith('.') || host.endsWith('.') || host.contains("..")) return null
        if (!host.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it.code > 127 }) return null
        val shown = if (host.all { it.code < 128 }) host else {
            try { IDN.toASCII(host, IDN.ALLOW_UNASSIGNED) } catch (_: IllegalArgumentException) { return null }
        }
        return "https://" + shown + (if (colon >= 0) ":$port" else "") + rest.substring(authorityEnd)
    }

    /// A link's host as the address names it, with its port: an international
    /// name in its `xn--` form, so a look-alike in another script cannot pass
    /// for the real one.
    fun host(url: String): String? {
        val rest = url.substringAfter("://", url)
        val authority = rest.substring(0, rest.indexOfFirst { it in "/?#" }.let { if (it < 0) rest.length else it })
        val name = authority.substringAfterLast('@')
        return name.takeIf { it.isNotEmpty() }
    }

    /// How a link reads in a message: the whole address as written, scheme and
    /// all, never shortened, so selecting it copies a working link. The host
    /// is never disguised: one written in another script shows as the address
    /// names it (`host`).
    fun linkLabel(text: String, url: String): String {
        val at = text.indexOf("://")
        val start = if (at >= 0) at + 3 else 0
        val hostEnd = text.indexOfFirst(start) { it in "/?#" }.let { if (it < 0) text.length else it }
        val shownHost = host(url) ?: return text
        if (text.substring(start, hostEnd).lowercase() == shownHost.lowercase()) return text
        return text.substring(0, start) + shownHost + text.substring(hostEnd)
    }

    private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
        for (i in from until length) if (predicate(this[i])) return i
        return -1
    }

    /// The first link in a message's text, bare or named.
    fun firstLink(text: String): String? {
        for (inline in inlines(text)) {
            when (val k = inline.kind) {
                is Kind.Link -> return k.url
                is Kind.NamedLink -> return k.url
                else -> continue
            }
        }
        return null
    }

    // MARK: Emphasis

    /// A run of one emphasis mark between spans, and whether it can open or
    /// close (CommonMark's flanking rules: "a * b" and "snake_case" do neither).
    private class Marks(val mark: Char, var start: Int, var end: Int, val opens: Boolean, val closes: Boolean) {
        val isEmpty get() = start >= end
        val length get() = end - start
    }

    private sealed interface Piece {
        data class Text(val start: Int, val end: Int) : Piece
        data class Sp(val span: Span) : Piece
        data class Run(val marks: Marks) : Piece
    }

    private val emphasisKinds = listOf(Emphasis.BOLD, Emphasis.ITALIC, Emphasis.STRIKETHROUGH)

    /// The text as it shows: `spans` with plain text between, and emphasis
    /// marks that pair up taken out, what they hold emphasised. Marks are
    /// never read inside a span, so code shows as typed; a mark nothing closes
    /// shows as typed. Each kind of mark pairs on its own, so every mark is
    /// looked at once however the text nests.
    fun inlines(text: String): List<Inline> {
        val pieces = ArrayList<Piece>()
        var from = 0
        /// The text from `from` up to `end`, as plain text and runs of marks.
        fun read(end: Int) {
            var plainFrom = from
            while (from < end) {
                val mark = text[from]
                var runEnd = from + 1
                if (mark !in emphasisMarks) {
                    from = runEnd
                    continue
                }
                while (runEnd < end && text[runEnd] == mark) runEnd++
                val marks = marks(mark, from, runEnd, text)
                if (marks != null) {
                    if (plainFrom < from) pieces.add(Piece.Text(plainFrom, from))
                    pieces.add(Piece.Run(marks))
                    plainFrom = runEnd
                }
                from = runEnd
            }
            if (plainFrom < end) pieces.add(Piece.Text(plainFrom, end))
        }
        for (span in spans(text)) {
            read(span.start)
            pieces.add(Piece.Sp(span))
            from = span.end
        }
        read(text.length)

        // Each pair adds its emphasis to the pieces between, counted at the
        // first piece and taken away after the last.
        val change = Array(emphasisKinds.size) { IntArray(pieces.size + 1) }
        val open = HashMap<Char, ArrayList<Int>>()
        for (closer in pieces.indices) {
            val marks = (pieces[closer] as? Piece.Run)?.marks ?: continue
            while (marks.closes && !marks.isEmpty) {
                val opener = open[marks.mark]?.lastOrNull() ?: break
                val opening = (pieces[opener] as Piece.Run).marks
                val used = if (minOf(opening.length, marks.length) >= 2) 2 else 1
                opening.end -= used
                marks.start += used
                if (opening.isEmpty) open[marks.mark]!!.removeAt(open[marks.mark]!!.lastIndex)
                val kind = if (marks.mark == '~') 2 else if (used == 2) 0 else 1
                change[kind][opener + 1] += 1
                change[kind][closer] -= 1
            }
            if (marks.opens && !marks.isEmpty) open.getOrPut(marks.mark) { ArrayList() }.add(closer)
        }

        val inlines = ArrayList<Inline>()
        // Plain text runs on until its emphasis changes or a span comes.
        val plain = StringBuilder()
        var plainEmphasis = Emphasis.NONE
        fun endPlain() {
            if (plain.isNotEmpty()) inlines.add(Inline(plain.toString(), null, plainEmphasis))
            plain.setLength(0)
        }
        val depth = IntArray(emphasisKinds.size)
        for ((index, piece) in pieces.withIndex()) {
            var bits = 0
            for (kind in emphasisKinds.indices) {
                depth[kind] += change[kind][index]
                if (depth[kind] > 0) bits = bits or emphasisKinds[kind].bits
            }
            val emphasis = Emphasis(bits)
            val shown: String = when (piece) {
                is Piece.Sp -> {
                    endPlain()
                    inlines.add(Inline(piece.span.text, piece.span.kind, emphasis))
                    continue
                }
                is Piece.Text -> text.substring(piece.start, piece.end)
                is Piece.Run -> text.substring(piece.marks.start, piece.marks.end)
            }
            if (shown.isEmpty()) continue
            if (emphasis != plainEmphasis) {
                endPlain()
                plainEmphasis = emphasis
            }
            plain.append(shown)
        }
        endPlain()
        return inlines
    }

    /// A run of marks that can emphasise: one to three "*" or "_", or "~~".
    private fun marks(mark: Char, start: Int, end: Int, text: String): Marks? {
        val count = end - start
        if (if (mark == '~') count != 2 else count > 3) return null
        val before = if (start > 0) text.codePointBefore(start) else null
        val after = if (end < text.length) text.codePointAt(end) else null
        fun blank(c: Int?) = c?.let { Character.isWhitespace(it) || Character.isSpaceChar(it) } ?: true
        fun punctuation(c: Int?) = c?.let { isPunctuationOrSymbol(it) } ?: false
        val left = !blank(after) && (!punctuation(after) || blank(before) || punctuation(before))
        val right = !blank(before) && (!punctuation(before) || blank(after) || punctuation(after))
        // "_" inside a word is the word's.
        val opens = if (mark == '_') left && (!right || punctuation(before)) else left
        val closes = if (mark == '_') right && (!left || punctuation(after)) else right
        return if (opens || closes) Marks(mark, start, end, opens, closes) else null
    }

    private fun isPunctuationOrSymbol(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
        Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
        -> true
        else -> false
    }

    // MARK: Character classes (Foundation's `.whitespaces` and `.newlines`)

    private fun isNewline(c: Char) = c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C' ||
        c == '\u0085' || c == '\u2028' || c == '\u2029'

    private fun isHorizontalSpace(c: Char) = c == ' ' || c == '\t' || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()

    private fun trimSpaces(s: String) = s.trim { isHorizontalSpace(it) }

    private fun isBlank(s: String) = s.all { it.isWhitespace() || Character.isSpaceChar(it) }
}
