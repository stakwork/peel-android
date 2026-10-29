package com.sphinx.peel.account

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/// Where an account's twelve words live, locked with the account's PIN.
/// Ported from PeelCore's SecretStore.swift.
interface SecretStore {
    val hasMnemonic: Boolean
    fun loadMnemonic(pin: String): String
    fun save(mnemonic: String, pin: String)
    fun changePin(oldPin: String, newPin: String)
    fun deleteMnemonic()
}

sealed class SecretStoreFailure(message: String) : Exception(message) {
    object NoAccount : SecretStoreFailure("no account stored")
    object AccountExists : SecretStoreFailure("an account is already stored here")
    object WrongPin : SecretStoreFailure("wrong PIN")
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

    fun seal(mnemonic: String, pin: String): ByteArray {
        val salt = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
        val key = derive(pin, salt)
        val nonce = ByteArray(NONCE_LENGTH).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, nonce))
        val combined = nonce + cipher.doFinal(mnemonic.toByteArray(Charsets.UTF_8))

        val out = ByteBuffer.allocate(HEADER_LENGTH + combined.size)
        out.put(VERSION)
        out.put(salt)
        out.putInt(ITERATIONS)
        out.put(combined)
        return out.array()
    }

    fun open(sealed: ByteArray, pin: String): String {
        if (sealed.size <= HEADER_LENGTH || sealed[0] != VERSION) {
            throw IllegalStateException("the stored account has an unknown format")
        }
        val buffer = ByteBuffer.wrap(sealed)
        buffer.get() // version, already checked
        val salt = ByteArray(SALT_LENGTH).also { buffer.get(it) }
        val iterations = buffer.int
        if (iterations != ITERATIONS) {
            throw IllegalStateException("the stored account has an unknown format")
        }
        val combined = ByteArray(sealed.size - HEADER_LENGTH).also { buffer.get(it) }
        val nonce = combined.copyOfRange(0, NONCE_LENGTH)
        val ciphertext = combined.copyOfRange(NONCE_LENGTH, combined.size)

        val key = derive(pin, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, nonce))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
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
    internal fun derive(pin: String, salt: ByteArray): SecretKeySpec {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pin.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hashLength = mac.macLength
        val block = ByteArray(salt.size + 4)
        salt.copyInto(block)
        block[block.size - 1] = 1 // one 256-bit block is enough for a 256-bit key
        var u = mac.doFinal(block)
        val result = u.copyOf()
        for (iteration in 1 until ITERATIONS) {
            u = mac.doFinal(u)
            for (i in 0 until hashLength) result[i] = (result[i].toInt() xor u[i].toInt()).toByte()
        }
        return SecretKeySpec(result, "AES")
    }
}

/// The words sealed under the PIN, in this app's private storage: on
/// Android that directory is already sandboxed to this app (not readable by
/// another app without root), the rough equivalent of the Mac's
/// access-group-scoped data-protection Keychain. The PIN is the real wall:
/// see `PinBox`.
class FileSecretStore(context: Context, account: String = "default") : SecretStore {
    private val file = File(context.filesDir, "secret.$account.v1")

    override val hasMnemonic: Boolean get() = file.exists()

    override fun loadMnemonic(pin: String): String {
        if (!file.exists()) throw SecretStoreFailure.NoAccount
        return PinBox.open(file.readBytes(), pin)
    }

    override fun save(mnemonic: String, pin: String) {
        require(Pin.isValid(pin)) { "the PIN must be ${Pin.LENGTH} digits" }
        if (file.exists()) throw SecretStoreFailure.AccountExists
        writeOwnerOnly(PinBox.seal(mnemonic, pin))
    }

    override fun changePin(oldPin: String, newPin: String) {
        require(Pin.isValid(newPin)) { "the PIN must be ${Pin.LENGTH} digits" }
        val mnemonic = loadMnemonic(oldPin)
        writeOwnerOnly(PinBox.seal(mnemonic, newPin))
    }

    override fun deleteMnemonic() {
        file.delete()
    }

    /// Writes the sealed mnemonic so it is never, even briefly,
    /// world-readable — unlike writing the bytes first and chmod-ing
    /// after, which leaves a window with default (not owner-only)
    /// permissions if the process dies in between. A fresh temp file is
    /// created with owner-only permissions as part of its own creation
    /// (`PosixFilePermissions.asFileAttribute`, applied atomically by the
    /// OS at `createTempFile`, not after), written to, then moved onto
    /// `file` in one filesystem op — the Android equivalent of iOS's own
    /// `open()` with `O_CREAT` and mode `0600` in one call
    /// (`SecretStore.swift`'s `FileSecretStore.write`).
    private fun writeOwnerOnly(bytes: ByteArray) {
        val ownerOnly = java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
        )
        val tmp = java.nio.file.Files.createTempFile(file.parentFile!!.toPath(), file.name, ".tmp", ownerOnly)
        java.nio.file.Files.write(tmp, bytes)
        java.nio.file.Files.move(
            tmp, file.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
    }
}
