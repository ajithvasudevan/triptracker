package dev.ajithv.triptracker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.os.Bundle
import android.view.View
import com.google.gson.JsonPrimitive
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.Circle
import org.maplibre.android.plugins.annotation.CircleManager
import org.maplibre.android.plugins.annotation.CircleOptions
import org.maplibre.android.plugins.annotation.OnCircleDragListener
import org.maplibre.android.plugins.annotation.Line
import org.maplibre.android.plugins.annotation.LineManager
import org.maplibre.android.plugins.annotation.LineOptions
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions
import org.maplibre.android.utils.ColorUtils

/** MapLibre (keyless) renderer: vector/raster basemaps, tracks (gap-split, per-trip or speed colour),
 *  start/end markers, the live head (arrow or dot), photo dots, camera + follow signals, and taps. */
class MapController(
    private val mapView: MapView,
    var onPhotoClick: (String) -> Unit,
    var onMapTap: (Double, Double) -> Unit,
    var onUserGesture: () -> Unit,
) : MapEngine {
    var map: MapLibreMap? = null
    private var style: Style? = null
    private var lineMgr: LineManager? = null
    private var fillDotMgr: CircleManager? = null  // dotted rendering of reconstructed ('filled') track runs
    private var decorMgr: CircleManager? = null   // start/end + still head
    private var photoMgr: CircleManager? = null   // photo dots (grey, non-current)
    private var curDotMgr: CircleManager? = null  // the CURRENT photo dot — its own layer, always on top
    private var clusterMgr: SymbolManager? = null // photo-cluster count badges
    private val density = mapView.resources.displayMetrics.density
    private var headMgr: SymbolManager? = null     // moving head arrow
    private var headDotMgr: CircleManager? = null  // moving head dot (own layer → cleared without touching start/end)
    private var replayMgr: CircleManager? = null   // trip-replay scrubber marker
    private var editMgr: CircleManager? = null     // draggable edit handles (selected points)
    override var onEditDragEnd: ((Long, Double, Double) -> Unit)? = null
    // live-trip incremental-append state (set by renderTracks, consumed by appendLive)
    private var liveTripId: Int? = null
    private var liveTail: Line? = null             // the polyline new points extend; null if not cleanly extendable
    private var liveTailBand: Int = -1             // speed-mode band of the tail (−1 = plain)
    private var liveCount: Int = 0                 // points of the live trip already drawn

    override var onStyleReady: (() -> Unit)? = null
    override var onCameraChanged: (() -> Unit)? = null
    override val isReady get() = map != null && style != null
    override val view: View get() = mapView

    companion object {
        const val DOT_CUR = "#ffd400"
        const val DOT_REST = "#c9d1d9"
        fun basemapStyle(key: String): Any = when (key) {
            "streets" -> rasterJson("https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}")
            "topo" -> rasterJson("https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}")
            "sat" -> rasterJson("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}")
            else -> "https://basemaps.cartocdn.com/gl/voyager-gl-style/style.json"   // carto (and any g_* fallback)
        }
        private fun rasterJson(tiles: String): String =
            """{"version":8,"sources":{"r":{"type":"raster","tiles":["$tiles"],"tileSize":256,"attribution":"© Esri, © OpenStreetMap"}},"layers":[{"id":"r","type":"raster","source":"r"}]}"""
    }

    override fun init(centerLat: Double, centerLon: Double, zoom: Int, basemapKey: String, onReady: () -> Unit) {
        mapView.getMapAsync { m ->
            map = m
            // Hide MapLibre's own compass (a circular button that appears top-right on rotate and overlaps our ✕).
            m.uiSettings.isCompassEnabled = false
            m.cameraPosition = CameraPosition.Builder()
                .target(LatLng(centerLat, centerLon)).zoom(zoom.toDouble()).build()
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) onUserGesture()
            }
            m.addOnMapClickListener { ll -> onMapTap(ll.latitude, ll.longitude); false }
            m.addOnCameraMoveListener { onCameraChanged?.invoke() }
            m.addOnCameraIdleListener { onCameraChanged?.invoke() }
            setBasemap(basemapKey) { onReady() }
        }
    }

    override fun setBasemap(key: String, after: (() -> Unit)?) {
        val m = map ?: return
        val s = basemapStyle(key)
        val builder = if (s is String && s.startsWith("http")) Style.Builder().fromUri(s)
                      else Style.Builder().fromJson(s as String)
        m.setStyle(builder) { st ->
            style = st
            destroyManagers()
            lineMgr = LineManager(mapView, m, st)
            fillDotMgr = CircleManager(mapView, m, st)   // created after lineMgr → fill dots draw on top of the line
            decorMgr = CircleManager(mapView, m, st)
            photoMgr = CircleManager(mapView, m, st)
            clusterMgr = SymbolManager(mapView, m, st).apply { iconAllowOverlap = true; iconIgnorePlacement = true }
            curDotMgr = CircleManager(mapView, m, st)   // created AFTER clusterMgr → current-photo dot draws on top of cluster circles
            headMgr = SymbolManager(mapView, m, st).apply { iconAllowOverlap = true; iconIgnorePlacement = true }
            headDotMgr = CircleManager(mapView, m, st)   // created last → head draws on top of the track
            replayMgr = CircleManager(mapView, m, st)
            editMgr = CircleManager(mapView, m, st)      // draggable edit handles, on top of everything
            editMgr!!.addDragListener(object : OnCircleDragListener {
                override fun onAnnotationDragStarted(a: Circle) {}
                override fun onAnnotationDrag(a: Circle) {}
                override fun onAnnotationDragFinished(a: Circle) {
                    val id = a.data?.asLong ?: return
                    onEditDragEnd?.invoke(id, a.latLng.latitude, a.latLng.longitude)
                }
            })
            liveTripId = null; liveTail = null; liveTailBand = -1; liveCount = 0
            // No annotation click listeners: every tap goes through onMapTap, which does dot-first hit-testing
            // (so a tap near a dot selects the nearest dot, not the track, and shows no balloon).
            onStyleReady?.invoke()
            after?.invoke()
        }
    }

    private fun destroyManagers() {
        replayMgr?.onDestroy(); headDotMgr?.onDestroy(); headMgr?.onDestroy(); clusterMgr?.onDestroy(); curDotMgr?.onDestroy(); photoMgr?.onDestroy(); decorMgr?.onDestroy(); lineMgr?.onDestroy()
        editMgr = null; replayMgr = null; headDotMgr = null; headMgr = null; clusterMgr = null; curDotMgr = null; photoMgr = null; decorMgr = null; fillDotMgr = null; lineMgr = null
        liveTail = null; liveTripId = null
    }

    override fun setReplay(lat: Double?, lon: Double?) {
        val cm = replayMgr ?: return
        cm.deleteAll()
        if (lat == null || lon == null) return
        cm.create(CircleOptions().withLatLng(LatLng(lat, lon)).withCircleRadius(9f)
            .withCircleColor("#ff6d00").withCircleStrokeColor("#ffffff").withCircleStrokeWidth(2.5f))
    }

    // ---- tracks ----
    override fun renderTracks(views: Collection<TripView>, liveId: Int?, speedColor: Boolean) {
        val lm = lineMgr ?: return; val dm = decorMgr ?: return; val st = style ?: return
        lm.deleteAll(); dm.deleteAll(); headMgr?.deleteAll(); headDotMgr?.deleteAll(); fillDotMgr?.deleteAll()
        liveTripId = liveId; liveTail = null; liveTailBand = -1; liveCount = 0
        for (v in views) {
            if (v.pts.isEmpty()) continue
            val isLive = v.id == liveId
            if (speedColor) { val (ll, lb) = drawSpeed(lm, v); if (isLive) { liveTail = ll; liveTailBand = lb } }
            else { val ll = drawPlain(lm, v); if (isLive) { liveTail = ll; liveTailBand = -1 } }
            // start/end markers
            addEnd(dm, v.pts.first(), start = true)
            if (!isLive && v.pts.size > 1) addEnd(dm, v.pts.last(), start = false)
            if (isLive) liveCount = v.pts.size
        }
        // live head
        val live = liveId?.let { id -> views.firstOrNull { it.id == id } }
        if (live != null && live.pts.isNotEmpty()) drawHead(st, live)
    }

    // Incrementally extend the live trip's tail polyline with points appended since the last render.
    override fun appendLive(v: TripView, liveId: Int?, speedColor: Boolean): Boolean {
        val lm = lineMgr ?: return false; val st = style ?: return false
        if (liveId == null || v.id != liveId || liveTripId != liveId) return false
        if (liveCount <= 0 || v.pts.size <= liveCount) return false
        var tail = liveTail ?: return false
        // the tail must still end at the last-rendered point; otherwise fall back to a full redraw
        val tp = v.pts[liveCount - 1]
        val tl = (tail.latLngs ?: return false).lastOrNull() ?: return false
        if (!near(tl.latitude, tp.lat) || !near(tl.longitude, tp.lon)) return false
        for (i in liveCount until v.pts.size) {
            if (TrackGeom.gapAt(v, i)) return false        // a real gap → let the caller redraw the whole track
            val a = v.pts[i - 1]; val p = v.pts[i]
            if (speedColor) {
                val band = Geo.colorBand(a, p)
                if (band != liveTailBand) {
                    tail = lm.create(LineOptions()
                        .withLatLngs(arrayListOf(LatLng(a.lat, a.lon), LatLng(p.lat, p.lon)))
                        .withLineColor(ColorUtils.colorToRgbaString(Geo.gradientColor(band.toDouble() / Geo.SPEED_LEVELS)))
                        .withLineWidth(5f).withLineOpacity(0.95f))
                    liveTailBand = band
                } else extendTail(lm, tail, p)
            } else extendTail(lm, tail, p)
        }
        liveTail = tail; liveCount = v.pts.size
        drawHead(st, v)
        return true
    }
    private fun extendTail(lm: LineManager, tail: Line, p: TrackPoint) {
        val pts = ArrayList(tail.latLngs ?: return); pts.add(LatLng(p.lat, p.lon))
        tail.latLngs = pts; lm.update(tail)
    }
    private fun near(a: Double, b: Double) = kotlin.math.abs(a - b) < 1e-7

    private fun drawPlain(lm: LineManager, v: TripView): Line? {
        val cstr = ColorUtils.colorToRgbaString(v.color)
        val runs = TrackGeom.drawRuns(v)
        var last: Line? = null; var lastReaches = false
        for ((idx, r) in runs.withIndex()) {
            if (r.pts.size < 2) continue
            if (r.dotted) { drawFillDots(cstr, r.pts); lastReaches = false }
            else {
                last = lm.create(LineOptions().withLatLngs(r.pts.map { LatLng(it.lat, it.lon) })
                    .withLineColor(cstr).withLineWidth(5f).withLineOpacity(0.9f))
                lastReaches = (idx == runs.lastIndex)
            }
        }
        // incremental tail valid only when the FINAL run is a solid real run reaching the last point
        return if (lastReaches) last else null
    }

    /** Render a reconstructed ('filled') run as a dotted line: dense small circles in the track colour. */
    private fun drawFillDots(cstr: String, pts: List<LL>) {
        val dm = fillDotMgr ?: return
        for (p in pts) dm.create(CircleOptions().withLatLng(LatLng(p.lat, p.lon))
            .withCircleRadius(2.5f).withCircleColor(cstr).withCircleOpacity(0.9f))
    }
    private fun drawSpeed(lm: LineManager, v: TripView): Pair<Line?, Int> {
        val runs = TrackGeom.speedRuns(v)
        var last: Line? = null
        for (r in runs) if (r.pts.size >= 2)
            last = lm.create(LineOptions().withLatLngs(r.pts.map { LatLng(it.lat, it.lon) })
                .withLineColor(ColorUtils.colorToRgbaString(r.color)).withLineWidth(5f).withLineOpacity(0.95f))
        // filled stretches are shown dotted on top of the speed line too (keeps them visually distinct)
        for (dr in TrackGeom.drawRuns(v)) if (dr.dotted) drawFillDots(ColorUtils.colorToRgbaString(v.color), dr.pts)
        val n = v.pts.size
        val reaches = runs.isNotEmpty() && runs.last().pts.size >= 2 &&
            runs.last().pts.last().let { near(it.lat, v.pts[n - 1].lat) && near(it.lon, v.pts[n - 1].lon) }
        return if (reaches) Pair(last, runs.last().band) else Pair(null, -1)
    }
    private fun addEnd(dm: CircleManager, p: TrackPoint, start: Boolean) {
        val fill = if (start) "#22c55e" else "#ef4444"
        val ring = if (start) "#0f5132" else "#7f1d1d"
        dm.create(CircleOptions().withLatLng(LatLng(p.lat, p.lon)).withCircleRadius(5f)
            .withCircleColor(fill).withCircleStrokeColor(ring).withCircleStrokeWidth(2f))
    }

    private fun drawHead(st: Style, v: TripView) {
        headMgr?.deleteAll(); headDotMgr?.deleteAll()
        val n = v.pts.size
        val head = v.pts[n - 1]
        var kmh = (v.latestSpeed ?: Double.NaN) * 3.6
        var brg = 0.0; var dist = 0.0
        val hasPrev = n >= 2
        if (hasPrev) {
            val prev = v.pts[n - 2]
            dist = Geo.haversine(prev.lat, prev.lon, head.lat, head.lon)
            brg = Geo.bearing(prev.lat, prev.lon, head.lat, head.lon)
            if (kmh.isNaN()) { val dt = head.ts - prev.ts; if (dt > 0) kmh = dist / dt * 3.6 }
        }
        if (kmh.isNaN()) kmh = 0.0
        if (kmh < 2) { headDot(head, "#e53935"); return }
        if (!hasPrev || dist < 1) { headDot(head, "#9e9e9e"); return }
        // arrow
        val col = Geo.gradientColor(minOf(kmh, 100.0) / 100.0)
        st.addImage("tt-head", arrowBitmap(col))
        headMgr?.create(SymbolOptions().withLatLng(LatLng(head.lat, head.lon))
            .withIconImage("tt-head").withIconRotate(brg.toFloat()).withIconSize(1f))
    }
    private fun headDot(p: TrackPoint, color: String) {
        headDotMgr?.create(CircleOptions().withLatLng(LatLng(p.lat, p.lon)).withCircleRadius(6f)
            .withCircleColor(color).withCircleStrokeColor("#ffffff").withCircleStrokeWidth(2f))
    }
    private fun arrowBitmap(color: Int): Bitmap {
        val sz = 48
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path().apply {
            moveTo(sz / 2f, 4f); lineTo(sz - 6f, sz - 6f); lineTo(6f, sz - 6f); close()
        }
        p.color = color; p.style = Paint.Style.FILL; c.drawPath(path, p)
        p.color = Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = 2f; c.drawPath(path, p)
        return bmp
    }

    // ---- photos ---- (grey dots in photoMgr; the current yellow dot in its own top layer so it's never hidden)
    override fun renderPhotos(markers: List<PhotoMarkerSpec>) {
        val pm = photoMgr ?: return; val cm = curDotMgr ?: return; val clm = clusterMgr ?: return; val st = style ?: return
        pm.deleteAll(); cm.deleteAll(); clm.deleteAll()
        for (m in markers) {
            if (m.count > 1) {
                val id = "cl${m.count}"
                st.addImage(id, MarkerBitmaps.cluster(density, m.count))
                clm.create(SymbolOptions().withLatLng(LatLng(m.lat, m.lon)).withIconImage(id).withIconSize(1f))
            } else {
                val mgr = if (m.current) cm else pm
                mgr.create(CircleOptions().withLatLng(LatLng(m.lat, m.lon))
                    .withCircleRadius(if (m.current) 8f else 6f)
                    .withCircleColor(if (m.current) DOT_CUR else DOT_REST)
                    .withCircleStrokeColor(if (m.current) "#7a5c00" else "#5b636e")
                    .withCircleStrokeWidth(2f)
                    .withData(JsonPrimitive(m.asset ?: "")))
            }
        }
    }

    // ---- camera ----
    override fun setEditPoints(pts: List<EditPt>) {
        val em = editMgr ?: return
        em.deleteAll()
        for (p in pts) em.create(CircleOptions().withLatLng(LatLng(p.lat, p.lon))
            .withCircleRadius(8f).withCircleColor("#ffd400").withCircleStrokeColor("#000000").withCircleStrokeWidth(2f)
            .withDraggable(true).withData(JsonPrimitive(p.id)))
    }
    override fun clearEdit() { editMgr?.deleteAll() }

    override fun zoom(): Double = map?.cameraPosition?.zoom ?: 13.0
    override fun zoomBy(delta: Double, focusX: Float, focusY: Float) {
        map?.moveCamera(CameraUpdateFactory.zoomBy(delta, android.graphics.Point(focusX.toInt(), focusY.toInt())))
    }
    override fun maxZoom(): Double = map?.maxZoomLevel ?: 25.0
    override fun metersPerPixel(): Double? {
        val m = map ?: return null
        val lat = m.cameraPosition.target?.latitude ?: return null
        return m.projection.getMetersPerPixelAtLatitude(lat)
    }
    override fun screenOf(lat: Double, lon: Double): PointF? = map?.projection?.toScreenLocation(LatLng(lat, lon))
    override fun centerOn(lat: Double, lon: Double, animate: Boolean) {
        val m = map ?: return
        val upd = CameraUpdateFactory.newLatLng(LatLng(lat, lon))
        if (animate) m.animateCamera(upd, 500) else m.moveCamera(upd)
    }
    override fun setView(lat: Double, lon: Double, zoom: Double) {
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom), 500)
    }
    override fun fitViews(views: Collection<TripView>) {
        val m = map ?: return
        val all = ArrayList<LatLng>()
        for (v in views) for (p in v.pts) all.add(LatLng(p.lat, p.lon))
        if (all.isEmpty()) return
        if (all.size == 1) { m.animateCamera(CameraUpdateFactory.newLatLngZoom(all[0], 16.0), 500); return }
        val b = LatLngBounds.Builder()
        for (ll in all) b.include(ll)
        try { m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 90), 500) } catch (e: Exception) {}
    }
    override fun currentCamera(): Cam? {
        val cp = map?.cameraPosition ?: return null
        val t = cp.target ?: return null
        return Cam(t.latitude, t.longitude, cp.zoom, cp.bearing)
    }
    override fun moveTo(cam: Cam) {
        val m = map ?: return
        m.moveCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(LatLng(cam.lat, cam.lon)).zoom(cam.zoom).bearing(cam.bearing).build()))
    }

    // ---- lifecycle ----
    override fun onCreate(b: Bundle?) = mapView.onCreate(b)
    override fun onStart() = mapView.onStart()
    override fun onResume() = mapView.onResume()
    override fun onPause() = mapView.onPause()
    override fun onStop() = mapView.onStop()
    override fun onLowMemory() = mapView.onLowMemory()
    override fun onSaveInstanceState(b: Bundle) = mapView.onSaveInstanceState(b)
    override fun onDestroy() { destroyManagers(); mapView.onDestroy() }
}
