package dev.ajithv.triptracker

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat

/** Feeds the map a "you are here + facing this way" signal: device location (framework LocationManager,
 *  no Play-services dependency) + compass heading (rotation-vector sensor). Push is throttled; headings are
 *  low-pass smoothed. start()/stop() are driven by the activity's onResume/onPause. */
class SelfLocator(
    private val act: Activity,
    private val onUpdate: (lat: Double?, lon: Double?, headingDeg: Float?, accuracyM: Float?) -> Unit,
) : SensorEventListener, LocationListener {

    private val lm = act.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sm = act.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rot = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private var lat: Double? = null
    private var lon: Double? = null
    private var heading: Float? = null
    private var acc: Float? = null
    private var lastPush = 0L
    private val rotM = FloatArray(9)
    private val ori = FloatArray(3)

    fun hasLocPerm() =
        ContextCompat.checkSelfPermission(act, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun start() {
        stop()   // idempotent: safe to call from both onResume and the permission-granted callback
        if (rot != null) sm.registerListener(this, rot, SensorManager.SENSOR_DELAY_UI)
        if (!hasLocPerm()) return
        try {
            (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))?.let {
                    lat = it.latitude; lon = it.longitude; acc = if (it.hasAccuracy()) it.accuracy else null
                }
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 2f, this)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 5f, this)
            push(true)
        } catch (e: SecurityException) { /* permission revoked mid-flight */ }
    }

    fun stop() {
        sm.unregisterListener(this)
        try { lm.removeUpdates(this) } catch (e: Exception) {}
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotM, e.values)
        SensorManager.getOrientation(rotM, ori)
        var az = Math.toDegrees(ori[0].toDouble()).toFloat()   // azimuth from north, -180..180
        if (az < 0) az += 360f
        heading = smooth(heading, az)
        push(false)
    }

    private fun smooth(prev: Float?, cur: Float): Float {
        if (prev == null) return cur
        var d = cur - prev
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        var h = prev + d * 0.25f                 // low-pass to kill compass jitter
        if (h < 0f) h += 360f
        if (h >= 360f) h -= 360f
        return h
    }

    override fun onLocationChanged(loc: Location) {
        lat = loc.latitude; lon = loc.longitude; acc = if (loc.hasAccuracy()) loc.accuracy else null; push(true)
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}
    @Deprecated("API<29") override fun onStatusChanged(p: String?, s: Int, extras: Bundle?) {}

    private fun push(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPush < 80L) return   // throttle sensor spam
        lastPush = now
        onUpdate(lat, lon, heading, acc)
    }
}
