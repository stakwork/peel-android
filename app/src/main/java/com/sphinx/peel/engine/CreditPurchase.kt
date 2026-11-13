package com.sphinx.peel.engine

import org.json.JSONObject

// Buying credit for a visitor's page (strut's specs/WALLET.md §1.2; iOS `CreditPurchase.swift`, docs/phases/34-buy-credit.md). Pure, JVM-tested.

/// A purchase of CREDIT on a strut server for a visitor's page: what strut's UI asks the native host to pay, one tap, so that a priced launch in a
/// public app runs. The host's own purchase, never an app's call: no connection carries it, no budget counts it, and every purchase asks. Peel decodes
/// the invoice itself; everything else here is the seller's word.
data class CreditPurchase(
    /// BOLT 11, lower case, naming its amount.
    val invoice: String,
    val paymentHash: String,
    /// msat: the price, `sats × 1000`.
    val amountMsat: Long,
    /// The credit the seller says the payment buys, in dollars: its own figure, never the price.
    val usd: Double,
    /// The seller's words for what it sells.
    val description: String,
    /// Unix seconds, as the page says; the sheet holds the decoded end.
    val expiresAt: Long,
    /// The strut server the credit is bought on, as the page names it.
    val seller: String,
    /// The public app whose page asked, as the seller serves it.
    val app: SignInApp,
    /// The workflow the launch is for.
    val workflow: String,
) {
    companion object {
        /// The longest description, title or name a purchase carries (strut's `CREDIT_TEXT_MAX`, as the sign-in's).
        const val TEXT_MAX = 1000

        /// A purchase strut's checks pass; null for anything else.
        fun of(raw: Any?): CreditPurchase? {
            if (raw == null || problems(raw).isNotEmpty()) return null
            val p = raw as? JSONObject ?: return null
            val app = p.opt("app") as? JSONObject ?: return null
            return CreditPurchase(
                invoice = p.opt("invoice") as? String ?: return null,
                paymentHash = p.opt("payment_hash") as? String ?: return null,
                amountMsat = WalletProtocol.integer(p.opt("amount")) ?: return null,
                usd = number(p.opt("usd")) ?: return null,
                description = p.opt("description") as? String ?: return null,
                expiresAt = WalletProtocol.integer(p.opt("expires_at")) ?: return null,
                seller = p.opt("seller") as? String ?: return null,
                app = SignInApp(
                    app.opt("name") as? String ?: return null, app.opt("version") as? String ?: return null,
                    app.opt("title") as? String ?: return null,
                ),
                workflow = p.opt("workflow") as? String ?: return null,
            )
        }

        /// What is wrong with a purchase; none when it is good. Shapes and the arithmetic between them, a port of strut's `creditPurchaseProblems` (its
        /// vectors' `credit_purchases` hold it); the host still decodes the invoice and holds it to `amount`, `payment_hash` and its end.
        fun problems(raw: Any?): List<String> {
            val p = raw as? JSONObject ?: return listOf("a purchase must be an object")
            val out = mutableListOf<String>()
            fun text(value: Any?, what: String) {
                val s = (value as? String)?.takeIf { it.isNotEmpty() }
                if (s == null) { out += "$what must be a string"; return }
                if (s.length > TEXT_MAX) out += "$what is longer than $TEXT_MAX characters"
            }
            fun whole(value: Any?, from: Long): Long? = WalletProtocol.integer(value)?.takeIf { it >= from }
            fun dollars(value: Any?) = number(value)?.let { it >= 0 } ?: false

            val invoice = p.opt("invoice") as? String
            val namesAmount = invoice?.takeIf { it == it.lowercase() }?.let { WalletProtocol.invoiceNamesAmount(it) }
            if (namesAmount != null) {
                if (!namesAmount) out += "invoice must name its amount"
            } else {
                out += "invoice must be a BOLT11 invoice, lower case"
            }
            if (!((p.opt("payment_hash") as? String)?.let(WalletProtocol::isHash) ?: false)) out += "payment_hash must be 64 hex"
            val sats = whole(p.opt("sats"), 1)
            if (sats == null) out += "sats must be a whole number above 0"
            val amount = whole(p.opt("amount"), 1)
            if (amount != null) {
                val product = sats?.let { runCatching { Math.multiplyExact(it, 1000L) }.getOrNull() }
                if (sats != null && product != amount) out += "amount must be sats × 1000"
            } else {
                out += "amount must be msat above 0"
            }
            if (!dollars(p.opt("usd"))) out += "usd must be a number of dollars, 0 or more"
            if (p.has("rate")) {
                val r = p.opt("rate") as? JSONObject
                if (!(r?.let { number(it.opt("usd_per_btc")) }?.let { it > 0 } ?: false) || r?.opt("at") !is String || r.opt("source") !is String) {
                    out += "rate must be { usd_per_btc, at, source }"
                }
            }
            text(p.opt("description"), "description")
            if (whole(p.opt("expires_at"), 1) == null) out += "expires_at must be unix seconds"
            if (!((p.opt("seller") as? String)?.let(Seller::isSellerURL) ?: false)) {
                out += "seller must be a strut server's base URL: https, or http on loopback, with no credentials, query or fragment"
            }
            val app = p.opt("app") as? JSONObject
            if (app != null) {
                text(app.opt("name"), "app.name")
                text(app.opt("version"), "app.version")
                text(app.opt("title"), "app.title")
            } else {
                out += "app is missing"
            }
            text(p.opt("workflow"), "workflow")
            val credit = p.opt("credit") as? JSONObject
            if (credit != null) {
                if (credit.opt("carries") !is Boolean) out += "credit.carries must be true or false"
                if (credit.has("reason") && (credit.opt("reason") as? String) !in listOf("short", "expired", "replaced")) {
                    out += "credit.reason must be short, expired or replaced"
                }
                if (credit.has("remaining_usd") && !dollars(credit.opt("remaining_usd"))) {
                    out += "credit.remaining_usd must be a number of dollars, 0 or more"
                }
            } else {
                out += "credit is missing"
            }
            return out
        }

        /// A finite JSON number, either shape (not a boolean or a string).
        fun number(value: Any?): Double? = when (value) {
            is Double -> value.takeIf { it.isFinite() }
            is Float -> value.toDouble().takeIf { it.isFinite() }
            is Number -> value.toDouble()
            else -> null
        }
    }
}

/// How strut's UI reads the host's answer to a purchase (`readCreditReply`, kit/src/wallet/protocol.ts; iOS's `CreditPurchaseTests.read`): `paid` with
/// the preimage that proves it, `pending` once sent, `fallback` with a code when nothing was.
data class CreditReply(val outcome: String, val code: String? = null, val preimage: String? = null) {
    companion object {
        private val KNOWN = WalletError.entries.map { it.name }.toSet()

        fun read(reply: Any?, hash: String): CreditReply {
            fun fallback(code: String) = CreditReply("fallback", code)
            val r = reply as? JSONObject ?: return fallback("INTERNAL")
            if (r.has("error")) {
                val code = ((r.opt("error") as? JSONObject)?.opt("data") as? JSONObject)?.opt("code") as? String
                return fallback(if (code != null && code in KNOWN) code else "OTHER")
            }
            val result = r.opt("result") as? JSONObject ?: return fallback("INTERNAL")
            if (result.opt("payment_hash") != hash.lowercase()) return fallback("INTERNAL")
            val state = result.opt("state")
            if (state == "failed") return fallback("PAYMENT_FAILED")
            if (state != "settled" && state != "pending") return fallback("INTERNAL")
            if (state == "pending" || WalletProtocol.resultProblems(WalletMethod.PAY_INVOICE, result).isNotEmpty()) return CreditReply("pending")
            val preimage = result.opt("preimage") as? String ?: ""
            return if (WalletProtocol.verifyPreimage(preimage, hash)) CreditReply("paid", null, preimage.lowercase()) else CreditReply("pending")
        }
    }
}

/// What strut's UI reads a native host's `host_capability` answer as (kit/src/wallet/protocol.ts `readHostCapability`, held to the vectors'
/// `host_capabilities`): version 1 only; the ops it knows (a newer host's are left out), and the wallet's name when short enough to show. Null for
/// anything else, an app's capability included.
data class HostCapability(val ops: List<String>, val wallet: String?) {
    companion object {
        val KNOWN_OPS = listOf("buy_credit", "buy_credit_status", "buy_credit_cancel", "sign_in")
        const val NAME_MAX = 40

        fun read(raw: Any?): HostCapability? {
            val r = raw as? JSONObject ?: return null
            if (WalletProtocol.integer(r.opt("version")) != 1L) return null
            val ops = r.opt("ops") as? org.json.JSONArray ?: return null
            val known = (0 until ops.length()).mapNotNull { ops.opt(it) as? String }.filter { it in KNOWN_OPS }
            val name = (r.opt("wallet") as? String)?.takeIf { it.isNotEmpty() && it.length <= NAME_MAX }
            return HostCapability(known, name)
        }
    }
}
