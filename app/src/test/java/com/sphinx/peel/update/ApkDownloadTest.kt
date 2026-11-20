package com.sphinx.peel.update

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkDownloadTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun release(size: Long, url: String = "https://x/peel.apk") =
        ReleaseInfo(ReleaseVersion(0, 1, 18), "t", "n", null, ReleaseAsset("peel-0.1.18.apk", url, size))

    private fun fetch(bytes: ByteArray) = ApkFetch { object : ApkStream {
        override val length = bytes.size.toLong()
        override val input: InputStream = ByteArrayInputStream(bytes)
        override fun close() {}
    } }

    @Test fun downloadsAndReportsProgress() {
        val dir = File(tmp.root, "updates")
        val seen = mutableListOf<Pair<Long, Long>>()
        val out = ApkDownloader(fetch(ByteArray(200_000) { 1 })).download(release(200_000), dir, { b, t -> seen += b to t })
        assertEquals(200_000L, (out as ApkDownloader.Outcome.Done).file.length())
        assertEquals(listOf("peel-0.1.18.apk"), dir.list()!!.toList())
        assertEquals(200_000L to 200_000L, seen.last())
        assertEquals(0L to 200_000L, seen.first())
    }

    @Test fun wrongSizeLeavesNothing() {
        val dir = tmp.newFolder("u")
        assertEquals(ApkDownloader.Outcome.Failed(UpdateFailure.SizeMismatch), ApkDownloader(fetch(ByteArray(10))).download(release(11), dir))
        assertEquals(0, dir.list()!!.size)
    }

    @Test fun cancelLeavesNoPartialFile() {
        val dir = tmp.newFolder("u")
        var reads = 0
        try {
            ApkDownloader(fetch(ByteArray(500_000))).download(release(500_000), dir, checkCancelled = { if (++reads > 2) throw CancellationException() })
            fail()
        } catch (_: CancellationException) {}
        assertEquals(0, dir.list()!!.size)
    }

    @Test fun ioFailureLeavesNothing() {
        val dir = tmp.newFolder("u")
        val broken = ApkFetch { object : ApkStream {
            override val length = 5L
            override val input: InputStream = object : InputStream() { override fun read(): Int = throw IOException("reset") }
            override fun close() {}
        } }
        assertEquals(ApkDownloader.Outcome.Failed(UpdateFailure.Offline), ApkDownloader(broken).download(release(5), dir))
        assertEquals(0, dir.list()!!.size)
        assertEquals(ApkDownloader.Outcome.Failed(UpdateFailure.Offline), ApkDownloader { throw IOException("HTTP 404") }.download(release(5), dir))
    }

    @Test fun refusesPlainHttp() {
        assertEquals(ApkDownloader.Outcome.Failed(UpdateFailure.Insecure), ApkDownloader(fetch(ByteArray(1))).download(release(1, "http://x/a.apk"), tmp.newFolder("u")))
    }

    @Test fun clearsOldFilesFirst() {
        val dir = tmp.newFolder("u")
        File(dir, "peel-0.1.10.apk").writeText("old"); File(dir, "x.part").writeText("p")
        ApkDownloader(fetch(ByteArray(3))).download(release(3), dir)
        assertEquals(listOf("peel-0.1.18.apk"), dir.list()!!.toList())
    }

    @Test fun staleRules() {
        val names = listOf("peel-0.1.17.apk", "peel-0.1.18.apk", "peel-0.1.19.apk.part", "junk")
        assertEquals(listOf("peel-0.1.17.apk", "peel-0.1.19.apk.part", "junk"), UpdateFiles.stale(names, 117))
        assertEquals(names, UpdateFiles.stale(names, 118))
    }

    @Test fun archiveVerdicts() {
        val installed = PackageFacts("com.sphinx.peel", 117, setOf("a"))
        assertNull(ArchiveCheck.verdict(installed, PackageFacts("com.sphinx.peel", 118, setOf("a"))))
        assertEquals(UpdateFailure.WrongSignature, ArchiveCheck.verdict(installed, PackageFacts("com.sphinx.peel", 118, setOf("z"))))
        assertEquals(UpdateFailure.WrongSignature, ArchiveCheck.verdict(installed, PackageFacts("com.sphinx.peel", 118, setOf("a", "z"))))
        assertEquals(UpdateFailure.NotNewer, ArchiveCheck.verdict(installed, PackageFacts("com.sphinx.peel", 117, setOf("a"))))
        assertEquals(UpdateFailure.UnreadableApk, ArchiveCheck.verdict(installed, PackageFacts("other", 118, setOf("a"))))
        assertEquals(UpdateFailure.UnreadableApk, ArchiveCheck.verdict(installed, null))
        assertEquals(UpdateFailure.UnreadableApk, ArchiveCheck.verdict(installed, PackageFacts("com.sphinx.peel", 118, emptySet())))
    }
}
