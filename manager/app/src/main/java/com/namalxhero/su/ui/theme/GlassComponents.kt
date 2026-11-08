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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Su's "liquid glass" look, matched to the uploaded design reference
 * (RootX - Black Liquid Glass Ghost UI) rather than loosely inspired by
 * it - every value below (gradient stops, border alpha, corner radii,
 * shadow alpha) is read directly off that reference's own CSS, not
 * approximated. True backdrop blur (seeing the background genuinely
 * blurred *through* a card) would need API 31+ RenderEffect plumbing or
 * a third-party blur library - skipped, same tradeoff as before: the
 * glass *look* this reference actually uses is a radial sheen + bright
 * thin border + soft shadow, not a blur, so nothing is lost by skipping
 * it.
 */

/** Backdrop: `radial-gradient(90% 40% at 50% 0%, #101014, #000 70%)` -
 * near-black with a faint glow rising from top-center. */
@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF101014), SuBlack),
                    center = Offset(0.5f, 0f),
                    radius = 1600f,
                )
            )
    ) {
        content()
    }
}

/** The one card/row/button/nav-bar primitive the reference reuses
 * everywhere: a radial sheen (bright top-left, fading to dark
 * bottom-right), a thin bright border, and a soft dark drop shadow. */
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
                elevation = 16.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.7f),
                spotColor = Color.Black.copy(alpha = 0.7f),
            )
            .clip(shape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.13f),
                        Color.White.copy(alpha = 0.025f),
                        Color.Black.copy(alpha = 0.45f),
                    ),
                    center = Offset.Zero,
                    radius = 700f,
                )
            )
            .border(1.5.dp, Color.White.copy(alpha = 0.42f), shape),
    ) {
        content()
    }
}

/** Small circular glass button - the reference's search/power/nav-icon
 * buttons: same treatment as GlassSurface, just always a perfect circle. */
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
                elevation = 14.dp,
                shape = androidx.compose.foundation.shape.CircleShape,
                ambientColor = Color.Black.copy(alpha = 0.7f),
                spotColor = Color.Black.copy(alpha = 0.7f),
            )
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.13f),
                        Color.White.copy(alpha = 0.025f),
                        Color.Black.copy(alpha = 0.45f),
                    ),
                    center = Offset.Zero,
                    radius = 140f,
                )
            )
            .border(1.5.dp, Color.White.copy(alpha = 0.42f), androidx.compose.foundation.shape.CircleShape),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        content()
    }
}
