package com.sphinx.peel.engine

import com.sphinx.peel.storage.Storage
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// `WalletWords` (iOS `AppModel+Wallet.swift`) and the Storage-backed connection actions of `AppModel` (`setOverLimit`, `setSpendingLimit`, `revokeWalletConnection`,
/// `walletUsage`), which are not UI.
@RunWith(RobolectricTestRunner::class)
class WalletWordsTest {
    private fun sheet(asksWallet: Boolean = true, signIn: SignInOffer? = null) = WalletConnectSheet(
        "Bench", "bench", "v3", "Acme", true, emptyList(), emptyList(), emptyList(), null, WalletRenewal.NEVER, null, null, asksWallet, signIn,
    )

    @Test fun `each method is said once in words`() {
        assertEquals("Pay invoices", WalletWords.method(WalletMethod.PAY_INVOICE))
        assertEquals("Send sats straight to people and nodes", WalletWords.method(WalletMethod.PAY_KEYSEND))
        assertEquals("Make invoices for you to be paid", WalletWords.method(WalletMethod.MAKE_INVOICE))
        assertEquals("Look up its own payments", WalletWords.method(WalletMethod.LOOKUP_PAYMENT))
        assertEquals("List its own payments", WalletWords.method(WalletMethod.LIST_TRANSACTIONS))
        assertEquals("See what is left of its spending limit", WalletWords.method(WalletMethod.GET_BUDGET))
        assertEquals("See your balance", WalletWords.method(WalletMethod.GET_BALANCE))
        assertEquals("Pay Lightning addresses", WalletWords.method(WalletMethod.PAY_LIGHTNING_ADDRESS))
        assertEquals("Ask to connect", WalletWords.method(WalletMethod.CONNECT))
        // The two lookups are one line.
        assertEquals(listOf("Look up its own payments", "Pay invoices"), WalletWords.methods(listOf(WalletMethod.LOOKUP_PAYMENT, WalletMethod.LOOKUP_INVOICE, WalletMethod.PAY_INVOICE)))
    }

    @Test fun `the limit is worded by its renewal`() {
        val us = Locale.US
        assertEquals("No spending limit: it asks you before every payment", WalletWords.limit(null, WalletRenewal.WEEKLY, us))
        val five = Sats.format(5_000_000, us)
        assertEquals("Up to $five in all", WalletWords.limit(5_000_000, WalletRenewal.NEVER, us))
        assertEquals("Up to $five a day", WalletWords.limit(5_000_000, WalletRenewal.DAILY, us))
        assertEquals("Up to $five a week", WalletWords.limit(5_000_000, WalletRenewal.WEEKLY, us))
        assertEquals("Up to $five a month", WalletWords.limit(5_000_000, WalletRenewal.MONTHLY, us))
        assertEquals("Up to $five a year", WalletWords.limit(5_000_000, WalletRenewal.YEARLY, us))
        assertTrue(five.contains("5,000"))
    }

    @Test fun `the sheets' titles and answers follow what they ask`() {
        val offer = SignInOffer("shop.example.com", listOf(SignInKey.root))
        assertEquals("Connect Bench to your wallet?", WalletWords.connectTitle(sheet()))
        assertEquals("Sign in to shop.example.com?", WalletWords.connectTitle(sheet(asksWallet = false, signIn = offer)))
        assertEquals("Sign in to shop.example.com and connect your wallet?", WalletWords.connectTitle(sheet(signIn = offer)))
        assertEquals("Connect" to "Don't connect", WalletWords.connectAnswers(sheet()))
        assertEquals("Sign in" to "Don't sign in", WalletWords.connectAnswers(sheet(asksWallet = false, signIn = offer)))
        assertEquals("Sign in and connect" to "Not now", WalletWords.connectAnswers(sheet(signIn = offer)))
        assertEquals("Every app on Acme shares this connection and its spending limit.", WalletWords.sharedConnection(sheet()))
        assertEquals("Every app on Acme shares this connection.", WalletWords.sharedConnection(sheet(asksWallet = false)))
    }

    @Test fun `signing in and who asks are worded`() {
        assertEquals("As you", WalletWords.signIn(SignInKey.root))
        assertEquals("As a new identity for this app", WalletWords.signIn(SignInKey.domain))
        assertEquals("Your Sphinx account: every app you sign in to this way sees the same you.", WalletWords.signInNote(SignInKey.root))
        assertEquals("A key for this app alone: no other app can tell it is you. Payments still come from your account.", WalletWords.signInNote(SignInKey.domain))
        assertEquals("Signed in to x.io as you", WalletWords.signedIn("x.io", SignInKey.root))
        assertEquals("Signed in to x.io as its own identity", WalletWords.signedIn("x.io", SignInKey.domain))
        assertEquals("Signs you in to x.io as you", WalletWords.signsIn(SignInKey.root, "x.io"))
        assertEquals("Signs you in to x.io as a new identity", WalletWords.signsIn(SignInKey.domain, "x.io"))
        assertEquals("An app on Acme, which calls itself Bench", WalletWords.who("Bench", "bench", "v3", "Acme", byOrg = true))
        assertEquals("Bench · bench@v3 · Yours", WalletWords.who("Bench", "bench", "v3", "Yours", byOrg = false))
        assertEquals("Over the limit, ask me", WalletWords.overLimit(OverBudget.ASK))
        assertEquals("Over the limit, refuse", WalletWords.overLimit(OverBudget.REFUSE))
        assertEquals("Ask me each time", WalletWords.overLimitChoice(OverBudget.ASK))
        assertEquals("Refuse", WalletWords.overLimitChoice(OverBudget.REFUSE))
        assertEquals("Bench asks to pay", WalletWords.paymentTitle(WalletPaymentSheet("Bench", "bench", "v3", "Acme", true, 1, "p", null, null, null, null, true, false, 0, null)))
    }

    /// Connected apps: a connection's limit and over-limit choice change in place, it is revoked, and its usage is read (iOS `AppModel+Wallet`).
    @Test fun `connected apps are changed, revoked and read from storage`() {
        val storage = Storage(ApplicationProvider.getApplicationContext(), account = "ww-${System.nanoTime()}")
        storage.saveWalletConnection(WalletConnection("org:acme", "/b", "B", "Acme", listOf(WalletMethod.PAY_KEYSEND), maxAmountMsat = 5_000, createdAt = 1))
        assertTrue(storage.setSpendingLimit("org:acme", 9_000))
        assertTrue(storage.setOverLimit("org:acme", OverBudget.REFUSE))
        assertEquals(9_000L, storage.walletConnection("org:acme")!!.maxAmountMsat)
        assertEquals(OverBudget.REFUSE, storage.walletConnection("org:acme")!!.overBudget)
        assertFalse(storage.setSpendingLimit("org:none", 1))
        assertEquals(0L, storage.walletUsage("org:acme", now = 100)!!.usedMsat)
        storage.deleteWalletConnection("org:acme")
        assertNull(storage.walletConnection("org:acme"))
        assertNull(storage.walletUsage("org:acme"))
    }

    @Test fun `the credit sheet's words are iOS's`() {
        val sheet = WalletCreditSheet("shop.example.com", "Voxel Studio", "acme/voxel", "v3", "acme/wf", 250_000_000, 250.0, "credit", 1)
        assertEquals("Buy credit on shop.example.com?", WalletWords.creditTitle(sheet))
        assertEquals("Voxel Studio · acme/voxel@v3, as shop.example.com names it", WalletWords.creditApp(sheet))
        assertEquals("\$250.00 of credit, by the server's own figure", WalletWords.creditBuys(sheet, Locale.US))
        assertEquals("This buys credit on shop.example.com. Runs in this app spend it, and what a run does not use stays there.", WalletWords.creditNote(sheet))
    }
}
