package dev.earshot.ui.theme

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The backdrop behind every screen: a faint grid of dots, like the panel of a field recorder.
 *
 * Drawn as ONE rectangle filled with a repeating one-dot tile (a bitmap shader), not one point
 * per dot. The old `drawPoints` list was about 3,000 anti-aliased ovals on a large unfolded phone
 * (2,000 on the cover screen) that the render thread drew again on every frame of every screen.
 * And because `drawWithCache` got a new lambda on every call, each recomposition of the root
 * rebuilt and re-recorded that list; the root recomposed on every headset push. This modifier is a
 * data-class element instead, so equal arguments keep the node and its tile as they are.
 *
 * The tile side is the spacing rounded to whole pixels (36.75 px becomes 37 px at 2.625x), so the
 * spacing moves by under 1 %. Every dot keeps the same diameter, colour and round anti-aliased
 * edge, and the first dot still sits half a step from the top-left corner.
 */
fun Modifier.dotGrid(color: Color, spacing: Dp = 14.dp, dot: Dp = 2.dp): Modifier =
    this then DotGridElement(color, spacing, dot)

private data class DotGridElement(val color: Color, val spacing: Dp, val dot: Dp) : ModifierNodeElement<DotGridNode>() {
    override fun create() = DotGridNode(color, spacing, dot)

    // Only called when an argument really changed (the element is a data class); Modifier.Node
    // then redraws by itself, and draw() builds a new tile because its key no longer matches.
    override fun update(node: DotGridNode) = node.update(color, spacing, dot)

    override fun InspectorInfo.inspectableProperties() {
        name = "dotGrid"
        properties["color"] = color
        properties["spacing"] = spacing
        properties["dot"] = dot
    }
}

private class DotGridNode(private var color: Color, private var spacing: Dp, private var dot: Dp) :
    Modifier.Node(), DrawModifierNode {

    private var cachedTile: DotTile? = null
    private var cachedBrush: ShaderBrush? = null

    fun update(color: Color, spacing: Dp, dot: Dp) {
        this.color = color
        this.spacing = spacing
        this.dot = dot
    }

    override fun ContentDrawScope.draw() {
        // Keyed on pixels as well as on the arguments: a density change (another screen) needs a new tile.
        val wanted = DotTile(DotTile.sidePx(spacing.toPx()), dot.toPx(), color)
        val brush = cachedBrush?.takeIf { cachedTile == wanted } ?: wanted.brush().also {
            cachedTile = wanted
            cachedBrush = it
        }
        drawRect(brush)
        drawContent()
    }

    override fun onDetach() {
        cachedTile = null
        cachedBrush = null
    }
}

/** One cell of the grid, in pixels: what the shader repeats. */
internal data class DotTile(val sidePx: Int, val dotPx: Float, val color: Color) {
    /** The dot sits in the middle of its cell, as the old grid put its first dot at half a step. */
    val centrePx: Float get() = sidePx / 2f

    fun brush(): ShaderBrush {
        val bitmap = Bitmap.createBitmap(sidePx, sidePx, Bitmap.Config.ARGB_8888)
        // A filled anti-aliased circle is what drawPoints with a round cap drew for each point.
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = this@DotTile.color.toArgb() }
        android.graphics.Canvas(bitmap).drawCircle(centrePx, centrePx, dotPx / 2f, paint)
        return ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
    }

    companion object {
        /** The spacing in whole pixels: a bitmap tile can't be 36.75 px wide. Never below one pixel. */
        fun sidePx(spacingPx: Float): Int = spacingPx.roundToInt().coerceAtLeast(1)
    }
}
