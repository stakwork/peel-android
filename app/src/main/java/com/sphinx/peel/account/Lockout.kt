package com.sphinx.peel.account

/// Wrong-PIN throttling: the pure rules, with no clock, file or Keystore of
/// their own so they are unit-tested on the JVM (`LockoutPolicyTest`).
///
/// iOS has NO lockout: there the PIN-sealed blob sits behind a Keychain item
/// (the "real wall") and the Keychain is the mitigation. Android adds this
/// on top of the Keystore wall (`KeyWall`) because a person holding the
/// unlocked phone can otherwise type PINs as fast as the keypad allows
/// (a PIN is only 10^6 values). It is a speed bump against online guessing
/// through the UI; it does not stop an attacker who can copy the files and
/// the Keystore (root) — that is what the slow PBKDF2 is for.
object LockoutPolicy {
    /// The wait imposed after the n-th consecutive wrong PIN (n >= 1).
    /// Four free tries, then 30 s, 1 min, 5 min, 15 min, 1 h, and 6 h for
    /// every try from the tenth on. There is deliberately no wipe: an
    /// auto-wipe turns a stranger's (or a toddler's) typing into permanent
    /// loss of an account whose twelve words the owner may not have backed
    /// up, and "forget this account" stays the person's own choice.
    fun delayMsAfter(failures: Int): Long = when {
        failures <= 4 -> 0L
        failures == 5 -> 30_000L
        failures == 6 -> 60_000L
        failures == 7 -> 5 * 60_000L
        failures == 8 -> 15 * 60_000L
        failures == 9 -> 60 * 60_000L
        else -> 6 * 60 * 60_000L
    }

    /// What a state that failed its integrity check (or went missing while
    /// an account exists) is treated as: the counter of someone who already
    /// failed this many times, starting a fresh wait now. Resetting the
    /// counter by deleting or corrupting the file therefore gains nothing.
    const val TAMPER_FLOOR = 5

    /// The clocks a wait is measured on. `bootId` identifies one boot of the
    /// device, so `elapsedMs` (monotonic, but reset by a reboot) is only
    /// compared with a reading from the same boot.
    data class Clock(val elapsedMs: Long, val wallMs: Long, val bootId: String)

    /// What is persisted (HMAC'd by `LockoutStore`).
    data class State(
        val failures: Int = 0,
        /// The wait imposed by the latest failure.
        val delayMs: Long = 0,
        /// When that failure happened, on both clocks.
        val failedAtElapsedMs: Long = 0,
        val failedAtWallMs: Long = 0,
        val bootId: String = "",
    ) {
        companion object { val CLEAN = State() }
    }

    /// Milliseconds still to wait, 0 when a try is allowed.
    ///  * Same boot: the monotonic clock decides; the wall clock (which the
    ///    person can change in Settings) is ignored.
    ///  * After a reboot the monotonic clock is meaningless, so the wall
    ///    clock is used, but never trusted past the wait itself: a clock set
    ///    BACK before the failure is treated as no time having passed, and
    ///    one set far ahead can shorten the wait at most to nothing — which
    ///    needs the person to have changed the clock AND rebooted, an
    ///    accepted gap (a hardware-backed time source is not available).
    ///  * The result never exceeds `delayMs`.
    fun remainingMs(state: State, now: Clock): Long {
        if (state.delayMs <= 0) return 0
        val passed = if (state.bootId.isNotEmpty() && state.bootId == now.bootId) {
            now.elapsedMs - state.failedAtElapsedMs
        } else {
            now.wallMs - state.failedAtWallMs
        }
        // A negative reading (clock moved back) means the full wait is still due.
        val left = if (passed < 0) state.delayMs else state.delayMs - passed
        return left.coerceIn(0, state.delayMs)
    }

    /// The state to persist BEFORE a PIN is checked: one more failure, its
    /// wait starting now. A correct PIN then clears it (`State.CLEAN`).
    fun registerAttempt(state: State, now: Clock): State {
        val failures = (state.failures + 1).coerceAtMost(1_000)
        return State(failures, delayMsAfter(failures), now.elapsedMs, now.wallMs, now.bootId)
    }

    fun tampered(now: Clock): State = registerAttempt(State(failures = TAMPER_FLOOR - 1), now)

    /// "30 seconds", "5 minutes", "1 hour" — rounded up, for the lock screen.
    fun describe(ms: Long): String {
        val seconds = (ms + 999) / 1000
        return when {
            seconds < 60 -> "$seconds second${if (seconds == 1L) "" else "s"}"
            seconds < 3600 -> ((seconds + 59) / 60).let { "$it minute${if (it == 1L) "" else "s"}" }
            else -> ((seconds + 3599) / 3600).let { "$it hour${if (it == 1L) "" else "s"}" }
        }
    }

    /// Text form of `State`, with a version prefix, for `LockoutStore`.
    fun encode(state: State): String =
        listOf("1", state.failures, state.delayMs, state.failedAtElapsedMs, state.failedAtWallMs, state.bootId).joinToString("|")

    fun decode(text: String): State? {
        val p = text.split("|")
        if (p.size != 6 || p[0] != "1") return null
        return State(
            failures = p[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null,
            delayMs = p[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null,
            failedAtElapsedMs = p[3].toLongOrNull() ?: return null,
            failedAtWallMs = p[4].toLongOrNull() ?: return null,
            bootId = p[5],
        )
    }
}

/// Persists the lockout counter so it survives restarts and cannot be
/// quietly reset. `load` returns null for "nothing stored"; a stored state
/// that fails its integrity check is reported by throwing `Tampered`.
interface LockoutStore {
    class Tampered : Exception("lockout state failed its integrity check")

    fun load(): LockoutPolicy.State?
    fun save(state: LockoutPolicy.State)
    fun clear()
}

/// Source of `LockoutPolicy.Clock` readings (fakeable in tests).
fun interface LockoutClock { fun now(): LockoutPolicy.Clock }
