package com.sphinx.peel.engine

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TimeZoneSharingTest {
    private val now = 1_700_000_000_000L // mid November: no daylight saving in the northern hemisphere

    @Test
    fun `offsets read as GMT sign hours and minutes only when not zero`() {
        assertEquals("GMT+1", TimeZoneSharing.formatOffset(3_600))
        assertEquals("GMT-5", TimeZoneSharing.formatOffset(-5 * 3_600))
        assertEquals("GMT+5:30", TimeZoneSharing.formatOffset(5 * 3_600 + 1_800))
        assertEquals("GMT-3:30", TimeZoneSharing.formatOffset(-(3 * 3_600 + 1_800)))
        assertEquals("GMT+0", TimeZoneSharing.formatOffset(0))
        assertEquals("GMT+5:45", TimeZoneSharing.formatOffset(5 * 3_600 + 2_700))
    }

    @Test
    fun `a chosen zone wins over the device and an unknown one falls back to it`() {
        val device = TimeZone.getTimeZone("America/New_York")
        assertEquals("GMT+1", TimeZoneSharing.offsetLabel("Europe/Paris", now, device))
        assertEquals("GMT-5", TimeZoneSharing.offsetLabel("", now, device))
        assertEquals("GMT-5", TimeZoneSharing.offsetLabel("Not/AZone", now, device))
        assertEquals("GMT+5:30", TimeZoneSharing.offsetLabel("Asia/Kolkata", now, device))
    }

    @Test
    fun `metadata only where offered and on, default on`() {
        val device = TimeZone.getTimeZone("Europe/Paris")
        assertEquals("""{"tz":"GMT+1"}""", TimeZoneSharing.metadata(null, true, now, device))
        assertEquals("""{"tz":"GMT-5"}""", TimeZoneSharing.metadata(TimeZoneSharing.Choice(true, "America/New_York"), true, now, device))
        assertNull(TimeZoneSharing.metadata(TimeZoneSharing.Choice(false), true, now, device))
        assertNull(TimeZoneSharing.metadata(null, false, now, device))
    }

    @Test
    fun `options start with the automatic one and search narrows the list`() {
        val all = TimeZoneSharing.options("", "Europe/Paris", listOf("Europe/Paris", "Asia/Kolkata", "Asia/Kolkata"))
        assertEquals(listOf("", "Asia/Kolkata", "Europe/Paris"), all.map { it.identifier })
        assertEquals("Automatic · Europe/Paris", all[0].title)
        assertEquals(listOf("America/New_York"), TimeZoneSharing.options("new york", "Europe/Paris", listOf("America/New_York", "Asia/Tokyo")).map { it.identifier })
        assertEquals(listOf(""), TimeZoneSharing.options("automatic", "Europe/Paris", listOf("Asia/Tokyo")).map { it.identifier })
    }

    @Test
    fun `decode reads current and legacy fields`() {
        assertEquals(TimeZoneSharing.Choice(false, "Asia/Tokyo", 5), TimeZoneSharing.decode("false", "Asia/Tokyo", null, null, 5))
        assertEquals(TimeZoneSharing.Choice(true, "", 5), TimeZoneSharing.decode("TRUE", "", null, null, 5))
        assertEquals(TimeZoneSharing.Choice(false, "Asia/Tokyo", 0), TimeZoneSharing.decode(null, null, false, "Asia/Tokyo", 0))
        assertEquals(TimeZoneSharing.Choice(true, "", 0), TimeZoneSharing.decode(null, null, null, null, 0))
        assertNull(TimeZoneSharing.decode("maybe", "", null, null, 0))
    }

    @Test
    fun `merge follows the newest date`() {
        val local = TimeZoneSharing.Choice(false, "Asia/Tokyo", 100)
        val older = TimeZoneSharing.FileItem(TimeZoneSharing.Choice(true, "", 50), 50)
        val newer = TimeZoneSharing.FileItem(TimeZoneSharing.Choice(true, "", 200), 200)
        assertEquals(TimeZoneSharing.Outcome.Upload(local), TimeZoneSharing.decide(older, local))
        assertEquals(TimeZoneSharing.Outcome.Take(TimeZoneSharing.Choice(true, "", 200)), TimeZoneSharing.decide(newer, local))
        assertEquals(TimeZoneSharing.Outcome.Nothing, TimeZoneSharing.decide(TimeZoneSharing.FileItem(local, 100), local))
        assertEquals(TimeZoneSharing.Outcome.Nothing, TimeZoneSharing.decide(TimeZoneSharing.FileItem(null, 200), local))
        assertEquals(TimeZoneSharing.Outcome.Take(TimeZoneSharing.Choice(true, "", 200)), TimeZoneSharing.decide(newer, null))
    }

    @Test
    fun `a choice only here goes up when chosen on a device`() {
        assertEquals(TimeZoneSharing.Outcome.Nothing, TimeZoneSharing.decide(null, TimeZoneSharing.Choice(date = 0)))
        assertEquals(TimeZoneSharing.Outcome.Nothing, TimeZoneSharing.decide(null, null))
        val chosen = TimeZoneSharing.Choice(false, "", 9)
        assertEquals(TimeZoneSharing.Outcome.Upload(chosen), TimeZoneSharing.decide(null, chosen))
    }

    @Test
    fun `the local store and the privacy flags round trip`() {
        val store = mapOf("a" to TimeZoneSharing.Choice(false, "Asia/Tokyo", 7), "b" to TimeZoneSharing.Choice())
        assertEquals(store, TimeZoneSharing.decodeStore(TimeZoneSharing.encodeStore(store)))
        assertTrue(TimeZoneSharing.decodeStore(null).isEmpty())
        assertTrue(TimeZoneSharing.decodeStore("not json").isEmpty())
        val flags = mapOf("t1" to true, "t2" to false)
        assertEquals(flags, TimeZoneSharing.decodeFlags(TimeZoneSharing.encodeFlags(flags)))
    }

    @Test
    fun `the preferences file carries a chat's timezone item as the iPhone writes it`() {
        val file = PreferencesFile.empty()
        file.setTimeZone("chat1", TimeZoneSharing.Choice(false, "Asia/Tokyo"), 42)
        val reread = PreferencesFile.parse(file.text())
        val item = reread.timeZone("chat1")!!
        assertEquals(TimeZoneSharing.Choice(false, "Asia/Tokyo", 42), item.choice)
        assertEquals(42L, item.date)
        assertEquals(listOf("chat1"), reread.chatIdentifiers(TimeZoneSharing.KEY))
        assertNull(reread.timeZone("other"))
        assertNull(reread.tipAmount())
    }

    @Test
    fun `the preferences file reads legacy keys and a value held as JSON text`() {
        val file = PreferencesFile.parse(
            """{"items":[
                {"key":"timezone","identifier":"c","date":"3","value":{"timezoneEnabled":"false","timezoneIdentifier":"Asia/Tokyo"}},
                {"key":"timezone","identifier":"d","date":"4","value":"{\"timezone_enabled\":\"true\",\"timezone_identifier\":\"\"}"}
            ]}""",
        )
        assertEquals(TimeZoneSharing.Choice(false, "Asia/Tokyo", 3), file.timeZone("c")!!.choice)
        assertEquals(TimeZoneSharing.Choice(true, "", 4), file.timeZone("d")!!.choice)
    }
}
