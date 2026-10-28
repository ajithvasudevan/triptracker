package dev.ajithv.triptracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Thin REST client for the Trip Tracker add-on. Every request carries the X-TT-Token admin header.
 *  A 250 MB OkHttp disk cache persists downloaded image bytes across app restarts + in-place updates
 *  (the immich proxy marks them immutable), so re-opening photos/grid doesn't re-download. */
class Api(ctx: Context, private val prefs: Prefs) {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cache(Cache(File(ctx.applicationContext.cacheDir, "http-img"), 250L * 1024 * 1024))
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun base() = prefs.baseUrl
    fun token() = prefs.token

    // ---- media URLs (consumed by PhotoRepo / VideoView) ----
    fun thumbUrl(a: String) = "${base()}/api/photo/${enc(a)}/thumbnail"
    fun previewUrl(a: String) = "${base()}/api/photo/${enc(a)}/preview"
    fun originalUrl(a: String) = "${base()}/api/photo/${enc(a)}/original"
    fun videoUrl(a: String) = "${base()}/api/photo/${enc(a)}/video"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun req(path: String) = Request.Builder()
        .url(base() + path)
        .header("X-TT-Token", token())

    private suspend fun getStr(path: String): String = withContext(Dispatchers.IO) {
        client.newCall(req(path).get().build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code} for $path")
            r.body?.string() ?: "null"
        }
    }

    private suspend fun postStr(path: String, body: JSONObject?): String = withContext(Dispatchers.IO) {
        val rb = (body?.toString() ?: "{}").toRequestBody(JSON)
        client.newCall(req(path).post(rb).build()).execute().use { r ->
            r.body?.string() ?: "{}"
        }
    }

    private suspend fun delStr(path: String): String = withContext(Dispatchers.IO) {
        client.newCall(req(path).delete().build()).execute().use { r -> r.body?.string() ?: "{}" }
    }

    // ---- endpoints ----
    suspend fun state(): StateResp = Parse.state(JSONObject(getStr("/api/state")))
    suspend fun trips(): List<Trip> = Parse.trips(JSONArray(getStr("/api/trips")))
    suspend fun track(id: Int, sinceId: Long): List<TrackPoint> =
        Parse.track(JSONArray(getStr("/api/trips/$id/track?since_id=$sinceId")))
    suspend fun photos(id: Int, sinceId: Long): List<Photo> =
        Parse.photos(JSONArray(getStr("/api/trips/$id/photos?since_id=$sinceId")))

    suspend fun start(name: String, mode: String, intervalS: Int, minDistanceM: Int?, color: String?) {
        val b = JSONObject().put("name", name).put("mode", mode).put("interval_s", intervalS)
        if (minDistanceM != null) b.put("min_distance_m", minDistanceM)
        if (color != null) b.put("color", color)
        postStr("/api/trips/start", b)
    }

    suspend fun stop() { postStr("/api/trips/stop", null) }

    suspend fun spacing(id: Int, mode: String, intervalS: Int, minDistanceM: Int?) {
        val b = JSONObject().put("mode", mode).put("interval_s", intervalS)
        if (minDistanceM != null) b.put("min_distance_m", minDistanceM)
        postStr("/api/trips/$id/spacing", b)
    }

    suspend fun rename(id: Int, name: String?, color: String?, hidden: Boolean?) {
        val b = JSONObject()
        if (name != null) b.put("name", name)
        if (color != null) b.put("color", color)
        if (hidden != null) b.put("hidden", hidden)
        postStr("/api/trips/$id/rename", b)
    }

    suspend fun share(id: Int, scope: String): JSONObject =
        JSONObject(postStr("/api/trips/$id/share", JSONObject().put("scope", scope)))
    suspend fun unshare(id: Int, scope: String) { postStr("/api/trips/$id/unshare", JSONObject().put("scope", scope)) }

    /** Add/remove an admin visibility tag ('Relatives'/'World') on a photo. Returns true on 2xx. */
    suspend fun setTag(asset: String, tag: String, on: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val rb = JSONObject().put("tag", tag).put("on", on).toString().toRequestBody(JSON)
            client.newCall(req("/api/photo/${enc(asset)}/tag").post(rb).build()).execute().use { it.isSuccessful }
        } catch (e: Exception) { false }
    }

    suspend fun fillGaps(id: Int): JSONObject = JSONObject(postStr("/api/trips/$id/fill_gaps", null))
    suspend fun fillStatus(id: Int): JSONObject = JSONObject(getStr("/api/trips/$id/fill_status"))
    suspend fun unfillGaps(id: Int) { postStr("/api/trips/$id/unfill_gaps", null) }

    // ---- manual track editing (admin) ----
    suspend fun movePoint(tid: Int, pid: Long, lat: Double, lon: Double, ts: Double?) {
        val b = JSONObject().put("id", pid).put("lat", lat).put("lon", lon)
        if (ts != null) b.put("ts", ts)
        postStr("/api/trips/$tid/points/move", b)
    }
    suspend fun deletePoints(tid: Int, ids: List<Long>) {
        postStr("/api/trips/$tid/points/delete", JSONObject().put("ids", org.json.JSONArray(ids)))
    }
    suspend fun addPoint(tid: Int, lat: Double, lon: Double, ts: Double): Long =
        JSONObject(postStr("/api/trips/$tid/points/add", JSONObject().put("lat", lat).put("lon", lon).put("ts", ts))).getLong("id")
    suspend fun joinPoints(tid: Int, idA: Long, idB: Long): JSONObject =
        JSONObject(postStr("/api/trips/$tid/points/join", JSONObject().put("id_a", idA).put("id_b", idB)))
    suspend fun densifyPoints(tid: Int, ids: List<Long>, minGap: Double = 40.0, spacing: Double = 25.0): JSONObject =
        JSONObject(postStr("/api/trips/$tid/points/densify", JSONObject().put("ids", org.json.JSONArray(ids)).put("min_gap_m", minGap).put("spacing_m", spacing)))

    suspend fun exportBytes(id: Int, fmt: String): ByteArray? = withContext(Dispatchers.IO) {
        client.newCall(req("/api/trips/$id/export.$fmt").get().build()).execute().use { r ->
            if (r.isSuccessful) r.body?.bytes() else null
        }
    }

    suspend fun delete(id: Int) { delStr("/api/trips/$id") }
    suspend fun syncPhotos(id: Int): JSONObject = JSONObject(postStr("/api/trips/$id/sync_photos", null))
    suspend fun setBasemap(key: String) { postStr("/api/basemap", JSONObject().put("key", key)) }
}
