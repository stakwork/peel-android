package com.sphinx.peel.engine

import java.io.ByteArrayOutputStream

/// A page of history as the server frames it, and what to do when the core
/// refuses one whole. Ported from PeelCore's `StateCodec.onions(inBatch:)`,
/// `batch`, `index(of:)` and `Engine.readable`/`readablePart`: the page is a
/// MessagePack array of binary chunks, one onion per message (`encode_onion`
/// in the core's `ping/onion.rs`). A malformed chunk makes the core's
/// `handle` fail for the whole page, so a restore would ask for the same
/// page for ever; this finds the chunks the core refuses and leaves just
/// those out.
internal object HistoryPage {
    // ping/onion.rs: a message is a 1401-byte onion, then id, ts and key
    // index (8 bytes each); a payment a 1366-byte onion, then id, ts, key
    // index and amount, then the hash. The id is the server index.
    private const val MESSAGE_ONION = 1401
    private const val MESSAGE_LENGTH = 1425
    private const val PAYMENT_ONION = 1366
    private const val PAYMENT_LENGTH = 1430

    /// The onions of a page, or null when it cannot be read as the core
    /// reads it (a live message is not a page, so is null too).
    fun onions(data: ByteArray): List<ByteArray>? {
        var at = 0
        fun unsigned(width: Int): Int? {
            if (data.size - at < width) return null
            var value = 0L
            repeat(width) { value = (value shl 8) or (data[at++].toLong() and 0xFF) }
            return if (value > Int.MAX_VALUE) null else value.toInt()
        }
        val marker = unsigned(1) ?: return null
        val count = when {
            marker in 0x90..0x9F -> marker - 0x90
            marker == 0xDC -> unsigned(2)
            marker == 0xDD -> unsigned(4)
            else -> null
        } ?: return null
        val onions = ArrayList<ByteArray>()
        repeat(count) {
            val length = when (unsigned(1)) {
                0xC4 -> unsigned(1)
                0xC5 -> unsigned(2)
                0xC6 -> unsigned(4)
                else -> null
            } ?: return null
            if (length != MESSAGE_LENGTH && length != PAYMENT_LENGTH) return null
            if (data.size - at < length) return null
            onions.add(data.copyOfRange(at, at + length))
            at += length
        }
        return if (at == data.size) onions else null
    }

    /// A page made of [onions] (from [onions]), framed as the server frames one.
    fun batch(onions: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        header(onions.size, fix = 0x90, marker8 = null, marker16 = 0xDC, marker32 = 0xDD, out = out)
        for (onion in onions) {
            header(onion.size, fix = null, marker8 = 0xC4, marker16 = 0xC5, marker32 = 0xC6, out = out)
            out.write(onion)
        }
        return out.toByteArray()
    }

    /// The server index of an onion from [onions].
    fun index(onion: ByteArray): Long {
        val start = if (onion.size == PAYMENT_LENGTH) PAYMENT_ONION else MESSAGE_ONION
        var value = 0L
        for (i in start until start + 8) value = (value shl 8) or (onion[i].toLong() and 0xFF)
        return value
    }

    private fun header(length: Int, fix: Int?, marker8: Int?, marker16: Int, marker32: Int, out: ByteArrayOutputStream) {
        when {
            fix != null && length < 16 -> out.write(fix or length)
            marker8 != null && length <= 0xFF -> {
                out.write(marker8)
                out.write(length)
            }
            length <= 0xFFFF -> {
                out.write(marker16)
                out.write(length ushr 8)
                out.write(length and 0xFF)
            }
            else -> {
                out.write(marker32)
                for (shift in intArrayOf(24, 16, 8, 0)) out.write((length ushr shift) and 0xFF)
            }
        }
    }

    /// What of a page the core refused whole it reads: the page in halves,
    /// and each half it refuses in halves again, until each part it refuses
    /// is one item, left out. PeelCore's `Engine.readable`. [refuses] says
    /// whether the core refuses a group of items.
    fun <Item> readable(refused: List<Item>, refuses: (List<Item>) -> Boolean): List<Item> {
        if (refused.size <= 1) return emptyList()
        val middle = refused.size / 2
        return listOf(refused.subList(0, middle), refused.subList(middle, refused.size))
            .flatMap { half -> if (refuses(half)) readable(half, refuses) else half }
    }

    /// The core's answer to the readable part of a page it refused whole
    /// ([refusal] is why), and the server indices left out. History still
    /// moves past the whole page, so one message can neither stop every
    /// catch-up nor take the rest of its page with it. [handle] gives the
    /// core a page (framed as the server frames one). A refusal of no
    /// message in particular (one of a payload that is not a page, or of an
    /// empty page too) is thrown as it came. PeelCore's `readablePart`.
    fun <Result> readablePart(payload: ByteArray, refusal: Exception, handle: (ByteArray) -> Result): Pair<Result, List<Long>> {
        val page = onions(payload) ?: throw refusal
        var result = try { handle(batch(emptyList())) } catch (e: Exception) { throw refusal }
        fun refuses(items: List<ByteArray>): Boolean = try { handle(batch(items)); false } catch (e: Exception) { true }
        var kept = readable(page) { refuses(it) }
        // What it reads apart it may still refuse together: then none of it.
        try {
            result = handle(batch(kept))
        } catch (e: Exception) {
            kept = emptyList()
        }
        val left = page.map { index(it) }.toSet() - kept.map { index(it) }.toSet()
        return result to left.sorted()
    }
}
