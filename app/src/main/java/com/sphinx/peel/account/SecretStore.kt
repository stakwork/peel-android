package com.sphinx.peel.account

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/// Where an account's twelve words live, locked with the account's PIN
/// (and, on Android, a hardware-backed Keystore key under that; see
/// `docs/phases/17-lock-hardening.md`). Ported from PeelCore's SecretStore.swift,
/// plus the Android-only lockout.
interface SecretStore {
    val hasMnemonic: Boolean

    /// Opens the words. Counts as a PIN attempt (throttled, see `LockoutPolicy`):
    /// throws `LockedOut` while a wait is due and `WrongPin` for a wrong PIN.
    fun loadMnemonic(pin: String): String {
        val words = loadMnemonicChars(pin)
        return try { String(words) } finally { words.fill('\u0000') }
    }

    /// Same as `loadMnemonic` but as a `CharArray` the caller must zero
    /// (`fill('\u0000')`) as soon as it has used it, so the words are not
    /// kept as an immutable `String` longer than needed.
    fun loadMnemonicChars(pin: String): CharArray
    fun save(mnemonic: String, pin: String)
    fun changePin(oldPin: String, newPin: String)
    fun deleteMnemonic()

    /// Milliseconds until another PIN try is allowed; 0 when one is.
    fun lockoutRemainingMs(): Long = 0

}

sealed class SecretStoreFailure(message: String) : Exception(message) {
    object NoAccount : SecretStoreFailure("no account stored")
    object AccountExists : SecretStoreFailure("an account is already stored here")
    object WrongPin : SecretStoreFailure("wrong PIN")
    class LockedOut(val remainingMs: Long) : SecretStoreFailure("too many wrong PINs; try again in ${LockoutPolicy.describe(remainingMs)}")
    object KeystoreUnavailable : SecretStoreFailure("this device's secure key store could not be used")
}

/// The words sealed under a key derived from the PIN, byte for byte the
/// same format PeelCore's `PINBox` writes on the Mac and iPhone:
/// `version(1) | salt(16) | iterations(4, big-endian) | AES-GCM nonce(12)+ciphertext+tag(16)`.
internal object PinBox {
    private const val VERSION: Byte = 1
    private const val SALT_LENGTH = 16
    private const val NONCE_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128
    private const val HEADER_LENGTH = 1 + SALT_LENGTH + 4

    /// About 0.4 s on an M-series Mac; this phone's cost will differ, but
    /// the derivation is meant to be slow on purpose. Fixed for this
    /// version: a blob claiming another count is refused rather than obeyed.
    internal const val ITERATIONS = 2_500_000

    private val random = SecureRandom()

    fun seal(mnemonic: String, pin: String, iterations: Int = ITERATIONS): ByteArray {
        val bytes = mnemonic.toByteArray(Charsets.UTF_8)
        return try { seal(bytes, pin, iterations) } finally { bytes.fill(0) }
    }

    fun seal(plain: ByteArray, pin: String, iterations: Int = ITERATIONS): ByteArray {
        val salt = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
        val key = derive(pin, salt, iterations)
        val nonce = ByteArray(NONCE_LENGTH).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, nonce))
        val combined = nonce + cipher.doFinal(plain)

        val out = ByteBuffer.allocate(HEADER_LENGTH + combined.size)
        out.put(VERSION)
        out.put(salt)
        out.putInt(iterations)
        out.put(combined)
        return out.array()
    }

    fun open(sealed: ByteArray, pin: String, iterations: Int = ITERATIONS): String {
        val bytes = openBytes(sealed, pin, iterations)
        return try { String(bytes, Charsets.UTF_8) } finally { bytes.fill(0) }
    }

    /// The plaintext as bytes the caller must zero.
    fun openBytes(sealed: ByteArray, pin: String, iterations: Int = ITERATIONS): ByteArray {
        val key = deriveKey(sealed, pin, iterations)
        return try { openWithKeyBytes(sealed, key, iterations) } finally { key.encoded.fill(0) }
    }

    /// The AES key a PIN derives for this blob (the slow step).
    fun deriveKey(sealed: ByteArray, pin: String, iterations: Int = ITERATIONS): SecretKeySpec {
        val salt = parse(sealed, iterations).salt
        return derive(pin, salt, iterations)
    }

    private class Parsed(val salt: ByteArray, val nonce: ByteArray, val ciphertext: ByteArray)

    private fun parse(sealed: ByteArray, iterations: Int): Parsed {
        if (sealed.size <= HEADER_LENGTH || sealed[0] != VERSION) {
            throw IllegalStateException("the stored account has an unknown format")
        }
        val buffer = ByteBuffer.wrap(sealed)
        buffer.get() // version, already checked
        val salt = ByteArray(SALT_LENGTH).also { buffer.get(it) }
        if (buffer.int != iterations) throw IllegalStateException("the stored account has an unknown format")
        val combined = ByteArray(sealed.size - HEADER_LENGTH).also { buffer.get(it) }
        if (combined.size <= NONCE_LENGTH) throw IllegalStateException("the stored account has an unknown format")
        return Parsed(salt, combined.copyOfRange(0, NONCE_LENGTH), combined.copyOfRange(NONCE_LENGTH, combined.size))
    }

    /// Opens with an already-derived key (the PIN's); a wrong key fails authentication as a wrong PIN.
    fun openWithKey(sealed: ByteArray, key: SecretKeySpec, iterations: Int = ITERATIONS): String {
        val bytes = openWithKeyBytes(sealed, key, iterations)
        return try { String(bytes, Charsets.UTF_8) } finally { bytes.fill(0) }
    }

    fun openWithKeyBytes(sealed: ByteArray, key: SecretKeySpec, iterations: Int = ITERATIONS): ByteArray {
        val parsed = parse(sealed, iterations)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, parsed.nonce))
            cipher.doFinal(parsed.ciphertext)
        } catch (e: Exception) {
            // A wrong PIN derives a wrong key, which fails authentication.
            throw SecretStoreFailure.WrongPin
        }
    }

    /// RFC 8018 PBKDF2-HMAC-SHA256, by hand, rather than
    /// `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")`: Android's
    /// own implementation of that algorithm name is measured several
    /// times slower than iOS's CommonCrypto for the same iteration count
    /// (a 6-second unlock here against a sub-second one there, for the
    /// identical 2,500,000) — object overhead inside its per-iteration
    /// loop, not anything about the algorithm itself. PBKDF2's output is
    /// fixed by the spec, not the provider, so this produces the exact
    /// same key, only without that overhead. PINs here are always six
    /// ASCII digits, so the password-to-bytes step has no encoding
    /// ambiguity to worry about (UTF-8, Latin-1 and a straight char cast
    /// all agree on the digits '0'–'9').
    internal fun derive(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): SecretKeySpec {
        val mac = Mac.getInstance("HmacSHA256")
        val pinBytes = pin.toByteArray(Charsets.UTF_8)
        mac.init(SecretKeySpec(pinBytes, "HmacSHA256"))
        pinBytes.fill(0)
        val hashLength = mac.macLength
        val block = ByteArray(salt.size + 4)
        salt.copyInto(block)
        block[block.size - 1] = 1 // one 256-bit block is enough for a 256-bit key
        var u = mac.doFinal(block)
        val result = u.copyOf()
        for (iteration in 1 until iterations) {
            u = mac.doFinal(u)
            for (i in 0 until hashLength) result[i] = (result[i].toInt() xor u[i].toInt()).toByte()
        }
        return SecretKeySpec(result, "AES")
    }
}

/// The words sealed under the PIN, in this app's private storage, with a
/// hardware-backed Keystore layer around the PIN blob and a throttle on
/// wrong PINs. Layout (all in `filesDir`, owner-only):
///
///  * `secret.<account>.v2` — `0x02 | AES-GCM(Keystore wall key, aad) of the v1 PinBox blob`.
///  * `secret.<account>.v1` — the legacy bare PinBox blob; migrated to v2 on
///    successful PIN unlock (v2 is written atomically and verified by a round
///    trip before v1 is deleted; see `migrateLegacy`).
///  * `lockout.<account>.v1` — the throttle counter, HMAC'd by a Keystore key.
///
/// The Keystore wall is the Android equivalent of iOS's Keychain "real
/// wall": the PIN blob copied off the device (backup, forensic copy of
/// /data without the secure element) cannot even be brute-forced offline,
/// because it is ciphertext under a key that never leaves the TEE/StrongBox.
/// The PIN's PBKDF2 still guards the words against anyone who can USE the
/// Keystore (root on a live device).
class FileSecretStore internal constructor(
    private val dir: File,
    private val account: String,
    private val wall: KeyWall,
    private val lockoutStore: LockoutStore,
    private val clock: LockoutClock,
    private val iterations: Int = PinBox.ITERATIONS,
) : SecretStore {
    constructor(context: Context, account: String = "default") : this(
        context.filesDir, account,
        AndroidKeyWall("peel.wall.$account"),
        FileLockoutStore(File(context.filesDir, "lockout.$account.v1"), AndroidStateSigner("peel.lockout.$account")),
        AndroidLockoutClock(context.applicationContext),
    )

    private val legacyFile = File(dir, "secret.$account.v1")
    private val file = File(dir, "secret.$account.v2")
    private val aad = "peel.secret.$account".toByteArray()
    private val lock = Any()

    override val hasMnemonic: Boolean get() = file.exists() || legacyFile.exists()

    // --- throttle -------------------------------------------------------

    private fun state(now: LockoutPolicy.Clock): LockoutPolicy.State = try {
        lockoutStore.load() ?: when {
        // An account that predates the throttle has no state yet: start clean.
        legacyFile.exists() -> LockoutPolicy.State.CLEAN
        hasMnemonic -> LockoutPolicy.tampered(now)
        else -> LockoutPolicy.State.CLEAN
    }
    } catch (e: LockoutStore.Tampered) {
        LockoutPolicy.tampered(now)
    }

    override fun lockoutRemainingMs(): Long = synchronized(lock) {
        val now = clock.now()
        LockoutPolicy.remainingMs(state(now), now)
    }

    /// The attempt's gate: refuses while a wait is due, else records the
    /// attempt as a failure BEFORE anything is checked, so killing the app
    /// mid-check never refunds it. `succeeded` clears it.
    private fun beginAttempt() = synchronized(lock) {
        val now = clock.now()
        val current = state(now)
        val remaining = LockoutPolicy.remainingMs(current, now)
        if (remaining > 0) throw SecretStoreFailure.LockedOut(remaining)
        lockoutStore.save(LockoutPolicy.registerAttempt(current, now))
    }

    private fun succeeded() = synchronized(lock) { lockoutStore.save(LockoutPolicy.State.CLEAN) }

    // --- reading and writing the blob ------------------------------------

    /// The inner v1 PinBox blob of the v2 file, through the Keystore wall.
    private fun readInner(): ByteArray {
        val bytes = file.readBytes()
        if (bytes.isEmpty() || bytes[0] != WRAPPED_VERSION) throw IllegalStateException("the stored account has an unknown format")
        return try {
            wall.unwrap(bytes.copyOfRange(1, bytes.size), aad)
        } catch (e: Exception) {
            throw SecretStoreFailure.KeystoreUnavailable
        }
    }

    private fun writeInner(inner: ByteArray) {
        val wrapped = try {
            byteArrayOf(WRAPPED_VERSION) + wall.wrap(inner, aad)
        } catch (e: Exception) {
            throw SecretStoreFailure.KeystoreUnavailable
        }
        writeOwnerOnly(file, wrapped)
    }

    /// Legacy (v1, bare PinBox: PIN-only, copyable and brute-forceable
    /// offline) to v2 (Keystore-wrapped), run on a successful PIN unlock
    /// with the plaintext already in hand. Never loses the account:
    ///  1. a fresh PinBox blob (new salt) is wrapped and written atomically to v2;
    ///  2. v2 is read back, unwrapped and opened with the PIN, and must give
    ///     back exactly the words;
    ///  3. only then is v1 deleted.
    /// Any failure (Keystore down, IO, mismatch) discards the v2 attempt and
    /// leaves v1 untouched; the next unlock tries again. A crash between 1
    /// and 3 leaves both; `readInner` prefers a v2 that opens and the next
    /// unlock removes v1.
    private fun migrateLegacy(plain: ByteArray, pin: String) {
        if (!legacyFile.exists()) return
        try {
            writeInner(PinBox.seal(plain, pin, iterations))
            val back = PinBox.openBytes(readInner(), pin, iterations)
            val same = try { MessageDigest.isEqual(back, plain) } finally { back.fill(0) }
            if (same) legacyFile.delete() else file.delete()
        } catch (e: Exception) {
            runCatching { file.delete() }
        }
    }

    override fun loadMnemonicChars(pin: String): CharArray {
        if (!hasMnemonic) throw SecretStoreFailure.NoAccount
        beginAttempt()
        var usedLegacy = false
        val plain: ByteArray = run {
            if (file.exists()) {
                try {
                    return@run PinBox.openBytes(readInner(), pin, iterations)
                } catch (e: Exception) {
                    // A v2 that does not open (wrong PIN, or the Keystore key is
                    // gone) beside a v1 (an interrupted migration): v1 still holds
                    // the account, so fall through to it.
                    if (!legacyFile.exists()) throw e
                }
            }
            usedLegacy = true
            PinBox.openBytes(legacyFile.readBytes(), pin, iterations)
        }
        try {
            succeeded()
            if (usedLegacy) migrateLegacy(plain, pin) else if (legacyFile.exists()) legacyFile.delete()
            val buffer = Charsets.UTF_8.decode(java.nio.ByteBuffer.wrap(plain))
            val chars = CharArray(buffer.remaining()).also { buffer.get(it) }
            buffer.array().fill('\u0000')
            return chars
        } finally {
            plain.fill(0)
        }
    }

    override fun save(mnemonic: String, pin: String) {
        require(Pin.isValid(pin)) { "the PIN must be ${Pin.LENGTH} digits" }
        if (hasMnemonic) throw SecretStoreFailure.AccountExists
        synchronized(lock) { lockoutStore.save(LockoutPolicy.State.CLEAN) }
        writeInner(PinBox.seal(mnemonic, pin, iterations))
    }

    override fun changePin(oldPin: String, newPin: String) {
        require(Pin.isValid(newPin)) { "the PIN must be ${Pin.LENGTH} digits" }
        val words = loadMnemonicChars(oldPin)
        val bytes = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(words))
        val plain = ByteArray(bytes.remaining()).also { bytes.get(it) }
        try {
            writeInner(PinBox.seal(plain, newPin, iterations))
            // A v1 leftover (migration unavailable) must not keep the old PIN alive.
            legacyFile.delete()
        } finally {
            words.fill('\u0000'); plain.fill(0); bytes.array().fill(0)
        }
    }

    override fun deleteMnemonic() {
        file.delete()
        legacyFile.delete()
        runCatching { lockoutStore.clear() }
        runCatching { wall.delete() }
    }

    /// Writes so the file is never, even briefly, world-readable — a fresh
    /// temp file is created with owner-only permissions as part of its own
    /// creation, written, then moved onto `target` in one filesystem op: the
    /// Android equivalent of iOS's own `open()` with `O_CREAT` and mode
    /// `0600` in one call (`SecretStore.swift`'s `FileSecretStore.write`).
    private fun writeOwnerOnly(target: File, bytes: ByteArray) = Companion.writeOwnerOnly(target, bytes)

    internal companion object {
        const val WRAPPED_VERSION: Byte = 2

        fun writeOwnerOnly(target: File, bytes: ByteArray) {
            val ownerOnly = java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
            )
            val tmp = java.nio.file.Files.createTempFile(target.parentFile!!.toPath(), target.name, ".tmp", ownerOnly)
            java.nio.file.Files.write(tmp, bytes)
            java.nio.file.Files.move(
                tmp, target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }
    }
}
