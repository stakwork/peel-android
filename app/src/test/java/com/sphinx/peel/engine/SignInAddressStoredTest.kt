package com.sphinx.peel.engine

import androidx.test.core.app.ApplicationProvider
import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// The sign-in address with no hand-seeded setting: what a core result commits ([CommitSettings], the rule `commitLocked`
/// applies in its one write) and the state fallback for accounts registered before that was kept. JVM: no native library.
@RunWith(RobolectricTestRunner::class)
class SignInAddressStoredTest {
    private val key = "02" + "ab".repeat(32)
    private val lsp = "03" + "cd".repeat(32)
    private val own = "${key}_${lsp}_1099511627776"
    private val lspBytes = ByteArray(33) { i -> if (i == 0) 3 else 0xcd.toByte() }
    private fun scid(v: Long) = java.nio.ByteBuffer.allocate(8).putLong(v).array()
    private fun storage() = Storage(ApplicationProvider.getApplicationContext(), account = "addr-${System.nanoTime()}")

    @Test fun contactInfoFromAResultIsCommittedAndSigned() {
        val storage = storage()
        storage.apply(Storage.Commit(settings = CommitSettings.of("5", 7uL, own.uppercase())))
        assertEquals(own.uppercase(), storage.value(Setting.CONTACT_INFO))
        assertEquals("5", storage.value(Setting.CLOCK))
        assertEquals("7", storage.value(Setting.BALANCE_MSAT))
        assertEquals(own, SignInAddress.resolve(storage.value(Setting.CONTACT_INFO), emptyMap(), key))
    }

    @Test fun aResultWithoutContactInfoLeavesTheStoredOneAlone() {
        val storage = storage()
        storage.apply(Storage.Commit(settings = CommitSettings.of("1", null, own)))
        storage.apply(Storage.Commit(settings = CommitSettings.of("2", null, null)))
        assertEquals(own, storage.value(Setting.CONTACT_INFO))
    }

    @Test fun theStateBuildsTheSameAddress() {
        val state = mapOf("my_scid" to scid(1099511627776L), "server_pubkey" to lspBytes)
        assertEquals(own, SignInAddress.resolve(null, state, key))
        assertEquals(own, SignInAddress.resolve("garbage", state, key))
        assertEquals(own, SignInAddress.resolve(null, state, key.uppercase()))
    }

    @Test fun storedInfoWinsAndAnotherKeysIsNeverSent() {
        val state = mapOf("my_scid" to scid(1), "server_pubkey" to lspBytes)
        assertEquals(own, SignInAddress.resolve(own, state, key))
        // A stored address of another key falls to the state, which is built for this key.
        assertEquals("${key}_${lsp}_1", SignInAddress.resolve("${"02" + "ef".repeat(32)}_${lsp}_1", state, key))
    }

    @Test fun anUndecodableStateGivesNothing() {
        assertNull(SignInAddress.resolve(null, emptyMap(), key))
        assertNull(SignInAddress.resolve(null, mapOf("my_scid" to scid(1)), key))
        assertNull(SignInAddress.resolve(null, mapOf("my_scid" to byteArrayOf(1, 2, 3), "server_pubkey" to lspBytes), key))
        assertNull(SignInAddress.resolve(null, mapOf("my_scid" to scid(1), "server_pubkey" to ByteArray(32)), key))
        assertNull(SignInAddress.resolve(null, mapOf("my_scid" to scid(1), "server_pubkey" to ByteArray(33) { 9 }), key))
    }
}
