package com.sphinx.peel.engine

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// Voice Mode (docs/phases/55-voice-mode.md): the setting, the routing and the linen's flecks.
@RunWith(RobolectricTestRunner::class)
class VoiceModeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun keyIsVoiceMode() = assertEquals("voiceMode", VoiceModePrefs.KEY)

    @Test fun offByDefault() {
        context.getSharedPreferences("peel.voicemode", Context.MODE_PRIVATE).edit().clear().commit()
        assertFalse(VoiceModePrefs.of(context).enabled)
    }

    @Test fun persists() {
        VoiceModePrefs.of(context).enabled = true
        assertTrue(VoiceModePrefs.of(context).enabled)
        assertTrue(context.getSharedPreferences("peel.voicemode", Context.MODE_PRIVATE).getBoolean("voiceMode", false))
        VoiceModePrefs.of(context).enabled = false
        assertFalse(VoiceModePrefs.of(context).enabled)
    }

    @Test fun routing() {
        assertEquals(VoiceModeRoute.VOICE, VoiceModeRoute.initial(voiceModeOn = true, showedStandard = false))
        assertEquals(VoiceModeRoute.STANDARD, VoiceModeRoute.initial(voiceModeOn = false, showedStandard = false))
        assertEquals(VoiceModeRoute.STANDARD, VoiceModeRoute.initial(voiceModeOn = true, showedStandard = true))
        assertEquals(VoiceModeRoute.STANDARD, VoiceModeRoute.initial(voiceModeOn = false, showedStandard = true))
    }

    @Test fun fleckDeterministicPerSeed() {
        assertEquals(Linen.flecks(7L, 50), Linen.flecks(7L, 50))
        assertNotEquals(Linen.flecks(7L, 50), Linen.flecks(8L, 50))
        assertEquals(90, Linen.flecks().size)
        assertTrue(Linen.flecks(1L, 200).all { it.x in 0f..1f && it.y in 0f..1f })
        assertTrue(Linen.flecks(1L, -3).isEmpty())
    }

    @Test fun textIsTrimmedOrNothing() {
        assertEquals("hi there", VoiceModeText.clean("  hi there \n"))
        assertNull(VoiceModeText.clean("  \n"))
    }

    @Test fun `interaction mode default and key`() {
        assertEquals("voiceModeInteraction", VoiceModeInteraction.KEY)
        assertEquals(VoiceModeInteraction.SPEECH_IN_SPEECH_OUT, VoiceModeInteraction.DEFAULT)
    }

    @Test fun `interaction mode usesMic`() {
        assertTrue(VoiceModeInteraction.SPEECH_IN_SPEECH_OUT.usesMic)
        assertFalse(VoiceModeInteraction.TEXT_IN_TEXT_OUT.usesMic)
        assertTrue(VoiceModeInteraction.SPEECH_IN_TEXT_OUT.usesMic)
        assertFalse(VoiceModeInteraction.TEXT_IN_SPEECH_OUT.usesMic)
    }

    @Test fun `interaction mode speaksReply`() {
        assertTrue(VoiceModeInteraction.SPEECH_IN_SPEECH_OUT.speaksReply)
        assertFalse(VoiceModeInteraction.TEXT_IN_TEXT_OUT.speaksReply)
        assertFalse(VoiceModeInteraction.SPEECH_IN_TEXT_OUT.speaksReply)
        assertTrue(VoiceModeInteraction.TEXT_IN_SPEECH_OUT.speaksReply)
    }

    @Test fun `subtitles default and key`() {
        assertEquals("voiceModeSubtitles", VoiceModeSubtitles.KEY)
        assertEquals(false, VoiceModeSubtitles.DEFAULT)
    }

    @Test fun `interaction mode round-trips through wire`() {
        for (mode in VoiceModeInteraction.entries) assertEquals(mode, VoiceModeInteraction.of(mode.wire))
        assertEquals(VoiceModeInteraction.DEFAULT, VoiceModeInteraction.of("nonsense"))
    }

    @Test fun `interaction and subtitles persist`() {
        val prefs = VoiceModePrefs.of(context)
        prefs.interaction = VoiceModeInteraction.TEXT_IN_SPEECH_OUT
        prefs.subtitles = true
        assertEquals(VoiceModeInteraction.TEXT_IN_SPEECH_OUT, VoiceModePrefs.of(context).interaction)
        assertTrue(VoiceModePrefs.of(context).subtitles)
        prefs.interaction = VoiceModeInteraction.DEFAULT
        prefs.subtitles = false
    }
}
