package com.sphinx.peel.engine

import com.sphinx.peel.engine.AutoDownloadPolicy.Decision
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AttachmentRulesTest {
    // --- error classification ---

    @Test
    fun `network trouble is transient`() {
        for (e in listOf(IOException("reset"), SocketTimeoutException(), UnknownHostException("memes"))) {
            assertEquals(AttachmentFailure.Kind.TRANSIENT, AttachmentFailure.classify(e))
        }
    }

    @Test
    fun `server trouble and an unauthorized answer after re-sign-in are transient`() {
        for (status in listOf(500, 502, 503, 599, 404, 408, 429, 401, 403)) {
            assertEquals("HTTP $status", AttachmentFailure.Kind.TRANSIENT, AttachmentFailure.classify(MemesServer.RefusedException(status, "the download")))
        }
        assertEquals(AttachmentFailure.Kind.TRANSIENT, AttachmentFailure.classify(MemesServer.UnreadableException("sign-in")))
    }

    @Test
    fun `other refusals, redirects and our own checks are permanent`() {
        for (status in listOf(400, 410, 413, 301, 302, 0)) {
            assertEquals("HTTP $status", AttachmentFailure.Kind.PERMANENT, AttachmentFailure.classify(MemesServer.RefusedException(status, "the download")))
        }
        val permanent = listOf(
            IllegalStateException("This attachment is on an untrusted host."),
            MemesServer.TooLargeException(1),
            MediaCrypto.WrongKeyException(),
            MediaCrypto.MalformedException(),
            RuntimeException("x"),
        )
        for (e in permanent) assertEquals(e.toString(), AttachmentFailure.Kind.PERMANENT, AttachmentFailure.classify(e))
    }

    @Test
    fun `a permanent cause never shows a raw exception message`() {
        assertEquals("It could not be read.", AttachmentFailure.describe(RuntimeException("java.lang.Secret at line 3")))
        assertEquals("It could not be decrypted.", AttachmentFailure.describe(MediaCrypto.WrongKeyException()))
        assertEquals("This attachment has no key.", AttachmentFailure.describe(IllegalStateException("This attachment has no key.")))
    }

    // --- auto download policy ---

    @Test
    fun `with the setting off nothing is fetched, for every kind and size`() {
        for (kind in AttachmentKind.values()) for (size in listOf(null, 0L, 1L, 50_000_000L)) {
            assertEquals(Decision.CachedOnly, AutoDownloadPolicy.decide(kind, size, autoOn = false))
        }
    }

    @Test
    fun `with the setting on images go to the image limit, video and voice to the larger one`() {
        assertEquals(Decision.FetchNow(10_000_000), AutoDownloadPolicy.decide(AttachmentKind.IMAGE, null, true))
        assertEquals(Decision.FetchNow(25_000_000), AutoDownloadPolicy.decide(AttachmentKind.VIDEO, null, true))
        assertEquals(Decision.FetchNow(25_000_000), AutoDownloadPolicy.decide(AttachmentKind.AUDIO, null, true))
        assertEquals(Engine.AUTO_DOWNLOAD_IMAGE_LIMIT, 10_000_000)
        assertEquals(Engine.AUTO_DOWNLOAD_LIMIT, 25_000_000)
    }

    @Test
    fun `a declared size over the cap is held, at the cap it is fetched`() {
        assertEquals(Decision.FetchNow(10_000_000), AutoDownloadPolicy.decide(AttachmentKind.IMAGE, 10_000_000L, true))
        assertEquals(Decision.Hold, AutoDownloadPolicy.decide(AttachmentKind.IMAGE, 10_000_001L, true))
        assertEquals(Decision.FetchNow(25_000_000), AutoDownloadPolicy.decide(AttachmentKind.VIDEO, 25_000_000L, true))
        assertEquals(Decision.Hold, AutoDownloadPolicy.decide(AttachmentKind.AUDIO, 25_000_001L, true))
    }

    @Test
    fun `files and pdfs are always held for a tap`() {
        for (size in listOf(null, 1L, 100_000_000L)) {
            assertEquals(Decision.Hold, AutoDownloadPolicy.decide(AttachmentKind.FILE, size, true))
            assertEquals(Decision.Hold, AutoDownloadPolicy.decide(AttachmentKind.PDF, size, true))
        }
    }

    // --- retry backoff ---

    @Test
    fun `retries wait 2s, 10s, 30s and then stop`() {
        assertEquals(2_000L, RetryBackoff.delayMs(0))
        assertEquals(10_000L, RetryBackoff.delayMs(1))
        assertEquals(30_000L, RetryBackoff.delayMs(2))
        assertNull(RetryBackoff.delayMs(3))
        assertEquals(3, RetryBackoff.DELAYS_MS.size)
    }

    // --- cache naming ---

    @Test
    fun `the cache name is deterministic and hex`() {
        val a = AttachmentCache.fileKey("muid1", "key1")
        assertEquals(a, AttachmentCache.fileKey("muid1", "key1"))
        assertEquals(64, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `the same muid with another key is another file`() {
        assertNotEquals(AttachmentCache.fileKey("muid1", "key1"), AttachmentCache.fileKey("muid1", "key2"))
        assertNotEquals(AttachmentCache.fileKey("muid1", "key1"), AttachmentCache.fileKey("muid2", "key1"))
        // The separator keeps ("ab", "c") apart from ("a", "bc").
        assertNotEquals(AttachmentCache.fileKey("ab", "c"), AttachmentCache.fileKey("a", "bc"))
    }

    @Test
    fun `a cache name has no path separators and stays in its directory`() {
        val dir = File("/cache/attachments/x")
        for (muid in listOf("../../etc/passwd", "a/b", "..\\x")) {
            val file = AttachmentCache.file(dir, muid, "../key")
            assertEquals(dir, file.parentFile)
            assertFalse(file.name.contains('/') || file.name.contains('\\') || file.name.contains(".."))
            assertEquals(dir, AttachmentCache.nameFile(dir, muid, "k").parentFile)
        }
    }

    @Test
    fun `a stored file is read back from the same name and removed with its sidecar`() {
        val dir = java.nio.file.Files.createTempDirectory("peel-cache").toFile()
        try {
            AttachmentCache.store(dir, "m", "k", byteArrayOf(1, 2, 3), "photo.jpg")
            assertArrayEquals(byteArrayOf(1, 2, 3), AttachmentCache.file(dir, "m", "k").readBytes())
            assertEquals("photo.jpg", AttachmentCache.nameFile(dir, "m", "k").readText())
            assertFalse(AttachmentCache.file(dir, "m", "other").exists())
            AttachmentCache.store(dir, "m", "k", byteArrayOf(9), null)
            assertArrayEquals(byteArrayOf(9), AttachmentCache.file(dir, "m", "k").readBytes())
            assertEquals(listOf(AttachmentCache.fileKey("m", "k"), AttachmentCache.fileKey("m", "k") + ".name").sorted(), dir.list()!!.sorted())
            AttachmentCache.remove(dir, "m", "k")
            assertEquals(0, dir.list()!!.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    // --- name sanitizing ---

    @Test
    fun `a file name is cut to something safe to show`() {
        assertEquals("passwd", AttachmentCache.sanitizedName("../../etc/passwd")?.substringAfterLast("etc"))
        assertEquals("a.txt", AttachmentCache.sanitizedName("a\u0000.t\nxt".replace("\n", "")))
        assertEquals("hidden", AttachmentCache.sanitizedName("...hidden"))
        assertEquals("evilname", AttachmentCache.sanitizedName("evil\\name"))
        assertEquals(200, AttachmentCache.sanitizedName("a".repeat(300))?.length)
        assertNull(AttachmentCache.sanitizedName(""))
        assertNull(AttachmentCache.sanitizedName("..."))
        assertNull(AttachmentCache.sanitizedName("/\\"))
        assertNull(AttachmentCache.sanitizedName(null))
    }

    // --- MemesServer's pure parts ---

    @Test
    fun `an announced length over the limit is refused before reading`() {
        MemesServer.checkContentLength(100, 100)
        MemesServer.checkContentLength(-1, 100)
        try {
            MemesServer.checkContentLength(101, 100)
            fail("expected TooLargeException")
        } catch (e: MemesServer.TooLargeException) {
            assertEquals(101, e.size)
        }
        try {
            MemesServer.checkContentLength(Long.MAX_VALUE, 100)
            fail("expected TooLargeException")
        } catch (e: MemesServer.TooLargeException) {
            assertEquals(Int.MAX_VALUE, e.size)
        }
    }

    @Test
    fun `streaming stops at the limit and reports progress`() {
        val out = ByteArrayOutputStream()
        val seen = mutableListOf<Long>()
        MemesServer.copyUpTo(ByteArrayInputStream(ByteArray(70_000) { 7 }), out, 70_000, 70_000) { read, total ->
            assertEquals(70_000L, total)
            seen += read
        }
        assertEquals(70_000, out.size())
        assertEquals(70_000L, seen.last())
        try {
            MemesServer.copyUpTo(ByteArrayInputStream(ByteArray(70_001)), ByteArrayOutputStream(), 70_000)
            fail("expected TooLargeException")
        } catch (e: MemesServer.TooLargeException) {
            assertTrue(e.size > 70_000)
        }
    }

    @Test
    fun `every 3xx is a redirect and is not followed`() {
        assertTrue(MemesServer.isRedirect(301))
        assertTrue(MemesServer.isRedirect(307))
        assertFalse(MemesServer.isRedirect(200))
        assertFalse(MemesServer.isRedirect(404))
    }
}
