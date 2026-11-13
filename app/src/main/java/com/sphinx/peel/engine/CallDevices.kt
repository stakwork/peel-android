package com.sphinx.peel.engine

/// Joining a call this account's other device is already on (PeelCore's `CallStore.otherDevice`, `askAboutOtherDevice`).
object CallDevices {
    /// How long a join waits to hear who is on the call before it goes ahead without knowing (iOS `presenceWait`).
    const val PRESENCE_WAIT_MS = 2_500L

    /// What the person is asked, as iOS words it.
    const val QUESTION = "You are already on this call on another of your devices."
    const val SWITCH = "Switch to this device"
    const val STAY = "Stay on both devices"
    const val CANCEL = "Cancel"

    /// What the chat says after another device took the call.
    const val MOVED_NOTICE = "The call moved to your other device."
    const val MOVED_NOTICE_MS = 5_000L

    /// Whether another device of this account looks to be on the call: a participant with another identity, this
    /// side's name and not Jamie (both devices join under the same name). Unknown ([people] null) is no: a join is
    /// never held up by it. Only decides whether to ask; leaving on a handoff rests on [CallHandoff]'s MAC.
    /// [mine] is this device's own earlier identity on the room (dropped and joined again, it may still be listed).
    fun otherDevice(people: List<CallParticipant>?, ticket: CallTicket, mine: String?): Boolean =
        (people ?: emptyList()).any {
            it.identity != ticket.identity && it.identity != mine && !it.isJamie && it.nickname == ticket.participantName
        }
}
