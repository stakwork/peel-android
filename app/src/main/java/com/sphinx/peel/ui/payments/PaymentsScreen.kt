package com.sphinx.peel.ui.payments

import androidx.compose.ui.res.stringResource
import com.sphinx.peel.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sphinx.peel.engine.ConnectionState
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.Sats
import com.sphinx.peel.engine.fetchOlderPaymentHistory
import com.sphinx.peel.engine.refreshPaymentHistory
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.theme.Theme
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class PaymentsPage {
    data object List : PaymentsPage()
    data object Receive : PaymentsPage()
    data class Send(val prefill: String = "") : PaymentsPage()
    data class Detail(val id: String) : PaymentsPage()
}

private const val PAGE = 50

/// The Payments tab (the iPhone's `PaymentsTab`, docs/design/iphone.md screen
/// 6): the balance, Receive and Send, then the history by day, newest first.
/// Older pages load as the list's end comes into view; Refresh asks the
/// server for what is new, as does a connection or a balance change. A row
/// opens its detail. Sats only: neither the iPhone nor the Mac shows fiat.
@Composable
fun PaymentsScreen(engine: Engine, storage: Storage, modifier: Modifier = Modifier, onOpenChat: (String) -> Unit = {}) {
    var page by remember { mutableStateOf<PaymentsPage>(PaymentsPage.List) }
    BackHandler(enabled = page != PaymentsPage.List) { page = PaymentsPage.List }
    Box(modifier.fillMaxSize()) {
        when (val current = page) {
            PaymentsPage.List -> PaymentsList(engine, storage, onReceive = { page = PaymentsPage.Receive }, onSend = { page = PaymentsPage.Send() }) {
                page = PaymentsPage.Detail(it)
            }
            PaymentsPage.Receive -> ReceiveScreen(engine, storage, onBack = { page = PaymentsPage.List })
            is PaymentsPage.Send -> SendScreen(
                engine, storage, prefill = current.prefill, onBack = { page = PaymentsPage.List }, onReceive = { page = PaymentsPage.Receive },
            )
            is PaymentsPage.Detail -> PaymentDetailScreen(storage, current.id, onBack = { page = PaymentsPage.List }, onOpenChat = onOpenChat)
        }
    }
}

@Composable
private fun PaymentsList(
    engine: Engine,
    storage: Storage,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val balance by engine.balance.collectAsState()
    val connection by engine.connectionState.collectAsState()
    val connected = connection is ConnectionState.Connected
    var limit by remember { mutableIntStateOf(PAGE) }
    var items by remember { mutableStateOf(emptyList<PaymentItem>()) }
    var loadingOlder by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val couldNotCheck = stringResource(R.string.pay_err_check)
    val todayLabel = stringResource(R.string.pay_today)
    val yesterdayLabel = stringResource(R.string.pay_yesterday)

    suspend fun reload() {
        items = withContext(Dispatchers.Default) {
            PaymentItem.build(storage.payments(limit), storage.invoices(), contactOf = storage::contact, messageOf = storage::messageByPaymentHash)
        }
    }
    suspend fun refresh() {
        try {
            engine.refreshPaymentHistory()
            problem = null
        } catch (e: Exception) {
            problem = e.message ?: couldNotCheck
        }
        reload()
    }
    LaunchedEffect(limit) { reload() }
    // The stored history moves as the listener stores pages and answers.
    LaunchedEffect(limit) {
        while (true) { delay(2_000); reload() }
    }
    // The connection and the balance ask for the newest page again; changes
    // within half a second make one refresh (connecting brings the balance a moment later).
    LaunchedEffect(connected, balance) {
        if (!connected) return@LaunchedEffect
        delay(500)
        refresh()
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(stringResource(R.string.pay_payments), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Theme.colors.text)
                Spacer(Modifier.height(16.dp))
                BalanceHeader(balance, onReceive, onSend)
                TextButton(onClick = { scope.launch { refresh() } }, enabled = connected) { Text(stringResource(R.string.pay_refresh), color = Theme.colors.accent) }
            }
        }
        if (items.isEmpty()) {
            item {
                Text(
                    problem ?: if (connected) stringResource(R.string.pay_no_payments) else stringResource(R.string.pay_not_connected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Theme.colors.secondaryText,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
            }
        } else {
            val days = PaymentItem.byDay(items)
            for ((day, dayItems) in days) {
                item(key = "day$day") {
                    Text(
                        dayHeading(day, todayLabel, yesterdayLabel),
                        style = MaterialTheme.typography.labelLarge,
                        color = Theme.colors.secondaryText,
                        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(dayItems, key = { it.id }) { item ->
                    PaymentRow(item, Modifier.clickable { onOpen(item.id) })
                    if (item.id == items.last().id && hasMore && connected) {
                        LaunchedEffect(item.id, limit) {
                            loadingOlder = true
                            try {
                                val came = engine.fetchOlderPaymentHistory()
                                hasMore = came >= PAGE
                                limit += PAGE
                                problem = null
                            } catch (e: Exception) {
                                problem = e.message
                            } finally {
                                loadingOlder = false
                            }
                        }
                    }
                }
            }
            if (loadingOlder || problem != null) {
                item {
                    Text(
                        if (loadingOlder) stringResource(R.string.pay_loading_older) else problem ?: "",
                        style = MaterialTheme.typography.labelMedium,
                        color = Theme.colors.secondaryText,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/// "Balance", the amount (large) with "sats", then Receive (outline) and Send (filled).
@Composable
private fun BalanceHeader(balanceMsat: Long?, onReceive: () -> Unit, onSend: () -> Unit) {
    Column {
        Text(stringResource(R.string.pay_balance), style = MaterialTheme.typography.labelMedium, color = Theme.colors.secondaryText)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                balanceMsat?.let { Sats.number(it) } ?: "–",
                fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Theme.colors.text,
            )
            Spacer(Modifier.width(6.dp))
            Text("sats", style = MaterialTheme.typography.titleMedium, color = Theme.colors.secondaryText, modifier = Modifier.padding(bottom = 7.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onReceive, modifier = Modifier.weight(1f).height(48.dp)) {
                Icon(Icons.Filled.ArrowDownward, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.pay_receive))
            }
            Button(
                onClick = onSend, modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Theme.colors.button, contentColor = Theme.colors.buttonText),
            ) {
                Icon(Icons.Filled.ArrowUpward, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.pay_send))
            }
        }
    }
}

/// One payment (iOS `PaymentRow`): the bolt on a soft amber circle (a clock
/// while it waits), who or what, the line under it, the signed amount over
/// the time, and a chevron. Received is green with "+"; pending grey, failed
/// struck through, never red.
@Composable
private fun PaymentRow(item: PaymentItem, modifier: Modifier = Modifier) {
    val waiting = item.status == PaymentItem.Status.PENDING
    val faded = item.status != PaymentItem.Status.COMPLETE
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).clip(CircleShape)
                .background(if (waiting) Theme.colors.fieldFill else Theme.colors.attention.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (waiting) Icons.Filled.Schedule else Icons.Filled.Bolt, null,
                Modifier.size(18.dp), tint = if (waiting) Theme.colors.secondaryText else Theme.colors.attention,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                color = if (waiting) Theme.colors.secondaryText else Theme.colors.text, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (item.status == PaymentItem.Status.FAILED) TextDecoration.LineThrough else null)
            Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                Sats.signed(item.amountMsat, item.incoming),
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                color = if (faded) Theme.colors.secondaryText else if (item.incoming) Theme.colors.confirmed else Theme.colors.text,
                textDecoration = if (item.status == PaymentItem.Status.FAILED) TextDecoration.LineThrough else null,
            )
            Text(item.timeWithStatus(timeOf(item.ts)), style = MaterialTheme.typography.labelSmall, color = Theme.colors.secondaryText)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Theme.colors.secondaryText, modifier = Modifier.size(20.dp))
    }
    HorizontalDivider(color = Theme.colors.divider, modifier = Modifier.padding(start = 68.dp))
}

private fun timeOf(ts: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ts))

/// iOS `Formatting.dayHeading`: "Today", "Yesterday", else "Monday 3 November".
private fun dayHeading(dayMs: Long, today: String, yesterday: String): String {
    val now = java.util.Calendar.getInstance()
    val day = java.util.Calendar.getInstance().apply { timeInMillis = dayMs }
    fun ymd(c: java.util.Calendar) = c.get(java.util.Calendar.YEAR) * 1000 + c.get(java.util.Calendar.DAY_OF_YEAR)
    val yesterday = (now.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
    return when (ymd(day)) {
        ymd(now) -> today
        ymd(yesterday) -> yesterday
        else -> java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault()).format(Date(dayMs))
    }
}

@Composable
internal fun Fact(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Theme.colors.secondaryText)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.text)
    }
}

/// A sub-page: a back arrow and a title over scrolling content.
@Composable
internal fun PageFrame(title: String, onBack: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.pay_back), tint = Theme.colors.text) }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Theme.colors.text)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            content = content,
        )
    }
}
