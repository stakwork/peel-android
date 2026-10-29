package com.sphinx.peel.ui.dashboard

import com.sphinx.peel.storage.ConversationRow

/// Unread first, then Tribes, then Direct messages, each trimmed until
/// shown in full. Ported from PeelCore's `ChatSections.swift`. `unread`
/// is never trimmed, same as there — PeelCore's own `unread` array has
/// no `shown()` call either, so a chat with something new never hides
/// behind "Show all". Simplified from PeelCore's own split within
/// `unread` (direct first, then tribes that mention you, then other
/// tribes): this port has no mention tracking, so it's direct first,
/// then every unread tribe, with no further split. No search yet.
data class ChatSections(
    val unread: List<ConversationRow>,
    val tribes: List<ConversationRow>,
    val allTribesCount: Int?,
    val direct: List<ConversationRow>,
    val allDirectCount: Int?,
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
        ): ChatSections {
            val unreadDirect = conversations.filter { !it.isTribe && it.shownUnread > 0 }
            val unreadTribes = conversations.filter { it.isTribe && it.shownUnread > 0 }
            val tribes = conversations.filter { it.isTribe && it.shownUnread == 0L }
            val direct = conversations.filter { !it.isTribe && it.shownUnread == 0L }
            val (shownTribes, tribesCount) = shown(tribes, tribesOpen, allTribes)
            val (shownDirect, directCount) = shown(direct, directOpen, allDirect)
            return ChatSections(unreadDirect + unreadTribes, shownTribes, tribesCount, shownDirect, directCount)
        }

        /// A section's rows and its "Show all" count. Ported from
        /// `ChatSections.shown`, minus the "keep the open chat visible"
        /// case — a chat open in `ConversationScreen` can still vanish
        /// from a collapsed or trimmed section underneath it.
        private fun shown(rows: List<ConversationRow>, open: Boolean, all: Boolean): Pair<List<ConversationRow>, Int?> {
            if (!open) return emptyList<ConversationRow>() to null
            if (rows.size <= RECENT) return rows to null
            if (all) return rows to rows.size
            return rows.take(RECENT) to rows.size
        }
    }
}
