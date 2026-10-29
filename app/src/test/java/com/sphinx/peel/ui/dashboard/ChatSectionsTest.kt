package com.sphinx.peel.ui.dashboard

import com.sphinx.peel.storage.ConversationRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatSectionsTest {
    private fun row(chat: String, isTribe: Boolean, unread: Long = 0, muted: Boolean = false, alias: String = chat) =
        ConversationRow(
            chat, alias = alias, photoUrl = null, isTribe = isTribe, lastContent = null, lastTimestamp = null,
            lastFromMe = null, unread = unread, muteLevel = if (muted) 2 else 0,
        )

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

    @Test
    fun `an unread chat moves to Unread, direct first then tribes, out of its own section`() {
        val rows = listOf(
            row("t1", true, unread = 3),
            row("d1", false),
            row("d2", false, unread = 1),
            row("t2", true),
        )
        val sections = ChatSections.of(rows, tribesOpen = true, directOpen = true, allTribes = false, allDirect = false)

        assertEquals(listOf("d2", "t1"), sections.unread.map { it.chat })
        assertEquals(listOf("t2"), sections.tribes.map { it.chat })
        assertEquals(listOf("d1"), sections.direct.map { it.chat })
    }

    @Test
    fun `Unread never trims, however many`() {
        val rows = (1..8).map { row("d$it", false, unread = 1) }
        val sections = ChatSections.of(rows, tribesOpen = true, directOpen = true, allTribes = false, allDirect = false)

        assertEquals(8, sections.unread.size)
    }

    @Test
    fun `a muted chat's unread never moves it into Unread`() {
        val rows = listOf(row("d1", false, unread = 5, muted = true))
        val sections = ChatSections.of(rows, tribesOpen = true, directOpen = true, allTribes = false, allDirect = false)

        assertEquals(emptyList<ConversationRow>(), sections.unread)
        assertEquals(listOf("d1"), sections.direct.map { it.chat })
    }

    @Test
    fun `a blank query is not searching`() {
        val rows = listOf(row("t1", true, unread = 0, alias = "Sphinx Tribe"))
        val sections = ChatSections.of(rows, tribesOpen = true, directOpen = true, allTribes = false, allDirect = false, query = "   ")

        assertEquals(false, sections.searching)
        assertEquals(listOf("t1"), sections.tribes.map { it.chat })
    }

    @Test
    fun `a query filters every section by name, case-insensitively`() {
        val rows = listOf(
            row("t1", true, unread = 0, alias = "Sphinx Tribe"),
            row("t2", true, unread = 2, alias = "Other Tribe"),
            row("d1", false, unread = 0, alias = "Alice"),
        )
        val sections = ChatSections.of(rows, tribesOpen = true, directOpen = true, allTribes = false, allDirect = false, query = "sphinx")

        assertEquals(true, sections.searching)
        assertEquals(listOf("t1"), sections.tribes.map { it.chat })
        assertEquals(emptyList<ConversationRow>(), sections.unread)
        assertEquals(emptyList<ConversationRow>(), sections.direct)
    }

    @Test
    fun `searching shows every match with no trimming and no Show all row`() {
        val rows = (1..8).map { row("t$it", true, unread = 0, alias = "Tribe $it") }
        val sections = ChatSections.of(rows, tribesOpen = false, directOpen = false, allTribes = false, allDirect = false, query = "Tribe")

        assertEquals(8, sections.tribes.size)
        assertNull(sections.allTribesCount)
    }

    @Test
    fun `searching with no matches returns empty sections`() {
        val rows = listOf(row("t1", true, unread = 0, alias = "Sphinx Tribe"))
        val sections = ChatSections.of(rows, tribesOpen = true, directOpen = true, allTribes = false, allDirect = false, query = "nothing matches this")

        assertEquals(emptyList<ConversationRow>(), sections.tribes)
        assertEquals(emptyList<ConversationRow>(), sections.direct)
        assertEquals(emptyList<ConversationRow>(), sections.unread)
    }
}
