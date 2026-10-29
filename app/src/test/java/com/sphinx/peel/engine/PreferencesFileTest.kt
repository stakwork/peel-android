package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PreferencesFileTest {
    @Test
    fun `a fresh file carries no tip_amount`() {
        assertNull(PreferencesFile.empty().tipAmount())
    }

    @Test
    fun `reads tip_amount as whole sats, not msat`() {
        val file = PreferencesFile.parse(
            """{"items":[{"key":"tip_amount","identifier":"0","date":"1700000000","value":"7"}]}""",
        )
        assertEquals(7L to 1_700_000_000L, file.tipAmount())
    }

    @Test
    fun `an item for a different key or identifier is ignored`() {
        val file = PreferencesFile.parse(
            """{"items":[
                {"key":"tip_amount","identifier":"some-chat","date":"1","value":"3"},
                {"key":"chat_color","identifier":"0","date":"1","value":"#ffffff"}
            ]}""",
        )
        assertNull(file.tipAmount())
    }

    @Test
    fun `a value of zero does not read as a tip amount`() {
        val file = PreferencesFile.parse("""{"items":[{"key":"tip_amount","identifier":"0","date":"1","value":"0"}]}""")
        assertNull(file.tipAmount())
    }

    @Test
    fun `the last copy of a duplicated item wins, as the official apps apply items in order`() {
        val file = PreferencesFile.parse(
            """{"items":[
                {"key":"tip_amount","identifier":"0","date":"1","value":"3"},
                {"key":"tip_amount","identifier":"0","date":"2","value":"9"}
            ]}""",
        )
        assertEquals(9L to 2L, file.tipAmount())
    }

    @Test
    fun `setTipAmount replaces every existing copy with exactly one`() {
        val file = PreferencesFile.parse(
            """{"items":[
                {"key":"tip_amount","identifier":"0","date":"1","value":"3"},
                {"key":"tip_amount","identifier":"0","date":"2","value":"9"}
            ]}""",
        )
        file.setTipAmount(10, 3)
        assertEquals(10L to 3L, file.tipAmount())
        assertEquals(1, file.text().split("tip_amount").size - 1)
    }

    @Test
    fun `every other item and top-level field round-trips untouched`() {
        val text = """{"items":[{"key":"chat_color","identifier":"abc-color","date":"5","value":"#112233"}],"other_field":"kept"}"""
        val file = PreferencesFile.parse(text)
        file.setTipAmount(10, 1)
        val out = file.text()
        assertTrue(out.contains("\"other_field\":\"kept\""))
        assertTrue(out.contains("chat_color"))
        assertTrue(out.contains("#112233"))
    }

    @Test
    fun `tip_amount value may also be a raw JSON number, as the official apps also read`() {
        val file = PreferencesFile.parse("""{"items":[{"key":"tip_amount","identifier":"0","date":"4","value":12}]}""")
        assertEquals(12L to 4L, file.tipAmount())
    }
}
