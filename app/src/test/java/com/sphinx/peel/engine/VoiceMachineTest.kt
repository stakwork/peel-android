package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun VoiceMachine.started(): VoiceMachine = this.also { apply(VoiceEvent.Start) }
private fun send(text: String) = listOf(VoiceAction.Send(text))
private fun plain(vararg effects: VoiceEffect) = effects.map { VoiceAction.Plain(it) }

/// Voice mode's transitions (`VoiceMachine`), with no audio and no engine. Ported from peel's `VoiceModeStateTests.swift`
/// (the `VoiceSettings`/`VoiceDefaults` cases there belong to the auto-send item, not this one).
class VoiceMachineTest {
    @Test fun `start listens and ends on stop`() {
        val machine = VoiceMachine()
        assertEquals(plain(VoiceEffect.BEGIN), machine.apply(VoiceEvent.Start))
        assertEquals(VoicePhase.Listening, machine.phase)
        assertEquals(emptyList<VoiceAction>(), machine.apply(VoiceEvent.Start))
        assertEquals(plain(VoiceEffect.END), machine.apply(VoiceEvent.Stop))
        assertEquals(VoicePhase.Idle, machine.phase)
        assertEquals(emptyList<VoiceAction>(), machine.apply(VoiceEvent.Stop))
    }

    @Test fun `transcript makes a draft and silence sends it`() {
        val machine = VoiceMachine().started()
        machine.apply(VoiceEvent.Transcript("add a screen"))
        assertEquals(VoicePhase.Hearing, machine.phase)
        assertEquals(send("add a screen"), machine.apply(VoiceEvent.Silence))
        assertEquals(VoicePhase.Sending, machine.phase)
        assertEquals(emptyList<VoiceAction>(), machine.apply(VoiceEvent.SendFinished(sent = true)))
        assertEquals(VoicePhase.Waiting, machine.phase)
        assertEquals("", machine.draft)
    }

    @Test fun `send command needs a draft`() {
        val machine = VoiceMachine().started()
        assertEquals(emptyList<VoiceAction>(), machine.apply(VoiceEvent.Command(VoiceCommand.SEND)))
        assertEquals(VoicePhase.Listening, machine.phase)
        machine.apply(VoiceEvent.Transcript("hello"))
        assertEquals(send("hello"), machine.apply(VoiceEvent.Command(VoiceCommand.SEND)))
    }

    @Test fun `a failed send keeps the draft`() {
        val machine = VoiceMachine().started()
        machine.apply(VoiceEvent.Transcript("hello"))
        machine.apply(VoiceEvent.Command(VoiceCommand.SEND))
        machine.apply(VoiceEvent.SendFinished(sent = false))
        assertEquals(VoicePhase.Hearing, machine.phase)
        assertEquals("hello", machine.draft)
    }

    @Test fun `discard clears the draft`() {
        val machine = VoiceMachine().started()
        machine.apply(VoiceEvent.Transcript("hello"))
        assertEquals(plain(VoiceEffect.CLEAR_DRAFT), machine.apply(VoiceEvent.Command(VoiceCommand.DISCARD)))
        assertEquals(VoicePhase.Listening, machine.phase)
        assertEquals("", machine.draft)
    }

    @Test fun `the next message can be said while waiting`() {
        val machine = VoiceMachine().started()
        machine.apply(VoiceEvent.Transcript("hello"))
        machine.apply(VoiceEvent.Command(VoiceCommand.SEND))
        machine.apply(VoiceEvent.SendFinished(sent = true))
        machine.apply(VoiceEvent.Transcript(" "))
        assertEquals(VoicePhase.Waiting, machine.phase)
        assertEquals("", machine.draft)
        machine.apply(VoiceEvent.Transcript("and another thing"))
        assertEquals(VoicePhase.Hearing, machine.phase)
        assertEquals("and another thing", machine.draft)
        assertEquals(send("and another thing"), machine.apply(VoiceEvent.Command(VoiceCommand.SEND)))
    }

    @Test fun `transcripts are ignored while sending and speaking`() {
        val machine = VoiceMachine().started()
        machine.apply(VoiceEvent.Transcript("hello"))
        machine.apply(VoiceEvent.Command(VoiceCommand.SEND))
        machine.apply(VoiceEvent.Transcript("noise"))
        assertEquals(VoicePhase.Sending, machine.phase)
        assertEquals("hello", machine.draft)
        machine.apply(VoiceEvent.SendFinished(sent = true))
        machine.apply(VoiceEvent.SpeechStarted)
        machine.apply(VoiceEvent.Transcript("the reply itself"))
        assertEquals(VoicePhase.Speaking, machine.phase)
        assertEquals("", machine.draft)
    }

    @Test fun `a reply with nothing to read listens again`() {
        val machine = VoiceMachine().started()
        machine.apply(VoiceEvent.Transcript("hello"))
        machine.apply(VoiceEvent.Command(VoiceCommand.SEND))
        machine.apply(VoiceEvent.SendFinished(sent = true))
        machine.apply(VoiceEvent.ReplyReady(hasReply = false))
        assertEquals(VoicePhase.Listening, machine.phase)
    }

    @Test fun `speaking controls`() {
        val machine = VoiceMachine().started()
        machine.apply(VoiceEvent.SpeechStarted)
        assertEquals(plain(VoiceEffect.SKIP_SPEECH), machine.apply(VoiceEvent.Command(VoiceCommand.SKIP)))
        assertEquals(VoicePhase.Speaking, machine.phase)
        assertEquals(plain(VoiceEffect.SPEAK_LAST_REPLY), machine.apply(VoiceEvent.Command(VoiceCommand.REPEAT_REPLY)))
        assertEquals(plain(VoiceEffect.SPEAK_FULL_REPLY), machine.apply(VoiceEvent.Command(VoiceCommand.READ_ALL)))
        assertEquals(plain(VoiceEffect.STOP_SPEECH, VoiceEffect.CLEAR_DRAFT), machine.apply(VoiceEvent.Command(VoiceCommand.STOP_SPEAKING)))
        assertEquals(VoicePhase.Listening, machine.phase)
    }

    @Test fun `speech ending returns to listening`() {
        val machine = VoiceMachine().started()
        machine.apply(VoiceEvent.SpeechStarted)
        assertEquals(plain(VoiceEffect.CLEAR_DRAFT), machine.apply(VoiceEvent.SpeechEnded))
        assertEquals(VoicePhase.Listening, machine.phase)
    }

    @Test fun `await reply waits after speech in between`() {
        val machine = VoiceMachine().started()
        machine.apply(VoiceEvent.SpeechStarted)
        machine.apply(VoiceEvent.SpeechEnded)
        machine.apply(VoiceEvent.AwaitReply)
        assertEquals(VoicePhase.Waiting, machine.phase)
    }

    @Test fun `stop agent works while waiting`() {
        val machine = VoiceMachine().started()
        machine.apply(VoiceEvent.Transcript("go"))
        machine.apply(VoiceEvent.Command(VoiceCommand.SEND))
        machine.apply(VoiceEvent.SendFinished(sent = true))
        assertEquals(plain(VoiceEffect.STOP_AGENT), machine.apply(VoiceEvent.Command(VoiceCommand.STOP_AGENT)))
        assertEquals(VoicePhase.Waiting, machine.phase)
    }

    @Test fun `deactivate and failure end`() {
        val machine = VoiceMachine().started()
        assertEquals(plain(VoiceEffect.END), machine.apply(VoiceEvent.Command(VoiceCommand.DEACTIVATE)))
        assertEquals(VoicePhase.Idle, machine.phase)
        machine.apply(VoiceEvent.Start)
        assertEquals(plain(VoiceEffect.END), machine.apply(VoiceEvent.Failure("no microphone")))
        assertEquals(VoicePhase.Failed("no microphone"), machine.phase)
        assertEquals(plain(VoiceEffect.BEGIN), machine.apply(VoiceEvent.Start))
    }

    @Test fun `mute freezes the draft and unmute resumes`() {
        val machine = VoiceMachine().started()
        machine.apply(VoiceEvent.Transcript("hello"))
        assertEquals(plain(VoiceEffect.MUTE), machine.apply(VoiceEvent.Command(VoiceCommand.MUTE)))
        assertTrue(machine.isMuted)
        assertEquals(VoiceContext.MUTED, machine.context)
        assertEquals(emptyList<VoiceAction>(), machine.apply(VoiceEvent.Transcript("chatter in the car")))
        assertEquals("hello", machine.draft)
        assertEquals(emptyList<VoiceAction>(), machine.apply(VoiceEvent.Silence))
        assertEquals(emptyList<VoiceAction>(), machine.apply(VoiceEvent.Command(VoiceCommand.SEND)))
        assertEquals(emptyList<VoiceAction>(), machine.apply(VoiceEvent.Command(VoiceCommand.DISCARD)))
        assertEquals(VoicePhase.Hearing, machine.phase)
        assertEquals("hello", machine.draft)
        assertEquals(emptyList<VoiceAction>(), machine.apply(VoiceEvent.Command(VoiceCommand.MUTE)))
        assertEquals(plain(VoiceEffect.UNMUTE), machine.apply(VoiceEvent.Command(VoiceCommand.UNMUTE)))
        assertFalse(machine.isMuted)
        assertEquals(VoiceContext.DICTATING, machine.context)
        machine.apply(VoiceEvent.Transcript("hello world"))
        assertEquals("hello world", machine.draft)
        assertEquals(send("hello world"), machine.apply(VoiceEvent.Command(VoiceCommand.SEND)))
    }

    @Test fun `unmute when not muted does nothing`() {
        val machine = VoiceMachine().started()
        assertEquals(emptyList<VoiceAction>(), machine.apply(VoiceEvent.Command(VoiceCommand.UNMUTE)))
        assertFalse(machine.isMuted)
        val idle = VoiceMachine()
        assertEquals(emptyList<VoiceAction>(), idle.apply(VoiceEvent.Command(VoiceCommand.MUTE)))
        assertFalse(idle.isMuted)
    }

    @Test fun `mute while speaking returns to speaking`() {
        val machine = VoiceMachine().started()
        machine.apply(VoiceEvent.SpeechStarted)
        assertEquals(plain(VoiceEffect.MUTE), machine.apply(VoiceEvent.Command(VoiceCommand.MUTE)))
        assertEquals(VoicePhase.Speaking, machine.phase)
        assertEquals(VoiceContext.MUTED, machine.context)
        assertEquals(plain(VoiceEffect.UNMUTE), machine.apply(VoiceEvent.Command(VoiceCommand.UNMUTE)))
        assertEquals(VoicePhase.Speaking, machine.phase)
        assertEquals(VoiceContext.SPEAKING, machine.context)
    }

    @Test fun `speech ending while muted stays muted`() {
        val machine = VoiceMachine().started()
        machine.apply(VoiceEvent.SpeechStarted)
        machine.apply(VoiceEvent.Command(VoiceCommand.MUTE))
        assertEquals(plain(VoiceEffect.CLEAR_DRAFT), machine.apply(VoiceEvent.SpeechEnded))
        assertEquals(VoicePhase.Listening, machine.phase)
        assertTrue(machine.isMuted)
        machine.apply(VoiceEvent.Command(VoiceCommand.UNMUTE))
        assertEquals(VoiceContext.DICTATING, machine.context)
    }

    @Test fun `mute while waiting returns to waiting`() {
        val machine = VoiceMachine().started()
        machine.apply(VoiceEvent.Transcript("go"))
        machine.apply(VoiceEvent.Command(VoiceCommand.SEND))
        machine.apply(VoiceEvent.SendFinished(sent = true))
        assertEquals(plain(VoiceEffect.MUTE), machine.apply(VoiceEvent.Command(VoiceCommand.MUTE)))
        assertEquals(VoicePhase.Waiting, machine.phase)
        assertEquals(VoiceContext.MUTED, machine.context)
        machine.apply(VoiceEvent.Command(VoiceCommand.UNMUTE))
        assertEquals(VoicePhase.Waiting, machine.phase)
        assertEquals(VoiceContext.DICTATING, machine.context)
    }

    @Test fun `a send in flight finishes while muted`() {
        val machine = VoiceMachine().started()
        machine.apply(VoiceEvent.Transcript("go"))
        machine.apply(VoiceEvent.Command(VoiceCommand.SEND))
        machine.apply(VoiceEvent.Command(VoiceCommand.MUTE))
        machine.apply(VoiceEvent.SendFinished(sent = true))
        assertEquals(VoicePhase.Waiting, machine.phase)
        assertTrue(machine.isMuted)
    }

    @Test fun `ending voice mode works while muted and clears it`() {
        val machine = VoiceMachine().started()
        machine.apply(VoiceEvent.Command(VoiceCommand.MUTE))
        assertEquals(plain(VoiceEffect.END), machine.apply(VoiceEvent.Command(VoiceCommand.DEACTIVATE)))
        assertFalse(machine.isMuted)
        assertEquals(null, machine.context)
        machine.apply(VoiceEvent.Start)
        machine.apply(VoiceEvent.Command(VoiceCommand.MUTE))
        machine.apply(VoiceEvent.Stop)
        machine.apply(VoiceEvent.Start)
        assertFalse(machine.isMuted)
    }
}
