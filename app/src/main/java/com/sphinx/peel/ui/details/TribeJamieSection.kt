package com.sphinx.peel.ui.details

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sphinx.peel.R
import com.sphinx.peel.engine.BoxJamies
import com.sphinx.peel.engine.Boxes
import com.sphinx.peel.engine.JamieApprover
import com.sphinx.peel.engine.TribeJamie
import com.sphinx.peel.engine.TribePlace
import com.sphinx.peel.ui.theme.Theme
import kotlinx.coroutines.launch

/// "Jamie", for the tribe's owner (iOS `TribeJamie`, the Mac's `TribeJamieSection`; docs/phases/54-jamie-from-a-box.md): which of
/// the boxes Peel is linked to answers `@jamie` in [tribe], or No Jamie. A pick links or unlinks at once ([BoxJamies.pick]), and a
/// refusal is said in place. Nothing shows while Peel is linked to no box whose Sphinx host is ready. The boxes are read again each
/// time the details open (another device may have changed them); a join request from the picked box's Jamie that waits unanswered
/// (it came while Peel was locked: [waiting]) is approved then, as one that arrives is ([BoxJamies.requested]).
@Composable
internal fun TribeJamieSection(
    tribe: TribePlace,
    tribeName: String,
    approver: JamieApprover,
    waiting: suspend () -> Boolean,
    jamies: BoxJamies = Boxes.jamies,
) {
    val links = jamies.links
    if (links.isEmpty()) return
    val scope = rememberCoroutineScope()
    LaunchedEffect(tribe.key, links) {
        jamies.read()
        if (TribeJamie.jamies(jamies.boxes(), tribe.key).isNotEmpty() && waiting()) jamies.requested(tribe.key, approver)
    }
    val boxes = jamies.boxes()
    if (!TribeJamie.shows(boxes, tribe.key)) return
    val noJamie = stringResource(R.string.ppl_jamie_none)
    val options = TribeJamie.options(boxes, tribe.key, noJamie)
    val pick = TribeJamie.pick(boxes, tribe.key)
    val working = tribe.key in jamies.working
    // A box that could not be read is not offered: why, under the row.
    val unread = boxes.mapNotNull { it.problem }.distinct()

    Spacer(Modifier.height(24.dp))
    SectionLabel(stringResource(R.string.ppl_jamie))
    if (pick != null && options.isNotEmpty()) {
        AnswersFrom(options, pick, working) { id -> jamies.choose(id, tribe) }
        val problem = jamies.problems[tribe.key]
        val helper = if (pick == TribeJamie.NONE) {
            stringResource(R.string.ppl_jamie_pick_helper, tribeName)
        } else {
            stringResource(R.string.ppl_jamie_answers_helper, tribeName, options.firstOrNull { it.id == pick }?.name ?: pick)
        }
        Note(problem ?: helper, isProblem = problem != null)
        unread.forEach { Note(it, isProblem = true) }
    } else if (unread.isNotEmpty() && !jamies.reading) {
        unread.forEach { Note(it, isProblem = true) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { scope.launch { jamies.read() } }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.ppl_jamie_try_again))
        }
    } else {
        Note(stringResource(R.string.ppl_jamie_asking), isProblem = false)
    }
}

/// "Answers from" and the box picked, a menu of the [options] under it (iOS: a menu picker in a card).
@Composable
private fun AnswersFrom(options: List<TribeJamie.Option>, pick: String, working: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val picked = options.firstOrNull { it.id == pick }?.name ?: ""
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .border(1.dp, Theme.colors.inputBorder, RoundedCornerShape(12.dp))
                .clickable(enabled = !working, role = Role.DropdownList) { open = true }
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.ppl_jamie_answers_from),
                style = MaterialTheme.typography.bodyMedium,
                color = Theme.colors.text,
                modifier = Modifier.weight(1f),
            )
            if (working) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                picked,
                style = MaterialTheme.typography.bodyMedium,
                color = if (working) Theme.colors.secondaryText else Theme.colors.text,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.widthIn(max = 200.dp),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = Theme.colors.secondaryText)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name) },
                    trailingIcon = if (option.id == pick) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
                    onClick = {
                        open = false
                        onPick(option.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun Note(text: String, isProblem: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isProblem) Theme.colors.destructive else Theme.colors.secondaryText,
        )
    }
}

