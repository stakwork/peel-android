package com.sphinx.peel.ui.dashboard

import androidx.compose.ui.res.stringResource
import com.sphinx.peel.R
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sphinx.peel.engine.OpenedApp
import com.sphinx.peel.engine.PublicAppLink
import com.sphinx.peel.engine.TasksTab
import com.sphinx.peel.ui.theme.Theme

// The Tasks tab (docs/phases/48-tasks-tab.md): its tab state, the list of employers, and the page kept alive behind the other tabs.
// The rules (which rows, one page or the list) are `engine/TasksTab.kt`'s.

/// The bar's tabs. Tasks only while the account has opened a Tasks page; then it comes first and Peel opens on it.
internal enum class MainTab {
    TASKS, CHATS, PAYMENTS, YOU, SEARCH;

    companion object {
        /// The tabs in the bar's pill, in order (Search is the circle beside it).
        fun pill(hasTasks: Boolean): List<MainTab> = listOfNotNull(TASKS.takeIf { hasTasks }, CHATS, PAYMENTS, YOU)

        /// The tab Peel opens on.
        fun start(hasTasks: Boolean): MainTab = if (hasTasks) TASKS else CHATS

        /// The tab in front: Tasks gone (its last page forgotten) is Chats.
        fun shown(tab: MainTab, hasTasks: Boolean): MainTab = if (tab == TASKS && !hasTasks) CHATS else tab
    }
}

/// Which tab is in front and which Tasks page is chosen, held by `MainTabs`.
@Stable
internal class TabState(hasTasks: Boolean) {
    var tab by mutableStateOf(MainTab.start(hasTasks))

    /// The Tasks page picked from the list or opened by a link; null: the one employer's page, or the list
    /// (`TasksTab.content`).
    var chosen by mutableStateOf<PublicAppLink?>(null)
        private set

    /// A Tasks link (a worker's invite after signup, a `peel://open` link, `PublicPageRouter.tasksPage`): the Tasks tab, with
    /// that page.
    fun openTasks(link: PublicAppLink) {
        chosen = link
        tab = MainTab.TASKS
    }

    /// A row of the list tapped.
    fun pick(link: PublicAppLink) {
        chosen = link
    }

    /// Back from a page picked off the list: the list again.
    fun backToList() {
        chosen = null
    }
}

/// [content] kept composed (its page alive) while it is out of sight, and placed over the whole of its parent only while [shown]. A
/// child Compose does not place is neither drawn nor given touches, and an `AndroidView` in it stays attached to the window, so its
/// web view is never torn down, detached or resized by a tab switch. It is measured at the parent's size either way.
@Composable
internal fun KeptPage(shown: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier.fillMaxSize()) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (shown) placeables.forEach { it.place(0, 0) }
        }
    }
}

/// Several employers: a row each (the connection's title, else the host; the host under it), last opened first. A tap opens that
/// employer's page in the tab; long-press takes one off (its sign-in stays in You › Connected apps).
@Composable
internal fun TasksList(pages: List<OpenedApp>, onOpen: (PublicAppLink) -> Unit, onRemove: (PublicAppLink) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.dash_tasks), Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Theme.colors.text,
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp)) {
            items(pages, key = { "${it.link.seller} ${it.link.app ?: ""}" }) { app ->
                TasksRow(app, onOpen = { onOpen(app.link) }, onRemove = { onRemove(app.link) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TasksRow(app: OpenedApp, onOpen: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { menu = true }).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.TaskAlt, null, Modifier.size(26.dp), tint = Theme.colors.secondaryText)
            Column(Modifier.weight(1f)) {
                Text(app.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text)
                Text(TasksTab.caption(app), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText)
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.dash_remove_from_tasks)) }, onClick = { menu = false; onRemove() })
        }
    }
}
