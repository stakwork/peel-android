package com.sphinx.peel.ui.components

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.unit.sp
import com.sphinx.peel.engine.AppLinkHandler
import com.sphinx.peel.engine.OrgPage
import com.sphinx.peel.engine.HivePeer
import com.sphinx.peel.engine.Openings
import com.sphinx.peel.ui.theme.LightPeelPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// A link tapped in a message goes to the one handler of the app (`LocalLinkOpener`, iOS `\.openURL`): the text's link annotations
/// call what `MessageText` gives them, here wired to the real [AppLinkHandler] over a paired workspace.
@RunWith(RobolectricTestRunner::class)
class MessageTextLinkTest {
    private val acme = HivePeer("acme", "https://acme.sphinx.chat/lab", "tok", "Acme")

    /// Taps every link in `text` as drawn, in order, through `openLink`.
    private fun tapAll(text: String, openLink: (String) -> Unit) {
        val drawn = attributed(text, emptySet(), LightPeelPalette, 14.sp, openLink)
        drawn.getLinkAnnotations(0, drawn.length).forEach { range ->
            val link = range.item as LinkAnnotation.Url
            link.linkInteractionListener!!.onClick(link)
        }
    }

    @Test fun `a hive app link in a message opens its page, any other link goes out, a stranger's workspace says connect first`() {
        val pages = mutableListOf<String>()
        val said = mutableListOf<String>()
        val system = mutableListOf<String>()
        val openings = Openings(listOf(acme), connected = true)
        val handler = AppLinkHandler(
            tap = openings::tap, reopen = { openings.opening(it) }, openPage = { pages += it.url }, announce = { said += it }, system = { system += it },
        )
        tapAll("see https://hive.sphinx.chat/w/acme/apps/board and [docs](https://example.com/docs) and https://hive.sphinx.chat/w/globex/apps/x", handler::open)
        assertEquals(listOf("https://acme.sphinx.chat/lab/?app=board&solo=1"), pages)
        assertEquals(listOf("Connect @globex first"), said)
        assertEquals(listOf("https://example.com/docs", "https://hive.sphinx.chat/w/globex/apps/x"), system)
        assertEquals(OrgPage.app("board", "acme"), OrgPage.hiveApp("https://hive.sphinx.chat/w/acme/apps/board"))
    }

    @Test fun `only https links are links at all`() {
        val opened = mutableListOf<String>()
        tapAll("http://hive.sphinx.chat/w/acme/apps/board and javascript:alert(1) and tel:1", opened::add)
        assertTrue(opened.toString(), opened.isEmpty())
    }
}
