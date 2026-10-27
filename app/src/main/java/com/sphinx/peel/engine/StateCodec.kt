package com.sphinx.peel.engine

import java.io.ByteArrayOutputStream

/// The core hands its state to the client as a MessagePack map of string
/// keys to binary values, and expects the whole map back on every call
/// (`serialize_simple_state_map` in the sphinx crate). This codec handles
/// exactly that shape and rejects anything else. Ported from PeelCore's
/// StateCodec.swift; the onion/history framing helpers in that file are not
/// ported, nothing on Android reads history pages yet.
object StateCodec {
    class Malformed(message: String) : Exception(message)

    /// The marker bytes of one MessagePack length-prefixed family.
    private class Family(
        val fix: IntRange?,
        val marker8: Int?,
        val marker16: Int,
        val marker32: Int,
    )

    private val MAP = Family(fix = 0x80..0x8F, marker8 = null, marker16 = 0xDE, marker32 = 0xDF)
    private val STR = Family(fix = 0xA0..0xBF, marker8 = 0xD9, marker16 = 0xDA, marker32 = 0xDB)
    private val BIN = Family(fix = null, marker8 = 0xC4, marker16 = 0xC5, marker32 = 0xC6)

    fun encode(state: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        write(state.size, MAP, out)
        for ((key, value) in state) {
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            write(keyBytes.size, STR, out)
            out.write(keyBytes)
            write(value.size, BIN, out)
            out.write(value)
        }
        return out.toByteArray()
    }

    fun decode(data: ByteArray): Map<String, ByteArray> {
        val reader = Reader(data)
        val count = reader.length(MAP, "map")
        val state = LinkedHashMap<String, ByteArray>(count)
        repeat(count) {
            val keyLength = reader.length(STR, "key")
            val key = reader.take(keyLength).toString(Charsets.UTF_8)
            val valueLength = reader.length(BIN, "value")
            state[key] = reader.take(valueLength)
        }
        if (!reader.isAtEnd) throw Malformed("trailing bytes after state map")
        return state
    }

    private fun write(length: Int, family: Family, out: ByteArrayOutputStream) {
        when {
            family.fix != null && length < family.fix.count() -> out.write(family.fix.first or length)
            family.marker8 != null && length <= 0xFF -> {
                out.write(family.marker8)
                out.write(length)
            }
            length <= 0xFFFF -> {
                out.write(family.marker16)
                out.write(length ushr 8)
                out.write(length and 0xFF)
            }
            else -> {
                out.write(family.marker32)
                for (shift in intArrayOf(24, 16, 8, 0)) out.write((length ushr shift) and 0xFF)
            }
        }
    }

    private class Reader(private val data: ByteArray) {
        var offset = 0
            private set

        val isAtEnd: Boolean get() = offset == data.size

        fun take(count: Int): ByteArray {
            if (count < 0 || data.size - offset < count) throw Malformed("state map is truncated")
            val slice = data.copyOfRange(offset, offset + count)
            offset += count
            return slice
        }

        private fun integer(width: Int): Int {
            var value = 0
            for (byte in take(width)) value = (value shl 8) or (byte.toInt() and 0xFF)
            return value
        }

        fun length(family: Family, what: String): Int {
            val marker = integer(1)
            if (family.fix != null && marker in family.fix) return marker - family.fix.first
            if (family.marker8 != null && marker == family.marker8) return integer(1)
            if (marker == family.marker16) return integer(2)
            if (marker == family.marker32) return integer(4)
            throw Malformed("unexpected marker 0x${marker.toString(16)} for state $what")
        }
    }
}
