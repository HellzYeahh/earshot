package dev.earshot.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The backdrop behind every screen: a faint grid of dots, like the panel of a field recorder.
 * Drawn as one `drawPoints` call from a list cached per size, so scrolling costs nothing extra.
 */
fun Modifier.dotGrid(color: Color, spacing: Dp = 14.dp, dot: Dp = 2.dp): Modifier = drawWithCache {
    val step = spacing.toPx()
    // Read before buildList: inside it `size` would be the list's count.
    val width = size.width
    val height = size.height
    val points = buildList {
        var y = step / 2
        while (y < height) {
            var x = step / 2
            while (x < width) {
                add(Offset(x, y))
                x += step
            }
            y += step
        }
    }
    val diameter = dot.toPx()
    onDrawBehind {
        drawPoints(points, PointMode.Points, color, strokeWidth = diameter, cap = StrokeCap.Round)
    }
}
