package dev.ajithv.triptracker

/** One selected trip's live client-side state (mirrors the web `views` Map entry). */
class TripView(val id: Int) {
    var color: Int = TripAdapter.parseColor("#1565c0")
    var mode: String = "time"
    var intervalS: Int = 15
    var minDistanceM: Int = 25
    val pts = ArrayList<TrackPoint>()
    var lastPointId: Long = 0
    var lastPhotoId: Long = 0
    // dedup across the two live-track sources (add-on REST + phone-direct LiveStore). Both carry the
    // phone's epoch-ms timestamp, which round-trips exactly through the DB, so ms is a safe identity key.
    val tsKeys = HashSet<Long>()
    var lastLiveMs: Long = 0          // high-water of LiveStore rows already merged (direct mode)
    fun tsKey(ts: Double): Long = Math.round(ts * 1000.0)
    /** Append a point iff its timestamp isn't already present; returns true when it was new. */
    fun addPoint(p: TrackPoint): Boolean {
        if (!tsKeys.add(tsKey(p.ts))) return false
        pts.add(p); return true
    }
    /** Keep tsKeys consistent with pts (e.g. after a trip was first loaded via the add-on-only path). */
    fun reseedKeys() { if (tsKeys.size != pts.size) { tsKeys.clear(); for (p in pts) tsKeys.add(tsKey(p.ts)) } }
    val photos = ArrayList<Photo>()
    var latestSpeed: Double? = null       // m/s
    var latestSpeedTs: Double = 0.0

    fun applyMeta(t: Trip?) {
        if (t == null) return
        color = TripAdapter.parseColor(t.color)
        // Preserve "hybrid" (web treats hybrid like distance for gap-splitting); anything else → "time".
        mode = if (t.mode == "distance" || t.mode == "hybrid") t.mode else "time"
        intervalS = if (t.intervalS > 0) t.intervalS else 15
        minDistanceM = t.minDistanceM ?: 25
    }
}
