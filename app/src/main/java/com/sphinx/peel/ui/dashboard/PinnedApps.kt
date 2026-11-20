package com.sphinx.peel.ui.dashboard

import androidx.compose.ui.res.stringResource
import com.sphinx.peel.R
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sphinx.peel.engine.WorkspaceApp
import com.sphinx.peel.ui.apps.WorkspaceAppIcon
import com.sphinx.peel.ui.theme.Theme

/// The Apps row, and the apps pinned under it (docs/phases/36-apps-cosmetics.md, "Pinned apps"; the Mac's `AppsFoldButton` and
/// `AppsUnderRow`, the iPhone's `PinnedAppRow`).

/// The Apps row with, when some listed app is pinned ([fold] non-null: its open state), a chevron at its end that shows or hides the
/// pinned apps under it.
@Composable
internal fun AppsRow(fold: Boolean?, onToggleFold: () -> Unit, onClick: () -> Unit) {
    val expandedLabel = stringResource(R.string.dash_expanded)
    val collapsedLabel = stringResource(R.string.dash_collapsed)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Apps, contentDescription = null, tint = Theme.colors.secondaryText, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.dash_apps), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = Theme.colors.secondaryText)
        if (fold != null) {
            IconButton(onClick = onToggleFold, Modifier.size(32.dp).semantics { stateDescription = if (fold) expandedLabel else collapsedLabel }) {
                Icon(
                    if (fold) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.dash_pinned_apps), tint = Theme.colors.secondaryText,
                )
            }
        }
    }
}

/// A pinned app under the Apps row (or an app found by a search): its icon and title, with its workspace when the apps come from
/// several. A tap opens it as Apps does; the long-press menu unpins it (or, for one found by a search that is not pinned, pins it).
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PinnedAppRow(app: WorkspaceApp, showsSource: Boolean, pinned: Boolean, onOpen: () -> Unit, onTogglePin: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { menu = true }).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkspaceAppIcon(app.icon, Theme.colors.secondaryText, Modifier.size(24.dp), 20.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                if (showsSource) stringResource(R.string.dash_app_with_source, app.title, app.source) else app.title,
                Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge, color = Theme.colors.secondaryText,
            )
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (pinned) stringResource(R.string.dash_unpin) else stringResource(R.string.dash_pin_to_chats)) },
                leadingIcon = { Icon(Icons.Filled.PushPin, null) },
                onClick = { menu = false; onTogglePin() },
            )
        }
    }
}
