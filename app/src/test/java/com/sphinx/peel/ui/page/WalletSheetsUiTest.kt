package com.sphinx.peel.ui.page

import android.app.Dialog
import android.view.MotionEvent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performClick
import com.sphinx.peel.engine.OverBudget
import com.sphinx.peel.engine.SignInKey
import com.sphinx.peel.engine.SignInOffer
import com.sphinx.peel.engine.Sats
import com.sphinx.peel.engine.WalletConnectAnswer
import com.sphinx.peel.engine.WalletConnectSheet
import com.sphinx.peel.engine.WalletCreditSheet
import com.sphinx.peel.engine.WalletMethod
import com.sphinx.peel.engine.WalletPaymentSheet
import com.sphinx.peel.engine.WalletPayStep
import com.sphinx.peel.engine.WalletRenewal
import com.sphinx.peel.engine.WalletWords
import com.sphinx.peel.ui.theme.PeelTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog

/// The three wallet sheets and the sign-in-only connect sheet, drawn under Robolectric (docs/phases/41-test-coverage.md): the arming delay
/// (Compose's clock for the 700 ms `delay`, an injected one for the gate), the second-tap pay flow, Back = decline, a tap outside
/// ignored, and the words (all from [WalletWords]). The real device feel (layout, keyboard, TalkBack) is still not covered.
@RunWith(RobolectricTestRunner::class)
class WalletSheetsUiTest {
    @get:Rule val rule = createComposeRule()

    private var now = 0L
    private val clock: () -> Long = { now }

    private val signInOnly = WalletConnectSheet(
        "Moon Game", "moon-game", "v3", "shop.example.com", false, emptyList(), emptyList(), emptyList(), null, WalletRenewal.NEVER, null, null,
        asksWallet = false, signIn = SignInOffer("shop.example.com", listOf(SignInKey.root, SignInKey.domain)),
    )
    private val wallet = WalletConnectSheet(
        "Bench", "acme/bench", "v3", "Acme", true, listOf(WalletMethod.PAY_INVOICE), listOf(WalletMethod.GET_BALANCE), emptyList(),
        5_000_000, WalletRenewal.WEEKLY, null, null,
    )
    private val pay = WalletPaymentSheet("Bench", "bench", "v3", "Acme", true, 2_000_000, "02abcdef0123", null, "for tea", null, "pay up", false, false, 1_000_000, 5_000_000)
    private val credit = WalletCreditSheet("shop.example.com", "Moon Game", "moon-game", "v3", "launch", 3_000_000, 1.5, "Moon credit", 4_000_000_000)

    private fun connect(sheet: WalletConnectSheet, done: (WalletConnectAnswer?) -> Unit) =
        rule.setContent { PeelTheme { WalletConnectPhoneSheet(sheet, clock, done) } }

    /// The sheet's yes: its own words also head a section ("Sign in"), so the one that clicks.
    private fun yes(words: String) = rule.onNode(hasText(words) and hasClickAction())

    private fun armedPay(msat: Long) = rule.onNodeWithText("Pay ${Sats.format(msat)}")

    private fun latestDialog(): Dialog = ShadowDialog.getLatestDialog()

    @Test fun `a sign in alone has the sign in words and no wallet sections`() {
        connect(signInOnly) {}
        rule.onNodeWithText(WalletWords.connectTitle(signInOnly)).assertExists()
        yes("Sign in").assertExists()
        rule.onNodeWithText("Don't sign in").assertExists()
        rule.onNodeWithText(WalletWords.sharedConnection(signInOnly)).assertExists()
        rule.onNodeWithText(WalletWords.signIn(SignInKey.root)).assertExists()
        rule.onNodeWithText(WalletWords.signIn(SignInKey.domain)).assertExists()
        rule.onNodeWithText("Spending limit").assertDoesNotExist()
        rule.onNodeWithText("It may").assertDoesNotExist()
    }

    @Test fun `the yes is off while the sheet rises and the gate holds a click until the clock agrees`() {
        val answers = mutableListOf<WalletConnectAnswer?>()
        // Frozen only for the assertions (a frozen clock with taps and scrolling can stall the test).
        rule.mainClock.autoAdvance = false
        connect(signInOnly) { answers += it }
        yes("Sign in").assertIsNotEnabled()
        rule.mainClock.advanceTimeBy(699)
        yes("Sign in").assertIsNotEnabled()
        rule.mainClock.advanceTimeBy(5)
        yes("Sign in").assertIsEnabled()
        rule.mainClock.autoAdvance = true
        // Enabled on screen, but the gate's own clock has not reached 700 ms: a late recomposition lets nothing through.
        now = 100
        yes("Sign in").performClick()
        assertTrue(answers.isEmpty())
        now = 700
        yes("Sign in").performClick()
        assertEquals(listOf<WalletConnectAnswer?>(WalletConnectAnswer(emptyList(), null, OverBudget.ASK, SignInKey.root)), answers)
    }

    @Test fun `choosing the domain key answers with it`() {
        val answers = mutableListOf<WalletConnectAnswer?>()
        connect(signInOnly) { answers += it }
        rule.onNodeWithText(WalletWords.signIn(SignInKey.domain)).performScrollTo().performClick()
        rule.onNodeWithText(WalletWords.signInNote(SignInKey.domain)).assertExists()
        rule.mainClock.advanceTimeBy(800)
        now = 800
        yes("Sign in").performClick()
        assertEquals(SignInKey.domain, answers.single()?.signIn)
    }

    @Test fun `the no is a decline`() {
        val answers = mutableListOf<WalletConnectAnswer?>()
        connect(signInOnly) { answers += it }
        rule.onNodeWithText("Don't sign in").performClick()
        assertEquals(listOf<WalletConnectAnswer?>(null), answers)
    }

    @Test fun `Back is a decline`() {
        val back = mutableListOf<WalletConnectAnswer?>()
        rule.setContent { PeelTheme { WalletConnectPhoneSheet(wallet, clock) { back += it } } }
        rule.runOnIdle { latestDialog().onBackPressed() }
        rule.waitForIdle()
        assertEquals(listOf<WalletConnectAnswer?>(null), back)
    }

    @Test fun `a touch outside the sheet does not dismiss it`() {
        val answers = mutableListOf<WalletConnectAnswer?>()
        connect(signInOnly) { answers += it }
        rule.runOnIdle {
            val dialog = latestDialog()
            val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, -20f, -20f, 0)
            dialog.onTouchEvent(event)
            dialog.onTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, -20f, -20f, 0))
            assertTrue(dialog.isShowing)
        }
        rule.waitForIdle()
        assertTrue(answers.isEmpty())
    }

    @Test fun `a wallet sheet shows what it may do, the limit and the choices over it`() {
        connect(wallet) {}
        yes("Connect").assertExists()
        rule.onNodeWithText(WalletWords.method(WalletMethod.PAY_INVOICE)).assertExists()
        rule.onNodeWithText(WalletWords.method(WalletMethod.GET_BALANCE)).assertExists()
        rule.onNodeWithText(WalletWords.limit(5_000_000, WalletRenewal.WEEKLY)).assertExists()
        rule.onNodeWithText(WalletWords.overLimitChoice(OverBudget.ASK)).assertExists()
        rule.onNodeWithText(WalletWords.overLimitChoice(OverBudget.REFUSE)).assertExists()
    }

    @Test fun `the over the limit choice goes into the answer`() {
        val answers = mutableListOf<WalletConnectAnswer?>()
        connect(wallet) { answers += it }
        rule.onNodeWithText(WalletWords.overLimitChoice(OverBudget.REFUSE)).performScrollTo().performClick()
        rule.mainClock.advanceTimeBy(800)
        now = 800
        yes("Connect").performClick()
        val answer = answers.single()!!
        assertEquals(OverBudget.REFUSE, answer.overBudget)
        assertEquals(5_000_000L, answer.maxAmountMsat)
        assertEquals(listOf(WalletMethod.GET_BALANCE), answer.optional)
    }

    @Test fun `the payment sheet pays in two taps, the second only once it settled`() {
        val results = mutableListOf<Boolean>()
        rule.setContent { PeelTheme { WalletPaymentPhoneSheet(pay, clock) { results += it } } }
        rule.onNodeWithText(WalletWords.paymentTitle(pay)).assertExists()
        rule.onNodeWithText("Message", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Memo: for tea").assertExists()
        rule.onNodeWithText("The app says: \u201Cpay up\u201D").assertExists()
        rule.onNodeWithText("Pay").performScrollTo().performClick()
        rule.mainClock.advanceTimeBy(50)
        // First tap armed the amount: the note shows, and nothing was paid.
        rule.onNodeWithText(WalletPayStep.ARMED_NOTE).assertExists()
        assertTrue(results.isEmpty())
        armedPay(pay.amountMsat).assertIsNotEnabled()
        rule.mainClock.advanceTimeBy(800)
        armedPay(pay.amountMsat).assertIsEnabled()
        // Enabled, but the gate's clock is still where the first tap left it.
        armedPay(pay.amountMsat).performScrollTo().performClick()
        rule.mainClock.advanceTimeBy(50)
        assertTrue(results.isEmpty())
        now = 800
        armedPay(pay.amountMsat).performScrollTo().performClick()
        rule.mainClock.advanceTimeBy(50)
        assertEquals(listOf(true), results)
    }

    @Test fun `cancel goes back to the first step and decline and Back are a no`() {
        val results = mutableListOf<Boolean>()
        rule.setContent { PeelTheme { WalletPaymentPhoneSheet(pay, clock) { results += it } } }
        rule.onNodeWithText("Pay").performScrollTo().performClick()
        rule.onNodeWithText(WalletPayStep.ARMED_NOTE).assertExists()
        rule.onNodeWithText("Cancel").performScrollTo().performClick()
        rule.onNodeWithText(WalletPayStep.ARMED_NOTE).assertDoesNotExist()
        rule.onNodeWithText("Decline").performClick()
        rule.runOnIdle { latestDialog().onBackPressed() }
        rule.waitForIdle()
        assertEquals(listOf(false, false), results)
    }

    @Test fun `the credit sheet marks the sellers words and arms with its own note`() {
        val results = mutableListOf<Boolean>()
        rule.setContent { PeelTheme { WalletCreditPhoneSheet(credit, clock) { results += it } } }
        rule.onNodeWithText(WalletWords.creditTitle(credit)).assertExists()
        rule.onNodeWithText(WalletWords.creditApp(credit)).assertExists()
        rule.onNodeWithText("Runs launch").assertExists()
        rule.onNodeWithText("Buys " + WalletWords.creditBuys(credit)).assertExists()
        rule.onNodeWithText("The server says: \u201CMoon credit\u201D").assertExists()
        rule.onNodeWithText(WalletWords.creditNote(credit)).assertExists()
        rule.onNodeWithText("Pay").performScrollTo().performClick()
        rule.mainClock.advanceTimeBy(50)
        rule.onNodeWithText(WalletWords.CREDIT_ARMED_NOTE).assertExists()
        rule.mainClock.advanceTimeBy(800)
        now = 800
        armedPay(credit.amountMsat).performScrollTo().performClick()
        rule.mainClock.advanceTimeBy(50)
        assertEquals(listOf(true), results)
    }

    @Test fun `the credit sheets Back is a decline`() {
        val results = mutableListOf<Boolean>()
        rule.setContent { PeelTheme { WalletCreditPhoneSheet(credit, clock) { results += it } } }
        rule.runOnIdle { latestDialog().onBackPressed() }
        rule.waitForIdle()
        assertEquals(listOf(false), results)
    }
}
