package com.sphinx.peel.storage

/// This account's own state and configuration, one row per case in the
/// `setting` table (key = `key`, a plain string). Ported from PeelCore's
/// Models.swift `Setting`; trimmed to what Android stores so far (no
/// tribes, device requests or agent yet) and extended as the engine grows.
enum class Setting(val key: String) {
    NETWORK("network"),
    SERVER("server"),
    DEVICE_ID("deviceID"),
    ALIAS("alias"),
    PHOTO_URL("photoURL"),
    CONTACT_INFO("contactInfo"),
    BALANCE_MSAT("balanceMsat"),
    CLOCK("clock"),

    /// "1" while an account brought back from its words still needs its history.
    NEEDS_RESTORE("needsRestore"),

    /// "1" once the twelve words were checked: three of them typed back, or
    /// all twelve to restore. Never the words themselves. An account with
    /// no record has not been checked.
    WORDS_CONFIRMED("wordsConfirmed"),

    /// "1" once `Engine.backfillContactsIfNeeded` has re-derived `contact`
    /// rows (tribes especially) from whatever `message` already held —
    /// needed once, for history a restore stored before that detection
    /// existed; never again after, so a real restore's own inserts aren't
    /// redundantly re-scanned on every launch.
    CONTACTS_BACKFILLED("contactsBackfilled"),
}
