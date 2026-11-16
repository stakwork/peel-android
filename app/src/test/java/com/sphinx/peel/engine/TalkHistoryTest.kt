package com.sphinx.peel.engine

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TalkHistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val lines = listOf(
        TalkLine("you/1", TalkLine.Speaker.YOU, "Hello Jamie", true),
        TalkLine("agent/1", TalkLine.Speaker.AGENT, "Hi — how can I help?", true),
    )

    @Test fun anEmptyTranscriptIsNotSaved() {
        val store = TalkHistoryStore(File(tmp.root, "h"))
        assertNull(store.save(emptyList(), "acme", "Acme"))
        assertTrue(store.sessions("acme").isEmpty())
    }

    @Test fun savedSessionsComeBackNewestFirstPerHome() {
        val store = TalkHistoryStore(File(tmp.root, "h"))
        store.save(lines, "acme", "Acme", Instant.parse("2026-01-02T10:00:00Z"))
        store.save(lines.take(1), "acme", "Acme", Instant.parse("2026-01-03T10:00:00Z"))
        store.save(lines, "other", "Other", Instant.parse("2026-01-04T10:00:00Z"))
        val got = store.sessions("acme")
        assertEquals(listOf(Instant.parse("2026-01-03T10:00:00Z"), Instant.parse("2026-01-02T10:00:00Z")), got.map { it.date })
        assertEquals(lines, got[1].lines)
        assertEquals("Acme", got[0].homeName)
        assertEquals(1, store.sessions("other").size)
    }

    @Test fun theDevelopmentHomeAndUnsafeIdsGetSafeFileNames() {
        val dir = File(tmp.root, "h")
        val store = TalkHistoryStore(dir)
        store.save(lines, "@development", "Dev")
        store.save(lines, "a/b:c", "Odd")
        assertTrue(dir.listFiles()!!.all { '/' !in it.name && ':' !in it.name })
        assertEquals(1, store.sessions("@development").size)
        assertEquals(1, store.sessions("a/b:c").size)
    }

    @Test fun aHomeWhoseIdStartsWithAnothersIsNotMixedIn() {
        val store = TalkHistoryStore(File(tmp.root, "h"))
        store.save(lines, "acme", "Acme")
        store.save(lines, "acme_two", "Two")
        assertEquals(1, store.sessions("acme").size)
    }

    @Test fun unreadableFilesAreSkipped() {
        val dir = File(tmp.root, "h").also { it.mkdirs() }
        File(dir, "acme_bad.json").writeText("not json")
        File(dir, "acme_other.txt").writeText("{}")
        val store = TalkHistoryStore(dir)
        store.save(lines, "acme", "Acme")
        assertEquals(1, store.sessions("acme").size)
        assertTrue(TalkHistoryStore(File(tmp.root, "none")).sessions("acme").isEmpty())
    }

    @Test fun jsonRoundTripAndPreview() {
        val s = TalkSavedSession("id1", "acme", "Acme", Instant.parse("2026-01-02T10:00:00.5Z"), lines)
        assertEquals(s, TalkSavedSession.parse(s.toJson()))
        assertEquals("Hello Jamie", s.preview)
        assertNull(s.copy(lines = emptyList()).preview)
        assertNull(TalkSavedSession.parse("{\"id\":\"x\"}"))
        assertNotNull(TalkSavedSession.parse(s.toJson().replace("\"you\"", "\"you\"")))
        assertNull(TalkSavedSession.parse(s.toJson().replace("\"you\"", "\"nobody\"")))
    }

    @Test fun datesAndSpeakers() {
        val d = Instant.parse("2026-03-05T14:07:00Z")
        assertEquals("Mar 5, 26, 2:07 PM", TalkHistoryFormat.row(d, ZoneId.of("UTC"), Locale.US))
        assertEquals("Mar 5, 2:07 PM", TalkHistoryFormat.title(d, ZoneId.of("UTC"), Locale.US))
        assertEquals("You", TalkHistoryFormat.speaker(lines[0]))
        assertEquals("Jamie", TalkHistoryFormat.speaker(lines[1]))
    }
}
