package com.sphinx.peel.engine

// An encrypted group call as it goes (docs/phases/56-box-group-calls.md; stadeum plans/group-calls.md §2): the link's key is the
// room's shared key, as Talk's is ([TalkEncryption]). What the call screen says when it fails, and what Jamie says of the call
// (plans/call-canvas.md §4). Pure, JVM-tested (`CallEncryptionTest`); the room is `ui/call/CallSession`.

/// The tracks LiveKit says will not encrypt or decrypt on an encrypted group call. The room's signaling is not encrypted, so a
/// call whose key does not match would otherwise show people talking over silence. [notice] is what the call screen says instead.
data class CallEncryption(
    /// The tracks failing now, by publication id: true for the person's own (it will not encrypt), false for someone else's (it
    /// will not decrypt).
    private val failing: Map<String, Boolean> = emptyMap(),
) {
    /// A track's state from LiveKit: [failed] for a missing key or frames that will not encrypt or decrypt, [local] for the
    /// person's own track.
    fun track(id: String, local: Boolean, failed: Boolean): CallEncryption =
        copy(failing = if (failed) failing + (id to local) else failing - id)

    /// What the call screen says, null while nothing fails. Someone unheard first.
    val notice: String?
        get() = when {
            failing.values.contains(false) -> SOMEONE_UNHEARD
            failing.values.contains(true) -> YOU_UNHEARD
            else -> null
        }

    companion object {
        const val SOMEONE_UNHEARD = "This call's encryption failed: someone can't be heard."
        const val YOU_UNHEARD = "This call's encryption failed: the others can't hear you."
        /// LiveKit did not turn the room's encryption on: Peel leaves rather than join in the clear.
        const val NOT_ON = "Peel couldn't encrypt this call, so it left."
    }
}

/// Jamie recording the call on the canvas (call-canvas.md §4): the box's voice service sets Jamie's participant attribute
/// [ATTRIBUTE] to "true" while it records, and clears it when it stops.
object CanvasRecording {
    const val ATTRIBUTE = "recording"

    /// Whether a participant named [name] with [attributes] says the call is recorded on the canvas: Jamie, saying so.
    fun isOn(name: String?, attributes: Map<String, String>): Boolean =
        attributes[ATTRIBUTE] == "true" && CallParticipant("", name ?: "", null).isJamie
}
