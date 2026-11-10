package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

class ConversationPreviewTest {
    private val now = Instant.parse("2024-03-15T18:00:00Z")
    private fun label(at: String?) =
        dateLabel(at?.let { Instant.parse(it).toEpochMilli() }, now, ZoneOffset.UTC, Locale.US)
            ?.replace(Regex("\\s"), " ")

    @Test
    fun todayShowsTime() {
        assertEquals("3:09 PM", label("2024-03-15T15:09:00Z"))
    }

    @Test
    fun yesterdayShowsDate() {
        assertEquals("3/14/24", label("2024-03-14T23:59:00Z"))
    }

    @Test
    fun olderShowsDate() {
        assertEquals("1/2/23", label("2023-01-02T10:00:00Z"))
    }

    @Test
    fun nullIsNull() {
        assertNull(label(null))
    }
}
