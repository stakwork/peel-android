package com.sphinx.peel.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// The wallet sheets' and Connected apps' rules without Compose (iOS `WalletConnectPhoneSheet`, `TouchPayButton`, `PaymentAttempt`, `ConnectedAppsScreen`).
class WalletSheetStateTest {
    private fun connect(
        max: Long? = 5_000_000, optional: List<WalletMethod> = listOf(WalletMethod.GET_BALANCE, WalletMethod.MAKE_INVOICE),
        signIn: SignInOffer? = null, held: WalletConnection? = null, asksWallet: Boolean = true,
    ) = WalletConnectSheet(
        "Bench", "acme/bench", "v3", "Acme", true, listOf(WalletMethod.PAY_INVOICE), optional, listOf(WalletNotificationType.PAYMENT_RECEIVED),
        max, WalletRenewal.WEEKLY, null, held, asksWallet, signIn,
    )

    private fun connection(max: Long? = 5_000_000, methods: List<WalletMethod> = listOf(WalletMethod.PAY_INVOICE), signIn: SignInKey? = null) =
        WalletConnection("org:acme", "acme/bench", "Bench", "Acme", methods, maxAmountMsat = max, renewal = WalletRenewal.WEEKLY, createdAt = 1, signIn = signIn)

    // --- arming ---

    @Test fun `no yes in the first 700 ms and one from then on`() {
        var now = 10_000L
        val gate = ArmingGate({ now })
        var yes = 0
        assertFalse(gate.yes { yes++ })
        now += 699
        assertFalse(gate.isArmed)
        assertFalse(gate.yes { yes++ })
        now += 1
        assertTrue(gate.isArmed)
        assertTrue(gate.yes { yes++ })
        assertEquals(1, yes)
        // Shown again (the armed Pay): the clock starts over.
        gate.open()
        assertFalse(gate.yes { yes++ })
        assertEquals(1, yes)
        assertEquals(700L, ArmingGate.SETTLE_MS)
    }

    // --- the two-step pay ---

    @Test fun `the first tap arms the amount and pays nothing`() = runBlocking {
        var now = 0L
        val step = WalletPayStep(2_000_000, { now })
        assertFalse(step.tap())
        assertEquals(2_000_000L, step.attempt.state.value.armedMsat)
        assertFalse(step.attempt.state.value.working)
    }

    @Test fun `a second tap inside 700 ms of arming is ignored, one after it pays exactly the amount`() = runBlocking {
        var now = 0L
        val step = WalletPayStep(2_000_000, { now })
        now = 5_000
        assertFalse(step.tap())
        now += 300
        assertFalse(step.tap())
        assertEquals(2_000_000L, step.attempt.state.value.armedMsat)
        now += 400
        assertTrue(step.tap())
        assertNull(step.attempt.state.value.armedMsat)
    }

    @Test fun `cancel goes back to the first step and asks again`() = runBlocking {
        var now = 0L
        val step = WalletPayStep(1_000, { now })
        step.tap()
        step.cancel()
        assertNull(step.attempt.state.value.armedMsat)
        now = 10_000
        assertFalse(step.tap())
        assertEquals(1_000L, step.attempt.state.value.armedMsat)
    }

    @Test fun `an attempt is not run twice while one is on its way and a refusal shows its reason`() = runBlocking {
        val attempt = PayAttempt()
        var inner = false
        val sent = attempt.run {
            inner = attempt.run { PayOutcome.Sent }
            PayOutcome.Refused("No.")
        }
        assertFalse(sent)
        assertFalse(inner)
        assertEquals(PayAttempt.State(problem = "No."), attempt.state.value)
        assertFalse(attempt.run { PayOutcome.Busy })
        assertTrue(attempt.run { PayOutcome.Sent })
        assertEquals(PayAttempt.State(), attempt.state.value)
    }

    @Test fun `the armed note is iOS's words for the payment sheet`() {
        assertEquals("That's over this app's spending limit. Check it, then confirm.", WalletPayStep.ARMED_NOTE)
    }

    // --- the connect form ---

    @Test fun `the form starts from the sheet and answers as asked`() {
        val sheet = connect(signIn = SignInOffer("acme.example", listOf(SignInKey.root, SignInKey.domain)))
        val form = WalletConnectForm(sheet)
        assertEquals("5000", form.limitText)
        assertEquals(OverBudget.ASK, form.overBudget)
        assertEquals(SignInKey.root, form.identity)
        assertEquals(WalletConnectAnswer(sheet.optional, 5_000_000, OverBudget.ASK, SignInKey.root), form.answer())
    }

    @Test fun `the limit is lowered but never raised above what was asked and the optional toggles are kept`() {
        val form = WalletConnectForm(connect())
        form.limitText = "1,200"
        assertEquals(1_200_000L, form.limitMsat)
        form.limitText = "9000"
        assertEquals(5_000_000L, form.limitMsat)
        form.limitText = ""
        assertNull(form.limitMsat)
        form.limitText = "12x"
        assertNull(form.limitMsat)
        form.toggle(WalletMethod.GET_BALANCE, false)
        assertFalse(form.keeps(WalletMethod.GET_BALANCE))
        assertEquals(listOf(WalletMethod.MAKE_INVOICE), form.answer().optional)
        form.toggle(WalletMethod.GET_BALANCE, true)
        assertEquals(listOf(WalletMethod.GET_BALANCE, WalletMethod.MAKE_INVOICE), form.answer().optional)
    }

    @Test fun `with no limit asked any typed limit stands and the sentence follows`() {
        val form = WalletConnectForm(connect(max = null))
        assertEquals("", form.limitText)
        assertEquals("No spending limit: it asks you before every payment", form.limitSentence)
        form.limitText = "300"
        assertEquals(300_000L, form.limitMsat)
        assertEquals(WalletWords.limit(300_000, WalletRenewal.WEEKLY), form.limitSentence)
    }

    @Test fun `over the limit starts from what is held and refuse is answered`() {
        val held = connection().copy(overBudget = OverBudget.REFUSE)
        val form = WalletConnectForm(connect(held = held))
        assertEquals(OverBudget.REFUSE, form.overBudget)
        form.overBudget = OverBudget.ASK
        assertEquals(OverBudget.ASK, form.answer().overBudget)
    }

    @Test fun `confirm waits for the sheet to settle and for a key when sign in is offered`() {
        val plain = WalletConnectForm(connect())
        assertFalse(plain.canConfirm(false))
        assertTrue(plain.canConfirm(true))
        val offered = WalletConnectForm(connect(signIn = SignInOffer("h", listOf(SignInKey.domain))))
        assertEquals(SignInKey.domain, offered.identity)
        assertTrue(offered.canConfirm(true))
        offered.identity = null
        assertFalse(offered.canConfirm(true))
    }

    // --- Connected apps ---

    @Test fun `a row says its title place sign in limit and over limit`() {
        val row = ConnectedAppRow(connection(signIn = SignInKey.domain))
        assertEquals("Bench", row.title)
        assertEquals("Acme", row.place)
        assertEquals("Signs you in to Acme as a new identity", row.signInLine)
        assertTrue(row.hasWallet)
        assertEquals(WalletWords.limit(5_000_000, WalletRenewal.WEEKLY), row.limitSentence)
        assertEquals("Ask me", ConnectedAppRow.overLimitChoice(OverBudget.ASK))
        assertEquals("Refuse", ConnectedAppRow.overLimitChoice(OverBudget.REFUSE))
        assertNull(ConnectedAppRow(connection()).signInLine)
        assertEquals("Signs you in to Acme as you", ConnectedAppRow(connection(signIn = SignInKey.root)).signInLine)
        // Title falls back to the place when a connection has none.
        assertEquals("Acme", ConnectedAppRow(connection().copy(title = "")).title)
    }

    @Test fun `a sign in alone has no wallet rows`() {
        assertFalse(ConnectedAppRow(connection(methods = emptyList(), signIn = SignInKey.root)).hasWallet)
    }

    @Test fun `the limit edit saves only a change and an empty field clears it`() {
        val row = ConnectedAppRow(connection())
        assertEquals("5000", row.limitText)
        assertFalse(row.canSave)
        val edited = row.withLimitText("700")
        assertTrue(edited.canSave)
        assertEquals(700_000L, edited.typedMsat)
        val cleared = row.withLimitText("")
        assertTrue(cleared.canSave)
        assertNull(cleared.typedMsat)
        assertFalse(ConnectedAppRow(connection(max = null)).canSave)
    }

    @Test fun `spent this period shows only under a limit and once read`() {
        assertEquals("${Sats.format(1_250_000)} spent this period", ConnectedAppRow(connection(), 1_250_000).spentLine)
        assertNull(ConnectedAppRow(connection(), null).spentLine)
        assertNull(ConnectedAppRow(connection(max = null), 1_000).spentLine)
    }

    @Test fun `the you row says None or how many and the empty screen says its words`() {
        assertEquals("None", ConnectedAppRow.summary(0))
        assertEquals("3", ConnectedAppRow.summary(3))
        assertEquals("An app that asks to use your wallet appears here once you connect it.", ConnectedAppRow.EMPTY)
    }

    @Test fun `sheet ids are iOS's`() {
        assertEquals("connect acme/bench h", WalletSheet.Connect(connect(signIn = SignInOffer("h", listOf(SignInKey.root)))).id)
        assertEquals("connect acme/bench ", WalletSheet.Connect(connect()).id)
        val pay = WalletPaymentSheet("B", "b", "v", "A", true, 2_000, "pk", null, null, null, null, false, false, 0, null)
        assertEquals("pay pk 2000", WalletSheet.Payment(pay).id)
    }
}
