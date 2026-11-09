package com.sphinx.peel.engine

import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import kotlinx.coroutines.delay
import uniffi.sphinxrs.SphinxException
import uniffi.sphinxrs.addContact as coreAddContact
import uniffi.sphinxrs.cancelInvite as coreCancelInvite
import uniffi.sphinxrs.codeFromInvite as coreCodeFromInvite
import uniffi.sphinxrs.makeInvite as coreMakeInvite

/// Contacts and invites on the engine. Ported from PeelCore's `Engine+Contacts.swift`,
/// `Engine+Messages.swift` (`addContact`) and `Engine+Account.swift` (`addInviterIfPending`).
/// Not ported: tribe links and the "new contact"
/// badge (`contact.isNew`); see docs/phases/21-invites.md.

/// What a key exchange pays: the official apps attach 5 sats to its first message.
const val KEY_EXCHANGE_MSAT = 5_000L

/// How long an invite is waited for to be confirmed by the server (PeelCore: 30 s).
private const val INVITE_CONFIRM_WAIT_MS = 30_000L

/// How long a cancel waits for the sats to come back (PeelCore: 20 s).
private const val INVITE_CANCEL_WAIT_MS = 20_000L

private const val POLL_MS = 250L

// MARK: - Adding a contact

/// Starts a key exchange with a contact given as `pubkey_lsp_scid`, paying [KEY_EXCHANGE_MSAT]
/// with no checks: [addContactByCode] checks, and only a new account's inviter
/// ([addInviterIfPending]) is added unchecked. [inviteCode] makes the other app see
/// its invite used. Throws [NotConnectedException] when nothing could be published.
suspend fun Engine.addContact(contactInfo: String, alias: String? = null, inviteCode: String? = null) {
    val parts = ContactCode.parts(contactInfo) ?: throw InviteRefused("That is not contact info.")
    if (!isConnected) throw NotConnectedException()
    val myAlias = storage.value(Setting.ALIAS) ?: ""
    val myImage = storage.value(Setting.PHOTO_URL)?.takeIf { it.startsWith("https://") } ?: ""
    val name = alias?.trim()?.takeIf { it.isNotEmpty() }
    val seed = seed
    run(ordered = true) { time, state ->
        coreAddContact(
            seed, time, state, parts[0], "${parts[1]}_${parts[2]}", myAlias, myImage,
            KEY_EXCHANGE_MSAT.toULong(), inviteCode, name,
        )
    }
    // The core holds them now: a conversation, shown at once.
    storage.upsertContact(parts[0], name, null, isTribe = false, ready = true)
    markPeopleReady()
}

/// Adds whoever a scanned or pasted code names (`ContactCode`): contact info, a
/// `share_contact` link, or an invite's inviter. An invite is for a new account, so its
/// code is never used here: the inviter is added as anyone would be. Someone already a
/// contact is not added again (another key exchange would cost sats for nothing). The key
/// exchange pays [KEY_EXCHANGE_MSAT] (and its ping, which the balance must cover too),
/// through the usual balance and threshold checks: over the threshold it throws
/// `PaymentRefused` with `confirmMsat`, and the second call, with that amount as
/// [confirmedMsat], adds them. Returns their pubkey and whether a key exchange was started.
suspend fun Engine.addContactByCode(
    text: String,
    alias: String? = null,
    confirmedMsat: Long? = null,
    routeHint: String? = null,
): Pair<String, Boolean> {
    val code = if (routeHint.isNullOrBlank()) ContactCode.read(text) else ContactCode.fromParts(text, routeHint)
    val pubkey = code.pubkey
    val own = storage.value(Setting.CONTACT_INFO)?.let { ContactCode.contactInfo(it) }
    if (own != null && own.startsWith(pubkey)) throw InviteRefused("That is your own contact code.")
    val known = storage.contact(pubkey)
    if (known != null && known.ready && !known.isTribe) return pubkey to false
    // Nobody confirms a payment that cannot go.
    if (!isConnected) throw PaymentRefused("Not connected.")
    checkPayment(KEY_EXCHANGE_MSAT, confirmedMsat, reserveMsat = PING_MSAT)
    addContact(code.contactInfo, alias ?: code.inviterName)
    return pubkey to true
}

// MARK: - The inviter of a new account

/// Adds whoever sent the invite this account signed up with as its first contact, as the
/// official apps do, once the account is online: paying [KEY_EXCHANGE_MSAT] from the invite's
/// sats with no checks, with the invite's code so their app sees it used. The invite is
/// forgotten as the key exchange starts, so it goes at most once; one that never left (not
/// connected, or refused by the core before anything was committed) keeps it for the next
/// connection.
suspend fun Engine.addInviterIfPending(): InviterFlow.Outcome {
    val outcome = InviterFlow.addIfPending(
        pendingInvites,
        retryable = { it is SphinxException },
    ) { info, alias, code -> addContact(info, alias, code) }
    publishInviterOutcome(outcome)
    return outcome
}

// MARK: - Invites

/// Makes an invite holding [amountMsat] for someone without an account, as the official apps
/// do (`make_invite`): a keysend of the amount to this account's own server carrying a new
/// code, which the server keeps with the sats (less its fee, if it charges one) until someone
/// signs up with the invite or this account cancels it. The invite names this account, so
/// whoever joins adds it as a contact.
///
/// Every invite is confirmed with its amount, whatever the threshold: the sats leave for a code
/// anyone holding it can spend. The balance and the connection are checked first, so nobody
/// confirms an invite that cannot be made; then the first call throws `PaymentRefused` with
/// `confirmMsat`, and the second, with that amount as [confirmedMsat], pays. Written before its
/// payment is published; returned once the server confirms it, or still sending when it does
/// not within 30 s (it shows ready when the server says; the server is asked again on every
/// connect). The nickname and message are kept here only.
suspend fun Engine.makeInvite(
    amountMsat: Long,
    nickname: String? = null,
    message: String? = null,
    confirmedMsat: Long? = null,
): InviteRecord {
    if (amountMsat <= 0) throw PaymentRefused("The amount must be more than zero.")
    val nick = InviteRules.cleanNickname(nickname)
    val note = InviteRules.cleanMessage(message)
    // The balance as for any send (a balance not known yet is refused), the threshold left to below.
    checkPayment(amountMsat, amountMsat)
    val server = storage.value(Setting.SERVER)?.let { ServerAddress.parse(it) }
    if (!isRegistered || !isConnected || server == null) throw PaymentRefused("Not connected.")
    if (confirmedMsat != amountMsat) {
        throw PaymentRefused("Confirm ${Sats.format(amountMsat)} to put in the invite.", confirmMsat = amountMsat)
    }
    checkPayment(amountMsat, confirmedMsat)

    val seed = seed
    val alias = storage.value(Setting.ALIAS) ?: ""
    var record: InviteRecord? = null
    try {
        run(
            beforePublish = { result ->
                val link = result.newInvite ?: throw PaymentRefused("The core returned no invite.")
                val tag = result.msgs.firstOrNull()?.tag ?: throw PaymentRefused("The core returned no tag for the invite.")
                val made = InviteRecord(
                    code = coreCodeFromInvite(link).lowercase(), link = link, amountMsat = amountMsat, nickname = nick,
                    message = note, tag = tag, status = InviteRecord.Status.SENDING, createdAt = System.currentTimeMillis(),
                )
                // Written with the state, before publishing: a payment that may have gone is never unrecorded.
                storage.insertInvite(made)
                record = made
            },
            ordered = true,
        ) { time, state ->
            // The official apps also pass the tribes host and the config's default tribe; with
            // none (production's is empty) the core leaves the tribe out. The inviter is this account.
            coreMakeInvite(seed, time, state, "${server.host}:${server.port}", amountMsat.toULong(), alias, null, null, null, null)
        }
    } catch (e: NotConnectedException) {
        record?.tag?.let { storage.markSendStatus(it, SendStatus.FAILED) }
        throw PaymentRefused("Not connected. Nothing was sent.")
    } catch (e: SphinxException) {
        throw PaymentRefused("Could not make the invite: ${e.message ?: "the core refused"}")
    }
    val made = record ?: throw PaymentRefused("The core returned no invite.")
    return confirmedInvite(made.code)
}

/// The invite once the server confirms it, or as it stands after the wait: still sending, its
/// payment perhaps gone, so it is returned rather than thrown and nobody is offered to pay for it
/// again. A failed payment throws: no sats moved and the code is worth nothing.
internal suspend fun Engine.confirmedInvite(code: String, waitMs: Long = INVITE_CONFIRM_WAIT_MS): InviteRecord {
    var waited = 0L
    while (storage.invite(code)?.status == InviteRecord.Status.SENDING && waited < waitMs) {
        delay(POLL_MS)
        waited += POLL_MS
    }
    val invite = storage.invite(code) ?: throw InviteRefused("There is no such invite.")
    if (invite.status == InviteRecord.Status.FAILED) throw InviteRefused("The server refused the invite's payment. No sats moved.")
    return invite
}

/// Asks the server for an invite's sats back (`cancel_invite`). The server answers only with the
/// new balance, and with nothing at all when the invite was used meanwhile, so the invite counts
/// as cancelled once the balance rises by at most its amount (the server may keep a fee). One at a
/// time, so a rise is this cancel's. With no rise within the wait it stays cancelling: it may have
/// been used, or the sats may come later; it can be asked again, or forgotten ([forgetInvite]).
suspend fun Engine.cancelInvite(code: String, waitMs: Long = INVITE_CANCEL_WAIT_MS): InviteRecord {
    val code = code.lowercase()
    val invite = storage.invite(code) ?: throw InviteRefused("There is no such invite.")
    if (!InviteRules.canCancel(invite.status)) throw InviteRefused("That invite is no longer waiting to be used.")
    if (!isRegistered || !isConnected) throw PaymentRefused("Not connected.")
    if (!cancelsInFlight.add(code)) throw InviteRefused("That invite is already being cancelled.")
    try {
        val before = balanceMsat() ?: throw PaymentRefused("Your balance isn't known yet. Try again in a moment.")
        val wasReady = invite.status == InviteRecord.Status.READY
        storage.setInviteStatus(code, InviteRecord.Status.CANCELLING, listOf(InviteRecord.Status.READY, InviteRecord.Status.CANCELLING))
        val seed = seed
        try {
            run { time, state -> coreCancelInvite(seed, time, state, code) }
        } catch (e: Exception) {
            // Nothing was published (or the core refused): back to where it was.
            if (wasReady) storage.setInviteStatus(code, InviteRecord.Status.READY, listOf(InviteRecord.Status.CANCELLING))
            throw if (e is SphinxException) PaymentRefused("Could not cancel: ${e.message ?: "the core refused"}") else e
        }
        var waited = 0L
        while (waited < waitMs) {
            val now = balanceMsat()
            if (now != null && InviteRules.refunded(before, now, invite.amountMsat)) {
                storage.setInviteStatus(code, InviteRecord.Status.CANCELLED, listOf(InviteRecord.Status.CANCELLING))
                return storage.invite(code) ?: invite
            }
            delay(POLL_MS)
            waited += POLL_MS
        }
        throw InviteRefused(
            "The server has sent no sats back yet; the invite may have been used. Ask again, or remove it from the list.",
        )
    } finally {
        cancelsInFlight.remove(code)
    }
}

/// Drops an invite whose cancel the server never answered from the list: it was used, or its sats
/// came back unnoticed. Only a cancelling one.
fun Engine.forgetInvite(code: String) {
    if (!storage.setInviteStatus(code.lowercase(), InviteRecord.Status.CANCELLED, listOf(InviteRecord.Status.CANCELLING))) {
        throw InviteRefused("That invite is no longer waiting to be used.")
    }
}
