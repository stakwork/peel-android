package com.sphinx.peel.account

import java.io.File

/// The PIN kept on the device while the "Ask for the PIN" timeout has not run
/// out, so a cold launch (or a return from the background) inside that window
/// opens the account without asking. Android counterpart of PeelCore's
/// `RememberedPIN.swift`.
///
/// Stored as `pin \n activeAt \n timeoutHours` sealed by a non-authenticated
/// Android Keystore AES-256-GCM key (`KeyWall`, alias `peel.remembered.<a>`;
/// `setUserAuthenticationRequired(false)` so a cold launch can read it with
/// nobody present, and no biometric binding). The file lives in
/// `noBackupFilesDir`, so it is never backed up (and the manifest has
/// `allowBackup=false` too); the key never leaves the Keystore.
///
/// Why not EncryptedSharedPreferences / androidx.security:security-crypto: it is
/// deprecated, adds a dependency, and this is one small value; `KeyWall` is
/// already the app's tested Keystore AES-GCM seam, so we reuse it (JVM-testable
/// with `FakeWall`).
///
/// Times are epoch SECONDS passed in by the caller (a clock is injected by
/// passing it), so validity is a pure function.
class RememberedPIN(private val wall: KeyWall, private val file: File) {
    data class Entry(val pin: String, val activeAt: Long, val timeoutHours: Int)

    private val aad = "peel.remembered".toByteArray()

    /// Remembers `pin` (a timeout of 0 means "every time": forget instead).
    @Synchronized
    fun keep(pin: String, activeAt: Long, timeoutHours: Int) {
        if (timeoutHours == 0) return forget()
        write(Entry(pin, activeAt, timeoutHours))
    }

    /// The app was in use at `activeAt` (called on leaving the foreground).
    @Synchronized
    fun touch(activeAt: Long) {
        val entry = read() ?: return
        write(entry.copy(activeAt = activeAt))
    }

    /// The setting changed: 0 forgets the PIN, otherwise the new window applies.
    @Synchronized
    fun timeoutChanged(hours: Int) {
        if (hours == 0) return forget()
        val entry = read() ?: return
        if (entry.timeoutHours != hours) write(entry.copy(timeoutHours = hours))
    }

    /// The PIN was changed: what is kept must follow, or it would be wrong.
    @Synchronized
    fun pinChanged(newPin: String) {
        val entry = read() ?: return
        write(entry.copy(pin = newPin))
    }

    /// The PIN, only if it may still be used at `now`; an expired one is removed.
    @Synchronized
    fun pin(now: Long): String? {
        val entry = read() ?: return null
        if (!isValid(entry, now)) {
            forget()
            return null
        }
        return entry.pin
    }

    @Synchronized
    fun forget() {
        file.delete()
    }

    private fun write(entry: Entry) {
        val plain = "${entry.pin}\n${entry.activeAt}\n${entry.timeoutHours}".toByteArray(Charsets.UTF_8)
        try {
            FileSecretStore.writeOwnerOnly(file, wall.wrap(plain, aad))
        } catch (e: Exception) {
            // Could not be kept: the safe outcome is that the PIN is asked.
            file.delete()
        } finally {
            plain.fill(0)
        }
    }

    private fun read(): Entry? {
        if (!file.exists()) return null
        return try {
            val plain = wall.unwrap(file.readBytes(), aad)
            val parts = String(plain, Charsets.UTF_8).also { plain.fill(0) }.split("\n")
            if (parts.size != 3) throw IllegalStateException("bad format")
            Entry(parts[0], parts[1].toLong(), parts[2].toInt())
        } catch (e: Exception) {
            // Unreadable (key lost, corrupt): drop it, the PIN is asked.
            file.delete()
            null
        }
    }

    companion object {
        /// never => valid; <= 0 otherwise => invalid; else within the window.
        /// A clock set back counts as no time passed.
        fun isValid(entry: Entry, now: Long): Boolean = when {
            entry.timeoutHours == PinTimeout.NEVER -> true
            entry.timeoutHours <= 0 -> false
            else -> maxOf(0L, now - entry.activeAt) <= entry.timeoutHours * 3600L
        }

        fun create(context: android.content.Context, account: String = "default") = RememberedPIN(
            AndroidKeyWall("peel.remembered.$account", strongBox = false),
            File(context.noBackupFilesDir, "remembered.$account.v1"),
        )

        fun nowSeconds(): Long = System.currentTimeMillis() / 1000
    }
}
