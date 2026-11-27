package com.linkme.androidbridge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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

private val StitchDarkColors = darkColorScheme(
    primary = StitchTokens.PrimaryCrimson,
    onPrimary = Color.White,
    tertiary = StitchTokens.TertiaryMint,
    background = StitchTokens.Surface,
    onBackground = StitchTokens.OnSurface,
    surface = StitchTokens.SurfaceContainer,
    onSurface = StitchTokens.OnSurface,
    surfaceVariant = StitchTokens.SurfaceHigh,
    onSurfaceVariant = StitchTokens.SecondaryText,
    outline = StitchTokens.OutlineVariant
)

@Composable
fun AndroidBridgeTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StitchDarkColors,
        content = content
    )
}
