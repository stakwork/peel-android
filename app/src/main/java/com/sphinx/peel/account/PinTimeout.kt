package com.sphinx.peel.account

/// The "Ask for the PIN" setting, ported from PeelCore's `PINTimeout`
/// (Preferences.swift) and `AppModel.isLockDue`.
///
/// Two scales: the official apps' `pin_timeout` file value (whole hours
/// 0..25; 0 = every time, 25 = never) is what is STORED here, exactly as the
/// file holds it. Peel's own scale (what the UI offers and these helpers
/// speak) is `NEVER` (-1), 0, 1, 4, 12, 24.
///
/// iOS semantics kept: launch ALWAYS asks (the PIN is not stored and the
/// words need it, or a biometric release of the derived key); the timeout
/// only governs RE-locking an already-open app after it has been idle.
/// 0 and `NEVER` therefore both mean "no idle re-lock".
object PinTimeout {
    const val NEVER = -1
    const val OFFICIAL_ALWAYS = 0
    const val OFFICIAL_NEVER = 25
    val OPTIONS = listOf(0, 1, 4, 12, 24)

    fun clampOfficial(hours: Int) = hours.coerceIn(OFFICIAL_ALWAYS, OFFICIAL_NEVER)

    fun peelHours(fromOfficial: Int): Int {
        val hours = clampOfficial(fromOfficial)
        if (hours == OFFICIAL_NEVER) return NEVER
        var best = OPTIONS[0]
        for (option in OPTIONS.drop(1)) if (kotlin.math.abs(option - hours) < kotlin.math.abs(best - hours)) best = option
        return best
    }

    fun officialHours(fromPeel: Int): Int =
        if (fromPeel <= NEVER) OFFICIAL_NEVER else minOf(fromPeel, OFFICIAL_NEVER - 1)

    fun label(peelHours: Int): String = when {
        peelHours <= NEVER -> "Never"
        peelHours == 0 -> "At launch"
        peelHours == 1 -> "After 1 hour"
        else -> "After $peelHours hours"
    }

    /// Whether an app idle for `idleMs` (monotonic) must ask again.
    fun isLockDue(peelHours: Int, idleMs: Long): Boolean =
        peelHours > 0 && idleMs > peelHours * 3_600_000L
}
