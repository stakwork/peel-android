package com.sphinx.peel.engine

/// Where voice mode is (docs/phases/55-voice-mode.md, "State machine"; peel's `docs/voice-mode.md`, same section).
/// Ported from `Sources/PeelUI/Voice/VoiceModeState.swift`.
sealed class VoicePhase {
    object Idle : VoicePhase()

    /// Listening, nothing said yet.
    object Listening : VoicePhase()

    /// Listening, with a draft.
    object Hearing : VoicePhase()

    /// The draft is on its way to the agent.
    object Sending : VoicePhase()

    /// The agent is answering.
    object Waiting : VoicePhase()

    /// A reply is being read.
    object Speaking : VoicePhase()

    data class Failed(val problem: String) : VoicePhase()

    val isActive: Boolean get() = this !is Idle && this !is Failed

    /// The context transcripts are heard in ([VoiceCommandParser], a later phase): while the agent answers you may already say
    /// the next message.
    val context: VoiceContext?
        get() = when (this) {
            Listening, Hearing, Waiting -> VoiceContext.DICTATING
            Sending -> VoiceContext.WAITING
            Speaking -> VoiceContext.SPEAKING
            Idle, is Failed -> null
        }
}

/// What a spoken phrase can ask of voice mode. The phrases themselves are a later phase (`VoiceCommandParser`); this is just
/// the vocabulary the reducer understands. Ported from `Sources/PeelUI/Voice/VoiceCommand.swift`.
enum class VoiceCommand {
    /// Turns voice mode on; heard only while a wake listener is armed (not built on Android).
    ACTIVATE, DEACTIVATE,
    /// Sends the draft. Said as the last words of a sentence.
    SEND,
    /// Throws the draft away. Said as the last words of a sentence.
    DISCARD,
    /// Barge-in: stops the speech now.
    STOP_SPEAKING, REPEAT_REPLY,
    /// Skips to the end of what is being read.
    SKIP,
    /// Reads the full text of a reply that was only summarised.
    READ_ALL,
    /// Stops the agent's running turn.
    STOP_AGENT,
    /// The microphone input is ignored until `UNMUTE`.
    MUTE, UNMUTE,
}

/// Where a transcript is heard, which decides the commands that count (`VoiceCommandParser`, a later phase).
enum class VoiceContext {
    /// The wake listener: only "hey peel" (not built on Android).
    ARMED,
    /// Listening for the user's message.
    DICTATING,
    /// Waiting for the agent.
    WAITING,
    /// The speech is playing.
    SPEAKING,
    /// Muted: only unmute and end voice mode.
    MUTED,
}

/// What happens to voice mode.
sealed class VoiceEvent {
    object Start : VoiceEvent()
    object Stop : VoiceEvent()

    /// The draft so far (everything heard since the last send).
    data class Transcript(val text: String) : VoiceEvent()

    /// No new words for the auto-send time (a later phase on Android; the one-shot recognizer fires this itself today, see
    /// docs/phases/55-voice-mode.md).
    object Silence : VoiceEvent()
    data class Command(val command: VoiceCommand) : VoiceEvent()
    data class SendFinished(val sent: Boolean) : VoiceEvent()

    /// The agent's turn ended; [hasReply] if there is something to read.
    data class ReplyReady(val hasReply: Boolean) : VoiceEvent()
    object SpeechStarted : VoiceEvent()
    object SpeechEnded : VoiceEvent()

    /// A turn is still being answered (after speech that came in between).
    object AwaitReply : VoiceEvent()
    data class Failure(val problem: String) : VoiceEvent()
}

/// What the screen must do about it.
enum class VoiceEffect {
    BEGIN, END,
    CLEAR_DRAFT, STOP_SPEECH, SKIP_SPEECH, SPEAK_LAST_REPLY, SPEAK_FULL_REPLY, STOP_AGENT, MUTE, UNMUTE,
}

/// One step the reducer asks for: either a plain [VoiceEffect] or a send with its text (its own case, since a Kotlin enum
/// cannot hold a per-case payload).
sealed class VoiceAction {
    data class Plain(val effect: VoiceEffect) : VoiceAction()
    data class Send(val text: String) : VoiceAction()
}

/// The transitions of voice mode as a reducer: no audio, no engine, so it is tested without either. Ported from
/// `Sources/PeelUI/Voice/VoiceModeState.swift`'s `VoiceMachine`.
class VoiceMachine {
    var phase: VoicePhase = VoicePhase.Idle
        private set
    var draft: String = ""
        private set

    /// Muted is a flag over the phase, so unmuting returns to whatever phase it was (listening, waiting, speaking) without
    /// remembering it.
    var isMuted: Boolean = false
        private set

    /// The context transcripts are heard in: [VoiceContext.MUTED] while muted.
    val context: VoiceContext?
        get() {
            if (!phase.isActive) return null
            return if (isMuted) VoiceContext.MUTED else phase.context
        }

    private val hasDraft: Boolean get() = draft.trim().isNotEmpty()

    fun apply(event: VoiceEvent): List<VoiceAction> {
        when (event) {
            is VoiceEvent.Start -> {
                if (phase.isActive) return emptyList()
                phase = VoicePhase.Listening
                draft = ""
                isMuted = false
                return listOf(VoiceAction.Plain(VoiceEffect.BEGIN))
            }
            is VoiceEvent.Stop -> {
                if (!phase.isActive) return emptyList()
                phase = VoicePhase.Idle
                draft = ""
                isMuted = false
                return listOf(VoiceAction.Plain(VoiceEffect.END))
            }
            is VoiceEvent.Command -> if (event.command == VoiceCommand.DEACTIVATE) {
                if (!phase.isActive) return emptyList()
                phase = VoicePhase.Idle
                draft = ""
                isMuted = false
                return listOf(VoiceAction.Plain(VoiceEffect.END))
            }
            is VoiceEvent.Failure -> {
                if (!phase.isActive) return emptyList()
                phase = VoicePhase.Failed(event.problem)
                draft = ""
                isMuted = false
                return listOf(VoiceAction.Plain(VoiceEffect.END))
            }
            else -> {}
        }
        if (!phase.isActive) return emptyList()

        when (event) {
            is VoiceEvent.Command -> when (event.command) {
                VoiceCommand.MUTE -> {
                    if (isMuted) return emptyList()
                    isMuted = true
                    return listOf(VoiceAction.Plain(VoiceEffect.MUTE))
                }
                VoiceCommand.UNMUTE -> {
                    if (!isMuted) return emptyList()
                    isMuted = false
                    return listOf(VoiceAction.Plain(VoiceEffect.UNMUTE))
                }
                else -> {}
            }
            else -> {}
        }
        // Muted: the draft is frozen and nothing is sent.
        if (isMuted) when (event) {
            is VoiceEvent.Transcript, VoiceEvent.Silence -> return emptyList()
            is VoiceEvent.Command -> if (event.command == VoiceCommand.SEND || event.command == VoiceCommand.DISCARD) return emptyList()
            else -> {}
        }

        return when {
            phase is VoicePhase.Waiting && event is VoiceEvent.Transcript -> {
                // The next message, said while the agent answers; silence (an empty text) keeps it waiting.
                if (event.text.trim().isEmpty()) emptyList() else {
                    draft = event.text
                    phase = VoicePhase.Hearing
                    emptyList()
                }
            }
            (phase is VoicePhase.Listening || phase is VoicePhase.Hearing) && event is VoiceEvent.Transcript -> {
                draft = event.text
                phase = if (hasDraft) VoicePhase.Hearing else VoicePhase.Listening
                emptyList()
            }
            phase is VoicePhase.Hearing && (event is VoiceEvent.Silence || (event is VoiceEvent.Command && event.command == VoiceCommand.SEND)) -> beginSend()
            phase is VoicePhase.Listening && event is VoiceEvent.Command && event.command == VoiceCommand.SEND -> emptyList()
            (phase is VoicePhase.Listening || phase is VoicePhase.Hearing) && event is VoiceEvent.Command && event.command == VoiceCommand.DISCARD -> {
                draft = ""
                phase = VoicePhase.Listening
                listOf(VoiceAction.Plain(VoiceEffect.CLEAR_DRAFT))
            }
            phase is VoicePhase.Sending && event is VoiceEvent.SendFinished -> {
                phase = if (event.sent) VoicePhase.Waiting else if (hasDraft) VoicePhase.Hearing else VoicePhase.Listening
                if (event.sent) draft = ""
                emptyList()
            }
            (phase is VoicePhase.Waiting || phase is VoicePhase.Sending) && event is VoiceEvent.ReplyReady -> {
                // Speech starts with SpeechStarted; with nothing to read, listen again.
                if (!event.hasReply) phase = VoicePhase.Listening
                emptyList()
            }
            phase is VoicePhase.Listening && event is VoiceEvent.AwaitReply -> {
                phase = VoicePhase.Waiting
                emptyList()
            }
            event is VoiceEvent.SpeechStarted -> {
                phase = VoicePhase.Speaking
                emptyList()
            }
            phase is VoicePhase.Speaking && event is VoiceEvent.SpeechEnded -> {
                phase = VoicePhase.Listening
                draft = ""
                listOf(VoiceAction.Plain(VoiceEffect.CLEAR_DRAFT))
            }
            phase is VoicePhase.Speaking && event is VoiceEvent.Command && event.command == VoiceCommand.STOP_SPEAKING -> {
                phase = VoicePhase.Listening
                draft = ""
                listOf(VoiceAction.Plain(VoiceEffect.STOP_SPEECH), VoiceAction.Plain(VoiceEffect.CLEAR_DRAFT))
            }
            phase is VoicePhase.Speaking && event is VoiceEvent.Command && event.command == VoiceCommand.SKIP ->
                listOf(VoiceAction.Plain(VoiceEffect.SKIP_SPEECH))
            event is VoiceEvent.Command && event.command == VoiceCommand.REPEAT_REPLY -> listOf(VoiceAction.Plain(VoiceEffect.SPEAK_LAST_REPLY))
            event is VoiceEvent.Command && event.command == VoiceCommand.READ_ALL -> listOf(VoiceAction.Plain(VoiceEffect.SPEAK_FULL_REPLY))
            event is VoiceEvent.Command && event.command == VoiceCommand.STOP_AGENT -> listOf(VoiceAction.Plain(VoiceEffect.STOP_AGENT))
            else -> emptyList()
        }
    }

    private fun beginSend(): List<VoiceAction> {
        val text = draft.trim()
        phase = VoicePhase.Sending
        return listOf(VoiceAction.Send(text))
    }
}
