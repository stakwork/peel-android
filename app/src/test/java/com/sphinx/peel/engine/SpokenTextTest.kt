package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/// An agent reply read aloud (`SpokenText`). Ported from peel's `Tests/PeelUITests/SpokenTextTests.swift`.
class SpokenTextTest {
    private fun spoken(markdown: String) = SpokenText(markdown).chunks

    @Test fun `plain sentences are chunks`() {
        assertEquals(listOf("Done.", "I started the job!"), spoken("Done. I started the job!"))
    }

    @Test fun `code fence is one phrase`() {
        val text = "Here it is:\n```swift\nlet a = 1\nlet b = 2\nlet c = 3\n```\nThat is all."
        val chunks = spoken(text)
        assertTrue(chunks.contains("A code block of 3 lines, skipped."))
        assertFalse(chunks.joinToString("").contains("let a"))
        assertEquals("That is all.", chunks.last())
    }

    @Test fun `unclosed fence is still skipped`() {
        assertEquals("A code block of 2 lines, skipped.", spoken("```\nx\ny").joinToString(""))
    }

    @Test fun `inline code keeps its text`() {
        assertEquals(listOf("Run swift test now."), spoken("Run `swift test` now."))
    }

    @Test fun `links read as their title`() {
        assertEquals(listOf("See the docs for more."), spoken("See [the docs](https://example.com/docs/page) for more."))
    }

    @Test fun `bare URLs name the host`() {
        assertEquals(listOf("Open a link to github.com."), spoken("Open https://github.com/stakwork/peel/pull/12."))
        assertEquals(listOf("Open (a link to example.com)."), spoken("Open (https://www.example.com/x)."))
    }

    @Test fun `images are an image`() {
        assertEquals(listOf("an image"), spoken("![screenshot](https://x.test/a.png)"))
    }

    @Test fun `headings emphasis and quotes are stripped`() {
        val chunks = spoken("# Summary\n**Bold** and *italic* and ~~gone~~ text.\n> quoted words")
        assertEquals(listOf("Summary.", "Bold and italic and gone text.", "quoted words"), chunks)
    }

    @Test fun `snake case keeps its underscores`() {
        assertEquals(listOf("Call my_function_name now."), spoken("Call my_function_name now."))
    }

    @Test fun `lists become separate sentences`() {
        val chunks = spoken("Steps:\n- first thing\n- second thing\n1. third thing\n2) fourth thing")
        assertEquals(listOf("Steps:", "first thing.", "second thing.", "third thing.", "fourth thing."), chunks)
    }

    @Test fun `tables are skipped`() {
        val chunks = spoken("Results:\n| a | b |\n|---|---|\n| 1 | 2 |\nDone.")
        assertEquals(listOf("Results:", "A table, skipped.", "Done."), chunks)
    }

    @Test fun `emoji are dropped`() {
        assertEquals(listOf("All good now"), spoken("All good 🎉🚀 now ✅"))
    }

    @Test fun `identifiers are collapsed`() {
        assertEquals(listOf("Job an identifier started."), spoken("Job 3f2504e0-4f89-11d3-9a0c-0305e82c3301 started."))
        assertEquals(listOf("Commit an identifier landed."), spoken("Commit 9fceb02d0ae598e95dc970b74767f19372d61af8 landed."))
        assertEquals(listOf("Edit an identifier please."), spoken("Edit /Users/me/code/peel/App.swift please."))
        assertEquals(listOf("The word abcdefabcdef stays."), spoken("The word abcdefabcdef stays."))
    }

    @Test fun `short replies are not shortened`() {
        val text = SpokenText("One. Two.")
        assertFalse(text.isShortened)
        assertEquals(text.full, text.chunks)
    }

    @Test fun `long replies are cut at a paragraph with a note`() {
        val paragraph = (1..6).joinToString(" ") { "This is sentence number $it of the paragraph." }
        val text = SpokenText(
            listOf(paragraph, paragraph, paragraph).joinToString("\n\n"),
            SpokenText.Budget(characters = 400, sentences = 20),
        )
        assertTrue(text.isShortened)
        assertEquals(SpokenText.SHORT_VERSION_NOTE, text.chunks.last())
        assertEquals(7, text.chunks.size)
        assertEquals(18, text.full.size)
    }

    @Test fun `a single long paragraph is cut at a sentence`() {
        val paragraph = (1..30).joinToString(" ") { "Sentence $it is here." }
        val text = SpokenText(paragraph, SpokenText.Budget(characters = 100, sentences = 20))
        assertTrue(text.isShortened)
        assertTrue(text.chunks.size > 2)
        assertTrue(text.chunks.dropLast(1).joinToString(" ").length <= 100)
        assertEquals(30, text.full.size)
    }

    @Test fun `sentence limit cuts too`() {
        val text = SpokenText("- a\n- b\n- c\n- d", SpokenText.Budget(characters = 600, sentences = 2))
        assertEquals(listOf("a.", "b.", SpokenText.SHORT_VERSION_NOTE), text.chunks)
    }

    @Test fun `empty reply has nothing to say`() {
        assertTrue(SpokenText("  \n").chunks.isEmpty())
        assertTrue(SpokenText("---").chunks.isEmpty())
    }
}
