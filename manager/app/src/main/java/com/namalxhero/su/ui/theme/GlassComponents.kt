package com.namalxhero.su.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Su's "Ghostly" look, matched to the Ghostly design reference rather
 * than loosely inspired by it - every value below (surface alpha,
 * border alpha, corner radius) is read directly off that reference's
 * own rendered CSS, not approximated. The reference's cards are flat,
 * not a radial "liquid glass" sheen (that was the previous RootX
 * reference) - pure near-black fill, a faint vertical lightening
 * toward the top, and a thin low-alpha white border. True backdrop
 * blur still isn't used, same tradeoff as before: nothing here needs
 * genuine blur-through, just translucency.
 */

/** Backdrop: flat black (`html{background:#f5f4ef}` is the *bundler's*
 * own page chrome, not the app - the app surface itself renders on
 * plain `#000`). */
@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SuBlack)
    ) {
        content()
    }
}

/** The one card/row/button primitive the reference reuses everywhere:
 * near-black fill with a faint top-to-bottom lightening, a thin
 * low-alpha white border, and a soft dark drop shadow. Defaults to
 * [cornerRadius] = 22dp, the reference's own card radius. */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .shadow(
                elevation = 10.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.6f),
                spotColor = Color.Black.copy(alpha = 0.6f),
            )
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.08f),
                        SuSurface,
                    ),
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape),
    ) {
        content()
    }
}

/** Fully pill-shaped variant of [GlassSurface] - rounded ends
 * regardless of height, used for the floating bottom nav bar and any
 * chip/badge ("Allow", grant-state tags) that should read as a pill
 * rather than a rounded rectangle. */
@Composable
fun GlassPill(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .shadow(
                elevation = 10.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.6f),
                spotColor = Color.Black.copy(alpha = 0.6f),
            )
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.08f),
                        SuSurface,
                    ),
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape),
    ) {
        content()
    }
}

/** Small circular glass button - the reference's refresh/settings
 * icon buttons: same fill/border treatment as [GlassSurface], just
 * always a perfect circle. */
@Composable
fun GlassCircle(
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // Plain clickable (default LocalIndication ripple) rather than a
    // custom bounded=false ripple - avoids pulling in the separate
    // androidx.compose.material ripple artifact this project doesn't
    // otherwise depend on (material3-only, see build.gradle.kts).
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else Modifier

    Box(
        modifier = modifier
            .then(Modifier.size(size))
            .then(clickableModifier)
            .shadow(
                elevation = 8.dp,
                shape = androidx.compose.foundation.shape.CircleShape,
                ambientColor = Color.Black.copy(alpha = 0.6f),
                spotColor = Color.Black.copy(alpha = 0.6f),
            )
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.10f),
                        SuSurface,
                    ),
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.12f), androidx.compose.foundation.shape.CircleShape),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        content()
    }
}
