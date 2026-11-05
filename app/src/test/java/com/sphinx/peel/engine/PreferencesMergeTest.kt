package com.sphinx.peel.engine

import com.sphinx.peel.engine.PreferencesMerge.Local
import com.sphinx.peel.engine.PreferencesMerge.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PreferencesMergeTest {
    private val now = 1_800_000_000L

    private fun file(vararg items: String) = PreferencesFile.parse("""{"items":[${items.joinToString(",")}]}""")
    private fun item(key: String, date: String, value: String) =
        """{"key":"$key","identifier":"0","date":"$date","value":"$value"}"""

    @Test
    fun `default boost is 10 sats`() {
        assertEquals(10_000L, BOOST_AMOUNT_MSAT)
    }

    @Test
    fun `the file's newer tip amount is taken`() {
        val out = PreferencesMerge.decide(PreferencesMerge.TIP, file(item("tip_amount", "200", "250")), Local(100, 100), now)
        assertEquals(Outcome.Take(Local(250, 200)), out)
    }

    @Test
    fun `a newer local tip amount goes up`() {
        val out = PreferencesMerge.decide(PreferencesMerge.TIP, file(item("tip_amount", "100", "250")), Local(500, 200), now)
        assertEquals(Outcome.Upload(Local(500, 200)), out)
    }

    @Test
    fun `a tie on date takes the file's value`() {
        val out = PreferencesMerge.decide(PreferencesMerge.TIP, file(item("tip_amount", "100", "250")), Local(500, 100), now)
        assertEquals(Outcome.Take(Local(250, 100)), out)
    }

    @Test
    fun `the same value and date is nothing to do`() {
        val out = PreferencesMerge.decide(PreferencesMerge.TIP, file(item("tip_amount", "100", "250")), Local(250, 100), now)
        assertEquals(Outcome.Nothing, out)
    }

    @Test
    fun `an unset device takes the file's value`() {
        val out = PreferencesMerge.decide(PreferencesMerge.TIP, file(item("tip_amount", "100", "250")), null, now)
        assertEquals(Outcome.Take(Local(250, 100)), out)
    }

    @Test
    fun `no item in the file uploads only what was chosen on a device`() {
        val empty = file(item("chat_color", "1", "#ffffff"))
        assertEquals(Outcome.Upload(Local(7, 5)), PreferencesMerge.decide(PreferencesMerge.TIP, empty, Local(7, 5), now))
        assertEquals(Outcome.Nothing, PreferencesMerge.decide(PreferencesMerge.TIP, empty, Local(7, 0), now))
        assertEquals(Outcome.Nothing, PreferencesMerge.decide(PreferencesMerge.TIP, empty, null, now))
    }

    @Test
    fun `a tip amount that is zero or not a number is refused but takes its date`() {
        assertEquals(Outcome.Take(Local(9, 300)), PreferencesMerge.decide(PreferencesMerge.TIP, file(item("tip_amount", "300", "0")), Local(9, 100), now))
        assertEquals(Outcome.Nothing, PreferencesMerge.decide(PreferencesMerge.TIP, file(item("tip_amount", "300", "abc")), null, now))
    }

    @Test
    fun `a garbage future date is capped at a day ahead`() {
        val out = PreferencesMerge.decide(PreferencesMerge.TIP, file(item("tip_amount", "99999999999", "0")), Local(9, 100), now)
        assertEquals(Outcome.Take(Local(9, now + 86_400)), out)
    }

    @Test
    fun `confirmation is taken from the file only when stricter`() {
        val spec = PreferencesMerge.CONFIRMATION
        val stricter = file(item("peel.payment_confirmation_msat", "200", "50000"))
        assertEquals(Outcome.Take(Local(50_000, 200)), PreferencesMerge.decide(spec, stricter, Local(100_000, 100), now))
        val looser = file(item("peel.payment_confirmation_msat", "200", "1000000"))
        // Kept here, but dated after the file so a later change beats it.
        assertEquals(Outcome.Take(Local(100_000, 200)), PreferencesMerge.decide(spec, looser, Local(100_000, 100), now))
    }

    @Test
    fun `an unset confirmation is compared against the default`() {
        val spec = PreferencesMerge.CONFIRMATION
        assertEquals(Outcome.Nothing, PreferencesMerge.decide(spec, file(item("peel.payment_confirmation_msat", "200", "10000000")), null, now))
        assertEquals(Outcome.Take(Local(0, 200)), PreferencesMerge.decide(spec, file(item("peel.payment_confirmation_msat", "200", "0")), null, now))
    }

    @Test
    fun `a local confirmation chosen later still goes up even when looser`() {
        val out = PreferencesMerge.decide(
            PreferencesMerge.CONFIRMATION, file(item("peel.payment_confirmation_msat", "100", "0")), Local(1_000_000, 300), now,
        )
        assertEquals(Outcome.Upload(Local(1_000_000, 300)), out)
    }

    @Test
    fun `auto download media reads true and false in any case and writes lowercase`() {
        val spec = PreferencesMerge.AUTO_DOWNLOAD_MEDIA
        assertEquals("peel.auto_download_media", spec.key)
        assertEquals(1L, spec.parse("true"))
        assertEquals(0L, spec.parse("FALSE"))
        assertEquals(null, spec.parse("maybe"))
        assertEquals("true", spec.format(1))
        assertEquals("false", spec.format(0))
        assertTrue(PreferencesMerge.SPECS.contains(spec))
    }

    @Test
    fun `auto download media off is taken from a newer file and uploaded when newer here`() {
        val spec = PreferencesMerge.AUTO_DOWNLOAD_MEDIA
        val off = file(item("peel.auto_download_media", "200", "false"))
        assertEquals(Outcome.Take(Local(0, 200)), PreferencesMerge.decide(spec, off, Local(1, 100), now))
        assertEquals(Outcome.Take(Local(0, 200)), PreferencesMerge.decide(spec, off, null, now))
        assertEquals(Outcome.Upload(Local(1, 300)), PreferencesMerge.decide(spec, off, Local(1, 300), now))
    }

    @Test
    fun `auto download media absent from the file leaves the default on and uploads only a choice`() {
        val spec = PreferencesMerge.AUTO_DOWNLOAD_MEDIA
        assertEquals(Outcome.Nothing, PreferencesMerge.decide(spec, file(), null, now))
        assertEquals(Outcome.Upload(Local(0, 50)), PreferencesMerge.decide(spec, file(), Local(0, 50), now))
    }

    @Test
    fun `auto download media is written as a string and other items survive`() {
        val f = PreferencesFile.parse("""{"items":[{"key":"chat_color","identifier":"x","date":"1","value":"#fff"}]}""")
        f.setEntry("peel.auto_download_media", "false", 7)
        val text = f.text()
        assertTrue(text.contains("chat_color"))
        assertEquals("false" to 7L, PreferencesFile.parse(text).entry("peel.auto_download_media"))
        assertTrue(text.contains("\"value\":\"false\""))
    }

    @Test
    fun `a date chosen here is always after the one it replaces`() {
        assertEquals(101L, PreferencesMerge.nextDate(100, 100))
        assertEquals(500L, PreferencesMerge.nextDate(500, 100))
    }

    @Test
    fun `setEntry keeps other items and top-level fields`() {
        val f = PreferencesFile.parse(
            """{"extra":{"a":1},"items":[{"key":"chat_color","identifier":"x","date":"1","value":"#fff"}]}""",
        )
        f.setEntry("peel.payment_confirmation_msat", "0", 5)
        val text = f.text()
        assertTrue(text.contains("\"extra\""))
        assertTrue(text.contains("chat_color"))
        assertEquals("0" to 5L, PreferencesFile.parse(text).entry("peel.payment_confirmation_msat"))
    }

    @Test
    fun `a file the official apps could not read is refused`() {
        assertThrows(IllegalArgumentException::class.java) { PreferencesFile.parse("""{"items":[{"key":"k","identifier":"0","date":5,"value":"1"}]}""") }
        assertThrows(IllegalArgumentException::class.java) { PreferencesFile.parse("""{"nope":1}""") }
    }
}
