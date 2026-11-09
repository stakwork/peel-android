package com.sphinx.peel.ui.tribes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.NotConnectedException
import com.sphinx.peel.engine.TribeForm
import com.sphinx.peel.engine.TribeInfo
import com.sphinx.peel.engine.TribeRefused
import com.sphinx.peel.engine.createTribe
import com.sphinx.peel.engine.tribeInfo
import com.sphinx.peel.engine.updateTribe
import com.sphinx.peel.ui.components.Avatar
import com.sphinx.peel.ui.components.PersonHue
import com.sphinx.peel.ui.components.prepareImageForSending
import com.sphinx.peel.ui.payments.PageFrame
import com.sphinx.peel.ui.theme.Theme
import com.sphinx.peel.ui.toast.LocalToast
import com.sphinx.peel.ui.toast.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// Create a tribe (`chat` null) or edit one this account owns (`chat` is the tribe's key, the form
/// filled from its registry entry). Name, description, picture, price to join, price per message,
/// escrow (amount and hours held), tags, unlisted, app and feed URLs and "approval required".
/// [onDone] gets the tribe's key once it is saved.
@Composable
fun TribeFormScreen(engine: Engine, chat: String?, onBack: () -> Unit, onDone: (String) -> Unit) {
    if (chat == null) {
        TribeFormBody(engine, null, TribeForm(), onBack, onDone)
        return
    }
    // The registry's word for what the tribe is now: the form is not shown without it, so a save never
    // overwrites a field this device could not read.
    val loaded by produceState<Result<TribeInfo>?>(null, chat) {
        value = withContext(Dispatchers.IO) { runCatching { engine.tribeInfo(chat) } }
    }
    val result = loaded
    val info = result?.getOrNull()
    if (info != null) {
        TribeFormBody(engine, chat, TribeForm.from(info), onBack, onDone)
    } else {
        PageFrame("Edit tribe", onBack) {
            if (result == null) {
                CircularProgressIndicator(Modifier.padding(top = 24.dp))
            } else {
                Text(
                    "Could not read the tribe: ${result.exceptionOrNull()?.message ?: "try again"}",
                    color = Theme.colors.destructive, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun TribeFormBody(engine: Engine, chat: String?, initial: TribeForm, onBack: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var description by remember { mutableStateOf(initial.description) }
    var img by remember { mutableStateOf(initial.img) }
    var priceToJoin by remember { mutableStateOf(initial.priceToJoinSat.takeIf { it > 0 }?.toString() ?: "") }
    var pricePerMessage by remember { mutableStateOf(initial.pricePerMessageSat.takeIf { it > 0 }?.toString() ?: "") }
    var escrowSat by remember { mutableStateOf(initial.escrowSat.takeIf { it > 0 }?.toString() ?: "") }
    var escrowHours by remember { mutableStateOf(initial.escrowHours.takeIf { it > 0 }?.toString() ?: "") }
    var tags by remember { mutableStateOf(initial.tags.joinToString(", ")) }
    var unlisted by remember { mutableStateOf(initial.unlisted) }
    var appUrl by remember { mutableStateOf(initial.appUrl) }
    var feedUrl by remember { mutableStateOf(initial.feedUrl) }
    var approval by remember { mutableStateOf(initial.approvalRequired) }
    var uploading by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        uploading = true
        problem = null
        scope.launch {
            try {
                val jpeg = withContext(Dispatchers.IO) { prepareImageForSending(context, uri, maxDimension = 1024, quality = 50) }
                    ?: throw IllegalStateException("That is not a picture.")
                img = engine.uploadPicture(jpeg)
            } catch (e: Exception) {
                problem = "Could not upload the picture: ${e.message ?: "try again"}"
            } finally {
                uploading = false
            }
        }
    }

    fun form() = initial.copy(
        name = name,
        description = description,
        img = img,
        priceToJoinSat = priceToJoin.toLongOrNull() ?: 0,
        pricePerMessageSat = pricePerMessage.toLongOrNull() ?: 0,
        escrowSat = escrowSat.toLongOrNull() ?: 0,
        escrowHours = escrowHours.toLongOrNull() ?: 0,
        tags = TribeForm.tagsFrom(tags),
        unlisted = unlisted,
        appUrl = appUrl,
        feedUrl = feedUrl,
        approvalRequired = approval,
    )

    fun save() {
        val filled = form()
        problem = filled.problem()
        if (problem != null) return
        working = true
        scope.launch {
            try {
                val key = if (chat == null) engine.createTribe(filled) else chat.also { engine.updateTribe(it, filled) }
                toast.show(if (chat == null) "Tribe created." else "Saved.", ToastType.Success)
                onDone(key)
            } catch (e: NotConnectedException) {
                problem = "Not connected. Try again in a moment."
            } catch (e: TribeRefused) {
                problem = e.message
            } catch (e: Exception) {
                problem = e.message ?: "Could not save the tribe."
            } finally {
                working = false
            }
        }
    }

    PageFrame(if (chat == null) "Create a tribe" else "Edit tribe", onBack) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(name.ifBlank { "Tribe" }, img, 64.dp, PersonHue.of(chat ?: name))
            OutlinedButton(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !uploading,
            ) { Text(if (uploading) "Uploading…" else "Picture") }
            if (img != null) TextButton(onClick = { img = null }) { Text("Remove") }
        }
        Field("Name", name, { name = it.take(TribeForm.MAX_NAME) })
        Field("Description", description, { description = it.take(TribeForm.MAX_DESCRIPTION) }, singleLine = false)
        Field("Tags (comma separated)", tags, { tags = it.take(300) })
        Field("Price to join (sats)", priceToJoin, { priceToJoin = it.digits() }, number = true)
        Field("Price per message (sats)", pricePerMessage, { pricePerMessage = it.digits() }, number = true)
        Field("Escrow amount (sats)", escrowSat, { escrowSat = it.digits() }, number = true)
        Field("Escrow time (hours held)", escrowHours, { escrowHours = it.digits() }, number = true)
        Field("App URL", appUrl, { appUrl = it.trim().take(500) })
        Field("Feed URL", feedUrl, { feedUrl = it.trim().take(500) })
        SwitchRow("Unlisted", "Left out of the tribes directory; people need the link.", unlisted) { unlisted = it }
        SwitchRow("Approval required", "Private: you approve each member who asks to join.", approval) { approval = it }
        problem?.let { Text(it, color = Theme.colors.destructive, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = !working && !uploading,
            onClick = ::save,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Theme.colors.button, contentColor = Theme.colors.buttonText),
        ) {
            if (working) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp) else Text(if (chat == null) "Create" else "Save")
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun String.digits(): String = filter { it in '0'..'9' }.take(12)

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, singleLine: Boolean = true, number: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    )
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
