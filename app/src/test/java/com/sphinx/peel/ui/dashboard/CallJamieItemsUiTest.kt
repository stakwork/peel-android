package com.sphinx.peel.ui.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sphinx.peel.engine.BoxCalls
import com.sphinx.peel.engine.BoxFixture
import com.sphinx.peel.engine.VoiceStrut
import com.sphinx.peel.engine.boxCalls
import com.sphinx.peel.ui.theme.PeelTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// The call menu's Jamie drawn ([CallJamieItems]; docs/phases/56-box-group-calls.md): nothing without a box that can join, the boxes
/// that can and No Jamie, a pick, and Record on the canvas only with a box picked. Against stand-in struts ([VoiceStrut]).
@RunWith(RobolectricTestRunner::class)
class CallJamieItemsUiTest {
    @get:Rule val rule = createComposeRule()

    private val callServer = "chat.sphinx.chat"
    private val a = BoxFixture.box("https://strut.alpha.example.com")
    private val b = BoxFixture.box("https://strut.bee.example.com")

    private fun show(calls: BoxCalls) = rule.setContent { PeelTheme { Column { CallJamieItems("chat1", callServer, calls) } } }

    @Test fun `nothing without a box that can join a call`() {
        val calls = boxCalls(a to VoiceStrut(a, group = false), b to VoiceStrut(b, livekit = "wss://livekit.bee.example.com"))
        runBlocking { calls.read() }
        show(calls)
        rule.onNodeWithText("Jamie").assertDoesNotExist()
        rule.onNodeWithText("No Jamie").assertDoesNotExist()
    }

    @Test fun `a box is picked, then recorded on the canvas, and No Jamie takes both back`() {
        val calls = boxCalls(a to VoiceStrut(a), b to VoiceStrut(b, livekit = "wss://livekit.bee.example.com"))
        runBlocking { calls.read() }
        show(calls)
        rule.onNodeWithText("Jamie").assertExists()
        rule.onNodeWithText("bee").assertDoesNotExist()
        rule.onNodeWithText("No Jamie").assertIsSelected()
        rule.onNodeWithText("Record on the canvas").assertDoesNotExist()

        rule.onNodeWithText("alpha").performClick()
        rule.onNodeWithText("alpha").assertIsSelected()
        assertEquals(a.url, calls.pick("chat1", callServer)?.url)
        rule.onNodeWithText("Record on the canvas").assertIsOff().performClick()
        rule.onNodeWithText("Record on the canvas").assertIsOn()
        assertTrue(calls.records("chat1"))

        rule.onNodeWithText("No Jamie").performClick()
        assertNull(calls.pick("chat1", callServer))
        assertFalse(calls.records("chat1"))
        rule.onNodeWithText("Record on the canvas").assertDoesNotExist()
    }

    @Test fun `Record on the canvas only with a box that can record`() {
        val calls = boxCalls(a to VoiceStrut(a, record = false), b to VoiceStrut(b, record = true))
        runBlocking { calls.read() }
        show(calls)
        rule.onNodeWithText("alpha").performClick()
        rule.onNodeWithText("alpha").assertIsSelected()
        rule.onNodeWithText("Record on the canvas").assertDoesNotExist()
        rule.onNodeWithText("bee").performClick()
        rule.onNodeWithText("Record on the canvas").assertIsOff()
    }
}
