package com.sphinx.peel.ui.you

/// Pure helpers behind Profile's pickers, kept free of Compose so they can
/// be unit-tested on the JVM.
object YouOptions {
    /// One entry in a Profile picker: what is stored and what is shown.
    data class Choice<T>(val value: T, val label: String)

    /// Whole sats with '.' between thousands, as the iOS app shows them
    /// ("5.179", "1.000").
    fun groupedSats(sats: Long): String {
        val digits = Math.abs(sats).toString()
        val grouped = digits.reversed().chunked(3).joinToString(".").reversed()
        return if (sats < 0) "-$grouped" else grouped
    }

    /// "Ask for the PIN" (PeelCore's `PINTimeout`): the stored string is
    /// hours away, or "never".
    const val PIN_TIMEOUT_NEVER = "never"
    val pinTimeouts: List<Choice<String>> = listOf(
        Choice("0", "Every time Peel opens"),
        Choice("1", "After 1 hour"),
        Choice("4", "After 4 hours"),
        Choice("12", "After 12 hours"),
        Choice("24", "After a day"),
        Choice(PIN_TIMEOUT_NEVER, "Never ask for PIN"),
    )

    /// Unset reads as a day, the iOS default.
    fun pinTimeoutOf(stored: String?): String =
        stored?.takeIf { s -> pinTimeouts.any { it.value == s } } ?: "24"

    /// "Confirm payments" (PeelCore's `paymentConfirmationMsat`).
    const val PAYMENT_CONFIRMATION_DEFAULT_MSAT = 100_000L
    val paymentConfirmations: List<Choice<Long>> = listOf(
        Choice(0L, "Always"),
        Choice(100_000L, "Over 100 sats"),
        Choice(1_000_000L, "Over 1.000 sats"),
        Choice(10_000_000L, "Over 10.000 sats"),
    )

    /// Unset (or an unknown value) reads as the iOS default, over 100 sats.
    fun paymentConfirmationOf(stored: String?): Long =
        stored?.toLongOrNull()?.takeIf { m -> paymentConfirmations.any { it.value == m } }
            ?: PAYMENT_CONFIRMATION_DEFAULT_MSAT

    /// The boost presets, in sats.
    val boostPresets: List<Long> = listOf(10, 50, 100, 500, 1000)

    /// A custom boost amount: a whole number of sats, more than zero.
    fun parseBoostSats(text: String): Long? = text.toLongOrNull()?.takeIf { it > 0 }
}
