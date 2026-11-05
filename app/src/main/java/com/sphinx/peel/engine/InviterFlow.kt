package com.sphinx.peel.engine

import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import kotlin.coroutines.cancellation.CancellationException

/// Nothing could be published: the account has no connection right now.
/// PeelCore's `Failure.notConnected`.
class NotConnectedException(message: String = "Not connected.") : Exception(message)

/// Where the invite a new account signed up with waits (`Setting.INVITE`).
interface PendingInviteStore {
    /// The stored invite, left in place.
    fun peek(): Invite?

    /// The stored invite, forgotten in the same step: whoever takes it is the only one who has it.
    /// Null when there is none, or when it could not be forgotten (so it never goes twice).
    fun take(): Invite?

    fun save(invite: Invite)
}

class SettingInviteStore(private val storage: Storage) : PendingInviteStore {
    override fun peek(): Invite? = Invite.fromJson(storage.value(Setting.INVITE))

    @Synchronized
    override fun take(): Invite? {
        val invite = peek() ?: return null
        return try {
            storage.apply(Storage.Commit(settings = mapOf(Setting.INVITE to "")))
            invite
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    override fun save(invite: Invite) {
        storage.apply(Storage.Commit(settings = mapOf(Setting.INVITE to invite.toJson())))
    }
}

/// Adding whoever sent the invite a new account signed up with, once it is
/// online (PeelCore's `addInviterIfPending`). The invite is forgotten as the
/// key exchange starts, so it goes at most once; one that never left
/// (not connected) keeps it for the next connection, and any other failure is reported.
object InviterFlow {
    sealed class Outcome {
        /// No invite was waiting.
        object NothingPending : Outcome()

        /// The invite names nobody to add; it is forgotten.
        object NoInviter : Outcome()

        /// The key exchange started.
        data class Started(val inviter: String) : Outcome()

        /// Nothing left (not connected): the invite is back for the next connect.
        object KeptForLater : Outcome()

        /// The key exchange failed for another reason; the invite stays forgotten (it may have gone).
        data class Failed(val message: String) : Outcome()

        /// The failure was one where nothing was committed or sent (the core refused): the
        /// invite is back for the next connect, which tries again.
        data class WillRetry(val message: String) : Outcome()
    }

    suspend fun addIfPending(
        store: PendingInviteStore,
        retryable: (Exception) -> Boolean = { false },
        add: suspend (contactInfo: String, alias: String?, inviteCode: String) -> Unit,
    ): Outcome {
        val invite = store.take() ?: return Outcome.NothingPending
        val inviter = invite.inviterContactInfo ?: return Outcome.NoInviter
        return try {
            add(inviter, invite.inviterAlias, invite.code)
            Outcome.Started(inviter)
        } catch (e: NotConnectedException) {
            store.save(invite)
            Outcome.KeptForLater
        } catch (e: CancellationException) {
            // Stopped before it is known to have gone: the next connect tries again.
            store.save(invite)
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "The key exchange failed."
            if (retryable(e)) {
                store.save(invite)
                Outcome.WillRetry(message)
            } else {
                Outcome.Failed(message)
            }
        }
    }
}

/// What a signup does with the account it made when the connection did not
/// confirm: PeelCore's `Account.signup` rule.
object SignupRules {
    /// The profile is removed again only if no register can have reached the
    /// server: the server redeems the invite as it registers the account,
    /// before it answers, so an answer lost or late may hide an account that
    /// holds the invite's sats. Such an account is kept unconfirmed and
    /// registers again on each connect.
    fun keepAccount(registered: Boolean, inviteMayBeRedeemed: Boolean): Boolean = registered || inviteMayBeRedeemed

    const val NOT_CONFIRMED = "Not confirmed yet. If it stays this way, ask for a new invite."
}

/// The order a result's publishes go in (PeelCore's `Engine.publishPlan`): the
/// settle and register first; then, once a new child key's register has had
/// time to land, the messages addressed to that key; and with an async pay,
/// the messages after it. Pure so its ordering has tests.
class PublishPlan(val now: List<Pair<String, ByteArray>>, val later: List<Pair<String, ByteArray>>, val afterPay: List<Pair<String, ByteArray>>) {
    companion object {
        fun of(
            settle: Pair<String, ByteArray>?,
            register: Pair<String, ByteArray>?,
            asyncPay: Pair<String, ByteArray>?,
            topics: List<Pair<String, ByteArray>>,
            newChildKey: Boolean,
        ): PublishPlan {
            val now = mutableListOf<Pair<String, ByteArray>>()
            settle?.let { now += it }
            register?.let { now += it }
            val rest: List<Pair<String, ByteArray>>
            val afterPay: List<Pair<String, ByteArray>>
            if (asyncPay != null) {
                rest = listOf(asyncPay)
                afterPay = topics
            } else {
                rest = topics
                afterPay = emptyList()
            }
            return if (register != null && newChildKey) PublishPlan(now, rest, afterPay)
            else PublishPlan(now + rest, emptyList(), afterPay)
        }
    }
}
