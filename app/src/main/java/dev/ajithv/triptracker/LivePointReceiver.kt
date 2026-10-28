package dev.ajithv.triptracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives GPS fixes broadcast directly by the mymessages app (same device, signature-protected) and
 * persists them to [LiveStore]. Manifest-registered so it captures points even when this app's UI is
 * closed — the whole live track is kept locally, independent of the network/MQTT path to the add-on.
 */
class LivePointReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "dev.ajithv.triptracker.LIVE_POINT"
    }

    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != ACTION) return
        val tripId = i.getIntExtra("trip_id", -1)
        val tsMs = i.getLongExtra("ts", 0L)
        if (tripId < 0 || tsMs <= 0L || !i.hasExtra("lat") || !i.hasExtra("lon")) return
        val lat = i.getDoubleExtra("lat", Double.NaN)
        val lon = i.getDoubleExtra("lon", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return
        val alt = if (i.hasExtra("alt")) i.getDoubleExtra("alt", Double.NaN) else null
        val speed = if (i.hasExtra("speed")) i.getFloatExtra("speed", Float.NaN) else null
        val acc = if (i.hasExtra("acc")) i.getFloatExtra("acc", Float.NaN) else null
        val pending = goAsync()
        Thread {
            try { LiveStore(ctx).insert(tripId, tsMs, lat, lon, alt, speed, acc) }
            catch (e: Exception) { /* best-effort; a dropped fix is non-fatal */ }
            finally { pending.finish() }
        }.start()
    }
}
