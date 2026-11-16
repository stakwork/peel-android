package com.sphinx.peel.engine

import java.math.BigDecimal
import java.math.BigInteger

// Reading numbers the way the wallet protocol does (iOS `WalletProtocol.integer`). Pure, JVM-tested.

object WalletProtocol {
    /// The largest integer a JavaScript number holds exactly (2^53 - 1).
    const val MAX_SAFE = 9007199254740991L

    /// [v] as an integer: an integral number, or a floating one with no fraction (`1.0`), within ±[MAX_SAFE].
    /// A string, a boolean, null, a fraction, NaN or anything out of range is null.
    fun integer(v: Any?): Long? {
        val n: Long = when (v) {
            is Int -> v.toLong()
            is Long -> v
            is Short -> v.toLong()
            is Byte -> v.toLong()
            is Double -> whole(v) ?: return null
            is Float -> whole(v.toDouble()) ?: return null
            is BigInteger -> if (v.bitLength() < 64) v.toLong() else return null
            is BigDecimal -> try {
                val exact = v.toBigIntegerExact()
                if (exact.bitLength() < 64) exact.toLong() else return null
            } catch (e: ArithmeticException) {
                return null
            }
            else -> return null
        }
        return n.takeIf { it in -MAX_SAFE..MAX_SAFE }
    }

    private fun whole(d: Double): Long? {
        if (d.isNaN() || d.isInfinite() || Math.rint(d) != d || Math.abs(d) > MAX_SAFE) return null
        return d.toLong()
    }

    private val HASH = Regex("^[0-9a-fA-F]{64}$")

    /// Whether [text] is 64 hex characters: a payment hash or a preimage.
    fun isHash(text: String): Boolean = HASH.matches(text)

    /// Whether [preimage] hashes (SHA-256) to [paymentHash]: both 64 hex characters, either in any case (iOS `verifyPreimage`). A page is
    /// never handed a preimage that proves nothing.
    fun verifyPreimage(preimage: String, paymentHash: String): Boolean {
        val p = preimage.lowercase()
        val h = paymentHash.lowercase()
        if (!isHash(p) || !isHash(h)) return false
        val bytes = ByteArray(32) { p.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) } == h
    }
}
