package com.sphinx.peel.engine

/// Payments sent to this account the asynchronous way (docs/phases/50-answer-pings.md; sphinx's
/// mixer `ping.rs` and `localpay.rs`; PeelCore's `receive`, `notePing`, `pingDone` and
/// `cameOnlineSteps`). A payment to a contact (the sats a key exchange pays back with its key, sats
/// in a chat, a boost, an employer's payout) is held by the sender's server, which pings this
/// account's server, which passes the ping on (topic kind [InboundTopic.PING]). The core's answer to
/// the ping (`handle`) is an "online" message to the sender's server; only when that arrives does
/// the payment come, on the stream, with its message inside. The core's answer to that settles it,
/// and the ping is acknowledged ([PingBook]) so the server stops passing it on. Pings that came
/// while this account was away are passed on again when it asks (`fetchPings`, on coming online).

/// The topics the server sends on: `<root>/<key>/<index>/res/<device>/<kind>/…`.
object InboundTopic {
    /// An async payment's ping.
    const val PING = "ping"

    /// The topic's kind (its sixth segment), or the whole topic when it has fewer.
    fun kind(topic: String): String = topic.split('/').getOrNull(5) ?: topic
}

/// Which results of `handle` are published. `handle` sends nothing itself, so what it asks to send
/// in answer goes only when published: a key exchange's confirmation and the sats it pays back
/// ([keyExchange]), a ping's "online" message ([answersPing], without which the payment it is for
/// never comes) and a payment's settle ([settles], without which the server holds it and gives it
/// back to its sender after a week). PeelCore publishes every result; this port these. A key
/// exchange is answered only live, never while a restore replays history
/// (docs/phases/21-invites.md); a ping or a settle only ever comes live.
object InboundPublish {
    fun publishes(keyExchange: Boolean, restoring: Boolean, answersPing: Boolean, settles: Boolean): Boolean =
        answersPing || settles || (keyExchange && !restoring)
}

/// The pings answered and not yet acknowledged (PeelCore's `pingsByHash`, `pingsByTag`). In memory
/// only, as on iOS: one a restart forgets is passed on again at the next `fetchPings`, answered, and
/// acknowledged once its payment comes.
class PingBook {
    private val byHash = HashMap<String, ULong>()
    private val byTag = HashMap<String, ULong>()

    /// A handled ping (`RunReturn.ping`): `<payment hash>:<time>:<tag>`, the hash `_` when the ping
    /// named none.
    @Synchronized
    fun note(ping: String) {
        val parts = ping.split(':')
        val time = parts.getOrNull(1)?.toULongOrNull() ?: return
        byHash[parts[0]] = time
        parts.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { byTag[it] = time }
    }

    /// The pings to acknowledge after a handled result: the one each payment that came (by
    /// [paymentHashes]) was for, and the one a refused "online" answer names ([error]
    /// `async pay not found: <tag>`: its payment no longer exists). Each is forgotten here, so it is
    /// acknowledged once.
    @Synchronized
    fun done(paymentHashes: List<String>, error: String?): List<ULong> {
        val landed = paymentHashes.mapNotNull { byHash.remove(it) }
        val gone = error?.substringAfterLast(NOT_FOUND, "")?.takeIf { it.length == 64 }?.let { byTag.remove(it) }
        return landed + listOfNotNull(gone)
    }

    private companion object {
        const val NOT_FOUND = "async pay not found: "
    }
}
