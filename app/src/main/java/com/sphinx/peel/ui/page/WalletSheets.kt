package com.sphinx.peel.ui.page

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sphinx.peel.engine.ArmingGate
import com.sphinx.peel.engine.OverBudget
import com.sphinx.peel.engine.Sats
import com.sphinx.peel.engine.WalletConnectAnswer
import com.sphinx.peel.engine.WalletConnectForm
import com.sphinx.peel.engine.WalletConnectSheet
import com.sphinx.peel.engine.WalletCreditSheet
import com.sphinx.peel.engine.WalletPayStep
import com.sphinx.peel.engine.WalletPaymentSheet
import com.sphinx.peel.engine.WalletSheet
import com.sphinx.peel.engine.WalletSheetAnswer
import com.sphinx.peel.engine.WalletWords
import com.sphinx.peel.engine.SignInKey
import com.sphinx.peel.ui.payments.PaymentItem
import com.sphinx.peel.ui.theme.Theme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/// Whatever sheet the page's wallet host has up (iOS `PhoneWalletSheets` shown as a sheet): none dismissed by a tap outside; Back
/// is a no. Keyed by the sheet's id, so a new sheet starts with a fresh form and a fresh 700 ms.
@Composable
fun WalletSheets(sheets: PhoneWalletSheets) {
    val shown = sheets.shown ?: return
    androidx.compose.runtime.key(shown.id) {
        when (shown) {
            is WalletSheet.Connect -> WalletConnectPhoneSheet(shown.sheet) { sheets.answer(WalletSheetAnswer(connect = it)) }
            is WalletSheet.Payment -> WalletPaymentPhoneSheet(shown.sheet) { sheets.answer(WalletSheetAnswer(paid = it)) }
            is WalletSheet.Credit -> WalletCreditPhoneSheet(shown.sheet) { sheets.answer(WalletSheetAnswer(paid = it)) }
        }
    }
}

private val sheetProperties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false, usePlatformDefaultWidth = false)

/// A sheet's chrome: its no on the left, its title, its yes on the right (iOS's navigation bar), then the scrolling body.
@Composable
private fun SheetPage(title: String, no: String, onNo: () -> Unit, yes: (@Composable () -> Unit)?, body: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Theme.colors.background)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onNo) { Text(no) }
                Spacer(Modifier.weight(1f))
                yes?.invoke()
            }
            Text(
                title, Modifier.fillMaxWidth().padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, color = Theme.colors.text,
            )
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { body() }
        }
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Theme.colors.secondaryText)

/// The CONNECT sheet (iOS `WalletConnectPhoneSheet`): the org from Peel's own record, the app as the org's page names it, what for,
/// the spending limit (lowered here if wanted, never above what was asked), what to do over it. A sign-in alone has no wallet
/// sections. The yes is off for the first 700 ms: a tap meant for the page is never the yes.
@Composable
fun WalletConnectPhoneSheet(sheet: WalletConnectSheet, done: (WalletConnectAnswer?) -> Unit) {
    val form = remember(sheet) { WalletConnectForm(sheet) }
    val gate = remember(sheet) { ArmingGate({ SystemClock.elapsedRealtime() }) }
    var settled by remember(sheet) { mutableStateOf(false) }
    LaunchedEffect(sheet) {
        delay(ArmingGate.SETTLE_MS)
        settled = true
    }
    // Compose state mirrors of the form's plain fields.
    var limit by remember(sheet) { mutableStateOf(form.limitText) }
    var overBudget by remember(sheet) { mutableStateOf(form.overBudget) }
    var identity by remember(sheet) { mutableStateOf(form.identity) }
    var kept by remember(sheet) { mutableStateOf(sheet.optional.filter(form::keeps).toSet()) }
    form.limitText = limit
    form.overBudget = overBudget
    form.identity = identity
    val answers = WalletWords.connectAnswers(sheet)
    Dialog(onDismissRequest = { done(null) }, properties = sheetProperties) {
        SheetPage(
            title = WalletWords.connectTitle(sheet), no = answers.second, onNo = { done(null) },
            yes = {
                TextButton(
                    onClick = { gate.yes { done(form.answer()) } },
                    enabled = form.canConfirm(settled),
                ) { Text(answers.first, fontWeight = FontWeight.SemiBold) }
            },
        ) {
            Text(WalletWords.who(sheet.title, sheet.app, sheet.version, sheet.place, sheet.byOrg), color = Theme.colors.text)
            Text(WalletWords.sharedConnection(sheet), color = Theme.colors.secondaryText)
            sheet.signIn?.let { offer ->
                SectionTitle("Sign in")
                offer.keys.forEach { key ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = identity == key, onClick = { identity = key }, role = Role.RadioButton)
                            .heightIn(min = 44.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = identity == key, onClick = null, modifier = Modifier.padding(end = 12.dp))
                        Text(WalletWords.signIn(key), color = Theme.colors.text)
                    }
                }
                identity?.let { Text(WalletWords.signInNote(it), style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText) }
            }
            if (sheet.asksWallet) {
                SectionTitle("It may")
                WalletWords.methods(sheet.required).forEach { Text(it, color = Theme.colors.text) }
                sheet.optional.forEach { method ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(WalletWords.method(method), Modifier.weight(1f), color = Theme.colors.text)
                        Switch(checked = method in kept, onCheckedChange = { on ->
                            form.toggle(method, on)
                            kept = sheet.optional.filter(form::keeps).toSet()
                        })
                    }
                }
                if (sheet.notifications.isNotEmpty()) Text("Hear when its own payments land", color = Theme.colors.text)
                HorizontalDivider(color = Theme.colors.divider)
                SectionTitle("Spending limit")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = limit, onValueChange = { limit = it.filter(Char::isDigit) }, modifier = Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("None") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Text("sats", Modifier.padding(start = 8.dp), color = Theme.colors.secondaryText)
                }
                Text(form.limitSentence, color = Theme.colors.secondaryText)
                SectionTitle("Over the limit")
                OverBudget.entries.forEach { choice ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = overBudget == choice, onClick = { overBudget = choice }, role = Role.RadioButton)
                            .heightIn(min = 44.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = overBudget == choice, onClick = null, modifier = Modifier.padding(end = 12.dp))
                        Text(WalletWords.overLimitChoice(choice), color = Theme.colors.text)
                    }
                }
                Text(
                    "Inside it, apps pay without asking you. Change it or take it back in You › Connected apps.",
                    style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText,
                )
            }
        }
    }
}

/// The PAYMENT sheet, for one payment over the limit (iOS `WalletPaymentPhoneSheet`): the amount Peel decoded, the payee, the memo
/// or message, the fee, the app's own words marked as its, and the touch-safe two-step.
@Composable
fun WalletPaymentPhoneSheet(sheet: WalletPaymentSheet, done: (Boolean) -> Unit) {
    val step = remember(sheet) { WalletPayStep(sheet.amountMsat, { SystemClock.elapsedRealtime() }) }
    val state by step.attempt.state.collectAsState()
    val scope = rememberCoroutineScope()
    var ready by remember(sheet) { mutableStateOf(false) }
    // The armed Pay takes taps only once 700 ms have passed since it showed.
    LaunchedEffect(sheet, state.armedMsat) {
        ready = false
        if (state.armedMsat != null) {
            delay(ArmingGate.SETTLE_MS)
            ready = true
        }
    }
    fun tap() {
        scope.launch { if (step.tap()) done(true) }
    }
    val key = PaymentItem.shortKey(sheet.payee)
    Dialog(onDismissRequest = { done(false) }, properties = sheetProperties) {
        SheetPage(title = WalletWords.paymentTitle(sheet), no = "Decline", onNo = { done(false) }, yes = null) {
            Text(WalletWords.who(sheet.title, sheet.app, sheet.version, sheet.place, sheet.byOrg), color = Theme.colors.secondaryText)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Sats.number(sheet.amountMsat), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Theme.colors.text)
                Text(" sats", Modifier.padding(bottom = 6.dp), color = Theme.colors.secondaryText)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("To " + (sheet.payeeName?.let { "$it · $key" } ?: "$key, not a contact"), color = Theme.colors.text)
                sheet.memo?.let { Text((if (sheet.keysend) "Message: " else "Memo: ") + it, color = Theme.colors.text) }
                Text(if (sheet.async) "Fee added on top, with 1 sat to reach them" else "Fee added on top", color = Theme.colors.secondaryText)
                sheet.maxAmountMsat?.let {
                    Text("${Sats.format(sheet.usedMsat)} of its ${Sats.format(it)} limit spent", color = Theme.colors.secondaryText)
                }
                sheet.comment?.let { Text("The app says: \u201C$it\u201D", color = Theme.colors.secondaryText) }
            }
            val armed = state.armedMsat
            if (armed != null) {
                Text(step.armedNote, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { step.cancel() }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = !state.working) { Text("Cancel") }
                    Button(
                        onClick = { tap() }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = ready,
                        colors = ButtonDefaults.buttonColors(containerColor = Theme.colors.attention, contentColor = Theme.colors.buttonText),
                    ) {
                        if (state.working) CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                        Text("Pay ${Sats.format(armed)}")
                    }
                }
            } else {
                Button(onClick = { tap() }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    if (state.working) CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                    Text("Pay")
                }
            }
            state.problem?.let { Text(it, color = Theme.colors.destructive, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/// The CREDIT sheet (iOS `WalletCreditPhoneSheet`, specs/WALLET.md §1.2): credit bought on the server Peel loaded for a launch in its public app; the price as
/// Peel decoded it, the rest marked as the server's, and the touch-safe two-step (the first tap arms the amount; the second, 700 ms later, pays it).
/// It comes down by itself when the host cancels it (the invoice ended, or the page sent `buy_credit_cancel`).
@Composable
fun WalletCreditPhoneSheet(sheet: WalletCreditSheet, done: (Boolean) -> Unit) {
    val step = remember(sheet) { WalletPayStep(sheet.amountMsat, { SystemClock.elapsedRealtime() }, armedNote = WalletWords.CREDIT_ARMED_NOTE) }
    val state by step.attempt.state.collectAsState()
    val scope = rememberCoroutineScope()
    var ready by remember(sheet) { mutableStateOf(false) }
    LaunchedEffect(sheet, state.armedMsat) {
        ready = false
        if (state.armedMsat != null) {
            delay(ArmingGate.SETTLE_MS)
            ready = true
        }
    }
    fun tap() {
        scope.launch { if (step.tap()) done(true) }
    }
    Dialog(onDismissRequest = { done(false) }, properties = sheetProperties) {
        SheetPage(title = WalletWords.creditTitle(sheet), no = "Decline", onNo = { done(false) }, yes = null) {
            Text(WalletWords.creditApp(sheet), color = Theme.colors.secondaryText)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Sats.number(sheet.amountMsat), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Theme.colors.text)
                Text(" sats", Modifier.padding(bottom = 6.dp), color = Theme.colors.secondaryText)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Runs ${sheet.workflow}", color = Theme.colors.text)
                Text("Buys " + WalletWords.creditBuys(sheet), color = Theme.colors.text)
                Text("Fee added on top", color = Theme.colors.secondaryText)
                Text("The server says: \u201C${sheet.description}\u201D", color = Theme.colors.secondaryText)
                Text(WalletWords.creditNote(sheet), color = Theme.colors.secondaryText)
            }
            val armed = state.armedMsat
            if (armed != null) {
                Text(step.armedNote, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { step.cancel() }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = !state.working) { Text("Cancel") }
                    Button(
                        onClick = { tap() }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = ready,
                        colors = ButtonDefaults.buttonColors(containerColor = Theme.colors.attention, contentColor = Theme.colors.buttonText),
                    ) {
                        if (state.working) CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                        Text("Pay ${Sats.format(armed)}")
                    }
                }
            } else {
                Button(onClick = { tap() }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    if (state.working) CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                    Text("Pay")
                }
            }
            state.problem?.let { Text(it, color = Theme.colors.destructive, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
