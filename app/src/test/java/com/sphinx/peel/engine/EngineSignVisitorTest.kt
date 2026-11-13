package com.sphinx.peel.engine

import androidx.test.core.app.ApplicationProvider
import com.sphinx.peel.storage.Storage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/// iOS's engine signing test, ported. Needs `libsphinxrs`, which the JVM unit-test classpath does not have: run on a device.
class EngineSignVisitorTest {
    private val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    private fun domainChallenge(host: String) = VisitorChallenge.parse(challengeOf(claims(host = host, key = "domain")))!!

    @Ignore("needs the native libsphinxrs, which the JVM unit-test classpath does not have: run on a device")
    @Test fun domainKeysAreOnePerHost() = runBlocking {
        val engine = Engine(mnemonic, Storage(ApplicationProvider.getApplicationContext(), account = "sign-${System.nanoTime()}"))
        val site = engine.signVisitor(domainChallenge("site.com"), SignInKey.domain)
        val app = engine.signVisitor(domainChallenge("app.example.com"), SignInKey.domain)
        assertEquals("027da5d64331f61260eb8e2b356403446555f525bc7dc35b991ec1447e4f58991f", site.key)
        assertEquals("0278e4feeddc78fae0a8d5d1b5d40c4d56ffa88ac42d13e8e3b2ac480e2b85b243", app.key)
    }

    @Ignore("needs the native libsphinxrs, which the JVM unit-test classpath does not have: run on a device")
    @Test fun rootSignsWithTheAccountKey() = runBlocking {
        val engine = Engine(mnemonic, Storage(ApplicationProvider.getApplicationContext(), account = "sign-${System.nanoTime()}"))
        val signed = engine.signVisitor(VisitorChallenge.parse(ROOT_CHALLENGE)!!, SignInKey.root)
        assertEquals(engine.accountKey, signed.key)
        assertEquals(66, signed.key.length)
        assertTrue(signed.key.startsWith("02") || signed.key.startsWith("03"))
        assertEquals(130, signed.sig.length)
    }
}
