package dev.ajithv.triptracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.pow

/** A small map scale bar (bottom-left overlay). Fed metres-per-pixel from the active map engine; picks the
 *  largest 1/2/5·10ⁿ-metre length that fits and draws a labelled bar. Non-clickable so map gestures pass
 *  through. Works for both the MapLibre and Google engines (each supplies metersPerPixel). */
class ScaleBarView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val d = resources.displayMetrics.density
    private val maxBarPx = 120f * d
    private var mpp: Double? = null

    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f * d; color = 0x99000000.toInt()
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; color = Color.WHITE
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 12f * d; setShadowLayer(3f * d, 0f, 0f, 0xCC000000.toInt())
    }

    fun setMpp(v: Double?) { if (v != mpp) { mpp = v; invalidate() } }

    private class Nice(val px: Float, val label: String)
    private fun nice(): Nice? {
        val m = mpp ?: return null
        if (m <= 0 || m.isNaN() || m.isInfinite()) return null
        val maxMeters = maxBarPx * m
        if (maxMeters < 1) return null
        var pick = 1.0
        outer@ for (e in 0..8) for (mult in listOf(1.0, 2.0, 5.0)) {
            val c = mult * 10.0.pow(e)
            if (c <= maxMeters) pick = c else break@outer
        }
        val px = (pick / m).toFloat()
        val label = if (pick >= 1000) {
            val km = pick / 1000.0
            (if (km == km.toLong().toDouble()) km.toLong().toString() else km.toString()) + " km"
        } else "${pick.toLong()} m"
        return Nice(px, label)
    }

    override fun onDraw(c: Canvas) {
        val n = nice() ?: return
        val baseY = height - 6f * d
        val x0 = 4f * d
        val x1 = x0 + n.px
        for (p in listOf(shadow, line)) {
            c.drawLine(x0, baseY, x1, baseY, p)
            c.drawLine(x0, baseY, x0, baseY - 6f * d, p)
            c.drawLine(x1, baseY, x1, baseY - 6f * d, p)
        }
        c.drawText(n.label, x0, baseY - 9f * d, txt)
    }
}
