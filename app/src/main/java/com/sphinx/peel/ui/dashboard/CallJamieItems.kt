package com.sphinx.peel.ui.dashboard

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.sphinx.peel.R
import com.sphinx.peel.engine.BoxCalls
import com.sphinx.peel.engine.Boxes
import com.sphinx.peel.ui.theme.Theme

/// The call menu's Jamie (docs/phases/56-box-group-calls.md; iOS `ConversationScreen.jamiePicker`): each linked box whose Jamie can
/// join a call on [callServer] now ([BoxCalls.offered]), No Jamie, and, with a box picked, Record on the canvas. Choosing keeps the
/// menu open; Audio or Video call then starts the call with it. Nothing at all while no box is offered.
@Composable
fun ColumnScope.CallJamieItems(chat: String, callServer: String, calls: BoxCalls = Boxes.calls) {
    val offered = calls.offered(callServer)
    if (offered.isEmpty()) return
    val picked = calls.pick(chat, callServer)
    HorizontalDivider()
    Text(
        stringResource(R.string.ppl_jamie),
        style = MaterialTheme.typography.labelMedium,
        color = Theme.colors.secondaryText,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    for (box in offered) {
        val on = picked?.url == box.url
        DropdownMenuItem(
            text = { Text(box.host) },
            leadingIcon = { PickMark(on) },
            onClick = { calls.choose(chat, box) },
            modifier = Modifier.semantics { selected = on; role = Role.RadioButton },
        )
    }
    DropdownMenuItem(
        text = { Text(stringResource(R.string.ppl_jamie_none)) },
        leadingIcon = { PickMark(picked == null) },
        onClick = { calls.choose(chat, null) },
        modifier = Modifier.semantics { selected = picked == null; role = Role.RadioButton },
    )
    if (picked != null) {
        val records = calls.records(chat)
        DropdownMenuItem(
            text = { Text(stringResource(R.string.conv_call_record_canvas)) },
            leadingIcon = {
                Icon(
                    if (records) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, contentDescription = null,
                    tint = if (records) Theme.colors.accent else Theme.colors.secondaryText,
                )
            },
            onClick = { calls.record(chat, !records) },
            modifier = Modifier.semantics { role = Role.Checkbox; toggleableState = ToggleableState(records) },
        )
    }
}

/// The picked one's check, else the same room so the names line up.
@Composable
private fun PickMark(on: Boolean) {
    if (on) Icon(Icons.Filled.Check, contentDescription = null, tint = Theme.colors.accent) else Spacer(Modifier.size(24.dp))
}
