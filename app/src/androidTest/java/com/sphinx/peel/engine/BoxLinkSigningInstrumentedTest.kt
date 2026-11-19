package com.sphinx.peel.engine

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sphinx.peel.storage.Storage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/// The engine signs a box's link challenge with the account's own key (child 0), over the challenge's UTF-8 bytes, exactly as a
/// visitor's sign-in with it is signed (docs/phases/45-box-link.md; iOS `theEngineSignsABoxsChallengeAsStrutChecksIt`, PR #574).
/// The vector is iOS's: "abandon … about"'s key and its signature over the box.example.com challenge, made by the core and checked
/// with strut's own `ln-message` verifier (`VERIFIERS["ln-message"]`, the one `POST /people/link` runs). Needs the native core.
@RunWith(AndroidJUnit4::class)
class BoxLinkSigningInstrumentedTest {
    private val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    private val text =
        "strut-link.eyJhY3RvciI6ImFkYS0zZjlhIiwiZXhwIjoxNzk1MDAwNjAwMDAwLCJob3N0IjoiYm94LmV4YW1wbGUuY29tIiwibmFtZSI6IkFkYSBMb3ZlbGFjZSIsIm5vbmNlIjoiMDAxMTIyMzM0NDU1NjY3Nzg4OTlhYWJiY2NkZGVlZmYifQ.Xk2bYWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXo"
    private val key = "03216967587493cc956be284fb14c9a5f102d1030b61694d622f368a554828e766"
    private val sig =
        "20e791ec9f2ec2be6dd6ee0d46564ef8ffc522f3917ff9a77633d63172013bde175b254bf01ef70ea4eea4ef84277d8c66434ed6b8cbb7a386c6ffaac9cfaf1f6d"

    @Test fun theEngineSignsABoxsChallengeAsStrutChecksIt() = runBlocking {
        val engine = Engine(mnemonic, Storage(ApplicationProvider.getApplicationContext(), account = "box-${System.nanoTime()}"))
        val signed = engine.signBoxLink(BoxChallenge.parse(text)!!)
        assertEquals(engine.accountKey, signed.key)
        assertEquals(key, signed.key)
        assertEquals(sig, signed.sig)
        // The same as a visitor's sign-in with the account's key over the same bytes: one path for both.
        val visitor = VisitorChallenge(text, "box.example.com", SignInKey.root, "n", 1)
        assertEquals(signed.sig, engine.signVisitor(visitor, SignInKey.root).sig)
        // Another challenge, another signature.
        val other = BoxChallenge.parse(text.replace(".Xk2b", ".Xk2c"))!!
        assertNotEquals(sig, engine.signBoxLink(other).sig)
    }
}
