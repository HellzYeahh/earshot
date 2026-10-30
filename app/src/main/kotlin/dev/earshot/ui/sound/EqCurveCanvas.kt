package dev.earshot.ui.sound

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.earshot.R
import kotlin.math.abs
import kotlin.math.roundToInt

private const val RANGE_DB = 15f
private val GRID_HZ = listOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0)
private val GRID_DB = listOf(-12.0, -6.0, 6.0, 12.0)
private val LABEL_HZ = listOf(50.0, 200.0, 1000.0, 5000.0, 10000.0)

/**
 * Frequency response of the Pro EQ: log-frequency x axis 20 Hz..20 kHz, ±15 dB y axis.
 * Band centres are marked as dots so the curve reads back onto the sliders below it.
 *
 * Two cached draw layers. The grid and the axis labels depend only on the size and the colours,
 * so a slider drag never rebuilds them; the curve layer rebuilds its paths only when [curve]
 * changes. Before, every draw made a new dash effect, label Paint, both paths and the gradient,
 * and one more EqResponse per band dot. The numbers themselves come ready from [EqCurveData].
 */
@Composable
fun EqCurveCanvas(
    curve: EqCurveData,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val curveColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val zeroColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    // The axis labels are values, so the bundled mono face (the native Paint can't read Compose fonts).
    val context = LocalContext.current
    val labelTypeface = remember(context) { runCatching { ResourcesCompat.getFont(context, R.font.jetbrains_mono) }.getOrNull() }
    val description = stringResource(R.string.sound_curve_description)

    // Below the curve: grid, 0 dB line. Above it: the frequency labels. Same order as one canvas drew them.
    val frameLayer = remember(gridColor, zeroColor, labelColor, labelTypeface) {
        Modifier.drawWithCache {
            val plot = Plot(this, size)
            val w = size.width
            val stroke = 1.dp.toPx()
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx()))
            val gridX = GRID_HZ.map { EqCurve.xFraction(it) * w }
            val gridY = GRID_DB.map { plot.yFor(it) }
            val zeroY = plot.yFor(0.0)
            val zeroStroke = 1.5.dp.toPx()
            // Frequency labels along the bottom (native text keeps this dependency-free).
            val paint = android.graphics.Paint().apply {
                color = labelColor
                textSize = 10.sp.toPx()
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
                labelTypeface?.let { typeface = it }
            }
            val labels = LABEL_HZ.map { formatHz(it) to EqCurve.xFraction(it) * w }
            val labelY = size.height - 2.dp.toPx()
            onDrawWithContent {
                // Grid: vertical lines at round frequencies, horizontal at ±6 and ±12 dB.
                for (x in gridX) drawLine(gridColor, Offset(x, 0f), Offset(x, plot.plotHeight), strokeWidth = stroke, pathEffect = dash)
                for (y in gridY) drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = stroke, pathEffect = dash)
                drawLine(zeroColor, Offset(0f, zeroY), Offset(w, zeroY), strokeWidth = zeroStroke)
                drawContent()
                for ((text, x) in labels) drawContext.canvas.nativeCanvas.drawText(text, x, labelY, paint)
            }
        }
    }
    val curveLayer = remember(curve, curveColor) {
        Modifier.drawWithCache {
            val plot = Plot(this, size)
            val w = size.width
            val fillColor = curveColor.copy(alpha = 0.18f)
            // Curve + translucent fill towards 0 dB.
            val paths = if (curve.samples.size >= 2) {
                val line = Path()
                val fill = Path()
                val zeroY = plot.yFor(0.0)
                curve.samples.forEachIndexed { i, (hz, db) ->
                    val x = EqCurve.xFraction(hz) * w
                    val y = plot.yFor(db)
                    if (i == 0) { line.moveTo(x, y); fill.moveTo(x, zeroY); fill.lineTo(x, y) } else { line.lineTo(x, y); fill.lineTo(x, y) }
                }
                fill.lineTo(w, zeroY)
                fill.close()
                line to fill
            } else {
                null
            }
            val fillBrush = Brush.verticalGradient(listOf(fillColor, fillColor.copy(alpha = 0.04f)))
            val lineStyle = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            // Band markers.
            val dots = curve.markers.map { (hz, db) -> Offset(EqCurve.xFraction(hz) * w, plot.yFor(db)) }
            val dotRadius = 4.dp.toPx()
            onDrawBehind {
                paths?.let { (line, fill) ->
                    drawPath(fill, fillBrush)
                    drawPath(line, curveColor, style = lineStyle)
                }
                for (dot in dots) drawCircle(curveColor, radius = dotRadius, center = dot)
            }
        }
    }

    Spacer(
        modifier
            .fillMaxWidth()
            .height(160.dp)
            .semantics { contentDescription = description }
            .then(frameLayer)
            .then(curveLayer),
    )
}

/** The vertical mapping both layers share: ±[RANGE_DB] over the area above the 16 dp label strip. */
private class Plot(density: Density, size: Size) {
    val plotHeight: Float = size.height - with(density) { 16.dp.toPx() }
    private val inset: Float = with(density) { 4.dp.toPx() }

    fun yFor(db: Double): Float = (plotHeight / 2f) - (db.toFloat().coerceIn(-RANGE_DB, RANGE_DB) / RANGE_DB) * (plotHeight / 2f - inset)
}

/** "50", "1k", "16k" — compact axis and slider labels. */
fun formatHz(hz: Double): String = when {
    hz >= 1000 -> {
        val k = hz / 1000
        if (abs(k - k.roundToInt()) < 0.05) "${k.roundToInt()}k" else String.format(java.util.Locale.US, "%.1fk", k)
    }
    else -> hz.roundToInt().toString()
}
