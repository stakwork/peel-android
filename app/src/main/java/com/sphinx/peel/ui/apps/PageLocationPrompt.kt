package com.sphinx.peel.ui.apps

import androidx.compose.foundation.layout.Arrangement
import com.sphinx.peel.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sphinx.peel.engine.PageLocation
import com.sphinx.peel.ui.theme.Theme

/// Peel's own question before a page gets the person's location (engine/PageLocation.kt). Precise is off unless the person turns it on;
/// Back or a tap outside is a no that is not remembered.
@Composable
internal fun LocationPromptSheet(ask: OrgPageController.LocationAsk) {
    var precise by remember(ask) { mutableStateOf(false) }
    Dialog(onDismissRequest = { ask.answer(null) }, properties = DialogProperties(dismissOnClickOutside = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = Theme.colors.background) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(PageLocation.title(ask.host), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Theme.colors.text)
                Text(PageLocation.PROMPT_BODY, color = Theme.colors.secondaryText)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(PageLocation.PRECISE_LABEL, Modifier.weight(1f), color = Theme.colors.text)
                    Switch(checked = precise, onCheckedChange = { precise = it })
                }
                Text(PageLocation.PRECISE_NOTE, style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText)
                Button(onClick = { ask.answer(OrgPageController.LocationAnswer(allow = true, once = false, precise = precise)) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.misc_apps_allow)) }
                OutlinedButton(onClick = { ask.answer(OrgPageController.LocationAnswer(allow = true, once = true, precise = precise)) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.misc_apps_allow_once)) }
                TextButton(onClick = { ask.answer(OrgPageController.LocationAnswer(allow = false, once = false, precise = false)) }, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.misc_apps_dont_allow), color = Theme.colors.destructive)
                }
            }
        }
    }
}
