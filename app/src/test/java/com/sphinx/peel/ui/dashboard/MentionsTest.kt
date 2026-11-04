package com.sphinx.peel.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MentionsTest {
    private fun m(name: String) = MentionMember(name, null, name)

    @Test fun wordAtCursorNeedsLeadingAt() {
        assertEquals(MentionWord(3, 6, "To"), mentionWord("hi @To", 6))
        assertNull(mentionWord("hi To", 5))
        assertNull(mentionWord("hi @To ", 7))
        assertEquals("", mentionWord("@", 1)?.query)
    }

    @Test fun handleUsesUnderscores() {
        assertEquals("@Tom_Smith", mentionHandle("Tom Smith"))
    }

    @Test fun suggestionsFilterAndSort() {
        val members = listOf(m("bob"), m("Tom Smith"), m("Alice"), m("tim"))
        assertEquals(listOf("Alice", "bob", "tim", "Tom Smith"), mentionSuggestions(members, "").map { it.name })
        assertEquals(listOf("tim", "Tom Smith"), mentionSuggestions(members, "T").map { it.name })
        assertEquals(listOf("Tom Smith"), mentionSuggestions(members, "tom_s").map { it.name })
    }

    @Test fun insertReplacesQueryAndMovesCursor() {
        val word = mentionWord("hey @To there", 7)!!
        val (text, cursor) = insertMention("hey @To there", word, m("Tom Smith"))
        assertEquals("hey @Tom_Smith  there", text)
        assertEquals(15, cursor)
    }
}
