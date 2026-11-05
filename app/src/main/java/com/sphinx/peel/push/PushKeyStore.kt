package com.sphinx.peel.push

import android.content.Context
import java.security.SecureRandom

/// The push key: 32 random bytes as 64 lowercase hex characters, which the
/// core's `setPushToken` seals the token's child indexes under and
/// `decryptChildIndex` opens them with. Ported from `PeelPush/PushKey.swift`
/// (`generate`, `isValid`, `loadOrCreate`). iOS keeps it in a Keychain group
/// shared with its notification extension; here it is a private
/// SharedPreferences value, which the messaging service (same process) reads.
object PushKeyStore {
    private const val FILE = "peel-push"
    private const val KEY = "push_key"

    fun isValid(key: String): Boolean =
        key.length == 64 && key.all { it in '0'..'9' || it in 'a'..'f' }

    fun generate(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun existing(context: Context): String? =
        prefs(context).getString(KEY, null)?.takeIf(::isValid)

    fun loadOrCreate(context: Context): String =
        existing(context) ?: generate().also { prefs(context).edit().putString(KEY, it).apply() }

    /// Account forgotten: the next account gets a fresh key.
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
