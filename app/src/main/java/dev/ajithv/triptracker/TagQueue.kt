package dev.ajithv.triptracker

import android.content.Context

/**
 * Offline-durable queue of pending photo-tag changes. Keyed "asset|tag" -> desired on-state, latest
 * write wins (a POST tag {on} is idempotent, so replaying the latest desired state is correct). Persisted
 * in SharedPreferences, so tags the admin sets with no network/MQTT survive an app kill and replay once
 * connectivity returns.
 */
class TagQueue(ctx: Context) {
    private val sp = ctx.getSharedPreferences("tt_tagq", Context.MODE_PRIVATE)
    private fun key(asset: String, tag: String) = "$asset|$tag"

    fun enqueue(asset: String, tag: String, on: Boolean) { sp.edit().putBoolean(key(asset, tag), on).apply() }
    fun clearOne(asset: String, tag: String) { sp.edit().remove(key(asset, tag)).apply() }
    fun isEmpty() = sp.all.isEmpty()

    /** Pending desired states as (asset, tag, on) triples. */
    fun pending(): List<Triple<String, String, Boolean>> = sp.all.mapNotNull { (k, v) ->
        if (v !is Boolean) return@mapNotNull null
        val p = k.split("|", limit = 2); if (p.size != 2) null else Triple(p[0], p[1], v)
    }
}
