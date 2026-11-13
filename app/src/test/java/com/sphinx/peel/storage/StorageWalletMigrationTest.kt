package com.sphinx.peel.storage

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.sphinx.peel.engine.InvoiceRecord
import com.sphinx.peel.engine.KeysendRecord
import com.sphinx.peel.engine.OverBudget
import com.sphinx.peel.engine.SignInKey
import com.sphinx.peel.engine.WalletRenewal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// v13 → v14: a database as PR #74 left it (a `wallet_connection` with its methods and sign-in key, `createdAt` in milliseconds, and invoices with no
/// wallet columns) opens at v14 with its rows kept and what the app wallet needs added.
@RunWith(RobolectricTestRunner::class)
class StorageWalletMigrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun v13Database(account: String): java.io.File {
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
        db.execSQL("INSERT INTO wallet_connection VALUES ('origin:https://old.example.com', '/old', 'Old', 'old.example.com', '[\"pay_keysend\",\"mystery\"]', 'domain', 1793800000)")
        db.execSQL("INSERT INTO wallet_connection VALUES ('origin:https://plain.example.com', '/plain', 'Plain', 'plain.example.com', '[]', NULL, 5)")
        db.execSQL(
            "INSERT INTO invoice VALUES ('${"a".repeat(64)}', 0, 'lnbc1', 1000, 'memo', 'peer', 1793800000000, NULL, 'COMPLETE', 1793800001000, NULL, 'tag1', 'chat1', NULL)",
        )
        db.execSQL("INSERT INTO payment VALUES ('${"a".repeat(64)}', 1793800001000, 1010, 0, NULL, 1, NULL)")
        db.version = 13
        db.close()
        return file
    }

    @Test fun `a v13 database opens at v14 with its rows and the wallet's new shape`() {
        val account = "migrate-${System.nanoTime()}"
        v13Database(account)
        val storage = Storage(context, account)
        // The first statement after an upgrade (the engine's own: settings) runs it. (Robolectric's native SQLite can serve the very first read
        // from a connection opened before the upgrade, with the old columns; a second statement is fine, so the test warms with this one.)
        assertEquals("bitcoin", storage.value(Setting.NETWORK))

        // The grants kept, their milliseconds made seconds (by size: seconds are left alone), the new columns defaulted.
        val shop = storage.walletConnection("origin:https://shop.example.com")!!
        assertEquals(SignInKey.root, shop.signIn)
        assertEquals(1_793_800_000L, shop.createdAt)
        assertEquals("Moon Game", shop.title)
        assertEquals("/moon-game", shop.app)
        assertTrue(shop.methods.isEmpty() && shop.notificationTypes.isEmpty())
        assertEquals(WalletRenewal.NEVER, shop.renewal)
        assertEquals(OverBudget.ASK, shop.overBudget)
        assertNull(shop.maxAmountMsat)
        assertNull(shop.expiresAt)
        val old = storage.walletConnection("origin:https://old.example.com")!!
        assertEquals(1_793_800_000L, old.createdAt)
        assertEquals(SignInKey.domain, old.signIn)
        assertEquals(listOf(com.sphinx.peel.engine.WalletMethod.PAY_KEYSEND), old.methods)
        assertEquals(5L, storage.walletConnection("origin:https://plain.example.com")!!.createdAt)
        assertNull(storage.walletConnection("origin:https://plain.example.com")!!.signIn)
        assertEquals(3, storage.walletConnections().size)

        // The invoice and the history it had are as they were, with no wallet fields yet.
        val invoice = storage.invoice("a".repeat(64), false)!!
        assertEquals(InvoiceRecord.Status.PAID, invoice.status)
        assertEquals("chat1", invoice.chat)
        assertNull(invoice.connection)
        assertNull(invoice.preimage)
        assertNull(invoice.app)
        assertEquals(1, storage.payments(10).size)
        assertEquals("bitcoin", storage.value(Setting.NETWORK))
        assertTrue(storage.walletRecords(null).isEmpty())

        // And the new tables work: a keysend, a tagged invoice, a limit, a preimage.
        val key = "origin:https://shop.example.com"
        storage.apply(
            Storage.Commit(
                keysends = listOf(KeysendRecord("b".repeat(64), "k1", "02" + "a".repeat(64), null, false, 2_000, 1_793_800_100_000, InvoiceRecord.Status.PENDING, connection = key, app = "/moon-game")),
                invoices = listOf(InvoiceRecord("c".repeat(64), false, "lnbc2", 3_000, "", "peer", 1_793_800_200_000, null, InvoiceRecord.Status.PENDING, tag = "i2", connection = key, app = "/moon-game")),
            ),
        )
        assertEquals(listOf("c".repeat(64), "b".repeat(64)), storage.walletRecords(key).map { it.paymentHash })
        storage.setSpendingLimit(key, 9_000)
        assertEquals(5_000L, storage.walletUsage(key)!!.usedMsat)
        assertEquals(9_000L, storage.walletConnection(key)!!.maxAmountMsat)
        // The old invoice, never tagged, is nobody's record.
        assertEquals(2, storage.walletRecords(null).size)
    }

    @Test fun `a fresh database has the same shape as a migrated one`() {
        fun columns(db: SQLiteDatabase, table: String) =
            db.rawQuery("PRAGMA table_info($table)", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(1) to c.getString(2)) } }.toSet()
        fun indexes(db: SQLiteDatabase) =
            db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND name IN ('invoice_connection', 'keysend_tag', 'keysend_connection')", null)
                .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }.toSet()
        val fresh = "fresh-${System.nanoTime()}"
        Storage(context, fresh).also { it.value(Setting.NETWORK) }.close()
        val migrated = "migrated-${System.nanoTime()}"
        v13Database(migrated)
        Storage(context, migrated).also { it.value(Setting.NETWORK) }.close()
        for (account in listOf(fresh, migrated)) {
            val db = SQLiteDatabase.openDatabase(context.getDatabasePath("peel-$account.db").path, null, SQLiteDatabase.OPEN_READWRITE)
            assertEquals(14, db.version)
            assertEquals(setOf("invoice_connection", "keysend_tag", "keysend_connection"), indexes(db))
            db.close()
        }
        val a = SQLiteDatabase.openDatabase(context.getDatabasePath("peel-$fresh.db").path, null, SQLiteDatabase.OPEN_READWRITE)
        val b = SQLiteDatabase.openDatabase(context.getDatabasePath("peel-$migrated.db").path, null, SQLiteDatabase.OPEN_READWRITE)
        for (table in listOf("wallet_connection", "keysend")) assertEquals(table, columns(a, table), columns(b, table))
        val wanted = setOf("preimage", "connection", "app")
        assertTrue(columns(a, "invoice").map { it.first }.containsAll(wanted))
        assertTrue(columns(b, "invoice").map { it.first }.containsAll(wanted))
        a.close(); b.close()
    }
}
