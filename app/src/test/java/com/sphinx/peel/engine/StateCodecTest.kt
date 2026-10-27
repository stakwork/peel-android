package com.sphinx.peel.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StateCodecTest {
    @Test
    fun `round trips an empty state`() {
        assertEquals(emptyMap<String, ByteArray>(), StateCodec.decode(StateCodec.encode(emptyMap())))
    }

    @Test
    fun `round trips keys and values of every MessagePack length class`() {
        // Fixstr tops out at 31 bytes, fixmap at 15 entries; past either the
        // encoder has to switch marker family, which is the one place this
        // format can go subtly wrong.
        val state = mapOf(
            "k" to byteArrayOf(),
            "a".repeat(40) to ByteArray(300) { it.toByte() },
            "my_scid" to byteArrayOf(1, 2, 3),
        ) + (0 until 20).associate { "key$it" to byteArrayOf(it.toByte()) }

        val decoded = StateCodec.decode(StateCodec.encode(state))

        assertEquals(state.keys, decoded.keys)
        for (key in state.keys) assertArrayEquals(state.getValue(key), decoded.getValue(key))
    }

    @Test
    fun `refuses truncated bytes`() {
        val encoded = StateCodec.encode(mapOf("k" to byteArrayOf(1, 2, 3)))
        assertThrows(StateCodec.Malformed::class.java) {
            StateCodec.decode(encoded.copyOfRange(0, encoded.size - 1))
        }
    }

    @Test
    fun `refuses trailing bytes`() {
        val encoded = StateCodec.encode(mapOf("k" to byteArrayOf(1)))
        assertThrows(StateCodec.Malformed::class.java) { StateCodec.decode(encoded + byteArrayOf(0)) }
    }
}
