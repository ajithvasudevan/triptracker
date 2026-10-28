package dev.ajithv.triptracker

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.min

/** Pinch / drag / double-tap zoom-pan + horizontal swipe-to-navigate for a single photo.
 *  Swipe mirrors the web app: the current image follows the finger while a "ghost" of the neighbour
 *  slides in beside it with a small gap; past threshold it commits (ghost → centre, current → out),
 *  else it springs back. Pan only while zoomed; double-tap resets-to-fit when zoomed and (when
 *  doubleTapZoom) zooms to MAX toward the tap when at fit; single tap → onTap. */
class ZoomImageView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null)
    : AppCompatImageView(ctx, attrs) {

    var onTap: (() -> Unit)? = null
    var onDoubleTapAtFit: (() -> Unit)? = null
    var onSwipe: ((Int) -> Unit)? = null
    var doubleTapZoom = false
    var swipeEnabled = true
    var ghost: ImageView? = null
    var neighborBitmap: ((Int) -> Bitmap?)? = null   // cached bitmap of the neighbour in a direction, or null
    var gapPx = 0f

    private val MAXMULT = 6f
    private val m = Matrix()
    private var fitScale = 1f
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    private var lastX = 0f; private var lastY = 0f
    private var downX = 0f; private var downY = 0f
    private var multi = false
    private var swiping = false
    private var swipeStartX = 0f
    private var ghostDir = 0
    private var curDx = 0f
    private var rawDx = 0f     // raw finger displacement (undamped), used for the commit threshold
    private var cachedSpan = 0f

    init { scaleType = ScaleType.MATRIX }

    private val scaleDet = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val cur = curScale(); var f = d.scaleFactor
            val target = (cur * f).coerceIn(fitScale, fitScale * MAXMULT)
            f = if (cur == 0f) 1f else target / cur
            m.postScale(f, f, d.focusX, d.focusY); clamp(); imageMatrix = m
            return true
        }
    })

    private val gesture = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap?.invoke(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (curScale() > fitScale * 1.01f) animateToFit()
            else if (doubleTapZoom) animateScaleTo(fitScale * MAXMULT, e.x, e.y)
            else onDoubleTapAtFit?.invoke()
            return true
        }
    })

    override fun setImageBitmap(bm: Bitmap?) { super.setImageBitmap(bm); post { fitToView() } }
    override fun setImageDrawable(dr: android.graphics.drawable.Drawable?) { super.setImageDrawable(dr); post { fitToView() } }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fitToView() }

    fun reset() { translationX = 0f; fitToView() }

    private fun dw() = (drawable?.intrinsicWidth ?: 0).toFloat()
    private fun dh() = (drawable?.intrinsicHeight ?: 0).toFloat()
    private fun curScale(): Float { val v = FloatArray(9); m.getValues(v); return v[Matrix.MSCALE_X] }

    private fun fitToView() {
        val dw = dw(); val dh = dh()
        if (dw <= 0 || dh <= 0 || width == 0 || height == 0) return
        fitScale = min(width / dw, height / dh)
        m.reset(); m.postScale(fitScale, fitScale)
        m.postTranslate((width - dw * fitScale) / 2f, (height - dh * fitScale) / 2f)
        imageMatrix = m
    }

    private fun clamp() {
        val r = RectF(0f, 0f, dw(), dh()); m.mapRect(r)
        var dx = 0f; var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2f - r.left
        else { if (r.left > 0) dx = -r.left; if (r.right < width) dx = width - r.right }
        if (r.height() <= height) dy = (height - r.height()) / 2f - r.top
        else { if (r.top > 0) dy = -r.top; if (r.bottom < height) dy = height - r.bottom }
        m.postTranslate(dx, dy)
    }

    private fun animateToFit() {
        val from = FloatArray(9); m.getValues(from)
        val fit = Matrix().also { it.postScale(fitScale, fitScale); it.postTranslate((width - dw() * fitScale) / 2f, (height - dh() * fitScale) / 2f) }
        val to = FloatArray(9); fit.getValues(to); animateMatrix(from, to)
    }
    private fun animateScaleTo(target: Float, fx: Float, fy: Float) {
        val from = FloatArray(9); m.getValues(from)
        val end = Matrix(m); val cur = curScale(); val f = if (cur == 0f) 1f else target / cur
        end.postScale(f, f, fx, fy)
        val r = RectF(0f, 0f, dw(), dh()); end.mapRect(r)
        var dx = 0f; var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2f - r.left
        else { if (r.left > 0) dx = -r.left; if (r.right < width) dx = width - r.right }
        if (r.height() <= height) dy = (height - r.height()) / 2f - r.top
        else { if (r.top > 0) dy = -r.top; if (r.bottom < height) dy = height - r.bottom }
        end.postTranslate(dx, dy)
        val to = FloatArray(9); end.getValues(to); animateMatrix(from, to)
    }
    private fun animateMatrix(from: FloatArray, to: FloatArray) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            addUpdateListener { val t = it.animatedValue as Float; val c = FloatArray(9); for (i in 0..8) c[i] = from[i] + (to[i] - from[i]) * t; m.setValues(c); imageMatrix = m }
            start()
        }
    }

    // ---- swipe helpers ----
    private fun dispW(bw: Int, bh: Int): Float {
        if (bw <= 0 || bh <= 0 || width == 0 || height == 0) return width.toFloat()
        return min(width.toFloat(), height.toFloat() * bw / bh)
    }
    private fun slideSpan(): Float {
        val sw = dispW(drawable?.intrinsicWidth ?: 0, drawable?.intrinsicHeight ?: 0)
        val gd = ghost?.drawable
        val gw = dispW(gd?.intrinsicWidth ?: 0, gd?.intrinsicHeight ?: 0)
        return sw / 2f + gw / 2f + gapPx
    }
    private fun setupGhost(dir: Int) {
        ghostDir = dir
        val g = ghost ?: return
        val bmp = neighborBitmap?.invoke(dir)
        if (bmp != null) { g.setImageBitmap(bmp); g.visibility = VISIBLE; cachedSpan = slideSpan() } else g.visibility = GONE
    }
    private fun moveSwipe(dx: Float, dir: Int) {
        rawDx = dx
        val hasN = ghost?.visibility == VISIBLE
        val d = if (hasN) dx else dx * 0.25f
        curDx = d
        translationX = d
        if (hasN) ghost!!.translationX = -dir * cachedSpan + d
    }
    private fun finishSwipe() {
        val dir = ghostDir
        val hasN = ghost?.visibility == VISIBLE
        // threshold on the RAW finger travel, so the 0.25x damping (no-ghost case) doesn't block the commit
        val far = dir != 0 && abs(rawDx) > min(width * 0.18f, 120f)
        if (far && hasN) {
            val span = cachedSpan
            animate().translationX(dir * span).setDuration(200).start()
            ghost!!.animate().translationX(0f).setDuration(200).withEndAction {
                onSwipe?.invoke(dir)          // swaps the real image (neighbour was cached → instant)
                translationX = 0f
                ghost?.visibility = GONE; ghost?.translationX = 0f
            }.start()
        } else if (far) {
            // the neighbour has no cached preview (e.g. a video) → commit the navigation without a ghost slide
            animate().translationX(0f).setDuration(150).start()
            onSwipe?.invoke(dir)
        } else {
            animate().translationX(0f).setDuration(200).start()
            ghost?.let { g -> g.animate().translationX(-dir * cachedSpan).setDuration(200).withEndAction { g.visibility = GONE; g.translationX = 0f }.start() }
        }
        curDx = 0f; rawDx = 0f
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaleDet.onTouchEvent(ev)
        gesture.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // claim the gesture immediately so the root DrawerLayout's edge-swipe can't steal/jitter the slide
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = ev.x; downY = ev.y; lastX = ev.x; lastY = ev.y; multi = false; swiping = false; ghostDir = 0
            }
            MotionEvent.ACTION_POINTER_DOWN -> { multi = true; swiping = false; translationX = 0f; ghost?.visibility = GONE }
            MotionEvent.ACTION_MOVE -> {
                if (scaleDet.isInProgress || multi) { lastX = ev.x; lastY = ev.y }
                else if (curScale() > fitScale * 1.01f) {
                    m.postTranslate(ev.x - lastX, ev.y - lastY); clamp(); imageMatrix = m; lastX = ev.x; lastY = ev.y
                } else if (swipeEnabled) {
                    val ndx = ev.x - downX; val ndy = ev.y - downY
                    if (!swiping && abs(ndx) > slop && abs(ndx) > abs(ndy)) { swiping = true; swipeStartX = ev.x; ghostDir = 0; parent?.requestDisallowInterceptTouchEvent(true) }
                    if (swiping) {
                        val dx = ev.x - swipeStartX       // starts at 0 → no initial jump/jitter
                        val dir = if (dx >= 0) 1 else -1
                        if (dir != ghostDir) setupGhost(dir)
                        moveSwipe(dx, dir)
                    }
                    lastX = ev.x; lastY = ev.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (swiping) finishSwipe(); multi = false; swiping = false }
        }
        return true
    }
}
