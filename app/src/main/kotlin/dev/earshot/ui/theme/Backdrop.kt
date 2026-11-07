package dev.earshot.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** One soft colour glow of a backdrop: a radial fade from [color] at ([x], [y]) (fractions of the screen). */
@Immutable
data class BackdropGlow(val color: Color, val x: Float, val y: Float, val radius: Dp)

/**
 * What the root draws behind every screen, after the plain background colour: Field's dot grid,
 * or Aurora's corner glows. Drawn once, under everything, so scrolling costs nothing extra.
 */
@Composable
fun Modifier.earshotBackdrop(): Modifier {
    val colors = EarshotTheme.colors
    return when (EarshotTheme.style.backdrop) {
        EarshotStyle.Backdrop.DOTS -> dotGrid(colors.dotGrid)
        // Remembered like DotGrid's element, so a recomposition of the root keeps the cached brushes.
        EarshotStyle.Backdrop.GLOW -> this then remember(colors.glows) { Modifier.glowBackdrop(colors.glows) }
    }
}

/** Radial glows fading to transparent at 70 % of their radius, drawn in order (later ones on top). */
fun Modifier.glowBackdrop(glows: List<BackdropGlow>): Modifier = drawWithCache {
    val brushes = glows.map { glow ->
        Brush.radialGradient(
            // Fade to the same colour at zero alpha: fading to transparent black would grey the edge.
            colorStops = arrayOf(0f to glow.color, 0.7f to glow.color.copy(alpha = 0f)),
            center = Offset(glow.x * size.width, glow.y * size.height),
            radius = glow.radius.toPx(),
        )
    }
    onDrawBehind { brushes.forEach { drawRect(it) } }
}
