package com.sphinx.peel.account

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/// FileSecretStore over fakes of the Keystore seams, at a tiny PBKDF2 cost.
/// What the real Keystore does (hardware, StrongBox, biometric gating) is
/// not covered here; it needs a device.
class FileSecretStoreTest {
    private val iterations = 50
    private val mnemonic = List(11) { "abandon" }.joinToString(" ") + " about"
    private val dir: File = Files.createTempDirectory("peel-secret").toFile()
    private val wall = FakeWall()
    private val lockout = FakeLockoutStore()
    private var legacyKeyDeleted = false
    private val remembered = RememberedPIN(FakeWall(), File(dir, "remembered.t.v1"))
    private val clock = TestClock()

    private fun store() = FileSecretStore(dir, "t", wall, lockout, { legacyKeyDeleted = true }, remembered, clock, iterations)
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

    @Test fun `without the wall key the blob cannot be opened even with the right PIN`() {
        store().save(mnemonic, "123456")
        wall.delete() // key lost / blob copied to another device
        assertThrows(SecretStoreFailure.KeystoreUnavailable::class.java) { store().loadMnemonic("123456") }
    }

    @Test fun `chars variant returns the words`() {
        val s = store()
        s.save(mnemonic, "123456")
        assertEquals(mnemonic, String(s.loadMnemonicChars("123456")))
    }

    @Test fun `legacy blob is migrated, v1 removed, words still open`() {
        legacy()
        val s = store()
        assertTrue(v2.exists()); assertFalse(v1.exists())
        assertEquals(mnemonic, s.loadMnemonic("123456"))
        assertNotNull(lockout.state)
    }

    @Test fun `crash after writing v2 but before deleting v1 completes on next start`() {
        val bytes = legacy()
        // simulate: v2 written, v1 not yet deleted (migration saves the lockout state first)
        lockout.save(LockoutPolicy.State.CLEAN)
        FileSecretStore.writeOwnerOnly(v2, byteArrayOf(FileSecretStore.WRAPPED_VERSION) + wall.wrap(bytes, "peel.secret.t".toByteArray()))
        val s = store()
        assertFalse(v1.exists())
        assertEquals(mnemonic, s.loadMnemonic("123456"))
    }

    @Test fun `a bad v2 next to a good v1 is discarded and redone`() {
        legacy()
        v2.writeBytes(byteArrayOf(FileSecretStore.WRAPPED_VERSION, 1, 2, 3))
        val s = store()
        assertFalse(v1.exists())
        assertEquals(mnemonic, s.loadMnemonic("123456"))
    }

    @Test fun `when the Keystore is down at start the legacy blob still opens, and migrates later`() {
        legacy()
        wall.broken = true
        val s = store()
        assertTrue(v1.exists()); assertFalse(v2.exists())
        assertEquals(mnemonic, s.loadMnemonic("123456"))
        wall.broken = false
        s.migrate()
        assertTrue(v2.exists()); assertFalse(v1.exists())
        assertEquals(mnemonic, s.loadMnemonic("123456"))
    }

    @Test fun `wrong PIN is counted before the check, five in a row lock out, a right PIN after the wait resets`() {
        val s = store()
        s.save(mnemonic, "123456")
        repeat(4) { assertThrows(SecretStoreFailure.WrongPin::class.java) { s.loadMnemonic("000000") } }
        assertEquals(0, s.lockoutRemainingMs())
        assertThrows(SecretStoreFailure.WrongPin::class.java) { s.loadMnemonic("000000") } // 5th
        assertEquals(30_000, s.lockoutRemainingMs())
        // even the right PIN is refused during the wait, and does not count
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

    @Test fun `changePin counts the old PIN, reseals, and updates the remembered PIN`() {
        val s = store()
        s.save(mnemonic, "123456")
        remembered.keep("123456", 100, PinTimeout.NEVER)
        s.changePin("123456", "654321")
        assertEquals("654321", remembered.pin(200))
        assertEquals(mnemonic, s.loadMnemonic("654321"))
        assertThrows(SecretStoreFailure.WrongPin::class.java) { s.loadMnemonic("123456") }
    }

    @Test fun `a legacy biometric item turns the flag on and is deleted unread`() {
        val s = store()
        s.save(mnemonic, "123456")
        File(dir, "bio.t.v1").writeBytes(byteArrayOf(1, 12, 0))
        assertFalse(s.biometricEnrolled)
        s.migrateLegacyBiometric()
        assertTrue(s.biometricEnrolled)
        assertFalse(File(dir, "bio.t.v1").exists())
        assertTrue(legacyKeyDeleted)
        s.setBiometricEnabled(false)
        assertFalse(s.biometricEnrolled)
    }

    @Test fun `deleting the account removes every artifact`() {
        val s = store()
        s.save(mnemonic, "123456")
        s.setBiometricEnabled(true)
        remembered.keep("123456", 1, PinTimeout.NEVER)
        s.deleteMnemonic()
        assertEquals(null, remembered.pin(2))
        assertEquals(emptyList<String>(), dir.list()!!.toList())
        assertFalse(s.hasMnemonic)
        assertEquals(null, lockout.state)
    }

    @Test fun `PinBox key derived for biometrics opens the same blob`() {
        val blob = PinBox.seal(mnemonic, "123456", iterations)
        val key = PinBox.deriveKey(blob, "123456", iterations)
        assertEquals(mnemonic, PinBox.openWithKey(blob, key, iterations))
        assertArrayEquals(key.encoded, PinBox.deriveKey(blob, "123456", iterations).encoded)
    }
}
