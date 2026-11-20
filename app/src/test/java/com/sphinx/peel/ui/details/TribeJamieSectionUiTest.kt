package com.sphinx.peel.ui.details

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sphinx.peel.engine.BoxFixture
import com.sphinx.peel.engine.BoxJamies
import com.sphinx.peel.engine.JamieApprover
import com.sphinx.peel.engine.PlacesStrut
import com.sphinx.peel.engine.TribePlace
import com.sphinx.peel.engine.boxJamies
import com.sphinx.peel.ui.theme.PeelTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// A tribe's Jamie row drawn ([TribeJamieSection]; docs/phases/54-jamie-from-a-box.md): absent with no box ready, "Answers from"
/// with No Jamie and the boxes in its menu, a box picked linking the tribe there, and a join request waiting from that box's Jamie
/// approved as the details open. Against stand-in struts ([PlacesStrut]).
@RunWith(RobolectricTestRunner::class)
class TribeJamieSectionUiTest {
    @get:Rule val rule = createComposeRule()

    private val box = BoxFixture.box("https://a.example.com")
    private val tribe = TribePlace(PlacesStrut.TRIBE, PlacesStrut.LINK, "Builders")
    private val approved = mutableListOf<String>()
    private val approver = JamieApprover { _, key -> approved += key; true }

    private fun show(jamies: BoxJamies, waiting: Boolean = false) = rule.setContent {
        PeelTheme { Column { TribeJamieSection(tribe, "Builders", approver, waiting = { waiting }, jamies = jamies) } }
    }

    @Test fun `no row without a box ready`() {
        show(boxJamies(box to PlacesStrut(box, jamie = null)))
        rule.onNodeWithText("Jamie").assertDoesNotExist()
        rule.onNodeWithText("Answers from").assertDoesNotExist()
    }

    @Test fun `no row without a box`() {
        show(boxJamies())
        rule.onNodeWithText("Jamie").assertDoesNotExist()
    }

    /// No Jamie yet: the row says what a pick does; the box picked from the menu links the tribe there, and the row says where Jamie
    /// answers from.
    @Test fun `a box is picked from the row`() {
        val strut = PlacesStrut(box)
        show(boxJamies(box to strut))
        rule.onNodeWithText("Jamie").assertExists()
        rule.onNodeWithText("No Jamie").assertExists()
        rule.onNodeWithText(
            "Pick one of your boxes: Jamie joins #Builders and answers @jamie there from that box, using your access to it.",
        ).assertExists()

        rule.onNodeWithText("Answers from").performClick()
        rule.onNodeWithText("a.example.com").performClick()
        rule.waitForIdle()
        assertEquals(PlacesStrut.ADA, strut.linkers[tribe.key])
        rule.onNodeWithText("Anyone in #Builders can ask @jamie. Jamie answers from a.example.com, using your access to it.").assertExists()
    }

    /// A refusal is said in place of the line under the row.
    @Test fun `a refusal is said in the row`() {
        val strut = PlacesStrut(box).apply { linkAs("02" + "cd".repeat(32), PlacesStrut.TRIBE) }
        show(boxJamies(box to strut))
        rule.onNodeWithText("Answers from").performClick()
        rule.onNodeWithText("a.example.com").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(
            "Could not add Jamie from a.example.com. Someone else on a.example.com already added Jamie to this tribe.",
        ).assertExists()
    }

    /// Linked on the box, with a request to join waiting unanswered: the box's Jamie is approved by its key as the row opens.
    @Test fun `a waiting request from the box's Jamie is approved`() {
        val strut = PlacesStrut(box).apply { linkAs(PlacesStrut.ADA, PlacesStrut.TRIBE) }
        show(boxJamies(box to strut), waiting = true)
        rule.waitForIdle()
        assertEquals(listOf(PlacesStrut.JAMIE), approved)
        rule.onNodeWithText("Anyone in #Builders can ask @jamie. Jamie answers from a.example.com, using your access to it.").assertExists()
    }

    /// Nothing waiting: nobody is approved.
    @Test fun `nothing waiting approves nobody`() {
        val strut = PlacesStrut(box).apply { linkAs(PlacesStrut.ADA, PlacesStrut.TRIBE) }
        show(boxJamies(box to strut), waiting = false)
        rule.waitForIdle()
        assertTrue(approved.isEmpty())
    }
}
