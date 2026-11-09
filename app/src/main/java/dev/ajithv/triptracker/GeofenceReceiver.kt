package dev.ajithv.triptracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/** OS geofence transitions (enter/exit) → start the alert; the STOP action (notification tap) → stop the
 *  alert service (which cancels the vibration). Works with the app backgrounded or killed. */
class GeofenceReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "dev.ajithv.triptracker.GEOFENCE_ALERT"
        const val ACTION_STOP = "dev.ajithv.triptracker.GEOFENCE_STOP"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_STOP) {
            ctx.stopService(Intent(ctx, AlertService::class.java)); return
        }
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val t = event.geofenceTransition
        if (t == Geofence.GEOFENCE_TRANSITION_ENTER || t == Geofence.GEOFENCE_TRANSITION_EXIT)
            AlertService.start(ctx, event.triggeringGeofences?.size ?: 1, t)
    }
}
