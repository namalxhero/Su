package com.namalxhero.su.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SuGreen = Color(0xFF00E676)
val SuGreenDim = Color(0xFF00C853)
val SuBlack = Color(0xFF000000)
val SuSurface = Color(0xFF0D0D0D)
val SuSurfaceVariant = Color(0xFF161616)
val SuRed = Color(0xFFFF5252)
val SuWhite = Color(0xFFF5F5F5)
val SuGrey = Color(0xFF9E9E9E)

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
