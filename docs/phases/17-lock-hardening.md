# Phase 17 — Lock hardening

**Status (built, on `feat/lock-hardening`, unverified on a device):** Keystore
wall under the PIN blob, biometric unlock, wrong-PIN throttling, `pin_timeout` /
`biometric_enabled` settings + sync, FLAG_SECURE on secret screens, clipboard
hygiene. **Open:** see "Left over" below. Everything that doesn't need a real
Keystore/BiometricPrompt is unit-tested; the Android classes themselves
(`AndroidKeyWall`, `AndroidStateSigner`, `AndroidBiometricKeyVault`,
`AndroidClipboardAccess`, the prompt) have only been compiled, **never run on a
device or emulator**.

## What iOS does (the reference)

* Words sealed under a PIN-derived key (`PINBox`), in a Keychain item (the
  "real wall"). **No wrong-PIN lockout at all**; the Keychain is the mitigation.
* `pin_timeout` (official file key, hours 0..25, 25 = never; Peel's scale
  `-1,0,1,4,12,24` converted at the edge) and `biometric_enabled` both sync via
  the preferences file. Launch **always** asks; the timeout only governs the
  idle re-lock (0 and never both mean no idle re-lock). Face ID releases the PIN
  from the Keychain; a synced "on" is only a wish and never enrols (decision 10);
  a synced "off" turns it off.
* Pasteboard is cleared when the app locks.

## Threat model

| Attacker | Defence |
|---|---|
| Holds the unlocked phone, types PINs | Throttle (`LockoutPolicy`), persisted + HMAC'd, counted before checking |
| Copies app files / backup (no secure element) | Blob is ciphertext under a non-exportable Keystore key (`KeyWall`); `allowBackup=false` already |
| Root on a live device (can *use* the Keystore) | PBKDF2 (2.5M iterations) on the PIN; the throttle can be bypassed by root — accepted |
| Shoulder-surf / screenshot / Recents | FLAG_SECURE on secret screens; Recents thumbnail hidden app-wide (API 33+) |
| Another app reads the clipboard | Sensitive flag, auto-clear, wipe after pasting the words |
| New fingerprint added by the attacker | Biometric key is invalidated by enrolment; falls back to PIN |

Not defended: a 6-digit PIN against someone who can both extract the Keystore-
wrapped blob *and* use the Keystore (root) — then it's 10^6 × PBKDF2.

## Keystore layout (all aliases suffixed with the account name, `default`)

* `peel.wall.<a>` — AES-256-GCM, no user auth, StrongBox if available (API 28+)
  else TEE (fallback on any failure). Wraps the **whole v1 PinBox blob**
  (AAD `peel.secret.<a>`). File `secret.<a>.v2` = `0x02 | iv(12) | ct+tag`.
* `peel.lockout.<a>` — HMAC-SHA256 key (StrongBox/TEE) authenticating
  `lockout.<a>.v1` (`text \n base64(mac)`).
* `peel.bio.<a>` — AES-256-GCM, `setUserAuthenticationRequired(true)`,
  per-use auth (timeout 0, `AUTH_BIOMETRIC_STRONG` on API 30+, validity −1
  before), `setInvalidatedByBiometricEnrollment(true)`. Wraps the 32-byte
  **PBKDF2-derived key** (never the PIN). File `bio.<a>.v1` = `1 | ivLen | iv | ct`.
* The PinBox byte format (v1, as iOS) is unchanged inside.

### Migration (legacy `secret.<a>.v1` → v2)
`FileSecretStore.migrate()` runs at construction and again on first read if it
couldn't: lockout state is created, v2 is written atomically (owner-only temp
file + rename), and only after v2 unwraps to exactly the v1 bytes is v1 deleted.
Crash cases: after v2 write → next start sees both, verifies, deletes v1; v2 bad
next to good v1 → v2 discarded and redone; Keystore unavailable → the legacy blob
is still opened (never lock the person out) and migrated later. v1 bytes are not
securely erased (flash) — they're only PIN-protected ciphertext, as before.

## Biometric flow

* Enable (You → Biometric unlock): PIN (counted attempt, derives key, verifies)
  → fresh biometric key → `BiometricPrompt` with the encrypt `CryptoObject` →
  wrapped derived key stored → `biometric_enabled=true` recorded (and synced).
* Unlock (LockScreen, also the Twelve words screen): prompt with the decrypt
  `CryptoObject` → derived key → AES-GCM open of the blob. **No PBKDF2.** Auto-
  prompts once on the lock screen; "Use biometrics" button; keypad always there.
  Cancel/no match/OS lockout → PIN. Always BIOMETRIC_STRONG + CryptoObject, no
  device-credential fallback (the fallback is Peel's PIN).
* Dead key (new enrolment, deleted, stale because the PIN changed) → store
  forgets it and the PIN is asked. Change PIN and Forget account delete it.
  Biometric unlock neither counts toward nor resets the PIN throttle (the OS
  has its own lockout).
* Launch always asks (PIN or biometric), as iOS. So "Never ask for the PIN"
  means "no re-lock after launch", exactly iOS's semantics; with biometrics on
  a cold start costs one touch instead of a multi-second derivation.

## `pin_timeout` / `biometric_enabled`

`Setting.PIN_TIMEOUT` stores the official value (0..25) like the file;
`PinTimeout` converts to Peel's scale. UI: You → "Ask for the PIN"
(At launch / 1 / 4 / 12 / 24 h / Never). Idle re-lock (`RelockWhenIdle`): time
spent in the background (monotonic clock) over the timeout → lock screen over
the still-running engine. Sync: `PreferencesMerge.PIN_TIMEOUT` (hours, clamped
0..25, non-integers refused) and `BIOMETRIC` ("true"/"false" any case, written
lower-case); newest date wins; `biometric_enabled` syncs only when the device
can do strong biometrics (`Engine.syncsBiometrics`), and a synced value is a wish:
"off" disables the local key, "on" enrols nothing.

## Lockout schedule (`LockoutPolicy`)

Consecutive wrong PINs → wait before the next try: 1–4: none; 5: 30 s; 6: 1 min;
7: 5 min; 8: 15 min; 9: 1 h; ≥10: 6 h. **No auto-wipe** (deliberate: turns
someone else's typing into permanent loss of an account whose words may not be
backed up; forget-account stays the owner's choice). iOS has no lockout; this is
Android-only.
* The counter is incremented and persisted **before** the PIN is checked; a
  correct PIN clears it. Killing the app mid-check doesn't refund the attempt.
  A correct PIN during a wait is refused and not counted.
* Same boot: wait measured on `elapsedRealtime` (wall clock ignored). After a
  reboot (boot id from `/proc/sys/kernel/random/boot_id`, else BOOT_COUNT)
  the wall clock is used, a clock set back before the failure gives the full wait,
  and the remaining time never exceeds the wait.
* State is HMAC'd with a Keystore key. Bad MAC, unparsable, or **missing while an
  account exists** → treated as a tampered state: counts as 5 failures starting now.
* Applies to every PIN use: unlock, Twelve words, Change PIN, biometric setup.

## FLAG_SECURE decision

`SecureWindow` (ref-counted) on: lock screen, onboarding (new words, PIN, restore
word entry), Twelve words, Change PIN, biometric setup. **Not** app-wide:
chat screenshots are legitimate and common. Instead the Recents thumbnail is
hidden for the whole app on API 33+ (`setRecentsScreenshotEnabled(false)`);
below 33 chat content may appear in Recents. A "screen security" toggle for
full-app FLAG_SECURE is a possible follow-up.

## Clipboard

* `SensitiveClipboard` (pure, tested) + `AndroidClipboardAccess`: copy marks
  `EXTRA_IS_SENSITIVE` (API 33+), labels the clip with a nonce, clears after a
  timeout only if the clipboard's current clip still carries that label
  (`clearPrimaryClip` on API 28+, empty clip below). Used for the invoice Copy
  (2 min).
* After the Paste button fills the twelve-word boxes, the clipboard is cleared
  if it still holds that exact text. Pasting via the keyboard into a single box
  is not intercepted. Message Copy / Copy link are left as plain copies.
* Timers run on the main looper: if the process is killed before the timeout the
  clip stays (no WorkManager on purpose).

## Left over / caveats

* Never run on hardware: StrongBox fallback, biometric invalidation,
  `setUserAuthenticationParameters`, the prompt on Compose and the Recents flag
  all need a device test (also GrapheneOS, which has no Play services but does
  have StrongBox/biometrics).
* No idle re-lock while the app stays in front (iOS has one); no clipboard wipe
  on lock; notifications are not suppressed while locked.
* `FragmentActivity` is now the base class (BiometricPrompt requirement).
* Throttle is bypassable by root, by clock change + reboot, or by wiping app data
  (which also destroys the account on this device).
* A Keystore key lost (e.g. factory reset restore) = blob unreadable → restore
  from the twelve words (surfaces as "secure key store could not be used").
* Merged base: this branch sits on `feat/preferences-sync` (which contains
  money-scope); `feat/push-notifications-reliability` was **not** merged (conflicts
  in `Engine.kt` and `libs.versions.toml`) — when it lands expect to reconcile
  `Engine.kt` and the version catalog, and rename this file's number if phase 17
  is taken.

## Journal
* Added the Keystore wall, throttle, biometric unlock, settings/sync,
  FLAG_SECURE, clipboard; tests in `account/*Test`, `PreferencesSecurityKeysTest`.
