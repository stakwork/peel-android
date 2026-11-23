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

    @Test fun `auto-send is off by default with a five second pause`() {
        context.getSharedPreferences("peel.voicemode", Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = VoiceModePrefs.of(context)
        assertFalse(prefs.autoSend)
        assertEquals(5.0, prefs.autoSendSeconds, 0.0)
        assertEquals(2.0..15.0, VoiceAutoSend.RANGE)
    }

    @Test fun `auto-send persists`() {
        val prefs = VoiceModePrefs.of(context)
        prefs.autoSend = true
        prefs.autoSendSeconds = 8.0
        assertTrue(VoiceModePrefs.of(context).autoSend)
        assertEquals(8.0, VoiceModePrefs.of(context).autoSendSeconds, 0.0)
        prefs.autoSend = false
        prefs.autoSendSeconds = VoiceAutoSend.DEFAULT_SECONDS
    }

    @Test fun `auto-send seconds are clamped to the range on read`() {
        val raw = context.getSharedPreferences("peel.voicemode", Context.MODE_PRIVATE)
        raw.edit().putFloat(VoiceAutoSend.SECONDS_KEY, 99f).commit()
        assertEquals(15.0, VoiceModePrefs.of(context).autoSendSeconds, 0.0)
        raw.edit().putFloat(VoiceAutoSend.SECONDS_KEY, 0.5f).commit()
        assertEquals(2.0, VoiceModePrefs.of(context).autoSendSeconds, 0.0)
        raw.edit().remove(VoiceAutoSend.SECONDS_KEY).commit()
    }

    @Test fun `old unversioned auto-send keys are not read`() {
        // What a first version might have stored: on, 2 s, under the old unversioned keys.
        val raw = context.getSharedPreferences("peel.voicemode", Context.MODE_PRIVATE)
        raw.edit().putBoolean("voice.autoSend", true).putFloat("voice.autoSendSeconds", 2f).commit()
        assertFalse(VoiceModePrefs.of(context).autoSend)
        assertEquals(5.0, VoiceModePrefs.of(context).autoSendSeconds, 0.0)
        raw.edit().remove("voice.autoSend").remove("voice.autoSendSeconds").commit()
    }

    @Test fun `enabled fires its listener when another instance changes it`() {
        context.getSharedPreferences("peel.voicemode", Context.MODE_PRIVATE).edit().clear().commit()
        val watcher = VoiceModePrefs.of(context)
        var seen: Boolean? = null
        val listener = watcher.onEnabledChanged { seen = it }
        VoiceModePrefs.of(context).enabled = true
        assertEquals(true, seen)
        watcher.removeListener(listener)
    }
}

/// `VoiceModeReply` (docs/phases/55-voice-mode.md, "What the reply actually is"): the agent's texts since the
/// person's own newest message. Ported from peel's `replyIsWhatTheAgentSaidSinceYourNewestMessage`/`replySkipsWhatIsNoTurn`.
@RunWith(RobolectricTestRunner::class)
class VoiceModeReplyTest {
    private val agent = "agent"

    private fun row(
        n: Int, fromMe: Boolean = false, content: String? = "m$n", type: Int = TEXT_MESSAGE_TYPE,
        chat: String = agent, status: String? = null,
    ) = com.sphinx.peel.storage.MessageRow(
        uuid = "u$n", serverIndex = n.toLong(), type = type, chat = chat, fromMe = fromMe, sender = null,
        body = content?.let { org.json.JSONObject().put("content", it).toString() }, msat = null,
        timestamp = n * 1_000L, tag = null, paymentHash = null, id = n + 1L, status = status,
    )

    @Test fun `the reply is the agent's texts since your newest message`() {
        val asked = row(1, fromMe = true, content = "What did I miss?")
        val answer = row(2, content = "Three chats.")
        val askedAgain = row(3, fromMe = true, content = "Read them")
        val part1 = row(4, content = "Kate says hi.")
        val part2 = row(5, content = "Bob asks about Friday.")
        assertEquals(listOf(answer), VoiceModeReply.latest(listOf(asked, answer), agent))
        // Your newer message has no answer yet.
        assertTrue(VoiceModeReply.latest(listOf(asked, answer, askedAgain), agent).isEmpty())
        assertEquals(listOf(part1, part2), VoiceModeReply.latest(listOf(asked, answer, askedAgain, part1, part2), agent))
        assertEquals("Kate says hi.\n\nBob asks about Friday.", VoiceModeReply.text(listOf(part1, part2)))
        assertNull(VoiceModeReply.text(emptyList()))
        // Another chat's messages are not the agent's.
        assertTrue(VoiceModeReply.latest(listOf(asked, answer), "someone-else").isEmpty())
    }

    @Test fun `a message that never went is no turn, so the answer before it is still the reply`() {
        val asked = row(1, fromMe = true, content = "Hi")
        val answer = row(2, content = "Hello.")
        val failed = row(3, fromMe = true, content = "Again", status = "FAILED")
        assertEquals(listOf(answer), VoiceModeReply.latest(listOf(asked, answer, failed), agent))
        // A picture you sent is a turn of its own, waiting for its answer.
        val picture = row(4, fromMe = true, content = null, type = ATTACHMENT_MESSAGE_TYPE)
        assertTrue(VoiceModeReply.latest(listOf(asked, answer, picture), agent).isEmpty())
    }
}
