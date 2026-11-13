package com.sphinx.peel.ui.page

import com.sphinx.peel.engine.SignInKey
import com.sphinx.peel.engine.SignInOffer
import com.sphinx.peel.engine.WalletConnectAnswer
import com.sphinx.peel.engine.WalletConnectSheet
import com.sphinx.peel.engine.WalletPaymentSheet
import com.sphinx.peel.engine.WalletRenewal
import com.sphinx.peel.engine.WalletSheet
import com.sphinx.peel.engine.WalletSheetAnswer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// The sheet slot of a page (iOS `PhoneWalletSheets`): one at a time, answered once, a no once the page is closed or Peel locked.
@RunWith(RobolectricTestRunner::class)
class PhoneWalletSheetsTest {
    private val connectSheet = WalletConnectSheet(
        "Bench", "acme/bench", "v3", "Acme", true, emptyList(), emptyList(), emptyList(), null, WalletRenewal.NEVER, null, null, true, null,
    )
    private val paySheet = WalletPaymentSheet("Bench", "b", "v3", "Acme", true, 2_000, "pk", null, null, null, null, false, false, 0, null)

    @Test fun `a connect sheet shows and the persons answer comes back`() = runBlocking {
        val sheets = PhoneWalletSheets(this)
        val asked = async(Dispatchers.Unconfined) { sheets.connect(connectSheet) }
        assertEquals(WalletSheet.Connect(connectSheet), sheets.shown)
        val answer = WalletConnectAnswer(emptyList(), 1_000, signIn = SignInKey.root)
        sheets.answer(WalletSheetAnswer(connect = answer))
        assertEquals(answer, asked.await())
        assertNull(sheets.shown)
    }

    @Test fun `a payment sheet answers paid or not`() = runBlocking {
        val sheets = PhoneWalletSheets(this)
        val yes = async(Dispatchers.Unconfined) { sheets.pay(paySheet) }
        assertEquals(WalletSheet.Payment(paySheet), sheets.shown)
        sheets.answer(WalletSheetAnswer(paid = true))
        assertTrue(yes.await())
        val no = async(Dispatchers.Unconfined) { sheets.pay(paySheet) }
        sheets.dismiss()
        assertFalse(no.await())
    }

    @Test fun `a second sheet while one is up is a no and the first stays`() = runBlocking {
        val sheets = PhoneWalletSheets(this)
        val first = async(Dispatchers.Unconfined) { sheets.pay(paySheet) }
        assertFalse(sheets.pay(paySheet))
        assertEquals(WalletSheet.Payment(paySheet), sheets.shown)
        sheets.answer(WalletSheetAnswer(paid = true))
        assertTrue(first.await())
    }

    @Test fun `closing the page answers a waiting sheet as a no and asks nothing after`() = runBlocking {
        val sheets = PhoneWalletSheets(this)
        val waiting = async(Dispatchers.Unconfined) { sheets.pay(paySheet) }
        assertTrue(sheets.shown != null)
        sheets.close()
        assertFalse(waiting.await())
        assertNull(sheets.shown)
        assertFalse(sheets.stillOpen)
        assertNull(sheets.connect(connectSheet))
        assertNull(sheets.shown)
    }

    @Test fun `a yes given after the page closed counts as no`() = runBlocking {
        val sheets = PhoneWalletSheets(this)
        val waiting = async(Dispatchers.Unconfined) { sheets.pay(paySheet) }
        // The page closes between the tap and the answer reaching the slot: the answer is judged when given.
        val late = WalletSheetAnswer(paid = true)
        sheets.close()
        sheets.answer(late)
        assertFalse(waiting.await())
    }

    @Test fun `the host cancelling a sheet takes it down`() = runBlocking {
        val sheets = PhoneWalletSheets(this)
        val waiting = async(Dispatchers.Unconfined, start = CoroutineStart.DEFAULT) { sheets.pay(paySheet) }
        assertTrue(sheets.shown != null)
        waiting.cancel()
        yield()
        assertNull(sheets.shown)
        // The slot is free for the next one.
        val next = async(Dispatchers.Unconfined) { sheets.pay(paySheet) }
        assertTrue(sheets.shown != null)
        sheets.answer(WalletSheetAnswer(paid = true))
        assertTrue(next.await())
    }

    @Test fun `a sign in within a connection is a toast in iOS's words and is announced`() = runBlocking {
        val said = mutableListOf<String>()
        val sheets = PhoneWalletSheets(this) { said += it }
        sheets.signedIn("acme.example", SignInKey.root)
        assertEquals("Signed in to acme.example as you", sheets.toast)
        assertEquals(listOf("Signed in to acme.example as you"), said)
        sheets.signedIn("acme.example", SignInKey.domain)
        assertEquals("Signed in to acme.example as its own identity", sheets.toast)
        sheets.close()
        assertNull(sheets.toast)
    }

    @Test fun `a sign in offer rides the same connect sheet`() = runBlocking {
        val sheets = PhoneWalletSheets(this)
        val offered = connectSheet.copy(asksWallet = false, signIn = SignInOffer("acme.example", listOf(SignInKey.root)))
        val asked = async(Dispatchers.Unconfined) { sheets.connect(offered) }
        assertEquals("connect acme/bench acme.example", sheets.shown!!.id)
        sheets.dismiss()
        assertNull(asked.await())
    }
}
