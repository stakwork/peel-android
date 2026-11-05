package com.sphinx.peel.engine

/// The pure per-key merge of PeelCore's `PreferencesFile.merged`, for the
/// whole-number preferences this port syncs. No network, storage or clock
/// of its own, so it is unit-tested on the JVM.
///
/// The rules (same as iOS, for one account-wide item):
///  1. Stored strictly newer than the file's item: it goes up.
///  2. Otherwise the file's wins and is taken, if it reads and the key's
///     apply policy (`Spec.takes`) accepts it over the stored value; if
///     not, the stored value stays and only takes the file's date (at most
///     a day ahead of `now`), so a later change here beats the file's.
///  3. In the file only: taken, likewise.
///  4. Stored only: goes up if it was chosen on a device (date above 0).
object PreferencesMerge {
    /// A preference's value as the file holds it and how it is taken.
    class Spec(
        val key: String,
        /// The file's string to this port's unit, or null if it is not a value of the key.
        val parse: (String) -> Long?,
        /// This port's unit as the file's string.
        val format: (Long) -> String = { it.toString() },
        /// Whether `incoming` is taken over `current` (null when none is stored).
        val takes: (incoming: Long, current: Long?) -> Boolean = { _, _ -> true },
    )

    /// The official `tip_amount`: whole sats > 0 (PeelCore `boostAmountMsat`, no msat overflow).
    val TIP = Spec(PreferencesFile.TIP_AMOUNT_KEY, parse = { text ->
        text.toLongOrNull()?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }
    })

    /// Peel's `peel.payment_confirmation_msat`: whole msat >= 0. Taken from
    /// the file only when it asks for no more than this device's does
    /// (stricter), never because another device asked for less.
    val CONFIRMATION = Spec(
        PreferencesFile.PAYMENT_CONFIRMATION_KEY,
        parse = { text -> text.toLongOrNull()?.takeIf { it >= 0 } },
        takes = { incoming, current -> incoming <= (current ?: PaymentRules.DEFAULT_CONFIRMATION_MSAT) },
    )

    /// The official `pin_timeout`: whole hours, 0 (every time) to 25 (never),
    /// out-of-range values clamped, anything else refused (PeelCore `Preference.pinTimeout`).
    /// Default apply policy: always the newest.
    val PIN_TIMEOUT = Spec(PreferencesFile.PIN_TIMEOUT_KEY, parse = { text ->
        text.toIntOrNull()?.let { com.sphinx.peel.account.PinTimeout.clampOfficial(it).toLong() }
    })

    /// The official `biometric_enabled`: "true"/"false" in any case (PeelCore's
    /// `flag`); 1/0 here. Synced only by a device with biometrics, and only a wish.
    val BIOMETRIC = Spec(
        PreferencesFile.BIOMETRIC_KEY,
        parse = { text -> when (text.lowercase()) { "true" -> 1L; "false" -> 0L; else -> null } },
        format = { if (it != 0L) "true" else "false" },
    )

    /// Peel's `peel.auto_download_media`: "true"/"false" in any case (PeelCore's
    /// `flag`); 1/0 here. Default apply policy: always the newest.
    val AUTO_DOWNLOAD_MEDIA = Spec(
        PreferencesFile.AUTO_DOWNLOAD_MEDIA_KEY,
        parse = { text -> when (text.lowercase()) { "true" -> 1L; "false" -> 0L; else -> null } },
        format = { if (it != 0L) "true" else "false" },
    )

    val SPECS = listOf(TIP, CONFIRMATION, PIN_TIMEOUT, BIOMETRIC, AUTO_DOWNLOAD_MEDIA)

    /// This device's value (in the spec's unit) and its date in seconds;
    /// 0 for one never chosen on a device.
    data class Local(val value: Long, val date: Long)

    sealed interface Outcome {
        data object Nothing : Outcome
        /// Put this device's value in the file.
        data class Upload(val local: Local) : Outcome
        /// Store this value and date here.
        data class Take(val local: Local) : Outcome
    }

    /// A date from the file is trusted to at most a day past `nowSeconds`.
    const val MAX_AHEAD_SECONDS = 86_400L

    fun decide(spec: Spec, file: PreferencesFile?, local: Local?, nowSeconds: Long): Outcome {
        val entry = file?.entry(spec.key)
        if (entry == null) {
            return if (local != null && local.date > 0) Outcome.Upload(local) else Outcome.Nothing
        }
        val (text, fileDate) = entry
        if (local != null && local.date > (fileDate ?: 0L)) return Outcome.Upload(local)
        val incoming = spec.parse(text)
        if (incoming == null || !spec.takes(incoming, local?.value)) {
            if (local != null && fileDate != null) {
                val raised = minOf(fileDate, nowSeconds + MAX_AHEAD_SECONDS)
                if (raised > local.date) return Outcome.Take(Local(local.value, raised))
            }
            return Outcome.Nothing
        }
        // The same value takes the file's date only if it has one, or an
        // undated item would be taken again on every sync.
        if (local?.value == incoming && (fileDate == null || fileDate == local.date)) return Outcome.Nothing
        return Outcome.Take(Local(incoming, fileDate ?: 0L))
    }

    /// The date for a value chosen here: now, but always after the version it replaces.
    fun nextDate(nowSeconds: Long, oldDate: Long): Long = maxOf(nowSeconds, oldDate + 1)
}
