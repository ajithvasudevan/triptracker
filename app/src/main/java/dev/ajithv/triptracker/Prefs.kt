package dev.ajithv.triptracker

import android.content.Context

/** Persisted settings. Defaults come from BuildConfig (secrets.properties at build time);
 *  the user can override the base URL / token from the Settings dialog. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("tt", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = (sp.getString("base_url", null) ?: BuildConfig.DEF_BASE_URL).trimEnd('/')
        set(v) { sp.edit().putString("base_url", v.trim().trimEnd('/')).apply() }

    var token: String
        get() = sp.getString("token", null) ?: BuildConfig.DEF_APP_TOKEN
        set(v) { sp.edit().putString("token", v.trim()).apply() }

    // "Colour track by speed" — per-app, not remembered across launches on the web; we persist it.
    var speedColor: Boolean
        get() = sp.getBoolean("speed_color", false)
        set(v) { sp.edit().putBoolean("speed_color", v).apply() }

    var basemap: String
        get() = sp.getString("basemap", "groadmap") ?: "groadmap"
        set(v) { sp.edit().putString("basemap", v).apply() }

    var gridCols: Int
        get() = sp.getInt("grid_cols", 4).coerceIn(2, 20)
        set(v) { sp.edit().putInt("grid_cols", v.coerceIn(2, 20)).apply() }

    // Live-track source: "network" = poll the add-on (default), "direct" = render the active trip from
    // GPS fixes broadcast straight from mymessages on this device (outage-proof). Direct still merges the
    // add-on track for backfill/history; a stopped trip always comes from the add-on.
    var liveSource: String
        get() = sp.getString("live_source", "network") ?: "network"
        set(v) { sp.edit().putString("live_source", v).apply() }
}
