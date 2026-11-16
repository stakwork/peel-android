package com.sphinx.peel.ui.talk

import com.sphinx.peel.engine.TalkHistoryStore
import com.sphinx.peel.engine.TalkHome
import com.sphinx.peel.engine.TalkLine
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// What `TalkStore.hangUp` saves when a call that reached the room ends (docs/phases/36-apps-cosmetics.md; the room itself is LiveKit's and
/// needs a device, and org.json needs Robolectric, so this drives the extracted `saveTranscript` over the real history store in a temp folder).
@RunWith(RobolectricTestRunner::class)
class TalkStoreSaveTest {
    private val dir = Files.createTempDirectory("talk-history").toFile()
    private val history = TalkHistoryStore(dir)
    private val home = TalkHome("acme", "Acme", "https://acme.sphinx.chat", "tok")
    private val lines = listOf(
        TalkLine("1", TalkLine.Speaker.YOU, "hello", true),
        TalkLine("2", TalkLine.Speaker.AGENT, "hi there", true),
    )

    @Test fun `a transcript with lines is saved under the home`() {
        val job = TalkStore.saveTranscript(history, home, lines, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        assertEquals(true, job?.isCompleted)
        val saved = history.sessions("acme").single()
        assertEquals("Acme", saved.homeName)
        assertEquals(lines, saved.lines)
        assertEquals("hello", saved.preview)
    }

    @Test fun `an empty transcript saves nothing`() {
        assertNull(TalkStore.saveTranscript(history, home, emptyList(), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined))
        assertEquals(emptyList<Any>(), history.sessions("acme"))
    }

    @Test fun `a home keeps only its own sessions`() {
        TalkStore.saveTranscript(history, home, lines, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        assertEquals(emptyList<Any>(), history.sessions("other"))
    }
}
