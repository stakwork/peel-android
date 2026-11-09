package com.sphinx.peel.engine

import com.sphinx.peel.storage.MessageRow
import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import org.json.JSONObject
import uniffi.sphinxrs.createTribe as coreCreateTribe
import uniffi.sphinxrs.deleteTribe as coreDeleteTribe
import uniffi.sphinxrs.getDefaultTribeServer as coreGetDefaultTribeServer
import uniffi.sphinxrs.joinTribe as coreJoinTribe
import uniffi.sphinxrs.listTribeMembers as coreListTribeMembers
import uniffi.sphinxrs.send as coreSend
import uniffi.sphinxrs.updateTribe as coreUpdateTribe

/// Tribes on the engine: join, create, edit, leave, delete, members and their requests, kick,
/// pin. Joining reads the tribe's registry entry; the rest are the core's own tribe calls
/// (`create_tribe`, `join_tribe`, `update_tribe`, `delete_tribe`, `list_tribe_members`) or a
/// tribe message sent with `send` (leave, approve, reject, kick), as PeelCore's
/// `Engine+Tribes.swift` does. See docs/phases/23-tribe-management.md for what is assumed.

/// A tribe action that cannot go ahead, with words for the person.
class TribeRefused(message: String) : Exception(message)

/// How long the tribes server is waited for when the core asks it something.
private const val TRIBE_ANSWER_WAIT_MS = 20_000L

/// The tribes this account created, kept as comma-separated pubkeys (`Setting.OWNED_TRIBES`).
object OwnedTribes {
    fun decode(stored: String?): Set<String> =
        stored?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    fun encode(owned: Set<String>): String = owned.sorted().joinToString(",")

    /// Whether this account runs the tribe: the registry names its key as the owner, or it created it here.
    fun owns(accountKey: String, tribe: String, ownerPubkey: String?, owned: Set<String>): Boolean =
        (ownerPubkey != null && ownerPubkey == accountKey) || tribe in owned
}

/// Whether this account is the tribe's owner, for offering the owner's actions (edit, delete,
/// requests, kick, pin). The tribes server enforces it anyway.
fun Engine.ownsTribe(chat: String, info: TribeInfo?): Boolean =
    OwnedTribes.owns(accountKey, chat, info?.ownerPubkey, OwnedTribes.decode(storage.value(Setting.OWNED_TRIBES)))

private fun Engine.setOwned(chat: String, owned: Boolean) {
    val now = OwnedTribes.decode(storage.value(Setting.OWNED_TRIBES))
    val next = if (owned) now + chat else now - chat
    storage.apply(Storage.Commit(settings = mapOf(Setting.OWNED_TRIBES to OwnedTribes.encode(next))))
}

private fun Engine.tribesHost(): String? = storage.value(Setting.TRIBE_HOST)?.takeIf { it.isNotEmpty() }

/// What the registry says about a tribe now, from the account's own tribes host.
suspend fun Engine.tribeInfo(chat: String): TribeInfo {
    val host = tribesHost() ?: throw TribeRefused("This account has no tribes server yet.")
    return TribeInfo.fetch(chat, host)
}

/// Remembers a tribe's registry entry locally: it is a conversation now, named and priced.
private fun Engine.rememberTribe(info: TribeInfo) {
    storage.upsertContact(info.pubkey, null, null, isTribe = true, ready = true)
    storage.updateTribeInfo(info.pubkey, info.name, info.img, info.pricePerMessageMsat, info.escrowMsat)
    info.isPrivate?.let { recordTribePrivacy(info.pubkey, it) }
}

// MARK: - Join

/// What joining would do, shown before it is confirmed: the registry's entry and what the join pays.
data class JoinOffer(val info: TribeInfo, val amountMsat: Long, val alreadyJoined: Boolean)

/// Reads what [input] names (`TribeLink.parse`) and looks it up in the registry. Nothing is sent.
suspend fun Engine.lookupTribe(input: String): JoinOffer {
    val target = try {
        TribeLink.parse(input)
    } catch (e: TribeLink.Problem) {
        throw TribeRefused(e.message ?: "That is not a tribe.")
    }
    val host = try {
        TribeLink.hostFor(target, tribesHost())
    } catch (e: TribeLink.Problem) {
        throw TribeRefused(e.message ?: "That tribe is not on this account's tribes server.")
    }
    val info = try {
        TribeInfo.lookup(target.id, host)
    } catch (e: Exception) {
        throw TribeRefused("Could not find that tribe on $host.")
    }
    if (target.pubkey != null && info.pubkey != target.pubkey) throw TribeRefused("The tribes server answered for a different tribe.")
    val known = storage.contact(info.pubkey)
    return JoinOffer(info, ChatDetails.joinPrice(info.priceToJoinMsat).msat, known != null && known.isTribe && known.ready)
}

/// Joins the tribe [offer] describes, paying its price to join (1 sat when it asks nothing, `ChatDetails.joinPrice`) through the
/// usual balance and confirmation checks: over the threshold it throws `PaymentRefused` with
/// `confirmMsat`, and the second call, with that amount as [confirmedMsat], joins. A private tribe
/// shows once the request is sent; its owner decides. Returns the tribe's pubkey, the chat's id.
suspend fun Engine.joinTribe(offer: JoinOffer, confirmedMsat: Long? = null): String {
    val info = offer.info
    if (offer.alreadyJoined) return info.pubkey
    val routeHint = info.ownerRouteHint ?: throw TribeRefused("The tribes server gave no route to this tribe's owner.")
    if (!isConnected) throw PaymentRefused("Not connected.")
    checkPayment(offer.amountMsat, confirmedMsat, unasked = info.priceToJoinMsat == 0L)
    val alias = sentIdentity(info.pubkey, true).first.ifBlank { "Peel user" }
    val seed = seed
    run(ordered = true) { time, state ->
        coreJoinTribe(seed, time, state, info.pubkey, routeHint, alias, offer.amountMsat.toULong(), info.isPrivate == true)
    }
    rememberTribe(info)
    return info.pubkey
}

// MARK: - Create, edit, delete

/// Creates a tribe from [form] on the account's default tribes server; this account owns it.
/// Returns the new tribe's pubkey, the chat's id.
suspend fun Engine.createTribe(form: TribeForm): String {
    form.problem()?.let { throw TribeRefused(it) }
    if (!isConnected) throw NotConnectedException()
    val json = form.copy(pubkey = null, uuid = null, pin = null).toJson()
    val seed = seed
    val answer = askAndAwaitOrNow(TRIBE_ANSWER_WAIT_MS, expect = { it.newTribe != null }) {
        run { time, state -> coreCreateTribe(seed, time, state, coreGetDefaultTribeServer(state), json) }
    } ?: throw TribeRefused("The tribes server did not answer. The tribe may still appear; check your chats.")
    val created = runCatching { JSONObject(answer.newTribe ?: "") }.getOrNull()
    val pubkey = created?.optString("pubkey")?.takeIf { it.isNotEmpty() }
        ?: throw TribeRefused("The tribes server answered without the new tribe's key.")
    setOwned(pubkey, true)
    storage.upsertContact(pubkey, null, null, isTribe = true, ready = true)
    storage.updateTribeInfo(pubkey, form.name.trim(), form.img, form.pricePerMessageSat * 1_000L, form.escrowSat * 1_000L)
    recordTribePrivacy(pubkey, form.approvalRequired)
    return pubkey
}

/// Saves [form] over the tribe [chat] (owner only). The registry shows it once the tribes server has it.
suspend fun Engine.updateTribe(chat: String, form: TribeForm) {
    form.problem()?.let { throw TribeRefused(it) }
    if (!isConnected) throw NotConnectedException()
    val json = form.copy(pubkey = chat).toJson()
    val seed = seed
    run { time, state -> coreUpdateTribe(seed, time, state, coreGetDefaultTribeServer(state), json) }
    storage.updateTribeInfo(chat, form.name.trim(), form.img, form.pricePerMessageSat * 1_000L, form.escrowSat * 1_000L)
    recordTribePrivacy(chat, form.approvalRequired)
}

/// Deletes a tribe this account owns, for everyone, and takes it off this device.
suspend fun Engine.deleteTribe(chat: String) {
    if (!isConnected) throw NotConnectedException()
    val seed = seed
    run { time, state -> coreDeleteTribe(seed, time, state, coreGetDefaultTribeServer(state), chat) }
    setOwned(chat, false)
    storage.removeChat(chat)
}

/// Leaves a tribe: tells it (a type-15 message, paying the tribe's usual message price) and takes
/// it off this device. An owner deletes instead ([deleteTribe]).
suspend fun Engine.leaveTribe(chat: String) {
    sendTribeCommand(chat, GROUP_LEAVE_MESSAGE_TYPE, TribeMembers.leaveBody(), store = false)
    storage.removeChat(chat)
}

// MARK: - Members

/// The tribe's members as its server lists them, with anyone whose join request here is still
/// unanswered. Throws `TribeRefused` when the server does not answer and nothing is pending.
suspend fun Engine.tribeMembers(chat: String): List<TribeMember> {
    if (!isConnected) throw NotConnectedException()
    val seed = seed
    val answer = askAndAwaitOrNow(TRIBE_ANSWER_WAIT_MS, expect = { it.tribeMembers != null }) {
        run { time, state -> coreListTribeMembers(seed, time, state, coreGetDefaultTribeServer(state), chat) }
    }
    val requests = TribeMembers.pendingRequests(storage.messages(chat))
    if (answer == null && requests.isEmpty()) throw TribeRefused("The tribes server did not answer. Try again in a moment.")
    return TribeMembers.merge(TribeMembers.parse(answer?.tribeMembers), requests)
}

/// Lets a member who asked to join in (a type-20 message answering their request).
suspend fun Engine.approveMember(chat: String, member: TribeMember) {
    sendTribeCommand(chat, MEMBER_APPROVE_MESSAGE_TYPE, TribeMembers.answerBody(member), store = true)
}

/// Turns a join request down (a type-21 message answering it).
suspend fun Engine.rejectMember(chat: String, member: TribeMember) {
    sendTribeCommand(chat, MEMBER_REJECT_MESSAGE_TYPE, TribeMembers.answerBody(member), store = true)
}

/// Removes a member from a tribe this account owns (a type-16 message naming them).
suspend fun Engine.kickMember(chat: String, memberPubkey: String) {
    sendTribeCommand(chat, GROUP_KICK_MESSAGE_TYPE, TribeMembers.kickBody(memberPubkey), store = false)
}

// MARK: - Pin

/// Pins the message [uuid] in a tribe this account owns, or unpins with null: the registry's `pin`
/// field, set through `update_tribe` with everything else the tribe has now left as it is.
suspend fun Engine.pinMessage(chat: String, uuid: String?) {
    val current = tribeInfo(chat)
    updateTribe(chat, TribeForm.from(current).copy(pin = uuid ?: ""))
}

// MARK: - Sending a tribe command

/// A tribe message that is a command, sent as any tribe message is (paying the tribe's price).
/// [store] keeps the row, so an approve or reject answers its request here at once.
private suspend fun Engine.sendTribeCommand(chat: String, type: Int, body: String, store: Boolean) {
    if (!isConnected) throw NotConnectedException()
    val amountMsat = tribeMessageAmountMsat(chat)
    val (myAlias, myPhoto) = sentIdentity(chat, true)
    val seed = seed
    val result = run { time, state ->
        coreSend(seed, time, chat, type.toUByte(), body, state, myAlias, myPhoto, amountMsat.toULong(), true)
    }
    if (!store) return
    val sent = result.msgs.firstOrNull()
    storage.insertMessages(
        listOf(
            MessageRow(
                uuid = sent?.uuid, serverIndex = null, type = type, chat = chat, fromMe = true, sender = null, body = body,
                msat = amountMsat, timestamp = System.currentTimeMillis(), tag = sent?.tag, paymentHash = sent?.paymentHash,
                status = "PENDING",
            ),
        ),
    )
}
