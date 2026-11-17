package com.sphinx.peel.ui.dashboard

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.sphinx.peel.engine.AgentToolLine
import com.sphinx.peel.engine.ControlRow
import com.sphinx.peel.ui.theme.PeelTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// The control rows drawn alone (docs/phases/40-control-messages.md): a quiet line as plain centred text, and the tool lines above an agent's reply.
/// The rules that choose them are `ControlPlanTest`'s; this is that they read as text and never as a bubble's content.
@RunWith(RobolectricTestRunner::class)
class ControlRowsUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `a line shows its text`() {
        rule.setContent { PeelTheme { ControlRowView(ControlRow.Line("Kate: on my way")) } }
        rule.onNodeWithText("Kate: on my way").assertExists()
    }

    @Test fun `tool lines show the tool, what it asked, and failed`() {
        rule.setContent {
            PeelTheme {
                AgentToolLines(listOf(AgentToolLine("read_unseen_messages", "Kate"), AgentToolLine("send_sphinx_message", "", failed = true)))
            }
        }
        rule.onNodeWithText("Read unseen messages · Kate").assertExists()
        rule.onNodeWithText("Send a message · failed").assertExists()
    }

    @Test fun `a request with no outcome shows its label done`() {
        rule.setContent { PeelTheme { AgentRequestLine("Read your unread messages", null) } }
        rule.onNodeWithText("Read your unread messages  ✓").assertExists()
    }
}
