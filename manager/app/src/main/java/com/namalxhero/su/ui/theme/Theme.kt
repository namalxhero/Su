package com.namalxhero.su.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// "Ghostly" palette - read directly off the Ghostly design reference
// (mint-green accent on near-pure black, pill-shaped glass cards)
// rather than the earlier neon-green "liquid glass" look.
val SuGreen = Color(0xFF34D399)
val SuGreenDim = Color(0xFF22B37D)
val SuGreenMuted = Color(0x2E34D399) // rgba(52,211,153,0.18) - badge/chip fills
val SuBlack = Color(0xFF000000)
val SuSurface = Color(0xFF0A0A0A)
val SuSurfaceVariant = Color(0xFF141414)
val SuRed = Color(0xFFFF8A80)
val SuWhite = Color(0xFFFFFFFF)
val SuGrey = Color(0xFF9A9AA6)
val SuGreyDim = Color(0xFF8D8D97)

private val SuDarkColors = darkColorScheme(
    primary = SuGreen,
    onPrimary = SuBlack,
    secondary = SuGreenDim,
    onSecondary = SuBlack,
    background = SuBlack,
    onBackground = SuWhite,
    surface = SuSurface,
    onSurface = SuWhite,
    surfaceVariant = SuSurfaceVariant,
    onSurfaceVariant = SuGrey,
    error = SuRed,
)

private val SuLightColors = lightColorScheme(
    primary = SuGreenDim,
    onPrimary = Color.White,
    secondary = SuGreen,
)

/**
 * AMOLED-black-first theme: the app is a root manager, meant to be read
 * at a glance and trusted - dark surfaces everywhere, a single neon-green
 * accent for anything "active"/"granted", red reserved for deny/revoke.
 * Falls back to a light scheme only if the system forces light mode.
 */
@Composable
fun SuManagerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) SuDarkColors else SuLightColors
    MaterialTheme(
        colorScheme = colors,
        typography = SuTypography,
        content = content,
    )
}
