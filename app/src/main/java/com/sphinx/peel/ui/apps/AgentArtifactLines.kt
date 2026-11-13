package com.sphinx.peel.ui.apps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sphinx.peel.engine.AgentArtifact
import com.sphinx.peel.engine.AgentHandle
import com.sphinx.peel.engine.Opening
import com.sphinx.peel.engine.Stadeum
import com.sphinx.peel.engine.UrlParts
import com.sphinx.peel.ui.theme.Theme

/// What an agent's reply points to, one quiet line each (iOS `AgentArtifactLines`): its label and title, and where it opens. An org's file
/// or app opens on that org's own strut in the page host (`Stadeum.openings`, `OrgPageScreen`), else its https link in the browser; with
/// neither, the line says why (one on the Mac's own strut, which only the Mac reaches). In a tribe ([inTribe], Jamie's deliverables,
/// docs/hive-tribes.md) the same, but anyone could have sent it: an app asks first.
@Composable
fun AgentArtifactLines(artifacts: List<AgentArtifact>, modifier: Modifier = Modifier, inTribe: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // By position: a reply may name one thing twice.
        artifacts.forEachIndexed { index, artifact -> AgentArtifactLine(artifact, inTribe, key = index) }
    }
}

/// How one artifact line reads and what a tap on it does (iOS `AgentArtifactLine`'s `open` and `whereTo`), as plain rules.
object ArtifactOpening {
    /// Where it is: the workspace, the link's host, or why it does not open here.
    fun whereTo(artifact: AgentArtifact, opening: (AgentHandle) -> Opening): String? {
        val handle = artifact.handle ?: return artifact.url?.let(::host)
        return when (val opened = opening(handle)) {
            is Opening.Page -> opened.request.workspace
            else -> when {
                artifact.url != null -> host(artifact.url)
                opened == Opening.ConnectFirst -> "Connect Stadeum in You to open"
                opened is Opening.NotPaired -> "@${opened.peer} isn't connected here"
                else -> "On your Mac"
            }
        }
    }

    /// What a tap does: open its page when it opens on an org's strut, else its https link, else nothing.
    fun action(artifact: AgentArtifact, opening: (AgentHandle, String) -> Opening): Action? {
        artifact.handle?.let { handle -> if (opening(handle, artifact.title) is Opening.Page) return Action.Page(handle, artifact.title) }
        return artifact.url?.let { Action.Link(it) }
    }

    sealed interface Action {
        data class Page(val handle: AgentHandle, val title: String) : Action
        data class Link(val url: String) : Action
    }

    private fun host(url: String) = UrlParts.parse(url)?.host
}

@Composable
private fun AgentArtifactLine(artifact: AgentArtifact, inTribe: Boolean, key: Int) {
    val openings = Stadeum.openings
    val openPage = LocalPageOpener.current
    val linkOpener = LocalLinkOpener.current
    val context = LocalContext.current
    var asking by remember(artifact, key) { mutableStateOf(false) }
    val place = ArtifactOpening.whereTo(artifact) { openings.opening(it) }
    val action = ArtifactOpening.action(artifact) { handle, title -> openings.opening(handle, title) }
    // Made again on the tap: the tokens may have been renewed since.
    val run: (() -> Unit)? = when (action) {
        is ArtifactOpening.Action.Page -> ({
            (Stadeum.openings.opening(action.handle, action.title) as? Opening.Page)?.let { openPage(it.request) }
        })
        is ArtifactOpening.Action.Link -> ({ linkOpener?.open(action.url) ?: openInBrowser(context, action.url) })
        null -> null
    }
    val asksFirst = artifact.asksBeforeOpening(inTribe) && action is ArtifactOpening.Action.Page
    Row(
        Modifier
            .then(if (run != null) Modifier.clickable(onClick = if (asksFirst) ({ asking = true }) else run).semantics { role = Role.Button } else Modifier)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.Description, null, Modifier.size(12.dp), tint = Theme.colors.secondaryText)
        Text(
            artifact.label?.let { "$it: ${artifact.title}" } ?: artifact.title,
            style = MaterialTheme.typography.bodySmall, color = Theme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        place?.let { Text("· $it", style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (run != null) Text("Open", style = MaterialTheme.typography.bodySmall, color = Theme.colors.link, maxLines = 1)
    }
    if (asking && run != null) {
        AlertDialog(
            onDismissRequest = { asking = false },
            text = { Text(artifact.tribeAppQuestion(place ?: "")) },
            confirmButton = { TextButton(onClick = { asking = false; run() }) { Text("Open app") } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }
}
