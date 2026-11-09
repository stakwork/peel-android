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
/// (`TimeZoneSharing`), and on a contact the key's QR with its share button
/// and "since"/local-time lines (`TimeZones.kt`). Not ported (no backing functionality on Android
/// yet): delete contact; leave/edit/members are in `Tribes.kt`
/// (`docs/phases/23-tribe-management.md`); see `docs/phases/22-tribe-details-parity.md`.
object ChatDetails {
    /// A tribe's link in the official apps' format (`Tribe.link`).
    fun tribeLink(pubkey: String, host: String?): String? =
        host?.takeIf { it.isNotEmpty() }?.let { "sphinx.chat://?action=tribeV2&pubkey=$pubkey&host=$it" }

    data class Fact(val label: String, val value: String)

    /// The members row's value, as iOS `TribeMembers.countLabel` for a list of people seen
    /// posting: them and this account ("4 seen"). The registry's own `member_count` is never
    /// used: it is not kept up (iOS `TribeInfo.memberCount`).
    fun seenLabel(others: Int): String = "${Sats.grouped(others + 1L, Locale.getDefault())} seen"

    private const val FLOOR_MSAT = 3_000L
    private const val JOIN_FLOOR_MSAT = 1_000L

    /// What a member pays and why when it is not the owner's price (iOS `TribePrice`).
    data class Price(val msat: Long, val note: String?) {
        val text: String get() = Sats.exact(msat)
        val described: String get() = note?.let { "$text, $it" } ?: text
    }

    /// The price to join, or 1 sat ("the minimum") when there is none (`TribePrice.join`).
    fun joinPrice(priceToJoinMsat: Long): Price =
        if (priceToJoinMsat > 0) Price(priceToJoinMsat, null) else Price(JOIN_FLOOR_MSAT, "the minimum")

    /// The price plus the escrow, or the 3-sat floor if higher (`TribePrice.message`).
    fun messagePrice(pricePerMessageMsat: Long, escrowMsat: Long): Price {
        val price = pricePerMessageMsat.coerceAtLeast(0)
        val escrow = escrowMsat.coerceAtLeast(0)
        val sum = if (price > Long.MAX_VALUE - escrow) Long.MAX_VALUE else price + escrow
        val msat = maxOf(sum, FLOOR_MSAT)
        val note = when {
            msat > sum -> "the minimum"
            escrow > 0 -> if (price > 0) "including ${Sats.exact(escrow)} escrow" else "all of it escrow"
            else -> null
        }
        return Price(msat, note)
    }

    /// "5 sats, back after 2 hours", or "None"; the time once the registry answers (`escrowLine`).
    fun escrowLine(escrowMsat: Long, escrowMillis: Long?): String {
        if (escrowMsat <= 0) return "None"
        val amount = Sats.exact(escrowMsat)
        if (escrowMillis == null || escrowMillis <= 0) return amount
        val hours = java.math.BigDecimal(escrowMillis).divide(java.math.BigDecimal(3_600_000L))
            .setScale(6, java.math.RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()
        return "$amount, back after $hours ${if (hours == "1") "hour" else "hours"}"
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

    /// The prices box, in the iPhone's order and always whole: owner, to join, each message,
    /// escrow. What only the registry knows reads "\u2014" until it answers ("Not given" for an
    /// owner it did not name). Stored prices are replaced by the registry's.
    fun tribeFacts(
        info: TribeInfo?,
        storedPricePerMessageMsat: Long,
        storedEscrowMsat: Long,
    ): List<Fact> {
        val owner = info?.let { it.ownerAlias ?: "Not given" } ?: "\u2014"
        val join = info?.let { joinPrice(it.priceToJoinMsat).described } ?: "\u2014"
        val price = info?.pricePerMessageMsat ?: storedPricePerMessageMsat
        val escrow = info?.escrowMsat ?: storedEscrowMsat
        return listOf(
            Fact("Owner", owner),
            Fact("To join", join),
            Fact("Each message", messagePrice(price, escrow).described),
            Fact("Escrow", escrowLine(escrow, info?.escrowMillis)),
        )
    }
}
