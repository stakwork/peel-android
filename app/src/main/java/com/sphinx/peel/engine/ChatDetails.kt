package com.sphinx.peel.engine

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

/// What a chat's details view shows, kept pure so it is testable on a plain
/// JVM. Ported in spirit from PeelCore's `Tribe.link` / the iPhone's
/// `ChatDetails.swift`: a row is shown only when it has something to say.
/// Ported since: the QR of the tribe link, the "Seen here" count, the
/// "To join" fact, the hero subtitle and the time zone switch/picker
/// (`TimeZoneSharing`). Not ported (no backing functionality on Android
/// yet): leave tribe, delete contact, edit tribe, members list, editing
/// "How you appear in #<tribe>", the QR button (save/share the QR image),
/// a contact's QR; see `docs/phases/22-tribe-details-parity.md`.
object ChatDetails {
    /// A tribe's link in the official apps' format (`Tribe.link`).
    fun tribeLink(pubkey: String, host: String?): String? =
        host?.takeIf { it.isNotEmpty() }?.let { "sphinx.chat://?action=tribeV2&pubkey=$pubkey&host=$it" }

    data class Fact(val label: String, val value: String)

    /// The "Seen here" row's value: the registry's member count, null when it said none.
    fun seenHere(info: TribeInfo?): String? = info?.memberCount?.takeIf { it > 0 }?.let {
        "${Sats.number(it * Sats.MSAT_PER_SAT)} ${if (it == 1L) "member" else "members"}"
    }

    /// The hero's line under the name: "Private tribe · Created 3 Mar 2021" (a public tribe
    /// reads "Public tribe"); null when the registry said neither.
    fun heroSubtitle(info: TribeInfo?, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String? {
        val kind = when (info?.isPrivate) { true -> "Private tribe"; false -> "Public tribe"; null -> null }
        val date = info?.created?.let {
            try {
                val instant = OffsetDateTime.parse(it).toInstant()
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(instant.atZone(zone))
            } catch (e: DateTimeParseException) {
                null
            }
        }
        return listOfNotNull(kind, date?.let { "Created $it" }).takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /// The tribe's facts, in the iPhone's order, empty ones left out.
    /// `pricePerMessageMsat`/`escrowMsat` are the stored ones, replaced by
    /// the registry's when it answered.
    fun tribeFacts(
        info: TribeInfo?,
        storedPricePerMessageMsat: Long,
        storedEscrowMsat: Long,
    ): List<Fact> {
        val facts = mutableListOf<Fact>()
        info?.ownerAlias?.let { facts += Fact("Owner", it) }
        info?.priceToJoinMsat?.takeIf { it > 0 }?.let { facts += Fact("To join", Sats.format(it)) }
        val price = info?.pricePerMessageMsat ?: storedPricePerMessageMsat
        val escrow = info?.escrowMsat ?: storedEscrowMsat
        if (price > 0) facts += Fact("Each message", Sats.format(price))
        if (escrow > 0) facts += Fact("Escrow", Sats.format(escrow))
        return facts
    }
}
