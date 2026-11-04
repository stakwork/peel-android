package com.sphinx.peel.account

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/// JVM stand-ins for the Keystore seams (`KeystoreKeys.kt`): same contracts, software keys.
class FakeWall : KeyWall {
    private var key: SecretKey? = null
    var broken = false
    private fun key() = key ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }

    override fun wrap(plain: ByteArray, aad: ByteArray): ByteArray {
        if (broken) throw IllegalStateException("keystore down")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return iv + c.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray, aad: ByteArray): ByteArray {
        if (broken) throw IllegalStateException("keystore down")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, wrapped.copyOfRange(0, 12)))
        c.updateAAD(aad)
        return c.doFinal(wrapped, 12, wrapped.size - 12)
    }

    override fun delete() { key = null }
}

class FakeLockoutStore : LockoutStore {
    var state: LockoutPolicy.State? = null
    var tampered = false
    override fun load(): LockoutPolicy.State? = if (tampered) throw LockoutStore.Tampered() else state
    override fun save(state: LockoutPolicy.State) { tampered = false; this.state = state }
    override fun clear() { state = null; tampered = false }
}

class FakeVault : BiometricKeyVault {
    var key: SecretKey? = null
    override fun newEncryptCipher(): Cipher {
        key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    override fun decryptCipher(iv: ByteArray): Cipher {
        val k = key ?: throw IllegalStateException("invalidated")
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv)) }
    }

    override fun delete() { key = null }
}

class TestClock(var elapsed: Long = 1_000, var wall: Long = 1_700_000_000_000, var boot: String = "boot-1") : LockoutClock {
    override fun now() = LockoutPolicy.Clock(elapsed, wall, boot)
    fun advance(ms: Long) { elapsed += ms; wall += ms }
}
