package dev.ajithv.triptracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** A pan/pinch-zoomable line chart: y vs x (x = cumulative distance in metres). Draws graduations with
 *  thin (1 px) grid lines + labels on BOTH axes, which rescale as you zoom. Pinch to zoom (both axes),
 *  drag to pan, double-tap or [fit] to reset. New data keeps the user's zoom unless the view is at fit. */
class ChartView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var title = ""
    private var unit = ""
    private val d = resources.displayMetrics.density

    private var dxMin = 0f; private var dxMax = 1f; private var dyMin = 0f; private var dyMax = 1f   // data bounds
    private var vx0 = 0f; private var vx1 = 1f; private var vy0 = 0f; private var vy1 = 1f           // visible range
    private var userZoomed = false
    private var colStored = Color.parseColor("#4da3ff")
    var onMaximize: (() -> Unit)? = null
    var showMaximize = true

    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; strokeJoin = Paint.Join.ROUND }   // 2 px line
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pGrid = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = 0x33ffffff; isAntiAlias = false }   // 1 px graduations
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff9aa4b2.toInt(); textSize = 10.5f * d }
    private val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12.5f * d; isFakeBoldText = true }
    private val pMax = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * d; color = Color.WHITE }
    private val pMaxBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x66000000 }

    private val scaleDet = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(dt: ScaleGestureDetector): Boolean {
            zoomAround(dt.focusX, dt.focusY, dt.scaleFactor); return true
        }
    })
    private val gestureDet = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dxp: Float, dyp: Float): Boolean { pan(dxp, dyp); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { fit(); return true }
    })

    fun setData(xs: FloatArray, ys: FloatArray, title: String, unit: String, color: Int) {
        this.xs = xs; this.ys = ys; this.title = title; this.unit = unit
        colStored = color; pLine.color = color; pFill.color = (color and 0x00ffffff) or 0x33000000
        if (xs.isNotEmpty()) {
            dxMin = xs[0]; dxMax = xs[0]
            for (v in xs) { if (v < dxMin) dxMin = v; if (v > dxMax) dxMax = v }
            var first = true                       // y bounds ignore NaN (gaps / removed outliers)
            for (v in ys) { if (v.isNaN()) continue; if (first) { dyMin = v; dyMax = v; first = false } else { if (v < dyMin) dyMin = v; if (v > dyMax) dyMax = v } }
            if (first) { dyMin = 0f; dyMax = 1f }
            if (dxMax - dxMin < 1e-3f) dxMax = dxMin + 1f
            val pad = (dyMax - dyMin) * 0.08f + 0.5f; dyMin -= pad; dyMax += pad
        }
        if (!userZoomed) fitRange()
        invalidate()
    }
    fun fit() { userZoomed = false; fitRange(); invalidate() }
    private fun fitRange() { vx0 = dxMin; vx1 = dxMax; vy0 = dyMin; vy1 = dyMax }
    fun cloneInto(t: ChartView) { t.setData(xs.copyOf(), ys.copyOf(), title, unit, colStored) }

    private fun maxRect(): RectF { val sz = 26f * d; val m = 3f * d; return RectF(width - sz - m, m, width - m, sz + m) }

    private fun plotL() = 38f * d
    private fun plotR() = 8f * d
    private fun plotT() = 22f * d
    private fun plotB() = 16f * d

    private fun zoomAround(fx: Float, fy: Float, factor: Float) {
        userZoomed = true
        val pl = plotL(); val pt = plotT(); val pw = width - pl - plotR(); val ph = height - pt - plotB()
        if (pw <= 0 || ph <= 0) return
        val fxr = ((fx - pl) / pw).coerceIn(0f, 1f)   // focus as a fraction of the plot (x: 0 left→1 right)
        val fyr = ((fy - pt) / ph).coerceIn(0f, 1f)   // y: 0 top→1 bottom (screen); data is inverted
        val dataFx = vx0 + fxr * (vx1 - vx0)
        val dataFy = vy1 - fyr * (vy1 - vy0)
        val spanX = ((vx1 - vx0) / factor).coerceIn((dxMax - dxMin) * 0.02f, dxMax - dxMin)
        val spanY = ((vy1 - vy0) / factor).coerceIn((dyMax - dyMin) * 0.02f, dyMax - dyMin)
        // keep the focus data-point under the fingers: solve so screenX(dataFx)=fx and screenY(dataFy)=fy
        vx0 = dataFx - fxr * spanX; vx1 = vx0 + spanX
        vy0 = dataFy - (1f - fyr) * spanY; vy1 = vy0 + spanY
        clampView(); invalidate()
    }
    private fun pan(dxp: Float, dyp: Float) {
        userZoomed = true
        val pl = plotL(); val pt = plotT(); val pw = width - pl - plotR(); val ph = height - pt - plotB()
        if (pw <= 0 || ph <= 0) return
        val sx = dxp / pw * (vx1 - vx0); val sy = dyp / ph * (vy1 - vy0)
        vx0 += sx; vx1 += sx; vy0 -= sy; vy1 -= sy   // drag-left reveals later; drag-up moves content up
        clampView(); invalidate()
    }
    private fun clampView() {
        val spanX = vx1 - vx0; val spanY = vy1 - vy0
        if (vx0 < dxMin) { vx0 = dxMin; vx1 = dxMin + spanX }
        if (vx1 > dxMax) { vx1 = dxMax; vx0 = dxMax - spanX }
        if (vy0 < dyMin) { vy0 = dyMin; vy1 = dyMin + spanY }
        if (vy1 > dyMax) { vy1 = dyMax; vy0 = dyMax - spanY }
    }

    private fun niceStep(span: Float, target: Int): Float {
        if (span <= 0f) return 1f
        val raw = span / target
        val mag = 10.0.pow(floor(log10(raw.toDouble()))).toFloat()
        val n = raw / mag
        val f = if (n < 1.5f) 1f else if (n < 3f) 2f else if (n < 7f) 5f else 10f
        return f * mag
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val pl = plotL(); val pr = plotR(); val pt = plotT(); val pb = plotB()
        val pw = w - pl - pr; val ph = h - pt - pb
        c.drawText(title, 2f * d, 15f * d, pTitle)
        if (xs.size < 2 || pw <= 0 || ph <= 0) { c.drawText("no data", pl, h / 2f, pTxt); return }
        fun px(x: Float) = pl + (x - vx0) / (vx1 - vx0) * pw
        fun py(y: Float) = pt + (1f - (y - vy0) / (vy1 - vy0)) * ph
        // graduations (both axes) — thin 1 px lines spanning the full plot + labels
        var lastRight = -1e9f   // skip an x-label that would overlap the previous one
        val xStep = niceStep(vx1 - vx0, 5); var tx = ceil(vx0 / xStep) * xStep; var guard = 0
        while (tx <= vx1 && guard++ < 200) {
            val x = px(tx); c.drawLine(x, pt, x, pt + ph, pGrid)
            val lbl = Geo.fmtDist(tx.toDouble()); val lx = x + 2f * d; val lw = pTxt.measureText(lbl)
            if (lx > lastRight + 5f * d) { c.drawText(lbl, lx, pt + ph + 12f * d, pTxt); lastRight = lx + lw }
            tx += xStep
        }
        val yStep = niceStep(vy1 - vy0, 4); var ty = ceil(vy0 / yStep) * yStep; guard = 0
        while (ty <= vy1 && guard++ < 200) {
            val y = py(ty); c.drawLine(pl, y, pl + pw, y, pGrid)
            c.drawText("${Math.round(ty)}$unit", 2f * d, y - 2f * d, pTxt); ty += yStep
        }
        // data line (+ soft fill), per contiguous segment (NaN gaps break the line), clipped to the plot
        c.save(); c.clipRect(pl, pt, pl + pw, pt + ph)
        var i = 0
        while (i < xs.size) {
            if (ys[i].isNaN()) { i++; continue }
            val seg = Path(); val fil = Path()
            val x0 = px(xs[i]); seg.moveTo(x0, py(ys[i])); fil.moveTo(x0, pt + ph); fil.lineTo(x0, py(ys[i]))
            var j = i
            while (j < xs.size && !ys[j].isNaN()) { val x = px(xs[j]); seg.lineTo(x, py(ys[j])); fil.lineTo(x, py(ys[j])); j++ }
            fil.lineTo(px(xs[j - 1]), pt + ph); fil.close()
            c.drawPath(fil, pFill); c.drawPath(seg, pLine)
            i = j
        }
        c.restore()
        // maximize button (top-right corner): a rounded badge with two expand brackets
        if (showMaximize) {
            val mr = maxRect(); c.drawRoundRect(mr, 4f * d, 4f * d, pMaxBg)
            val ins = 7f * d; val len = 6f * d
            c.drawLine(mr.left + ins, mr.top + ins, mr.left + ins + len, mr.top + ins, pMax)
            c.drawLine(mr.left + ins, mr.top + ins, mr.left + ins, mr.top + ins + len, pMax)
            c.drawLine(mr.right - ins, mr.bottom - ins, mr.right - ins - len, mr.bottom - ins, pMax)
            c.drawLine(mr.right - ins, mr.bottom - ins, mr.right - ins, mr.bottom - ins - len, pMax)
        }
    }

    private var downOnIcon = false; private var dX = 0f; private var dY = 0f
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dX = ev.x; dY = ev.y
                downOnIcon = showMaximize && maxRect().contains(ev.x, ev.y)
                if (downOnIcon) return true   // starting on the maximize icon → not a pan/zoom
            }
            MotionEvent.ACTION_UP -> if (downOnIcon) {
                val tap = abs(ev.x - dX) < 24f && abs(ev.y - dY) < 24f
                downOnIcon = false
                if (tap) onMaximize?.invoke()
                return true
            }
        }
        if (!downOnIcon) { scaleDet.onTouchEvent(ev); gestureDet.onTouchEvent(ev) }
        return true
    }
}
