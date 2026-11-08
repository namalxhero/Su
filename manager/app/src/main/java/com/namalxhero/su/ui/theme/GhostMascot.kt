package com.namalxhero.su.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Su's own mascot - a small ghost, not a copy of any other root
 * manager's character. Entirely Canvas-drawn (no image/vector asset),
 * so its colour follows app state directly instead of needing separate
 * light/dark or status-coloured art files.
 *
 * "Live" / idle-animated per the redesign ask: gently floats up and
 * down on an infinite loop, and its glow pulses - cheap, GPU-light
 * animation (two interpolated floats), not a sprite/frame animation.
 *
 * @param state drives both colour and (loosely) "expression" - a flat
 * mouth when [GhostState.WORKING], a small worried "o" mouth otherwise.
 */
enum class GhostState { WORKING, NOT_WORKING, CHECKING }

@Composable
fun GhostMascot(state: GhostState, modifier: Modifier = Modifier, size: Dp = 96.dp) {
    val infinite = rememberInfiniteTransition(label = "ghost")
    val floatY by infinite.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "floatY",
    )
    val glowPulse by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow",
    )

    val bodyColor = when (state) {
        GhostState.WORKING -> SuGreen
        GhostState.NOT_WORKING -> SuRed
        GhostState.CHECKING -> SuGrey
    }

    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val bob = (floatY / 100f) * h // tiny vertical drift, ~1% of the icon's own height

        // soft glow behind the ghost - a few concentric low-alpha circles
        // rather than a real blur (no extra blur dependency in this
        // project - see ui/theme/GlassComponents.kt's note on the same
        // tradeoff).
        val glowCenter = Offset(w / 2f, h / 2f + bob)
        for (i in 3 downTo 1) {
            drawCircle(
                color = bodyColor.copy(alpha = (glowPulse * 0.12f) / i),
                radius = (w / 2.1f) * (1f + i * 0.18f),
                center = glowCenter,
            )
        }

        // body: rounded dome top + three-bump wavy bottom, classic ghost
        // silhouette, drawn as one filled path.
        val left = w * 0.18f
        val right = w * 0.82f
        val top = h * 0.14f + bob
        val bottomY = h * 0.86f + bob
        val bodyW = right - left

        val path = Path().apply {
            moveTo(left, bottomY)
            // dome
            cubicTo(left, top, right, top, right, bottomY)
            // wavy hem - three scallops back to the left edge
            val scallop = bodyW / 3f
            relativeLineTo(0f, 0f)
            lineTo(right - scallop * 0.1f, bottomY)
            quadraticTo(right - scallop * 0.5f, bottomY + h * 0.08f, right - scallop, bottomY)
            quadraticTo(right - scallop * 1.5f, bottomY + h * 0.08f, right - scallop * 2f, bottomY)
            quadraticTo(right - scallop * 2.5f, bottomY + h * 0.08f, left, bottomY)
            close()
        }
        drawPath(path = path, color = bodyColor, style = Fill)

        // eyes
        val eyeY = top + (bottomY - top) * 0.42f
        val eyeOffset = bodyW * 0.17f
        val eyeRadius = w * 0.045f
        drawCircle(color = Color(0xFF0D0D0D), radius = eyeRadius, center = Offset(w / 2f - eyeOffset, eyeY))
        drawCircle(color = Color(0xFF0D0D0D), radius = eyeRadius, center = Offset(w / 2f + eyeOffset, eyeY))

        // mouth: flat contented line when working, small round "o" otherwise
        val mouthY = eyeY + h * 0.11f
        if (state == GhostState.WORKING) {
            drawLine(
                color = Color(0xFF0D0D0D),
                start = Offset(w / 2f - w * 0.06f, mouthY),
                end = Offset(w / 2f + w * 0.06f, mouthY),
                strokeWidth = w * 0.025f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        } else {
            drawCircle(color = Color(0xFF0D0D0D), radius = w * 0.035f, center = Offset(w / 2f, mouthY))
        }
    }
}
