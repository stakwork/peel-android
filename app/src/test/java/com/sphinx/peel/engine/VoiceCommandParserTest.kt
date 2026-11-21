package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun command(cmd: VoiceCommand, textBefore: String = "") = VoiceCommandMatch.Command(cmd, textBefore)

/// Voice mode's phrases (docs/phases/55-voice-mode.md): every command is introduced by the wake word "peel" and ends the
/// utterance; without it all is dictation. Ported from peel's `VoiceCommandParserTests.swift`.
class VoiceCommandParserTest {
    private val parser = VoiceCommandParser()
    private val everything = VoiceCommand.entries.toSet()

    private fun parse(text: String, accepted: Set<VoiceCommand> = everything) = parser.parse(text, accepted)

    @Test fun `every prefixed phrase is recognised as the whole utterance`() {
        for ((cmd, phrases) in VoiceCommandPhrases.ENGLISH.phrases) {
            for (phrase in phrases) {
                val said = VoiceCommandPhrases.ENGLISH.spoken(phrase, cmd)
                assertEquals(said, command(cmd), parse(said))
            }
        }
    }

    @Test fun `the requested phrases exist`() {
        assertEquals(command(VoiceCommand.SEND), parse("peel send it"))
        assertEquals(command(VoiceCommand.SEND), parse("peel send message"))
        assertEquals(command(VoiceCommand.SEND), parse("peel over"))
        assertEquals(command(VoiceCommand.SEND, "add a login page"), parse("add a login page Peel sent"))
        assertEquals(command(VoiceCommand.SEND), parse("peel sent it"))
        assertEquals(command(VoiceCommand.STOP_SPEAKING), parse("peel stop"))
        assertEquals(command(VoiceCommand.DISCARD), parse("peel never mind"))
        assertEquals(command(VoiceCommand.DISCARD), parse("peel cancel message"))
        assertEquals(command(VoiceCommand.REPEAT_REPLY), parse("peel say that again"))
        assertEquals(command(VoiceCommand.READ_ALL), parse("peel read everything"))
        assertEquals(command(VoiceCommand.STOP_AGENT), parse("peel cancel the agent"))
        assertEquals(command(VoiceCommand.DEACTIVATE), parse("peel goodbye"))
        assertEquals(command(VoiceCommand.DEACTIVATE), parse("peel exit voice mode"))
        assertEquals(command(VoiceCommand.DEACTIVATE), parse("peel end voice session"))
    }

    @Test fun `bare commands are dictation`() {
        for (bare in listOf(
            "send", "send it", "stop", "over", "scratch that", "mute", "unmute", "repeat that", "skip",
            "read it all", "stop the agent", "goodbye", "end voice mode", "never mind",
        )) {
            assertEquals(bare, VoiceCommandMatch.None, parse(bare))
        }
    }

    @Test fun `trailing command keeps the text before`() {
        assertEquals(command(VoiceCommand.SEND, "add a login page to the backlog"), parse("add a login page to the backlog peel send"))
        assertEquals(command(VoiceCommand.SEND, "Start the nightly job"), parse("Start the nightly job, Peel, send it."))
        assertEquals(command(VoiceCommand.DISCARD, "no wait"), parse("no wait peel scratch that"))
        assertEquals(command(VoiceCommand.MUTE, "remember the milk"), parse("remember the milk peel mute"))
    }

    @Test fun `a command in the middle does not fire`() {
        assertEquals(VoiceCommandMatch.None, parse("peel send it to the team tomorrow"))
        assertEquals(VoiceCommandMatch.None, parse("tell peel to stop the build and then continue"))
        assertEquals(VoiceCommandMatch.None, parse("peel mute the other channel please"))
    }

    @Test fun `prose with peel is dictation`() {
        assertEquals(VoiceCommandMatch.None, parse("peel the potatoes and then send"))
        assertEquals(VoiceCommandMatch.None, parse("I need to peel an orange"))
        assertEquals(VoiceCommandMatch.None, parse("the peel"))
        assertEquals(VoiceCommandMatch.None, parse("peel"))
    }

    @Test fun `longer phrase wins`() {
        assertEquals(command(VoiceCommand.STOP_AGENT), parse("peel stop the agent"))
        assertEquals(command(VoiceCommand.DEACTIVATE), parse("peel stop voice mode"))
        assertEquals(command(VoiceCommand.SEND), parse("peel send it"))
    }

    @Test fun `wake word variants count only before a command`() {
        assertEquals(command(VoiceCommand.SEND), parse("pill send"))
        assertEquals(command(VoiceCommand.SEND, "add a screen"), parse("add a screen peal send"))
        assertEquals(command(VoiceCommand.STOP_SPEAKING), parse("feel stop"))
        assertEquals(VoiceCommandMatch.None, parse("pill"))
        assertEquals(VoiceCommandMatch.None, parse("I feel fine"))
        assertEquals(VoiceCommandMatch.None, parse("a pill for the cat"))
    }

    @Test fun `case and punctuation do not matter`() {
        assertEquals(command(VoiceCommand.STOP_AGENT), parse("  PEEL, STOP THE AGENT. "))
        assertEquals(command(VoiceCommand.DEACTIVATE), parse("Peel: end   voice-mode"))
        assertEquals(command(VoiceCommand.SEND, "Add a login screen."), parse("Add a login screen. Peel, send message."))
    }

    @Test fun `activation needs no prefix`() {
        assertEquals(command(VoiceCommand.ACTIVATE), parse("  Hey, Peel! "))
        assertEquals(command(VoiceCommand.ACTIVATE), parse("voice mode"))
        assertEquals(command(VoiceCommand.ACTIVATE), parse("start voice mode"))
        assertEquals(command(VoiceCommand.ACTIVATE), parse("peel start voice mode"))
        // Whole utterance only, unless the wake listener makes it trailing.
        assertEquals(VoiceCommandMatch.None, parse("tell me about voice mode"))
        val table = VoiceCommandPhrases.ENGLISH.copy(trailing = setOf(VoiceCommand.ACTIVATE))
        assertEquals(command(VoiceCommand.ACTIVATE, "ok"), VoiceCommandParser(table).parse("ok hey peel", setOf(VoiceCommand.ACTIVATE)))
    }

    @Test fun `dictating context`() {
        val accepts = VoiceContext.DICTATING.accepts
        assertEquals(command(VoiceCommand.SEND), parse("peel send", accepts))
        assertEquals(command(VoiceCommand.MUTE), parse("peel mute", accepts))
        assertEquals(VoiceCommandMatch.None, parse("hey peel", accepts))
        assertEquals(VoiceCommandMatch.None, parse("peel stop", accepts))
        assertEquals(VoiceCommandMatch.None, parse("peel unmute", accepts))
        assertEquals(VoiceCommandMatch.None, parse("peel skip", accepts))
    }

    @Test fun `speaking context`() {
        val accepts = VoiceContext.SPEAKING.accepts
        val cases = listOf(
            "peel stop" to VoiceCommand.STOP_SPEAKING, "peel repeat" to VoiceCommand.REPEAT_REPLY, "peel skip" to VoiceCommand.SKIP,
            "peel read it all" to VoiceCommand.READ_ALL, "peel stop the agent" to VoiceCommand.STOP_AGENT, "peel mute" to VoiceCommand.MUTE,
            "peel end voice mode" to VoiceCommand.DEACTIVATE,
        )
        for ((said, cmd) in cases) assertEquals(said, command(cmd), parse(said, accepts))
        assertEquals(VoiceCommandMatch.None, parse("peel send", accepts))
        assertEquals(VoiceCommandMatch.None, parse("peel scratch that", accepts))
        assertEquals(VoiceCommandMatch.None, parse("peel unmute", accepts))
    }

    @Test fun `muted context hears only unmute and end`() {
        val accepts = VoiceContext.MUTED.accepts
        assertEquals(setOf(VoiceCommand.UNMUTE, VoiceCommand.DEACTIVATE), accepts)
        assertEquals(command(VoiceCommand.UNMUTE), parse("peel unmute", accepts))
        assertEquals(command(VoiceCommand.UNMUTE, "some chatter in the car"), parse("some chatter in the car peel unmute", accepts))
        assertEquals(command(VoiceCommand.DEACTIVATE), parse("peel end voice mode", accepts))
        assertEquals(VoiceCommandMatch.None, parse("peel send", accepts))
        assertEquals(VoiceCommandMatch.None, parse("peel stop", accepts))
        assertEquals(VoiceCommandMatch.None, parse("peel mute", accepts))
    }

    @Test fun `waiting context`() {
        val accepts = VoiceContext.WAITING.accepts
        assertEquals(command(VoiceCommand.STOP_AGENT), parse("peel stop the agent", accepts))
        assertEquals(VoiceCommandMatch.None, parse("peel send", accepts))
        assertEquals(VoiceCommandMatch.None, parse("peel stop", accepts))
    }

    @Test fun `contextual strings are prefixed`() {
        val all = VoiceCommandPhrases.ENGLISH.all
        assertTrue(all.contains("peel send"))
        assertTrue(all.contains("peel end voice mode"))
        assertTrue(all.contains("peel unmute"))
        assertTrue(all.contains("hey peel"))
        assertFalse(all.contains("send"))
    }

    @Test fun `empty and punctuation only find nothing`() {
        assertEquals(VoiceCommandMatch.None, parse(""))
        assertEquals(VoiceCommandMatch.None, parse(" ... "))
    }

    @Test fun `normalize lowercases and collapses`() {
        assertEquals("send it", VoiceCommandParser.normalize("  Send,   IT!! "))
    }
}
