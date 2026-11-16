package com.sphinx.peel.ui.apps

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sphinx.peel.engine.OrgPageRequest
import com.sphinx.peel.engine.ScriptedWallet
import com.sphinx.peel.engine.SignInKey
import com.sphinx.peel.engine.VisitorChallenge
import com.sphinx.peel.engine.VisitorSignature
import com.sphinx.peel.engine.VisitorSigner
import com.sphinx.peel.engine.WalletConnection
import com.sphinx.peel.engine.WalletConnectSheet
import com.sphinx.peel.engine.WalletConnections
import com.sphinx.peel.engine.WalletHost
import com.sphinx.peel.engine.WalletPaymentSheet
import com.sphinx.peel.engine.WalletRenewal
import com.sphinx.peel.engine.WalletSheet
import com.sphinx.peel.ui.page.PhoneWalletSheets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/// What iOS's `stillOpen` guarantees ("a yes once Peel locked is a no"), through the real composable: a lock removes the page (`MainTabs` / `PublicPageRouter`
/// stop showing it) and the window going away disposes it, and `OrgPageScreen`'s dispose path must close the person's sheet slot, answering what it waits on
/// as a no. `PhoneWalletSheetsTest` covers the slot alone; this covers that the page calls it.
@RunWith(RobolectricTestRunner::class)
class OrgPageDisposeTest {
    private class Connections : WalletConnections {
        val rows = mutableMapOf<String, WalletConnection>()
        override fun connection(key: String) = rows[key]
        override fun save(connection: WalletConnection) { rows[connection.key] = connection }
    }

    private val host = WalletHost(Connections(), ScriptedWallet(VisitorSigner { _: VisitorChallenge, key: SignInKey -> VisitorSignature("pub", "sig-${key.wire}") }), { 1_793_800_000_000L })
    private val page = OrgPageRequest("https://acme.sphinx.chat/lab/?app=x&solo=1", "tok", "acme", "Acme", "Board")
    private val paySheet = WalletPaymentSheet("Bench", "b", "v3", "Acme", true, 2_000, "pk", null, null, null, null, false, false, 0, null)
    private val connectSheet = WalletConnectSheet(
        "Bench", "acme/bench", "v3", "Acme", true, emptyList(), emptyList(), emptyList(), null, WalletRenewal.NEVER, null, null, true, null,
    )

    @Test fun `the window going away closes the page's sheets and a waiting sheet is a no`() = runBlocking<Unit> {
        val sheets = PhoneWalletSheets(CoroutineScope(Dispatchers.Unconfined))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { OrgPageScreen(page, host, onClose = {}, sheets = sheets) }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(sheets.stillOpen)
        val waiting = async(Dispatchers.Unconfined) { sheets.pay(paySheet) }
        assertEquals(WalletSheet.Payment(paySheet), sheets.shown)

        controller.pause().stop().destroy()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertFalse("the page closed with a sheet waiting: that is a no", waiting.await())
        assertFalse(sheets.stillOpen)
        assertNull(sheets.shown)
        // Nothing more is asked once closed.
        assertFalse(sheets.pay(paySheet))
        assertNull(sheets.connect(connectSheet))
    }

    @Test fun `the page leaving the composition (a lock removes it) does the same`() = runBlocking<Unit> {
        val sheets = PhoneWalletSheets(CoroutineScope(Dispatchers.Unconfined))
        var shown by mutableStateOf(true)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { if (shown) OrgPageScreen(page, host, onClose = {}, sheets = sheets) }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val waiting = async(Dispatchers.Unconfined) { sheets.pay(paySheet) }
        assertTrue(sheets.shown != null)

        shown = false
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))

        assertFalse(waiting.await())
        assertFalse(sheets.stillOpen)
    }
}
