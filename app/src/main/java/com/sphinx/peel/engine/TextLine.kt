package com.sphinx.peel.engine

// Page-supplied text made safe to show (iOS `TextLine.clean`). Pure, JVM-tested.

object TextLine {
    /// Characters that draw nothing but reorder or hide the text around them: bidi marks and embeddings, zero-width
    /// and soft-hyphen characters, the BOM and the tag block.
    private fun hidden(cp: Int): Boolean = when (cp) {
        0x200E, 0x200F, 0x061C, 0x200B, 0x2060, 0xFEFF, 0x00AD -> true
        in 0x202A..0x202E, in 0x2066..0x2069, in 0xE0000..0xE007F -> true
        else -> false
    }

    private fun space(cp: Int): Boolean =
        Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85

    /// [text] on one line: control characters and line/paragraph separators are spaces, hidden characters are
    /// dropped, runs of whitespace are one space (and the ends are trimmed), `<<<` (which a transcript could read as
    /// its own markup) is broken up, and more than [max] characters is cut to `max - 1` and an ellipsis.
    fun clean(text: String, max: Int): String {
        val spaced = StringBuilder()
        text.codePoints().forEach { cp ->
            val type = Character.getType(cp)
            when {
                type == Character.CONTROL.toInt() || type == Character.LINE_SEPARATOR.toInt() ||
                    type == Character.PARAGRAPH_SEPARATOR.toInt() -> spaced.append(' ')
                hidden(cp) -> Unit
                else -> spaced.appendCodePoint(cp)
            }
        }
        // Split on any whitespace by code point, then join with single spaces.
        val words = mutableListOf<String>()
        val word = StringBuilder()
        spaced.codePoints().forEach { cp ->
            if (space(cp)) {
                if (word.isNotEmpty()) { words += word.toString(); word.setLength(0) }
            } else {
                word.appendCodePoint(cp)
            }
        }
        if (word.isNotEmpty()) words += word.toString()
        var line = words.joinToString(" ")
        while (line.contains("<<<")) line = line.replace("<<<", "<< <")
        if (line.codePointCount(0, line.length) <= max) return line
        val keep = (max - 1).coerceAtLeast(0)
        return line.substring(0, line.offsetByCodePoints(0, keep)) + "…"
    }
}
