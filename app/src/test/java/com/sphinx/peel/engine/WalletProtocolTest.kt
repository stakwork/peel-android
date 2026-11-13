package com.sphinx.peel.engine

import java.math.BigDecimal
import java.math.BigInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WalletProtocolTest {
    @Test fun integers() {
        assertEquals(1L, WalletProtocol.integer(1))
        assertEquals(1L, WalletProtocol.integer(1L))
        assertEquals(1L, WalletProtocol.integer(1.0))
        assertEquals(1L, WalletProtocol.integer(1.0f))
        assertEquals(-3L, WalletProtocol.integer(-3.0))
        assertEquals(1L, WalletProtocol.integer(BigDecimal("1.00")))
        assertEquals(1L, WalletProtocol.integer(BigInteger.ONE))
        assertEquals(WalletProtocol.MAX_SAFE, WalletProtocol.integer(WalletProtocol.MAX_SAFE))
        assertEquals(-WalletProtocol.MAX_SAFE, WalletProtocol.integer(-WalletProtocol.MAX_SAFE))
    }

    @Test fun notIntegers() {
        assertNull(WalletProtocol.integer(1.5))
        assertNull(WalletProtocol.integer(BigDecimal("1.5")))
        assertNull(WalletProtocol.integer("1"))
        assertNull(WalletProtocol.integer(true))
        assertNull(WalletProtocol.integer(null))
        assertNull(WalletProtocol.integer(JSONObject.NULL))
        assertNull(WalletProtocol.integer(Double.NaN))
        assertNull(WalletProtocol.integer(Double.POSITIVE_INFINITY))
        assertNull(WalletProtocol.integer(WalletProtocol.MAX_SAFE + 1))
        assertNull(WalletProtocol.integer(-WalletProtocol.MAX_SAFE - 1))
        assertNull(WalletProtocol.integer(9007199254740992.0))
        assertNull(WalletProtocol.integer(BigInteger.TWO.pow(70)))
    }
}
