package com.sphinx.peel.ui.apps

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.sphinx.peel.engine.OrgPageRequest
import com.sphinx.peel.engine.PageLog
import com.sphinx.peel.engine.PublicAppLink
import com.sphinx.peel.engine.PublicPage
import com.sphinx.peel.engine.ScriptedWallet
import com.sphinx.peel.engine.SignInKey
import com.sphinx.peel.engine.VisitorChallenge
import com.sphinx.peel.engine.VisitorSignature
import com.sphinx.peel.engine.VisitorSigner
import com.sphinx.peel.engine.WalletConnection
import com.sphinx.peel.engine.WalletConnections
import com.sphinx.peel.engine.WalletHost
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog

/// What the real page screen writes to logcat as it makes its web view (docs/phases/42-page-host-logs.md): what was made and the
/// first load, by origin; never the bearer, the app or anything after the host. Robolectric's WebView has no features, profiles or
/// renderer, so this checks the lines and what they leave out, not what a device's WebView reports.
@RunWith(RobolectricTestRunner::class)
class PageHostLogTest {
    private class Connections : WalletConnections {
        val rows = mutableMapOf<String, WalletConnection>()
        override fun connection(key: String) = rows[key]
        override fun save(connection: WalletConnection) { rows[connection.key] = connection }
    }

    private val host = WalletHost(Connections(), ScriptedWallet(VisitorSigner { _: VisitorChallenge, key: SignInKey -> VisitorSignature("pub", "sig-${key.wire}") }), { 1_793_800_000_000L })

    private fun linesFor(show: (ComponentActivity) -> Unit): List<String> {
        val before = ShadowLog.getLogsForTag(PageLog.TAG).size
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        show(activity.get())
        shadowOf(android.os.Looper.getMainLooper()).idle()
        activity.pause().stop().destroy()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return ShadowLog.getLogsForTag(PageLog.TAG).drop(before).map { it.msg }
    }

    @Test fun `an org page logs what it made and its first load, with no bearer or app`() {
        val page = OrgPageRequest("https://acme.sphinx.chat/lab/?app=board&solo=1", "sekret-bearer-4242", "acme", "Acme", "Board")
        val lines = linesFor { it.setContent { OrgPageScreen(page, host, onClose = {}) } }
        assertTrue(lines.toString(), lines.any { it.startsWith("webView gen=0 ") && " profile=" in it && " tokenScript=" in it && " wallet=" in it })
        assertTrue(lines.toString(), "loadUrl https://acme.sphinx.chat gen=0" in lines)
        assertTrue(lines.toString(), lines.any { it.startsWith("close ") })
        assertFalse(lines.toString(), lines.any { "sekret" in it || "app=" in it || "/lab" in it || "board" in it })
    }

    @Test fun `a public page logs its origin alone`() {
        val page = PublicPage(PublicAppLink.of("https://strut.stadeum1.sphinx.chat/?app=strut/worker&solo=1&public=1")!!)
        val lines = linesFor { it.setContent { OrgPageScreen(page, host, onClose = {}) } }
        assertTrue(lines.toString(), "loadUrl https://strut.stadeum1.sphinx.chat gen=0" in lines)
        assertFalse(lines.toString(), lines.any { "worker" in it || "app=" in it || "public=" in it })
    }
}
