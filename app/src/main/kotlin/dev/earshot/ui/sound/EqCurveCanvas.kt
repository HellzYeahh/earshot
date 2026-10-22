package dev.earshot.ui.sound

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.earshot.R
import dev.earshot.dsp.ParametricBand
import kotlin.math.abs
import kotlin.math.roundToInt

private const val RANGE_DB = 15f
private val GRID_HZ = listOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0)

/**
 * Frequency response of the Pro EQ: log-frequency x axis 20 Hz..20 kHz, ±15 dB y axis.
 * Band centres are marked as dots so the curve reads back onto the sliders below it.
 */
@Composable
fun EqCurveCanvas(
    bands: List<ParametricBand>,
    preampDb: Double,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val curveColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val fillColor = curveColor.copy(alpha = 0.18f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val zeroColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    // The axis labels are values, so the bundled mono face (the native Paint can't read Compose fonts).
    val context = LocalContext.current
    val labelTypeface = remember(context) { runCatching { ResourcesCompat.getFont(context, R.font.jetbrains_mono) }.getOrNull() }
    val samples = remember(bands, preampDb) { EqCurve.sample(bands, preampDb) }
    val description = stringResource(R.string.sound_curve_description)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(160.dp)
            .semantics { contentDescription = description },
    ) {
        val w = size.width
        val h = size.height
        val bottomPad = 16.dp.toPx()
        val plotH = h - bottomPad
        fun yFor(db: Double): Float = (plotH / 2f) - (db.toFloat().coerceIn(-RANGE_DB, RANGE_DB) / RANGE_DB) * (plotH / 2f - 4.dp.toPx())

        // Grid: vertical lines at round frequencies, horizontal at ±6 and ±12 dB.
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx()))
        for (hz in GRID_HZ) {
            val x = EqCurve.xFraction(hz) * w
            drawLine(gridColor, Offset(x, 0f), Offset(x, plotH), strokeWidth = 1.dp.toPx(), pathEffect = dash)
        }
        for (db in listOf(-12.0, -6.0, 6.0, 12.0)) {
            drawLine(gridColor, Offset(0f, yFor(db)), Offset(w, yFor(db)), strokeWidth = 1.dp.toPx(), pathEffect = dash)
        }
        drawLine(zeroColor, Offset(0f, yFor(0.0)), Offset(w, yFor(0.0)), strokeWidth = 1.5.dp.toPx())

        // Curve + translucent fill towards 0 dB.
        if (samples.size >= 2) {
            val line = Path()
            val fill = Path()
            samples.forEachIndexed { i, (hz, db) ->
                val x = EqCurve.xFraction(hz) * w
                val y = yFor(db)
                if (i == 0) { line.moveTo(x, y); fill.moveTo(x, yFor(0.0)); fill.lineTo(x, y) } else { line.lineTo(x, y); fill.lineTo(x, y) }
            }
            fill.lineTo(w, yFor(0.0))
            fill.close()
            drawPath(fill, Brush.verticalGradient(listOf(fillColor, fillColor.copy(alpha = 0.04f))))
            drawPath(line, curveColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        }

        // Band markers.
        bands.filter { it.enabled }.forEach { band ->
            val x = EqCurve.xFraction(band.frequencyHz) * w
            val y = yFor(EqCurve.magnitudeDb(bands, preampDb, band.frequencyHz))
            drawCircle(curveColor, radius = 4.dp.toPx(), center = Offset(x, y))
        }

        // Frequency labels along the bottom (native text keeps this dependency-free).
        val paint = android.graphics.Paint().apply {
            color = labelColor
            textSize = 10.sp.toPx()
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            labelTypeface?.let { typeface = it }
        }
        for (hz in listOf(50.0, 200.0, 1000.0, 5000.0, 10000.0)) {
            val x = EqCurve.xFraction(hz) * w
            drawContext.canvas.nativeCanvas.drawText(formatHz(hz), x, h - 2.dp.toPx(), paint)
        }
    }
}

/** "50", "1k", "16k" — compact axis and slider labels. */
fun formatHz(hz: Double): String = when {
    hz >= 1000 -> {
        val k = hz / 1000
        if (abs(k - k.roundToInt()) < 0.05) "${k.roundToInt()}k" else String.format(java.util.Locale.US, "%.1fk", k)
    }
    else -> hz.roundToInt().toString()
}
