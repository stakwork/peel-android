package com.sphinx.peel.ui.dashboard

import com.sphinx.peel.storage.ConversationRow

/// Unread first, then Tribes, then Direct messages, each trimmed until
/// shown in full. Ported from PeelCore's `ChatSections.swift`. `unread`
/// is never trimmed, same as there — PeelCore's own `unread` array has
/// no `shown()` call either, so a chat with something new never hides
/// behind "Show all". Simplified from PeelCore's own split within
/// `unread` (direct first, then tribes that mention you, then other
/// tribes): this port has no mention tracking, so it's direct first,
/// then every unread tribe, with no further split.
///
/// `query` filters every section by name before splitting into unread/
/// tribes/direct (PeelCore's own `init`, which checks `matches` before
/// classifying a conversation at all) — a chat not matching the query
/// doesn't show, unread or not. `searching` (non-blank `query`) forces
/// every section open with every match shown, no trimming and no "Show
/// all" row (PeelCore's own `tribesOpen = layout.tribesOpen || searching`
/// and `shown`'s own `!searching` guard) — PeelCore's iPhone `SearchTab`
/// reuses the same `ChatList` the Chats tab does, filtered this way.
data class ChatSections(
    val unread: List<ConversationRow>,
    val tribes: List<ConversationRow>,
    val allTribesCount: Int?,
    val direct: List<ConversationRow>,
    val allDirectCount: Int?,
    val searching: Boolean = false,
) {
    companion object {
        /// How many show before "Show all" — PeelCore's own `recent`.
        const val RECENT = 5

        fun of(
            conversations: List<ConversationRow>,
            tribesOpen: Boolean,
            directOpen: Boolean,
            allTribes: Boolean,
            allDirect: Boolean,
            query: String = "",
        ): ChatSections {
            val searching = query.isNotBlank()
            val matching = if (searching) conversations.filter { matches(it.name, query) } else conversations
            val unreadDirect = matching.filter { !it.isTribe && it.shownUnread > 0 }
            val unreadTribes = matching.filter { it.isTribe && it.shownUnread > 0 }
            val tribes = matching.filter { it.isTribe && it.shownUnread == 0L }
            val direct = matching.filter { !it.isTribe && it.shownUnread == 0L }
            val (shownTribes, tribesCount) = shown(tribes, tribesOpen || searching, allTribes, searching)
            val (shownDirect, directCount) = shown(direct, directOpen || searching, allDirect, searching)
            return ChatSections(unreadDirect + unreadTribes, shownTribes, tribesCount, shownDirect, directCount, searching)
        }

        /// Whether a chat named `name` shows for a search — a blank query
        /// matches everything; PeelCore's own `matches`.
        fun matches(name: String, query: String): Boolean {
            val trimmed = query.trim()
            return trimmed.isEmpty() || name.contains(trimmed, ignoreCase = true)
        }

        /// A section's rows and its "Show all" count. Ported from
        /// `ChatSections.shown`, minus the "keep the open chat visible"
        /// case — a chat open in `ConversationScreen` can still vanish
        /// from a collapsed or trimmed section underneath it.
        private fun shown(rows: List<ConversationRow>, open: Boolean, all: Boolean, searching: Boolean): Pair<List<ConversationRow>, Int?> {
            if (!open) return emptyList<ConversationRow>() to null
            if (searching) return rows to null
            if (rows.size <= RECENT) return rows to null
            if (all) return rows to rows.size
            return rows.take(RECENT) to rows.size
        }
    }
}
