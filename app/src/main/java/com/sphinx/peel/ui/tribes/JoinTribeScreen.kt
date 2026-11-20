package com.sphinx.peel.ui.tribes

import androidx.compose.ui.res.stringResource
import com.sphinx.peel.R
import com.sphinx.peel.ui.str
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sphinx.peel.engine.ChatDetails
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.JoinOffer
import com.sphinx.peel.engine.NotConnectedException
import com.sphinx.peel.engine.PaymentRefused
import com.sphinx.peel.engine.Sats
import com.sphinx.peel.engine.TribeRefused
import com.sphinx.peel.engine.joinTribe
import com.sphinx.peel.engine.lookupTribe
import com.sphinx.peel.ui.payments.CodeScanner
import com.sphinx.peel.ui.payments.PageFrame
import com.sphinx.peel.ui.theme.Theme
import com.sphinx.peel.ui.toast.LocalToast
import com.sphinx.peel.ui.toast.ToastType
import kotlinx.coroutines.launch

/// Join a tribe (the iPhone's join sheet): a `sphinx.chat` tribe link, `pubkey@host`, a bare public
/// key or a uuid, pasted or scanned. "Look up" reads the tribe's registry entry and shows what
/// joining costs; "Join" then pays it, through the usual balance and confirmation checks.
@Composable
fun JoinTribeScreen(engine: Engine, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var offer by remember { mutableStateOf<JoinOffer?>(null) }
    var confirmMsat by remember { mutableStateOf<Long?>(null) }
    val clipboard = LocalClipboardManager.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lookupFailedText = stringResource(R.string.ppl_lookup_failed)
    val requestSentText = stringResource(R.string.ppl_request_sent)
    val notConnectedText = stringResource(R.string.ppl_not_connected)
    val joinFailedText = stringResource(R.string.ppl_join_failed)

    if (scanning) {
        CodeScanner(
            prompt = stringResource(R.string.ppl_scan_tribe_prompt),
            accept = { scanned -> text = scanned.trim(); offer = null; scanning = false; null },
            onCancel = { scanning = false },
        )
        return
    }

    fun lookUp() {
        working = true
        problem = null
        scope.launch {
            try {
                offer = engine.lookupTribe(text)
            } catch (e: TribeRefused) {
                problem = e.message
            } catch (e: Exception) {
                problem = e.message ?: lookupFailedText
            } finally {
                working = false
            }
        }
    }

    fun join(confirmed: Long?) {
        val chosen = offer ?: return
        working = true
        problem = null
        scope.launch {
            try {
                val chat = engine.joinTribe(chosen, confirmed)
                toast.show(
                    if (chosen.info.isPrivate == true) requestSentText else context.str(R.string.ppl_joined, chosen.info.name),
                    ToastType.Success,
                )
                onOpenChat(chat)
            } catch (e: PaymentRefused) {
                val ask = e.confirmMsat
                if (ask != null) confirmMsat = ask else problem = e.message
            } catch (e: NotConnectedException) {
                problem = notConnectedText
            } catch (e: TribeRefused) {
                problem = e.message
            } catch (e: Exception) {
                problem = e.message ?: joinFailedText
            } finally {
                working = false
            }
        }
    }

    PageFrame(stringResource(R.string.ppl_join_tribe), onBack) {
        Text(
            stringResource(R.string.ppl_join_hint),
            style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it.take(2_000); offer = null }, label = { Text(stringResource(R.string.ppl_tribe_link_field)) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedButton(onClick = { clipboard.getText()?.text?.let { text = it.trim(); offer = null } }) { Text(stringResource(R.string.ppl_paste)) }
            OutlinedButton(onClick = { scanning = true }) { Text(stringResource(R.string.ppl_scan_qr)) }
        }
        offer?.let { found ->
            Spacer(Modifier.height(8.dp))
            Text("#${found.info.name}", style = MaterialTheme.typography.titleMedium, color = Theme.colors.text)
            found.info.description?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText, modifier = Modifier.padding(top = 4.dp))
            }
            val join = ChatDetails.joinPrice(found.info.priceToJoinMsat)
            Text(
                join.note?.let { stringResource(R.string.ppl_to_join_note, Sats.format(found.amountMsat), it) }
                    ?: stringResource(R.string.ppl_to_join, Sats.format(found.amountMsat)),
                style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText, modifier = Modifier.padding(top = 4.dp),
            )
            if (found.info.isPrivate == true) {
                Text(stringResource(R.string.ppl_owner_approves), style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText)
            }
            if (found.alreadyJoined) {
                Text(stringResource(R.string.ppl_already_in), style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText)
            }
        }
        problem?.let { Text(it, color = Theme.colors.destructive, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = text.isNotBlank() && !working,
            onClick = { if (offer == null) lookUp() else join(null) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Theme.colors.button, contentColor = Theme.colors.buttonText),
        ) {
            if (working) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
            else Text(stringResource(if (offer == null) R.string.ppl_look_up else if (offer?.alreadyJoined == true) R.string.ppl_open else R.string.ppl_join))
        }
    }

    confirmMsat?.let { amount ->
        AlertDialog(
            onDismissRequest = { confirmMsat = null },
            title = { Text(stringResource(R.string.ppl_join_this_title)) },
            text = { Text(stringResource(R.string.ppl_join_sends, Sats.format(amount))) },
            confirmButton = { TextButton(onClick = { confirmMsat = null; join(amount) }) { Text(stringResource(R.string.ppl_join), color = Theme.colors.accent) } },
            dismissButton = { TextButton(onClick = { confirmMsat = null }) { Text(stringResource(R.string.ppl_cancel)) } },
        )
    }
}
