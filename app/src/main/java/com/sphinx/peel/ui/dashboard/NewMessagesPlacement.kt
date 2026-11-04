package com.sphinx.peel.ui.dashboard

import com.sphinx.peel.storage.MessageRow

/// Where "New messages" goes: the index of the first incoming message past
/// the read marker the chat had when it was opened (PeelCore's
/// `ChatRow.rows(newAfter:)` / `Message.countsAsUnread(after:)`). Null when
/// there is no marker (nothing was unread, or you have sent since) or no row
/// qualifies. Own messages and boosts never claim it. `rows` must be in
/// display order; the first match wins.
internal fun newMessagesIndex(rows: List<MessageRow>, after: Long?, boostType: Int): Int? {
    if (after == null) return null
    return rows.indexOfFirst { !it.fromMe && it.type != boostType && (it.serverIndex ?: -1L) > after }
        .takeIf { it >= 0 }
}
