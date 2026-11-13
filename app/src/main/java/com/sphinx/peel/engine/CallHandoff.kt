package com.sphinx.peel.engine

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/// Moving a call between this account's devices: the newest device on a call keeps it, and any other device on
/// the same account leaves. Ported from PeelCore's `CallHandoff.swift`, byte for byte, so Android, iPhone and Mac
/// interoperate.
///
/// A device that connects says so on [TOPIC] (a LiveKit text stream whose text is the base64 of a MAC) with a MAC over
/// the room and its own identity there, keyed from the account's seed (HKDF-SHA256, empty salt, info
/// "peel call handoff v1", 32 bytes). The LiveKit server stamps each packet with its sender's identity, so a device
/// that checks the MAC against that identity knows the sender is on this account. With [announcement]'s `stay`, the
/// device says it keeps both: the MAC is over a different message, so it never makes anyone leave.
class CallHandoff(seed: String) {
    private val key: ByteArray = hkdf(seed.toByteArray(Charsets.UTF_8), INFO.toByteArray(Charsets.UTF_8), 32)

    /// What a device that has just connected to [room] as [identity] sends: the MAC alone.
    fun announcement(room: String, identity: String, stay: Boolean = false): ByteArray =
        hmac(key, message(room, identity, stay))

    /// The text sent on the stream: [announcement], base64 (what iOS sends and reads, 44 characters).
    fun announcementText(room: String, identity: String, stay: Boolean = false): String =
        Base64.getEncoder().encodeToString(announcement(room, identity, stay))

    /// Whether [data] proves [sender], on [room], is another device on this account (a plain announcement).
    /// Says nothing of who joined first.
    fun isOtherDevice(data: ByteArray, room: String, sender: String, me: String): Boolean =
        sender != me && MessageDigest.isEqual(data, hmac(key, message(room, sender, stay = false)))

    /// Whether this device, on [room] as [me], should leave on [data] from [sender]: it must come from another device
    /// on this account that joined after this one did. A join time unknown on either side counts the sender as
    /// newer; the same time is settled by identity, so two devices joining at once never both leave.
    fun shouldLeave(
        data: ByteArray, room: String, sender: String, senderJoinedAt: Long?, me: String, myJoinedAt: Long?,
    ): Boolean {
        if (!isOtherDevice(data, room, sender, me)) return false
        if (senderJoinedAt == null || myJoinedAt == null) return true
        return if (senderJoinedAt == myJoinedAt) sender > me else senderJoinedAt > myJoinedAt
    }

    companion object {
        /// The text stream topic the announcement goes on, apart from the chat's.
        const val TOPIC = "peel-handoff"

        /// Waits (ms) before each try again of an announcement that could not be sent.
        val RETRY_DELAYS_MS = listOf(1_000L, 2_000L)

        /// The longest announcement text read (iOS: `text.utf8.count <= 64`).
        const val MAX_TEXT_BYTES = 64

        private const val INFO = "peel call handoff v1"

        /// The room and identity, length-prefixed (UTF-8 byte counts) so no two pairs read alike.
        internal fun message(room: String, identity: String, stay: Boolean): ByteArray = (
            (if (stay) "stay:" else "") + "${room.toByteArray(Charsets.UTF_8).size}:$room" +
                "${identity.toByteArray(Charsets.UTF_8).size}:$identity"
            ).toByteArray(Charsets.UTF_8)

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

        /// HKDF (RFC 5869) with SHA-256 and an empty salt, as CryptoKit's `HKDF<SHA256>.deriveKey` without one.
        internal fun hkdf(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
            val prk = hmac(ByteArray(32), ikm)
            var block = ByteArray(0)
            val out = java.io.ByteArrayOutputStream()
            var counter = 1
            while (out.size() < length) {
                block = hmac(prk, block + info + byteArrayOf(counter.toByte()))
                out.write(block)
                counter++
            }
            return out.toByteArray().copyOf(length)
        }
    }
}
