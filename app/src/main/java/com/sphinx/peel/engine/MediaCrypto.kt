package com.sphinx.peel.engine

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/// The official apps' attachment encryption: RNCryptor v3 with a
/// password (the media key, from the message's own `mediaKey`/
/// `mediaKeyForSelf` field) — ported from PeelCore's `MediaCrypto.swift`.
/// Decrypt only: this port receives attachments, it doesn't send them
/// yet (`docs/phases/08-attachments.md`), so nothing here ever needs to
/// *generate* a key or encrypt.
object MediaCrypto {
    private const val ROUNDS = 10_000
    private const val HEADER_SIZE = 2 + 8 + 8 + 16
    private const val MAC_SIZE = 32
    private const val KEY_SIZE = 32

    class MalformedException : Exception("the attachment is not in the expected encrypted format")
    class WrongKeyException : Exception("the attachment could not be decrypted with its key")

    /// `sealed`'s layout: `[3, 1]`, an 8-byte encryption salt, an 8-byte
    /// MAC salt, a 16-byte IV, the AES-256-CBC/PKCS7 ciphertext, then a
    /// 32-byte HMAC-SHA256 over everything before it.
    fun decrypt(sealed: ByteArray, key: String): ByteArray {
        if (sealed.size < HEADER_SIZE + 16 + MAC_SIZE || sealed[0] != 3.toByte() || sealed[1] != 1.toByte()) {
            throw MalformedException()
        }
        val encryptionSalt = sealed.copyOfRange(2, 10)
        val macSalt = sealed.copyOfRange(10, 18)
        val iv = sealed.copyOfRange(18, 34)
        val message = sealed.copyOfRange(0, sealed.size - MAC_SIZE)
        val mac = sealed.copyOfRange(sealed.size - MAC_SIZE, sealed.size)

        val expectedMac = hmacSha256(message, derive(key, macSalt))
        if (!MessageDigest.isEqual(expectedMac, mac)) throw WrongKeyException()

        val cipherText = sealed.copyOfRange(HEADER_SIZE, sealed.size - MAC_SIZE)
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derive(key, encryptionSalt), "AES"), IvParameterSpec(iv))
            cipher.doFinal(cipherText)
        } catch (e: Exception) {
            throw WrongKeyException()
        }
    }

    /// PBKDF2-HMAC-SHA1 over the password's own UTF-8 bytes, 32 bytes
    /// out — hand-rolled rather than `SecretKeyFactory`/`PBEKeySpec`
    /// (whose `char[]` password is not guaranteed to turn into the same
    /// bytes CommonCrypto's byte-level `CCKeyDerivationPBKDF` used on the
    /// other side), the same reasoning as this project's PIN key
    /// derivation (`phases/03-restoring.md`). A media key is always
    /// ASCII in practice, so the two would likely agree here too, but
    /// there is no reason to depend on that when the explicit version
    /// costs little.
    private fun derive(password: String, salt: ByteArray): ByteArray {
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(passwordBytes, "HmacSHA1"))
        val hLen = mac.macLength
        val blocks = (KEY_SIZE + hLen - 1) / hLen
        val result = ByteArray(blocks * hLen)
        for (i in 1..blocks) {
            val intBlock = byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte())
            mac.reset()
            mac.update(salt)
            var u = mac.doFinal(intBlock)
            val block = u.copyOf()
            for (round in 2..ROUNDS) {
                mac.reset()
                u = mac.doFinal(u)
                for (k in block.indices) block[k] = (block[k].toInt() xor u[k].toInt()).toByte()
            }
            System.arraycopy(block, 0, result, (i - 1) * hLen, hLen)
        }
        return result.copyOf(KEY_SIZE)
    }

    private fun hmacSha256(data: ByteArray, key: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
