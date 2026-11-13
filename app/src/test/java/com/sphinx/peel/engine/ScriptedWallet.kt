package com.sphinx.peel.engine

import java.util.Collections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

internal val SCRIPT_KEY = "02" + "a".repeat(64)

/// A wallet that answers as a scenario scripts it (strut's specs/WALLET.md §7; iOS `ScriptedWallet`): what it is told to answer, what it was asked, the records
/// it keeps (tagged as the host tagged them, so lookups and budgets read them), and the wallet events a scenario feeds it.
internal class ScriptedWallet(private val signer: VisitorSigner? = null, override val signsPerDomain: Boolean = true) : AppWallet {
    private var answer: JSONObject? = null
    private var decodedMsat = 0L
    private var decodedHash = "f".repeat(64)
    private var decodedEnd: Long? = null
    private val records = Collections.synchronizedList(mutableListOf<WalletRecord>())
    private val log = Collections.synchronizedList(mutableListOf<String>())
    private var problem: WalletRefusal? = null
    private val stream = MutableSharedFlow<WalletRecord>(extraBufferCapacity = 64)

    val calls: List<String> get() = synchronized(log) { log.toList() }

    fun script(a: JSONObject?, decoded: Long? = null) { answer = a; if (decoded != null) decodedMsat = decoded }
    fun decodes(hash: String, ends: Long?) { decodedHash = hash; decodedEnd = ends }
    fun failNextProblem(e: WalletRefusal?) { problem = e }

    fun seed(spent: Long, connection: String, app: String, at: Long) {
        if (spent <= 0) return
        records += WalletRecord("seed", WalletRecord.Direction.OUTGOING, WalletRecord.PaymentType.KEYSEND, WalletRecord.State.SETTLED, "e".repeat(64), spent, preimage = "0".repeat(64), createdAt = at, settledAt = at, connection = connection, app = app)
    }

    /// A wallet event (a notification the wallet raises): the transaction it names moves, and the host is told by [updates].
    fun event(notification: JSONObject): WalletRecord? {
        val direction = if (notification.opt("notification_type") == "payment_received") WalletRecord.Direction.INCOMING else WalletRecord.Direction.OUTGOING
        val payload = notification.opt("notification")
        val id = (payload as? JSONObject)?.opt("transaction_id") as? String
        val known = records.indexOfFirst { it.transactionId == id }
        val owner = records.getOrNull(known)
        val moved = WalletRecord.fromPayment(payload, direction, owner?.paymentType ?: WalletRecord.PaymentType.KEYSEND, owner?.connection ?: "", owner?.app ?: "")?.copy(pubkey = owner?.pubkey) ?: return null
        if (known >= 0) records[known] = moved else records += moved
        stream.tryEmit(moved)
        return moved
    }

    private fun take(call: String): JSONObject {
        log += call
        val a = answer
        answer = null
        if (a == null) throw WalletRefusal(WalletError.INTERNAL, "the scripted wallet was asked when it must not be")
        (a.opt("error") as? String)?.let { code -> throw WalletRefusal(WalletError.valueOf(code), "scripted $code") }
        return a.optJSONObject("result") ?: JSONObject()
    }

    override suspend fun network() = "bitcoin"
    override suspend fun decode(invoice: String) = WalletInvoice(invoice, decodedHash, decodedMsat, SCRIPT_KEY, "", decodedEnd)
    override suspend fun problem(payment: WalletPayment) = problem

    override suspend fun pay(payment: WalletPayment, tag: WalletTag): WalletRecord {
        tag.budget?.let { b -> if (!b.admits(payment.amountMsat, records(tag.connection).mapNotNull { it.entry })) throw WalletRefusal(WalletError.QUOTA_EXCEEDED, "over budget") }
        val result = take("pay")
        val type = if (payment is WalletPayment.OfInvoice) WalletRecord.PaymentType.BOLT11 else WalletRecord.PaymentType.KEYSEND
        var record = WalletRecord.fromPayment(result, paymentType = type, connection = tag.connection, app = tag.app) ?: throw WalletRefusal(WalletError.INTERNAL, "the script's result is not a payment")
        if (payment is WalletPayment.OfKeysend) record = record.copy(pubkey = payment.pubkey)
        records += record
        return record
    }

    override suspend fun makeInvoice(amountMsat: Long, description: String, tag: WalletTag): WalletRecord {
        val result = take("make_invoice")
        val record = WalletRecord.fromPayment(result, WalletRecord.Direction.INCOMING, WalletRecord.PaymentType.BOLT11, tag.connection, tag.app) ?: throw WalletRefusal(WalletError.INTERNAL, "the script's result is not a transaction")
        records += record
        return record
    }

    override suspend fun records(connection: String) = synchronized(records) { records.filter { it.connection == connection }.reversed() }
    override suspend fun balance(): Long { take("get_balance"); return 1_000_000 }
    override suspend fun payeeName(pubkey: String) = if (pubkey == SCRIPT_KEY) "Ada" else null
    override suspend fun signVisitor(challenge: VisitorChallenge, key: SignInKey): VisitorSignature {
        log += "sign_in ${key.wire}"
        return signer?.sign(challenge, key) ?: VisitorSignature(if (key == SignInKey.root) SCRIPT_KEY else "03" + "b".repeat(64), "${key.wire}:${challenge.text}")
    }
    override fun updates(): Flow<WalletRecord> = stream.asSharedFlow()
}

/// The person, as a scenario scripts them: which sheets they were shown, and what they answered (iOS `ScriptedPerson`).
internal class ScriptedPerson(private val absent: Boolean = false) : WalletPerson {
    @Volatile var answer: String? = null
    @Volatile var signIn: SignInKey? = null
    private val shownLog = Collections.synchronizedList(mutableListOf<String>())
    @Volatile var lastConnect: WalletConnectSheet? = null
    @Volatile var lastPayment: WalletPaymentSheet? = null
    val notices = Collections.synchronizedList(mutableListOf<Pair<String, SignInKey>>())

    fun will(a: String?) { answer = a }
    fun takeShown(): List<String> = synchronized(shownLog) { shownLog.toList().also { shownLog.clear() } }

    override suspend fun connect(sheet: WalletConnectSheet): WalletConnectAnswer? {
        shownLog += "connect"; lastConnect = sheet
        if (absent) kotlinx.coroutines.awaitCancellation()
        if (answer != "approve") return null
        var yes = WalletConnectAnswer.asAsked(sheet)
        if (sheet.signIn != null) signIn?.let { yes = yes.copy(signIn = it) }
        return yes
    }

    override suspend fun pay(sheet: WalletPaymentSheet): Boolean {
        shownLog += "payment"; lastPayment = sheet
        if (absent) kotlinx.coroutines.awaitCancellation()
        return answer == "approve"
    }

    override fun signedIn(host: String, key: SignInKey) { notices += host to key }
}

/// What the host told the pages, in order (iOS `PushLog`).
internal class PushLog(host: WalletHost) {
    private val got = Collections.synchronizedList(mutableListOf<WalletPush>())
    private val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) { host.notifications.collect { got += it } }

    suspend fun take(expecting: Int = 0): List<WalletPush> {
        val deadline = System.nanoTime() + 5_000_000_000
        while (got.size < expecting && System.nanoTime() < deadline) kotlinx.coroutines.delay(10)
        // A moment for one that must not come.
        kotlinx.coroutines.delay(250)
        return synchronized(got) { got.toList().also { got.clear() } }
    }

    fun close() = job.cancel()
}

