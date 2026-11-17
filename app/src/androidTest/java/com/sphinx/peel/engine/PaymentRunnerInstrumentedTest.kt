package com.sphinx.peel.engine

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sphinx.peel.storage.Storage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/// `Engine.paymentRunner()` over the real native core, as far as a device with no server can take it (docs/phases/33-app-payments.md "UNTESTED"):
/// the runner reflects the engine, an unconnected engine refuses and records nothing, and an unreadable invoice throws. The paid path
/// needs a server and a funded account and is not covered. NOT RUN where this was written (no emulator): compiled only; the expectations
/// below are read from the code, not observed.
@RunWith(AndroidJUnit4::class)
class PaymentRunnerInstrumentedTest {
    private val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val storage = Storage(ApplicationProvider.getApplicationContext(), account = "runner-${System.nanoTime()}")
    private val engine = Engine(mnemonic, storage)

    @Test fun theRunnerReflectsTheEngine() {
        val runner = engine.paymentRunner()
        assertEquals(engine.accountKey, runner.accountKey)
        assertEquals(engine.network, runner.network)
        assertFalse(runner.isConnected)
        // Nothing is registered, so there is no contact info and so no own keys.
        assertNull(runner.ownKeys)
    }

    @Test fun anInvoiceTheCoreCannotReadThrows() {
        try {
            engine.paymentRunner().decodeInvoice("not an invoice")
            fail("expected the core to refuse")
        } catch (e: Exception) {
            // Any refusal: what matters is that it is not read as an invoice.
        }
    }

    @Test fun aKeysendWithoutAServerIsRefusedAndRecordsNothing() = runBlocking {
        val payee = "02" + "a".repeat(64)
        try {
            engine.paymentRunner().sendKeysend(payee, 1_000, null, null) { _, _ -> Storage.Commit() }
            fail("an unconnected engine cannot send")
        } catch (e: AppPaymentFailure) {
            assertEquals(AppFailure.NOT_CONNECTED, e.failure)
        } catch (e: WalletRefusal) {
            // The core refusing first (no state) maps to PAYMENT_FAILED.
            assertEquals(WalletError.PAYMENT_FAILED, e.code)
        }
        assertTrue(storage.walletRecords(null).isEmpty())
    }
}
