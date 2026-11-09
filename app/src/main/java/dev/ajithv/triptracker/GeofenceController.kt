package dev.ajithv.triptracker

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/** Registers the user's geofences with the OS geofencing engine so alerts fire even when the app is
 *  backgrounded or killed. Each zone registers only the transitions it wants (enter and/or exit). The OS
 *  delivers transitions to [GeofenceReceiver] through a PendingIntent. */
object GeofenceController {
    private fun pendingIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx.applicationContext, GeofenceReceiver::class.java).setAction(GeofenceReceiver.ACTION)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(ctx.applicationContext, 0, i, flags)
    }

    /** Replace all OS registrations with [specs] (remove stale ones, then (re)add the current set). */
    @SuppressLint("MissingPermission")
    fun register(ctx: Context, specs: List<GeofenceSpec>) {
        val client = LocationServices.getGeofencingClient(ctx.applicationContext)
        val pi = pendingIntent(ctx)
        client.removeGeofences(pi).addOnCompleteListener {
            val fences = specs.mapNotNull { g ->
                var tt = 0
                if (g.enter) tt = tt or Geofence.GEOFENCE_TRANSITION_ENTER
                if (g.exit) tt = tt or Geofence.GEOFENCE_TRANSITION_EXIT
                if (tt == 0) return@mapNotNull null   // nothing to watch for this zone
                Geofence.Builder()
                    .setRequestId(g.id.toString())
                    .setCircularRegion(g.lat, g.lon, g.radiusM.toFloat())
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(tt)
                    .build()
            }
            if (fences.isEmpty()) return@addOnCompleteListener
            val req = GeofencingRequest.Builder()
                .setInitialTrigger(0)   // don't alert for the side of a zone you're already on at registration time
                .addGeofences(fences).build()
            try { client.addGeofences(req, pi) } catch (e: SecurityException) { /* location perm missing yet */ }
        }
    }

    /** The persisted geofences — used by [BootReceiver] to re-register after a reboot. */
    fun stored(ctx: Context): List<GeofenceSpec> {
        val out = ArrayList<GeofenceSpec>()
        try {
            val arr = org.json.JSONArray(Prefs(ctx).geofencesJson)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(GeofenceSpec(o.getLong("id"), o.getDouble("lat"), o.getDouble("lon"), o.getDouble("r"),
                    o.optBoolean("enter", true), o.optBoolean("exit", false), o.optString("name", "")))
            }
        } catch (e: Exception) {}
        return out
    }
}
