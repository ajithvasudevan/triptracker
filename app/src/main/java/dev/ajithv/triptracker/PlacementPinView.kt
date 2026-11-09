package dev.ajithv.triptracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** Screen-space preview of a geofence being placed: a fixed pin + alert circle drawn at the centre of the
 *  visible map area (above the bottom popup). It stays put on screen while the map pans beneath it; the circle's
 *  pixel radius is recomputed from metres/pixel so it keeps a fixed GROUND radius as you zoom.
 *
 *  A knob on the circle's east edge can be DRAGGED to resize the circle; a touch anywhere else returns false so
 *  it falls through to the map (pan/zoom). While dragging, [onRadiusDragPx] reports the new pixel radius. */
class PlacementPinView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val d = resources.displayMetrics.density
    var active = false
        set(v) { field = v; invalidate() }
    var radiusPx = 0f
        set(v) { field = v; invalidate() }
    var bottomInsetPx = 0f               // popup height → pin sits at the centre of the area ABOVE it
        set(v) { field = v; invalidate() }
    var onRadiusDragPx: ((Float) -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x4DFF0000 }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#8b0000"); strokeWidth = 2f * d }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#8b0000") }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 2f * d }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#8b0000"); strokeWidth = 2.5f * d }

    private val minRadiusPx = 12f * d
    private val handleR = 9f * d
    private val handleHitPx = 26f * d
    private var dragging = false

    private fun cx() = width / 2f
    private fun cy() = (height - bottomInsetPx) / 2f

    override fun onDraw(c: Canvas) {
        if (!active) return
        val cx = cx(); val cy = cy()
        if (radiusPx > 1f) {
            c.drawCircle(cx, cy, radiusPx, fill)
            c.drawCircle(cx, cy, radiusPx, stroke)
            c.drawCircle(cx + radiusPx, cy, handleR, handleFill)   // east-edge resize knob
            c.drawCircle(cx + radiusPx, cy, handleR, handleRing)
        }
        val r = 6f * d
        c.drawCircle(cx, cy, r, dotFill); c.drawCircle(cx, cy, r, dotRing)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!active) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hx = cx() + radiusPx; val hy = cy()
                if (hypot((e.x - hx).toDouble(), (e.y - hy).toDouble()) <= handleHitPx) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)   // keep the DrawerLayout from stealing the drag
                    return true
                }
                return false   // not on the knob → let the map handle the gesture (pan/zoom)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                var r = hypot((e.x - cx()).toDouble(), (e.y - cy()).toDouble()).toFloat()
                if (r < minRadiusPx) r = minRadiusPx
                radiusPx = r                 // setter invalidates → live resize
                onRadiusDragPx?.invoke(r)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) { dragging = false; return true }
        }
        return false
    }
}
