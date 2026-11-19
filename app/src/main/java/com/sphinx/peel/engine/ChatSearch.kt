package com.sphinx.peel.engine

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sphinx.peel.storage.MessageRow

/// One chat's search (port of `peel`'s `ChatSearch`, PR #576): what was typed, what was last searched for
/// and found, which match is the current one, and the words the bar shows. Held by the chat screen for as
/// long as the search is open, so it is scoped to one chat and gone with it. Observable by Compose
/// (`mutableStateOf`, as `Boxes`/`Stadeum` are); the searching itself is the screen's, which calls [found].
/// Matches are newest first: index 0 is the newest, so stepping to an OLDER match moves the index up.
class ChatSearch(val chat: String) {
    /// What is typed in the field.
    var text by mutableStateOf("")

    /// What [matches] are for; null while nothing has been searched.
    var query by mutableStateOf<MessageQuery?>(null)
        private set

    /// The matches, newest first.
    var matches by mutableStateOf<List<MessageRow>>(emptyList())
        private set

    /// The index in [matches] of the match being shown; null until one is picked (0 is the newest).
    var current by mutableStateOf<Int?>(null)

    /// A search for the typed text is running.
    var searching by mutableStateOf(false)

    /// The last search could not be run.
    var failed by mutableStateOf(false)
        private set

    /// History was still being restored when the last search ran, so older messages may be missing.
    var historySyncing by mutableStateOf(false)
        private set

    /// Counts the times the Search menu item was used while the bar was open: the field takes focus again.
    var focusRequests by mutableStateOf(0)
        private set

    /// The query Return (the keyboard's Search key) was pressed for before its matches arrived: when they do,
    /// the first is shown.
    var returnAwaits by mutableStateOf<MessageQuery?>(null)
        private set

    /// What the typed text means as a query.
    val typed: MessageQuery get() = MessageQuery.parse(text)

    /// The bar's count: "Could not search", "No matches", "3 matches", "2 of 3", or null (nothing searched, or
    /// still searching with nothing yet).
    val status: String?
        get() {
            if (failed) return "Could not search"
            if (query == null || (searching && matches.isEmpty())) return null
            if (matches.isEmpty()) return "No matches"
            val total = if (matches.size >= LIMIT) "1,000+" else matches.size.toString()
            val at = current ?: return if (matches.size == 1) "1 match" else "$total matches"
            return "${at + 1} of $total"
        }

    /// What the count slot says: [status], or why a search of only short words did not run.
    val countText: String?
        get() {
            status?.let { return it }
            val t = typed
            return if (t.isEmpty && t.hasShortTerms) "${MessageQuery.minimum} or more characters" else null
        }

    /// Shown under the results while the history they come from is still arriving.
    val historyNote: String? get() = if (historySyncing && query != null) "Still loading older messages" else null

    val canStepOlder: Boolean get() = (current ?: -1) + 1 < matches.size
    val canStepNewer: Boolean get() = (current ?: 0) > 0

    /// The Search menu item again: focus the field again.
    fun requestFocus() {
        focusRequests += 1
    }

    /// The next older match (the first, when none is picked yet), now the current one; null at the end.
    fun stepOlder(): MessageRow? {
        if (!canStepOlder) return null
        val next = (current ?: -1) + 1
        current = next
        return matches[next]
    }

    /// The next newer match, now the current one; null at the newest or when none is picked.
    fun stepNewer(): MessageRow? {
        if (!canStepNewer) return null
        val next = (current ?: 0) - 1
        current = next
        return matches[next]
    }

    /// Picks the match at [index] (the results list); null when there is none there.
    fun select(index: Int): MessageRow? {
        val row = matches.getOrNull(index) ?: return null
        current = index
        return row
    }

    /// Whether the typed query is one nobody has searched yet; if so, Return is remembered to show the first
    /// match when [found] delivers them.
    fun waitForMatches(): Boolean {
        val typed = typed
        if (typed.isEmpty || typed == query) return false
        returnAwaits = typed
        return true
    }

    /// A search finished: [matches] for [query]. No match is current afterwards. True when Return was waiting
    /// for exactly these and there is a first match to show.
    fun found(matches: List<MessageRow>, query: MessageQuery?, failed: Boolean = false, historySyncing: Boolean = false): Boolean {
        this.matches = matches
        this.query = query
        this.failed = failed
        this.historySyncing = historySyncing
        current = null
        searching = false
        val awaited = returnAwaits != null && returnAwaits == query
        if (awaited) returnAwaits = null
        return awaited && !failed && matches.isNotEmpty()
    }

    /// The typed text asks for nothing: no results, nothing pending.
    fun clearResults() {
        found(emptyList(), null)
        returnAwaits = null
    }

    companion object {
        /// How many matches a search keeps (`Storage.searchMessages`): at this many the count reads "1,000+".
        const val LIMIT = 1000
    }
}
