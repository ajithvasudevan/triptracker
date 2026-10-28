package dev.ajithv.triptracker

import org.json.JSONArray
import org.json.JSONObject

/** Mirrors the add-on REST payloads (server.py). */

data class MapCfg(val lat: Double, val lon: Double, val zoom: Int)
data class Config(
    val immich: Boolean,
    val googleKey: String,
    val map: MapCfg,
    val photoPollS: Int,
    val matchToleranceS: Int,
    val nearTrackRadiusM: Int,
)
data class StateResp(
    val active: Boolean,
    val admin: Boolean,
    val adminBasemap: String,
    val trip: Trip?,
    val config: Config,
)

data class Trip(
    val id: Int,
    val name: String?,
    val startedAt: String?,
    val stoppedAt: String?,
    val active: Boolean,
    val intervalS: Int,
    val mode: String,
    val minDistanceM: Int?,
    val color: String?,
    val hidden: Boolean,
    val points: Int,
    val photos: Int,
    val distanceM: Double,
)

data class TrackPoint(
    val id: Long,
    val ts: Double,
    val lat: Double,
    val lon: Double,
    val acc: Double?,
    val speed: Double?,   // m/s
    val alt: Double?,
    val source: String? = null,   // 'app' real fix, 'filled' = reconstructed gap point
)

data class Photo(
    val id: Long,
    val asset: String,
    val ts: Double?,       // capture epoch seconds
    val lat: Double,
    val lon: Double,
    val matchedBy: String?,
    val name: String?,
    val type: String,      // "image" | "video"
    val camera: String?,
    val people: List<String>,
    val tags: List<String> = emptyList(),   // admin visibility tags: 'Relatives' / 'World'
)

object Parse {
    fun config(o: JSONObject): Config {
        val m = o.optJSONObject("map") ?: JSONObject()
        return Config(
            immich = o.optBoolean("immich", false),
            googleKey = o.optString("google_key", ""),
            map = MapCfg(m.optDouble("lat", 12.9716), m.optDouble("lon", 77.5946), m.optInt("zoom", 13)),
            photoPollS = o.optInt("photo_poll_s", 20),
            matchToleranceS = o.optInt("match_tolerance_s", 120),
            nearTrackRadiusM = o.optInt("near_track_radius_m", 300),
        )
    }

    fun state(o: JSONObject): StateResp = StateResp(
        active = o.optBoolean("active", false),
        admin = o.optBoolean("admin", false),
        adminBasemap = o.optString("admin_basemap", "carto"),
        trip = o.optJSONObject("trip")?.let { trip(it) },
        config = config(o.optJSONObject("config") ?: JSONObject()),
    )

    fun trip(o: JSONObject): Trip = Trip(
        id = o.getInt("id"),
        name = o.optStringOrNull("name"),
        startedAt = o.optStringOrNull("started_at"),
        stoppedAt = o.optStringOrNull("stopped_at"),
        active = o.optBoolean("active", false),
        intervalS = o.optInt("interval_s", 5),
        mode = o.optString("mode", "time"),
        minDistanceM = if (o.isNull("min_distance_m")) null else o.optInt("min_distance_m"),
        color = o.optStringOrNull("color"),
        hidden = o.optBoolean("hidden", false),
        points = o.optInt("points", 0),
        photos = o.optInt("photos", 0),
        distanceM = o.optDouble("distance_m", 0.0),
    )

    fun trips(a: JSONArray): List<Trip> = (0 until a.length()).map { trip(a.getJSONObject(it)) }

    fun track(a: JSONArray): List<TrackPoint> = (0 until a.length()).map {
        val o = a.getJSONObject(it)
        TrackPoint(
            id = o.getLong("id"),
            ts = o.optDouble("ts", 0.0),
            lat = o.getDouble("lat"),
            lon = o.getDouble("lon"),
            acc = o.optDoubleOrNull("acc"),
            speed = o.optDoubleOrNull("speed"),
            alt = o.optDoubleOrNull("alt"),
            source = o.optStringOrNull("source"),
        )
    }

    fun photos(a: JSONArray): List<Photo> = (0 until a.length()).mapNotNull {
        val o = a.getJSONObject(it)
        if (o.isNull("lat") || o.isNull("lon")) return@mapNotNull null
        val ppl = o.optJSONArray("people")
        Photo(
            id = o.getLong("id"),
            asset = o.getString("asset"),
            ts = o.optDoubleOrNull("ts"),
            lat = o.getDouble("lat"),
            lon = o.getDouble("lon"),
            matchedBy = o.optStringOrNull("matched_by"),
            name = o.optStringOrNull("name"),
            type = o.optString("type", "image"),
            camera = o.optStringOrNull("camera"),
            people = if (ppl == null) emptyList() else (0 until ppl.length()).map { i -> ppl.getString(i) },
            tags = o.optJSONArray("tags")?.let { t -> (0 until t.length()).map { i -> t.getString(i) } } ?: emptyList(),
        )
    }
}

private fun JSONObject.optStringOrNull(k: String): String? =
    if (isNull(k)) null else optString(k, "").ifEmpty { if (has(k)) "" else null }

private fun JSONObject.optDoubleOrNull(k: String): Double? =
    if (isNull(k) || !has(k)) null else optDouble(k).let { if (it.isNaN()) null else it }
