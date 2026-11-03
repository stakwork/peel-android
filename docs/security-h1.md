# Security finding H1: seed protected only by a 6-digit PIN

## Before
`secret.<account>.v1` was a bare `PinBox` blob (PBKDF2-HMAC-SHA256, 2.5M iterations, AES-GCM). A copy of app data
(backup, forensic image) allowed offline brute force of the 10^6 PINs; there was no attempt limit; the words
were handled as `String`s.

## Design
Layout in `filesDir` (owner-only, atomic writes):

- `secret.<account>.v2` = `0x02 | AES-GCM(Keystore wall key, aad "peel.secret.<account>")` of the v1 `PinBox` blob.
  The wall key is a non-auth-bound AES-256-GCM key in AndroidKeyStore; StrongBox is requested first and the code
  falls back to the TEE keystore when it is unavailable (`Keystore.withStrongBoxFallback`). It never leaves the
  secure hardware, so a copied blob cannot be brute-forced offline. The PIN factor is unchanged: PBKDF2 (2.5M, random
  16-byte salt) -> AES-GCM key. Both factors are needed to read the words.
- `lockout.<account>.v1` = lockout counter text + HMAC-SHA256 under a Keystore HMAC key (also StrongBox-first).
- Reuses PR #6's (`feat/lock-hardening-2`) `Lockout.kt`/`KeystoreKeys.kt`/tests, with its biometric unlock, idle
  re-lock, clipboard and window-secure parts left out (not H1).

### Lockout
`LockoutPolicy`: 4 free tries, then 30 s, 1 min, 5 min, 15 min, 1 h, 6 h per further failure. The attempt is
recorded *before* the PIN is checked (killing the app does not refund it); a correct PIN resets it. Waits are measured on the
monotonic clock within a boot (wall clock after a reboot, never beyond the wait). Missing/corrupt state counts as 5 failures.
**No wipe-after-N**: auto-wipe would let a stranger destroy an account whose words may not be backed up. Lock screen
and the "show twelve words" prompt show "Too many wrong PINs. Try again in N ..." with a live countdown; the keypad is inert.
Limit: this is a speed bump for UI guessing; root on a live device can use the Keystore and bypass it, which the PBKDF2 cost then covers.

### Migration (existing installs)
v1 files are read as before. On the next **successful PIN unlock**: open v1, seal the words afresh (new salt), wrap, write v2 atomically,
read v2 back and open it with the PIN, require byte-equal plaintext, **only then delete v1**. Any failure (Keystore down,
IO, mismatch) deletes the partial v2 and leaves v1 untouched; the unlock itself still succeeds and the next unlock retries.
A crash between write and delete leaves both; v2 is preferred, a v2 that doesn't open falls back to v1. Accounts predating the
lockout start with a clean counter. A wrong PIN never migrates. `changePin` writes v2 and removes any v1 leftover.

### In-memory lifetime
`SecretStore.loadMnemonicChars` returns a `CharArray`; `PinBox` works on `ByteArray`s zeroed after use (also the PBKDF2 key
material and PIN bytes). `Engine` takes a `CharArray`, derives the seed, and zeroes the words. LockScreen and the reveal screen
use the char variant. **Remaining limits:** the native `sphinxrs` API takes `String`s, so `Engine.seed` is a `String` for the
engine's lifetime and a transient `String` is created per `Account.seedOf` call; the PIN is a Compose `String`; onboarding
holds the generated words as UI state; the reveal screen turns the words into a list of Strings for display. The JVM may
also keep copies the app can't zero.

## Tests
`LockoutPolicyTest` (backoff, clocks), `FileSecretStoreTest` (wrapping, wall-key loss, migration success/failure/crash/verify
mismatch, lockout persistence/tamper, changePin, delete) over fakes of `KeyWall`/`LockoutStore` (`LockFakes.kt`);
`PinBoxTest` still passes.

## Needs manual device testing
Unit tests do not touch the real Keystore. On devices (with and without StrongBox, several API levels/OEMs): fresh install ->
unlock; upgrade from a pre-change build -> v1 migrates on first unlock (check `secret.*.v1` gone, `.v2` present); StrongBox
fallback path; behaviour after OS update / lock-screen change / app data restore to another device (the wall key is
gone -> `KeystoreUnavailable`; the user must restore from the twelve words); lockout countdown, reboot and force-stop during a wait;
keystore errors during migration leaving v1 intact. Note Android auto-backup would restore the files without the key: confirm
`allowBackup`/data-extraction rules exclude them.
