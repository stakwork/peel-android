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
}
