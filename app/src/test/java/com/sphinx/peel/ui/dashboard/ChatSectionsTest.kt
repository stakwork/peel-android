package com.sphinx.peel.ui.dashboard

import com.sphinx.peel.engine.InviteRecord
import com.sphinx.peel.storage.ConversationRow
import org.junit.Assert.assertEquals
import org.junit.Test

/// Chats' one list (docs/phases/58-chats-one-list.md; PeelCore's `latestListsEveryChatNewestFirst`).
class ChatSectionsTest {
    private fun row(chat: String, isTribe: Boolean = false, unread: Long = 0, at: Long? = null) =
        ConversationRow(
            chat, alias = chat, photoUrl = null, isTribe = isTribe, lastContent = null, lastTimestamp = at,
            lastFromMe = null, unread = unread,
        )

    private fun invite(code: String) = InviteRecord(code, "l", 1, null, null, "t", InviteRecord.Status.READY, 0)

    // As `Storage.conversations` reads them: newest first, never-messaged last.
    private val rows = listOf(
        row("t1", isTribe = true, at = 50), row("d1", unread = 3, at = 40), row("agent", unread = 1, at = 30),
        row("t2", isTribe = true, unread = 2, at = 20), row("d2", at = 10), row("d3"),
    )

    @Test
    fun `every chat but the agent's, newest first, unread ones in place`() {
        assertEquals(listOf("t1", "d1", "t2", "d2", "d3"), ChatSections.latest(rows, "agent").map { it.chat })
        // Never trimmed: no "Show all".
        val many = (1..40).map { row("c$it", at = 100L - it) }
        assertEquals(40, ChatSections.latest(many, "agent").size)
    }

    @Test
    fun `invites first, then Peel's row, then the chats`() {
        val items = ChatSections.items(rows, listOf(invite("i1"), invite("i2")), "agent")
        assertEquals(
            listOf("invite-i1", "invite-i2", "agent", "chat-t1", "chat-d1", "chat-t2", "chat-d2", "chat-d3"),
            items.map { it.key },
        )
        // Peel's row even while unread: never in the chats below it.
        assertEquals("agent", (items[2] as ChatSections.Item.Agent).conversation.chat)
    }

    @Test
    fun `no agent chat yet means no Peel row, and no chats leaves the invites`() {
        val items = ChatSections.items(rows.filter { it.chat != "agent" }, emptyList(), "agent")
        assertEquals(listOf("chat-t1", "chat-d1", "chat-t2", "chat-d2", "chat-d3"), items.map { it.key })
        assertEquals(listOf("invite-i1"), ChatSections.items(emptyList(), listOf(invite("i1")), "agent").map { it.key })
    }
}
