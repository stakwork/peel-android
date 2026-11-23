package com.sphinx.peel.ui.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sphinx.peel.engine.InviteRecord
import com.sphinx.peel.storage.ConversationRow
import com.sphinx.peel.storage.Storage

/// The chat list's last read, held outside the composition (one per open account, like `SidebarLayoutState`) so a tab switch
/// shows the previous list at once and refreshes behind it, instead of starting from an empty list. `loaded` is false only until
/// the very first read finishes: the "No chats yet" placeholder must wait for it.
class ChatListState(val storage: Storage) {
    var conversations by mutableStateOf(emptyList<ConversationRow>())
    var unreadMentions by mutableStateOf(0L)
    var unreadThreads by mutableStateOf(0L)
    var pendingInvites by mutableStateOf(emptyList<InviteRecord>())
    var loaded by mutableStateOf(false)

    companion object {
        private var held: ChatListState? = null

        @Synchronized fun of(storage: Storage): ChatListState =
            held?.takeIf { it.storage === storage } ?: ChatListState(storage).also { held = it }
    }
}
