package com.sphinx.peel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sphinx.peel.ui.theme.Theme

/// A small capsule on `fieldFill`: an icon, a title and, above zero, a count in the accent (the iPhone's `PinnedPages` and
/// `RecentApps` chips). A tap opens what it stands for; [onLongClick], when given, opens its menu. Chats' pinned pages and Apps'
/// Recent use it.
@Composable
fun CapsuleChip(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Long = 0,
    onLongClick: (() -> Unit)? = null,
    icon: @Composable () -> Unit,
) {
    Row(
        modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(Theme.colors.fieldFill)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { icon() }
        Text(title, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count > 0) {
            Text(
                countText(count),
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
                color = Theme.colors.accent,
            )
        }
    }
}

/// A count as a badge shows it: 99+ past 99 (PeelCore's `Badge.text`).
fun countText(count: Long): String = if (count > 99) "99+" else count.toString()
