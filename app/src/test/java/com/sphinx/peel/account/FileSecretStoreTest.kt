package com.sphinx.peel.account

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/// FileSecretStore over fakes of the Keystore seams, at a tiny PBKDF2 cost.
/// What the real Keystore does (hardware, StrongBox) needs a device.
class FileSecretStoreTest {
    private val iterations = 50
    private val mnemonic = List(11) { "abandon" }.joinToString(" ") + " about"
    private val dir: File = Files.createTempDirectory("peel-secret").toFile()
    private val wall = FakeWall()
    private val lockout = FakeLockoutStore()
    private val clock = TestClock()

    private fun store() = FileSecretStore(dir, "t", wall, lockout, clock, iterations)
    private val v1 get() = File(dir, "secret.t.v1")
    private val v2 get() = File(dir, "secret.t.v2")

    private fun legacy(pin: String = "123456") = PinBox.seal(mnemonic, pin, iterations).also { v1.writeBytes(it) }

    @Test fun `save then load, blob on disk is wrapped and not the bare PinBox`() {
        val s = store()
        s.save(mnemonic, "123456")
        assertTrue(v2.exists()); assertFalse(v1.exists())
        assertEquals(FileSecretStore.WRAPPED_VERSION, v2.readBytes()[0])
        assertEquals(mnemonic, s.loadMnemonic("123456"))
        assertThrows(SecretStoreFailure.AccountExists::class.java) { s.save(mnemonic, "123456") }
    }

    @Test fun `chars variant returns the words`() {
        val s = store()
        s.save(mnemonic, "123456")
        assertEquals(mnemonic, String(s.loadMnemonicChars("123456")))
    }

    @Test fun `without the wall key the blob cannot be opened even with the right PIN`() {
        store().save(mnemonic, "123456")
        wall.delete() // key lost / blob copied to another device
        assertThrows(SecretStoreFailure.KeystoreUnavailable::class.java) { store().loadMnemonic("123456") }
    }

    @Test fun `legacy account is not migrated until a correct PIN unlock, with no lockout penalty`() {
        legacy()
        val s = store()
        assertTrue(s.hasMnemonic)
        assertTrue(v1.exists()); assertFalse(v2.exists())
        assertEquals(0, s.lockoutRemainingMs())
        assertThrows(SecretStoreFailure.WrongPin::class.java) { s.loadMnemonic("000000") }
        assertTrue(v1.exists()); assertFalse(v2.exists()) // a wrong PIN never migrates
        assertEquals(mnemonic, s.loadMnemonic("123456"))
        assertTrue(v2.exists()); assertFalse(v1.exists())
        assertEquals(mnemonic, store().loadMnemonic("123456")) // and v2 opens afterwards
        assertNotNull(lockout.state)
    }

    @Test fun `migration re-seals with a fresh salt`() {
        val old = legacy()
        store().loadMnemonic("123456")
        val inner = wall.unwrap(v2.readBytes().copyOfRange(1, v2.length().toInt()), "peel.secret.t".toByteArray())
        assertFalse(inner.contentEquals(old))
    }

    @Test fun `failed migration (Keystore down) keeps the legacy file and the words open`() {
        legacy()
        wall.broken = true
        val s = store()
        assertEquals(mnemonic, s.loadMnemonic("123456"))
        assertTrue(v1.exists()); assertFalse(v2.exists())
        wall.broken = false
        assertEquals(mnemonic, s.loadMnemonic("123456")) // retried on the next unlock
        assertTrue(v2.exists()); assertFalse(v1.exists())
    }

    @Test fun `a verification mismatch keeps v1 and discards v2`() {
        legacy()
        wall.corrupt = true // unwrap returns altered bytes
        val s = store()
        assertEquals(mnemonic, s.loadMnemonic("123456"))
        assertTrue(v1.exists()); assertFalse(v2.exists())
        wall.corrupt = false
        assertEquals(mnemonic, s.loadMnemonic("123456"))
        assertFalse(v1.exists())
    }

    @Test fun `crash after v2 written but before v1 deleted finishes on next unlock`() {
        val bytes = legacy()
        FileSecretStore.writeOwnerOnly(v2, byteArrayOf(FileSecretStore.WRAPPED_VERSION) + wall.wrap(bytes, "peel.secret.t".toByteArray()))
        assertEquals(mnemonic, store().loadMnemonic("123456"))
        assertFalse(v1.exists())
        assertTrue(v2.exists())
    }

    @Test fun `a bad v2 next to a good v1 falls back to v1 and is redone`() {
        legacy()
        v2.writeBytes(byteArrayOf(FileSecretStore.WRAPPED_VERSION, 1, 2, 3))
        assertEquals(mnemonic, store().loadMnemonic("123456"))
        assertFalse(v1.exists())
        assertEquals(mnemonic, store().loadMnemonic("123456"))
    }

    @Test fun `wrong PIN is counted before the check, five in a row lock out, a right PIN after the wait resets`() {
        val s = store()
        s.save(mnemonic, "123456")
        repeat(4) { assertThrows(SecretStoreFailure.WrongPin::class.java) { s.loadMnemonic("000000") } }
        assertEquals(0, s.lockoutRemainingMs())
        assertThrows(SecretStoreFailure.WrongPin::class.java) { s.loadMnemonic("000000") } // 5th
        assertEquals(30_000, s.lockoutRemainingMs())
        val e = assertThrows(SecretStoreFailure.LockedOut::class.java) { s.loadMnemonic("123456") }
        assertEquals(30_000, e.remainingMs)
        assertEquals(5, lockout.state!!.failures)
        clock.advance(30_000)
        assertEquals(mnemonic, s.loadMnemonic("123456"))
        assertEquals(0, lockout.state!!.failures)
    }

    @Test fun `the counter survives a restart (a new store object)`() {
        store().also { it.save(mnemonic, "123456") }
        repeat(5) { runCatching { store().loadMnemonic("000000") } }
        assertEquals(30_000, store().lockoutRemainingMs())
    }

    @Test fun `an attempt is recorded even if the check never completes`() {
        val s = store()
        s.save(mnemonic, "123456")
        wall.broken = true
        assertThrows(SecretStoreFailure.KeystoreUnavailable::class.java) { s.loadMnemonic("123456") }
        assertEquals(1, lockout.state!!.failures)
    }

    @Test fun `tampered or deleted lockout state costs at least the floor`() {
        val s = store()
        s.save(mnemonic, "123456")
        lockout.tampered = true
        assertEquals(30_000, s.lockoutRemainingMs())
        lockout.clear() // file deleted while an account exists
        assertEquals(30_000, s.lockoutRemainingMs())
    }

    @Test fun `no account means no lockout`() {
        assertEquals(0, store().lockoutRemainingMs())
        assertThrows(SecretStoreFailure.NoAccount::class.java) { store().loadMnemonic("123456") }
    }

    @Test fun `changePin counts the old PIN and reseals, removing any v1 leftover`() {
        legacy()
        val s = store()
        s.changePin("123456", "654321")
        assertFalse(v1.exists())
        assertEquals(mnemonic, s.loadMnemonic("654321"))
        assertThrows(SecretStoreFailure.WrongPin::class.java) { s.loadMnemonic("123456") }
    }

    @Test fun `deleting the account removes every artifact`() {
        val s = store()
        s.save(mnemonic, "123456")
        s.deleteMnemonic()
        assertEquals(emptyList<String>(), dir.list()!!.toList())
        assertFalse(s.hasMnemonic)
        assertEquals(null, lockout.state)
    }
}
