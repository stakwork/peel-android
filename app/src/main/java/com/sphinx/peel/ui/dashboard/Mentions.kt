package com.sphinx.peel.ui.dashboard

/// A chat member who can be `@mentioned` — name, photo and avatar-colour key.
data class MentionMember(val name: String, val photoUrl: String?, val hueKey: String)

/// The `@word` the cursor is in: `start` is the index of its `@`, `end` the
/// cursor, `query` what follows the `@` — PeelCore's `MentionQuery.word`.
data class MentionWord(val start: Int, val end: Int, val query: String)

/// The word ending at `cursor` (back to the last whitespace), only when it
/// starts with `@`; null otherwise.
fun mentionWord(text: String, cursor: Int): MentionWord? {
    if (cursor < 0 || cursor > text.length) return null
    var start = cursor
    while (start > 0 && !text[start - 1].isWhitespace()) start--
    if (start >= cursor || text[start] != '@') return null
    return MentionWord(start, cursor, text.substring(start + 1, cursor))
}

/// `@Tom_Smith` for "Tom Smith" — spaces become underscores.
fun mentionHandle(name: String): String = "@" + name.trim().replace(Regex("\\s+"), "_")

/// Members matching what's typed after the `@` (prefix of the name or of its
/// underscore handle, ignoring case), alphabetical by name.
fun mentionSuggestions(members: List<MentionMember>, typed: String): List<MentionMember> {
    val q = typed.lowercase()
    return members
        .filter { it.name.lowercase().startsWith(q) || mentionHandle(it.name).substring(1).lowercase().startsWith(q) }
        .distinctBy { it.name }
        .sortedBy { it.name.lowercase() }
}

/// `text` with the typed `@query` replaced by `@Handle ` (trailing space),
/// and where the cursor goes — right after that space.
fun insertMention(text: String, word: MentionWord, member: MentionMember): Pair<String, Int> {
    val insert = mentionHandle(member.name) + " "
    return (text.substring(0, word.start) + insert + text.substring(word.end)) to (word.start + insert.length)
}
