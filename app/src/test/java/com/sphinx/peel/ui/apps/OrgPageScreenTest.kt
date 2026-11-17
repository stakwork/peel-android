package com.sphinx.peel.ui.apps

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import com.sphinx.peel.engine.PhonePage
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/// The page's web view as the real `OrgPageScreen` places it: the two reasons a page showed nothing on a phone
/// (docs/phases/42-page-host-logs.md). Robolectric has no Chromium, so this checks what Peel hands the WebView (its layout params,
/// a web view of its own per page), not what the page then draws.
@RunWith(RobolectricTestRunner::class)
class OrgPageScreenTest {
    private class Connections : WalletConnections {
        val rows = mutableMapOf<String, WalletConnection>()
        override fun connection(key: String) = rows[key]
        override fun save(connection: WalletConnection) { rows[connection.key] = connection }
    }

    private val host = WalletHost(Connections(), ScriptedWallet(VisitorSigner { _: VisitorChallenge, key: SignInKey -> VisitorSignature("pub", "sig-${key.wire}") }), { 1_793_800_000_000L })
    private val shop = PublicPage(PublicAppLink.of("https://shop.example.com/?app=moon-game")!!)
    private val other = PublicPage(PublicAppLink.of("https://other.example.org/")!!)

    private fun webViews(view: View): List<WebView> = when (view) {
        is WebView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { webViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    /// A WRAP_CONTENT layout height makes the WebView lay the page out on a zero-height viewport: `100vh` is 0, and strut's solo
    /// app (`.app-solo { height: 100vh }`) was laid out at no height at all.
    @Test fun `the web view fills the page, not its content`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent { OrgPageScreen(shop, host, onClose = {}) }
        idle()
        val web = webViews(activity.get().window.decorView).single()
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, web.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, web.layoutParams.height)
        activity.pause().stop().destroy()
    }

    /// The screen given another page makes it a web view of its own: it used to keep the last page's AndroidView, whose web view
    /// the last page's close had destroyed and taken out, so the next page had none. Recomposed by a recomposer and frame clock of
    /// its own: Robolectric's shared Compose frame clock stops running frames once an earlier test has destroyed its activity.
    @Test fun `another page gets a web view of its own`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val context = Dispatchers.Unconfined + clock
        val recomposer = Recomposer(context)
        val running = CoroutineScope(context).launch { recomposer.runRecomposeAndApplyChanges() }
        var page by mutableStateOf<PhonePage>(shop)
        val view = ComposeView(activity.get()).apply {
            setParentCompositionContext(recomposer)
            setContent { OrgPageScreen(page, host, onClose = {}) }
        }
        activity.get().setContentView(view)
        idle()
        val first = webViews(view).single()
        assertEquals(shop.url, shadowOf(first).lastLoadedUrl)

        page = other
        Snapshot.sendApplyNotifications()
        clock.sendFrame(0L)
        idle()
        val second = webViews(view).single()
        assertNotSame(first, second)
        assertEquals(other.url, shadowOf(second).lastLoadedUrl)
        recomposer.cancel()
        running.cancel()
        activity.pause().stop().destroy()
    }
}
