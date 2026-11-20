package com.sphinx.peel.ui.diagnostics

import androidx.compose.foundation.background
import com.sphinx.peel.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.BackHandler
import com.sphinx.peel.diagnostics.Diagnostics
import com.sphinx.peel.diagnostics.DiagnosticsExport
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.theme.Theme
import com.sphinx.peel.ui.you.BackButtonRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// A row at the top of Chats when the last run ended badly: Peel recorded it on this phone, and the
/// record can be read, copied or deleted. Nothing is sent anywhere (iOS `DiagnosticsNotice`).
@Composable
fun DiagnosticsNotice(storage: Storage, modifier: Modifier = Modifier) {
    if (!DiagnosticsModel.notice) return
    val scope = rememberCoroutineScope()
    Column(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp))
            .background(Theme.colors.fieldFill).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = Theme.colors.attention, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.misc_diag_notice),
                style = MaterialTheme.typography.bodyMedium, color = Theme.colors.text,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                stringResource(R.string.misc_diag_show_record), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Theme.colors.accent,
                modifier = Modifier.clickable { scope.launch { DiagnosticsModel.showRecord(storage) } },
            )
            Text(
                stringResource(R.string.misc_diag_dismiss), style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText,
                modifier = Modifier.clickable { DiagnosticsModel.notice = false },
            )
        }
    }
    DiagnosticRecordSheet()
}

/// The redacted record in a scrolling monospaced view, with Copy and Delete. No send, share or export
/// from this sheet (iOS `DiagnosticRecordSheet`).
@Composable
fun DiagnosticRecordSheet() {
    val text = DiagnosticsModel.record ?: return
    val context = LocalContext.current
    var copied by remember(text) { mutableStateOf(false) }
    Dialog(onDismissRequest = DiagnosticsModel::closeRecord, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Theme.colors.background).padding(horizontal = 16.dp)) {
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.misc_diag_problem_record), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Theme.colors.text)
                Box(Modifier.align(Alignment.CenterEnd).size(44.dp).clip(CircleShape).clickable(onClick = DiagnosticsModel::closeRecord), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.misc_close), tint = Theme.colors.text)
                }
            }
            MonoText(text, Modifier.weight(1f))
            Caption(stringResource(R.string.misc_diag_redacted))
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryAction(if (copied) stringResource(R.string.misc_copied) else stringResource(R.string.misc_copy), Modifier.weight(1f)) {
                    DiagnosticsExport.copy(context, text)
                    copied = true
                }
                SecondaryAction(stringResource(R.string.misc_delete), Modifier.weight(1f)) { DiagnosticsModel.deleteRecord() }
            }
        }
    }
}

/// You › Diagnostics, always there: the event ring as text (how the last run ended, then the recent
/// events with their times). Copy and Share are the person's own act; nothing is sent automatically.
/// Clear empties the ring only, never a crash record (iOS `DiagnosticsScreen`).
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    var confirmingClear by remember { mutableStateOf(false) }

    suspend fun load() {
        copied = false
        text = withContext(Dispatchers.IO) { DiagnosticsExport.screenText() }
    }
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.misc_diag_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Theme.colors.text)
            Row(Modifier.fillMaxWidth()) { com.sphinx.peel.ui.you.BackButton(onBack) }
        }
        MonoText(text, Modifier.weight(1f))
        Caption(
            stringResource(R.string.misc_diag_caption),
        )
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryAction(stringResource(R.string.misc_refresh), Modifier.weight(1f)) { scope.launch { load() } }
            SecondaryAction(if (copied) stringResource(R.string.misc_copied) else stringResource(R.string.misc_copy), Modifier.weight(1f)) {
                DiagnosticsExport.copy(context, text)
                copied = true
            }
            SecondaryAction(stringResource(R.string.misc_share), Modifier.weight(1f)) { DiagnosticsExport.share(context, text) }
            SecondaryAction(stringResource(R.string.misc_clear), Modifier.weight(1f)) { confirmingClear = true }
        }
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text(stringResource(R.string.misc_diag_clear_title)) },
            text = { Text(stringResource(R.string.misc_diag_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingClear = false
                    Diagnostics.clearEvents()
                    scope.launch { load() }
                }) { Text(stringResource(R.string.misc_clear), color = Theme.colors.destructive) }
            },
            dismissButton = { TextButton(onClick = { confirmingClear = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/// 11 sp monospaced, scrolling both ways, selectable.
@Composable
private fun MonoText(text: String, modifier: Modifier) {
    SelectionContainer(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Theme.colors.text)
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText,
        modifier = Modifier.padding(vertical = 10.dp),
    )
}

@Composable
private fun SecondaryAction(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 48.dp).clip(CircleShape).background(Theme.colors.fieldFill).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Theme.colors.text, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp))
    }
}
