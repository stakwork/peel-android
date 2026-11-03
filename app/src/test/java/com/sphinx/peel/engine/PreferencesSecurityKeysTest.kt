package com.sphinx.peel.engine

import com.sphinx.peel.engine.PreferencesMerge.Local
import com.sphinx.peel.engine.PreferencesMerge.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// `pin_timeout` and `biometric_enabled`, with PeelCore's key names and value reading.
@RunWith(RobolectricTestRunner::class)
class PreferencesSecurityKeysTest {
    private val now = 1_800_000_000L
    private fun file(key: String, date: String, value: String) =
        PreferencesFile.parse("""{"items":[{"key":"$key","identifier":"0","date":"$date","value":"$value"}]}""")

    @Test fun `key names match iOS`() {
        assertEquals("pin_timeout", PreferencesMerge.PIN_TIMEOUT.key)
        assertEquals("biometric_enabled", PreferencesMerge.BIOMETRIC.key)
    }

    @Test fun `pin_timeout reads whole hours, clamps, refuses the rest`() {
        val p = PreferencesMerge.PIN_TIMEOUT
        assertEquals(12L, p.parse("12"))
        assertEquals(25L, p.parse("99"))
        assertEquals(0L, p.parse("-4"))
        assertNull(p.parse("soon"))
        assertNull(p.parse("1.5"))
        assertNull(p.parse("99999999999999999999"))
    }

    @Test fun `newest pin_timeout wins both ways`() {
        assertEquals(
            Outcome.Take(Local(4, 200)),
            PreferencesMerge.decide(PreferencesMerge.PIN_TIMEOUT, file("pin_timeout", "200", "4"), Local(24, 100), now),
        )
        assertEquals(
            Outcome.Upload(Local(24, 300)),
            PreferencesMerge.decide(PreferencesMerge.PIN_TIMEOUT, file("pin_timeout", "200", "4"), Local(24, 300), now),
        )
    }

    @Test fun `biometric_enabled reads true and false in any case and writes lower-case words`() {
        val b = PreferencesMerge.BIOMETRIC
        assertEquals(1L, b.parse("true")); assertEquals(1L, b.parse("TRUE"))
        assertEquals(0L, b.parse("False"))
        assertNull(b.parse("1")); assertNull(b.parse("yes"))
        assertEquals("true", b.format(1)); assertEquals("false", b.format(0))
    }

    @Test fun `biometric_enabled from the file is taken as a value (a wish) when newer`() {
        assertEquals(
            Outcome.Take(Local(0, 200)),
            PreferencesMerge.decide(PreferencesMerge.BIOMETRIC, file("biometric_enabled", "200", "false"), Local(1, 100), now),
        )
    }

    @Test fun `a local choice never made is not uploaded`() {
        val none = PreferencesFile.empty()
        assertEquals(Outcome.Nothing, PreferencesMerge.decide(PreferencesMerge.PIN_TIMEOUT, none, Local(0, 0), now))
    }
}
