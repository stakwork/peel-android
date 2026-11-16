package com.sphinx.peel.ui.you

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.sphinx.peel.engine.ConnectedAppRow
import com.sphinx.peel.engine.OverBudget
import com.sphinx.peel.engine.SignInKey
import com.sphinx.peel.engine.WalletConnection
import com.sphinx.peel.engine.WalletMethod
import com.sphinx.peel.engine.WalletRenewal
import com.sphinx.peel.engine.WalletWords
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.theme.PeelTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// You › Connected apps drawn over a real (Robolectric SQLite) `Storage` (docs/phases/41-test-coverage.md): the empty text, a connection's
/// words, the limit edit with Save, the over-limit choice and Disconnect, each checked in storage.
@RunWith(RobolectricTestRunner::class)
class ConnectedAppsUiTest {
    @get:Rule val rule = createComposeRule()

    private val storage = Storage(ApplicationProvider.getApplicationContext(), account = "connected-${System.nanoTime()}")
    private val acme = WalletConnection(
        "org:acme", "acme/bench", "Bench", "Acme", listOf(WalletMethod.PAY_INVOICE), emptyList(), 5_000_000, WalletRenewal.WEEKLY, null,
        OverBudget.ASK, 1_000, null,
    )

    private fun show() = rule.setContent { PeelTheme { ConnectedAppsScreen(storage, onBack = {}) } }

    @Test fun `with none the screen says how one appears`() {
        show()
        rule.onNodeWithText(ConnectedAppRow.EMPTY).assertExists()
    }

    @Test fun `a connection shows its words`() {
        storage.saveWalletConnection(acme)
        show()
        rule.onNodeWithText("Bench").assertExists()
        rule.onNodeWithText("Acme").assertExists()
        rule.onNodeWithText(WalletWords.limit(5_000_000, WalletRenewal.WEEKLY)).assertExists()
        rule.onNodeWithText(ConnectedAppRow.DISCONNECT).assertExists()
    }

    @Test fun `a sign in alone shows no limit rows`() {
        storage.saveWalletConnection(acme.copy(methods = emptyList(), signIn = SignInKey.root))
        show()
        rule.onNodeWithText(WalletWords.signsIn(SignInKey.root, "Acme")).assertExists()
        rule.onNodeWithText(ConnectedAppRow.OVER_LIMIT).assertDoesNotExist()
    }

    @Test fun `editing the limit enables Save and saving writes it`() {
        storage.saveWalletConnection(acme)
        show()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextReplacement("1000")
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        assertEquals(1_000_000L, storage.walletConnections().single().maxAmountMsat)
        rule.onNodeWithText(WalletWords.limit(1_000_000, WalletRenewal.WEEKLY)).assertExists()
    }

    @Test fun `an empty limit clears it`() {
        storage.saveWalletConnection(acme)
        show()
        rule.onNode(hasSetTextAction()).performTextReplacement("")
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        assertEquals(null, storage.walletConnections().single().maxAmountMsat)
    }

    @Test fun `the over limit choice is written at once`() {
        storage.saveWalletConnection(acme)
        show()
        rule.onNodeWithText(ConnectedAppRow.overLimitChoice(OverBudget.REFUSE)).performClick()
        rule.waitForIdle()
        assertEquals(OverBudget.REFUSE, storage.walletConnections().single().overBudget)
    }

    @Test fun `disconnect removes the connection and the empty text returns`() {
        storage.saveWalletConnection(acme)
        show()
        rule.onNodeWithText(ConnectedAppRow.DISCONNECT).performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(storage.walletConnections().isEmpty())
        rule.onNodeWithText(ConnectedAppRow.EMPTY).assertExists()
    }
}
