package com.sphinx.peel.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sphinx.peel.engine.ChatSearch
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.MessageQuery
import com.sphinx.peel.storage.MessageRow
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.components.Avatar
import com.sphinx.peel.ui.components.AvatarShape
import com.sphinx.peel.ui.components.PersonHue
import com.sphinx.peel.ui.theme.Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// The search bar under a chat's header while it is in search mode (iOS `ChatSearchBar`, peel#576): the field
/// (focused, Search key = next older match, the first when none is picked), the count, newer/older arrows, the
/// results list and close. Typing is debounced ~200ms and searched off the main thread over the whole stored
/// history (`Storage.searchMessages`). The bar never draws in a thread: `ConversationScreen` leaves it out there.
/// [onJump] is called with the match to show (the screen scrolls to it, or opens its thread).
/// [display] is a row's sender name, photo and avatar-colour key.
@Composable
internal fun ChatSearchBar(
    search: ChatSearch,
    storage: Storage,
    engine: Engine,
    display: (MessageRow) -> Triple<String, String?, String>,
    onJump: (MessageRow) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    var showResults by remember { mutableStateOf(false) }

    // The field takes focus when the bar opens and again each time the Search menu item is used.
    LaunchedEffect(search.focusRequests) { runCatching { focus.requestFocus() } }

    // Typing searches 200ms after the last key. A result for text typed over, or for a bar closed (this effect
    // is cancelled with it), is dropped: `withContext` checks for cancellation when it returns.
    LaunchedEffect(search.text) {
        val typed = search.typed
        if (typed.isEmpty) {
            search.clearResults()
            return@LaunchedEffect
        }
        if (typed == search.query && !search.failed) return@LaunchedEffect
        delay(200)
        search.searching = true
        try {
            val (rows, syncing) = withContext(Dispatchers.IO) {
                // A restore still running means older messages may not be stored yet.
                storage.searchMessages(typed, search.chat, ChatSearch.LIMIT) to engine.restoreProgress.value.restoring
            }
            if (search.typed != typed) return@LaunchedEffect
            if (search.found(rows, typed, historySyncing = syncing)) search.stepOlder()?.let(onJump)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (search.typed == typed) search.found(emptyList(), typed, failed = true)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(Theme.colors.fieldFill).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = Theme.colors.secondaryText, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = search.text,
                    onValueChange = { search.text = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Theme.colors.text),
                    cursorBrush = SolidColor(Theme.colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        // Typed text not searched yet: its first match is shown when the search lands.
                        if (!search.waitForMatches()) search.stepOlder()?.let(onJump)
                    }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (search.text.isEmpty()) {
                                Text("Search this chat", style = MaterialTheme.typography.bodyLarge, color = Theme.colors.secondaryText)
                            }
                            inner()
                        }
                    },
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close search", tint = Theme.colors.text)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (search.searching && search.matches.isEmpty()) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Theme.colors.secondaryText)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    search.countText.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Theme.colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Matches run newest first: up goes to a NEWER match, down to an OLDER one.
            IconButton(onClick = { search.stepNewer()?.let(onJump) }, enabled = search.canStepNewer) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Newer match", tint = if (search.canStepNewer) Theme.colors.text else Theme.colors.secondaryText.copy(alpha = 0.4f))
            }
            IconButton(onClick = { search.stepOlder()?.let(onJump) }, enabled = search.canStepOlder) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Older match", tint = if (search.canStepOlder) Theme.colors.text else Theme.colors.secondaryText.copy(alpha = 0.4f))
            }
            IconButton(onClick = { showResults = true }, enabled = search.matches.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Search results", tint = if (search.matches.isNotEmpty()) Theme.colors.text else Theme.colors.secondaryText.copy(alpha = 0.4f))
            }
        }
        HorizontalDivider(color = Theme.colors.divider)
    }

    if (showResults) {
        SearchResultsSheet(search, display, onDismiss = { showResults = false }, onPick = onJump)
    }
}

/// The matches as a list (iOS `SearchResultsSheet`): who, in a thread or not, when, and a snippet with the terms
/// marked; the current one tinted. A pick is remembered, the sheet closed, and only then [onPick] runs, so a sheet
/// never stands over the thread a match opens.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchResultsSheet(
    search: ChatSearch,
    display: (MessageRow) -> Triple<String, String?, String>,
    onDismiss: () -> Unit,
    onPick: (MessageRow) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val query = search.query
    val mark = Theme.colors.attention.copy(alpha = 0.35f)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Theme.colors.background) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Search results",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Theme.colors.text,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { scope.launch { sheetState.hide(); onDismiss() } }) { Text("Done", color = Theme.colors.accent) }
        }
        HorizontalDivider(color = Theme.colors.divider)
        LazyColumn(Modifier.fillMaxWidth()) {
            itemsIndexed(search.matches, key = { _, row -> row.id.takeIf { it != 0L } ?: (row.uuid ?: row.hashCode()) }) { index, row ->
                val (name, photoUrl, hueKey) = display(row)
                val snippet = remember(row.id, query) {
                    val content = previewContent(row)
                    highlighted(query?.snippet(content) ?: content, query, mark)
                }
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (search.current == index) Theme.colors.fieldFill else Color.Transparent)
                        .clickable {
                            val picked = search.select(index)
                            scope.launch {
                                sheetState.hide()
                                onDismiss()
                                if (picked != null) onPick(picked)
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Avatar(name = name, url = photoUrl, size = 32.dp, hue = PersonHue.of(hueKey), shape = AvatarShape.CIRCLE)
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Theme.colors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (row.threadUuid != null) {
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Filled.Forum, contentDescription = "In a thread", tint = Theme.colors.secondaryText, modifier = Modifier.size(14.dp))
                            }
                            Spacer(Modifier.weight(1f))
                            row.timestamp?.let {
                                Text(relativeDate(it), style = MaterialTheme.typography.labelSmall, color = Theme.colors.secondaryText)
                            }
                        }
                        Text(
                            snippet,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Theme.colors.text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            search.historyNote?.let { note ->
                item {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = Theme.colors.secondaryText,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            }
        }
    }
}

private fun highlighted(text: String, query: MessageQuery?, mark: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    query?.highlights(text)?.forEach { addStyle(SpanStyle(fontWeight = FontWeight.Bold, background = mark), it.first, it.last + 1) }
}

/// "5 minutes ago", "Yesterday", else a date (the platform's own wording).
private fun relativeDate(timestamp: Long): String =
    android.text.format.DateUtils.getRelativeTimeSpanString(
        timestamp, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS, android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
