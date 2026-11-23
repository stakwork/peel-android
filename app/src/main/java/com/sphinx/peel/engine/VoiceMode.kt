package com.sphinx.peel.engine

import android.content.Context
import android.content.SharedPreferences
import kotlin.random.Random

/// Voice Mode (docs/phases/55-voice-mode.md): an alternative first screen, a mic on a linen canvas, behind a setting.
/// Android-original (the iOS `docs/voice-mode.md` has the Apple side); nothing here needs the native core, so all of it is JVM-tested.

/// The setting, kept on this device in its own preferences file (an app-level UI choice, like `TalkVoice`: not synced, not per account).
class VoiceModePrefs(private val prefs: SharedPreferences) {
    /// Off until the person turns it on.
    var enabled: Boolean
        get() = prefs.getBoolean(KEY, false)
        set(value) { prefs.edit().putBoolean(KEY, value).apply() }

    /// Calls [onChange] with the new value whenever [enabled] changes, from any `VoiceModePrefs` instance (they all
    /// share the one SharedPreferences file) -- so the app root's routing can react live, the way iOS's `@AppStorage`
    /// does, to the setting changing elsewhere (You's own toggle, or Voice Mode's own grid icon turning it off).
    /// Unregister the returned listener with [removeListener] when done (a `DisposableEffect`'s `onDispose`).
    fun onEnabledChanged(onChange: (Boolean) -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key -> if (key == KEY) onChange(changed.getBoolean(KEY, false)) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun removeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(listener)

    /// How the person and Peel talk in Voice Mode (see [VoiceModeInteraction]).
    var interaction: VoiceModeInteraction
        get() = VoiceModeInteraction.of(prefs.getString(VoiceModeInteraction.KEY, null))
        set(value) { prefs.edit().putString(VoiceModeInteraction.KEY, value.wire).apply() }

    /// Whether a spoken reply is also shown as text.
    var subtitles: Boolean
        get() = prefs.getBoolean(VoiceModeSubtitles.KEY, VoiceModeSubtitles.DEFAULT)
        set(value) { prefs.edit().putBoolean(VoiceModeSubtitles.KEY, value).apply() }

    /// Auto-send after a pause in continuous listening (docs/phases/55-voice-mode.md, "Silence timeout"), ported from
    /// peel's `VoiceDefaults.autoSend`. Off by default: "peel send" is the way to send; a pause can cut a driver off
    /// mid-thought, and a message sent half-formed is worse than one that waits.
    var autoSend: Boolean
        get() = prefs.getBoolean(VoiceAutoSend.KEY, VoiceAutoSend.DEFAULT)
        set(value) { prefs.edit().putBoolean(VoiceAutoSend.KEY, value).apply() }

    /// Seconds of silence before an auto-send, clamped to [VoiceAutoSend.RANGE] on every read (so a stale or corrupt
    /// stored value, or one carried over from somewhere else, is still bounded).
    var autoSendSeconds: Double
        get() = prefs.getFloat(VoiceAutoSend.SECONDS_KEY, VoiceAutoSend.DEFAULT_SECONDS.toFloat()).toDouble().coerceIn(VoiceAutoSend.RANGE)
        set(value) { prefs.edit().putFloat(VoiceAutoSend.SECONDS_KEY, value.toFloat()).apply() }

    companion object {
        const val KEY = "voiceMode"
        private const val FILE = "peel.voicemode"

        fun of(context: Context) = VoiceModePrefs(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}

/// Which interface opens once the account is unlocked.
enum class VoiceModeRoute {
    VOICE, STANDARD;

    companion object {
        /// Voice when the setting is on, unless the person went to the standard view this launch ([showedStandard]; the setting stays on).
        fun initial(voiceModeOn: Boolean, showedStandard: Boolean): VoiceModeRoute =
            if (voiceModeOn && !showedStandard) VOICE else STANDARD
    }
}

/// One thread fleck on the linen: a short stroke, positions as fractions of the canvas (0..1).
data class LinenFleck(val x: Float, val y: Float, val length: Float, val angle: Float, val shade: Float, val width: Float)

object Linen {
    const val SEED = 20261121L

    /// [count] flecks from a fixed [seed]: the same seed always gives the same flecks, so the cloth does not shimmer on recomposition.
    fun flecks(seed: Long = SEED, count: Int = 90): List<LinenFleck> {
        val random = Random(seed)
        return List(count.coerceAtLeast(0)) {
            LinenFleck(
                x = random.nextFloat(), y = random.nextFloat(),
                length = 0.004f + random.nextFloat() * 0.014f,
                // Mostly along the warp or weft, a few crooked.
                angle = if (random.nextFloat() < 0.8f) (if (random.nextBoolean()) 0f else 90f) else random.nextFloat() * 180f,
                shade = random.nextFloat(), width = 0.6f + random.nextFloat() * 0.9f,
            )
        }
    }
}

/// What may be sent from the Voice Mode screen: a trimmed, non-blank text.
object VoiceModeText {
    fun clean(text: String): String? = text.trim().takeIf { it.isNotEmpty() }
}

/// The four interaction modes. Stored as a string under [KEY] in [VoiceModePrefs].
enum class VoiceModeInteraction(val wire: String) {
    SPEECH_IN_SPEECH_OUT("speechInSpeechOut"),
    TEXT_IN_TEXT_OUT("textInTextOut"),
    SPEECH_IN_TEXT_OUT("speechInTextOut"),
    TEXT_IN_SPEECH_OUT("textInSpeechOut");

    /// True for modes where the mic is used for input.
    val usesMic: Boolean get() = this == SPEECH_IN_SPEECH_OUT || this == SPEECH_IN_TEXT_OUT
    /// True for modes where the agent's reply is spoken aloud.
    val speaksReply: Boolean get() = this == SPEECH_IN_SPEECH_OUT || this == TEXT_IN_SPEECH_OUT

    companion object {
        const val KEY = "voiceModeInteraction"
        val DEFAULT = SPEECH_IN_SPEECH_OUT
        fun of(wire: String?): VoiceModeInteraction = entries.firstOrNull { it.wire == wire } ?: DEFAULT
    }
}

/// Whether to show subtitles (the spoken reply as on-screen text) in speech-out modes.
object VoiceModeSubtitles {
    const val KEY = "voiceModeSubtitles"
    const val DEFAULT = false
}

/// Auto-send-after-silence's keys, default and range, ported from peel's `VoiceDefaults` (`Sources/PeelUI/Voice/VoiceModeState.swift`;
/// only the auto-send fields -- Android has no wake listener and no separate "other audio" session to pause, and TTS rate is a
/// separate concern from this phase's own).
object VoiceAutoSend {
    // ".v2": the first version stored on, 1-5 s, under the old unversioned keys. Those are not read here, so an old
    // stale short value already on a device is never silently carried over into this version's 2-15 s range.
    const val KEY = "voice.autoSend.v2"
    const val SECONDS_KEY = "voice.autoSendSeconds.v2"
    const val DEFAULT = false
    const val DEFAULT_SECONDS = 5.0
    val RANGE = 2.0..15.0
}

/// What Voice Mode shows (or reads) as the agent's reply: the agent's texts since the person's own newest message in
/// its DM, read from the chat's stored messages rather than a single live-arriving row (docs/phases/55-voice-mode.md,
/// "What the reply actually is"). Ported from peel's `VoiceModeReply` (`Sources/PeelUI/Voice/VoiceModeScreen.swift`).
object VoiceModeReply {
    /// The agent's texts since the person's newest message, oldest first; empty while it has not answered yet. A
    /// control line, a deleted message, and the person's own that never went (failed) do not count as a turn.
    fun latest(messages: List<com.sphinx.peel.storage.MessageRow>, agentPubkey: String): List<com.sphinx.peel.storage.MessageRow> {
        val replies = mutableListOf<com.sphinx.peel.storage.MessageRow>()
        for (row in messages.asReversed()) {
            if (row.chat != agentPubkey || row.deleted) continue
            if (ControlMessages.control(row.type, row.deleted, row.body) != null) continue
            if (row.fromMe) {
                if (row.status == "FAILED") continue
                break
            }
            if (row.type == TEXT_MESSAGE_TYPE) replies.add(row)
        }
        return replies.asReversed()
    }

    /// [replies] as one text, a blank line between them.
    fun text(replies: List<com.sphinx.peel.storage.MessageRow>): String? {
        val texts = replies.mapNotNull { row ->
            row.body?.let { runCatching { org.json.JSONObject(it).optString("content") }.getOrNull() }?.let(VoiceModeText::clean)
        }
        return texts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }
}
