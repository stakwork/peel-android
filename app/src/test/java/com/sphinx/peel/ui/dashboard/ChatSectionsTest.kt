package com.sphinx.peel.ui.dashboard

import com.sphinx.peel.storage.ConversationRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatSectionsTest {
    private fun row(chat: String, isTribe: Boolean) =
        ConversationRow(chat, alias = chat, photoUrl = null, isTribe = isTribe, lastContent = null, lastTimestamp = null, lastFromMe = null)

    @Test
    fun `splits tribes from direct messages`() {
        val rows = listOf(row("t1", true), row("d1", false), row("t2", true))
        val sections = ChatSections.of(rows, tribesOpen = true, directOpen = true, allTribes = false, allDirect = false)

        assertEquals(listOf("t1", "t2"), sections.tribes.map { it.chat })
        assertEquals(listOf("d1"), sections.direct.map { it.chat })
    }

    @Test
    fun `a closed section shows nothing`() {
        val rows = listOf(row("t1", true))
        val sections = ChatSections.of(rows, tribesOpen = false, directOpen = true, allTribes = false, allDirect = false)

        assertEquals(emptyList<ConversationRow>(), sections.tribes)
        assertNull(sections.allTribesCount)
    }

    @Test
    fun `trims to RECENT and reports the full count, until all is shown`() {
        val rows = (1..8).map { row("d$it", false) }
        val trimmed = ChatSections.of(rows, tribesOpen = true, directOpen = true, allTribes = false, allDirect = false)
        assertEquals(ChatSections.RECENT, trimmed.direct.size)
        assertEquals(8, trimmed.allDirectCount)

        val all = ChatSections.of(rows, tribesOpen = true, directOpen = true, allTribes = false, allDirect = true)
        assertEquals(8, all.direct.size)
        assertEquals(8, all.allDirectCount)
    }

    @Test
    fun `no Show all row when everything already fits`() {
        val rows = listOf(row("d1", false), row("d2", false))
        val sections = ChatSections.of(rows, tribesOpen = true, directOpen = true, allTribes = false, allDirect = false)

        assertEquals(2, sections.direct.size)
        assertNull(sections.allDirectCount)
    }
}
