package com.sphinx.peel.engine

/// How loud a chat is allowed to be, as the server stores it
/// (`chatState.muteLevel`) — PeelCore's `MuteLevel`.
enum class MuteLevel(val raw: Int) {
    ALL(0),
    MENTIONS_ONLY(1),
    MUTED(2),
    ;

    /// As the long-press menu offers it, under "Notify me about".
    val title: String
        get() = when (this) {
            ALL -> "All messages"
            MENTIONS_ONLY -> "Mentions only"
            MUTED -> "Nothing (mute)"
        }

    companion object {
        /// An unknown level reads as "all", as PeelCore's `MuteLevel(rawValue:) ?? .all`.
        fun of(raw: Int): MuteLevel = entries.firstOrNull { it.raw == raw } ?: ALL

        /// The levels a chat offers, loudest first: the tribes server marks
        /// mentions only in tribes, so a direct message has no "Mentions only".
        fun choices(isTribe: Boolean): List<MuteLevel> =
            if (isTribe) listOf(ALL, MENTIONS_ONLY, MUTED) else listOf(ALL, MUTED)
    }
}

/// What a chat's menu offers for its read state — PeelCore's `ReadToggle`:
/// Mark read while it has unread messages, otherwise Mark unread once
/// someone else has written in it (the server keeps a marker only below an
/// incoming message).
enum class ReadToggle(val title: String) {
    MARK_READ("Mark read"),
    MARK_UNREAD("Mark unread"),
}
