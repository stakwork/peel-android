package com.sphinx.peel.engine

import java.util.Locale

/// The app wallet's words for the person (docs/VISION.md, "How we talk about it": a spending limit, never a budget), for the sheets and Connected apps alike
/// (iOS `WalletWords`, `PeelUI/AppModel+Wallet.swift`). Pure. Not ported: the credit sheet's words (step 7).
object WalletWords {
    /// What a method lets the app do.
    fun method(method: WalletMethod): String = when (method) {
        WalletMethod.PAY_INVOICE -> "Pay invoices"
        WalletMethod.PAY_KEYSEND -> "Send sats straight to people and nodes"
        WalletMethod.MAKE_INVOICE -> "Make invoices for you to be paid"
        WalletMethod.LOOKUP_PAYMENT, WalletMethod.LOOKUP_INVOICE -> "Look up its own payments"
        WalletMethod.LIST_TRANSACTIONS -> "List its own payments"
        WalletMethod.GET_BUDGET -> "See what is left of its spending limit"
        WalletMethod.GET_BALANCE -> "See your balance"
        WalletMethod.PAY_LIGHTNING_ADDRESS -> "Pay Lightning addresses"
        WalletMethod.CONNECT, WalletMethod.GET_INFO -> "Ask to connect"
    }

    /// The methods, each once, in words.
    fun methods(methods: List<WalletMethod>): List<String> = methods.map(::method).distinct()

    /// "Up to 5,000 sats a week", or how every payment is asked.
    fun limit(msat: Long?, renewal: WalletRenewal, locale: Locale = Locale.getDefault()): String {
        if (msat == null) return "No spending limit: it asks you before every payment"
        val amount = Sats.format(maxOf(msat, 0), locale)
        return when (renewal) {
            WalletRenewal.NEVER -> "Up to $amount in all"
            WalletRenewal.DAILY -> "Up to $amount a day"
            WalletRenewal.WEEKLY -> "Up to $amount a week"
            WalletRenewal.MONTHLY -> "Up to $amount a month"
            WalletRenewal.YEARLY -> "Up to $amount a year"
        }
    }

    fun overLimit(choice: OverBudget): String = when (choice) {
        OverBudget.ASK -> "Over the limit, ask me"
        OverBudget.REFUSE -> "Over the limit, refuse"
    }

    /// The picker's two rows for the same choice, on the connect sheet and in Connected apps.
    fun overLimitChoice(choice: OverBudget): String = when (choice) {
        OverBudget.ASK -> "Ask me each time"
        OverBudget.REFUSE -> "Refuse"
    }

    /// Who the person signs in as (strut's plans/app-payments.md §4.4).
    fun signIn(key: SignInKey): String = if (key == SignInKey.root) "As you" else "As a new identity for this app"

    /// What each choice means, under it.
    fun signInNote(key: SignInKey): String =
        if (key == SignInKey.root) "Your Sphinx account: every app you sign in to this way sees the same you."
        else "A key for this app alone: no other app can tell it is you. Payments still come from your account."

    /// The connection sheet's title: connecting a wallet, signing in, or both.
    fun connectTitle(sheet: WalletConnectSheet): String = when {
        !sheet.asksWallet && sheet.signIn != null -> "Sign in to ${sheet.signIn.host}?"
        sheet.asksWallet && sheet.signIn != null -> "Sign in to ${sheet.signIn.host} and connect your wallet?"
        else -> "Connect ${sheet.title} to your wallet?"
    }

    /// Its yes and its no.
    fun connectAnswers(sheet: WalletConnectSheet): Pair<String, String> = when {
        !sheet.asksWallet && sheet.signIn != null -> "Sign in" to "Don't sign in"
        sheet.asksWallet && sheet.signIn != null -> "Sign in and connect" to "Not now"
        else -> "Connect" to "Don't connect"
    }

    /// What every app of a place shares.
    fun sharedConnection(sheet: WalletConnectSheet): String =
        "Every app on ${sheet.place} shares this connection${if (sheet.asksWallet) " and its spending limit" else ""}."

    /// The toast for a sign-in within a connection, with no sheet.
    fun signedIn(host: String, key: SignInKey): String =
        if (key == SignInKey.root) "Signed in to $host as you" else "Signed in to $host as its own identity"

    /// A connection's sign-in in Connected apps.
    fun signsIn(key: SignInKey, place: String): String =
        if (key == SignInKey.root) "Signs you in to $place as you" else "Signs you in to $place as a new identity"

    /// Who is asking, from Peel's own record: the app on the Mac, the org on the phone (whose page names the app).
    fun who(title: String, app: String, version: String, place: String, byOrg: Boolean): String =
        if (byOrg) "An app on $place, which calls itself $title" else "$title · $app@$version · $place"

    /// The credit sheet's title (specs/WALLET.md §1.2).
    fun creditTitle(sheet: WalletCreditSheet) = "Buy credit on ${sheet.host}?"

    /// The app the launch is in, as the server names it.
    fun creditApp(sheet: WalletCreditSheet) = "${sheet.title} · ${sheet.app}@${sheet.version}, as ${sheet.host} names it"

    /// What the server says the payment buys: its own figure, never the price.
    fun creditBuys(sheet: WalletCreditSheet, locale: java.util.Locale = java.util.Locale.getDefault()): String {
        val dollars = java.text.NumberFormat.getCurrencyInstance(locale).apply { currency = java.util.Currency.getInstance("USD") }.format(sheet.usd)
        return "$dollars of credit, by the server's own figure"
    }

    /// What credit is, under the sheet.
    fun creditNote(sheet: WalletCreditSheet) =
        "This buys credit on ${sheet.host}. Runs in this app spend it, and what a run does not use stays there."

    /// The credit sheet's armed Pay (iOS `armedNote`).
    const val CREDIT_ARMED_NOTE = "Check the amount, then confirm."

    /// The payment sheet's title.
    fun paymentTitle(sheet: WalletPaymentSheet) = "${sheet.title} asks to pay"
}
