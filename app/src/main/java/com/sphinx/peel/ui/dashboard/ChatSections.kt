package com.sphinx.peel.ui.dashboard

import com.sphinx.peel.storage.ConversationRow

/// Tribes, then direct messages, each trimmed until shown in full. Ported
/// from PeelCore's `ChatSections.swift`, cut down to what this phase
/// actually tracks: no Unread section (no read-state table yet — see
/// `docs/phases/04-dashboard.md`), so every chat sorts by its newest
/// message alone, and no search. The trimming and "Show all N" behaviour
/// is the real thing, not a placeholder.
data class ChatSections(
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
            val tribes = conversations.filter { it.isTribe }
            val direct = conversations.filter { !it.isTribe }
            val (shownTribes, tribesCount) = shown(tribes, tribesOpen, allTribes)
            val (shownDirect, directCount) = shown(direct, directOpen, allDirect)
            return ChatSections(shownTribes, tribesCount, shownDirect, directCount)
        }

        /// A section's rows and its "Show all" count. Ported from
        /// `ChatSections.shown`, minus the "keep the open chat visible"
        /// case — there is no chat screen to open yet.
        private fun shown(rows: List<ConversationRow>, open: Boolean, all: Boolean): Pair<List<ConversationRow>, Int?> {
            if (!open) return emptyList<ConversationRow>() to null
            if (rows.size <= RECENT) return rows to null
            if (all) return rows to rows.size
            return rows.take(RECENT) to rows.size
        }
    }
}
