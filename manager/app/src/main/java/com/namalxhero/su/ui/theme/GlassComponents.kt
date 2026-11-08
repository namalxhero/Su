package com.namalxhero.su.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * Su's own "liquid glass" look - NOT a reproduction of any other root
 * manager's UI, built from Compose primitives this project already
 * depends on (no new dependency, no blur library): a dark dotted-grid
 * backdrop with a couple of slowly-pulsing soft colour blobs behind it,
 * and translucent bordered cards floating on top. True backdrop blur
 * (seeing the grid genuinely blurred *through* a card) needs either API
 * 31+'s RenderEffect wired through an extra Compose integration layer
 * or a third-party blur library - skipped here to avoid that dependency
 * weight; the glass *look* (translucency + soft glow + rounded border)
 * is approximated instead, which is what most of this redesign actually
 * asked for.
 */

@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val infinite = rememberInfiniteTransition(label = "glassGlow")
    val glowPulse by infinite.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4000), repeatMode = RepeatMode.Reverse),
        label = "glowPulse",
    )

    Box(modifier = modifier.fillMaxSize().background(SuBlack)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // two soft colour blobs (brand green + a cool blue accent),
            // fixed positions, pulsing opacity only - cheap and avoids
            // any layout recomposition cost since nothing moves.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(SuGreen.copy(alpha = 0.10f * glowPulse), Color.Transparent),
                ),
                radius = size.width * 0.55f,
                center = Offset(size.width * 0.15f, size.height * 0.1f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF2979FF).copy(alpha = 0.08f * glowPulse), Color.Transparent),
                ),
                radius = size.width * 0.6f,
                center = Offset(size.width * 0.9f, size.height * 0.75f),
            )

            // sparse dot grid, deterministic (fixed seed) so it doesn't
            // reshuffle on every recomposition.
            val rng = Random(42)
            val spacing = 48.dp.toPx()
            var y = 0f
            while (y < size.height) {
                var x = 0f
                while (x < size.width) {
                    if (rng.nextFloat() < 0.35f) {
                        val jitterX = (rng.nextFloat() - 0.5f) * spacing * 0.6f
                        val jitterY = (rng.nextFloat() - 0.5f) * spacing * 0.6f
                        drawCircle(
                            color = SuGrey.copy(alpha = 0.18f),
                            radius = 1.6.dp.toPx(),
                            center = Offset(x + jitterX, y + jitterY),
                        )
                    }
                    x += spacing
                }
                y += spacing
            }
        }
        content()
    }
}

/** A translucent, bordered, rounded surface - the "glass" building
 * block every redesigned card/tile/nav-bar in this pass uses, so the
 * look stays consistent instead of each screen inventing its own card
 * style (which is roughly what happened before this pass - plain
 * CardDefaults.cardColors(SuSurfaceVariant) everywhere, flat not
 * "glass"). */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = 20.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(cornerRadius)),
    ) {
        content()
    }
}
