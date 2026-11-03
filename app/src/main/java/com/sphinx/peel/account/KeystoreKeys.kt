package com.sphinx.peel.account

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/// The Keystore-facing seams of the lock. Everything that needs a real
/// Android Keystore sits behind these interfaces, so the logic around
/// them (`FileSecretStore`, `LockoutPolicy`) is unit-tested on the JVM with
/// fakes (`LockFakes` in the tests); the `Android*` classes below are the
/// real thing and are only exercised on a device.

/// A non-authenticated, hardware-backed AES-GCM key that wraps the PIN blob:
/// the Android counterpart of the iOS Keychain "real wall". Usable without
/// the person present (the app must open its account on a push).
interface KeyWall {
    /// `iv(12) | ciphertext+tag`, bound to `aad`.
    fun wrap(plain: ByteArray, aad: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray, aad: ByteArray): ByteArray
    fun delete()
}

/// A key used only to authenticate small pieces of state (the lockout counter).
interface StateSigner {
    fun sign(data: ByteArray): ByteArray
    fun verify(data: ByteArray, mac: ByteArray): Boolean
    fun delete()
}

internal object Keystore {
    const val PROVIDER = "AndroidKeyStore"

    fun store(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun delete(alias: String) {
        runCatching { store().deleteEntry(alias) }
    }

    /// Generates with StrongBox where the device has it (API 28+), falling back
    /// to the TEE-backed Keystore — StrongBox is slower, absent on most phones,
    /// and its absence surfaces as StrongBoxUnavailableException (sometimes
    /// wrapped in a ProviderException).
    fun <T> withStrongBoxFallback(build: (strongBox: Boolean) -> T): T {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return build(true)
            } catch (e: Exception) {
                // fall through to the non-StrongBox attempt
            }
        }
        return build(false)
    }
}

class AndroidKeyWall(private val alias: String) : KeyWall {
    private fun key(): SecretKey {
        val ks = Keystore.store()
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return Keystore.withStrongBoxFallback { strongBox ->
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, Keystore.PROVIDER).apply { init(spec) }.generateKey()
        }
    }

    override fun wrap(plain: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()) // the Keystore picks the IV
        cipher.updateAAD(aad)
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, wrapped.copyOfRange(0, 12)))
        cipher.updateAAD(aad)
        return cipher.doFinal(wrapped, 12, wrapped.size - 12)
    }

    override fun delete() = Keystore.delete(alias)
}

class AndroidStateSigner(private val alias: String) : StateSigner {
    private fun key(): SecretKey {
        (Keystore.store().getKey(alias, null) as? SecretKey)?.let { return it }
        return Keystore.withStrongBoxFallback { strongBox ->
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, Keystore.PROVIDER).apply { init(spec) }.generateKey()
        }
    }

    override fun sign(data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(key()) }.doFinal(data)

    override fun verify(data: ByteArray, mac: ByteArray): Boolean = MessageDigest.isEqual(sign(data), mac)

    override fun delete() = Keystore.delete(alias)
}

/// `LockoutStore` in a file: `state-text \n base64(HMAC(state-text))`.
class FileLockoutStore(private val file: File, private val signer: StateSigner) : LockoutStore {
    override fun load(): LockoutPolicy.State? {
        if (!file.exists()) return null
        try {
            val parts = String(file.readBytes(), Charsets.UTF_8).split("\n")
            if (parts.size != 2) throw LockoutStore.Tampered()
            val mac = android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP)
            if (!signer.verify(parts[0].toByteArray(), mac)) throw LockoutStore.Tampered()
            return LockoutPolicy.decode(parts[0]) ?: throw LockoutStore.Tampered()
        } catch (e: LockoutStore.Tampered) {
            throw e
        } catch (e: Exception) {
            throw LockoutStore.Tampered()
        }
    }

    override fun save(state: LockoutPolicy.State) {
        val text = LockoutPolicy.encode(state)
        val mac = android.util.Base64.encodeToString(signer.sign(text.toByteArray()), android.util.Base64.NO_WRAP)
        FileSecretStore.writeOwnerOnly(file, "$text\n$mac".toByteArray(Charsets.UTF_8))
    }

    override fun clear() {
        file.delete()
        signer.delete()
    }
}

/// Real readings: monotonic and wall clocks, and a boot identifier
/// (`/proc/sys/kernel/random/boot_id`, else `Settings.Global.BOOT_COUNT`).
class AndroidLockoutClock(private val context: Context) : LockoutClock {
    override fun now() = LockoutPolicy.Clock(SystemClock.elapsedRealtime(), System.currentTimeMillis(), bootId())

    private fun bootId(): String =
        runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toString() }.getOrNull()
            ?: ""
}
