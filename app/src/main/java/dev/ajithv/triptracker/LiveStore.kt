package dev.ajithv.triptracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local, durable store of the raw GPS fixes the mymessages app broadcasts directly to this app
 * (same device, no network). It exists so the LIVE track can keep growing during a network/MQTT
 * outage, when the add-on/DB stops receiving points.
 *
 * Every fix mymessages records is kept here forever (never trimmed) so the data is available to
 * repair a track in the add-on DB if it ever loses points. The table is keyed by (trip_id, ts_ms)
 * so a repeated broadcast of the same fix is idempotent.
 */
class LiveStore(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "live_track.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE live_point(" +
                "trip_id INTEGER NOT NULL, ts_ms INTEGER NOT NULL, " +
                "lat REAL NOT NULL, lon REAL NOT NULL, " +
                "alt REAL, speed REAL, acc REAL, " +
                "PRIMARY KEY(trip_id, ts_ms))"
        )
        db.execSQL("CREATE INDEX live_point_trip_ts ON live_point(trip_id, ts_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {}

    /** Idempotent insert of one fix (ignores a duplicate (trip_id, ts_ms)). */
    fun insert(tripId: Int, tsMs: Long, lat: Double, lon: Double, alt: Double?, speed: Float?, acc: Float?) {
        val cv = ContentValues().apply {
            put("trip_id", tripId); put("ts_ms", tsMs); put("lat", lat); put("lon", lon)
            if (alt != null && !alt.isNaN()) put("alt", alt)
            if (speed != null && !speed.isNaN()) put("speed", speed.toDouble())
            if (acc != null && !acc.isNaN()) put("acc", acc.toDouble())
        }
        writableDatabase.insertWithOnConflict("live_point", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** Points for a trip with ts_ms strictly greater than [sinceMs], oldest first, as app TrackPoints
     *  (ts in epoch SECONDS to match the add-on's REST shape; id = ts_ms so it's stable + ordered). */
    fun pointsSince(tripId: Int, sinceMs: Long): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        readableDatabase.rawQuery(
            "SELECT ts_ms,lat,lon,alt,speed,acc FROM live_point WHERE trip_id=? AND ts_ms>? ORDER BY ts_ms",
            arrayOf(tripId.toString(), sinceMs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val tsMs = c.getLong(0)
                out.add(TrackPoint(
                    id = tsMs, ts = tsMs / 1000.0,
                    lat = c.getDouble(1), lon = c.getDouble(2),
                    acc = if (c.isNull(5)) null else c.getDouble(5),
                    speed = if (c.isNull(4)) null else c.getDouble(4),
                    alt = if (c.isNull(3)) null else c.getDouble(3),
                ))
            }
        }
        return out
    }

    fun countForTrip(tripId: Int): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM live_point WHERE trip_id=?", arrayOf(tripId.toString()))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
}
