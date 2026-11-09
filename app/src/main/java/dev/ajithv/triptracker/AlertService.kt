package dev.ajithv.triptracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence

/** Foreground service that vibrates STRONGLY and continuously (with an ongoing "tap to stop" notification)
 *  when you enter or leave an alert zone, until you tap the notification. A foreground service is needed
 *  because a bare BroadcastReceiver's process can be killed right after onReceive, cutting the vibration short. */
class AlertService : Service() {
    companion object {
        const val ACTION_START = "dev.ajithv.triptracker.ALERT_START"
        const val CHANNEL = "geofence_alert_vibrate"   // fresh id so the no-channel-vibration config applies
        const val NOTIF_ID = 43
        const val SAFETY_MS = 5 * 60 * 1000L           // hard stop after 5 min so it can't buzz forever unnoticed
        fun start(ctx: Context, count: Int, transition: Int) {
            val i = Intent(ctx, AlertService::class.java).setAction(ACTION_START)
                .putExtra("count", count).putExtra("transition", transition)
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra("count", 1) ?: 1
        val transition = intent?.getIntExtra("transition", Geofence.GEOFENCE_TRANSITION_ENTER)
            ?: Geofence.GEOFENCE_TRANSITION_ENTER
        startForeground(NOTIF_ID, buildNotification(count, transition))
        startVibration()
        handler.removeCallbacks(autoStop); handler.postDelayed(autoStop, SAFETY_MS)
        return START_STICKY
    }

    private fun startVibration() {
        val vib = if (Build.VERSION.SDK_INT >= 31)
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        vibrator = vib
        try {
            vib.cancel()
            val timings = longArrayOf(0, 800, 400)             // delay 0, ON 800 ms, OFF 400 ms, then repeat
            if (Build.VERSION.SDK_INT >= 26) {
                val amps = intArrayOf(0, 255, 0)               // 255 = maximum strength
                vib.vibrate(VibrationEffect.createWaveform(timings, amps, 0))   // repeat from index 0 → until cancel
            } else {
                @Suppress("DEPRECATION") vib.vibrate(timings, 0)
            }
        } catch (e: Exception) {}
    }

    private fun buildNotification(count: Int, transition: Int): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "Alert zones", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Entering or leaving a Trip Tracker alert zone"
                enableVibration(false)   // the service drives the strong repeating vibration itself
            }
            nm.createNotificationChannel(ch)   // channel's default sound plays once when the alert first shows
        }
        // tap the notification → broadcast STOP → GeofenceReceiver stops this service (cancels the vibration)
        val stop = PendingIntent.getBroadcast(this, 1,
            Intent(this, GeofenceReceiver::class.java).setAction(GeofenceReceiver.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val verb = if (transition == Geofence.GEOFENCE_TRANSITION_EXIT) "Left" else "Entered"
        val noun = if (count > 1) "$count alert zones" else "an alert zone"
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Trip Tracker")
            .setContentText("$verb $noun — tap to stop")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)             // can't be swiped away — must be tapped to stop
            .setContentIntent(stop)
            .build()
        return notif
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoStop)
        try { vibrator?.cancel() } catch (e: Exception) {}
        super.onDestroy()
    }
}
