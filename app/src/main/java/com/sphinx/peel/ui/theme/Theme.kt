package com.sphinx.peel.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/// The accent blue Mac and iOS share (Apps/Shared/Theme.swift: 0x618AFF).
private val PeelAccent = Color(0xFF618AFF)

private val DarkColors = darkColorScheme(primary = PeelAccent)
private val LightColors = lightColorScheme(primary = PeelAccent)

@Composable
fun PeelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
