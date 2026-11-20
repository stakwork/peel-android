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

    /// An invite link on the clipboard (docs/phases/46-invite-from-clipboard.md): strut's join page copies
    /// `sphinx.chat://?action=i&d=…` as it downloads Peel, since nothing carries a tapped link across an install; a worker
    /// may instead have copied the join link itself from the message it came in (`https://<strut>/public/join#action=i&…`,
    /// [fromJoinLink]), which is taken as the same `sphinx.chat://?` link (docs/phases/51-pasted-invite.md). Only a whole
    /// link, held to [fromDeepLink]'s rule (`action=i`) and naming a `d=`; anything else someone copied is null, so it never
    /// lands in the invite field.
    fun fromClipboard(text: String?): String? {
        val trimmed = text?.trim() ?: return null
        if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH || trimmed.any { it.isWhitespace() }) return null
        val prefix = "$SCHEME://?"
        val link = if (trimmed.startsWith(prefix, ignoreCase = true)) trimmed else fromJoinLink(trimmed) ?: return null
        val sections = link.substring(prefix.length).split('&')
        if (sections.count { it == "action=i" } != 1 || sections.none { it.startsWith("d=") && it.length > 2 }) return null
        return fromDeepLink(SCHEME, "i", link)
    }

    /// Strut's join page path (stakwork/strut `src/public-join.ts`): the invite rides after its `#`.
    private const val JOIN_PATH = "/public/join"

    /// The invite in strut's join link, `https://<strut>/public/join[?lang=…]#action=i&d=…&c=…&a=…`, as the
    /// `sphinx.chat://?action=i&…` link the page itself opens Peel with; null for any other text. https only, no user info, the
    /// path exactly the join page's; the part after `#` is taken as the page takes it (a `sphinx.chat://?` before it dropped).
    fun fromJoinLink(text: String): String? {
        val hash = text.indexOf('#').takeIf { it >= 0 } ?: return null
        val page = runCatching { java.net.URI(text.substring(0, hash)) }.getOrNull() ?: return null
        if (page.scheme?.lowercase() != "https" || page.host.isNullOrEmpty() || page.rawUserInfo != null || page.rawPath != JOIN_PATH) return null
        val invite = text.substring(hash + 1).let { if (it.startsWith("$SCHEME://?", ignoreCase = true)) it.substring(SCHEME.length + 4) else it }
        return "$SCHEME://?$invite"
    }

    /// The section of a worker's invite link that names the app to open (docs/phases/41-worker-onboarding.md).
    const val APP_SECTION = "a="

    /// The public strut app a worker's invite link names, `…&a=<percent-encoded address>`, to open once the account is ready
    /// (docs/phases/41-worker-onboarding.md). It lives only in the link: the core finds `d=`/`c=`/`t=` by prefix and passes
    /// over it, so the link still goes to the core whole, and the mixer never sees it. Held to a `peel://open?url=` link's rules
    /// ([PublicAppLink.of]); null when there is no `a=`, more than one, or it fails them, and the invite is read as before.
    /// The sections are read as the core reads them, from whatever follows the first `?` or `#` (strut's join link carries the
    /// invite after `#`; docs/phases/51-pasted-invite.md), or the whole text when it has neither (a bare `d=…&a=…`).
    fun appOf(link: String): PublicAppLink? {
        val start = link.indexOfFirst { it == '?' || it == '#' }
        val sections = link.substring(start + 1).split('&').filter { it.startsWith(APP_SECTION) }
        val encoded = sections.singleOrNull()?.removePrefix(APP_SECTION) ?: return null
        return percentDecode(encoded)?.let(PublicAppLink::of)
    }
}
