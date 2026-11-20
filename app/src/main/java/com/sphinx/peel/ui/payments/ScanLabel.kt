package com.sphinx.peel.ui.payments

import com.sphinx.peel.engine.ContactCode
import com.sphinx.peel.engine.InviteLink
import com.sphinx.peel.engine.PaymentInput
import com.sphinx.peel.engine.Sats
import com.sphinx.peel.engine.Seller
import com.sphinx.peel.engine.TribeLink
import java.net.URLDecoder
import java.util.Locale

/// The short name the scanner's pill shows for what the camera read (the iOS Camera's
/// "Open in Safari" pill): a host for a web address, a word for the links Peel knows.
/// The words a pill label is made of; [English] is what the JVM tests read, the scanner passes string resources.
class ScanWords(
    val openInPeel: String,
    val openHostInPeel: (String) -> String,
    val linkBox: String,
    val invite: String,
    val contact: String,
    val tribe: String,
    val invoice: String,
    val invoiceAmount: (String) -> String,
) {
    companion object {
        val English = ScanWords(
            "Open in Peel", { "Open $it in Peel" }, "Link a box", "Sphinx invite", "Contact", "Tribe",
            "Lightning invoice", { "Lightning invoice · $it" },
        )
    }
}

object ScanLabel {
    private const val LONGEST = 30
    private val BOLT11 = Regex("^ln(?:bcrt|bc|tb|sb)([0-9]*)([munp]?)1[a-z0-9]{20,}$")

    fun of(raw: String, locale: Locale = Locale.getDefault(), words: ScanWords = ScanWords.English): String {
        val text = raw.trim()
        val lower = text.lowercase()
        if (lower.startsWith("peel://open")) {
            val host = param(text, "url")?.let { Seller.host(it) ?: web(it) }
            return if (host == null) words.openInPeel else words.openHostInPeel(host)
        }
        if (lower.startsWith("peel://strut")) return words.linkBox
        if (InviteLink.fromDeepLink(scheme(text), param(text, "action"), text) != null) return words.invite
        web(text)?.let { return it }
        invoice(text, locale, words)?.let { return it }
        if (ContactCode.contactInfo(text) != null) return words.contact
        if (isTribe(text)) return words.tribe
        return if (text.length <= LONGEST) text else text.take(LONGEST).trimEnd() + "…"
    }

    /// "Lightning invoice", with the amount when the invoice's prefix states one
    /// (`lnbc2500u1…`: 2,500 µBTC), read without the core.
    private fun invoice(raw: String, locale: Locale, words: ScanWords): String? {
        var text = raw.trim().lowercase()
        if (text.startsWith("lightning:")) text = text.removePrefix("lightning:")
        if (PaymentInput.parse(text) !is PaymentInput.Bolt11) return null
        val match = BOLT11.matchEntire(text) ?: return null
        val digits = match.groupValues[1].toBigIntegerOrNull() ?: return words.invoice
        val msat = when (match.groupValues[2]) {
            "m" -> digits.multiply(java.math.BigInteger.valueOf(100_000_000))
            "u" -> digits.multiply(java.math.BigInteger.valueOf(100_000))
            "n" -> digits.multiply(java.math.BigInteger.valueOf(100))
            "p" -> digits.divide(java.math.BigInteger.TEN)
            else -> digits.multiply(java.math.BigInteger.valueOf(100_000_000_000))
        }
        if (msat.signum() <= 0 || msat.bitLength() > 62) return words.invoice
        return words.invoiceAmount(Sats.format(msat.toLong(), locale))
    }

    /// The host of an http(s) address, without "www.".
    private fun web(text: String): String? {
        val lower = text.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        val host = Seller.host(text) ?: text.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':').lowercase()
        return host.removePrefix("www.").takeIf { it.isNotEmpty() }
    }

    private fun isTribe(text: String): Boolean {
        val target = runCatching { TribeLink.parse(text) }.getOrNull() ?: return false
        // A bare word of sixteen letters would pass as a tribe's id; it takes a link, a key or a host with it.
        return !(target.pubkey == null && target.host == null)
    }

    internal fun scheme(text: String): String? {
        val i = text.indexOf("://")
        return if (i > 0) text.substring(0, i).lowercase() else null
    }

    /// The value of query item [name], decoded, or null.
    internal fun param(text: String, name: String): String? {
        val query = text.substringAfter('?', "").substringBefore('#')
        for (item in query.split('&')) {
            val key = item.substringBefore('=')
            if (key == name && item.contains('=')) {
                return runCatching { URLDecoder.decode(item.substringAfter('='), "UTF-8") }.getOrNull()
            }
        }
        return null
    }
}

/// What a tap on the pill does with a code the screen's own check refused.
sealed class ScanRoute {
    /// A link Peel itself handles: sent to its own activity.
    data object InApp : ScanRoute()

    /// Any other address with a scheme: handed to whichever app opens it.
    data object External : ScanRoute()

    /// Not a link: the refusal is shown as a note.
    data object Refuse : ScanRoute()

    companion object {
        private val SCHEME = Regex("^([a-z][a-z0-9+.-]*):\\S")

        fun of(raw: String): ScanRoute {
            val text = raw.trim()
            if (text.isEmpty() || text.any { it.isWhitespace() }) return Refuse
            val lower = text.lowercase()
            if (lower.startsWith("peel://open") || lower.startsWith("peel://strut")) return InApp
            if (InviteLink.fromDeepLink(ScanLabel.scheme(text), ScanLabel.param(text, "action"), text) != null) return InApp
            // Only these open in another app: an untrusted code must not start tel:, intent:, file:, content: and the like.
            val scheme = SCHEME.find(lower)?.groupValues?.get(1) ?: return Refuse
            if (scheme != "http" && scheme != "https" && scheme != "lightning") return Refuse
            return External
        }
    }
}
