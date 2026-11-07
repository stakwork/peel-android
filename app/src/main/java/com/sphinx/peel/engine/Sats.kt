package com.sphinx.peel.engine

import java.text.NumberFormat
import java.util.Locale

/// Amounts as people see and type them. Money is kept, charged and sent in
/// msat; this is display and input only. Ported from PeelCore's `Sats.swift`
/// (no fiat anywhere: neither the iPhone nor the Mac app converts to a
/// currency, so there is no rate source to port — `docs/money-parity.md`).
object Sats {
    const val MSAT_PER_SAT = 1000L

    private fun whole(msat: Long): Long = if (msat <= 0) 0 else msat / MSAT_PER_SAT

    fun grouped(value: Long, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(value)

    /// "1 sat", "21 sats", "1,210 sats": whole sats, the rest of a sat
    /// dropped as the official apps drop it; below zero reads "0 sats".
    fun format(msat: Long, locale: Locale = Locale.getDefault()): String {
        val sats = whole(msat)
        return "${grouped(sats, locale)} ${if (sats == 1L) "sat" else "sats"}"
    }

    /// "3 sats", "3.5 sats": exact, as sent (the sub-sat part kept, up to three places).
    fun exact(msat: Long, locale: Locale = Locale.getDefault()): String {
        val value = msat.coerceAtLeast(0)
        if (value % MSAT_PER_SAT == 0L) return format(value, locale)
        val format = NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 0; maximumFractionDigits = 3 }
        return "${format.format(java.math.BigDecimal(value).divide(java.math.BigDecimal(MSAT_PER_SAT)))} sats"
    }

    /// "1,210": the number alone, for chips and lists that write "sats" apart.
    fun number(msat: Long, locale: Locale = Locale.getDefault()): String = grouped(whole(msat), locale)

    /// "+500" received, "−2,000" sent, with a true minus (U+2212) as the
    /// banking apps write it; "0" unsigned, since nothing a person can see moved.
    fun signed(msat: Long, incoming: Boolean, locale: Locale = Locale.getDefault()): String {
        val sign = if (whole(msat) == 0L) "" else if (incoming) "+" else "\u2212"
        return sign + number(msat, locale)
    }

    /// A whole number of sats above zero ("1,000" too), as msat; null for
    /// anything else, or too many to count in msat.
    fun msat(text: String): Long? {
        val digits = text.trim().replace(",", "")
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
        val sats = digits.toLongOrNull() ?: return null
        if (sats <= 0 || sats > Long.MAX_VALUE / MSAT_PER_SAT) return null
        return sats * MSAT_PER_SAT
    }
}
