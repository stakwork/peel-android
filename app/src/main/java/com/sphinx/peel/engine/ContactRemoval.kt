package com.sphinx.peel.engine

import org.json.JSONObject
import uniffi.sphinxrs.deleteMsgs as coreDeleteMsgs

/// Removing a contact. Ported from PeelCore's `Engine+Contacts.swift` (`deleteContact`),
/// `Engine.swift` (`forgetContact`, `removeFiles`) and `Storage+Writes.swift` (`deleteContact`);
/// see docs/phases/24-delete-contact.md for what differs.

/// Why a contact cannot be removed (PeelCore's `Failure.notAContact`, `.paymentWithContactPending`).
/// [NotConnectedException] is the third refusal: the server's copy goes first.
class ContactRemovalRefused(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { NOT_A_CONTACT, PAYMENT_PENDING }
}

/// The pure part of removing a contact: which refusals apply, in which order, and which core
/// calls are made with which arguments. The core, the connection and the storage are passed in
/// so a JVM test can drive it (`Engine` itself needs the native library).
object ContactRemoval {
    /// What the server deletes by index, asked with the account's own key, as the official apps do
    /// (PeelCore's `MessageType.accountKeyTypes`): invoice payment 3, direct payment 5, purchase 7,
    /// boost 29, contact key 10 and its confirmation 11. The rest goes with everything on the
    /// contact's child key.
    val ACCOUNT_KEY_TYPES = listOf(
        INVOICE_PAYMENT_MESSAGE_TYPE, DIRECT_PAYMENT_MESSAGE_TYPE, 7, BOOST_MESSAGE_TYPE,
        com.sphinx.peel.storage.CONTACT_KEY_MESSAGE_TYPE, CONTACT_KEY_CONFIRMATION_MESSAGE_TYPE,
    )

    /// Whether the core's JSON for a contact (`get_contact`) shows the key exchange completed
    /// (`contact_key` present and not empty).
    fun hasContactKey(contactJson: String): Boolean =
        runCatching {
            // A JSON null is no key: `optString` would give it back as the text "null".
            val json = JSONObject(contactJson)
            !json.isNull("contact_key") && json.optString("contact_key", "").isNotEmpty()
        }.getOrDefault(false)

    /// The steps, in PeelCore's order. [isContact] is the core knowing them as a person (a tribe or
    /// a stranger is not one); [hasPendingPayment] sats still on their way; [connected] registered
    /// and a transport; [contactJson] the core's record of them; [serverIndexes] their account-key
    /// messages; [runCore] one published `delete_msgs(pubkey, msgIdxs)`; [forget] the local removal.
    suspend fun delete(
        pubkey: String,
        isContact: Boolean,
        hasPendingPayment: () -> Boolean,
        connected: Boolean,
        contactJson: () -> String?,
        serverIndexes: () -> List<Long>,
        runCore: suspend (pubkey: String?, msgIdxs: List<ULong>?) -> Unit,
        forget: suspend () -> Unit,
    ) {
        if (!isContact) throw ContactRemovalRefused(ContactRemovalRefused.Reason.NOT_A_CONTACT, "That is not one of your contacts.")
        if (hasPendingPayment()) {
            throw ContactRemovalRefused(
                ContactRemovalRefused.Reason.PAYMENT_PENDING,
                "Sats with this contact are still on their way. Try again once they have arrived.",
            )
        }
        if (!connected) throw NotConnectedException("Not connected. Connect to remove a contact: the server's copy goes first.")
        val indexes = serverIndexes()
        val exchanged = contactJson()?.let(::hasContactKey) == true
        if (exchanged && indexes.isNotEmpty()) runCore(null, indexes.map { it.toULong() })
        runCore(pubkey, null)
        forget()
    }
}

/// Removes a contact as the official apps do (`deleteContactOrChatMsgsFor`, `deleteContactFromState`,
/// `deleteContactObjectsFor`): the server deletes the chat's key exchange and payment messages by
/// index (once the exchange completed) and everything on the contact's child key, so a restore never
/// brings them back; then the core forgets them and the chat's rows and files go here
/// ([forgetContact]). Nothing is sent to them: they keep their copy, and either side can add the
/// other again. Payment history stays.
///
/// Refused ([ContactRemovalRefused]) for a tribe or a stranger, while sats with them are still on
/// their way, and offline ([NotConnectedException]), since the server's copy must go first.
suspend fun Engine.deleteContact(pubkey: String) {
    val seed = seed
    ContactRemoval.delete(
        pubkey = pubkey,
        isContact = storage.contact(pubkey)?.isTribe != true && contactEntryJson(pubkey) != null,
        hasPendingPayment = { storage.hasPendingPayment(pubkey) },
        connected = isRegistered && isConnected,
        contactJson = { contactEntryJson(pubkey) },
        serverIndexes = { storage.serverIndexes(pubkey, ContactRemoval.ACCOUNT_KEY_TYPES) },
        runCore = { who, indexes ->
            run(ordered = true) { time, state -> coreDeleteMsgs(seed, time, state, who, indexes) }
        },
        forget = { forgetContact(pubkey) },
    )
}
