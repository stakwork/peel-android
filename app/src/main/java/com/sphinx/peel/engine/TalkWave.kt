package com.sphinx.peel.engine

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/// The Talk call screen's waveform (iOS `TalkWave`): rounded bars in the
/// circle that move with the voice heard (Jamie's while it speaks, the
/// member's own while it listens), a calm wave travelling across them while
/// Jamie thinks, a slow breath while calling. Pure: the heights from the mode,
/// a level and the time, and the level's smoothing; the screen draws them.
/// Heights are a share of the tallest bar, [FLOOR] to 1. Not ported: Reduce
/// Motion's still variant, and `Animator`'s per-bar easing.
object TalkWave {
    const val BAR_COUNT = 7
    /// The lowest a bar goes.
    const val FLOOR = 0.12

    /// What the bars show.
    enum class Mode {
        /// Jamie speaking: the agent's audio level.
        AGENT,
        /// Listening: the member's microphone level.
        MEMBER,
        /// Jamie thinking: a wave travelling across.
        THINKING,
        /// Calling, connecting, getting ready: a slow breath.
        BREATHING,
        /// Listening with the microphone muted: low and flat.
        MUTED,
        /// Jamie left, or the call is over: no motion.
        STILL;

        /// Whether the bars follow a level.
        val hearsVoice: Boolean get() = this == AGENT || this == MEMBER

        companion object {
            /// The mode for a phase; null while still calling. [muted] only
            /// counts while listening: what Jamie says still shows.
            fun of(phase: TalkPhase?, muted: Boolean): Mode = when (phase) {
                null, TalkPhase.Connecting, TalkPhase.Reconnecting, TalkPhase.GettingReady -> BREATHING
                is TalkPhase.Waiting -> if (phase.left) STILL else BREATHING
                TalkPhase.Listening -> if (muted) MUTED else MEMBER
                TalkPhase.Thinking -> THINKING
                TalkPhase.Speaking -> AGENT
                is TalkPhase.Ended -> STILL
            }
        }
    }

    /// Each bar's height for [mode] at [level] (0…1, smoothed) and [time] in seconds.
    fun heights(mode: Mode, level: Double, time: Double, count: Int = BAR_COUNT): List<Double> {
        val l = min(1.0, max(0.0, level))
        return (0 until count).map { i ->
            val shape = envelope(i, count)
            val height = when (mode) {
                Mode.AGENT, Mode.MEMBER -> {
                    // Each bar at its own pace, so they move like a voice
                    // rather than in lockstep; a faint shimmer in silence.
                    val wobble = 0.5 + 0.5 * sin(time * speed(i) + i * 2.4)
                    FLOOR + 0.04 * wobble + (1 - FLOOR - 0.04) * l * shape * (0.55 + 0.45 * wobble)
                }
                Mode.THINKING -> FLOOR + 0.08 + 0.3 * (0.5 + 0.5 * sin(2 * PI * (0.7 * time - i.toDouble() / count)))
                Mode.BREATHING -> FLOOR + (0.1 + 0.18 * (0.5 + 0.5 * sin(2 * PI * time / 2.6))) * shape
                Mode.MUTED -> FLOOR
                Mode.STILL -> FLOOR + 0.1 * shape
            }
            min(1.0, max(FLOOR, height))
        }
    }

    /// The bars' shape: the middle tallest, the ends about two thirds.
    internal fun envelope(i: Int, count: Int): Double = 0.6 + 0.4 * sin(PI * (i + 0.5) / count)

    /// A bar's wobble, in radians a second: 1.6 to 2.8 cycles, spread by the
    /// golden ratio so neighbours differ.
    private fun speed(i: Int): Double = 2 * PI * (1.6 + 1.2 * ((i * 0.618034) % 1.0))
}

/// A level smoothed for the eye: it rises fast with the voice and falls more
/// slowly, so a word lifts the bars at once and they settle after it (iOS `LevelSmoother`).
class LevelSmoother(private val attack: Double = 0.03, private val release: Double = 0.25, value: Double = 0.0) {
    var value: Double = value
        private set

    /// Moves [dt] seconds toward [target] and returns where it is.
    fun update(target: Double, dt: Double): Double {
        if (dt <= 0) return value
        val tau = if (target > value) attack else release
        value += (target - value) * (1 - exp(-dt / tau))
        return value
    }
}
