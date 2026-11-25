package com.sphinx.peel.ui.apps

import android.content.ClipData
import com.sphinx.peel.ui.str
import com.sphinx.peel.R
import androidx.compose.ui.res.stringResource
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sphinx.peel.engine.AppsOpenDecision
import com.sphinx.peel.engine.HivePeer
import com.sphinx.peel.engine.OpenedApp
import com.sphinx.peel.engine.PublicAppLink
import com.sphinx.peel.engine.Stadeum
import com.sphinx.peel.engine.TalkHome
import com.sphinx.peel.engine.AppsListing
import com.sphinx.peel.engine.WorkspaceApp
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.dashboard.SidebarLayoutState
import com.sphinx.peel.ui.theme.Theme
import com.sphinx.peel.ui.toast.LocalToast
import com.sphinx.peel.ui.toast.ToastType

/// Apps, from the Dashboard's top rows — `AppsScreen.swift`: Talk to Jamie on
/// each workspace, then the apps the Stadeum workspaces pinned as tabs, A–Z,
/// each read and opened on its workspace's own strut with the person's token.
/// Search, a source filter (with several workspaces), Refresh, long-press to
/// copy a share link, pull to refresh, lucide icons (`LucideNames`/`AppIcons`), Talk rows' long-press menu
/// (New conversation, History). Not ported: the Mac-only own-strut apps.
/// `live` comes from the Stadeum pairing (You › Hive) and is observable, so
/// this screen follows a connect or disconnect without a restart.
/// Android only: this is the Apps tab (docs/phases/48-tasks-tab.md). Its one list is the workspace apps, then every public app opened
/// here (`Storage.openedApps`, an employer's Tasks page too, last opened first; `AppsListing`), so a worker opens a Stadeum's worker
/// app again with one tap (docs/phases/41-worker-onboarding.md); long-press removes one, and Disconnect in You › Connected apps
/// removes an origin's. An opened app's source is its host. [onBack] null: in the tab, with no Back.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(storage: Storage, onBack: (() -> Unit)?, onTalk: (TalkHome) -> Unit, onOpenPublic: (PublicAppLink) -> Unit, modifier: Modifier = Modifier) {
    val live = Stadeum.live
    val toast = LocalToast.current
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var source by remember { mutableStateOf<String?>(null) }
    // The read is Stadeum's, shared with the Dashboard's pinned apps.
    val read = Stadeum.apps
    val reading = Stadeum.readingApps
    val unreadIds = Stadeum.unreadAppIds
    val layoutState = remember(storage) { SidebarLayoutState.of(storage) }
    val layout = layoutState.layout
    val openPage = LocalPageOpener.current
    val publicVersion by storage.publicAppsVersion.collectAsState()
    // The connection titles move too (a first sign-in names the app).
    val connectionsVersion by storage.walletConnectionsVersion.collectAsState()
    val opened = remember(publicVersion, connectionsVersion) { storage.openedApps() }
    var refresh by remember { mutableStateOf(0) }
    var historyHome by remember { mutableStateOf<TalkHome?>(null) }
    val context2 = LocalContext.current
    val history = remember { com.sphinx.peel.engine.TalkHistoryStore(java.io.File(context2.filesDir, "talk_history")) }

    // Read again on opening, and when the workspaces (or Refresh) change.
    LaunchedEffect(live.map { it.id }, refresh) { Stadeum.readApps() }

    if (onBack != null) BackHandler(onBack = onBack)
    // Conversations: a screen over the list (iOS pushes `TalkHistoryScreen`); Back returns to the list.
    historyHome?.let { home ->
        com.sphinx.peel.ui.talk.TalkHistoryScreen(home, history, onBack = { historyHome = null }, onTalk = onTalk, modifier = modifier)
        return
    }

    val liveIds = live.map { it.id }.toSet()
    val apps = AppsOpenDecision.visible(read, liveIds)
    val unread = AppsOpenDecision.unreadNames(unreadIds, live)
    val several = apps.mapNotNull { it.peer }.toSet().size > 1
    val sources = AppsListing.sources(apps, opened)
    val shownSource = if (sources.size > 1 && source in sources) source else null
    val rows = AppsListing.merged(apps, opened, query, shownSource)
    val shown = rows.mapNotNull { (it as? AppsListing.Row.Workspace)?.app }
    val openedShown = rows.mapNotNull { (it as? AppsListing.Row.Opened)?.app }
    // Not while searching: the search is of the apps.
    val homes = if (query.isEmpty()) Stadeum.talkHomes else emptyList()

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Theme.colors.fieldFill), contentAlignment = Alignment.Center) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.misc_back), tint = Theme.colors.text) }
                }
                Spacer(Modifier.width(12.dp))
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(R.string.misc_apps_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Theme.colors.text)
            if (sources.size > 1) {
                var open by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { open = true }) { Icon(Icons.Filled.FilterList, stringResource(R.string.misc_apps_filter), tint = if (shownSource == null) Theme.colors.secondaryText else Theme.colors.accent) }
                    DropdownMenu(open, { open = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.misc_apps_all)) }, onClick = { source = null; open = false })
                        sources.forEach { s -> DropdownMenuItem(text = { Text(s) }, onClick = { source = s; open = false }) }
                    }
                }
            }
            // Off while a read is under way: another would cut it short. None with no workspace to read (a worker's Apps holds
            // only the public apps opened here, which nothing reads).
            if (live.isNotEmpty()) {
                IconButton(onClick = { refresh++ }, enabled = !reading) {
                    Icon(Icons.Filled.Refresh, stringResource(R.string.misc_apps_refresh), tint = Theme.colors.secondaryText)
                }
            }
        }
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
            placeholder = { Text(stringResource(R.string.misc_apps_search)) },
        )
        // Pulling reads again, as Refresh does, and waits for the read (`reading`).
        PullToRefreshBox(
            isRefreshing = reading,
            onRefresh = { if (!reading) refresh++ },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                if (homes.isNotEmpty()) {
                    item { Text(stringResource(R.string.misc_apps_talk_header), Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleSmall, color = Theme.colors.secondaryText) }
                    items(homes, key = { "talk-${it.id}" }) { home ->
                        TalkRow(home, onClick = { onTalk(home) }, onHistory = { historyHome = home })
                    }
                    item { Spacer(Modifier.size(12.dp)) }
                }
                items(shown, key = { it.id }) { app ->
                    AppRow(app, several, pinned = layout.isPinned(app.id), onTogglePin = {
                        layoutState.change(layout.withPinned(app.id, !layout.isPinned(app.id)))
                    }, onOpen = {
                        // iOS opens a page when there is one and otherwise nothing, with no word said.
                        AppsOpenDecision.page(Stadeum.openings.opening(app))?.let(openPage)
                    }, onShare = { link ->
                        copy(context, link)
                        toast.show(context.str(R.string.misc_apps_link_copied), ToastType.Success)
                    })
                }
                items(openedShown, key = { "opened-${it.link.seller} ${it.link.app ?: ""}" }) { app ->
                    OpenedAppRow(app, onOpen = { onOpenPublic(app.link) }, onRemove = { storage.forgetPublicApp(app.link) })
                }
                if (unread.isNotEmpty() && apps.isNotEmpty() && !reading) {
                    item { Note(AppsOpenDecision.unreadNote(unread)) }
                } else if (apps.isEmpty() && homes.isNotEmpty() && !reading) {
                    item { Note(AppsOpenDecision.emptyText(live.isEmpty(), unread)) }
                }
            }
            when {
                openedShown.isNotEmpty() -> {}
                apps.isNotEmpty() && shown.isEmpty() -> Centered(stringResource(R.string.misc_apps_no_results), stringResource(R.string.misc_apps_nothing_matches, query.trim()))
                opened.isNotEmpty() && shown.isEmpty() && query.isNotBlank() -> Centered(stringResource(R.string.misc_apps_no_results), stringResource(R.string.misc_apps_nothing_matches, query.trim()))
                apps.isEmpty() && homes.isNotEmpty() -> if (reading) Spinner()
                apps.isEmpty() && live.isEmpty() -> Centered(stringResource(R.string.misc_apps_no_stadeum), AppsOpenDecision.CONNECT_STADEUM)
                apps.isEmpty() && reading -> Spinner()
                apps.isEmpty() && unread.isNotEmpty() -> Centered(stringResource(R.string.misc_apps_unreadable), AppsOpenDecision.didNotAnswer(unread))
                apps.isEmpty() -> Centered(stringResource(R.string.misc_apps_none_yet), AppsOpenDecision.NO_APPS)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppRow(
    app: WorkspaceApp, showsSource: Boolean, onOpen: () -> Unit, onShare: (String) -> Unit,
    /// Pinned to the Dashboard's Apps row: a small accent pin at the end.
    pinned: Boolean = false, onTogglePin: () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    val link = app.shareLink()
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { menu = true }).padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WorkspaceAppIcon(app.icon, Theme.colors.secondaryText, Modifier.size(26.dp), 20.dp)
            Column(Modifier.weight(1f)) {
                Text(app.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text)
                app.about?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText) }
                app.caption(showsSource)?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText) }
            }
            if (pinned) Icon(Icons.Filled.PushPin, stringResource(R.string.misc_apps_pinned), Modifier.size(14.dp).align(Alignment.CenterVertically), tint = Theme.colors.accent)
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (pinned) stringResource(R.string.misc_apps_unpin) else stringResource(R.string.misc_apps_pin)) },
                leadingIcon = { Icon(Icons.Filled.PushPin, null) },
                onClick = { menu = false; onTogglePin() },
            )
            // Copies a link to the app: nothing is sent, and it opens only for
            // someone with that workspace connected.
            if (link != null) DropdownMenuItem(text = { Text(stringResource(R.string.misc_apps_share_link)) }, onClick = { menu = false; onShare(link) })
        }
    }
}

/// A stranger's public app opened here: a tap opens it again (`PublicPageRouter`), long-press offers to take it off the list.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OpenedAppRow(app: OpenedApp, onOpen: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { menu = true }).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Public, null, Modifier.size(26.dp), tint = Theme.colors.secondaryText)
            Column(Modifier.weight(1f)) {
                Text(app.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text)
                Text(app.caption, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText)
            }
        }
        DropdownMenu(menu, { menu = false }) {
            // Only off this list: a sign-in it holds stays in You › Connected apps.
            DropdownMenuItem(text = { Text(stringResource(R.string.misc_apps_remove)) }, onClick = { menu = false; onRemove() })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TalkRow(home: TalkHome, onClick: () -> Unit, onHistory: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { menu = true }).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.GraphicEq, null, Modifier.size(26.dp), tint = Theme.colors.secondaryText)
        Column(Modifier.weight(1f)) {
            Text(home.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text)
            Text(if (home.id == TalkHome.DEVELOPMENT) home.url else "@${home.id}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText)
        }
        Icon(Icons.Filled.Mic, stringResource(R.string.misc_apps_talk_on, home.name), tint = Theme.colors.accent)
    }
    DropdownMenu(menu, { menu = false }) {
        DropdownMenuItem(text = { Text(stringResource(R.string.misc_apps_new_conversation)) }, leadingIcon = { Icon(Icons.Filled.Mic, null) }, onClick = { menu = false; onClick() })
        DropdownMenuItem(text = { Text(stringResource(R.string.misc_apps_history)) }, leadingIcon = { Icon(Icons.Filled.History, null) }, onClick = { menu = false; onHistory() })
    }
    }
}

@Composable
private fun Spinner() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

@Composable
private fun Note(text: String) = Text(text, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText)

@Composable
private fun Centered(title: String, body: String) = Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Theme.colors.text)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText)
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("App link", text))
}
