package com.sphinx.peel.engine

import androidx.test.core.app.ApplicationProvider
import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /// iOS `theEngineSendsItsAddressWithItsKeyAlone` (PR #557): with the account's key the engine sends the account's own address,
    /// the contact info the core reported: none before registering, none with a domain's key, none that is another key's.
    @Ignore("needs the native libsphinxrs, which the JVM unit-test classpath does not have: run on a device")
    @Test fun theEngineSendsItsAddressWithItsKeyAlone() = runBlocking {
        val storage = Storage(ApplicationProvider.getApplicationContext(), account = "sign-${System.nanoTime()}")
        val engine = Engine(mnemonic, storage)
        val challenge = VisitorChallenge.parse(ROOT_CHALLENGE)!!
        assertNull(engine.signVisitor(challenge, SignInKey.root).address)

        val lsp = "03" + "ab".repeat(32)
        val own = "${engine.accountKey}_${lsp}_1099511627776"
        storage.apply(Storage.Commit(settings = mapOf(Setting.CONTACT_INFO to own.uppercase())))
        val signed = engine.signVisitor(challenge, SignInKey.root)
        assertEquals(own, signed.address)
        assertEquals(own, signed.toJson().getString("address"))
        val domain = engine.signVisitor(VisitorChallenge.parse(challengeOf(claims(key = "domain")))!!, SignInKey.domain)
        assertNull(domain.address)

        storage.apply(Storage.Commit(settings = mapOf(Setting.CONTACT_INFO to "${"02" + "cd".repeat(32)}_${lsp}_1")))
        assertNull(engine.signVisitor(challenge, SignInKey.root).address)
        storage.apply(Storage.Commit(settings = mapOf(Setting.CONTACT_INFO to "${engine.accountKey}_$lsp")))
        assertNull(engine.signVisitor(challenge, SignInKey.root).address)
    }
}
