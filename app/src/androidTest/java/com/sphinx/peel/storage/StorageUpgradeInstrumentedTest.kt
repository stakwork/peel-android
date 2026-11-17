package com.sphinx.peel.storage

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sphinx.peel.engine.OverBudget
import com.sphinx.peel.engine.SignInKey
import com.sphinx.peel.engine.WalletRenewal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/// A database as PR #74 left it (version 13) upgraded by the app's own `Storage` on the device's real SQLite (docs/phases/32-wallet-storage.md
/// "UNTESTED": the JVM test runs on Robolectric's SQLite). the test asserts the app's own `Storage.VERSION`, so it follows
/// each later migration. NOT RUN where this was written (no emulator): compiled only.
@RunWith(AndroidJUnit4::class)
class StorageUpgradeInstrumentedTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun v13(account: String) {
        val file = context.getDatabasePath("peel-$account.db").also { it.parentFile!!.mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE setting (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        db.execSQL("INSERT INTO setting VALUES ('network', 'bitcoin')")
        db.execSQL(
            "CREATE TABLE invoice (paymentHash TEXT NOT NULL, incoming INTEGER NOT NULL, bolt11 TEXT NOT NULL, amountMsat INTEGER NOT NULL, memo TEXT NOT NULL, " +
                "payee TEXT NOT NULL, createdAt INTEGER NOT NULL, expiresAt INTEGER, status TEXT NOT NULL, paidAt INTEGER, paidMsat INTEGER, tag TEXT, chat TEXT, " +
                "error TEXT, PRIMARY KEY (paymentHash, incoming))",
        )
        db.execSQL("CREATE INDEX invoice_tag ON invoice(tag)")
        db.execSQL(
            "CREATE TABLE payment (rhash TEXT PRIMARY KEY NOT NULL, ts INTEGER NOT NULL, amountMsat INTEGER NOT NULL, incoming INTEGER NOT NULL, msgIndex INTEGER, " +
                "remote INTEGER NOT NULL, error TEXT)",
        )
        db.execSQL(
            "CREATE TABLE wallet_connection (key TEXT PRIMARY KEY NOT NULL, app TEXT NOT NULL, title TEXT NOT NULL, place TEXT NOT NULL, methods TEXT NOT NULL, " +
                "signIn TEXT, createdAt INTEGER NOT NULL)",
        )
        db.execSQL("INSERT INTO wallet_connection VALUES ('origin:https://shop.example.com', '/moon-game', 'Moon Game', 'shop.example.com', '[]', 'root', 1793800000123)")
        db.version = 13
        db.close()
    }

    @Test fun aV13DatabaseOpensWithItsRowsAndTheWalletsNewShape() {
        val account = "upgrade-${System.nanoTime()}"
        v13(account)
        val storage = Storage(context, account)
        // The engine's own first statement runs the upgrade.
        assertEquals("bitcoin", storage.value(Setting.NETWORK))
        val shop = storage.walletConnection("origin:https://shop.example.com")!!
        assertEquals(SignInKey.root, shop.signIn)
        assertEquals(1_793_800_000L, shop.createdAt)
        assertEquals(WalletRenewal.NEVER, shop.renewal)
        assertEquals(OverBudget.ASK, shop.overBudget)
        assertNull(shop.maxAmountMsat)
        assertTrue(storage.walletRecords(null).isEmpty())
        val db = SQLiteDatabase.openDatabase(context.getDatabasePath("peel-$account.db").path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            assertEquals("the upgrade left the version at ${db.version}", Storage.VERSION, db.version)
        } finally {
            db.close()
        }
    }
}
