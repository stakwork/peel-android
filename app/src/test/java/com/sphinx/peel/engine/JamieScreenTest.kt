package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Jamie's screen as the app itself ([JamieScreen]; docs/phases/57-live-canvas-tile.md, stadeum plans/call-canvas.md §6.5): the link
/// Jamie's `screen` attribute gives, the linked box it is on, and the page that opens there with the person's token. Anything else
/// keeps the video.
class JamieScreenTest {
    private val stadeum = BoxFixture.box("https://strut.stadeum.stakwork.ai")
    private val other = BoxFixture.box("https://strut.other.example.com")
    private val canvas = "https://strut.stadeum.stakwork.ai/?app=stadeum%2Fcanvas%2Fcanvas&input=present%3Dcall-7f3a"
    private val jamie = mapOf("tts" to "on", "recording" to "true", JamieScreen.ATTRIBUTE to canvas)

    @Test fun `Jamie's screen attribute is its link, and nobody else's is`() {
        assertEquals(canvas, JamieScreen.link("Jamie", jamie))
        assertEquals(canvas, JamieScreen.link(" jamie ", jamie + (JamieScreen.ATTRIBUTE to " $canvas ")))
        // Not sharing: empty, or no attribute at all.
        assertNull(JamieScreen.link("Jamie", jamie + (JamieScreen.ATTRIBUTE to "")))
        assertNull(JamieScreen.link("Jamie", jamie - JamieScreen.ATTRIBUTE))
        // A person called Jamie (no `tts`, which only the voice service sets), or anyone else.
        assertNull(JamieScreen.link("Jamie", jamie - "tts"))
        assertNull(JamieScreen.link("Ada", jamie))
        assertNull(JamieScreen.link(null, jamie))
        assertNull(JamieScreen.link("Jamie", jamie + (JamieScreen.ATTRIBUTE to canvas + "&x=" + "a".repeat(5_000))))
    }

    @Test fun `a link on a linked box is that box's app, at the view it names`() {
        val place = JamieScreen.place(canvas, listOf(other, stadeum))!!
        assertEquals(stadeum, place.box)
        assertEquals("stadeum/canvas/canvas", place.app.app.app)
        assertEquals("present=call-7f3a", place.app.input)
        // The same box, written with its default port or another case.
        assertEquals(stadeum, JamieScreen.place(canvas.replace("stadeum.stakwork.ai/", "Stadeum.Stakwork.AI:443/"), listOf(stadeum))?.box)
        // With no input: the app's first view.
        assertNull(JamieScreen.place("https://strut.stadeum.stakwork.ai/?app=stadeum%2Fcanvas%2Fcanvas", listOf(stadeum))!!.app.input)
    }

    @Test fun `anything else keeps the video`() {
        val linked = listOf(stadeum)
        assertNull(JamieScreen.place(null, linked))
        assertNull(JamieScreen.place("", linked))
        assertNull(JamieScreen.place(canvas, emptyList()))
        // A box this phone is not linked to.
        assertNull(JamieScreen.place(canvas, listOf(other)))
        // A lookalike host, another scheme, another port, a user in the address.
        assertNull(JamieScreen.place(canvas.replace("stakwork.ai/", "stakwork.ai.evil.example/"), linked))
        assertNull(JamieScreen.place(canvas.replace("https:", "http:"), linked))
        assertNull(JamieScreen.place(canvas.replace("stakwork.ai/", "stakwork.ai:8443/"), linked))
        // Not strut's UI naming an app: a file, a run, an app strut would refuse, an org's app, an app given twice.
        assertNull(JamieScreen.place("https://strut.stadeum.stakwork.ai/files/canvas?app=stadeum%2Fcanvas%2Fcanvas", linked))
        assertNull(JamieScreen.place("https://strut.stadeum.stakwork.ai/?artifact=notes.md", linked))
        assertNull(JamieScreen.place("https://strut.stadeum.stakwork.ai/?app=..%2Fdata.db", linked))
        assertNull(JamieScreen.place("https://strut.stadeum.stakwork.ai/?app=%40acme%2Fboard", linked))
        assertNull(JamieScreen.place("$canvas&app=strut%2Fjamie", linked))
        assertNull(JamieScreen.place("not a link", linked))
    }

    @Test fun `the app opens alone on the box with the person's token, never in its address`() {
        val place = JamieScreen.place(canvas, listOf(stadeum))!!
        val page = stadeum.home.app(place.app)!!
        assertEquals("https://strut.stadeum.stakwork.ai/?app=stadeum%2Fcanvas%2Fcanvas&input=present%3Dcall-7f3a&solo=1", page.url)
        assertFalse(page.url.contains(stadeum.token))
        assertEquals(mapOf("Authorization" to "Bearer ${stadeum.token}"), page.request.headers)
        assertTrue(page.script.contains(stadeum.token))
        // The box's page, as any of its apps: it stays open while the box does, and goes where strut's UI there goes.
        assertEquals(stadeum.peer, page.peer)
        assertTrue(page.serves("https://strut.stadeum.stakwork.ai/?app=strut%2Fjamie"))
        assertFalse(page.serves("https://strut.other.example.com/"))
        assertEquals("stadeum", stadeum.home.app(place.app, title = stadeum.shortName)!!.title)
    }
}
