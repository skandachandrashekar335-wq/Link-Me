package com.linkme.androidbridge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.linkme.androidbridge.core.prefs.BridgeAccentTheme

object StitchTokens {
    val SurfaceLowest = Color(0xFF0D0E10)
    val Surface = Color(0xFF121315)
    val SurfaceLow = Color(0xFF1B1C1E)
    val SurfaceContainer = Color(0xFF1F2022)
    val SurfaceHigh = Color(0xFF292A2C)
    val SurfaceHighest = Color(0xFF343537)

    val PrimaryCrimson = Color(0xFFE11D48)
    val TertiaryMint = Color(0xFF74D8BD)
    val ActiveCardLight = Color(0xFFE4E2DE)
    val ActiveCardInk = Color(0xFF1B1C1A)

    val OutlineVariant = Color(0xFF5C3F40)
    val OnSurface = Color(0xFFE3E2E4)
    val SecondaryText = Color(0xFFC8C6C3)
}

@Immutable
data class BridgeThemePalette(
    val theme: BridgeAccentTheme,
    val surfaceLowest: Color,
    val surface: Color,
    val surfaceLow: Color,
    val surfaceContainer: Color,
    val surfaceHigh: Color,
    val surfaceHighest: Color,
    val primaryAccent: Color,
    val secondaryAccent: Color,
    val activeCardLight: Color,
    val activeCardInk: Color,
    val outlineVariant: Color,
    val onSurface: Color,
    val secondaryText: Color,
    val isLight: Boolean
)

fun paletteForTheme(theme: BridgeAccentTheme): BridgeThemePalette = when (theme) {
    BridgeAccentTheme.CRIMSON -> BridgeThemePalette(
        theme = theme,
        surfaceLowest = Color(0xFF0D0E10),
        surface = Color(0xFF121315),
        surfaceLow = Color(0xFF1B1C1E),
        surfaceContainer = Color(0xFF1F2022),
        surfaceHigh = Color(0xFF292A2C),
        surfaceHighest = Color(0xFF343537),
        primaryAccent = Color(0xFFE11D48),
        secondaryAccent = Color(0xFF74D8BD),
        activeCardLight = Color(0xFFE4E2DE),
        activeCardInk = Color(0xFF1B1C1A),
        outlineVariant = Color(0xFF5C3F40),
        onSurface = Color(0xFFE3E2E4),
        secondaryText = Color(0xFFC8C6C3),
        isLight = false
    )

    BridgeAccentTheme.MINT -> BridgeThemePalette(
        theme = theme,
        surfaceLowest = Color(0xFF0D0E10),
        surface = Color(0xFF121315),
        surfaceLow = Color(0xFF171A1D),
        surfaceContainer = Color(0xFF1D2226),
        surfaceHigh = Color(0xFF252C31),
        surfaceHighest = Color(0xFF2F373D),
        primaryAccent = Color(0xFF10B981),
        secondaryAccent = Color(0xFF38BDF8),
        activeCardLight = Color(0xFFE4E2DE),
        activeCardInk = Color(0xFF1B1C1A),
        outlineVariant = Color(0xFF2A4D46),
        onSurface = Color(0xFFE3E2E4),
        secondaryText = Color(0xFFB9C6C9),
        isLight = false
    )

    BridgeAccentTheme.AMBER -> BridgeThemePalette(
        theme = theme,
        surfaceLowest = Color(0xFF0E0D0B),
        surface = Color(0xFF141210),
        surfaceLow = Color(0xFF1C1916),
        surfaceContainer = Color(0xFF24201C),
        surfaceHigh = Color(0xFF2E2924),
        surfaceHighest = Color(0xFF3A332D),
        primaryAccent = Color(0xFFF59E0B),
        secondaryAccent = Color(0xFFF5F3EF),
        activeCardLight = Color(0xFFF5F3EF),
        activeCardInk = Color(0xFF141210),
        outlineVariant = Color(0xFF5B4728),
        onSurface = Color(0xFFF5F3EF),
        secondaryText = Color(0xFFC8BEB0),
        isLight = false
    )

    BridgeAccentTheme.ALABASTER -> BridgeThemePalette(
        theme = theme,
        surfaceLowest = Color(0xFFEAE6DF),
        surface = Color(0xFFF5F3EF),
        surfaceLow = Color(0xFFFFFFFF),
        surfaceContainer = Color(0xFFFFFFFF),
        surfaceHigh = Color(0xFFE5E1D8),
        surfaceHighest = Color(0xFFD8D3C8),
        primaryAccent = Color(0xFFE11D48),
        secondaryAccent = Color(0xFF0F766E),
        activeCardLight = Color(0xFF111214),
        activeCardInk = Color(0xFFF5F3EF),
        outlineVariant = Color(0xFFC9C3B8),
        onSurface = Color(0xFF111214),
        secondaryText = Color(0xFF4B4E52),
        isLight = true
    )
}

val LocalBridgePalette = staticCompositionLocalOf { paletteForTheme(BridgeAccentTheme.CRIMSON) }

@Composable
fun AndroidBridgeTheme(
    accentTheme: BridgeAccentTheme = BridgeAccentTheme.CRIMSON,
    content: @Composable () -> Unit
) {
    val palette = paletteForTheme(accentTheme)
    val colorScheme = if (palette.isLight) {
        lightColorScheme(
            primary = palette.primaryAccent,
            onPrimary = Color.White,
            tertiary = palette.secondaryAccent,
            background = palette.surface,
            onBackground = palette.onSurface,
            surface = palette.surfaceContainer,
            onSurface = palette.onSurface,
            surfaceVariant = palette.surfaceHigh,
            onSurfaceVariant = palette.secondaryText,
            outline = palette.outlineVariant
        )
    } else {
        darkColorScheme(
            primary = palette.primaryAccent,
            onPrimary = Color.White,
            tertiary = palette.secondaryAccent,
            background = palette.surface,
            onBackground = palette.onSurface,
            surface = palette.surfaceContainer,
            onSurface = palette.onSurface,
            surfaceVariant = palette.surfaceHigh,
            onSurfaceVariant = palette.secondaryText,
            outline = palette.outlineVariant
        )
    }

    CompositionLocalProvider(LocalBridgePalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
