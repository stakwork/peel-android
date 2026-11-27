package com.sphinx.peel.ui.dashboard

import com.sphinx.peel.engine.InviteRecord
import com.sphinx.peel.storage.ConversationRow

/// Chats' one list (docs/phases/58-chats-one-list.md; PeelCore's `ChatSections.latest` and the iPhone's `ChatList`, Tom
/// 2026-11-27): the pending invites first, then Peel's own row (the agent's DM), then every other chat newest first, unread ones in
/// place (bold with their counts, `ChatRow`). No Unread / Tribes / Direct messages sections, no "Show all", no search.
/// `Storage.conversations` is already newest first, so the order is kept as read, as PeelCore keeps `conversations`'.
///
/// Not ported: PeelCore's sections (`unread`, `tribes`, `direct`, `combined`) and its `query`, which stay for the Mac's sidebar.
object ChatSections {
    /// One row of the list, keyed for a `LazyColumn`.
    sealed class Item(val key: String) {
        data class Invite(val invite: InviteRecord) : Item("invite-${invite.code}")
        data class Agent(val conversation: ConversationRow) : Item("agent")
        data class Chat(val conversation: ConversationRow) : Item("chat-${conversation.chat}")
    }

    /// Every chat but the agent's [agent] (Peel has its own row), as read: newest first, unread ones in place (`latestChats`).
    fun latest(conversations: List<ConversationRow>, agent: String?): List<ConversationRow> =
        conversations.filter { it.chat != agent }

    /// The whole list: [invites] (the pending ones), the agent's row when its chat exists, then [latest].
    fun items(conversations: List<ConversationRow>, invites: List<InviteRecord>, agent: String?): List<Item> {
        val agentRow = conversations.firstOrNull { it.chat == agent }
        return invites.map { Item.Invite(it) } +
            listOfNotNull(agentRow?.let { Item.Agent(it) }) +
            latest(conversations, agent).map { Item.Chat(it) }
    }
}

/// The pages Chats' More menu opens (the iPhone's `ChatsPage`): Mentions and Threads. A pinned one shows as a chip above the chats,
/// kept by [key] in the layout's `pinnedPages`.
enum class ChatsPage(val key: String, val route: DashboardRoute) {
    MENTIONS("mentions", DashboardRoute.MENTIONS),
    THREADS("threads", DashboardRoute.THREADS),
}
