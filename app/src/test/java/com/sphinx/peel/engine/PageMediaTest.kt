package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/// A page's camera and microphone (iOS `requestMediaCapturePermissionFor`; docs/phases/42-page-host-logs.md): the origin Peel
/// loaded, the camera and the microphone, nothing else.
class PageMediaTest {
    private val page = "https://strut.stadeum1.sphinx.chat"
    private val other = "android.webkit.resource.PROTECTED_MEDIA_ID"

    @Test fun `the page's own origin may have the camera and the microphone`() {
        assertEquals(listOf(PageMedia.VIDEO), PageMedia.grantable("https://strut.stadeum1.sphinx.chat/", page, listOf(PageMedia.VIDEO)))
        assertEquals(listOf(PageMedia.VIDEO), PageMedia.grantable("https://strut.stadeum1.sphinx.chat:443", page, listOf(PageMedia.VIDEO)))
        assertEquals(
            listOf(PageMedia.VIDEO, PageMedia.AUDIO),
            PageMedia.grantable(page, page, listOf(PageMedia.VIDEO, PageMedia.AUDIO, PageMedia.VIDEO)),
        )
    }

    @Test fun `nothing else, and nothing for another origin`() {
        assertEquals(emptyList<String>(), PageMedia.grantable(page, page, listOf(other, "android.webkit.resource.MIDI_SYSEX")))
        assertEquals(listOf(PageMedia.VIDEO), PageMedia.grantable(page, page, listOf(other, PageMedia.VIDEO)))
        assertEquals(emptyList<String>(), PageMedia.grantable("https://evil.example.com/", page, listOf(PageMedia.VIDEO)))
        assertEquals(emptyList<String>(), PageMedia.grantable("http://strut.stadeum1.sphinx.chat/", page, listOf(PageMedia.VIDEO)))
        assertEquals(emptyList<String>(), PageMedia.grantable("https://strut.stadeum1.sphinx.chat:8443/", page, listOf(PageMedia.VIDEO)))
        // An app's sandboxed frame has an opaque origin.
        assertEquals(emptyList<String>(), PageMedia.grantable("null", page, listOf(PageMedia.VIDEO)))
        assertEquals(emptyList<String>(), PageMedia.grantable("", page, listOf(PageMedia.VIDEO)))
    }

    @Test fun `each is granted only with the system permission it needs`() {
        assertEquals(listOf(PageMedia.CAMERA, PageMedia.RECORD_AUDIO), PageMedia.systemPermissions(listOf(PageMedia.VIDEO, PageMedia.AUDIO)))
        assertEquals(emptyList<String>(), PageMedia.systemPermissions(listOf(other)))
        val both = listOf(PageMedia.VIDEO, PageMedia.AUDIO)
        assertEquals(listOf(PageMedia.VIDEO), PageMedia.granted(both) { it == PageMedia.CAMERA })
        assertEquals(both, PageMedia.granted(both) { true })
        assertEquals(emptyList<String>(), PageMedia.granted(both) { false })
    }

    @Test fun `the log line names the origin and the resources' last word`() {
        assertEquals(
            "permission https://strut.stadeum1.sphinx.chat asked=VIDEO_CAPTURE,PROTECTED_MEDIA_ID granted=VIDEO_CAPTURE",
            PageLog.permission("https://strut.stadeum1.sphinx.chat/", listOf(PageMedia.VIDEO, other), listOf(PageMedia.VIDEO)),
        )
        assertEquals("permission ? asked=VIDEO_CAPTURE granted=-", PageLog.permission("null", listOf(PageMedia.VIDEO), emptyList()))
    }
}
