package com.sphinx.peel.update

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// Pure parsing; Robolectric only supplies a real org.json (the plain JVM's is a stub).
@RunWith(RobolectricTestRunner::class)
class UpdateReleaseTest {
    @Test fun tagToCodeMatchesReleaseYml() {
        assertEquals(117, ReleaseVersion.parseTag("v0.1.17")!!.code)
        assertEquals(10_000, ReleaseVersion.parseTag("v1.0.0")!!.code)
        assertEquals(20_399, ReleaseVersion.parseTag("v2.3.99")!!.code)
        assertEquals("0.1.17", ReleaseVersion.parseTag("v0.1.17")!!.name)
    }

    @Test fun badTagsAreRefused() {
        listOf("0.1.17", "v1.2", "v1.100.0", "v1.0.100", "nightly", "v1.2.3-rc1", "", null).forEach { assertNull(it, ReleaseVersion.parseTag(it)) }
    }

    @Test fun newerThanInstalled() {
        val v = ReleaseVersion(0, 1, 18)
        assertTrue(UpdateDecision.isNewer(v, 117))
        assertFalse(UpdateDecision.isNewer(v, 118))
        assertFalse(UpdateDecision.isNewer(v, 119))
    }

    private fun release(tag: String = "v0.1.18", assets: String) =
        """{"tag_name":"$tag","name":"Peel 0.1.18","body":"Notes","html_url":"https://github.com/x/y/releases/tag/$tag","assets":[$assets]}"""

    private fun asset(name: String, size: Long = 100) = """{"name":"$name","browser_download_url":"https://github.com/dl/$name","size":$size}"""

    @Test fun parsesARelease() {
        val ok = ReleaseParser.parse(release(assets = asset("peel-0.1.18.apk", 5000))) as ReleaseParser.Parsed.Ok
        assertEquals("0.1.18", ok.release.version.name)
        assertEquals("Peel 0.1.18", ok.release.title)
        assertEquals("Notes", ok.release.notes)
        assertEquals(5000L, ok.release.apk.size)
        assertEquals("https://github.com/dl/peel-0.1.18.apk", ok.release.apk.url)
    }

    @Test fun onlyTheNamedApk() {
        val both = ReleaseParser.parse(release(assets = asset("other.apk") + "," + asset("peel-0.1.18.apk"))) as ReleaseParser.Parsed.Ok
        assertEquals("peel-0.1.18.apk", both.release.apk.name)
        assertTrue((ReleaseParser.parse(release(assets = asset("notes.txt") + "," + asset("app.apk"))) as ReleaseParser.Parsed.Failed).failure is UpdateFailure.NoAsset)
    }

    @Test fun noAssetYetOrAmbiguous() {
        assertEquals(UpdateFailure.NoAsset("v0.1.18"), (ReleaseParser.parse(release(assets = "")) as ReleaseParser.Parsed.Failed).failure)
        assertEquals(UpdateFailure.NoAsset("v0.1.18"), (ReleaseParser.parse(release(assets = asset("a.zip"))) as ReleaseParser.Parsed.Failed).failure)
        assertTrue((ReleaseParser.parse(release(assets = asset("a.apk") + "," + asset("b.apk"))) as ReleaseParser.Parsed.Failed).failure is UpdateFailure.NoAsset)
    }

    @Test fun badResponse() {
        assertEquals(UpdateFailure.BadResponse, (ReleaseParser.parse("<html>") as ReleaseParser.Parsed.Failed).failure)
        assertEquals(UpdateFailure.BadResponse, (ReleaseParser.parse(release(tag = "weekly", assets = asset("peel-0.1.18.apk"))) as ReleaseParser.Parsed.Failed).failure)
        assertEquals(UpdateFailure.BadResponse, (ReleaseParser.parse("[]") as ReleaseParser.Parsed.Failed).failure)
    }

    @Test fun notesAreTrimmedAndCapped() {
        assertEquals("a\n\nb", ReleaseNotes.plain("  a\r\n\r\n\r\n\r\nb \n"))
        val long = ReleaseNotes.plain("x".repeat(10_000), 100)
        assertEquals(101, long.length)
        assertTrue(long.endsWith("…"))
    }
}
