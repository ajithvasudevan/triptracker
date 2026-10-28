# Trip Tracker — Android app

A native **Kotlin/Android** viewer for the self-hosted [**Trip Tracker** Home Assistant
add-on](https://github.com/ajithvasudevan/ha-addon-trip-tracker). It shows a live GPS **trip
map** that grows as you travel, with **photo markers** pulled from immich placed at the spot
each picture was taken — the same features as the add-on's web UI, rebuilt from scratch as a
real app (no WebView).

- **Package / applicationId:** `dev.ajithv.triptracker`
- **Min / target / compile SDK:** 27 / 34 / 34 · **ABI:** `arm64-v8a`
- **Language / build:** Kotlin 1.9.24, AGP 8.5.2, Gradle 8.7, JDK 17

> LAN-only. The app talks directly to the add-on's host port (`:8099`) over HTTP on the home
> network — the same reachability as the web pop-out. There is no cloud component.

---

## Features

### Map
- **Dual map engine** behind a common `MapEngine` interface, switchable live from the basemap
  picker with no flash (camera + overlays are handed to the incoming engine before it is shown):
  - **MapLibre** (keyless) — CARTO Voyager vector, plus Esri raster Streets / Topo / Satellite.
  - **Google Maps** (native SDK) — Roadmap / Satellite / Hybrid / Terrain. Needs a Maps-SDK-for-
    Android key (see [Configuration](#configuration)); without one the Google basemaps render
    blank but never crash.
- **Tracks** drawn per trip: contiguous segments split at real data gaps, optional **speed-band
  colouring**, and **reconstructed ("filled") stretches rendered dotted**. Shared `TrackGeom`
  logic keeps both engines pixel-identical.
- **Photo markers** with screen-space **clustering** (a numbered badge that splits as you zoom
  in); the current photo gets a highlighted dot drawn above the rest. Start/end flags and a
  moving heading arrow for the live trip.
- **Mouse-wheel / trackpad zoom**, anchored at the pointer (works on both engines).
- Scale bar, speed pill, compass (tap = north-up), and a device-location **orientation cone +
  accuracy circle** (locate button).
- Tap a track → a balloon anchored to the nearest point; tap a cluster → zoom to split it.

### Photos
- **Dock viewer** that is layout-aware, mirroring the web app:
  **portrait → bottom 44%**, **landscape → right 40%** (the map and its controls shrink to the
  left 60% so nothing is hidden behind the pane).
- Custom `ZoomImageView`: pinch-zoom, pan, double-tap (fit ↔ fill), and horizontal **ghost-slide
  swipe** between photos.
- **Full-screen lightbox** (same gestures) with "open original" and in-place video playback.
- **Grid** of thumbnails (RecyclerView, square cells, 2–20 columns via a slider) with the current
  photo highlighted and a progress bar.
- Aggressive, offline-friendly image cache: in-memory `LruCache` + a **250 MB OkHttp disk cache**
  of the (immutable) immich previews, with ±5 neighbour pre-loading.

### Trips & editing (admin)
- Side panel (Trips / Start / Filter tabs): start/stop a trip, set point spacing, rename, recolour,
  hide/show, delete, and re-sync — plus the ability to see hidden trips.
- **Manual track editor**: move / delete / add / join points, with timestamp handling (keep or
  recompute by distance) and undo/redo.
- **Gap-fill**: reconstruct a missing stretch along OSM rail or OSRM road, rendered dotted.
- **Replay** a trip (scrub a marker along the track), **stats + charts** (elevation / speed, each
  maximisable to a full-screen landscape overlay), and **export** to GPX / GeoJSON.
- Photo **tagging** (Relatives / World) from the dock, lightbox, and grid.

### Live tracking
- **Network mode** — poll the add-on's `/api/state`, incremental
  `/api/trips/{id}/track?since_id` and `/photos?since_id` every 4 s (exactly like the web).
- **Direct mode** — receive on-device GPS points broadcast by the MyMessages app via a
  signature-gated `Intent` (same debug signing cert), stored in a local SQLite buffer and merged
  (timestamp-deduped) with the server track, so the live trace keeps moving even without the
  add-on round-trip.

---

## Architecture

- **No WebView, no MQTT in the app.** A pure REST client (OkHttp + Gson) to the add-on on `:8099`.
- **`MapEngine`** (`MapEngine.kt`) is the engine-neutral surface `MainActivity` talks to
  (init / basemap / render tracks & photos / zoom / camera / lifecycle …). `MapController`
  (MapLibre) and `GoogleMapController` (Google Maps SDK) each implement it; both MapViews are kept
  alive at once and only their visibility is toggled on a switch.
- **`PhotoRepo`** owns fetching, downsampling, the two-tier cache, and preloading.
- Admin requests carry the **`X-TT-Token`** header (see [below](#admin--the-add-on-token)); the
  app runs read-only without it.

### Project layout (`app/src/main/java/dev/ajithv/triptracker/`)

| File | Role |
| --- | --- |
| `MainActivity.kt` | The single activity: wiring, panel/dock/grid/lightbox overlays, layout logic. |
| `MapEngine.kt` | Engine-neutral interface + shared `TrackGeom` (segments / draw-runs / speed-runs). |
| `MapController.kt` | MapLibre renderer (Line/Circle/Symbol managers). |
| `GoogleMapController.kt` | Native Google Maps renderer (Polyline + Marker overlays). |
| `Api.kt` / `Models.kt` | REST client + data models. |
| `PhotoRepo.kt` / `ZoomImageView.kt` | Image fetch/cache + the zoom/pan/swipe photo view. |
| `GridAdapter.kt` / `SquareFrameLayout.kt` / `TripAdapter.kt` | RecyclerView adapters + square-cell layout. |
| `TripView.kt` / `Geo.kt` | Per-trip render state + geo helpers (haversine, gaps, speed bands). |
| `ChartView.kt` / `ScaleBarView.kt` | Elevation/speed charts + the map scale bar. |
| `SelfLocator.kt` / `LiveStore.kt` / `LivePointReceiver.kt` | Device location/heading + direct-mode live track. |
| `Prefs.kt` / `TagQueue.kt` | Persisted settings + batched tag writes. |

---

## Configuration

Build-time settings come from a **gitignored** `app/secrets.properties` (create it yourself — it
is never committed):

```properties
# Add-on base URL (default if omitted: http://172.20.1.100:8099)
tt_base_url=http://172.20.1.100:8099
# Admin token — must match the add-on's `app_token` option / secrets.yaml `trip_tracker_app_token`
tt_app_token=YOUR_TOKEN
# Optional: Google Maps SDK for Android key (enables the Google basemaps)
tt_maps_key=YOUR_MAPS_KEY
```

- `tt_base_url` and `tt_app_token` are baked into `BuildConfig`; they can also be changed at
  runtime from the in-app **⚙ settings** (panel header).
- `tt_maps_key` is injected as the `com.google.android.geo.API_KEY` manifest placeholder. It must
  be a **Maps SDK for Android** key restricted to applicationId `dev.ajithv.triptracker` **and**
  the signing-cert SHA-1 (the release is debug-signed — see below). Absent/invalid → Google
  basemaps blank, no crash.
- `local.properties` (`sdk.dir=…`, also gitignored) points Gradle at your Android SDK.

---

## Build & sign

No Gradle wrapper is checked in; use a standalone Gradle 8.7 with JDK 17.

```bash
# Build the (unsigned) release APK
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/jdk17 PATH=$HOME/jdk17/bin:$PATH \
  gradle assembleRelease
# -> app/build/outputs/apk/release/app-release-unsigned.apk
```

The release has **no signing config**, so sign it with the debug keystore before installing
(every build uses the same key, so in-place updates work):

```bash
BT=$HOME/Android/Sdk/build-tools/34.0.0
cd app/build/outputs/apk/release
$BT/zipalign -f -p 4 app-release-unsigned.apk aligned.apk
$BT/apksigner sign --ks ~/.android/debug.keystore \
  --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android \
  --out triptracker.apk aligned.apk
$BT/apksigner verify triptracker.apk
```

---

## Install / deliver

- Direct: `adb install -r triptracker.apk`.
- Over the air: the signed APK is typically dropped to the home server and pushed to Telegram via
  the Telegram-userbot add-on, landing in Saved Messages.

---

## Admin & the add-on token

The app hits `:8099`, which has no HA ingress session, so it is **non-admin by default**. Sending
the header `X-TT-Token: <token>` (from `tt_app_token`) makes the add-on treat the request as full
admin — enabling start/stop, spacing, rename, colour, hide, delete, sync, basemap-share, and
visibility of hidden trips. The token lives in the add-on's `app_token` option (falling back to
`secrets.yaml` → `trip_tracker_app_token`) and in `app/secrets.properties` here.

---

## Related projects

- **[ha-addon-trip-tracker](https://github.com/ajithvasudevan/ha-addon-trip-tracker)** — the
  Home Assistant add-on this app is a client of (Flask + MapLibre web UI; recorder-Postgres `trips`
  schema; immich photo matching).
- **[mymessages](https://github.com/ajithvasudevan/mymessages)** — supplies the live GPS track
  (its *Trip* tab publishes points over MQTT to the add-on, and broadcasts them on-device for the
  app's direct mode).

---

## Notable changes

- **r50–r51** — mouse-wheel/trackpad zoom (pointer-anchored); landscape photo pane moved to the
  **right 40%**, with the map, its control buttons, and the thumbnail grid reflowing into the left
  60% (mirrors the web `#mapwrap` flex layout) so nothing is hidden behind the pane.
- Manual track editor, OSM/OSRM gap-fill, native Google Maps as a second engine, and direct-mode
  live tracking landed in earlier revisions.
