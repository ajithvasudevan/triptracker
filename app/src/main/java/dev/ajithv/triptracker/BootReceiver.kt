package dev.ajithv.triptracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-registers the user's geofences with the OS after a reboot (OS registrations don't survive a restart). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val specs = GeofenceController.stored(ctx)
        if (specs.isNotEmpty()) GeofenceController.register(ctx, specs)
    }
}
