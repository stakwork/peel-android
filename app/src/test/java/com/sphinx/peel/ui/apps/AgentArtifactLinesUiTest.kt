package com.sphinx.peel.ui.apps

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sphinx.peel.engine.AgentArtifact
import com.sphinx.peel.engine.AgentHandle
import com.sphinx.peel.engine.HivePeer
import com.sphinx.peel.engine.Openings
import com.sphinx.peel.engine.OrgPageRequest
import com.sphinx.peel.ui.theme.PeelTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// `AgentArtifactLines` drawn and tapped over fake `Openings` (docs/phases/31-open-paths.md): a line's words, a tap opening the org's page, the
/// ask-first dialog for an app sent in a tribe (Cancel and Open app), a link through the app-wide opener, and a line that cannot open.
@RunWith(RobolectricTestRunner::class)
class AgentArtifactLinesUiTest {
    @get:Rule val rule = createComposeRule()

    private val opened = mutableListOf<OrgPageRequest>()
    private val links = mutableListOf<String>()
    private val live = Openings(listOf(HivePeer("acme", "https://acme.sphinx.chat", "tok", "Acme")), connected = true)
    private val app = AgentArtifact("1", "Board", label = "App", handle = AgentHandle.App("@acme/board@v2"))
    private val page = AgentArtifact("2", "Plan", label = "Plan", url = "https://example.com/plan")

    private fun show(artifacts: List<AgentArtifact>, inTribe: Boolean = false, openings: Openings = live) = rule.setContent {
        PeelTheme {
            CompositionLocalProvider(
                LocalPageOpener provides { request: OrgPageRequest -> opened += request },
                LocalLinkOpener provides LinkOpener { links += it },
            ) { AgentArtifactLines(artifacts, inTribe = inTribe, openings = { openings }) }
        }
    }

    @Test fun `a line says its label, title and where it opens`() {
        show(listOf(app))
        rule.onNodeWithText("App: Board").assertExists()
        rule.onNodeWithText("Open").assertExists()
    }

    @Test fun `a tap opens the apps page with the token and no question outside a tribe`() {
        show(listOf(app))
        rule.onNodeWithText("App: Board").performClick()
        val request = opened.single()
        assertTrue(request.url.startsWith("https://acme.sphinx.chat/"))
        assertTrue(request.url.contains("app=board"))
        assertEquals("Bearer tok", request.request.headers["Authorization"])
    }

    @Test fun `in a tribe an app asks first, and Cancel opens nothing`() {
        show(listOf(app), inTribe = true)
        rule.onNodeWithText("App: Board").performClick()
        rule.onNodeWithText(app.tribeAppQuestion("Acme"), substring = true).assertExists()
        rule.onNodeWithText("Anyone in the tribe could have sent this link.", substring = true).assertExists()
        assertTrue(opened.isEmpty())
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Cancel").assertDoesNotExist()
        assertTrue(opened.isEmpty())
    }

    @Test fun `in a tribe Open app opens it`() {
        show(listOf(app), inTribe = true)
        rule.onNodeWithText("App: Board").performClick()
        rule.onNodeWithText("Open app").performClick()
        assertEquals(1, opened.size)
        rule.onNodeWithText("Open app").assertDoesNotExist()
    }

    @Test fun `a link goes to the app wide opener`() {
        show(listOf(page))
        rule.onNodeWithText("Plan: Plan").performClick()
        assertEquals(listOf("https://example.com/plan"), links)
        assertTrue(opened.isEmpty())
    }

    @Test fun `an app with no workspace connected shows why and does not open`() {
        show(listOf(app), openings = Openings(emptyList(), connected = false))
        rule.onNodeWithText("· Connect Hive in You to open").assertExists()
        rule.onNodeWithText("Open").assertDoesNotExist()
        rule.onNodeWithText("App: Board").performClick()
        assertTrue(opened.isEmpty() && links.isEmpty())
    }
}
