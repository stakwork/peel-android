package com.sphinx.peel.engine

import java.math.BigDecimal
import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject

// Reading numbers the way the wallet protocol does (iOS `WalletProtocol.integer`). Pure, JVM-tested.

object WalletProtocol {
    /// The largest integer a JavaScript number holds exactly (2^53 - 1).
    const val MAX_SAFE = 9007199254740991L

    /// [v] as an integer: an integral number, or a floating one with no fraction (`1.0`), within ±[MAX_SAFE].
    /// A string, a boolean, null, a fraction, NaN or anything out of range is null.
    fun integer(v: Any?): Long? {
        val n: Long = when (v) {
            is Int -> v.toLong()
            is Long -> v
            is Short -> v.toLong()
            is Byte -> v.toLong()
            is Double -> whole(v) ?: return null
            is Float -> whole(v.toDouble()) ?: return null
            is BigInteger -> if (v.bitLength() < 64) v.toLong() else return null
            is BigDecimal -> try {
                val exact = v.toBigIntegerExact()
                if (exact.bitLength() < 64) exact.toLong() else return null
            } catch (e: ArithmeticException) {
                return null
            }
            else -> return null
        }
        return n.takeIf { it in -MAX_SAFE..MAX_SAFE }
    }

    private fun whole(d: Double): Long? {
        if (d.isNaN() || d.isInfinite() || Math.rint(d) != d || Math.abs(d) > MAX_SAFE) return null
        return d.toLong()
    }

    private val HASH = Regex("^[0-9a-f]{64}$")

    /// Whether [text] is 64 lower-case hex characters: a payment hash or a preimage (JavaScript-side values are lowered first).
    fun isHash(text: String): Boolean = HASH.matches(text)

    /// Whether [preimage] hashes (SHA-256) to [paymentHash]: both 64 hex characters, either in any case (iOS `verifyPreimage`). A page is
    /// never handed a preimage that proves nothing.
    fun verifyPreimage(preimage: String, paymentHash: String): Boolean {
        val p = preimage.lowercase()
        val h = paymentHash.lowercase()
        if (!isHash(p) || !isHash(h)) return false
        val bytes = ByteArray(32) { p.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) } == h
    }

    /// BOLT 1: custom records are 2^16 and up; 5482373484 is keysend's preimage.
    const val MIN_CUSTOM_TLV_TYPE = 65_536L
    const val KEYSEND_PREIMAGE_TLV_TYPE = 5_482_373_484L

    /// Sphinx's own record (the core's `SPHINX_TLV_KEY`): its `data`, as base64 text.
    const val SPHINX_TLV_TYPE = 13_377_331L
    const val METADATA_MAX_CHARS = 4096
    const val LIST_LIMIT_MAX = 100L

    private const val BECH32 = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    // `[0-9]` rather than `\d`: JavaScript's `\d` is ASCII only.
    private val INVOICE = Regex("ln(bcrt|bc|tbs|tb|sb)([0-9]+[munp]?)?1[$BECH32]{20,}")
    private val LIGHTNING_ADDRESS = Regex("[a-z0-9._+-]+@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}")
    private val LNURL = Regex("(lnurl1[$BECH32]{10,}|lnurlp://\\S+)")

    // MARK: Checking params

    /// Checks a request's params as every side must: the params as the method takes them, normalized (hex and invoices lower case, a `lightning:` prefix
    /// dropped, a Sphinx address split, unknown keys dropped), or a `BAD_REQUEST`. A port of strut's `checkParams` (iOS `WalletProtocol.checkParams`).
    fun checkParams(method: WalletMethod, params: Any?): Result<JSONObject> =
        try {
            Result.success(check(method, params))
        } catch (bad: Bad) {
            Result.failure(WalletRefusal(WalletError.BAD_REQUEST, bad.message ?: ""))
        }

    private class Bad(message: String) : Exception(message)

    private fun fail(message: String): Nothing = throw Bad(message)

    private fun obj(value: Any?, what: String): JSONObject = when (value) {
        null, JSONObject.NULL -> JSONObject()
        is JSONObject -> value
        else -> fail("$what must be an object")
    }

    private fun amount(value: Any?, what: String = "amount"): Long = integer(value)?.takeIf { it > 0 } ?: fail("$what must be a whole number of msat above 0")

    private fun count(value: Any?, what: String): Long = integer(value)?.takeIf { it >= 0 } ?: fail("$what must be a whole number, 0 or more")

    fun isHex(text: String) = text.all { it in '0'..'9' || it in 'a'..'f' }

    fun isPubkey(text: String) = text.length == 66 && (text.startsWith("02") || text.startsWith("03")) && isHex(text)

    private fun isDigits(text: String) = text.isNotEmpty() && text.all { it in '0'..'9' }

    /// A decimal u64, or `<block>x<tx>x<output>`.
    fun isScid(text: String): Boolean {
        if (isDigits(text)) return true
        val parts = text.split("x")
        return parts.size == 3 && parts.all(::isDigits)
    }

    private enum class HexShape { ANY, PUBKEY, HASH }

    private fun hex(value: Any?, what: String, shape: HexShape = HexShape.ANY): String {
        val raw = value as? String ?: fail("$what must be a hex string")
        val h = raw.lowercase()
        val ok = when (shape) { HexShape.ANY -> isHex(h); HexShape.PUBKEY -> isPubkey(h); HexShape.HASH -> isHash(h) }
        if (!ok || h.length % 2 != 0) fail("$what is not valid hex of the right length")
        return h
    }

    /// A string at most [max] long, counted as JavaScript counts (UTF-16).
    private fun text(value: Any?, what: String, max: Int): String {
        val s = value as? String ?: fail("$what must be a string")
        if (s.length > max) fail("$what is longer than $max characters")
        return s
    }

    /// JavaScript's `trim()`.
    fun trim(s: String): String = s.trim { it.isWhitespace() }

    /// BOLT 11 as bech32: `ln`, a network, an optional amount, `1`, the data. Shape only; a host decodes it. Answers the invoice normalized and whether
    /// its prefix names an amount.
    private fun parsedInvoice(value: Any?): Pair<String, Boolean> {
        val raw = value as? String ?: fail("invoice must be a BOLT11 string")
        var s = trim(raw).lowercase()
        if (s.startsWith("lightning:")) s = s.removePrefix("lightning:")
        val match = INVOICE.matchEntire(s) ?: fail("invoice is not a BOLT11 invoice")
        return s to (match.groups[2] != null)
    }

    /// Whether [text] is BOLT 11's shape exactly as it stands (not trimmed or lowered), and if so whether its prefix names an amount; null when it is not.
    fun invoiceNamesAmount(text: String): Boolean? = INVOICE.matchEntire(text)?.let { it.groups[2] != null }

    /// The JSON text's length as JavaScript's `JSON.stringify(m).length` counts it (no `/` escaped).
    private fun jsonLength(value: Any?): Int = when (value) {
        null, JSONObject.NULL -> 4
        is String -> OrgPageRequest.jsonLiteral(value).length
        is Boolean -> value.toString().length
        is JSONObject -> value.keys().asSequence().sumOf { OrgPageRequest.jsonLiteral(it).length + 1 + jsonLength(value.get(it)) } +
            maxOf(value.length() - 1, 0) + 2
        is JSONArray -> (0 until value.length()).sumOf { jsonLength(value.get(it)) } + maxOf(value.length() - 1, 0) + 2
        is Double -> (if (value % 1.0 == 0.0 && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()).length
        else -> value.toString().length
    }

    private fun metadata(value: Any?): JSONObject {
        val m = obj(value, "metadata")
        if (jsonLength(m) > METADATA_MAX_CHARS) fail("metadata is longer than $METADATA_MAX_CHARS characters as JSON")
        return m
    }

    /// Exactly one of [keys] is present (a JSON null counts as present, as `!== undefined` does).
    private fun exactlyOne(p: JSONObject, keys: List<String>): String {
        val present = keys.filter { p.has(it) }
        if (present.size != 1) fail("give exactly one of ${keys.joinToString(", ")}")
        return present[0]
    }

    private fun routeHints(value: Any?): JSONArray {
        val routes = value as? JSONArray
        if (routes == null || routes.length() == 0) fail("route_hints must be a non-empty list of routes")
        return JSONArray().also { out ->
            for (i in 0 until routes.length()) {
                val hops = routes.get(i) as? JSONArray
                if (hops == null || hops.length() == 0) fail("route_hints[$i] must be a non-empty list of hops")
                out.put(
                    JSONArray().also { hopsOut ->
                        for (j in 0 until hops.length()) {
                            val hop = obj(hops.get(j), "route_hints[$i][$j]")
                            val one = JSONObject().put("pubkey", hex(hop.opt("pubkey"), "route_hints[$i][$j].pubkey", HexShape.PUBKEY))
                            val scid = hop.opt("short_channel_id") as? String
                            if (scid == null || !isScid(scid)) fail("route_hints[$i][$j].short_channel_id must be a decimal u64 or <block>x<tx>x<output>")
                            one.put("short_channel_id", scid)
                            for (key in listOf("fee_base_msat", "fee_proportional_millionths", "cltv_expiry_delta")) {
                                if (hop.has(key)) one.put(key, count(hop.opt(key), "route_hints[$i][$j].$key"))
                            }
                            hopsOut.put(one)
                        }
                    },
                )
            }
        }
    }

    private fun tlvRecords(value: Any?): JSONArray {
        val records = value as? JSONArray ?: fail("tlv_records must be a list")
        val seen = mutableSetOf<Long>()
        return JSONArray().also { out ->
            for (i in 0 until records.length()) {
                val record = obj(records.get(i), "tlv_records[$i]")
                val type = integer(record.opt("type"))?.takeIf { it >= MIN_CUSTOM_TLV_TYPE } ?: fail("tlv_records[$i].type must be a whole number of $MIN_CUSTOM_TLV_TYPE or more")
                if (type == KEYSEND_PREIMAGE_TLV_TYPE) fail("tlv_records[$i]: $KEYSEND_PREIMAGE_TLV_TYPE is keysend's preimage, which the wallet sets")
                if (!seen.add(type)) fail("tlv_records[$i]: type $type is given twice")
                out.put(JSONObject().put("type", type).put("value", hex(record.opt("value"), "tlv_records[$i].value")))
            }
        }
    }

    private fun check(method: WalletMethod, raw: Any?): JSONObject {
        val p = obj(raw, "params")
        when (method) {
            WalletMethod.CONNECT, WalletMethod.GET_INFO, WalletMethod.GET_BUDGET, WalletMethod.GET_BALANCE -> return JSONObject()
            WalletMethod.PAY_INVOICE -> {
                val (invoice, namesAmount) = parsedInvoice(p.opt("invoice"))
                val out = JSONObject().put("invoice", invoice)
                if (p.has("amount")) {
                    out.put("amount", amount(p.opt("amount")))
                    if (namesAmount) fail("the invoice names its own amount: amount is only for an invoice that names none")
                } else if (!namesAmount) {
                    fail("the invoice names no amount: give amount")
                }
                if (p.has("metadata")) out.put("metadata", metadata(p.opt("metadata")))
                return out
            }
            WalletMethod.PAY_KEYSEND -> {
                val target = p.opt("pubkey") as? String ?: fail("pubkey must be a node pubkey or a Sphinx address")
                val out: JSONObject
                val address = SphinxAddress.of(target)
                if (address != null) {
                    if (p.has("route_hints")) fail("a Sphinx address carries its route hint: give route_hints or the address, not both")
                    out = JSONObject().put("pubkey", address.pubkey).put("amount", amount(p.opt("amount"))).put("route_hints", address.routeHints)
                } else {
                    out = JSONObject().put("pubkey", hex(p.opt("pubkey"), "pubkey", HexShape.PUBKEY)).put("amount", amount(p.opt("amount")))
                    if (p.has("route_hints")) out.put("route_hints", routeHints(p.opt("route_hints")))
                }
                if (p.has("tlv_records")) out.put("tlv_records", tlvRecords(p.opt("tlv_records")))
                if (p.has("preimage")) out.put("preimage", hex(p.opt("preimage"), "preimage", HexShape.HASH))
                if (p.has("metadata")) out.put("metadata", metadata(p.opt("metadata")))
                return out
            }
            WalletMethod.MAKE_INVOICE -> {
                val out = JSONObject().put("amount", amount(p.opt("amount")))
                if (p.has("description")) out.put("description", text(p.opt("description"), "description", 639))
                if (p.has("description_hash")) out.put("description_hash", hex(p.opt("description_hash"), "description_hash", HexShape.HASH))
                if (p.has("expiry")) {
                    val expiry = count(p.opt("expiry"), "expiry")
                    if (expiry == 0L) fail("expiry must be above 0 seconds")
                    out.put("expiry", expiry)
                }
                if (p.has("metadata")) out.put("metadata", metadata(p.opt("metadata")))
                return out
            }
            WalletMethod.LOOKUP_PAYMENT -> return when (exactlyOne(p, listOf("transaction_id", "payment_hash", "invoice"))) {
                "transaction_id" -> {
                    val id = text(p.opt("transaction_id"), "transaction_id", 256)
                    if (id.isEmpty()) fail("transaction_id is empty")
                    JSONObject().put("transaction_id", id)
                }
                "payment_hash" -> JSONObject().put("payment_hash", hex(p.opt("payment_hash"), "payment_hash", HexShape.HASH))
                else -> JSONObject().put("invoice", parsedInvoice(p.opt("invoice")).first)
            }
            WalletMethod.LOOKUP_INVOICE ->
                return if (exactlyOne(p, listOf("payment_hash", "invoice")) == "payment_hash") {
                    JSONObject().put("payment_hash", hex(p.opt("payment_hash"), "payment_hash", HexShape.HASH))
                } else {
                    JSONObject().put("invoice", parsedInvoice(p.opt("invoice")).first)
                }
            WalletMethod.LIST_TRANSACTIONS -> {
                val out = JSONObject()
                var from: Long? = null
                var until: Long? = null
                if (p.has("from")) { from = count(p.opt("from"), "from"); out.put("from", from) }
                if (p.has("until")) { until = count(p.opt("until"), "until"); out.put("until", until) }
                if (from != null && until != null && from > until) fail("from is after until")
                if (p.has("limit")) {
                    val limit = count(p.opt("limit"), "limit")
                    if (limit < 1 || limit > LIST_LIMIT_MAX) fail("limit must be 1 to $LIST_LIMIT_MAX")
                    out.put("limit", limit)
                }
                if (p.has("offset")) out.put("offset", count(p.opt("offset"), "offset"))
                if (p.has("unpaid")) out.put("unpaid", p.opt("unpaid") as? Boolean ?: fail("unpaid must be true or false"))
                if (p.has("type")) {
                    val type = p.opt("type")
                    if (type != "incoming" && type != "outgoing") fail("type must be incoming or outgoing")
                    out.put("type", type)
                }
                return out
            }
            WalletMethod.PAY_LIGHTNING_ADDRESS -> {
                val out = JSONObject()
                if (exactlyOne(p, listOf("address", "lnurl")) == "address") {
                    val address = trim(text(p.opt("address"), "address", 320)).lowercase()
                    if (!LIGHTNING_ADDRESS.matches(address)) fail("address is not a Lightning Address (name@domain)")
                    out.put("address", address)
                } else {
                    val lnurl = trim(text(p.opt("lnurl"), "lnurl", 2048))
                    val norm = if (lnurl.lowercase().startsWith("lnurl1")) lnurl.lowercase() else lnurl
                    if (!LNURL.matches(norm)) fail("lnurl is not an LNURL (lnurl1… or lnurlp://…)")
                    out.put("lnurl", norm)
                }
                out.put("amount", amount(p.opt("amount")))
                if (p.has("comment")) out.put("comment", text(p.opt("comment"), "comment", 2000))
                if (p.has("payer_data")) out.put("payer_data", obj(p.opt("payer_data"), "payer_data"))
                if (p.has("metadata")) out.put("metadata", metadata(p.opt("metadata")))
                return out
            }
        }
    }

    // MARK: Connection requests

    /// What is wrong with an `app.yaml` `wallet:` block, one line each; none when it is good. A port of strut's `connectionRequestProblems`.
    fun connectionRequestProblems(raw: Any?): List<String> {
        val r = raw as? JSONObject ?: return listOf("wallet must be a mapping")
        val problems = mutableListOf<String>()
        val requestable = WalletMethod.entries.filter { it.isRequestable }.map { it.wire }
        fun methods(key: String, required: Boolean) {
            if (!r.has(key)) {
                if (required) problems += "$key is required: the methods the app cannot work without"
                return
            }
            val list = r.opt(key) as? JSONArray
            if (list == null) { problems += "$key must be a list of methods"; return }
            for (i in 0 until list.length()) {
                val m = list.get(i)
                if (!(m is String && m in requestable)) problems += "$key: \"${(m as? String) ?: describe(m)}\" is not a method an app may request"
            }
        }
        methods("request_methods", required = true)
        methods("optional_request_methods", required = false)
        if (r.has("notification_types")) {
            val list = r.opt("notification_types") as? JSONArray
            if (list != null) {
                for (i in 0 until list.length()) {
                    val n = list.get(i)
                    if (WalletNotificationType.of(n as? String) == null) problems += "notification_types: \"${(n as? String) ?: describe(n)}\" is not a notification type"
                }
            } else {
                problems += "notification_types must be a list"
            }
        }
        if (r.has("max_amount") && integer(r.opt("max_amount"))?.let { it > 0 } != true) problems += "max_amount must be a whole number of msat above 0"
        if (r.has("renewal_period")) {
            if (WalletRenewal.of(r.opt("renewal_period") as? String) == null) {
                problems += "renewal_period must be one of ${WalletRenewal.entries.joinToString(", ") { it.wire }}"
            } else if (!r.has("max_amount")) {
                problems += "renewal_period needs max_amount"
            }
        }
        if (r.has("expires_at") && r.opt("expires_at") != JSONObject.NULL && integer(r.opt("expires_at"))?.let { it > 0 } != true) problems += "expires_at must be unix seconds, or null"
        val known = setOf("request_methods", "optional_request_methods", "notification_types", "max_amount", "renewal_period", "expires_at")
        for (key in r.keys().asSequence().sorted()) if (key !in known) problems += "$key is not a connection request field"
        return problems
    }

    private fun describe(v: Any?): String = if (v == JSONObject.NULL || v == null) "null" else v.toString()

    // MARK: Checking results and notifications

    private fun isInt(v: Any?, min: Long = 0) = integer(v)?.let { it >= min } ?: false
    private fun isHashValue(v: Any?) = (v as? String)?.let(::isHash) ?: false
    private fun oneOf(v: Any?, list: List<String>) = (v as? String)?.let { it in list } ?: false
    private fun isList(v: Any?, of: List<String>): Boolean {
        val list = v as? JSONArray ?: return false
        return (0 until list.length()).all { oneOf(list.get(it), of) }
    }
    private fun JSONObject.str(key: String) = opt(key) as? String

    private fun paymentProblems(p: JSONObject): List<String> {
        val out = mutableListOf<String>()
        if ((p.str("transaction_id") ?: "").isEmpty()) out += "transaction_id is missing"
        if (!oneOf(p.opt("state"), listOf("pending", "settled", "failed"))) out += "state must be pending, settled or failed"
        if (!isHashValue(p.opt("payment_hash"))) out += "payment_hash must be 64 hex"
        if (!isInt(p.opt("amount"))) out += "amount must be msat"
        if (p.has("fees_paid") && !isInt(p.opt("fees_paid"))) out += "fees_paid must be msat"
        if (!isInt(p.opt("created_at"))) out += "created_at must be unix seconds"
        if (p.opt("state") == "settled") {
            if (!isHashValue(p.opt("preimage"))) out += "a settled payment carries its preimage"
            if (!isInt(p.opt("settled_at"))) out += "a settled payment carries settled_at"
        } else if (p.has("preimage")) {
            out += "preimage only once settled"
        }
        if (p.opt("state") != "failed" && p.has("failure_reason")) out += "failure_reason only on a failed payment"
        return out
    }

    private fun transactionProblems(t: JSONObject, at: String = ""): List<String> {
        val out = mutableListOf<String>()
        if (!oneOf(t.opt("type"), listOf("incoming", "outgoing"))) out += at + "type must be incoming or outgoing"
        if (t.has("state") && !oneOf(t.opt("state"), listOf("pending", "settled", "accepted", "expired", "failed"))) out += at + "state is not a transaction state"
        if (!isHashValue(t.opt("payment_hash"))) out += at + "payment_hash must be 64 hex"
        if (!isInt(t.opt("amount"))) out += at + "amount must be msat"
        if (!isInt(t.opt("created_at"))) out += at + "created_at must be unix seconds"
        if (t.has("preimage") && t.has("state") && t.opt("state") != "settled") out += at + "preimage only once settled"
        return out
    }

    /// What is wrong with a host's answer to [method]; none when it is the shape the contract promises. A port of strut's `resultProblems`.
    fun resultProblems(method: WalletMethod, raw: Any?): List<String> {
        val r = raw as? JSONObject ?: return listOf("the result must be an object")
        when (method) {
            WalletMethod.PAY_INVOICE, WalletMethod.PAY_KEYSEND, WalletMethod.PAY_LIGHTNING_ADDRESS -> return paymentProblems(r)
            WalletMethod.MAKE_INVOICE -> {
                val out = transactionProblems(r).toMutableList()
                if (r.opt("type") != "incoming") out += "an invoice made is incoming"
                if (r.str("invoice") == null) out += "invoice is missing"
                return out
            }
            WalletMethod.LOOKUP_INVOICE -> return transactionProblems(r)
            WalletMethod.LIST_TRANSACTIONS -> {
                val list = r.opt("transactions") as? JSONArray ?: return listOf("transactions must be a list")
                return (0 until list.length()).flatMap { i ->
                    val o = list.get(i) as? JSONObject ?: return@flatMap listOf("transactions[$i]: must be an object")
                    transactionProblems(o, "transactions[$i]: ")
                }
            }
            WalletMethod.LOOKUP_PAYMENT -> {
                val out = mutableListOf<String>()
                if ((r.str("transaction_id") ?: "").isEmpty()) out += "transaction_id is missing"
                if (!oneOf(r.opt("type"), listOf("incoming", "outgoing"))) out += "type must be incoming or outgoing"
                if (!oneOf(r.opt("state"), listOf("pending", "accepted", "settled", "failed", "expired", "canceled"))) out += "state is not a lookup state"
                if (!oneOf(r.opt("payment_type"), listOf("bolt11", "keysend"))) out += "payment_type must be bolt11 or keysend"
                if (!isInt(r.opt("amount"))) out += "amount must be msat"
                if (!isInt(r.opt("created_at"))) out += "created_at must be unix seconds"
                val d = r.opt("details") as? JSONObject
                if (d != null) {
                    if (!isHashValue(d.opt("payment_hash"))) out += "details.payment_hash must be 64 hex"
                    if (r.opt("payment_type") == "keysend" && !(d.str("pubkey")?.let(::isPubkey) ?: false)) out += "a keysend's details carry its pubkey"
                    if (d.has("preimage") && r.opt("state") != "settled") out += "preimage only once settled"
                } else {
                    out += "details is missing"
                }
                return out
            }
            WalletMethod.GET_INFO -> {
                val out = mutableListOf<String>()
                if (!oneOf(r.opt("network"), listOf("bitcoin", "testnet", "signet", "regtest"))) out += "network is not a network"
                if (!isList(r.opt("methods"), WalletMethod.entries.map { it.wire })) out += "methods must be wallet methods"
                if (!isList(r.opt("notifications"), WalletNotificationType.entries.map { it.wire })) out += "notifications must be notification types"
                return out
            }
            WalletMethod.GET_BUDGET -> {
                if (r.length() == 0) return emptyList()
                val out = mutableListOf<String>()
                if (!isInt(r.opt("used_budget"))) out += "used_budget must be msat"
                if (!isInt(r.opt("total_budget"))) out += "total_budget must be msat"
                if (!oneOf(r.opt("renewal_period"), WalletRenewal.entries.map { it.wire })) out += "renewal_period is not a renewal period"
                return out
            }
            WalletMethod.GET_BALANCE -> return if (isInt(r.opt("balance"))) emptyList() else listOf("balance must be msat")
            WalletMethod.CONNECT -> {
                val c = r.opt("connection") as? JSONObject ?: return listOf("connection is missing")
                val out = mutableListOf<String>()
                if (!isList(c.opt("methods"), WalletMethod.entries.filter { it.isRequestable }.map { it.wire })) out += "connection.methods must be requestable methods"
                if (!isList(c.opt("notification_types"), WalletNotificationType.entries.map { it.wire })) out += "connection.notification_types must be notification types"
                return out
            }
        }
    }

    /// What is wrong with a notification; none when it is good. A port of strut's `notificationProblems`.
    fun notificationProblems(raw: Any?): List<String> {
        val n = raw as? JSONObject ?: return listOf("a notification must be an object")
        val type = WalletNotificationType.of(n.str("notification_type") ?: "") ?: return listOf("notification_type is not a notification type")
        val t = n.opt("notification") as? JSONObject ?: return listOf("notification is missing")
        val out = transactionProblems(t).toMutableList()
        if ((t.str("transaction_id") ?: "").isEmpty()) out += "transaction_id is missing"
        val expected = if (type == WalletNotificationType.PAYMENT_RECEIVED) "incoming" else "outgoing"
        if (t.opt("type") != expected) out += "${type.wire} carries an $expected transaction"
        if (type == WalletNotificationType.PAYMENT_FAILED && t.opt("state") != "failed") out += "payment_failed carries state failed"
        if (type != WalletNotificationType.PAYMENT_FAILED && t.has("state") && t.opt("state") != "settled") out += "${type.wire} carries a settled transaction"
        return out
    }

    /// One hop back to the core's route hint, `<lsp_pubkey>_<scid>`, the form it pays the asynchronous way; null when the scid is not a decimal u64 (strut's `sphinxRouteHint`).
    fun routeHint(pubkey: String, shortChannelId: String): String? =
        if (isDigits(shortChannelId) && shortChannelId.toULongOrNull() != null) "${pubkey}_$shortChannelId" else null
}

/// A Sphinx address, `<pubkey>_<lsp_pubkey>_<scid>`, as a pubkey and its one-hop route hint (the core's `parse_route_hint`: the hint is what follows the
/// first `_`) (iOS `SphinxAddress`).
class SphinxAddress private constructor(val pubkey: String, val lsp: String, val scid: String) {
    /// `[[{ pubkey, short_channel_id }]]`, BOLT 11's `r` with one hop.
    val routeHints: JSONArray
        get() = JSONArray().put(JSONArray().put(JSONObject().put("pubkey", lsp).put("short_channel_id", scid)))

    companion object {
        fun of(text: String): SphinxAddress? {
            val parts = WalletProtocol.trim(text).lowercase().split("_")
            if (parts.size != 3 || !WalletProtocol.isPubkey(parts[0]) || !WalletProtocol.isPubkey(parts[1]) ||
                parts[2].isEmpty() || !parts[2].all { it in '0'..'9' }
            ) return null
            return SphinxAddress(parts[0], parts[1], parts[2])
        }
    }
}

/// A refusal: the wallet's code and the person's words (iOS `WalletError`); [body] is the JSON-RPC error ([WalletErrors.body]).
class WalletRefusal(val code: WalletError, message: String) : Exception(message) {
    val body: JSONObject get() = WalletErrors.body(code, message ?: "")
}
