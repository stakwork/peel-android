package com.sphinx.peel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.sphinx.peel.R
import com.sphinx.peel.storage.ConversationRow
import com.sphinx.peel.ui.theme.Theme

/// A person's colour on their initials avatar — one of eight hues, chosen
/// by a hash of their key, the same on every device. Ported from
/// PeelCore's `PersonHue.swift`: FNV-1a (32-bit) over `"<pubkey>-color"`,
/// mod 8. Peel's own accounts have no synced `chat_color` to match against
/// a hue's nearest neighbour (that only matters for importing the official
/// apps' colours, which nothing here does), so this is the hash alone —
/// `PersonHue.init(pubkey:alias:inTribe:synced:)`'s fallback path, always.
enum class PersonHue(val fill: Color) {
    CORAL(Color(0xFFBA4A36)),
    LIME(Color(0xFF587900)),
    GREEN(Color(0xFF007F3D)),
    TEAL(Color(0xFF007C70)),
    CYAN(Color(0xFF007893)),
    VIOLET(Color(0xFF765DC1)),
    ORCHID(Color(0xFF9951A9)),
    PINK(Color(0xFFB44778)),
    ;

    companion object {
        /// Conversation-list avatars are never a tribe *member*'s hue (that
        /// needs an alias and "in this tribe"), only a chat's own key —
        /// `ConversationIcon`'s call, `hue: model.hue(pubkey: ..., alias:
        /// nil, inTribe: false)`.
        fun of(pubkey: String): PersonHue {
            var hash = 0x811C_9DC5u
            for (byte in "$pubkey-color".toByteArray(Charsets.UTF_8)) {
                hash = hash xor byte.toUByte().toUInt()
                hash *= 0x0100_0193u
            }
            return entries[(hash % entries.size.toUInt()).toInt()]
        }
    }
}

enum class AvatarShape { CIRCLE, ROUNDED_SQUARE }

/// A contact's photo, a tribe's icon, or initials on a hue — ported from
/// `Apps/Shared/Avatar.swift`. People are circles, tribes rounded squares;
/// a rounded square or anything under 18dp gets one initial, a circle at
/// or above it gets up to two (two letters are "too small to read" below
/// that, the Swift source's own words). Only an https photo is ever
/// loaded.
@Composable
fun Avatar(
    name: String,
    url: String?,
    size: Dp,
    hue: PersonHue,
    shape: AvatarShape = AvatarShape.CIRCLE,
    modifier: Modifier = Modifier,
) {
    val initials = remember(name, shape, size) {
        val wordCount = if (shape == AvatarShape.ROUNDED_SQUARE || size < 18.dp) 1 else 2
        name.split(' ', '_')
            .filter { it.isNotEmpty() }
            .take(wordCount)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")
    }
    val outline: Shape = if (shape == AvatarShape.CIRCLE) CircleShape else RoundedCornerShape(size * 0.25f)
    val photo = url?.takeIf { it.startsWith("https://", ignoreCase = true) }

    Box(modifier.size(size).clip(outline).background(hue.fill), contentAlignment = Alignment.Center) {
        if (photo != null) {
            AsyncImage(
                model = photo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val multiplier = if (initials.length > 1) 0.38f else if (size < 24.dp) 0.55f else 0.42f
            Text(initials, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * multiplier).sp)
        }
    }
}

/// `Avatar`, shaped and coloured from a conversation row: a tribe's
/// rounded square or a person's circle, by its own key — `ConversationIcon`
/// ported.
@Composable
fun ConversationAvatar(conversation: ConversationRow, size: Dp, modifier: Modifier = Modifier) {
    Avatar(
        name = conversation.name,
        url = conversation.photoUrl,
        size = size,
        hue = PersonHue.of(conversation.chat),
        shape = if (conversation.isTribe) AvatarShape.ROUNDED_SQUARE else AvatarShape.CIRCLE,
        modifier = modifier,
    )
}

/// Peel's own icon as the agent's avatar — `Apps/Shared/Avatar.swift`'s
/// `PeelLogo`: agents are rounded squares like tribes, never the hashed
/// colour people and tribes get. The launcher's own foreground glyph
/// (`ic_launcher_foreground`) on its background colour, the same icon the
/// app itself uses, not a second asset to keep in sync with it.
@Composable
fun PeelLogo(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.25f)).background(Color(0xFF618AFF)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = "Peel",
            tint = Color.White,
            modifier = Modifier.size(size * 0.85f),
        )
    }
}

/// The small "AGENT" pill beside Peel's name — PeelCore's own `AgentTag`,
/// shown wherever the agent's chat is named: its row in Chats, and its own
/// row in Profile.
@Composable
fun AgentTag() {
    Text(
        "AGENT",
        style = MaterialTheme.typography.labelSmall,
        color = Theme.colors.secondaryText,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Theme.colors.fieldFill)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
