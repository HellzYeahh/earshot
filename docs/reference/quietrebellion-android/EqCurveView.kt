/*
 * EqCurveView.kt – Draggable 3-band EQ curve (Bass / Mid / Treble).
 */
package net.quietrebellion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

class EqCurveView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null,
) : View(ctx, attrs) {

    private val values = IntArray(3) { 0 }
    private val mins   = IntArray(3) { -10 }
    private val maxs   = IntArray(3) { 10 }

    var onBandChanged: ((bandId: Int, value: Int) -> Unit)? = null
    var onBandReleased: ((bandId: Int, value: Int) -> Unit)? = null

    fun setBand(bandId: Int, value: Int, min: Int, max: Int) {
        values[bandId] = value.coerceIn(min, max)
        mins[bandId]   = min
        maxs[bandId]   = max
        invalidate()
    }

    // ── Paints ────────────────────────────────────────────────────────────────

    private val dp = ctx.resources.displayMetrics.density

    private val curveColor = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
    private val fanColor   = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOutlineVariant, Color.LTGRAY)
    private val dotColor   = MaterialColors.getColor(ctx, android.R.attr.colorPrimary, Color.BLUE)

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = curveColor; strokeWidth = 4f * dp; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val zeroLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = curveColor; strokeWidth = 1.5f * dp; style = Paint.Style.STROKE; alpha = 35
    }
    // One line per dB step; ceiling = 20 lines max (range ±10)
    private val fanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fanColor; strokeWidth = 1f * dp; style = Paint.Style.STROKE
    }
    private val handleFillPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dotColor; style = Paint.Style.FILL }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dotColor; strokeWidth = 1.5f * dp; style = Paint.Style.STROKE
    }

    // ── Geometry ──────────────────────────────────────────────────────────────

    private val PAD = 36f
    private val R   = 22f
    private val TR  = 52f
    private var drag = -1

    private fun xAt(i: Int) = width * (i * 2 + 1) / 6f

    private fun yAt(i: Int): Float {
        val norm = (values[i] - mins[i]).toFloat() / (maxs[i] - mins[i])
        return (height - PAD) - norm * (height - 2 * PAD)
    }

    private fun yZeroFor(i: Int): Float {
        val norm = (0 - mins[i]).toFloat() / (maxs[i] - mins[i])
        return (height - PAD) - norm * (height - 2 * PAD)
    }

    private fun valAt(i: Int, y: Float): Int {
        val norm = 1f - (y - PAD) / (height - 2 * PAD)
        return (mins[i] + norm * (maxs[i] - mins[i])).roundToInt().coerceIn(mins[i], maxs[i])
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val p = Array(3) { PointF(xAt(it), yAt(it)) }

        // Zero baseline
        canvas.drawLine(0f, yZeroFor(0), w, yZeroFor(0), zeroLinePaint)

        // Shadow curves – copies of the Bezier interpolated from zero toward the main curve,
        // one per dB step. Symmetric range → yZero is identical for all three bands.
        val maxSteps = values.map { abs(it) }.maxOrNull() ?: 0
        if (maxSteps > 0) {
            val yZ = yZeroFor(0)
            for (j in 1 until maxSteps) {
                val t = j.toFloat() / maxSteps
                val sp = Array(3) { i -> PointF(p[i].x, yZ + t * (p[i].y - yZ)) }
                val stx = floatArrayOf(sp[1].x - sp[0].x, (sp[2].x - sp[0].x) / 2f, sp[2].x - sp[1].x)
                val sty = floatArrayOf(sp[1].y - sp[0].y, (sp[2].y - sp[0].y) / 2f, sp[2].y - sp[1].y)
                val shadow = Path().apply {
                    moveTo(0f, sp[0].y)
                    lineTo(sp[0].x, sp[0].y)
                    for (seg in 0..1) cubicTo(
                        sp[seg].x   + stx[seg]   / 3f, sp[seg].y   + sty[seg]   / 3f,
                        sp[seg+1].x - stx[seg+1] / 3f, sp[seg+1].y - sty[seg+1] / 3f,
                        sp[seg+1].x, sp[seg+1].y,
                    )
                    lineTo(w, sp[2].y)
                }
                canvas.drawPath(shadow, fanPaint)
            }
        }

        // EQ curve (Catmull-Rom) – extends flat to both edges
        val tx = floatArrayOf(p[1].x - p[0].x, (p[2].x - p[0].x) / 2f, p[2].x - p[1].x)
        val ty = floatArrayOf(p[1].y - p[0].y, (p[2].y - p[0].y) / 2f, p[2].y - p[1].y)
        val path = Path().apply {
            moveTo(0f, p[0].y)
            lineTo(p[0].x, p[0].y)
            for (seg in 0..1) cubicTo(
                p[seg].x   + tx[seg]   / 3f, p[seg].y   + ty[seg]   / 3f,
                p[seg+1].x - tx[seg+1] / 3f, p[seg+1].y - ty[seg+1] / 3f,
                p[seg+1].x, p[seg+1].y,
            )
            lineTo(w, p[2].y)
        }
        canvas.drawPath(path, curvePaint)

        // Primary-color handles
        for (pt in p) {
            canvas.drawCircle(pt.x, pt.y, R, handleFillPaint)
            canvas.drawCircle(pt.x, pt.y, R, handleStrokePaint)
        }
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                var best = TR; drag = -1
                for (i in 0..2) {
                    val dx = e.x - xAt(i); val dy = e.y - yAt(i)
                    val d = sqrt(dx * dx + dy * dy)
                    if (d < best) { best = d; drag = i }
                }
                if (drag >= 0) parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (drag >= 0) {
                values[drag] = valAt(drag, e.y)
                onBandChanged?.invoke(drag, values[drag])
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (drag >= 0) {
                onBandReleased?.invoke(drag, values[drag])
                parent.requestDisallowInterceptTouchEvent(false)
                drag = -1
            }
        }
        return drag >= 0 || e.action == MotionEvent.ACTION_DOWN
    }
}
