package com.sphinx.peel.ui.talk

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sphinx.peel.engine.TalkHistoryFormat
import com.sphinx.peel.engine.TalkHistoryStore
import com.sphinx.peel.engine.TalkHome
import com.sphinx.peel.engine.TalkLine
import com.sphinx.peel.ui.theme.PeelTheme
import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// `TalkHistoryScreen` over a real `TalkHistoryStore` in a temp folder (docs/phases/36-apps-cosmetics.md): the empty text, the rows (date and
/// preview), a row opening its transcript and Back returning to the list, and Talk from the toolbar and from a row.
@RunWith(RobolectricTestRunner::class)
class TalkHistoryScreenUiTest {
    @get:Rule val rule = createComposeRule()

    private val store = TalkHistoryStore(Files.createTempDirectory("talk-history-ui").toFile())
    private val home = TalkHome("acme", "Acme", "https://acme.sphinx.chat", "tok")
    private val at = Instant.parse("2026-11-02T10:15:00Z")
    private val talked = mutableListOf<TalkHome>()
    private var backed = 0

    private fun show() = rule.setContent {
        PeelTheme { TalkHistoryScreen(home, store, onBack = { backed++ }, onTalk = { talked += it }) }
    }

    private fun await(text: String) = rule.waitUntil(5_000) { rule.onAllNodesWithTextCount(text) > 0 }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().size

    @Test fun `with no conversations it says so`() {
        show()
        await("No conversations yet")
        rule.onNodeWithText("Past conversations with Jamie on Acme will appear here after a call ends.").assertExists()
    }

    @Test fun `a saved conversation is a row with its date and preview, and opens as a transcript`() {
        store.save(listOf(TalkLine("1", TalkLine.Speaker.YOU, "what is up", true), TalkLine("2", TalkLine.Speaker.AGENT, "all good", true)), "acme", "Acme", at)
        show()
        await("what is up")
        rule.onNodeWithText(TalkHistoryFormat.row(at)).assertExists()
        rule.onNodeWithText("what is up").performClick()
        rule.onNodeWithText(TalkHistoryFormat.title(at)).assertExists()
        rule.onNodeWithText("You").assertExists()
        rule.onNodeWithText("Jamie").assertExists()
        rule.onNodeWithText("all good").assertExists()
        // Back from a transcript returns to the list, not out.
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Conversations").assertExists()
        assertEquals(0, backed)
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backed)
    }

    @Test fun `talk starts from the toolbar and from a row`() {
        store.save(listOf(TalkLine("1", TalkLine.Speaker.YOU, "hi", true)), "acme", "Acme", at)
        show()
        await("hi")
        rule.onNodeWithContentDescription("New conversation").performClick()
        rule.onNodeWithContentDescription("Resume").performClick()
        assertEquals(listOf(home, home), talked)
    }

    @Test fun `another homes conversation is not listed`() {
        store.save(listOf(TalkLine("1", TalkLine.Speaker.YOU, "elsewhere", true)), "other", "Other", at)
        show()
        await("No conversations yet")
        rule.onNodeWithText("elsewhere").assertDoesNotExist()
    }
}
