package com.sphinx.peel.engine

/// Mentions in message text, ported from peel's `Tribe.swift` (`mentions`,
/// `ownMentionAliases`, `tribeAlias`). Not ported: the aliases a tribe echoes
/// back (`echoedAliases`) and a per-tribe alias, which Android does not track
/// yet, so only the account's own alias and "all" light up.
object Mentions {
    private fun isAliasCharacter(c: Char) = c.code < 128 && (c.isLetterOrDigit() || c == '_')

    /// Aliases in tribes: letters, digits and underscores, spaces as
    /// underscores, as the official apps send them.
    fun tribeAlias(alias: String): String =
        alias.replace(" ", "_").filter { isAliasCharacter(it) }.ifEmpty { "Unknown" }

    /// What a mention of this account looks like, for its chip: its tribe
    /// alias and "all". @all is styled as a mention of you.
    fun ownAliases(name: String): Set<String> = setOf(tribeAlias(name), "all")

    /// Whether `text` mentions one of `aliases`: `@alias` in any case, after a
    /// space or the start, and ended by anything an alias cannot contain
    /// ("@Tom_Smith," counts, "@Tom_Smith_2" and "mail@Tom_Smith" do not).
    fun mentions(text: String, aliases: Collection<String>): Boolean {
        if (!text.contains('@')) return false
        val lower = text.lowercase()
        for (alias in aliases.filter { it.isNotEmpty() }) {
            val word = "@" + alias.lowercase()
            var from = 0
            while (true) {
                val found = lower.indexOf(word, from)
                if (found < 0) break
                val end = found + word.length
                val before = found == 0 || !isAliasCharacter(lower[found - 1])
                val after = end == lower.length || !isAliasCharacter(lower[end])
                if (before && after) return true
                from = end
            }
        }
        return false
    }
}
