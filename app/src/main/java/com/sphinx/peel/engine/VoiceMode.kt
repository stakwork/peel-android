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
