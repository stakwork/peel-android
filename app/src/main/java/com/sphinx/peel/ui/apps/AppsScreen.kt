package com.sphinx.peel.ui.apps

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.viewinterop.AndroidView
import com.sphinx.peel.engine.AppPage
import com.sphinx.peel.engine.HivePeer
import com.sphinx.peel.engine.Stadeum
import com.sphinx.peel.engine.TalkHome
import com.sphinx.peel.engine.WorkspaceApp
import com.sphinx.peel.ui.theme.Theme
import com.sphinx.peel.ui.toast.LocalToast
import com.sphinx.peel.ui.toast.ToastType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/// Apps, from the Dashboard's top rows — `AppsScreen.swift`: Talk to Jamie on
/// each workspace, then the apps the Stadeum workspaces pinned as tabs, A–Z,
/// each read and opened on its workspace's own strut with the person's token.
/// Search, a source filter (with several workspaces), Refresh, long-press to
/// copy a share link. Not ported: pull-to-refresh (Refresh does it), the
/// Mac-only own-strut apps, the lucide icon table (a generic glyph instead).
/// There is no Stadeum pairing on Android yet (`Stadeum`), so today `live`
/// is empty and the row that opens this screen is hidden.
@Composable
fun AppsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val live = Stadeum.live
    val toast = LocalToast.current
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var source by remember { mutableStateOf<String?>(null) }
    var read by remember { mutableStateOf<List<WorkspaceApp>>(emptyList()) }
    var reading by remember { mutableStateOf(false) }
    var unread by remember { mutableStateOf<List<String>>(emptyList()) }
    var page by remember { mutableStateOf<AppPage?>(null) }
    var refresh by remember { mutableStateOf(0) }

    // Read again on opening, and when the workspaces (or Refresh) change.
    LaunchedEffect(live.map { it.id }, refresh) {
        if (live.isEmpty()) { read = emptyList(); return@LaunchedEffect }
        reading = true
        try {
            val results = coroutineScope {
                live.map { peer -> async { peer to runCatching { WorkspaceApp.pinned(peer) } } }.awaitAll()
            }
            // A workspace that did not answer keeps the apps it last listed.
            val kept = read.filter { app -> results.any { (p, r) -> p.id == app.peer && r.isFailure } }
            read = kept + results.flatMap { it.second.getOrNull().orEmpty() }
            unread = results.filter { it.second.isFailure }.map { it.first.name }
        } finally {
            reading = false
        }
    }

    val shownPage = page
    if (shownPage != null) {
        AppPageScreen(shownPage, onBack = { page = null }, modifier)
        return
    }
    BackHandler(onBack = onBack)

    val liveIds = live.map { it.id }.toSet()
    val apps = read.filter { it.peer in liveIds }
    val several = apps.mapNotNull { it.peer }.toSet().size > 1
    val sources = WorkspaceApp.sources(apps)
    val shownSource = if (sources.size > 1 && source in sources) source else null
    val shown = WorkspaceApp.byTitle(WorkspaceApp.filter(apps, query, shownSource))
    // Not while searching: the search is of the apps.
    val homes = if (query.isEmpty()) Stadeum.talkHomes else emptyList()

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Theme.colors.fieldFill), contentAlignment = Alignment.Center) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Theme.colors.text) }
            }
            Spacer(Modifier.width(12.dp))
            Text("Apps", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Theme.colors.text)
            if (sources.size > 1) {
                var open by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { open = true }) { Icon(Icons.Filled.FilterList, "Filter by source", tint = if (shownSource == null) Theme.colors.secondaryText else Theme.colors.accent) }
                    DropdownMenu(open, { open = false }) {
                        DropdownMenuItem(text = { Text("All") }, onClick = { source = null; open = false })
                        sources.forEach { s -> DropdownMenuItem(text = { Text(s) }, onClick = { source = s; open = false }) }
                    }
                }
            }
            // Off while a read is under way: another would cut it short.
            IconButton(onClick = { refresh++ }, enabled = live.isNotEmpty() && !reading) {
                Icon(Icons.Filled.Refresh, "Refresh", tint = Theme.colors.secondaryText)
            }
        }
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
            placeholder = { Text("Search apps") },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                if (homes.isNotEmpty()) {
                    item { Text("Talk to Jamie", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleSmall, color = Theme.colors.secondaryText) }
                    items(homes, key = { "talk-${it.id}" }) { home ->
                        TalkRow(home) {
                            // TODO(jamie-voice): start a call with Jamie on `home`. Needs Hive's call
                            // endpoint (iOS Hive.swift ~393, docs/talk.md) to return a room + hive
                            // token, then `MainTabs.connect(...)` with a `CallLink` carrying
                            // `hiveToken` (CallLink already round-trips it).
                            toast.show("Talking to Jamie is coming soon on Android.", ToastType.Info)
                        }
                    }
                    item { Spacer(Modifier.size(12.dp)) }
                }
                items(shown, key = { it.id }) { app ->
                    AppRow(app, several, onOpen = {
                        val opening = AppPage.opening(app, live)
                        if (opening != null) page = opening else toast.show("Connect ${app.source} to open this app.", ToastType.Warning)
                    }, onShare = { link ->
                        copy(context, link)
                        toast.show("Link copied", ToastType.Success)
                    })
                }
                if (unread.isNotEmpty() && apps.isNotEmpty() && !reading) {
                    item { Note("Couldn't read ${unread.joinToString(", ")} just now. Refresh to try again.") }
                } else if (apps.isEmpty() && homes.isNotEmpty() && !reading) {
                    item { Note(emptyText(live, unread)) }
                }
            }
            when {
                apps.isNotEmpty() && shown.isEmpty() -> Centered("No results", "Nothing matches \u201c${query.trim()}\u201d.")
                apps.isEmpty() && homes.isNotEmpty() -> if (reading) Spinner()
                apps.isEmpty() && live.isEmpty() -> Centered("No Stadeum here yet", "Connect Stadeum under You to open the apps your workspaces pin.")
                apps.isEmpty() && reading -> Spinner()
                apps.isEmpty() && unread.isNotEmpty() -> Centered("Couldn't read the apps", "${unread.joinToString(", ")} did not answer. Refresh to try again.")
                apps.isEmpty() -> Centered("No apps yet", "Apps your Stadeum workspaces pin show here.")
            }
        }
    }
}

private fun emptyText(live: List<HivePeer>, unread: List<String>) = when {
    live.isEmpty() -> "Connect Stadeum under You to open the apps your workspaces pin."
    unread.isEmpty() -> "Apps your Stadeum workspaces pin show here."
    else -> "${unread.joinToString(", ")} did not answer. Refresh to try again."
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(app: WorkspaceApp, showsSource: Boolean, onOpen: () -> Unit, onShare: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val link = app.shareLink()
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { if (link != null) menu = true }).padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Apps, null, Modifier.size(26.dp), tint = Theme.colors.secondaryText)
            Column(Modifier.weight(1f)) {
                Text(app.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text)
                app.about?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText) }
                app.caption(showsSource)?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText) }
            }
        }
        DropdownMenu(menu, { menu = false }) {
            // Copies a link to the app: nothing is sent, and it opens only for
            // someone with that workspace connected.
            DropdownMenuItem(text = { Text("Share link") }, onClick = { menu = false; link?.let(onShare) })
        }
    }
}

@Composable
private fun TalkRow(home: TalkHome, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.GraphicEq, null, Modifier.size(26.dp), tint = Theme.colors.secondaryText)
        Column(Modifier.weight(1f)) {
            Text(home.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text)
            Text(if (home.id == TalkHome.DEVELOPMENT) home.url else "@${home.id}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText)
        }
        Icon(Icons.Filled.Mic, "Talk to Jamie on ${home.name}", tint = Theme.colors.accent)
    }
}

@Composable
private fun Note(text: String) = Text(text, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText)

@Composable
private fun Spinner() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

@Composable
private fun Centered(title: String, body: String) = Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Theme.colors.text)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText)
    }
}

/// An app's page in an in-app web view, as iOS's org page sheet does: the
/// person's token goes in an `Authorization` header on the first request
/// only (never in the URL, which would leak it to history and logs).
/// Navigation is kept inside the strut's own origin; anything else is not
/// loaded here.
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AppPageScreen(page: AppPage, onBack: () -> Unit, modifier: Modifier) {
    BackHandler(onBack = onBack)
    var web by remember { mutableStateOf<WebView?>(null) }
    val origin = remember(page) { android.net.Uri.parse(page.url).let { it.scheme to it.authority } }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Theme.colors.fieldFill), contentAlignment = Alignment.Center) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Theme.colors.text) }
            }
            Spacer(Modifier.width(12.dp))
            Text(page.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Theme.colors.text)
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                            val u = request.url
                            // Same origin loads here, with the token, since a redirect drops headers.
                            if (u.scheme == origin.first && u.authority == origin.second) {
                                view.loadUrl(u.toString(), mapOf("Authorization" to "Bearer ${page.token}"))
                            }
                            return true
                        }
                    }
                    loadUrl(page.url, mapOf("Authorization" to "Bearer ${page.token}"))
                    web = this
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { web?.destroy() } }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("App link", text))
}
