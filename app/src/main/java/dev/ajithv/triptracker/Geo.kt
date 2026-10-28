package dev.ajithv.triptracker

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val R = 6371000.0
    private const val D = Math.PI / 180.0

    fun haversine(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = (bLat - aLat) * D; val dLon = (bLon - aLon) * D
        val s = sin(dLat / 2) * sin(dLat / 2) +
                cos(aLat * D) * cos(bLat * D) * sin(dLon / 2) * sin(dLon / 2)
        return R * 2 * atan2(sqrt(s), sqrt(1 - s))
    }

    fun bearing(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val y = sin((bLon - aLon) * D) * cos(bLat * D)
        val x = cos(aLat * D) * sin(bLat * D) - sin(aLat * D) * cos(bLat * D) * cos((bLon - aLon) * D)
        return (atan2(y, x) * 180 / Math.PI + 360) % 360
    }

    // speed (0..1 normalized) → colour, same stops as the web heatmap
    private val STOPS = arrayOf(
        0.0 to intArrayOf(21, 101, 192),
        0.20 to intArrayOf(0, 188, 212),
        0.40 to intArrayOf(67, 160, 71),
        0.60 to intArrayOf(255, 214, 0),
        0.80 to intArrayOf(251, 140, 0),
        1.0 to intArrayOf(229, 57, 53),
    )

    fun gradientColor(tIn: Double): Int {
        val t = tIn.coerceIn(0.0, 1.0)
        for (i in 1 until STOPS.size) {
            if (t <= STOPS[i].first) {
                val (t0, c0) = STOPS[i - 1]; val (t1, c1) = STOPS[i]
                val f = if (t1 - t0 == 0.0) 0.0 else (t - t0) / (t1 - t0)
                return Color.rgb(
                    (c0[0] + f * (c1[0] - c0[0])).toInt(),
                    (c0[1] + f * (c1[1] - c0[1])).toInt(),
                    (c0[2] + f * (c1[2] - c0[2])).toInt(),
                )
            }
        }
        val c = STOPS.last().second; return Color.rgb(c[0], c[1], c[2])
    }

    const val SPEED_LEVELS = 20
    fun segSpeedKmh(a: TrackPoint, b: TrackPoint): Double {
        val v = when {
            a.speed != null && b.speed != null -> (a.speed + b.speed) / 2
            b.speed != null -> b.speed
            a.speed != null -> a.speed
            else -> 0.0
        }
        return maxOf(0.0, v * 3.6)
    }
    fun colorBand(a: TrackPoint, b: TrackPoint): Int =
        Math.round(min(segSpeedKmh(a, b), 100.0) / 100.0 * SPEED_LEVELS).toInt()

    fun fmtDist(m: Double): String =
        if (m >= 1000) String.format(java.util.Locale.US, "%.1f km", m / 1000.0) else "${Math.round(m)} m"

    fun fmtHMS(sIn: Double): String {
        val s = maxOf(0L, sIn.toLong())
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    fun isGapDist(aLat: Double, aLon: Double, bLat: Double, bLon: Double, minDist: Int): Boolean {
        val thr = maxOf(150.0, 6.0 * (if (minDist > 0) minDist else 25))
        return haversine(aLat, aLon, bLat, bLon) > thr
    }
    fun isGapTime(dtSec: Double, intervalS: Int): Boolean =
        dtSec > maxOf(30.0, 3.0 * (if (intervalS > 0) intervalS else 15))
}
