package dev.ajithv.triptracker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.view.View
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions

/** Native Google Maps renderer (Google Maps SDK for Android). Mirrors [MapController]'s visuals:
 *  tracks as polylines (gap-split / speed-banded via the shared [TrackGeom]), start/end + head + photo
 *  dots as fixed-pixel marker bitmaps (Google's Circle is metre-sized, so markers are used for a constant
 *  on-screen size). Taps forward to MainActivity's own dot-first hit-testing, exactly like MapLibre. */
class GoogleMapController(
    private val mapView: MapView,
    private val density: Float,
    var onPhotoClick: (String) -> Unit,
    var onMapTap: (Double, Double) -> Unit,
    var onUserGesture: () -> Unit,
) : MapEngine {

    private var map: GoogleMap? = null
    override var onStyleReady: (() -> Unit)? = null
    override var onCameraChanged: (() -> Unit)? = null
    override val isReady get() = map != null
    override val view: View get() = mapView

    private val trackLines = ArrayList<Polyline>()
    private val decorMarkers = ArrayList<Marker>()   // start/end dots
    private var headMarker: Marker? = null           // the live head (arrow/dot) — its own ref, updated in place
    private val photoMarkers = ArrayList<Marker>()
    private val clusterMarkers = ArrayList<Marker>()
    private val editMarkers = ArrayList<Marker>()   // draggable edit handles (selected points)
    override var onEditDragEnd: ((Long, Double, Double) -> Unit)? = null
    private var curDot: Marker? = null
    // live-trip incremental-append state (set by renderTracks, consumed by appendLive)
    private var liveTripId: Int? = null
    private var liveTail: Polyline? = null
    private var liveTailBand: Int = -1
    private var liveCount: Int = 0
    private var selfCone: Marker? = null      // orientation "flashlight" cone (device heading)
    private var selfDotM: Marker? = null      // the blue "you are here" dot
    private var selfAccCircle: Circle? = null // GPS accuracy circle (radius in metres)
    private var coneBmp: Bitmap? = null
    private var selfDotBmp: Bitmap? = null
    private var replayMarker: Marker? = null  // trip-replay scrubber marker
    private var replayBmp: Bitmap? = null

    override fun init(centerLat: Double, centerLon: Double, zoom: Int, basemapKey: String, onReady: () -> Unit) {
        MapsInitializer.initialize(mapView.context)
        mapView.getMapAsync { gm ->
            map = gm
            gm.mapType = mapTypeOf(basemapKey)
            gm.uiSettings.isCompassEnabled = false           // native compass disabled → we draw our own (btn_compass) on the right
            gm.uiSettings.isMapToolbarEnabled = false
            gm.uiSettings.isZoomControlsEnabled = true       // native +/- zoom buttons
            gm.uiSettings.isRotateGesturesEnabled = true
            // Padding so Google's own controls clear ours: TOP pushes the compass below the ☰ menu AND the
            // 📊 stats button (which sits at ~64 dp); BOTTOM lifts the zoom buttons above the locate button.
            gm.setPadding(0, (118f * density).toInt(), 0, (76f * density).toInt())
            gm.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(centerLat, centerLon), zoom.toFloat()))
            gm.setOnMapClickListener { ll -> onMapTap(ll.latitude, ll.longitude) }
            // Google routes a tap that lands ON a marker to the marker listener (NOT onMapClick) and
            // consumes it, so photo-dot/track-marker taps must be forwarded into the same hit-test as a
            // tap at the marker's position. Returning true suppresses Google's default info-window + recenter.
            gm.setOnMarkerClickListener { mk -> onMapTap(mk.position.latitude, mk.position.longitude); true }
            gm.setOnMarkerDragListener(object : GoogleMap.OnMarkerDragListener {
                override fun onMarkerDragStart(m: Marker) {}
                override fun onMarkerDrag(m: Marker) {}
                override fun onMarkerDragEnd(m: Marker) {
                    (m.tag as? Long)?.let { onEditDragEnd?.invoke(it, m.position.latitude, m.position.longitude) }
                }
            })
            gm.setOnCameraMoveStartedListener { reason ->
                if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) onUserGesture()
            }
            gm.setOnCameraMoveListener { onCameraChanged?.invoke() }
            gm.setOnCameraIdleListener { onCameraChanged?.invoke() }
            onStyleReady?.invoke()
            onReady()
        }
    }

    override fun setBasemap(key: String, after: (() -> Unit)?) {
        map?.mapType = mapTypeOf(key)
        after?.invoke()
    }
    private fun mapTypeOf(key: String) = when (key) {
        "gsatellite" -> GoogleMap.MAP_TYPE_SATELLITE
        "ghybrid" -> GoogleMap.MAP_TYPE_HYBRID
        "gterrain" -> GoogleMap.MAP_TYPE_TERRAIN
        else -> GoogleMap.MAP_TYPE_NORMAL
    }

    // ---- tracks ----
    override fun renderTracks(views: Collection<TripView>, liveId: Int?, speedColor: Boolean) {
        val gm = map ?: return
        trackLines.forEach { it.remove() }; trackLines.clear()
        decorMarkers.forEach { it.remove() }; decorMarkers.clear()
        headMarker?.remove(); headMarker = null
        liveTripId = liveId; liveTail = null; liveTailBand = -1; liveCount = 0
        for (v in views) {
            if (v.pts.isEmpty()) continue
            val isLive = v.id == liveId
            if (speedColor) { val (ll, lb) = drawSpeed(gm, v); if (isLive) { liveTail = ll; liveTailBand = lb } }
            else { val ll = drawPlain(gm, v); if (isLive) { liveTail = ll; liveTailBand = -1 } }
            addEnd(gm, v.pts.first(), start = true)
            if (!isLive && v.pts.size > 1) addEnd(gm, v.pts.last(), start = false)
            if (isLive) liveCount = v.pts.size
        }
        val live = liveId?.let { id -> views.firstOrNull { it.id == id } }
        if (live != null && live.pts.isNotEmpty()) drawHead(gm, live)
    }

    override fun appendLive(v: TripView, liveId: Int?, speedColor: Boolean): Boolean {
        val gm = map ?: return false
        if (liveId == null || v.id != liveId || liveTripId != liveId) return false
        if (liveCount <= 0 || v.pts.size <= liveCount) return false
        var tail = liveTail ?: return false
        val tp = v.pts[liveCount - 1]
        val tl = tail.points?.lastOrNull() ?: return false
        if (!near(tl.latitude, tp.lat) || !near(tl.longitude, tp.lon)) return false
        for (i in liveCount until v.pts.size) {
            if (TrackGeom.gapAt(v, i)) return false          // gap → caller redraws the whole track
            val a = v.pts[i - 1]; val p = v.pts[i]
            if (speedColor) {
                val band = Geo.colorBand(a, p)
                if (band != liveTailBand) {
                    val opt = PolylineOptions().color(withAlpha(Geo.gradientColor(band.toDouble() / Geo.SPEED_LEVELS), 0.95))
                        .width(5f * density).zIndex(1f).add(LatLng(a.lat, a.lon)).add(LatLng(p.lat, p.lon))
                    tail = gm.addPolyline(opt) ?: return false
                    trackLines.add(tail); liveTailBand = band
                } else extendTail(tail, p)
            } else extendTail(tail, p)
        }
        liveTail = tail; liveCount = v.pts.size
        drawHead(gm, v)
        return true
    }
    private fun extendTail(tail: Polyline, p: TrackPoint) {
        val pts = ArrayList(tail.points ?: return); pts.add(LatLng(p.lat, p.lon)); tail.points = pts
    }
    private fun near(a: Double, b: Double) = kotlin.math.abs(a - b) < 1e-7

    private fun drawPlain(gm: GoogleMap, v: TripView): Polyline? {
        val runs = TrackGeom.drawRuns(v)
        var last: Polyline? = null; var lastReaches = false
        for ((idx, r) in runs.withIndex()) {
            if (r.pts.size < 2) continue
            if (r.dotted) { addDotted(gm, v.color, r.pts); lastReaches = false }
            else {
                val opt = PolylineOptions().color(withAlpha(v.color, 0.9)).width(5f * density).zIndex(1f)
                for (p in r.pts) opt.add(LatLng(p.lat, p.lon))
                last = gm.addPolyline(opt)?.also { trackLines.add(it) }
                lastReaches = (idx == runs.lastIndex)
            }
        }
        return if (lastReaches) last else null
    }

    /** A reconstructed ('filled') run as a dotted polyline (native Dot/Gap pattern). */
    private fun addDotted(gm: GoogleMap, color: Int, pts: List<LL>) {
        val opt = PolylineOptions().color(withAlpha(color, 0.95)).width(5f * density).zIndex(1f)
            .pattern(listOf(com.google.android.gms.maps.model.Dot(),
                            com.google.android.gms.maps.model.Gap(3f * density)))
        for (p in pts) opt.add(LatLng(p.lat, p.lon))
        gm.addPolyline(opt)?.also { trackLines.add(it) }
    }
    private fun drawSpeed(gm: GoogleMap, v: TripView): Pair<Polyline?, Int> {
        val runs = TrackGeom.speedRuns(v)
        var last: Polyline? = null
        for (r in runs) if (r.pts.size >= 2) {
            val opt = PolylineOptions().color(withAlpha(r.color, 0.95)).width(5f * density).zIndex(1f)
            for (p in r.pts) opt.add(LatLng(p.lat, p.lon))
            last = gm.addPolyline(opt)?.also { trackLines.add(it) }
        }
        for (dr in TrackGeom.drawRuns(v)) if (dr.dotted) addDotted(gm, v.color, dr.pts)   // filled = dotted here too
        val n = v.pts.size
        val reaches = runs.isNotEmpty() && runs.last().pts.size >= 2 &&
            runs.last().pts.last().let { near(it.lat, v.pts[n - 1].lat) && near(it.lon, v.pts[n - 1].lon) }
        return if (reaches) Pair(last, runs.last().band) else Pair(null, -1)
    }
    private fun addEnd(gm: GoogleMap, p: TrackPoint, start: Boolean) {
        val fill = Color.parseColor(if (start) "#22c55e" else "#ef4444")
        val ring = Color.parseColor(if (start) "#0f5132" else "#7f1d1d")
        addMarker(gm, p.lat, p.lon, dotBitmap(5f, fill, ring, 2f), 3f)?.let { decorMarkers.add(it) }
    }

    private fun drawHead(gm: GoogleMap, v: TripView) {
        headMarker?.remove(); headMarker = null
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
        if (kmh < 2) { headMarker = addMarker(gm, head.lat, head.lon, dotBitmap(6f, Color.parseColor("#e53935"), Color.WHITE, 2f), 4f); return }
        if (!hasPrev || dist < 1) { headMarker = addMarker(gm, head.lat, head.lon, dotBitmap(6f, Color.parseColor("#9e9e9e"), Color.WHITE, 2f), 4f); return }
        val col = Geo.gradientColor(minOf(kmh, 100.0) / 100.0)
        headMarker = gm.addMarker(MarkerOptions().position(LatLng(head.lat, head.lon))
            .icon(BitmapDescriptorFactory.fromBitmap(arrowBitmap(col)))
            .anchor(0.5f, 0.5f).rotation(brg.toFloat()).flat(true).zIndex(4f))
    }

    // ---- photos ---- (grey dots; current yellow dot on a higher zIndex so it's never hidden)
    override fun renderPhotos(markers: List<PhotoMarkerSpec>) {
        val gm = map ?: return
        photoMarkers.forEach { it.remove() }; photoMarkers.clear()
        clusterMarkers.forEach { it.remove() }; clusterMarkers.clear()
        curDot?.remove(); curDot = null
        for (m in markers) {
            if (m.count > 1) {
                addMarker(gm, m.lat, m.lon, MarkerBitmaps.cluster(density, m.count), 6f)?.let { clusterMarkers.add(it) }
            } else {
                val cur = m.current
                val bmp = dotBitmap(if (cur) 8f else 6f,
                    Color.parseColor(if (cur) MapController.DOT_CUR else MapController.DOT_REST),
                    Color.parseColor(if (cur) "#7a5c00" else "#5b636e"), 2f)
                val mk = addMarker(gm, m.lat, m.lon, bmp, if (cur) 10f else 5f) ?: continue   // current dot above clusters (z 6)
                if (cur) curDot = mk else photoMarkers.add(mk)
            }
        }
    }

    // ---- self location + heading cone (Google-Maps-style orientation "flashlight") ----
    override fun setSelf(lat: Double?, lon: Double?, headingDeg: Float?, accuracyM: Float?) {
        val gm = map ?: return
        if (lat == null || lon == null) {
            selfCone?.remove(); selfCone = null; selfDotM?.remove(); selfDotM = null
            selfAccCircle?.remove(); selfAccCircle = null; return
        }
        val pos = LatLng(lat, lon)
        // accuracy circle (radius in metres — scales with zoom, like Google's own)
        if (accuracyM != null && accuracyM > 0f) {
            val circ = selfAccCircle
            if (circ == null) {
                selfAccCircle = gm.addCircle(CircleOptions().center(pos).radius(accuracyM.toDouble())
                    .fillColor(0x22186ae8).strokeColor(0x55186ae8).strokeWidth(1.5f * density).zIndex(1f))
            } else { circ.center = pos; circ.radius = accuracyM.toDouble() }
        } else { selfAccCircle?.remove(); selfAccCircle = null }
        if (headingDeg != null) {
            if (coneBmp == null) coneBmp = coneBitmap()
            val cone = selfCone
            if (cone == null) {
                // flat marker anchored at the cone's apex (bottom-centre = the dot), so rotation spins it
                // around the dot and it lies on the map (rotates with map bearing).
                selfCone = gm.addMarker(MarkerOptions().position(pos)
                    .icon(BitmapDescriptorFactory.fromBitmap(coneBmp!!))
                    .anchor(0.5f, 1.0f).flat(true).rotation(headingDeg).zIndex(7f))
            } else { cone.position = pos; cone.rotation = headingDeg }
        } else { selfCone?.remove(); selfCone = null }
        if (selfDotBmp == null) selfDotBmp = dotBitmap(7f, Color.parseColor("#1a73e8"), Color.WHITE, 2.5f)
        val dot = selfDotM
        if (dot == null) {
            selfDotM = gm.addMarker(MarkerOptions().position(pos)
                .icon(BitmapDescriptorFactory.fromBitmap(selfDotBmp!!)).anchor(0.5f, 0.5f)
                .flat(true).zIndex(8f))
        } else { dot.position = pos }
    }
    /** A translucent blue circular-sector beam fading outward from the apex (the dot) — the "flashlight". */
    private fun coneBitmap(): Bitmap {
        val h = 72f * density
        val size = h.toInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val apexX = size / 2f; val apexY = size.toFloat()   // apex at bottom-centre
        val len = h * 0.96f
        val spreadDeg = 30f                                 // half-angle of the beam
        val rect = RectF(apexX - len, apexY - len, apexX + len, apexY + len)
        val path = Path().apply {
            moveTo(apexX, apexY)
            arcTo(rect, 270f - spreadDeg, 2f * spreadDeg)    // 270° = straight up (north) in canvas angles
            close()
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(apexX, apexY, len,
            0x66186ae8.toInt(), 0x00186ae8, Shader.TileMode.CLAMP)   // blue ~40% near the dot → 0% at the tip
        c.drawPath(path, p)
        return bmp
    }

    override fun setReplay(lat: Double?, lon: Double?) {
        val gm = map ?: return
        if (lat == null || lon == null) { replayMarker?.remove(); replayMarker = null; return }
        if (replayBmp == null) replayBmp = dotBitmap(9f, Color.parseColor("#ff6d00"), Color.WHITE, 2.5f)
        val pos = LatLng(lat, lon)
        val m = replayMarker
        if (m == null) replayMarker = gm.addMarker(MarkerOptions().position(pos)
            .icon(BitmapDescriptorFactory.fromBitmap(replayBmp!!)).anchor(0.5f, 0.5f).flat(true).zIndex(9f))
        else m.position = pos
    }

    private fun addMarker(gm: GoogleMap, lat: Double, lon: Double, bmp: Bitmap, z: Float): Marker? =
        gm.addMarker(MarkerOptions().position(LatLng(lat, lon))
            .icon(BitmapDescriptorFactory.fromBitmap(bmp)).anchor(0.5f, 0.5f).zIndex(z))

    private fun withAlpha(c: Int, a: Double): Int = (c and 0x00FFFFFF) or ((a * 255).toInt() shl 24)

    /** A filled circle + stroke ring, radius in dp → fixed on-screen size (markers don't scale with zoom). */
    private fun dotBitmap(radiusDp: Float, fill: Int, ring: Int, strokeDp: Float): Bitmap {
        val r = radiusDp * density
        val sw = strokeDp * density
        val size = ((r + sw) * 2f).toInt().coerceAtLeast(2) + 2
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val cx = size / 2f; val cy = size / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.FILL; p.color = fill; c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = sw; p.color = ring; c.drawCircle(cx, cy, r, p)
        return bmp
    }
    private fun arrowBitmap(color: Int): Bitmap {
        val sz = (22f * density).toInt().coerceAtLeast(16)
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val m = sz * 0.12f
        val path = Path().apply { moveTo(sz / 2f, m); lineTo(sz - m, sz - m); lineTo(m, sz - m); close() }
        p.color = color; p.style = Paint.Style.FILL; c.drawPath(path, p)
        p.color = Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = 2f * density; c.drawPath(path, p)
        return bmp
    }

    // ---- camera ----
    override fun setEditPoints(pts: List<EditPt>) {
        val gm = map ?: return
        editMarkers.forEach { it.remove() }; editMarkers.clear()
        val bmp = dotBitmap(8f, Color.parseColor("#ffd400"), Color.BLACK, 2f)
        for (p in pts) {
            val mk = addMarker(gm, p.lat, p.lon, bmp, 20f) ?: continue
            mk.isDraggable = true; mk.tag = p.id; editMarkers.add(mk)
        }
    }
    override fun clearEdit() { editMarkers.forEach { it.remove() }; editMarkers.clear() }
    override fun setZoomControls(on: Boolean) { map?.uiSettings?.isZoomControlsEnabled = on }

    override fun zoom(): Double = map?.cameraPosition?.zoom?.toDouble() ?: 13.0
    override fun zoomBy(delta: Double, focusX: Float, focusY: Float) {
        map?.moveCamera(CameraUpdateFactory.zoomBy(delta.toFloat(), android.graphics.Point(focusX.toInt(), focusY.toInt())))
    }
    override fun maxZoom(): Double = map?.maxZoomLevel?.toDouble() ?: 21.0
    override fun metersPerPixel(): Double? {
        val gm = map ?: return null
        val w = mapView.width; val h = mapView.height
        if (w <= 0 || h <= 0) return null
        val cx = w / 2; val cy = h / 2
        val p1 = gm.projection.fromScreenLocation(Point(cx, cy))
        val p2 = gm.projection.fromScreenLocation(Point(cx + 100, cy))
        return Geo.haversine(p1.latitude, p1.longitude, p2.latitude, p2.longitude) / 100.0
    }
    override fun screenOf(lat: Double, lon: Double): PointF? {
        val gm = map ?: return null
        val pt: Point = gm.projection.toScreenLocation(LatLng(lat, lon))
        return PointF(pt.x.toFloat(), pt.y.toFloat())
    }
    override fun centerOn(lat: Double, lon: Double, animate: Boolean) {
        val gm = map ?: return
        val upd = CameraUpdateFactory.newLatLng(LatLng(lat, lon))
        if (animate) gm.animateCamera(upd, 500, null) else gm.moveCamera(upd)
    }
    override fun setView(lat: Double, lon: Double, zoom: Double) {
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom.toFloat()), 500, null)
    }
    override fun fitViews(views: Collection<TripView>) {
        val gm = map ?: return
        val all = ArrayList<LatLng>()
        for (v in views) for (p in v.pts) all.add(LatLng(p.lat, p.lon))
        if (all.isEmpty()) return
        if (all.size == 1) { gm.animateCamera(CameraUpdateFactory.newLatLngZoom(all[0], 16f), 500, null); return }
        val b = LatLngBounds.Builder(); for (ll in all) b.include(ll)
        try { gm.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), (90 * density).toInt()), 500, null) } catch (e: Exception) {}
    }
    override fun currentCamera(): Cam? {
        val cp = map?.cameraPosition ?: return null
        return Cam(cp.target.latitude, cp.target.longitude, cp.zoom.toDouble(), cp.bearing.toDouble())
    }
    override fun moveTo(cam: Cam) {
        val gm = map ?: return
        gm.moveCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(LatLng(cam.lat, cam.lon)).zoom(cam.zoom.toFloat()).bearing(cam.bearing.toFloat()).build()))
    }

    // ---- lifecycle ----
    override fun onCreate(b: Bundle?) = mapView.onCreate(b)
    override fun onStart() = mapView.onStart()
    override fun onResume() = mapView.onResume()
    override fun onPause() = mapView.onPause()
    override fun onStop() = mapView.onStop()
    override fun onLowMemory() = mapView.onLowMemory()
    override fun onSaveInstanceState(b: Bundle) = mapView.onSaveInstanceState(b)
    override fun onDestroy() = mapView.onDestroy()
}
