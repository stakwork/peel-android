package com.sphinx.peel.engine

import org.json.JSONObject

/// An invite as the core reads it (`parseInvite`): the code a new account
/// registers with, the server that holds it, and who sent it. Ported from
/// PeelCore's `Engine.Invite` (EngineTypes.swift). `inviterContactInfo` is
/// `pubkey_lsp_scid`, what `addContact` takes.
data class Invite(
    val code: String,
    val server: String?,
    val inviterContactInfo: String?,
    val inviterAlias: String?,
) {
    /// What `Setting.INVITE` holds while the invite is waiting to be used.
    fun toJson(): String = JSONObject().apply {
        put("code", code)
        server?.let { put("server", it) }
        inviterContactInfo?.let { put("inviterContactInfo", it) }
        inviterAlias?.let { put("inviterAlias", it) }
    }.toString()

    companion object {
        /// The invite `Setting.INVITE` holds; null for no value, "" (cleared) or anything unreadable.
        fun fromJson(json: String?): Invite? {
            if (json.isNullOrBlank()) return null
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val code = o.optString("code").takeIf { it.isNotEmpty() } ?: return null
            return Invite(
                code = code,
                server = o.optString("server").takeIf { it.isNotEmpty() },
                inviterContactInfo = o.optString("inviterContactInfo").takeIf { it.isNotEmpty() },
                inviterAlias = o.optString("inviterAlias").takeIf { it.isNotEmpty() },
            )
        }
    }
}

/// Why an invite (or a contact code) was refused, in words for the person.
/// The messages for the invite cases are iOS's own where it has them.
class InviteProblem(message: String) : Exception(message) {
    companion object {
        val NOT_AN_INVITE get() = InviteProblem("That doesn't look like a Sphinx invite.")
        val TOO_LONG get() = InviteProblem("That is too long to be a Sphinx invite.")
        val NO_INVITER get() = InviteProblem("That invite names nobody to add.")
        val NO_SERVER get() = InviteProblem("The invite does not say which server to use.")
    }
}

/// What the core's `parse_invite` says about a text, as plain fields, so the
/// rules around it need no native library to be tested.
data class InviteFields(
    val code: String,
    val lspHost: String?,
    val inviterContactInfo: String?,
    val inviterAlias: String?,
)

/// The one place the native library is touched for reading an invite.
fun interface InviteReader {
    /// Throws for text the core cannot read.
    fun read(inviteQr: String): InviteFields

    companion object {
        val Native = InviteReader { qr ->
            val parsed = uniffi.sphinxrs.parseInvite(qr)
            InviteFields(parsed.code, parsed.lspHost, parsed.inviterContactInfo, parsed.inviterAlias)
        }
    }
}

object InviteLink {
    /// Longer than any invite can be: what a QR code holds at most (iOS `ContactCode.maxLength`).
    const val MAX_LENGTH = 3000

    /// A pasted whole document is refused before it is walked (iOS `maxLength * 4` bytes).
    private const val MAX_BYTES = MAX_LENGTH * 4

    /// The deep link: `sphinx.chat://?action=i&d=…`. The scheme is the official apps' and is not
    /// confirmed for Peel (open question in docs/phases/21-invites.md).
    const val SCHEME = "sphinx.chat"

    /// Reads what was pasted or scanned: the full link, or the bare `d=…`
    /// the core also reads. An invite must say who sent it (they are added
    /// once the account is online), and a signup needs the server it names.
    fun parse(text: String, reader: InviteReader = InviteReader.Native, requireServer: Boolean = true): Invite {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw InviteProblem.TOO_LONG
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw InviteProblem.NOT_AN_INVITE
        if (trimmed.length > MAX_LENGTH) throw InviteProblem.TOO_LONG
        val parsed = try {
            reader.read(trimmed)
        } catch (e: Exception) {
            throw InviteProblem.NOT_AN_INVITE
        }
        if (parsed.code.isBlank()) throw InviteProblem.NOT_AN_INVITE
        val inviter = parsed.inviterContactInfo?.let { ContactCode.contactInfo(it.trim()) } ?: throw InviteProblem.NO_INVITER
        val server = parsed.lspHost?.trim()?.takeIf { it.isNotEmpty() }
        if (requireServer && server == null) throw InviteProblem.NO_SERVER
        return Invite(
            code = parsed.code,
            server = server,
            inviterContactInfo = inviter,
            inviterAlias = parsed.inviterAlias?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /// The text of an incoming `sphinx.chat://?action=i&d=…` link, or null for any other URI (so a
    /// link for something else, a tribe say, never lands in the invite field).
    fun fromDeepLink(scheme: String?, action: String?, uri: String?): String? {
        if (scheme?.lowercase() != SCHEME || action != "i" || uri.isNullOrBlank()) return null
        return uri
    }

    /// The section of a worker's invite link that names the app to open (docs/phases/41-worker-onboarding.md).
    const val APP_SECTION = "a="

    /// The public strut app a worker's invite link names, `…&a=<percent-encoded address>`, to open once the account is ready
    /// (docs/phases/41-worker-onboarding.md). It lives only in the link: the core finds `d=`/`c=`/`t=` by prefix and passes
    /// over it, so the link still goes to the core whole, and the mixer never sees it. Held to a `peel://open?url=` link's rules
    /// ([PublicAppLink.of]); null when there is no `a=`, more than one, or it fails them, and the invite is read as before.
    fun appOf(link: String): PublicAppLink? {
        val sections = link.substringAfter('?', "").split('&').filter { it.startsWith(APP_SECTION) }
        val encoded = sections.singleOrNull()?.removePrefix(APP_SECTION) ?: return null
        return percentDecode(encoded)?.let(PublicAppLink::of)
    }
}
