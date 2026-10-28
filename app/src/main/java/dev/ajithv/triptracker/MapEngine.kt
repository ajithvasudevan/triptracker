package dev.ajithv.triptracker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.Bundle
import android.view.View

/** Engine-neutral lat/lon (so MainActivity never depends on a renderer's own LatLng type). */
data class LL(val lat: Double, val lon: Double)

/** One rendered photo marker: a single photo (asset != null, count == 1) or a cluster (asset == null,
 *  count > 1). MainActivity computes these by screen-space clustering; the engine draws them. */
data class PhotoMarkerSpec(val lat: Double, val lon: Double, val asset: String?, val count: Int, val current: Boolean)
data class EditPt(val id: Long, val lat: Double, val lon: Double)

object MarkerBitmaps {
    /** A round cluster badge with the count (grows a little with the count). Shared by both engines. */
    fun cluster(density: Float, count: Int): Bitmap {
        val r = (13f + minOf(count, 60) * 0.18f) * density
        val sw = 2f * density
        val size = ((r + sw) * 2f).toInt() + 2
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val cx = size / 2f; val cy = size / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.FILL; p.color = Color.parseColor("#1565c0"); c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = sw; p.color = Color.WHITE; c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER
        p.textSize = 12f * density; p.isFakeBoldText = true
        val txt = if (count > 999) "999+" else count.toString()
        c.drawText(txt, cx, cy - (p.descent() + p.ascent()) / 2f, p)
        return bmp
    }
}

/** A camera snapshot, handed off between engines so switching basemaps keeps the exact view. */
data class Cam(val lat: Double, val lon: Double, val zoom: Double, val bearing: Double)

/** Common surface for a map renderer. Implemented by the MapLibre controller (keyless vector/raster
 *  basemaps) and the Google Maps SDK controller (native Google basemaps). MainActivity holds whichever
 *  is active and swaps them seamlessly by handing off the camera + re-rendering the overlays. */
interface MapEngine {
    var onStyleReady: (() -> Unit)?
    var onCameraChanged: (() -> Unit)?
    val isReady: Boolean
    val view: View

    fun init(centerLat: Double, centerLon: Double, zoom: Int, basemapKey: String, onReady: () -> Unit)
    fun setBasemap(key: String, after: (() -> Unit)? = null)
    fun renderTracks(views: Collection<TripView>, liveId: Int?, speedColor: Boolean)
    // Incrementally extend the live trip's track with the points appended since the last render, instead of
    // redrawing the whole track. Returns false when it can't (gap reached, state stale) → caller full-renders.
    fun appendLive(v: TripView, liveId: Int?, speedColor: Boolean): Boolean = false
    fun renderPhotos(markers: List<PhotoMarkerSpec>)

    // ---- manual track editing: draggable markers for the currently-selected points ----
    var onEditDragEnd: ((Long, Double, Double) -> Unit)?
        get() = null
        set(_) {}
    fun setEditPoints(pts: List<EditPt>) {}
    fun clearEdit() {}
    fun setZoomControls(on: Boolean) {}

    fun zoom(): Double
    // Zoom by a delta (mouse-wheel / trackpad scroll), anchored at the given screen point. Default no-op.
    fun zoomBy(delta: Double, focusX: Float, focusY: Float) {}
    fun maxZoom(): Double = 22.0     // the deepest zoom this engine/basemap allows (for cluster-tap behaviour)
    fun metersPerPixel(): Double?    // ground metres per screen pixel at the current view (for the scale bar)
    // Device location + compass heading + accuracy indicator (the Google-Maps-style orientation cone +
    // accuracy circle). Default no-op; only the Google engine draws it. null lat/lon removes it; null heading
    // shows just the dot; null accuracy shows no circle.
    fun setSelf(lat: Double?, lon: Double?, headingDeg: Float?, accuracyM: Float?) {}
    // A single movable marker used by trip replay (scrubbing the track). null removes it.
    fun setReplay(lat: Double?, lon: Double?) {}
    fun screenOf(lat: Double, lon: Double): PointF?
    fun centerOn(lat: Double, lon: Double, animate: Boolean)
    fun setView(lat: Double, lon: Double, zoom: Double)
    fun fitViews(views: Collection<TripView>)
    fun currentCamera(): Cam?
    fun moveTo(cam: Cam)

    // activity lifecycle pass-through (both engines are live at once; only visibility differs)
    fun onCreate(b: Bundle?)
    fun onStart()
    fun onResume()
    fun onPause()
    fun onStop()
    fun onLowMemory()
    fun onSaveInstanceState(b: Bundle)
    fun onDestroy()
}

/** Track geometry shared by both renderers so gap-splitting and speed-band runs stay identical.
 *  (A line/polyline must be built with its COMPLETE point list — see the speed-run note below.) */
object TrackGeom {
    // When set, the trip being edited is drawn as ONE connected line (no gap breaks) so edits stay visible.
    var editConnectedId: Int? = null
    fun gapAt(v: TripView, i: Int): Boolean {
        val a = v.pts[i - 1]; val b = v.pts[i]
        // distance AND hybrid are distance-spaced (web app) → a large jump = a real data gap (drops outliers
        // from the line). Only pure "time" mode uses the time-gap rule.
        return if (v.mode == "distance" || v.mode == "hybrid")
                   Geo.isGapDist(a.lat, a.lon, b.lat, b.lon, v.minDistanceM)
               else Geo.isGapTime(b.ts - a.ts, v.intervalS)
    }

    /** Contiguous polyline pieces, broken at gaps. */
    fun segments(v: TripView): List<List<LL>> {
        if (editConnectedId != null && v.id == editConnectedId) return listOf(v.pts.map { LL(it.lat, it.lon) })
        val segs = ArrayList<ArrayList<LL>>()
        var cur: ArrayList<LL>? = null
        for (i in v.pts.indices) {
            val gap = if (i == 0) true else gapAt(v, i)
            if (i == 0 || gap) { cur = arrayListOf(LL(v.pts[i].lat, v.pts[i].lon)); segs.add(cur) }
            else cur!!.add(LL(v.pts[i].lat, v.pts[i].lon))
        }
        return segs
    }

    class DrawRun(val dotted: Boolean, val pts: List<LL>)
    /** Draw-runs broken at gaps AND at real/filled boundaries, so reconstructed ('filled') stretches can be
     *  rendered dotted while real fixes stay solid. Each run shares its boundary point with its neighbour,
     *  so the solid and dotted pieces join seamlessly. */
    fun drawRuns(v: TripView): List<DrawRun> {
        val out = ArrayList<DrawRun>()
        val p = v.pts
        if (p.isEmpty()) return out
        if (editConnectedId != null && v.id == editConnectedId) return listOf(DrawRun(false, p.map { LL(it.lat, it.lon) }))
        var cur = ArrayList<LL>().apply { add(LL(p[0].lat, p[0].lon)) }
        var dotted = false; var started = false
        for (i in 1 until p.size) {
            if (gapAt(v, i)) {
                if (cur.size >= 2) out.add(DrawRun(dotted, cur))
                cur = ArrayList<LL>().apply { add(LL(p[i].lat, p[i].lon)) }; started = false; continue
            }
            val ed = p[i - 1].source == "filled" || p[i].source == "filled"
            if (!started) { dotted = ed; started = true; cur.add(LL(p[i].lat, p[i].lon)) }
            else if (ed == dotted) cur.add(LL(p[i].lat, p[i].lon))
            else {
                out.add(DrawRun(dotted, cur))
                cur = arrayListOf(LL(p[i - 1].lat, p[i - 1].lon), LL(p[i].lat, p[i].lon)); dotted = ed
            }
        }
        if (cur.size >= 2) out.add(DrawRun(dotted, cur))
        return out
    }

    class Run(val color: Int, val band: Int, val pts: ArrayList<LL>)
    /** Same-speed-band runs, broken at gaps; each run is built complete before it's returned. */
    fun speedRuns(v: TripView): List<Run> {
        val runs = ArrayList<Run>()
        var run: Run? = null; var runBand = -1
        for (i in 1 until v.pts.size) {
            if (gapAt(v, i)) { run = null; runBand = -1; continue }
            val a = v.pts[i - 1]; val b = v.pts[i]; val band = Geo.colorBand(a, b)
            if (run != null && runBand == band) run.pts.add(LL(b.lat, b.lon))
            else {
                run = Run(Geo.gradientColor(band.toDouble() / Geo.SPEED_LEVELS), band,
                    arrayListOf(LL(a.lat, a.lon), LL(b.lat, b.lon)))
                runBand = band; runs.add(run)
            }
        }
        return runs
    }
}
