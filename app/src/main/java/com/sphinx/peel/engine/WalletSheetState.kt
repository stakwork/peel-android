package com.sphinx.peel.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/// The pure state behind the wallet's sheets and Connected apps (iOS `WalletConnectPhoneSheet`, `WalletPaymentPhoneSheet`,
/// `TouchPayButton`, `PaymentAttempt`, `ConnectedAppsScreen`; `Apps/iOS/Peel/OrgWallet.swift` and `PayControls.swift`), without any
/// Compose, so each rule is JVM-tested. The composables only draw these.

/// A yes that cannot come as a sheet rises: a tap meant for the page is never the yes (iOS `TouchPayButton.settle`, 700 ms).
/// The button is also drawn off until [isArmed], but the click is checked here too, so a late recomposition cannot let one through.
class ArmingGate(private val clock: () -> Long, private val settleMs: Long = SETTLE_MS) {
    private var openedAt: Long = clock()

    /// The sheet (or the armed Pay) has just shown: the clock starts again.
    fun open() {
        openedAt = clock()
    }

    val isArmed: Boolean get() = clock() - openedAt >= settleMs

    /// Runs [yes] only once armed; whether it ran.
    fun yes(yes: () -> Unit): Boolean {
        if (!isArmed) return false
        yes()
        return true
    }

    companion object {
        const val SETTLE_MS = 700L
    }
}

/// What the pay step answers (iOS `PaymentOutcome`).
sealed class PayOutcome {
    object Sent : PayOutcome()
    data class NeedsConfirmation(val msat: Long) : PayOutcome()
    data class Refused(val reason: String) : PayOutcome()
    object Busy : PayOutcome()
}

/// iOS `PaymentAttempt`: the amount the engine asked to confirm, once asked; not twice while one is on its way.
class PayAttempt {
    data class State(val armedMsat: Long? = null, val working: Boolean = false, val problem: String? = null)

    private val flow = MutableStateFlow(State())
    val state: StateFlow<State> get() = flow

    /// Returns whether it was sent.
    suspend fun run(send: suspend (confirmedMsat: Long?) -> PayOutcome): Boolean {
        val before = flow.value
        if (before.working) return false
        flow.value = before.copy(working = true, problem = null)
        return when (val outcome = send(before.armedMsat)) {
            PayOutcome.Sent -> { flow.value = State(); true }
            is PayOutcome.NeedsConfirmation -> { flow.value = flow.value.copy(working = false, armedMsat = outcome.msat); false }
            is PayOutcome.Refused -> { flow.value = State(problem = outcome.reason); false }
            PayOutcome.Busy -> { flow.value = flow.value.copy(working = false); false }
        }
    }

    /// Cancel, or a change to the amount: back to the first step.
    fun disarm() {
        flow.value = flow.value.copy(armedMsat = null)
    }
}

/// The touch-safe two-step the payment sheet's Pay button is (iOS `TouchPayButton` with the payment sheet's `send`): the first tap
/// arms the amount the sheet shows ([ARMED_NOTE]); the second, only once the gate has settled again, pays exactly that amount.
class WalletPayStep(
    val amountMsat: Long, clock: () -> Long, settleMs: Long = ArmingGate.SETTLE_MS, val armedNote: String = ARMED_NOTE,
) {
    val attempt = PayAttempt()
    val gate = ArmingGate(clock, settleMs)

    /// A tap on Pay (first step) or on the armed "Pay N sats" (second). True when the payment is approved.
    suspend fun tap(): Boolean {
        val armed = attempt.state.value.armedMsat
        // Armed but not settled: ignored (iOS disables the button).
        if (armed != null && !gate.isArmed) return false
        val sent = attempt.run { confirmed -> if (confirmed == amountMsat) PayOutcome.Sent else PayOutcome.NeedsConfirmation(amountMsat) }
        // The first step just armed: the second yes waits its own 700 ms.
        if (!sent) gate.open()
        return sent
    }

    fun cancel() = attempt.disarm()

    companion object {
        const val ARMED_NOTE = "That's over this app's spending limit. Check it, then confirm."
    }
}

/// The connect sheet's form (iOS `WalletConnectPhoneSheet`): the optional methods the person keeps, the spending limit typed in sats
/// (lowered here, never above what was asked; the host enforces lower-only again), what to do over it, and who to sign in as.
class WalletConnectForm(val sheet: WalletConnectSheet) {
    var limitText: String = sheet.maxAmountMsat?.let { (it / 1000).toString() } ?: ""
    var overBudget: OverBudget = sheet.held?.overBudget ?: OverBudget.ASK
    private val kept = sheet.optional.toMutableSet()
    var identity: SignInKey? = sheet.signIn?.keys?.firstOrNull()

    fun keeps(method: WalletMethod) = method in kept

    fun toggle(method: WalletMethod, on: Boolean) {
        if (on) kept.add(method) else kept.remove(method)
    }

    /// The typed sats as msat, never above what the app asked for; null when empty or not a number of sats.
    val limitMsat: Long?
        get() {
            val typed = Sats.msat(limitText) ?: return null
            return sheet.maxAmountMsat?.let { minOf(typed, it) } ?: typed
        }

    val limitSentence: String get() = WalletWords.limit(limitMsat, sheet.renewal)

    /// Not as the sheet rises ([settled]), and a sign-in offered needs a key chosen.
    fun canConfirm(settled: Boolean) = settled && !(sheet.signIn != null && identity == null)

    fun answer() = WalletConnectAnswer(
        optional = sheet.optional.filter { it in kept }, maxAmountMsat = limitMsat, overBudget = overBudget, signIn = identity,
    )
}

/// One connection in You › Connected apps (iOS `ConnectedAppsScreen`, `ConnectedLimitRow`).
class ConnectedAppRow(
    val connection: WalletConnection,
    val usedMsat: Long? = null,
    val limitText: String = connection.maxAmountMsat?.let { (it / 1000).toString() } ?: "",
) {
    val title: String get() = connection.title.ifEmpty { connection.place }
    val place: String get() = connection.place
    val signInLine: String? get() = connection.signIn?.let { WalletWords.signsIn(it, connection.place) }

    /// The limit, over-limit and spent rows are for a connection that holds methods (a sign-in alone has none).
    val hasWallet: Boolean get() = connection.methods.isNotEmpty()
    val limitSentence: String get() = WalletWords.limit(connection.maxAmountMsat, connection.renewal)
    val overBudget: OverBudget get() = connection.overBudget

    /// The typed limit as msat; null clears it.
    val typedMsat: Long? get() = Sats.msat(limitText)
    val canSave: Boolean get() = typedMsat != connection.maxAmountMsat

    /// "N spent this period", only under a limit.
    val spentLine: String? get() = usedMsat?.takeIf { connection.maxAmountMsat != null }?.let { "${Sats.format(it)} spent this period" }

    fun withLimitText(text: String) = ConnectedAppRow(connection, usedMsat, text)

    companion object {
        const val EMPTY = "An app that asks to use your wallet appears here once you connect it."
        const val DISCONNECT = "Disconnect"
        const val OVER_LIMIT = "Over the limit"
        fun overLimitChoice(choice: OverBudget) = if (choice == OverBudget.ASK) "Ask me" else "Refuse"

        /// The You tab's row: "None", or how many.
        fun summary(count: Int) = if (count == 0) "None" else "$count"
    }
}

/// Which sheet the page shows (iOS `PhoneWalletSheets.Sheet`) and the id iOS gives it.
sealed class WalletSheet {
    abstract val id: String

    data class Connect(val sheet: WalletConnectSheet) : WalletSheet() {
        override val id get() = "connect ${sheet.app} ${sheet.signIn?.host ?: ""}"
    }

    data class Payment(val sheet: WalletPaymentSheet) : WalletSheet() {
        override val id get() = "pay ${sheet.payee} ${sheet.amountMsat}"
    }

    data class Credit(val sheet: WalletCreditSheet) : WalletSheet() {
        override val id get() = "credit ${sheet.host} ${sheet.amountMsat}"
    }
}

/// What the person answered (iOS `PhoneWalletSheets.Answer`); the empty answer is a no.
data class WalletSheetAnswer(val connect: WalletConnectAnswer? = null, val paid: Boolean = false)
