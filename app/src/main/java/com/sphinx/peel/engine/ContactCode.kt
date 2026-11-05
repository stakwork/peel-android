package com.sphinx.peel.engine

import java.net.URI

/// What a pasted or scanned text is, for adding a contact: someone's contact
/// info (bare, or in the official apps' `share_contact` link) or an invite
/// (whose inviter is the one added: the code itself is for a new account).
/// Tribe links and anything else are refused. Ported from PeelCore's
/// `ContactCode` (Contacts.swift); tribe links are refused here with a
/// pointer rather than read, since Android has no join flow yet.
sealed class ContactCode {
    /// `pubkey_lsp_scid`, checked and in lower case.
    data class Contact(val info: String) : ContactCode()
    data class FromInvite(val invite: Invite) : ContactCode()

    /// Who would be added: the contact info.
    val contactInfo: String
        get() = when (this) {
            is Contact -> info
            is FromInvite -> contactInfo(invite.inviterContactInfo ?: "") ?: ""
        }

    /// The contact's public key, the conversation's id once added.
    val pubkey: String get() = contactInfo.take(66)

    /// The name an invite gives its inviter.
    val inviterName: String?
        get() = (this as? FromInvite)?.invite?.inviterAlias?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        /// Reads what a QR code or the clipboard held. Strict: contact info is
        /// three parts (two keys and a channel id) and nothing more; a
        /// `sphinx.chat://` link is read only for the actions below.
        fun read(text: String, reader: InviteReader = InviteReader.Native): ContactCode {
            if (text.toByteArray(Charsets.UTF_8).size > InviteLink.MAX_LENGTH * 4) throw problem("notACode")
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed.length > InviteLink.MAX_LENGTH) throw problem("notACode")
            if (trimmed.lowercase().startsWith("${InviteLink.SCHEME}://")) return link(trimmed, reader)
            contactInfo(trimmed)?.let { return Contact(it) }
            if (isPubkey(trimmed)) throw problem("keyWithoutRoute")
            // An invite without its prefix, as the core also reads them.
            if (trimmed.startsWith("d=")) return inviter(trimmed, reader)
            throw problem("notACode")
        }

        /// A contact given as a bare pubkey plus the route hint (`lsp_scid`) someone shared separately.
        fun fromParts(pubkey: String, routeHint: String): ContactCode {
            val key = pubkey.trim().lowercase()
            val hint = routeHint.trim().lowercase()
            return contactInfo("${key}_$hint")?.let { Contact(it) } ?: throw problem("notACode")
        }

        private fun link(text: String, reader: InviteReader): ContactCode {
            val uri = runCatching { URI(text) }.getOrNull() ?: throw problem("notACode")
            // `sphinx.chat://?action=…` has no host and no path.
            if (!uri.host.isNullOrEmpty() || !uri.path.isNullOrEmpty()) throw problem("notACode")
            val items = queryItems(uri.rawQuery)
            return when (items["action"]) {
                "i" -> inviter(text, reader)
                "share_contact" -> {
                    // The official apps write `pubKey`; its value may be a whole contact info.
                    val key = items["pubKey"] ?: items["pubkey"] ?: throw problem("notACode")
                    contactInfo(key)?.let { Contact(it) }
                        ?: throw problem(if (isPubkey(key)) "keyWithoutRoute" else "notACode")
                }
                "tribeV2" -> throw problem("tribe")
                else -> throw problem("notACode")
            }
        }

        private fun inviter(text: String, reader: InviteReader): ContactCode {
            val invite = try {
                InviteLink.parse(text, reader, requireServer = false)
            } catch (e: InviteProblem) {
                // Without an inviter it is an invite, but names nobody to add.
                if (e.message == InviteProblem.NO_INVITER.message) throw e
                throw problem("notACode")
            }
            return FromInvite(invite)
        }

        private fun queryItems(rawQuery: String?): Map<String, String> {
            if (rawQuery.isNullOrEmpty()) return emptyMap()
            val items = LinkedHashMap<String, String>()
            for (part in rawQuery.split("&")) {
                val i = part.indexOf('=')
                val name = if (i < 0) part else part.substring(0, i)
                val value = if (i < 0) "" else part.substring(i + 1)
                val decoded = runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
                items.putIfAbsent(name, decoded)
            }
            return items
        }

        /// `pubkey_lsp_scid` with both keys compressed points in hex and the
        /// channel id a number, lower-cased; null for anything else.
        fun contactInfo(text: String): String? {
            val parts = text.split("_").map { it.lowercase() }
            if (parts.size != 3 || !isKey(parts[0]) || !isKey(parts[1])) return null
            val scid = parts[2]
            if (scid.isEmpty() || scid.length > 20 || !scid.all { it in '0'..'9' } || scid.toULongOrNull() == null) return null
            return parts.joinToString("_")
        }

        /// `pubkey_lsp_scid` split in three, or null.
        fun parts(contactInfo: String): List<String>? = contactInfo(contactInfo)?.split("_")

        private fun isPubkey(text: String): Boolean =
            text.length == 66 && text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

        private fun isKey(text: String): Boolean = isPubkey(text) && (text.startsWith("02") || text.startsWith("03"))

        private fun problem(kind: String): InviteProblem = InviteProblem(
            when (kind) {
                "keyWithoutRoute" ->
                    "That is a public key with no route hint. Ask for their contact code, or add the route hint (the part after the key)."
                "tribe" -> "That is a tribe link, not a person."
                else -> "That is not contact info, an invite or a Sphinx link."
            },
        )
    }
}
