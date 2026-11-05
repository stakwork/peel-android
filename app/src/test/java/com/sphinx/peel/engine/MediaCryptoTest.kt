package com.sphinx.peel.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCryptoTest {
    /// A sealed blob built by a from-scratch Python RNCryptor-v3
    /// implementation (PBKDF2-HMAC-SHA1 + AES-256-CBC/PKCS7 +
    /// HMAC-SHA256, Python's own `cryptography`/`hashlib`, not this
    /// Kotlin code and not CommonCrypto) — the point is a mistake here
    /// can't also be in the oracle it's checked against, same reasoning
    /// as `PersonHueTest`.
    private val key = "abc123def456abc123def456abc123d"
    private val plainText = "Hello Peel attachment test, this is the plaintext payload!"
    private val sealedHex = "0301f4cf495f1f737573ef0a8fead509063884fa031ddadc32ea26dec28e4b8b7bd" +
        "944eeec86c337f973c618e2cd3452ab5b9063a9a9a886d293b01bc9f872ab80851580e7ee7fa2cd64ad306e8a71" +
        "6d43300c5c5776be9b7e14e3454b52ad01baafac9e4bb86b0a0407c0dd3c52a0ff81104ebec1a8579b56429ed65" +
        "9c4cc18a281"

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((hex[it * 2].digitToInt(16) shl 4) + hex[it * 2 + 1].digitToInt(16)).toByte() }

    @Test
    fun `decrypts a sealed blob built by an independent Python implementation`() {
        val plain = MediaCrypto.decrypt(hexToBytes(sealedHex), key)
        assertArrayEquals(plainText.toByteArray(Charsets.UTF_8), plain)
    }

    @Test
    fun `the wrong key is refused, not silently wrong bytes`() {
        assertThrows(MediaCrypto.WrongKeyException::class.java) {
            MediaCrypto.decrypt(hexToBytes(sealedHex), "not-the-right-key-not-the-right-key")
        }
    }

    @Test
    fun `too short to even hold a header is malformed, not an index crash`() {
        assertThrows(MediaCrypto.MalformedException::class.java) {
            MediaCrypto.decrypt(ByteArray(10), key)
        }
    }

    @Test
    fun `the wrong version byte is malformed`() {
        val tampered = hexToBytes(sealedHex)
        tampered[0] = 9
        assertThrows(MediaCrypto.MalformedException::class.java) {
            MediaCrypto.decrypt(tampered, key)
        }
    }

    @Test
    fun `encrypt then decrypt returns the original bytes`() {
        // decrypt is already checked against an independent (Python)
        // implementation above; encrypt reuses the same key derivation
        // and MAC it does, so a round trip through decrypt is a real
        // check of encrypt's own byte layout, not just self-agreement —
        // decrypt rigidly expects that exact layout regardless of what
        // produced it.
        val plain = "Some bytes to round-trip through RNCryptor v3.".toByteArray(Charsets.UTF_8)
        val sealed = MediaCrypto.encrypt(plain, key)
        assertArrayEquals(plain, MediaCrypto.decrypt(sealed, key))
    }

    @Test
    fun `encrypt never produces the same bytes twice for the same plaintext and key`() {
        val plain = "repeat me".toByteArray(Charsets.UTF_8)
        val first = MediaCrypto.encrypt(plain, key)
        val second = MediaCrypto.encrypt(plain, key)
        org.junit.Assert.assertFalse(first.contentEquals(second))
    }

    @Test
    fun `newKey is 64 lowercase hex chars and round-trips`() {
        val key = MediaCrypto.newKey()
        assertTrue(Regex("^[0-9a-f]{64}$").matches(key))
        val plain = "round trip".toByteArray()
        assertArrayEquals(plain, MediaCrypto.decrypt(MediaCrypto.encrypt(plain, key), key))
    }
}
