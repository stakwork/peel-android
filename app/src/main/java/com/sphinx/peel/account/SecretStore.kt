package com.sphinx.peel.account

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
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
    private const val ITERATIONS = 2_500_000

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

    private fun derive(pin: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
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

    private fun writeOwnerOnly(bytes: ByteArray) {
        file.writeBytes(bytes)
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }
}
