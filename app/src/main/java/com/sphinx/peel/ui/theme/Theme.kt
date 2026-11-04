package com.sphinx.peel.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/// Sphinx's colours, ported from Apps/Shared/Theme.swift (peel, Mac/iOS).
/// Every colour follows the system's light or dark appearance.
data class PeelPalette(
    val background: Color,
    val text: Color,
    val secondaryText: Color,
    val divider: Color,
    /// A translucent fill for boxed fields (PIN boxes, word boxes).
    val fieldFill: Color,
    val inputBorder: Color,
    val accent: Color,
    val button: Color,
    val buttonText: Color,
    /// PrimaryRed: only for actions that destroy something.
    val destructive: Color,
    /// PrimaryGreen: connected, and a message the server confirmed.
    val confirmed: Color,
    /// Needs a look: not connected, a retry pending.
    val attention: Color,
    /// Message text (Theme.swift's `link`, `mention*`, `codeFill`).
    val link: Color,
    val mentionFill: Color,
    val mentionText: Color,
    /// A mention of you, or @all.
    val mentionYouFill: Color,
    val mentionYouText: Color,
    val codeFill: Color,
)

private val LightPeelPalette = PeelPalette(
    background = Color(0xFFFFFFFF),
    text = Color(0xFF292C33),
    secondaryText = Color(0xFF646C73),
    divider = Color(0xFFDCE0E3),
    fieldFill = Color(0x0D141E2D),
    inputBorder = Color(0x29141E2D),
    accent = Color(0xFF618AFF),
    button = Color(0xFF3F68E0),
    buttonText = Color(0xFFFFFFFF),
    destructive = Color(0xFFED7474),
    confirmed = Color(0xFF49C998),
    attention = Color(0xFFA86400),
    link = Color(0xFF3F68E0),
    mentionFill = Color(0xFFDDE6FF),
    mentionText = Color(0xFF1E3D99),
    mentionYouFill = Color(0xFFFBE7A6),
    mentionYouText = Color(0xFF5C4300),
    codeFill = Color(0x12000000),
)

private val DarkPeelPalette = PeelPalette(
    background = Color(0xFF151E27),
    text = Color(0xFFE3E7EC),
    secondaryText = Color(0xFF97A3B4),
    divider = Color(0xFF2C353D),
    fieldFill = Color(0x0EFFFFFF),
    inputBorder = Color(0x26FFFFFF),
    accent = Color(0xFF618AFF),
    button = Color(0xFF618AFF),
    buttonText = Color(0xFF0E1620),
    destructive = Color(0xFFED7474),
    confirmed = Color(0xFF49C998),
    attention = Color(0xFFFFBC58),
    link = Color(0xFF8FB0FF),
    mentionFill = Color(0xFF2A3A5E),
    mentionText = Color(0xFFB4C8FF),
    mentionYouFill = Color(0x38F2C744),
    mentionYouText = Color(0xFFFFE08A),
    codeFill = Color(0x12FFFFFF),
)

val LocalPeelPalette = compositionLocalOf { LightPeelPalette }

/// Peel's own colour tokens, for components ported pixel-for-pixel from the
/// Swift apps (PIN pad, word grid). Material3's `MaterialTheme.colorScheme`
/// is still used for plain Material components.
object Theme {
    val colors: PeelPalette
        @Composable get() = LocalPeelPalette.current
}

@Composable
fun PeelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkPeelPalette else LightPeelPalette
    val materialColors = if (darkTheme) {
        darkColorScheme(primary = palette.accent, background = palette.background)
    } else {
        lightColorScheme(primary = palette.accent, background = palette.background)
    }
    CompositionLocalProvider(LocalPeelPalette provides palette) {
        MaterialTheme(colorScheme = materialColors, content = content)
    }
}
