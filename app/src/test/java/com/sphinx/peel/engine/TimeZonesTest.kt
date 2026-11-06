package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class TimeZonesTest {
    private val now = Instant.parse("2026-01-15T12:00:00Z")

    @Test
    fun `formats offsets like the official apps`() {
        assertEquals("GMT+1", TimeZones.format(ZoneId.of("Europe/Paris"), now))
        assertEquals("GMT-5", TimeZones.format(ZoneId.of("America/New_York"), now))
        assertEquals("GMT+5:30", TimeZones.format(ZoneId.of("Asia/Kolkata"), now))
        assertEquals("GMT+0", TimeZones.format(ZoneOffset.UTC, now))
        assertEquals("GMT+5:45", TimeZones.format(ZoneId.of("Asia/Kathmandu"), now))
    }

    @Test
    fun `reads what arrives`() {
        assertEquals(ZoneOffset.ofHoursMinutes(5, 30), TimeZones.zone("GMT+5:30"))
        assertEquals(ZoneOffset.ofHours(-5), TimeZones.zone("UTC-5"))
        assertEquals(ZoneOffset.UTC, TimeZones.zone("GMT"))
        assertEquals(ZoneId.of("Europe/London"), TimeZones.zone("Europe/London"))
        assertNull(TimeZones.zone("GMT+15"))
        assertNull(TimeZones.zone("GMT+5:7"))
        assertNull(TimeZones.zone("EST"))
        assertNull(TimeZones.zone("/etc/passwd"))
        assertNull(TimeZones.zone("../a/b"))
    }

    @Test
    fun `finds tz in a body's metadata`() {
        assertEquals("GMT+2", TimeZones.sent("""{"content":"hi","metadata":"{\"tz\":\"GMT+2\"}"}"""))
        assertNull(TimeZones.sent("""{"content":"hi"}"""))
        assertNull(TimeZones.sent(null))
        assertEquals(ZoneOffset.ofHours(2), TimeZones.newest(listOf("""{"metadata":"{\"tz\":\"nonsense\"}"}""", """{"metadata":"{\"tz\":\"GMT+2\"}"}""")))
    }

    @Test
    fun `local time line is hidden when offsets match`() {
        val paris = ZoneId.of("Europe/Paris")
        assertNull(TimeZones.localTimeLine(paris, now, paris) { _, _ -> "x" })
        assertEquals("x local time · 6 h ahead", TimeZones.localTimeLine(ZoneOffset.ofHours(1), now, ZoneOffset.ofHours(-5)) { _, _ -> "x" })
        assertEquals("30 min behind", TimeZones.offsetText(-1800))
    }

    @Test
    fun `picker starts with the default and has every zone`() {
        assertEquals(TimeZones.DEFAULT_CHOICE, TimeZones.choices.first())
        assertTrue("Europe/Paris" in TimeZones.choices)
        assertTrue(TimeZones.choices.drop(1).all { TimeZones.zoneNamed(it) != null })
    }
}
