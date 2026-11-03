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

    /// Where an interrupted restore stands (`RestoreCursor.encode`): which
    /// phase, the next server index to ask for, where it ends. Written after
    /// every page, removed in the commit that finishes the restore. Not
    /// secret: indices only.
    RESTORE_CURSOR("restoreCursor"),

    /// "1" once the twelve words were checked: three of them typed back, or
    /// all twelve to restore. Never the words themselves. An account with
    /// no record has not been checked.
    WORDS_CONFIRMED("wordsConfirmed"),

    /// The backfill's own version number, as a string, once
    /// `Engine.backfillContactsIfNeeded` has re-derived `contact` rows
    /// (tribes especially) from whatever `message` already held — needed
    /// once, for history a restore stored before that detection existed;
    /// never again after, so a real restore's own inserts aren't
    /// redundantly re-scanned on every launch. Bumping the version a
    /// fresh backfill checks against (`Engine.BACKFILL_VERSION`) re-runs
    /// it once more on a device that already ran an older, buggier pass
    /// — this has already happened twice: once when a first version left
    /// a tribe's `alias`/`photoUrl` stale if a `host`-less message
    /// reached `Storage.upsertContact` before the one that revealed it
    /// was a tribe, and again when that version turned out to still skip
    /// every `fromMe` message, missing tribes whose only stored message
    /// was one of our own, echoed back with `host`
    /// (`docs/phases/04-dashboard.md`).
    CONTACTS_BACKFILLED("contactsBackfilled"),

    /// The tribes registry this account's tribes are listed on
    /// (`ServerConfig.tribeHost`), recorded once so a tribe's name/photo
    /// can be asked for later without the public config call every time.
    TRIBE_HOST("tribeHost"),

    /// The one-tap boost's own tip, in msat — PeelCore's own `tip_amount`
    /// preference. Unset until chosen here or taken from another
    /// device's (`Engine.syncBoostAmount`); `Engine.boostAmountMsat()`
    /// falls back to `BOOST_AMOUNT_MSAT` (`Attachment.kt`) until then.
    BOOST_AMOUNT_MSAT("boostAmountMsat"),

    /// When `BOOST_AMOUNT_MSAT` was last set, in whole seconds since the
    /// epoch — the preferences file's own "date" for `tip_amount`,
    /// needed to tell a value chosen on this device from one the file
    /// might hold from another, PeelCore's own last-write-wins rule
    /// (`PreferencesFile.merged`).
    BOOST_AMOUNT_UPDATED_AT("boostAmountUpdatedAt"),

    /// The memes server this account's preferences file was last
    /// confirmed fetched from (or started on), lower-cased — PeelCore's
    /// own `device.preferences_file_seen`: a "not found" answer may
    /// only start a fresh file here once, on a server never seen to
    /// have had one before (`Engine.mayStartPreferencesFile`) — so a
    /// transient server hiccup never looks like "this account has no
    /// preferences file yet" and overwrites a real one.
    PREFERENCES_FILE_SEEN("preferencesFileSeen"),

    /// This account's own call server, if ever explicitly configured —
    /// PeelCore's own `Setting.callServer`. Unset on every account so
    /// far (no settings screen writes it yet); `Engine.callServer()`
    /// falls back to the official apps' own `chat.sphinx.chat` the same
    /// way PeelCore's `configuredHost` does.
    CALL_SERVER("callServer"),

    /// Payments and invoices over this many msat need the person's
    /// confirmation first — PeelCore's `paymentConfirmationMsat`
    /// (default 100 sats, 0 asks every time). Synced as Peel's
    /// `peel.payment_confirmation_msat`, taken from another device only when stricter.
    PAYMENT_CONFIRMATION_MSAT("paymentConfirmationMsat"),

    /// When `PAYMENT_CONFIRMATION_MSAT` was last chosen here (or taken from
    /// the preferences file), in whole seconds; absent for a value never
    /// chosen, which is never uploaded (`peel.payment_confirmation_msat`).
    PAYMENT_CONFIRMATION_UPDATED_AT("paymentConfirmationUpdatedAt"),

    /// The official `pin_timeout` as the preferences file holds it (whole
    /// hours 0..25, 25 = never); Peel's scale is converted at the edge
    /// (`PinTimeout`). Unset = at launch.
    PIN_TIMEOUT("pinTimeout"),
    PIN_TIMEOUT_UPDATED_AT("pinTimeoutUpdatedAt"),

    /// The official `biometric_enabled` ("1"/"0" here): the person's wish,
    /// synced; whether biometrics are actually enrolled is the Keystore's
    /// (`SecretStore.biometricEnrolled`), never set by a sync.
    BIOMETRIC_ENABLED("biometricEnabled"),
    BIOMETRIC_ENABLED_UPDATED_AT("biometricEnabledUpdatedAt"),
}
