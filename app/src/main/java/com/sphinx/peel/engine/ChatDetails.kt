package com.sphinx.peel.engine

/// What a chat's details view shows, kept pure so it is testable on a plain
/// JVM. Ported in spirit from PeelCore's `Tribe.link` / the iPhone's
/// `ChatDetails.swift`: a row is shown only when it has something to say.
/// Not ported (no backing functionality on Android yet): leave tribe,
/// delete contact, edit tribe, members list, join price, the time zone
/// switch.
object ChatDetails {
    /// A tribe's link in the official apps' format (`Tribe.link`).
    fun tribeLink(pubkey: String, host: String?): String? =
        host?.takeIf { it.isNotEmpty() }?.let { "sphinx.chat://?action=tribeV2&pubkey=$pubkey&host=$it" }

    data class Fact(val label: String, val value: String)

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
        info?.memberCount?.let { facts += Fact("Members", Sats.number(it * Sats.MSAT_PER_SAT)) }
        val price = info?.pricePerMessageMsat ?: storedPricePerMessageMsat
        val escrow = info?.escrowMsat ?: storedEscrowMsat
        if (price > 0) facts += Fact("Each message", Sats.format(price))
        if (escrow > 0) facts += Fact("Escrow", Sats.format(escrow))
        return facts
    }
}
