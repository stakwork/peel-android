package com.sphinx.peel.engine

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sphinx.peel.storage.Storage
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/// The sign-in signing vectors, run against the real `libsphinxrs` (docs/phases/43-test-coverage.md): the cases the JVM suite has to `@Ignore`
/// (`EngineSignVisitorTest`, `VisitorSignatureVectorTest`). NOT RUN where this was written (no emulator): compiled only.
@RunWith(AndroidJUnit4::class)
class SigningVectorsInstrumentedTest {
    private val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    private fun engine() = Engine(mnemonic, Storage(ApplicationProvider.getApplicationContext(), account = "sign-${System.nanoTime()}"))

    /// A challenge with these claims (the HMAC is never checked by Peel).
    private fun challenge(host: String, key: String): VisitorChallenge {
        val claims = """{"host":"$host","visitorKey":"$key","nonce":"n0nce","exp":1793800060000}"""
        val body = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray(Charsets.UTF_8))
        return VisitorChallenge.parse("strut-visitor.$body.c2ln")!!
    }

    private fun vectors(): JSONObject {
        val text = javaClass.classLoader!!.getResourceAsStream("wallet-vectors.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        return JSONObject(text)
    }

    /// Every `sign_in_signatures` entry made by Sphinx's own signer: the same seed, account and network sign the same message to the same signature.
    @Test fun strutSignInSignaturesAreReproduced() {
        val all = vectors().getJSONArray("sign_in_signatures")
        var checked = 0
        for (i in 0 until all.length()) {
            val v = all.getJSONObject(i)
            val signer = v.optJSONObject("signer") ?: continue
            val sig = uniffi.sphinxrs.signBytes(
                signer.getString("seed"), signer.getLong("idx").toULong(), "1", signer.getString("network"), v.getString("message").toByteArray(Charsets.UTF_8),
            )
            assertEquals(v.getString("name"), v.getString("sig"), sig)
            assertEquals(v.getString("name"), v.getString("key"), uniffi.sphinxrs.pubkeyFromSeed(signer.getString("seed"), signer.getLong("idx").toULong(), "1", signer.getString("network")))
            checked++
        }
        assertTrue("no vector had a signer", checked > 0)
    }

    @Test fun domainKeysAreOnePerHost() = runBlocking {
        val engine = engine()
        val site = engine.signVisitor(challenge("site.com", "domain"), SignInKey.domain)
        val app = engine.signVisitor(challenge("app.example.com", "domain"), SignInKey.domain)
        // LUD-05 keys of "abandon … about", as iOS's engine test has them.
        assertEquals("027da5d64331f61260eb8e2b356403446555f525bc7dc35b991ec1447e4f58991f", site.key)
        assertEquals("0278e4feeddc78fae0a8d5d1b5d40c4d56ffa88ac42d13e8e3b2ac480e2b85b243", app.key)
        assertNotEquals(site.key, app.key)
    }

    @Test fun rootSignsWithTheAccountKey() = runBlocking {
        val engine = engine()
        val signed = engine.signVisitor(challenge("shop.example.com", "root"), SignInKey.root)
        assertEquals(engine.accountKey, signed.key)
        assertEquals(66, signed.key.length)
        assertTrue(signed.key.startsWith("02") || signed.key.startsWith("03"))
        assertEquals(130, signed.sig.length)
    }
}
