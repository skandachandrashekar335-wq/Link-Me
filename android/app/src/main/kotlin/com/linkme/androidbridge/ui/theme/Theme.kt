package com.linkme.androidbridge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object WarmIndustrialPalette {
    val ObsidianBg = Color(0xFF111214)
    val ObsidianSurface = Color(0xFF191B1F)
    val ObsidianBorder = Color(0xFF2A2D34)

    val AlabasterBg = Color(0xFFF5F3EF)
    val AlabasterSurface = Color(0xFFFFFFFF)
    val AlabasterBorder = Color(0xFFDDD8CE)

    val CrimsonAccent = Color(0xFFE11D48)
    val WarmWhite = Color(0xFFF5F3EF)
    val MutedDarkText = Color(0xFF9CA3AF)
    val MutedLightText = Color(0xFF57534E)
    val SignalGreen = Color(0xFF10B981)
}

private val DarkColors = darkColorScheme(
    primary = WarmIndustrialPalette.CrimsonAccent,
    onPrimary = Color.White,
    background = WarmIndustrialPalette.ObsidianBg,
    onBackground = WarmIndustrialPalette.WarmWhite,
    surface = WarmIndustrialPalette.ObsidianSurface,
    onSurface = WarmIndustrialPalette.WarmWhite,
    surfaceVariant = Color(0xFF202329),
    onSurfaceVariant = WarmIndustrialPalette.MutedDarkText,
    outline = WarmIndustrialPalette.ObsidianBorder
)

private val LightColors = lightColorScheme(
    primary = WarmIndustrialPalette.CrimsonAccent,
    onPrimary = Color.White,
    background = WarmIndustrialPalette.AlabasterBg,
    onBackground = WarmIndustrialPalette.ObsidianBg,
    surface = WarmIndustrialPalette.AlabasterSurface,
    onSurface = WarmIndustrialPalette.ObsidianBg,
    surfaceVariant = Color(0xFFECE8E0),
    onSurfaceVariant = WarmIndustrialPalette.MutedLightText,
    outline = WarmIndustrialPalette.AlabasterBorder
)

@Composable
fun AndroidBridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
