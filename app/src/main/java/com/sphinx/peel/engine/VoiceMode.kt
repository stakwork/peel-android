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

    /// How the person and Peel talk in Voice Mode (see [VoiceModeInteraction]).
    var interaction: VoiceModeInteraction
        get() = VoiceModeInteraction.of(prefs.getString(VoiceModeInteraction.KEY, null))
        set(value) { prefs.edit().putString(VoiceModeInteraction.KEY, value.wire).apply() }

    /// Whether a spoken reply is also shown as text.
    var subtitles: Boolean
        get() = prefs.getBoolean(VoiceModeSubtitles.KEY, VoiceModeSubtitles.DEFAULT)
        set(value) { prefs.edit().putBoolean(VoiceModeSubtitles.KEY, value).apply() }

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

/// The agent's reply text in a message the engine received: a plain, undeleted text message from the agent's DM that is not a control line.
object VoiceModeReply {
    fun text(row: com.sphinx.peel.storage.MessageRow, agentPubkey: String): String? {
        if (row.chat != agentPubkey || row.fromMe || row.deleted || row.type != TEXT_MESSAGE_TYPE) return null
        if (ControlMessages.control(row.type, row.deleted, row.body) != null) return null
        val content = row.body?.let { runCatching { org.json.JSONObject(it).optString("content") }.getOrNull() }
        return content?.let(VoiceModeText::clean)
    }
}
