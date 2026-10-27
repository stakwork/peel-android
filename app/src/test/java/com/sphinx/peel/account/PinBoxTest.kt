package com.sphinx.peel.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/// PinBox is where an account's twelve words live; a mistake here either
/// loses them or leaves them readable without the PIN, so it gets its own
/// tests rather than relying on the onboarding flow to exercise it.
class PinBoxTest {
    private val mnemonic = List(11) { "abandon" }.joinToString(" ") + " about"

    @Test
    fun `opens what it sealed, with the right PIN`() {
        val sealed = PinBox.seal(mnemonic, "123456")
        assertEquals(mnemonic, PinBox.open(sealed, "123456"))
    }

    @Test
    fun `refuses the wrong PIN`() {
        val sealed = PinBox.seal(mnemonic, "123456")
        assertThrows(SecretStoreFailure.WrongPin::class.java) { PinBox.open(sealed, "000000") }
    }

    @Test
    fun `two seals of the same words differ`() {
        // A reused salt or nonce would be a real break (key or keystream
        // reuse), so this is the one thing worth asserting about the bytes.
        val first = PinBox.seal(mnemonic, "123456")
        val second = PinBox.seal(mnemonic, "123456")
        assertNotEquals(first.toList(), second.toList())
        assertEquals(mnemonic, PinBox.open(first, "123456"))
        assertEquals(mnemonic, PinBox.open(second, "123456"))
    }

    @Test
    fun `a flipped byte fails rather than returning garbage`() {
        val sealed = PinBox.seal(mnemonic, "123456")
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertThrows(SecretStoreFailure.WrongPin::class.java) { PinBox.open(sealed, "123456") }
    }
}
