package com.sphinx.peel.engine

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.assertEquals
import org.junit.Test

/// Why `Engine` may take its Sphinx seed as the first 64 hex characters of the BIP39 seed (one PBKDF2, as iOS does):
/// sphinx-ffi documents `mnemonic_to_seed` as the first 32 bytes of the 64-byte BIP39 seed, and sphinx-rs's own
/// `mnemonic_to_seed` vector for this mnemonic is that prefix. Pure JDK: PBKDF2-HMAC-SHA512, 2048 rounds, salt "mnemonic".
class SeedDerivationTest {
    private val mnemonic = "absurd amount doctor acoustic avoid letter advice cage absurd amount doctor adjust"

    private fun bip39Seed(words: String): String {
        val spec = PBEKeySpec(words.toCharArray(), "mnemonic".toByteArray(Charsets.UTF_8), 2048, 512)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded.joinToString("") { "%02x".format(it) }
    }

    @Test fun theSphinxSeedIsTheFirst32BytesOfTheBip39Seed() {
        val full = bip39Seed(mnemonic)
        assertEquals(128, full.length)
        assertEquals("02592d423c4e7c6d18947713b47f7957c9f1ddd0a196d649d777cd91469c0fb3", full.take(64))
    }
}
