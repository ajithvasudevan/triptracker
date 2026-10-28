package dev.ajithv.triptracker

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.MotionEvent
import android.view.Gravity
import android.view.InputDevice
import android.content.Context
import android.content.res.Configuration
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import java.util.Date
import kotlin.math.hypot

class MainActivity : AppCompatActivity() {
    // Scale the WHOLE UI up on large screens (tablets) so text/icons aren't tiny/sparse on a big display.
    // Phones (smallestWidth < 600dp) are left exactly as before.
    override fun attachBaseContext(newBase: Context) {
        val cfg = newBase.resources.configuration
        val sw = cfg.smallestScreenWidthDp
        val factor = when { sw >= 840 -> 1.55f; sw >= 600 -> 1.35f; else -> 1f }
        if (factor != 1f && cfg.densityDpi > 0) {
            val nc = Configuration(cfg)
            nc.densityDpi = (cfg.densityDpi * factor).toInt()
            super.attachBaseContext(newBase.createConfigurationContext(nc))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: Prefs
    private lateinit var api: Api
    private lateinit var repo: PhotoRepo
    private lateinit var liveStore: LiveStore
    private val useDirectLive: Boolean get() = prefs.liveSource == "direct"
    private lateinit var mapCtl: MapEngine        // the currently-visible engine (mlCtl or gCtl)
    private lateinit var mlCtl: MapController      // MapLibre (keyless) — always inited
    private lateinit var gCtl: GoogleMapController // native Google Maps SDK — inited lazily on first use

    private lateinit var mapView: MapView
    private lateinit var gmapView: com.google.android.gms.maps.MapView
    private var googleInited = false
    private var afterGoogleReady: (() -> Unit)? = null
    private var mapBootDone = false
    private lateinit var scaleBar: ScaleBarView
    private lateinit var selfLoc: SelfLocator
    private var selfLat: Double? = null
    private var selfLon: Double? = null
    private var selfHeading: Float? = null
    private var selfAcc: Float? = null
    private val REQ_LOC = 7001
    private lateinit var drawer: DrawerLayout

    private var cfg: Config? = null
    private var isAdmin = false

    // ----- manual track editor state -----
    private var editMode = false
    private var editTripId: Int? = null
    private val editSelected = LinkedHashSet<Long>()
    private var editAddArmed = false
    private var editTsAuto = false        // move → recompute ts by weighted distance (true) vs keep ts (false)
    private val editNewPts = HashSet<Long>()      // ids added this session (manual/fill) → always auto-timestamp
    private var editSelectMode = false            // box-select mode armed
    private var boxStartX = 0f; private var boxStartY = 0f
    private var joinState = 0             // 0 off · 1 awaiting first endpoint · 2 awaiting second
    private var joinFirstId = -1L
    private lateinit var editToolbar: View
    private lateinit var editHint: TextView
    // multi-level undo/redo for track edits
    private val undoStack = ArrayDeque<EditCmd>()
    private val redoStack = ArrayDeque<EditCmd>()

    private val views = LinkedHashMap<Int, TripView>()
    private var tripsById = mutableMapOf<Int, Trip>()
    private var activeTripId: Int? = null
    private var centeredTripId: Int? = null
    private var focusId: Int? = null
    private var latestTripId: Int? = null
    private var autoFollowLive = true
    private var followMode = false

    private var photos = listOf<Photo>()
    private var photoSorted = listOf<Photo>()
    private var curPhoto = -1
    private var fsOpen = false
    private var dockOpen = false
    private var gridOpen = false
    private var statsOpen = false
    private var chartFullOpen = false
    private var statsView: TripView? = null
    private var replayView: TripView? = null
    private var replayPos = 0.0
    private var replayPlaying = false
    private val replayHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val filterPeople = HashSet<String>()
    private val filterCameras = HashSet<String>()
    private var speedColorOn = false

    private var tickN = 0
    private var popupLatLng: LL? = null
    private var gridKey = ""
    private val PREVIEW_MAX = 1440
    private var startColor = "#1565c0"
    private var spacingPrefillId: Int? = null
    private var tripLive = false

    private val basemapKeys = listOf("carto", "streets", "topo", "sat",
        "groadmap", "gsatellite", "ghybrid", "gterrain")
    private val basemapLabels = listOf("CARTO Voyager", "Streets (Esri)", "Topographic", "Satellite",
        "Google Maps", "Google Satellite", "Google Hybrid", "Google Terrain")
    private val googleKeys = setOf("groadmap", "gsatellite", "ghybrid", "gterrain")
    private fun isGoogle(k: String) = k in googleKeys
    private val modeKeys = listOf("hybrid", "time", "distance")
    private val modeLabels = listOf("Hybrid (time + distance)", "By time", "By distance")

    // view refs
    private lateinit var tripAdapter: TripAdapter
    private lateinit var gridAdapter: GridAdapter
    private lateinit var speedPill: TextView
    private lateinit var trackPopup: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var pvImg: ZoomImageView
    private lateinit var pvCbRel: android.widget.CheckBox
    private lateinit var pvCbWorld: android.widget.CheckBox
    private lateinit var pvPlay: TextView
    private lateinit var pvMeta: TextView
    private lateinit var lbOverlay: FrameLayout
    private lateinit var lbImg: ZoomImageView
    private lateinit var lbVideo: VideoView
    private lateinit var lbMeta: TextView
    private lateinit var lbOrig: TextView
    private lateinit var lbTopleft: View   // container: Open-original + the tag checkboxes (fades with nav)
    private lateinit var lbClose: View
    private lateinit var lbTags: View
    private lateinit var cbRelatives: android.widget.CheckBox
    private lateinit var cbWorld: android.widget.CheckBox
    private lateinit var tagQueue: TagQueue
    private val assetTags = HashMap<String, MutableSet<String>>()   // asset -> current visibility tags (optimistic)
    private lateinit var gridOverlay: FrameLayout
    private lateinit var mapwrap: FrameLayout
    private lateinit var pgItems: RecyclerView
    private var gridPadBottomBase = 0
    private lateinit var pgCount: TextView
    private lateinit var pgTagCount: TextView                 // line-2 tag counts (👪 / 🌍)
    private lateinit var masterRel: android.widget.CheckBox   // "tag all Relatives" top-bar master
    private lateinit var masterWorld: android.widget.CheckBox // "tag all World" top-bar master
    private lateinit var filterPeopleBox: LinearLayout
    private lateinit var filterCameraBox: LinearLayout
    private lateinit var filterCount: TextView
    private lateinit var pageTrips: View
    private lateinit var pageFilter: View
    private lateinit var pageStart: View
    private lateinit var btnStart: Button
    private lateinit var btnApply: Button
    private lateinit var btnStop: Button
    private lateinit var ivRow: View
    private lateinit var distRow: View
    private lateinit var ivLabel: TextView
    private lateinit var distLabel: TextView
    private lateinit var ivSeek: SeekBar
    private lateinit var distSeek: SeekBar
    private lateinit var modeSpinner: Spinner
    private lateinit var tripName: EditText
    private lateinit var tripColorSw: View
    private lateinit var btnLocate: ImageButton
    private lateinit var btnCompass: ImageButton
    private lateinit var pvPrev: ImageButton
    private lateinit var pvNext: ImageButton
    private lateinit var lbPrev: ImageButton
    private lateinit var lbNext: ImageButton
    private var navShown = false
    private val navHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val navHideRunnable = Runnable { navShown = false; applyNav() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        api = Api(this, prefs)
        repo = PhotoRepo(api)
        liveStore = LiveStore(this)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        drawer = findViewById(R.id.drawer_layout)
        mapView = findViewById(R.id.map)
        gmapView = findViewById(R.id.gmap)
        bindViews()
        setupPanel()
        setupPhotoViewer()
        setupGrid()

        mlCtl = MapController(mapView,
            onPhotoClick = { asset -> openPhoto(asset, false) },
            onMapTap = { lat, lon -> onMapTap(lat, lon) },
            onUserGesture = { setFollow(false) })
        gCtl = GoogleMapController(gmapView, resources.displayMetrics.density,
            onPhotoClick = { asset -> openPhoto(asset, false) },
            onMapTap = { lat, lon -> onMapTap(lat, lon) },
            onUserGesture = { setFollow(false) })
        // Each engine re-renders ITS OWN overlays when its style/map becomes ready (not the active one's).
        mlCtl.onStyleReady = { renderOn(mlCtl) }
        gCtl.onStyleReady = { renderOn(gCtl) }
        mlCtl.onCameraChanged = { onCamChanged() }
        gCtl.onCameraChanged = { onCamChanged() }
        // Both MapViews are live at once (only visibility differs) → both get the activity lifecycle.
        // Separate sub-Bundles so the two MapViews' saved state can't collide on shared keys.
        mlCtl.onCreate(savedInstanceState?.getBundle("ml_state"))
        gCtl.onCreate(savedInstanceState?.getBundle("g_state"))
        mapCtl = mlCtl

        // device location + compass heading → the Google map's orientation cone
        selfLoc = SelfLocator(this) { lat, lon, hd, acc ->
            selfLat = lat; selfLon = lon; selfHeading = hd; selfAcc = acc
            mapCtl.setSelf(lat, lon, hd, acc)
        }
        if (!selfLoc.hasLocPerm())
            androidx.core.app.ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOC)

        ui.launch { boot() }
    }

    private fun bindViews() {
        speedPill = findViewById(R.id.speed_pill)
        trackPopup = findViewById(R.id.track_popup)
        dock = findViewById(R.id.dock)
        pvImg = findViewById(R.id.pv_img)
        pvCbRel = findViewById(R.id.pv_cb_rel); pvCbWorld = findViewById(R.id.pv_cb_world)
        pvPlay = findViewById(R.id.pv_play)
        pvMeta = findViewById(R.id.pv_meta)
        lbOverlay = findViewById(R.id.lb_overlay)
        lbImg = findViewById(R.id.lb_img)
        lbVideo = findViewById(R.id.lb_video)
        lbMeta = findViewById(R.id.lb_meta)
        lbOrig = findViewById(R.id.lb_orig)
        lbTopleft = findViewById(R.id.lb_topleft)
        lbClose = findViewById(R.id.lb_close)
        lbTags = findViewById(R.id.lb_tags)
        cbRelatives = findViewById(R.id.cb_relatives)
        cbWorld = findViewById(R.id.cb_world)
        tagQueue = TagQueue(this)
        gridOverlay = findViewById(R.id.grid_overlay)
        mapwrap = findViewById(R.id.mapwrap)
        pgItems = findViewById(R.id.pg_items)
        pgCount = findViewById(R.id.pg_count)
        pgTagCount = findViewById(R.id.pg_tagcount)
        filterPeopleBox = findViewById(R.id.filter_people)
        filterCameraBox = findViewById(R.id.filter_camera)
        filterCount = findViewById(R.id.filter_count)
        pageTrips = findViewById(R.id.page_trips)
        pageFilter = findViewById(R.id.page_filter)
        pageStart = findViewById(R.id.page_start)
        btnStart = findViewById(R.id.btn_start)
        btnApply = findViewById(R.id.btn_apply)
        btnStop = findViewById(R.id.btn_stop)
        ivRow = findViewById(R.id.iv_row)
        distRow = findViewById(R.id.dist_row)
        ivLabel = findViewById(R.id.iv_label)
        distLabel = findViewById(R.id.dist_label)
        ivSeek = findViewById(R.id.iv_seek)
        distSeek = findViewById(R.id.dist_seek)
        modeSpinner = findViewById(R.id.mode_spinner)
        tripName = findViewById(R.id.trip_name)
        tripColorSw = findViewById(R.id.trip_color_sw)
        btnLocate = findViewById(R.id.btn_locate)
        btnCompass = findViewById(R.id.btn_compass)
        btnCompass.setOnClickListener { mapCtl.currentCamera()?.let { c -> mapCtl.moveTo(Cam(c.lat, c.lon, c.zoom, 0.0)) } }
        scaleBar = findViewById(R.id.scale_bar)

        // dock geometry (portrait: bottom 44% · landscape: right 40%, like the web app)
        applyDockGeometry()

        // mouse-wheel / trackpad scroll → zoom the active map, anchored at the pointer
        val wheelZoom = View.OnGenericMotionListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
                val vs = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (vs != 0f) { mapCtl.zoomBy(vs.toDouble() * 0.5, ev.x, ev.y); return@OnGenericMotionListener true }
            }
            false
        }
        mapView.setOnGenericMotionListener(wheelZoom)
        gmapView.setOnGenericMotionListener(wheelZoom)

        findViewById<ImageButton>(R.id.btn_menu).setOnClickListener { drawer.openDrawer(findViewByGravityStart()) }
        findViewById<ImageButton>(R.id.btn_grid).setOnClickListener { if (gridOpen) closeGrid() else openGrid() }
        btnLocate.setOnClickListener { locateTraveler() }
        findViewById<ImageButton>(R.id.btn_stats).setOnClickListener { openStats() }
        findViewById<ImageButton>(R.id.btn_edit_track).setOnClickListener { editCurrentTrip() }
        findViewById<ImageButton>(R.id.stats_close).setOnClickListener { closeStats() }
        findViewById<Button>(R.id.btn_export_gpx).setOnClickListener { liveOrFocusView()?.let { v -> exportTrip(v, "gpx") } }
        findViewById<Button>(R.id.btn_export_geojson).setOnClickListener { liveOrFocusView()?.let { v -> exportTrip(v, "geojson") } }
        findViewById<Button>(R.id.btn_chart_fit).setOnClickListener {
            findViewById<ChartView>(R.id.chart_elev).fit(); findViewById<ChartView>(R.id.chart_speed).fit()
        }
        findViewById<ChartView>(R.id.chart_elev).onMaximize = { openChartFull(R.id.chart_elev) }
        findViewById<ChartView>(R.id.chart_speed).onMaximize = { openChartFull(R.id.chart_speed) }
        findViewById<ImageButton>(R.id.chart_full_close).setOnClickListener { closeChartFull() }
        findViewById<ChartView>(R.id.chart_full).showMaximize = false   // already maximised
        findViewById<ImageButton>(R.id.btn_replay_map).setOnClickListener { openReplay() }
        findViewById<ImageButton>(R.id.btn_replay_play).setOnClickListener { if (replayPlaying) pauseReplay() else playReplay() }
        findViewById<ImageButton>(R.id.btn_replay_close).setOnClickListener { closeReplay() }
        findViewById<SeekBar>(R.id.sk_replay).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) { replayPos = p.toDouble(); drawReplayAt() } }
            override fun onStartTrackingTouch(s: SeekBar) { pauseReplay() }
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        findViewById<TextView>(R.id.tp_close).setOnClickListener { popupLatLng = null; trackPopup.visibility = View.GONE }
    }

    private fun findViewByGravityStart(): View = (drawer.getChildAt(1))

    // ----- panel wiring -----
    private fun setupPanel() {
        val tabs = findViewById<TabLayout>(R.id.tabs)
        tabs.addTab(tabs.newTab().setText("Trips"))
        tabs.addTab(tabs.newTab().setText("Start"))
        tabs.addTab(tabs.newTab().setText("Filter"))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) { showPage(t.position) }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })

        tripAdapter = TripAdapter(
            onToggle = { t -> autoFollowLive = false; ui.launch { toggleTrip(t.id) } },
            onMenu = { t -> showTrackPopup(t) },
        )
        findViewById<RecyclerView>(R.id.trip_list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity); adapter = tripAdapter
        }

        val bmSpinner = findViewById<Spinner>(R.id.basemap_spinner)
        bmSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, basemapLabels)
        bmSpinner.setSelection(basemapKeys.indexOf(prefs.basemap).coerceAtLeast(0))
        bmSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!mapBootDone) return   // the initial basemap is applied by boot(), not this listener
                val key = basemapKeys[pos]
                if (key == prefs.basemap && mapCtl.isReady) return
                selectBasemap(key)
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        val sw = findViewById<SwitchCompat>(R.id.speed_switch)
        sw.isChecked = prefs.speedColor
        sw.setOnCheckedChangeListener { _, c -> speedColorOn = c; prefs.speedColor = c; renderTracks() }

        findViewById<ImageButton>(R.id.btn_reload).setOnClickListener { ui.launch { runCatching { refreshTrips() } } }
        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener { openSettings() }
        setupEditToolbar()
        findViewById<Button>(R.id.filter_clear).setOnClickListener {
            filterPeople.clear(); filterCameras.clear(); rebuildFilterOptions(); applyPhotoFilter()
        }

        // start page controls
        modeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modeLabels)
        modeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) { syncSpacingRows() }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        ivSeek.setOnSeekBarChangeListener(simpleSeek { ivLabel.text = (it + 1).toString() })
        distSeek.setOnSeekBarChangeListener(simpleSeek { distLabel.text = (it + 1).toString() })
        setStartColor(startColor)
        findViewById<Button>(R.id.trip_color_btn).setOnClickListener { pickColor(startColor) { setStartColor(it) } }

        btnStart.setOnClickListener { doStart() }
        btnApply.setOnClickListener { doApply() }
        btnStop.setOnClickListener { ui.launch { runCatching { api.stop() }; refreshTrips() } }
        showPage(0)
    }

    private fun showPage(pos: Int) {
        pageTrips.visibility = if (pos == 0) View.VISIBLE else View.GONE
        pageStart.visibility = if (pos == 1) View.VISIBLE else View.GONE
        pageFilter.visibility = if (pos == 2) View.VISIBLE else View.GONE
        if (pos == 2) rebuildFilterOptions()   // always reflect the currently shown photos when opened
    }

    private fun simpleSeek(cb: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { cb(p) }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    // ----- boot + polling -----
    private suspend fun boot() {
        val st = try { api.state() } catch (e: Exception) {
            toast("Can't reach add-on: ${e.message}"); return
        }
        cfg = st.config; isAdmin = st.admin
        applyAdminVisibility()
        speedColorOn = prefs.speedColor
        val bm = normalizeBasemap(if (isAdmin) prefs.basemap else st.adminBasemap)
        prefs.basemap = bm
        findViewById<Spinner>(R.id.basemap_spinner).setSelection(basemapKeys.indexOf(bm).coerceAtLeast(0))
        val c = st.config.map
        // MapLibre always inits (keyless, reliable). If the saved basemap is a Google one, switch to it once
        // MapLibre has a camera to hand off from.
        mlCtl.init(c.lat, c.lon, c.zoom, if (isGoogle(bm)) "carto" else bm) {
            mapBootDone = true
            if (isGoogle(bm)) selectBasemap(bm)
            ui.launch {
                runCatching { refreshTrips() }
                activeTripId = if (st.active) st.trip?.id else null
                if (st.active && st.trip != null) setOnlyTrip(st.trip.id, true)
                else latestTripId?.let { setOnlyTrip(it, true) }
                updateActiveBox(st.active, st.trip)
                updatePill()
                startPolling()
            }
        }
    }

    private fun normalizeBasemap(k: String): String = if (k in basemapKeys) k else "carto"

    private fun startPolling() {
        ui.launch {
            var sec = 0
            while (isActive) {
                delay(1000); updatePill(); sec++
                if (sec % 4 == 0) runCatching { tick() }
            }
        }
    }

    private suspend fun tick() {
        val st = api.state()
        activeTripId = if (st.active) st.trip?.id else null
        if (st.active && st.trip != null) { tripsById[st.trip.id] = st.trip; views[st.trip.id]?.applyMeta(st.trip) }
        updateActiveBox(st.active, st.trip)
        tickN++
        if (tickN % 3 == 0) runCatching { refreshTrips() }
        if (st.active && autoFollowLive && st.trip != null && !views.containsKey(st.trip.id)) {
            setOnlyTrip(st.trip.id, true); runCatching { refreshTrips() }; updatePill(); return
        }
        val lv = activeTripId?.let { views[it] }
        if (lv != null) appendViewData(lv, false)
        updatePill()
        if (lv != null && centeredTripId != activeTripId && lv.pts.isNotEmpty()) {
            val h = lv.pts.last()
            mapCtl.setView(h.lat, h.lon, maxOf(mapCtl.zoom(), 16.0))
            centeredTripId = activeTripId
        }
    }

    // ----- trips -----
    private suspend fun refreshTrips() {
        val trips = api.trips()
        tripsById = trips.associateBy { it.id }.toMutableMap()
        latestTripId = trips.firstOrNull()?.id
        for (t in trips) views[t.id]?.applyMeta(t)
        val gone = views.keys.filter { !tripsById.containsKey(it) }
        if (gone.isNotEmpty()) {
            gone.forEach { views.remove(it); if (focusId == it) focusId = null }
            rebuildPhotos(); renderTracks(); renderPhotos(); if (views.isEmpty()) closeDock()
        }
        tripAdapter.submit(trips, views.keys, isAdmin)
    }

    private fun addTripView(id: Int): TripView {
        val v = TripView(id); v.applyMeta(tripsById[id]); views[id] = v; return v
    }

    private suspend fun setOnlyTrip(id: Int, fit: Boolean) {
        popupLatLng = null; trackPopup.visibility = View.GONE
        views.clear(); focusId = id
        photos = emptyList(); photoSorted = emptyList(); curPhoto = -1
        setFollow(false); closeDock()
        val v = addTripView(id)
        tripAdapter.submit(tripAdapter.let { tripsById.values.sortedByDescending { t -> t.startedAt } }, views.keys, isAdmin)
        renderTracks(); renderPhotos()
        appendViewData(v, fit)
    }

    private suspend fun toggleTrip(id: Int) {
        if (views.containsKey(id)) {
            views.remove(id); if (focusId == id) focusId = null
            rebuildPhotos(); renderTracks(); renderPhotos()
            tripAdapter.submit(tripsById.values.sortedByDescending { it.startedAt }, views.keys, isAdmin)
            if (views.isEmpty()) closeDock()
            return
        }
        val v = addTripView(id); focusId = id
        tripAdapter.submit(tripsById.values.sortedByDescending { it.startedAt }, views.keys, isAdmin)
        appendViewData(v, true)
    }

    private suspend fun appendViewData(v: TripView, fit: Boolean) {
        val mergeLive = useDirectLive && v.id == activeTripId
        val prevCount = v.pts.size
        val oldLast = v.pts.lastOrNull()
        if (mergeLive) v.reseedKeys()   // keep tsKeys consistent if the trip was first loaded add-on-only

        // (1) add-on points — always fetched (history + backfill; the source of truth once a trip is stopped)
        val pts = runCatching { api.track(v.id, v.lastPointId) }.getOrNull() ?: emptyList()
        if (pts.isNotEmpty()) v.lastPointId = maxOf(v.lastPointId, pts.maxOf { it.id })
        // (2) direct phone fixes from the local store (outage-proof; may run ahead of the add-on)
        val live = if (mergeLive) liveStore.pointsSince(v.id, v.lastLiveMs) else emptyList()
        if (live.isNotEmpty()) v.lastLiveMs = maxOf(v.lastLiveMs, live.maxOf { it.id })   // LiveStore id == ts_ms

        var anyNew = false; var minNewTs = Double.MAX_VALUE
        if (mergeLive) {
            for (p in pts)  if (v.addPoint(p)) { anyNew = true; if (p.ts < minNewTs) minNewTs = p.ts }
            for (p in live) if (v.addPoint(p)) { anyNew = true; if (p.ts < minNewTs) minNewTs = p.ts }
        } else if (pts.isNotEmpty()) {
            v.pts.addAll(pts); anyNew = true; minNewTs = pts.first().ts   // add-on-only fast path (no dedup set)
        }
        if (anyNew) {
            val reorder = oldLast != null && minNewTs < oldLast.ts
            if (reorder) v.pts.sortWith(compareBy({ it.ts }, { it.id }))
            val last = v.pts.last()
            v.latestSpeed = last.speed; v.latestSpeedTs = last.ts
            // Steady live tick → extend incrementally; first load / fit / reorder / a gap → full redraw.
            val tryInc = !fit && prevCount > 0 && !reorder && v.id == activeTripId
            if (!(tryInc && mapCtl.appendLive(v, activeTripId, speedColorOn))) renderTracks()
        }
        val phs = runCatching { api.photos(v.id, v.lastPhotoId) }.getOrNull() ?: emptyList()
        var added = false
        val have = v.photos.map { it.asset }.toHashSet()
        for (ph in phs) {
            if (!have.contains(ph.asset)) { v.photos.add(ph); have.add(ph.asset); added = true }
            v.lastPhotoId = maxOf(v.lastPhotoId, ph.id)
        }
        if (phs.isNotEmpty()) ingestPhotoTags(phs)
        if (added) { rebuildPhotos(); renderPhotos() }
        if (fit && v.pts.isNotEmpty()) { mapCtl.fitViews(views.values); centeredTripId = v.id }
        followIfNeeded()
        if (statsOpen && statsView === v) populateStats(v)   // live-update the stats panel as the track grows
    }

    private suspend fun doSync(t: Trip) {
        toast("Scanning immich…")
        val r = runCatching { api.syncPhotos(t.id) }.getOrNull()
        views[t.id]?.let { it.lastPhotoId = 0; it.photos.clear(); appendViewData(it, false) }
        refreshTrips()
        if (r != null) toast("Added ${r.optInt("added")}, scanned ${r.optInt("scanned")}")
    }

    // ----- photos / filter -----
    private fun photoPasses(ph: Photo): Boolean {
        if (filterPeople.isNotEmpty()) { if (!filterPeople.all { ph.people.contains(it) }) return false }
        if (filterCameras.isNotEmpty() && !filterCameras.contains(ph.camera ?: "")) return false
        return true
    }

    private fun sortPhotos() {
        val seen = HashSet<String>()
        photoSorted = photos.sortedBy { it.ts ?: 0.0 }.filter { photoPasses(it) && seen.add(it.asset) }
    }

    private fun currentAsset(): String? = if (curPhoto in photoSorted.indices) photoSorted[curPhoto].asset else null

    private fun rebuildPhotos() {
        photos = views.values.flatMap { it.photos }
        val ca = currentAsset()
        if (ca != null && photos.none { it.asset == ca }) closeDock()
        rebuildFilterOptions()
        if (gridOpen) renderGrid()
    }

    private fun rebuildFilterOptions() {
        val people = sortedSetOf<String>(); val cams = sortedSetOf<String>()
        for (ph in photos) { ph.people.forEach { people.add(it) }; cams.add(ph.camera ?: "") }
        filterPeople.retainAll(people); filterCameras.retainAll(cams)
        renderFilterGroup(filterPeopleBox, people.toList(), filterPeople, "(unknown)")
        renderFilterGroup(filterCameraBox, cams.toList(), filterCameras, "(no camera)")
        updateFilterCount()
    }

    private fun renderFilterGroup(box: LinearLayout, options: List<String>, sel: HashSet<String>, emptyLabel: String) {
        box.removeAllViews()
        if (options.isEmpty()) {
            val tv = TextView(this); tv.text = "none"; tv.setTextColor(Color.parseColor("#9aa5b1")); box.addView(tv); return
        }
        val tint = android.content.res.ColorStateList.valueOf(Color.parseColor("#4da3ff"))
        for (opt in options) {
            val cb = CheckBox(this)
            cb.text = if (opt.isEmpty()) emptyLabel else opt
            cb.setTextColor(Color.parseColor("#e6edf3"))
            androidx.core.widget.CompoundButtonCompat.setButtonTintList(cb, tint)
            cb.isChecked = sel.contains(opt)
            cb.setOnCheckedChangeListener { _, c -> if (c) sel.add(opt) else sel.remove(opt); applyPhotoFilter() }
            box.addView(cb)
        }
    }

    private fun filterShownCount(): Int {
        val seen = HashSet<String>(); for (ph in photos) if (photoPasses(ph)) seen.add(ph.asset); return seen.size
    }
    private fun updateFilterCount() {
        val n = filterShownCount(); filterCount.text = "$n image${if (n == 1) "" else "s"}"
    }

    private fun applyPhotoFilter() {
        renderPhotos(); updateFilterCount()
        if (gridOpen) renderGrid()
        if (dockOpen) {
            val ca = currentAsset(); sortPhotos()
            val i = if (ca != null) photoSorted.indexOfFirst { it.asset == ca } else -1
            if (i < 0) closeDock() else { curPhoto = i; renderPhotos(); updateGridCurrent() }
        }
    }

    private fun renderTracks() { if (mapCtl.isReady) mapCtl.renderTracks(views.values, activeTripId, speedColorOn) }
    private val lastClusters = ArrayList<PhotoMarkerSpec>()   // what's drawn now (for cluster-tap hit testing)
    private var lastClusterZoom = -999.0
    private fun renderPhotos() {
        if (!mapCtl.isReady) return
        sortPhotos()
        lastClusterZoom = mapCtl.zoom()
        lastClusters.clear(); lastClusters.addAll(clusterPhotos(photoSorted, currentAsset(), mapCtl))
        mapCtl.renderPhotos(lastClusters)
    }
    // Screen-space greedy clustering: photos within ~34 dp on screen fold into one count badge. The CURRENT
    // (selected) photo always stays its own dot so it's never hidden inside a cluster.
    private fun clusterPhotos(photos: List<Photo>, currentAsset: String?, eng: MapEngine): List<PhotoMarkerSpec> {
        val out = ArrayList<PhotoMarkerSpec>()
        if (photos.isEmpty()) return out
        val pxR = 34f * resources.displayMetrics.density
        val screens = photos.map { eng.screenOf(it.lat, it.lon) }
        val claimed = BooleanArray(photos.size)
        for (i in photos.indices) if (photos[i].asset == currentAsset) {
            out.add(PhotoMarkerSpec(photos[i].lat, photos[i].lon, photos[i].asset, 1, true)); claimed[i] = true
        }
        for (i in photos.indices) {
            if (claimed[i]) continue
            claimed[i] = true
            val si = screens[i] ?: run { out.add(PhotoMarkerSpec(photos[i].lat, photos[i].lon, photos[i].asset, 1, false)); null } ?: continue
            var cnt = 1; var sumLat = photos[i].lat; var sumLon = photos[i].lon
            for (j in i + 1 until photos.size) {
                if (claimed[j]) continue
                val sj = screens[j] ?: continue
                if (hypot((si.x - sj.x).toDouble(), (si.y - sj.y).toDouble()) < pxR) {
                    claimed[j] = true; cnt++; sumLat += photos[j].lat; sumLon += photos[j].lon
                }
            }
            if (cnt == 1) out.add(PhotoMarkerSpec(photos[i].lat, photos[i].lon, photos[i].asset, 1, false))
            else out.add(PhotoMarkerSpec(sumLat / cnt, sumLon / cnt, null, cnt, false))
        }
        return out
    }
    // Photos folded into the tapped cluster = those whose screen position is within the clustering radius of
    // the cluster centroid (exact for the co-located case this guards; good enough for a spread cluster).
    private fun clusterMembersNear(clu: PhotoMarkerSpec): List<Photo> {
        val cs = mapCtl.screenOf(clu.lat, clu.lon) ?: return emptyList()
        val pxR = 40f * resources.displayMetrics.density
        return photoSorted.filter { ph ->
            val s = mapCtl.screenOf(ph.lat, ph.lon) ?: return@filter false
            hypot((cs.x - s.x).toDouble(), (cs.y - s.y).toDouble()) <= pxR
        }
    }
    // Largest ground distance between any two cluster members (metres). ~0 ⇒ same spot ⇒ no zoom can split them.
    private fun clusterSpanM(members: List<Photo>): Double {
        var mx = 0.0
        for (i in members.indices) for (j in i + 1 until members.size) {
            val d = Geo.haversine(members[i].lat, members[i].lon, members[j].lat, members[j].lon)
            if (d > mx) mx = d
        }
        return mx
    }
    // A small thumbnail grid to pick one photo out of a cluster that zooming can't pull apart.
    private fun openClusterPicker(members: List<Photo>) {
        if (members.isEmpty()) return
        if (members.size == 1) { openPhoto(members[0].asset, false); return }
        val ordered = members.sortedBy { it.ts ?: 0.0 }
        val rv = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 3)
            setBackgroundColor(android.graphics.Color.BLACK)
            setPadding(1, 1, 1, 1); clipToPadding = false   // 1 px outer frame → 2 px gaps with the item decoration
            addItemDecoration(object : androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
                override fun getItemOffsets(outRect: android.graphics.Rect, view: View, parent: androidx.recyclerview.widget.RecyclerView, s: androidx.recyclerview.widget.RecyclerView.State) {
                    outRect.set(1, 1, 1, 1)   // 1 px each side → 2 px between adjacent thumbnails
                }
            })
        }
        val dlg = AlertDialog.Builder(this).setTitle("${members.size} photos here").setView(rv)
            .setNegativeButton("Close", null).create()
        rv.adapter = GridAdapter(api, repo, onClick = { p -> dlg.dismiss(); openPhoto(p.asset, false) }).also { it.submit(ordered) }
        dlg.show()
    }
    // Render tracks + photos onto a SPECIFIC engine (used when an engine's style becomes ready, and when a
    // just-switched-in engine must be brought up to date even though it isn't the active one yet).
    private fun onCamChanged() {
        positionPopup(); updateScaleBar(); updateCompass()
        // re-cluster the photos when the zoom changes enough (clustering is screen-distance based)
        if (mapCtl.isReady && photoSorted.isNotEmpty() && kotlin.math.abs(mapCtl.zoom() - lastClusterZoom) > 0.25) renderPhotos()
    }
    private fun renderOn(e: MapEngine) {
        if (!e.isReady) return
        e.renderTracks(views.values, activeTripId, speedColorOn)
        sortPhotos(); e.renderPhotos(clusterPhotos(photoSorted, currentAsset(), e))
    }

    // ----- basemap switching (seamless MapLibre <-> Google) -----
    // Hand the camera off to the incoming engine and re-render its overlays BEFORE flipping visibility, so
    // the swap shows no flash and keeps the exact map view.
    private fun selectBasemap(key: String) {
        val src = if (mapCtl.isReady) mapCtl.currentCamera() else null
        prefs.basemap = key
        if (isGoogle(key)) {
            ensureGoogleInit {
                gCtl.setBasemap(key)
                src?.let { gCtl.moveTo(it) }
                renderOn(gCtl)
                activate(gCtl)
            }
        } else {
            src?.let { mlCtl.moveTo(it) }
            mlCtl.setBasemap(key) { renderOn(mlCtl); activate(mlCtl) }
        }
        // The addon's web viewer can't render Google basemaps → only push keyless keys to the shared admin pref.
        if (isAdmin && !isGoogle(key)) ui.launch { runCatching { api.setBasemap(key) } }
    }
    private fun ensureGoogleInit(then: () -> Unit) {
        if (gCtl.isReady) { then(); return }
        afterGoogleReady = then
        if (googleInited) return
        googleInited = true
        val cam = mapCtl.currentCamera()
        val m = cfg?.map
        gCtl.init(cam?.lat ?: m?.lat ?: 0.0, cam?.lon ?: m?.lon ?: 0.0,
            (cam?.zoom ?: m?.zoom?.toDouble() ?: 13.0).toInt(), prefs.basemap) {
            val cb = afterGoogleReady; afterGoogleReady = null; cb?.invoke()
        }
    }
    private fun activate(e: MapEngine) {
        mapCtl = e
        mlCtl.view.visibility = if (e === mlCtl) View.VISIBLE else View.GONE
        gCtl.view.visibility = if (e === gCtl) View.VISIBLE else View.GONE
        positionPopup(); updateScaleBar(); updateCompass()
        e.setSelf(selfLat, selfLon, selfHeading, selfAcc)   // show the orientation cone immediately on the new engine
        if (mapCtl.isReady && photoSorted.isNotEmpty()) {   // refresh the cluster-tap model for the now-active engine
            lastClusterZoom = mapCtl.zoom(); lastClusters.clear(); lastClusters.addAll(clusterPhotos(photoSorted, currentAsset(), mapCtl))
        }
    }
    private fun updateScaleBar() { scaleBar.setMpp(mapCtl.metersPerPixel()) }
    // Custom compass (btn_compass, top-right under the play button). Rotates with the map bearing; shown only
    // when rotated; tap animates back to north-up. Works on either engine (Google's native compass is disabled).
    private fun updateCompass() {
        if (!::btnCompass.isInitialized) return
        val brg = (if (mapCtl.isReady) mapCtl.currentCamera()?.bearing else null) ?: 0.0
        btnCompass.rotation = (-brg).toFloat()
        val b = ((brg % 360.0) + 360.0) % 360.0
        btnCompass.visibility = if (b > 0.5 && b < 359.5) View.VISIBLE else View.GONE
    }

    private fun openPhoto(key: Any, fullscreen: Boolean, fromSwipe: Boolean = false) {
        sortPhotos()
        if (photoSorted.isEmpty()) return
        var i = if (key is Int) key else photoSorted.indexOfFirst { it.asset == key }
        if (i < 0) i = 0
        curPhoto = i.coerceIn(0, photoSorted.size - 1)
        val p = photoSorted[curPhoto]
        renderPhotos()
        showDock(p)
        if (fullscreen) showFullscreen(p)
        preloadAround(curPhoto)
        updateGridCurrent()
        if (fromSwipe) applyNav() else showNav()   // swipe-driven change must NOT re-show the arrows
    }
    private fun navPhoto(d: Int, fromSwipe: Boolean = false) { if (photoSorted.isNotEmpty()) openPhoto(curPhoto + d, fsOpen, fromSwipe) }

    private fun preloadAround(i: Int) {
        fun warm(p: Photo?) { if (p != null && p.type != "video") repo.preload(api.previewUrl(p.asset), PREVIEW_MAX) }
        warm(photoSorted.getOrNull(i))
        for (d in 1..5) { warm(photoSorted.getOrNull(i + d)); warm(photoSorted.getOrNull(i - d)) }
    }

    private fun showDock(p: Photo) {
        dockOpen = true; dock.visibility = View.VISIBLE; dock.bringToFront()   // above the grid overlay when opened from the grid
        applyGridDockPad()
        pvPlay.visibility = if (p.type == "video") View.VISIBLE else View.GONE
        repo.show(pvImg, api.previewUrl(p.asset), PREVIEW_MAX)
        pvImg.reset()
        pvMeta.text = metaText(p)
        bindTagChecks(pvCbRel, pvCbWorld, p)
    }

    // Wire a pair of Relatives/World corner checkboxes to a photo (admin only); shared by the dock preview.
    private fun bindTagChecks(cbRel: android.widget.CheckBox, cbWorld: android.widget.CheckBox, p: Photo) {
        val vis = if (isAdmin) View.VISIBLE else View.GONE
        cbRel.visibility = vis; cbWorld.visibility = vis
        if (!isAdmin) return
        val tags = assetTags.getOrPut(p.asset) { p.tags.toMutableSet() }
        cbRel.setOnCheckedChangeListener(null); cbWorld.setOnCheckedChangeListener(null)
        cbRel.isChecked = tags.contains("Relatives")
        cbWorld.isChecked = tags.contains("World")
        cbRel.setOnCheckedChangeListener { _, on -> tagPhoto(p.asset, "Relatives", on) }
        cbWorld.setOnCheckedChangeListener { _, on -> tagPhoto(p.asset, "World", on) }
    }
    private fun closeDock() {
        if (!dockOpen && curPhoto < 0) return
        dockOpen = false; dock.visibility = View.GONE
        applyGridDockPad()
        curPhoto = -1
        resetNav()
        if (fsOpen) closeFullscreen()
        renderPhotos(); updateGridCurrent()
    }
    // Reserve the dock's height as bottom padding on the grid while the dock is open, so the grid's usable
    // area ends at the dock's top edge (not behind it). clipToPadding is on, so rows scroll fully clear of
    // the dock and nothing is drawn under it.
    private fun applyGridDockPad() {
        applyLandscapeSplit()
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val open = dockOpen && dock.visibility == View.VISIBLE
        val glp = gridOverlay.layoutParams as FrameLayout.LayoutParams
        if (land) {
            // dock is the right 40% column → confine the grid overlay to the left 60% (web flex layout) so the
            // grid header + thumbnails never sit under the dock; no bottom padding in this mode.
            val mr = if (open) (dock.layoutParams?.width ?: 0) else 0
            if (glp.rightMargin != mr) { glp.rightMargin = mr; gridOverlay.layoutParams = glp }
            if (pgItems.paddingBottom != gridPadBottomBase)
                pgItems.setPadding(pgItems.paddingLeft, pgItems.paddingTop, pgItems.paddingRight, gridPadBottomBase)
        } else {
            if (glp.rightMargin != 0) { glp.rightMargin = 0; gridOverlay.layoutParams = glp }
            val h = dock.layoutParams?.height ?: 0
            val extra = if (open && h > 0) h else 0
            val want = gridPadBottomBase + extra
            if (pgItems.paddingBottom != want)
                pgItems.setPadding(pgItems.paddingLeft, pgItems.paddingTop, pgItems.paddingRight, want)
        }
    }

    // Landscape split: when the 40% photo pane (dock) is open, shrink the map-side region (map + its control
    // buttons, the @id/mapwrap wrapper) to the left 60% so the right-edge buttons stay visible beside the pane
    // (the web #mapwrap flex layout). rightMargin on the wrapper = dock width; 0 otherwise.
    private fun applyLandscapeSplit() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val open = dockOpen && dock.visibility == View.VISIBLE
        val m = if (land && open) (dock.layoutParams?.width ?: 0) else 0
        val lp = mapwrap.layoutParams as FrameLayout.LayoutParams
        if (lp.rightMargin != m) { lp.rightMargin = m; mapwrap.layoutParams = lp }
    }

    // Dock geometry: portrait = bottom 44% (full width); landscape = right 40% (full height), mirroring the
    // web app's "right 40% on desktop, bottom 40% on mobile". Re-applied on rotation (activity handles configChanges).
    private fun applyDockGeometry() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val lp = dock.layoutParams as FrameLayout.LayoutParams
        if (land) {
            lp.width = (resources.displayMetrics.widthPixels * 0.40).toInt()
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.END
        } else {
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = (resources.displayMetrics.heightPixels * 0.44).toInt()
            lp.gravity = Gravity.BOTTOM
        }
        dock.layoutParams = lp
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyDockGeometry()
        applyGridDockPad()
    }

    private fun showFullscreen(p: Photo) {
        lbOverlay.visibility = View.VISIBLE; lbOverlay.bringToFront()
        fsOpen = true
        applyImmersive(true)
        lbOrig.setOnClickListener {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(api.originalUrl(p.asset))))
        }
        lbMeta.text = metaText(p)
        lbMeta.animate().cancel(); lbMeta.alpha = 1f; lbMeta.visibility = View.VISIBLE
        // admin-only visibility tags (checkbox -> tag the displayed photo; offline-durable via TagQueue)
        if (isAdmin) {
            lbTags.visibility = View.VISIBLE
            val tags = assetTags.getOrPut(p.asset) { p.tags.toMutableSet() }
            cbRelatives.setOnCheckedChangeListener(null); cbWorld.setOnCheckedChangeListener(null)
            cbRelatives.isChecked = tags.contains("Relatives")
            cbWorld.isChecked = tags.contains("World")
            cbRelatives.setOnCheckedChangeListener { _, on -> tagPhoto(p.asset, "Relatives", on) }
            cbWorld.setOnCheckedChangeListener { _, on -> tagPhoto(p.asset, "World", on) }
        } else lbTags.visibility = View.GONE
        if (p.type == "video") {
            lbImg.visibility = View.GONE
            lbVideo.visibility = View.VISIBLE
            lbVideo.setVideoURI(Uri.parse(api.videoUrl(p.asset)))
            lbVideo.setOnPreparedListener { it.isLooping = false; lbVideo.start() }
        } else {
            lbVideo.stopPlayback(); lbVideo.visibility = View.GONE
            lbImg.visibility = View.VISIBLE
            repo.show(lbImg, api.previewUrl(p.asset), PREVIEW_MAX); lbImg.reset()
        }
    }
    private fun closeFullscreen() {
        lbOverlay.visibility = View.GONE
        runCatching { lbVideo.stopPlayback() }
        fsOpen = false; applyImmersive(false)
        if (dockOpen) showNav()   // back to the dock → show its arrows, fade after 2s
    }

    // Optimistically tag/untag a photo, durably (TagQueue) so it survives offline + replays on reconnect.
    private fun tagPhoto(asset: String, tag: String, on: Boolean) {
        val set = assetTags.getOrPut(asset) { hashSetOf() }
        if (on) set.add(tag) else set.remove(tag)
        tagQueue.enqueue(asset, tag, on)
        if (fsOpen) showNav()   // keep the fullscreen chrome visible while the admin is tagging
        if (gridOpen) {   // live-update top-bar counts + masters, and rebind the grid thumb for this asset
            setGridCountText(); refreshMasters()
            val gi = gridAdapter.indexOfAsset(asset); if (gi >= 0) gridAdapter.notifyItemChanged(gi)
        }
        ui.launch { if (api.setTag(asset, tag, on)) tagQueue.clearOne(asset, tag) }
    }

    // Replay any tag changes that couldn't reach the add-on yet (called on resume + each live tick).
    private fun flushTagQueue() {
        val pend = tagQueue.pending()
        if (pend.isEmpty()) return
        ui.launch { for ((asset, tag, on) in pend) if (api.setTag(asset, tag, on)) tagQueue.clearOne(asset, tag) }
    }

    // Refresh the local tag cache from a photo load, then re-overlay still-pending local changes.
    private fun ingestPhotoTags(phs: List<Photo>) {
        for (ph in phs) assetTags[ph.asset] = ph.tags.toMutableSet()
        for ((asset, tag, on) in tagQueue.pending()) {
            val s = assetTags.getOrPut(asset) { hashSetOf() }; if (on) s.add(tag) else s.remove(tag)
        }
    }

    @Suppress("DEPRECATION")
    private fun applyImmersive(on: Boolean) {
        window.decorView.systemUiVisibility = if (on)
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        else View.SYSTEM_UI_FLAG_VISIBLE
    }

    private fun metaText(p: Photo): String {
        val sb = StringBuilder()
        if (!p.camera.isNullOrEmpty()) sb.append("📷 ").append(p.camera)
        if (p.people.isNotEmpty()) { if (sb.isNotEmpty()) sb.append("\n"); sb.append("👤 ").append(p.people.joinToString(", ")) }
        val last = ArrayList<String>()
        if (!p.name.isNullOrEmpty()) last.add(p.name)
        if (p.ts != null) last.add(java.text.DateFormat.getDateTimeInstance().format(Date((p.ts * 1000).toLong())))
        if (last.isNotEmpty()) { if (sb.isNotEmpty()) sb.append("\n"); sb.append(last.joinToString(" · ")) }
        return sb.toString()
    }

    // ----- photo viewer wiring -----
    private fun setupPhotoViewer() {
        pvPrev = findViewById(R.id.pv_prev); pvNext = findViewById(R.id.pv_next)
        lbPrev = findViewById(R.id.lb_prev); lbNext = findViewById(R.id.lb_next)
        pvPrev.setOnClickListener { navPhoto(-1) }
        pvNext.setOnClickListener { navPhoto(1) }
        lbPrev.setOnClickListener { navPhoto(-1) }
        lbNext.setOnClickListener { navPhoto(1) }
        findViewById<ImageButton>(R.id.pv_close).setOnClickListener { closeDock() }
        findViewById<ImageButton>(R.id.pv_full).setOnClickListener { photoSorted.getOrNull(curPhoto)?.let { showFullscreen(it); showNav() } }
        pvPlay.setOnClickListener { photoSorted.getOrNull(curPhoto)?.let { showFullscreen(it); showNav() } }
        findViewById<ImageButton>(R.id.lb_close).setOnClickListener { closeFullscreen() }

        val gap = 10f * resources.displayMetrics.density
        val neighbor = { dir: Int ->
            val p = photoSorted.getOrNull(curPhoto + dir)
            if (p == null || p.type == "video") null else repo.cached(api.previewUrl(p.asset))
        }

        // dock image: double-tap / tap on a video opens fullscreen; single tap re-shows the nav arrows; ghost-swipe navigates
        pvImg.doubleTapZoom = false
        pvImg.onDoubleTapAtFit = { photoSorted.getOrNull(curPhoto)?.let { showFullscreen(it); showNav() } }
        pvImg.onTap = { val p = photoSorted.getOrNull(curPhoto); if (p?.type == "video") { showFullscreen(p); showNav() } else showNav() }
        pvImg.onSwipe = { d -> navPhoto(d, fromSwipe = true) }
        pvImg.ghost = findViewById(R.id.pv_ghost); pvImg.neighborBitmap = neighbor; pvImg.gapPx = gap

        // fullscreen image: pinch/pan; double-tap zooms to max; single tap re-shows the nav arrows; ghost-swipe navigates
        lbImg.doubleTapZoom = true
        lbImg.onTap = { showNav() }
        lbImg.onSwipe = { d -> navPhoto(d, fromSwipe = true) }
        lbImg.ghost = findViewById(R.id.lb_ghost); lbImg.neighborBitmap = neighbor; lbImg.gapPx = gap
    }

    // Nav arrows: auto-fade 2s after shown/used; a single tap re-shows them; disabled (hidden) at the ends.
    private fun applyNav() {
        val size = photoSorted.size
        val showPrev = navShown && curPhoto > 0
        val showNext = navShown && curPhoto in 0 until (size - 1)
        pvPrev.visibility = if (showPrev) View.VISIBLE else View.INVISIBLE
        lbPrev.visibility = if (showPrev) View.VISIBLE else View.INVISIBLE
        pvNext.visibility = if (showNext) View.VISIBLE else View.INVISIBLE
        lbNext.visibility = if (showNext) View.VISIBLE else View.INVISIBLE
        // fullscreen chrome — bottom info bar, Open-original + tag checkboxes, and close button — all fade
        // together with the nav arrows (same show/hide timing)
        if (fsOpen) {
            fadeChrome(lbMeta); fadeChrome(lbTopleft); fadeChrome(lbClose)
        }
    }
    private fun fadeChrome(v: View) {
        v.animate().alpha(if (navShown) 1f else 0f).setDuration(200)
            .withStartAction { if (navShown) v.visibility = View.VISIBLE }
            .withEndAction { if (!navShown) v.visibility = View.INVISIBLE }
            .start()
    }
    private fun showNav() {
        navShown = true; applyNav()
        navHandler.removeCallbacks(navHideRunnable)
        navHandler.postDelayed(navHideRunnable, 2000)
    }
    private fun resetNav() { navHandler.removeCallbacks(navHideRunnable); navShown = false; applyNav() }

    // ----- grid -----
    private fun setupGrid() {
        gridAdapter = GridAdapter(api, repo,
            onClick = { p -> openPhoto(p.asset, false) },
            tagsOf = { ph -> assetTags[ph.asset] ?: ph.tags.toSet() },
            onTag = { ph, tag, on -> tagPhoto(ph.asset, tag, on) })
        pgItems.layoutManager = GridLayoutManager(this, prefs.gridCols)
        pgItems.adapter = gridAdapter
        masterRel = findViewById(R.id.sw_all_rel); masterWorld = findViewById(R.id.sw_all_world)
        findViewById<android.widget.Switch>(R.id.sw_grid_tag).setOnCheckedChangeListener { _, on ->
            gridAdapter.setTagMode(on)
            val vis = if (on && isAdmin) View.VISIBLE else View.GONE
            masterRel.visibility = vis; masterWorld.visibility = vis
            if (on && isAdmin) refreshMasters()
        }
        gridPadBottomBase = pgItems.paddingBottom   // the XML base padding; dock height is added on top while open
        // small gap between thumbnails (like the web grid)
        val gap = (1.5f * resources.displayMetrics.density).toInt()
        pgItems.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                outRect.set(gap, gap, gap, gap)
            }
        })
        val label = findViewById<TextView>(R.id.pg_cols_v)
        label.text = prefs.gridCols.toString()
        fun setCols(cols: Int) {
            val c = cols.coerceIn(2, 20)
            label.text = c.toString(); prefs.gridCols = c
            (pgItems.layoutManager as GridLayoutManager).spanCount = c
            pgItems.adapter?.notifyDataSetChanged()
        }
        findViewById<Button>(R.id.pg_cols_dec).setOnClickListener { setCols(prefs.gridCols - 1) }
        findViewById<Button>(R.id.pg_cols_inc).setOnClickListener { setCols(prefs.gridCols + 1) }
        findViewById<ImageButton>(R.id.pg_close).setOnClickListener { closeGrid() }
    }
    private fun openGrid() { gridOpen = true; gridOverlay.visibility = View.VISIBLE; gridOverlay.bringToFront(); renderGrid(); updateGridCurrent() }
    private fun closeGrid() { gridOpen = false; gridOverlay.visibility = View.GONE }

    // ----- trip stats + elevation/speed charts (all computed from the already-loaded track) -----
    private fun openStats() {
        val v = liveOrFocusView() ?: run { toast("Select a trip first"); return }
        if (v.pts.isEmpty()) { toast("No track points yet"); return }
        statsView = v
        findViewById<ChartView>(R.id.chart_elev).fit(); findViewById<ChartView>(R.id.chart_speed).fit()
        populateStats(v)
        statsOpen = true
        findViewById<View>(R.id.stats_overlay).apply { visibility = View.VISIBLE; bringToFront() }
    }
    private fun closeStats() { statsOpen = false; statsView = null; findViewById<View>(R.id.stats_overlay).visibility = View.GONE }

    // Maximise a chart into a full-screen landscape overlay (its own pan/zoom; the small chart is untouched).
    private fun openChartFull(srcId: Int) {
        findViewById<ChartView>(srcId).cloneInto(findViewById(R.id.chart_full))
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        chartFullOpen = true
        findViewById<View>(R.id.chart_full_overlay).apply { visibility = View.VISIBLE; bringToFront() }
    }
    private fun closeChartFull() {
        chartFullOpen = false
        findViewById<View>(R.id.chart_full_overlay).visibility = View.GONE
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    // ----- trip replay (scrub a marker along the track with a time slider) -----
    private fun openReplay() {
        val v = liveOrFocusView() ?: run { toast("Select a trip first"); return }
        if (v.pts.size < 2) { toast("Not enough track to replay"); return }
        if (statsOpen) closeStats()
        replayView = v; replayPos = 0.0; replayPlaying = false
        val sk = findViewById<SeekBar>(R.id.sk_replay); sk.max = v.pts.size - 1; sk.progress = 0
        findViewById<ImageButton>(R.id.btn_replay_play).setImageResource(android.R.drawable.ic_media_play)
        findViewById<View>(R.id.replay_bar).apply { visibility = View.VISIBLE; bringToFront() }
        drawReplayAt()
    }
    private fun closeReplay() {
        pauseReplay(); replayView = null
        findViewById<View>(R.id.replay_bar).visibility = View.GONE
        mapCtl.setReplay(null, null)
    }
    private fun playReplay() {
        val v = replayView ?: return
        if (replayPos >= v.pts.size - 1) replayPos = 0.0   // restart from the beginning
        replayPlaying = true
        findViewById<ImageButton>(R.id.btn_replay_play).setImageResource(android.R.drawable.ic_media_pause)
        replayHandler.removeCallbacks(replayRunnable); replayHandler.post(replayRunnable)
    }
    private fun pauseReplay() {
        replayPlaying = false
        findViewById<ImageButton>(R.id.btn_replay_play).setImageResource(android.R.drawable.ic_media_play)
        replayHandler.removeCallbacks(replayRunnable)
    }
    private val replayRunnable = object : Runnable {
        override fun run() {
            val v = replayView ?: return
            val step = maxOf(1.0, (v.pts.size - 1) / 300.0)   // whole trip plays in ~300 frames (~15 s)
            replayPos += step
            if (replayPos >= v.pts.size - 1) { replayPos = (v.pts.size - 1).toDouble(); drawReplayAt(); pauseReplay(); return }
            drawReplayAt()
            replayHandler.postDelayed(this, 50)
        }
    }
    private fun drawReplayAt() {
        val v = replayView ?: return
        val n = v.pts.size
        val i = replayPos.toInt().coerceIn(0, n - 1)
        val frac = (replayPos - i).coerceIn(0.0, 1.0)
        val a = v.pts[i]
        val lat: Double; val lon: Double; val ts: Double
        if (i + 1 < n) { val b = v.pts[i + 1]; lat = a.lat + (b.lat - a.lat) * frac; lon = a.lon + (b.lon - a.lon) * frac; ts = a.ts + (b.ts - a.ts) * frac }
        else { lat = a.lat; lon = a.lon; ts = a.ts }
        mapCtl.setReplay(lat, lon)
        mapCtl.centerOn(lat, lon, false)
        findViewById<SeekBar>(R.id.sk_replay).progress = i
        val whenStr = if (ts > 0) java.text.DateFormat.getTimeInstance().format(Date((ts * 1000).toLong())) else ""
        findViewById<TextView>(R.id.tv_replay_time).text = "$whenStr · ${Geo.fmtDist(distFromStart(v, i))}"
    }

    // Kick the add-on's background gap-fill job for a trip, poll it, then reload the track to show the fill.
    private fun startFill(id: Int) {
        toast("Filling gaps… (snaps to OSM road/rail, may take a minute)")
        ui.launch {
            runCatching { api.fillGaps(id) }
            repeat(80) {
                kotlinx.coroutines.delay(3000)
                val s = runCatching { api.fillStatus(id) }.getOrNull() ?: return@repeat
                when (s.optString("state")) {
                    "done" -> {
                        val r = s.optJSONObject("result")
                        reloadTrip(id)
                        toast("Gaps filled: ${r?.optInt("rail")} rail, ${r?.optInt("road")} road, " +
                              "${r?.optInt("skipped")} skipped (+${r?.optInt("added")} pts)")
                        return@launch
                    }
                    "error" -> { toast("Fill failed: ${s.optString("error")}"); return@launch }
                }
            }
            toast("Fill still running — reopen the trip shortly")
        }
    }

    // Re-fetch a loaded trip's track from scratch (after fill/unfill changed the stored points).
    private fun reloadTrip(id: Int) {
        val v = views[id] ?: return
        v.pts.clear(); v.tsKeys.clear(); v.lastPointId = 0; v.lastLiveMs = 0
        ui.launch { appendViewData(v, false) }
    }

    // ===================== manual track editor =====================
    private fun setupEditToolbar() {
        editToolbar = findViewById(R.id.edit_toolbar)
        editHint = findViewById(R.id.edit_hint)
        findViewById<Button>(R.id.edit_add).setOnClickListener { editAddArmed = !editAddArmed; if (editAddArmed) joinState = 0; updateEditHint() }
        findViewById<Button>(R.id.edit_del).setOnClickListener { editDelete() }
        findViewById<Button>(R.id.edit_join).setOnClickListener { editJoinPressed() }
        findViewById<Button>(R.id.edit_ts).setOnClickListener { editTsAuto = !editTsAuto; (it as Button).text = if (editTsAuto) "ts:auto" else "ts:keep" }
        findViewById<Button>(R.id.edit_undo).setOnClickListener { doUndo() }
        findViewById<Button>(R.id.edit_redo).setOnClickListener { doRedo() }
        findViewById<Button>(R.id.edit_left ).setOnClickListener { nudgeSelected(-NUDGE_PX, 0.0) }
        findViewById<Button>(R.id.edit_up   ).setOnClickListener { nudgeSelected(0.0, -NUDGE_PX) }
        findViewById<Button>(R.id.edit_down ).setOnClickListener { nudgeSelected(0.0,  NUDGE_PX) }
        findViewById<Button>(R.id.edit_right).setOnClickListener { nudgeSelected( NUDGE_PX, 0.0) }
        findViewById<Button>(R.id.edit_close).setOnClickListener { exitTrackEdit() }
        findViewById<Button>(R.id.edit_box).setOnClickListener { toggleBoxSelect() }
        findViewById<Button>(R.id.edit_all).setOnClickListener { editSelectAll() }
        findViewById<Button>(R.id.edit_clear).setOnClickListener { editClearSel() }
        findViewById<Button>(R.id.edit_fill).setOnClickListener { editFill() }
        findViewById<Button>(R.id.edit_fillgaps).setOnClickListener { editFillGaps() }
        findViewById<Button>(R.id.edit_removefill).setOnClickListener { editRemoveFill() }
        setupBoxCatcher()
    }

    // Map "edit track" button: toggle the toolbox on the currently-loaded trip.
    private fun editCurrentTrip() {
        if (!isAdmin) return
        if (editMode) { exitTrackEdit(); return }
        val id = activeTripId ?: latestTripId ?: views.keys.lastOrNull()
        val t = id?.let { tripsById[it] } ?: run { toast("No trip loaded"); return }
        startTrackEdit(t)
    }

    private fun startTrackEdit(t: Trip) {
        if (!isAdmin) return
        ui.launch {
            setOnlyTrip(t.id, true)
            editTripId = t.id; editMode = true; editSelected.clear(); editAddArmed = false; joinState = 0; joinFirstId = -1; editNewPts.clear(); editSelectMode = false
            undoStack.clear(); redoStack.clear()
            // declutter the bottom chrome the toolbox would overlap (restored on exit)
            btnLocate.visibility = View.GONE; scaleBar.visibility = View.GONE
            mapCtl.setZoomControls(false)
            editToolbar.visibility = View.VISIBLE; editToolbar.bringToFront()
            TrackGeom.editConnectedId = t.id; renderTracks()
            renderEdit(); updateEditHint()
            toast("Track edit mode")
        }
    }
    private fun exitTrackEdit() {
        editMode = false; editAddArmed = false; joinState = 0; joinFirstId = -1; editSelected.clear(); editNewPts.clear(); editSelectMode = false
        undoStack.clear(); redoStack.clear()
        TrackGeom.editConnectedId = null
        findViewById<View>(R.id.edit_box_catcher).visibility = View.GONE
        findViewById<View>(R.id.edit_rubber).visibility = View.GONE
        mapCtl.clearEdit()
        editToolbar.visibility = View.GONE
        btnLocate.visibility = View.VISIBLE; scaleBar.visibility = View.VISIBLE
        mapCtl.setZoomControls(true)
        renderTracks()
    }
    private fun renderEdit() {
        if (!editMode) return
        val v = editTripId?.let { views[it] } ?: return
        val byId = HashMap<Long, TrackPoint>(v.pts.size); for (p in v.pts) byId[p.id] = p
        val idsList = editSelected.toList()
        val cap = 400
        val stride = if (idsList.size > cap) (idsList.size + cap - 1) / cap else 1
        val pts = ArrayList<EditPt>()
        var k = 0
        while (k < idsList.size) { byId[idsList[k]]?.let { pts.add(EditPt(it.id, it.lat, it.lon)) }; k += stride }
        mapCtl.onEditDragEnd = { id, lat, lon -> onEditDrag(id, lat, lon) }
        mapCtl.setEditPoints(pts)
        updateEditButtons()
    }
    private fun updateEditHint() {
        editHint.text = when {
            editSelectMode -> "Drag a box on the map to select points inside it"
            editAddArmed   -> "Tap on the track to add a point"
            joinState == 1 -> "Join: tap the FIRST segment endpoint"
            joinState == 2 -> "Join: tap the SECOND endpoint"
            else           -> "Tap track to select · drag/arrows to move · ${editSelected.size} selected"
        }
        findViewById<Button>(R.id.edit_add).text = if (editAddArmed) "＋Add…" else "＋Add"
        updateEditButtons()
    }
    // Enable arrows only with a live selection; undo/redo only when their stacks are non-empty.
    private fun updateEditButtons() {
        if (!::editToolbar.isInitialized) return
        val hasSel = editSelected.isNotEmpty()
        for (rid in intArrayOf(R.id.edit_left, R.id.edit_up, R.id.edit_down, R.id.edit_right)) {
            findViewById<Button>(rid)?.let { it.isEnabled = hasSel; it.alpha = if (hasSel) 1f else 0.35f }
        }
        findViewById<Button>(R.id.edit_undo)?.let { it.isEnabled = undoStack.isNotEmpty(); it.alpha = if (undoStack.isNotEmpty()) 1f else 0.35f }
        findViewById<Button>(R.id.edit_redo)?.let { it.isEnabled = redoStack.isNotEmpty(); it.alpha = if (redoStack.isNotEmpty()) 1f else 0.35f }
        findViewById<Button>(R.id.edit_del)?.let { it.isEnabled = hasSel; it.alpha = if (hasSel) 1f else 0.35f }
        findViewById<Button>(R.id.edit_clear)?.let { it.isEnabled = hasSel; it.alpha = if (hasSel) 1f else 0.35f }
        findViewById<Button>(R.id.edit_fill)?.let { val ok = editSelected.size >= 2; it.isEnabled = ok; it.alpha = if (ok) 1f else 0.35f }
        findViewById<Button>(R.id.edit_box)?.let { it.alpha = if (editSelectMode) 1f else 0.9f }
    }

    // ---- new toolbox actions (parity with the web edit toolbox) ----
    private fun editSelectAll() {
        val tid = editTripId ?: return; val v = views[tid] ?: return
        editSelected.clear(); for (p in v.pts) editSelected.add(p.id)
        editAddArmed = false; editSelectMode = false
        findViewById<View>(R.id.edit_box_catcher).visibility = View.GONE
        renderEdit(); updateEditHint()
    }
    private fun editClearSel() { editSelected.clear(); renderEdit(); updateEditHint() }
    private fun toggleBoxSelect() {
        editSelectMode = !editSelectMode
        if (editSelectMode) { editAddArmed = false; joinState = 0 }
        val catcher = findViewById<View>(R.id.edit_box_catcher)
        catcher.visibility = if (editSelectMode) View.VISIBLE else View.GONE
        if (editSelectMode) catcher.bringToFront()
        updateEditHint()
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupBoxCatcher() {
        val catcher = findViewById<View>(R.id.edit_box_catcher)
        val rubber = findViewById<View>(R.id.edit_rubber)
        catcher.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { boxStartX = e.x; boxStartY = e.y; updateRubber(rubber, e.x, e.y, e.x, e.y); rubber.visibility = View.VISIBLE; rubber.bringToFront(); true }
                MotionEvent.ACTION_MOVE -> { updateRubber(rubber, boxStartX, boxStartY, e.x, e.y); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    rubber.visibility = View.GONE
                    boxSelectPoints(boxStartX, boxStartY, e.x, e.y)
                    editSelectMode = false; catcher.visibility = View.GONE; updateEditHint(); true
                }
                else -> true
            }
        }
    }
    private fun updateRubber(rubber: View, x0: Float, y0: Float, x1: Float, y1: Float) {
        val lp = rubber.layoutParams as android.widget.FrameLayout.LayoutParams
        lp.leftMargin = kotlin.math.min(x0, x1).toInt(); lp.topMargin = kotlin.math.min(y0, y1).toInt()
        lp.width = kotlin.math.abs(x1 - x0).toInt(); lp.height = kotlin.math.abs(y1 - y0).toInt()
        rubber.layoutParams = lp
    }
    private fun boxSelectPoints(x0: Float, y0: Float, x1: Float, y1: Float) {
        val tid = editTripId ?: return; val v = views[tid] ?: return
        if (kotlin.math.abs(x1 - x0) < 6 && kotlin.math.abs(y1 - y0) < 6) return
        val minX = kotlin.math.min(x0, x1); val maxX = kotlin.math.max(x0, x1)
        val minY = kotlin.math.min(y0, y1); val maxY = kotlin.math.max(y0, y1)
        for (p in v.pts) {
            val sp = mapCtl.screenOf(p.lat, p.lon) ?: continue
            if (sp.x in minX..maxX && sp.y in minY..maxY) editSelected.add(p.id)
        }
        renderEdit(); updateEditHint()
    }
    // Densify the big gaps WITHIN the selected points (undoable).
    private inner class FillCmd(val v: TripView, val ids: List<Long>) : EditCmd {
        var added: List<Long> = emptyList()
        override suspend fun redo() {
            val before = v.pts.map { it.id }.toHashSet()
            runCatching { api.densifyPoints(v.id, ids) }
            reloadTripSuspend(v)
            added = v.pts.filter { it.id !in before }.map { it.id }
            for (id in added) editNewPts.add(id)
            renderTracks(); renderEdit(); updateEditHint()
        }
        override suspend fun undo() {
            for (id in added) editNewPts.remove(id)
            if (added.isNotEmpty()) runCatching { api.deletePoints(v.id, added) }
            reloadTripSuspend(v)
            renderTracks(); renderEdit(); updateEditHint()
        }
    }
    private fun editFill() {
        val tid = editTripId ?: return; val v = views[tid] ?: return
        if (editSelected.size < 2) { toast("Select 2+ points to fill between"); return }
        val ids = editSelected.toList()
        toast("Filling…")
        ui.launch { val cmd = FillCmd(v, ids); cmd.redo(); pushCmd(cmd) }
    }
    // Full-trip gap reconstruction along roads/rail (moved here from the Edit dialog).
    private fun editFillGaps() { editTripId?.let { startFill(it) } }
    private fun editRemoveFill() {
        val tid = editTripId ?: return; val v = views[tid] ?: return
        ui.launch { runCatching { api.unfillGaps(tid) }; reloadTripSuspend(v); renderTracks(); renderEdit(); updateEditHint(); toast("Fill removed") }
    }

    private fun handleEditTap(lat: Double, lon: Double) {
        val v = editTripId?.let { views[it] } ?: return
        if (editAddArmed) { addPointAt(lat, lon); return }
        val tapS = mapCtl.screenOf(lat, lon) ?: return
        var best: TrackPoint? = null; var bestD = Float.MAX_VALUE
        for (p in v.pts) {
            val s = mapCtl.screenOf(p.lat, p.lon) ?: continue
            val d = hypot((tapS.x - s.x).toDouble(), (tapS.y - s.y).toDouble()).toFloat()
            if (d < bestD) { bestD = d; best = p }
        }
        val b = best
        if (b == null || bestD > 30f * resources.displayMetrics.density) return   // tapped empty space
        if (joinState > 0) { handleJoinPick(b); return }
        if (!editSelected.add(b.id)) editSelected.remove(b.id)   // tap again → deselect
        renderEdit(); updateEditHint()
    }

    private fun onEditDrag(id: Long, lat: Double, lon: Double) {
        val tid = editTripId ?: return; val v = views[tid] ?: return
        val idx = v.pts.indexOfFirst { it.id == id }; if (idx < 0) return
        val old = v.pts[idx]
        val newTs = if (editTsAuto || editNewPts.contains(id)) weightedTs(v, idx, lat, lon) else old.ts
        val recs = listOf(MoveRec(id, old.lat, old.lon, old.ts, lat, lon, newTs))
        ui.launch { applyMoveSet(v, recs, true); pushCmd(MoveCmd(v, recs)) }
    }

    // Nudge all selected points by a screen-pixel delta (dxPx east+, dyPx down+). One undoable move.
    private fun nudgeSelected(dxPx: Double, dyPx: Double) {
        val tid = editTripId ?: return; val v = views[tid] ?: return
        if (editSelected.isEmpty()) return
        val mpp = mapCtl.metersPerPixel() ?: return
        val north = -dyPx * mpp; val east = dxPx * mpp
        val recs = ArrayList<MoveRec>()
        for (id in editSelected) {
            val idx = v.pts.indexOfFirst { it.id == id }; if (idx < 0) continue
            val p = v.pts[idx]
            val dLat = north / 111320.0
            val dLon = east / (111320.0 * kotlin.math.cos(Math.toRadians(p.lat)).coerceAtLeast(1e-6))
            val nLat = p.lat + dLat; val nLon = p.lon + dLon
            val nTs = if (editTsAuto || editNewPts.contains(id)) weightedTs(v, idx, nLat, nLon) else p.ts
            recs.add(MoveRec(id, p.lat, p.lon, p.ts, nLat, nLon, nTs))
        }
        if (recs.isEmpty()) return
        ui.launch { applyMoveSet(v, recs, true); pushCmd(MoveCmd(v, recs)) }
    }

    // Apply a batch of point moves to local model + backend, then resort/rekey/re-render.
    private suspend fun applyMoveSet(v: TripView, moves: List<MoveRec>, forward: Boolean) {
        for (m in moves) {
            val idx = v.pts.indexOfFirst { it.id == m.id }; if (idx < 0) continue
            val lat = if (forward) m.nLat else m.oLat
            val lon = if (forward) m.nLon else m.oLon
            val ts  = if (forward) m.nTs  else m.oTs
            v.pts[idx] = v.pts[idx].copy(lat = lat, lon = lon, ts = ts)
            runCatching { api.movePoint(v.id, m.id, lat, lon, ts) }
        }
        v.pts.sortWith(compareBy({ it.ts }, { it.id })); rebuildKeys(v)
        renderTracks(); renderEdit()
    }
    private fun rebuildKeys(v: TripView) { v.tsKeys.clear(); for (p in v.pts) v.tsKeys.add(v.tsKey(p.ts)) }
    private suspend fun reloadTripSuspend(v: TripView) {
        v.pts.clear(); v.tsKeys.clear(); v.lastPointId = 0; v.lastLiveMs = 0
        appendViewData(v, false)
    }
    private fun weightedTs(v: TripView, idx: Int, lat: Double, lon: Double): Double {
        val prev = v.pts.getOrNull(idx - 1); val next = v.pts.getOrNull(idx + 1)
        if (prev == null || next == null) return v.pts[idx].ts
        val d1 = Geo.haversine(prev.lat, prev.lon, lat, lon); val d2 = Geo.haversine(lat, lon, next.lat, next.lon)
        val f = if (d1 + d2 <= 0.0) 0.5 else d1 / (d1 + d2)
        return prev.ts + f * (next.ts - prev.ts)
    }

    private fun addPointAt(lat: Double, lon: Double) {
        val tid = editTripId ?: return; val v = views[tid] ?: return
        val tapS = mapCtl.screenOf(lat, lon) ?: return
        var bi = -1; var bestD = Float.MAX_VALUE; var bestF = 0.0
        for (i in 1 until v.pts.size) {
            val a = mapCtl.screenOf(v.pts[i - 1].lat, v.pts[i - 1].lon) ?: continue
            val bb = mapCtl.screenOf(v.pts[i].lat, v.pts[i].lon) ?: continue
            val (d, f) = pointSegDistPx(tapS, a, bb)
            if (d < bestD) { bestD = d; bi = i; bestF = f }
        }
        val ts = if (bi >= 1) v.pts[bi - 1].ts + bestF * (v.pts[bi].ts - v.pts[bi - 1].ts)
                 else (v.pts.lastOrNull()?.ts ?: (System.currentTimeMillis() / 1000.0)) + 1.0
        editAddArmed = false
        ui.launch {
            val cmd = AddCmd(v, lat, lon, ts)
            cmd.redo()
            if (cmd.id < 0) { toast("Add failed"); return@launch }
            pushCmd(cmd); updateEditHint()
        }
    }

    private fun editDelete() {
        val tid = editTripId ?: return; val v = views[tid] ?: return
        if (editSelected.isEmpty()) { toast("No points selected"); return }
        val pts0 = editSelected.mapNotNull { id -> v.pts.firstOrNull { it.id == id }?.let { DelPt(it.lat, it.lon, it.ts, it.source) } }
        val ids = editSelected.toList()
        if (pts0.isEmpty()) return
        ui.launch {
            val cmd = DeleteCmd(v, pts0, ids)
            cmd.redo()
            pushCmd(cmd); updateEditHint()
        }
    }

    // Join button: use the current selection if it already qualifies, else prompt for what's missing.
    private fun editJoinPressed() {
        val tid = editTripId ?: return; val v = views[tid] ?: return
        editAddArmed = false
        val eps = editSelected.filter { isEndpoint(v, it) }
        when {
            eps.size >= 2 -> { joinState = 0; startJoinConfirm(v, eps[0], eps[1]) }
            eps.size == 1 -> {
                joinFirstId = eps[0]; joinState = 2
                editSelected.clear(); editSelected.add(eps[0]); renderEdit(); updateEditHint()
                toast("First endpoint set — tap the second endpoint")
            }
            else -> { joinState = 1; joinFirstId = -1; editSelected.clear(); renderEdit(); updateEditHint() }
        }
    }
    private fun resetJoin() { joinState = 0; joinFirstId = -1; editSelected.clear(); renderEdit(); updateEditHint() }
    private fun isEndpoint(v: TripView, id: Long): Boolean {
        val i = v.pts.indexOfFirst { it.id == id }; if (i < 0) return false
        if (i == 0 || i == v.pts.size - 1) return true
        return TrackGeom.gapAt(v, i) || TrackGeom.gapAt(v, i + 1)
    }
    private fun handleJoinPick(p: TrackPoint) {
        val tid = editTripId ?: return; val v = views[tid] ?: return
        if (!isEndpoint(v, p.id)) { toast("Not a segment endpoint — pick an end next to a gap"); return }
        if (joinState == 1) {
            joinFirstId = p.id; joinState = 2; editSelected.clear(); editSelected.add(p.id); renderEdit(); updateEditHint(); return
        }
        if (p.id == joinFirstId) { toast("Pick a different endpoint"); return }
        joinState = 0
        startJoinConfirm(v, joinFirstId, p.id)
    }
    // Final confirm (warn if far apart in time) then perform + record the join.
    private fun startJoinConfirm(v: TripView, aId: Long, bId: Long) {
        // No intermediate points: the edited track is drawn connected, so the gap is already a straight line.
        // (Add points on it, or use Fill to densify / Fill gaps to reconstruct along roads.)
        joinState = 0; joinFirstId = -1; editSelected.clear(); renderEdit(); updateEditHint(); toast("Joined (straight line)")
    }
    private fun pointSegDistPx(p: android.graphics.PointF, a: android.graphics.PointF, b: android.graphics.PointF): Pair<Float, Double> {
        val dx = b.x - a.x; val dy = b.y - a.y; val len2 = dx * dx + dy * dy
        val t = if (len2 <= 0f) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).toDouble().coerceIn(0.0, 1.0)
        val cx = a.x + t.toFloat() * dx; val cy = a.y + t.toFloat() * dy
        return Pair(hypot((p.x - cx).toDouble(), (p.y - cy).toDouble()).toFloat(), t)
    }

    // ---------- undo / redo for track edits ----------
    private val NUDGE_PX = 4.0
    private interface EditCmd { suspend fun undo(); suspend fun redo() }
    private data class MoveRec(val id: Long, val oLat: Double, val oLon: Double, val oTs: Double,
                               val nLat: Double, val nLon: Double, val nTs: Double)
    private data class DelPt(val lat: Double, val lon: Double, val ts: Double, val source: String?)

    private fun pushCmd(c: EditCmd) {
        undoStack.addLast(c); while (undoStack.size > 40) undoStack.removeFirst()
        redoStack.clear(); updateEditButtons()
    }
    private fun doUndo() {
        val c = undoStack.removeLastOrNull() ?: return
        ui.launch { runCatching { c.undo() }; redoStack.addLast(c); updateEditButtons() }
    }
    private fun doRedo() {
        val c = redoStack.removeLastOrNull() ?: return
        ui.launch { runCatching { c.redo() }; undoStack.addLast(c); updateEditButtons() }
    }

    private inner class MoveCmd(val v: TripView, val moves: List<MoveRec>) : EditCmd {
        override suspend fun undo() { applyMoveSet(v, moves, false) }
        override suspend fun redo() { applyMoveSet(v, moves, true) }
    }
    private inner class AddCmd(val v: TripView, val lat: Double, val lon: Double, val ts: Double) : EditCmd {
        var id: Long = -1L
        override suspend fun redo() {
            val nid = runCatching { api.addPoint(v.id, lat, lon, ts) }.getOrNull() ?: run { id = -1L; return }
            id = nid
            editNewPts.add(nid)
            v.pts.add(TrackPoint(nid, ts, lat, lon, null, null, null, "edited"))
            v.pts.sortWith(compareBy({ it.ts }, { it.id })); rebuildKeys(v)
            editSelected.clear(); editSelected.add(nid)
            renderTracks(); renderEdit()
        }
        override suspend fun undo() {
            if (id < 0) return
            runCatching { api.deletePoints(v.id, listOf(id)) }
            v.pts.removeAll { it.id == id }; editSelected.remove(id); editNewPts.remove(id); rebuildKeys(v)
            renderTracks(); renderEdit(); updateEditHint()
        }
    }
    private inner class DeleteCmd(val v: TripView, val pts0: List<DelPt>, var ids: List<Long>) : EditCmd {
        override suspend fun redo() {
            val set = ids.toHashSet()
            runCatching { api.deletePoints(v.id, ids) }
            v.pts.removeAll { it.id in set }; editSelected.clear(); rebuildKeys(v)
            renderTracks(); renderEdit(); updateEditHint()
        }
        override suspend fun undo() {
            val newIds = ArrayList<Long>(pts0.size)
            for (p in pts0) {
                val nid = runCatching { api.addPoint(v.id, p.lat, p.lon, p.ts) }.getOrNull() ?: continue
                newIds.add(nid)
                v.pts.add(TrackPoint(nid, p.ts, p.lat, p.lon, null, null, null, p.source ?: "edited"))
            }
            ids = newIds
            v.pts.sortWith(compareBy({ it.ts }, { it.id })); rebuildKeys(v)
            renderTracks(); renderEdit(); updateEditHint()
        }
    }
    private inner class JoinCmd(val v: TripView, val aId: Long, val bId: Long, var bridgeIds: List<Long>) : EditCmd {
        override suspend fun redo() {
            val before = v.pts.map { it.id }.toHashSet()
            runCatching { api.joinPoints(v.id, aId, bId) }
            reloadTripSuspend(v)
            bridgeIds = v.pts.filter { it.id !in before }.map { it.id }
            renderTracks(); renderEdit(); updateEditHint()
        }
        override suspend fun undo() {
            if (bridgeIds.isNotEmpty()) runCatching { api.deletePoints(v.id, bridgeIds) }
            reloadTripSuspend(v)
            renderTracks(); renderEdit(); updateEditHint()
        }
    }

    // Admin: per-track action popup (long-press a track in the side panel). Title = the track name.
    private fun showTrackPopup(t: Trip) {
        if (!isAdmin) return
        val items = arrayOf("🔄 Refresh photos", "✏️ Edit", "🛠 Edit track", "🗑️ Delete",
                            "👪 Share for Relatives", "🌍 Share for World")
        AlertDialog.Builder(this)
            .setTitle(t.name ?: "Trip ${t.id}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> ui.launch { doSync(t) }
                    1 -> openEditDialog(t)
                    2 -> startTrackEdit(t)
                    3 -> confirmDelete(t)
                    4 -> shareTrip(t.id, t.name, "relatives")
                    5 -> shareTrip(t.id, t.name, "world")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Mint (or reuse) a scoped public share link; it shows only photos tagged for that audience.
    private fun shareTrip(id: Int, name: String?, scope: String) {
        val who = if (scope == "relatives") "Relatives" else "World"
        ui.launch {
            val r = runCatching { api.share(id, scope) }.getOrNull()
            val url = r?.optString("url")
            if (url.isNullOrEmpty()) { toast("Share failed"); return@launch }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Share for $who")
                .setMessage("Anyone with this link sees ${name ?: "the trip"} read-only — the track plus " +
                    "only the photos you tagged \u201C$who\u201D:\n\n$url")
                .setPositiveButton("Share link") { _, _ ->
                    startActivity(android.content.Intent.createChooser(
                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, url)
                        }, "Share trip"))
                }
                .setNeutralButton("Stop sharing") { _, _ -> ui.launch { runCatching { api.unshare(id, scope) }; toast("Sharing stopped") } }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    // Export the trip as GPX/GeoJSON: fetch from the add-on, cache it, and hand it to the share sheet.
    private fun exportTrip(v: TripView, fmt: String) {
        toast("Exporting ${fmt.uppercase()}…")
        ui.launch {
            val bytes = runCatching { api.exportBytes(v.id, fmt) }.getOrNull()
            if (bytes == null || bytes.isEmpty()) { toast("Export failed"); return@launch }
            try {
                val name = (tripsById[v.id]?.name ?: "trip_${v.id}")
                    .replace(Regex("[^A-Za-z0-9._-]+"), "_").take(60).ifEmpty { "trip_${v.id}" }
                val dir = java.io.File(cacheDir, "export"); dir.mkdirs()
                val f = java.io.File(dir, "$name.$fmt")
                kotlinx.coroutines.withContext(Dispatchers.IO) { f.writeBytes(bytes) }
                val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", f)
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = if (fmt == "gpx") "application/gpx+xml" else "application/geo+json"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(android.content.Intent.createChooser(send, "Export ${fmt.uppercase()}"))
            } catch (e: Exception) { toast("Export error: ${e.message}") }
        }
    }

    private fun populateStats(v: TripView) {
        val pts = v.pts
        var dist = 0.0; var moving = 0.0; var stopped = 0.0; var maxKmh = 0.0
        var hasAlt = false; var aMin = Double.MAX_VALUE; var aMax = -Double.MAX_VALUE
        val xs = FloatArray(pts.size); val elev = FloatArray(pts.size); val spd = FloatArray(pts.size)
        for (i in pts.indices) {
            val p = pts[i]
            if (i > 0) {
                val a = pts[i - 1]
                val hop = Geo.haversine(a.lat, a.lon, p.lat, p.lon)
                dist += hop
                val dt = p.ts - a.ts
                val sp = p.speed ?: (if (dt > 0) hop / dt else 0.0)
                if (dt in 0.0..60.0) { if (sp > 0.5) moving += dt else stopped += dt }   // ignore signal-loss gaps
                if (sp * 3.6 > maxKmh) maxKmh = sp * 3.6
            }
            val alt = p.alt
            if (alt != null) { hasAlt = true; if (alt < aMin) aMin = alt; if (alt > aMax) aMax = alt }
            xs[i] = dist.toFloat()
            elev[i] = alt?.toFloat() ?: Float.NaN   // gaps / removed outliers → NaN so the chart skips them
            spd[i] = ((p.speed ?: 0.0) * 3.6).toFloat()
        }
        val gain = elevationGain(pts)
        val avgKmh = if (moving > 0) dist / moving * 3.6 else 0.0
        findViewById<TextView>(R.id.stats_title).text = tripsById[v.id]?.name ?: "Trip ${v.id}"
        val rows = findViewById<LinearLayout>(R.id.stats_rows); rows.removeAllViews()
        addStat(rows, "📏  Distance", Geo.fmtDist(dist))
        addStat(rows, "⏱  Duration", Geo.fmtHMS(statsDuration(v)))
        addStat(rows, "🚗  Moving · stopped", "${Geo.fmtHMS(moving)}  ·  ${Geo.fmtHMS(stopped)}")
        addStat(rows, "⚡  Avg · max speed", "${Math.round(avgKmh)} · ${Math.round(maxKmh)} km/h")
        if (hasAlt) addStat(rows, "⛰  Elevation gain", "${Math.round(gain)} m   (${Math.round(aMin)}–${Math.round(aMax)} m)")
        addStat(rows, "📷  Photos", "${v.photos.size}")
        addStat(rows, "📍  Track points", "${pts.size}")
        val ce = findViewById<ChartView>(R.id.chart_elev)
        if (hasAlt) { ce.visibility = View.VISIBLE; ce.setData(xs, elev, "Elevation", " m", Color.parseColor("#4da3ff")) }
        else ce.visibility = View.GONE
        findViewById<ChartView>(R.id.chart_speed).setData(xs, spd, "Speed", " km/h", Color.parseColor("#43a047"))
    }
    // Cumulative ascent from noisy GPS altitude, in three passes: (1) reject spikes — readings whose vertical
    // rate vs the last good one is impossible for ground travel; (2) moderate smoothing of the pervasive
    // sub-metre jitter; (3) hysteresis, crediting only sustained climbs. Without these a long trip reads ~8× high.
    private fun elevationGain(pts: List<TrackPoint>): Double {
        val a = ArrayList<Double>()
        var lastA: Double? = null; var lastT = 0.0
        val maxVert = 2.0   // m/s — steeper vertical speed than any car/walk ⇒ a bad reading
        for (p in pts) {
            val v = p.alt ?: continue
            val la = lastA
            if (la == null) { a.add(v); lastA = v; lastT = p.ts; continue }
            val dt = p.ts - lastT
            if (dt <= 0.0) continue
            if (kotlin.math.abs(v - la) / dt > maxVert) continue   // spike → drop
            a.add(v); lastA = v; lastT = p.ts
        }
        if (a.size < 3) return 0.0
        val w = minOf(21, a.size); val s = DoubleArray(a.size)
        for (i in a.indices) { val lo = maxOf(0, i - w / 2); val hi = minOf(a.size, i + w / 2 + 1); var sum = 0.0; for (k in lo until hi) sum += a[k]; s[i] = sum / (hi - lo) }
        var g = 0.0; var ref = s[0]; val t = 15.0
        for (i in 1 until s.size) { val v = s[i]; if (v - ref >= t) { g += v - ref; ref = v } else if (ref - v >= t) ref = v }
        return g
    }
    private fun statsDuration(v: TripView): Double {
        val t = tripsById[v.id]
        val startMs = t?.startedAt?.let { parseIsoMs(it) }
        if (startMs != null) {
            val endMs = t.stoppedAt?.let { parseIsoMs(it) } ?: System.currentTimeMillis()
            return (endMs - startMs) / 1000.0
        }
        return if (v.pts.size > 1) v.pts.last().ts - v.pts.first().ts else 0.0
    }
    private fun addStat(parent: LinearLayout, label: String, value: String) {
        val pad = (6 * resources.displayMetrics.density).toInt()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, pad, 0, pad) }
        row.addView(TextView(this).apply {
            text = label; setTextColor(0xff9aa4b2.toInt()); textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = value; setTextColor(0xffe6edf3.toInt()); textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        parent.addView(row)
    }
    // Grid top-bar text: image count, plus (admin) how many of the shown photos carry each visibility tag.
    private fun setGridCountText() {
        pgCount.text = "${photoSorted.size} image${if (photoSorted.size == 1) "" else "s"}"
        if (isAdmin && ::pgTagCount.isInitialized) {
            var rel = 0; var wor = 0
            for (p in photoSorted) { val t = assetTags[p.asset] ?: p.tags.toSet()
                if (t.contains("Relatives")) rel++; if (t.contains("World")) wor++ }
            pgTagCount.text = "👪 $rel      🌍 $wor"
        }
        updateGridHeader()
    }
    // Trip name (below the progress bar) + the red current-photo position bar.
    private fun updateGridHeader() {
        val tid = focusId ?: views.keys.firstOrNull()
        val nm = tid?.let { tripsById[it]?.name?.takeIf { n -> n.isNotBlank() } ?: "Trip $it" } ?: ""
        findViewById<TextView>(R.id.pg_trip_name)?.apply {
            text = nm; visibility = if (nm.isBlank()) View.GONE else View.VISIBLE
        }
        updateGridProgress()
    }
    private fun updateGridProgress() {
        val total = photoSorted.size
        val frac = if (total > 0 && curPhoto in photoSorted.indices) (curPhoto + 1).toFloat() / total else 0f
        val cont = findViewById<View>(R.id.pg_progress) ?: return
        val fill = findViewById<View>(R.id.pg_progress_fill) ?: return
        val apply = {
            val lp = fill.layoutParams; lp.width = (cont.width * frac).toInt(); fill.layoutParams = lp
        }
        if (cont.width > 0) apply() else cont.post { apply() }
    }
    // The two top-bar masters reflect "every shown photo carries this tag"; they go unchecked the moment one
    // photo in the category is untagged. Setting a master tags/untags ALL shown photos.
    private fun refreshMasters() {
        if (!::masterRel.isInitialized) return
        val photos = photoSorted
        fun allHave(tag: String) = photos.isNotEmpty() && photos.all { (assetTags[it.asset] ?: it.tags.toSet()).contains(tag) }
        masterRel.setOnCheckedChangeListener(null); masterWorld.setOnCheckedChangeListener(null)
        masterRel.isChecked = allHave("Relatives")
        masterWorld.isChecked = allHave("World")
        masterRel.setOnCheckedChangeListener { cb, on -> if (on) tagAll("Relatives", true) else cb.isChecked = true }
        masterWorld.setOnCheckedChangeListener { cb, on -> if (on) tagAll("World", true) else cb.isChecked = true }
    }
    private fun tagAll(tag: String, on: Boolean) {
        for (p in photoSorted) {
            val set = assetTags.getOrPut(p.asset) { hashSetOf() }
            if (on == set.contains(tag)) continue       // only touch photos that actually change
            if (on) set.add(tag) else set.remove(tag)
            tagQueue.enqueue(p.asset, tag, on)
            val asset = p.asset
            ui.launch { if (api.setTag(asset, tag, on)) tagQueue.clearOne(asset, tag) }
        }
        gridAdapter.notifyDataSetChanged()              // rebind the per-thumb checkboxes
        setGridCountText(); refreshMasters()
    }
    private fun renderGrid() {
        sortPhotos()
        setGridCountText()
        // Only re-submit (which rebinds + reloads thumbs) when the photo set actually changed; otherwise keep
        // the RecyclerView's views + already-loaded thumbnails, so reopening the grid doesn't reload.
        val key = photoSorted.joinToString(",") { it.asset }
        if (key != gridKey) { gridKey = key; gridAdapter.submit(photoSorted) }
        gridAdapter.setCurrent(currentAsset())
        refreshMasters()
    }
    private fun updateGridCurrent() {
        gridAdapter.setCurrent(currentAsset())
        updateGridProgress()
        if (!gridOpen) return
        val idx = gridAdapter.indexOfAsset(currentAsset())
        if (idx < 0) return
        pgItems.post { ensureCellFullyVisible(idx) }
    }
    // Scroll only when the current cell isn't fully inside the viewport; measured from its actual bounds, so
    // a fully-visible cell is left alone (no needless scroll, no scrolling it off-screen).
    private fun ensureCellFullyVisible(idx: Int) {
        val child = pgItems.findViewHolderForAdapterPosition(idx)?.itemView
        if (child == null) {
            // off-screen (or not laid out yet) → bring it in, aligned near the top
            (pgItems.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(idx, pgItems.paddingTop)
            return
        }
        val visTop = pgItems.paddingTop
        var visBottom = pgItems.height - pgItems.paddingBottom
        // The photo dock, when open, is brought to front over the bottom of the grid, but pg_items still
        // extends full-height behind it. Clamp the visible bottom to the dock's top edge so the current cell
        // is kept above the dock, not merely on-screen (where it'd be hidden under the dock).
        if (dockOpen && dock.visibility == View.VISIBLE &&
            resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            val rvY = IntArray(2); pgItems.getLocationOnScreen(rvY)
            val dkY = IntArray(2); dock.getLocationOnScreen(dkY)
            val dockTopInRv = dkY[1] - rvY[1]      // dock's top edge, in pg_items' coordinate space
            if (dockTopInRv < visBottom) visBottom = dockTopInRv
        }
        when {
            child.top < visTop -> pgItems.scrollBy(0, child.top - visTop)
            child.bottom > visBottom -> pgItems.scrollBy(0, child.bottom - visBottom)
        }
    }

    // ----- track balloon (anchored to the point, moves with the map) -----
    private fun onMapTap(lat: Double, lon: Double) {
        if (editMode) { handleEditTap(lat, lon); return }
        if (gridOpen || fsOpen) { popupLatLng = null; trackPopup.visibility = View.GONE; return }
        val tapS = mapCtl.screenOf(lat, lon) ?: run { popupLatLng = null; trackPopup.visibility = View.GONE; return }
        fun hidePopup() { popupLatLng = null; trackPopup.visibility = View.GONE }
        // 0) Cluster-first: a tap on a photo cluster zooms in to split it (rather than opening a photo under it).
        val cluHit = 26f * resources.displayMetrics.density
        var bestClu: PhotoMarkerSpec? = null; var bestCluD = Float.MAX_VALUE
        for (m in lastClusters) {
            if (m.count <= 1) continue
            val s = mapCtl.screenOf(m.lat, m.lon) ?: continue
            val dd = hypot((tapS.x - s.x).toDouble(), (tapS.y - s.y).toDouble()).toFloat()
            if (dd < bestCluD) { bestCluD = dd; bestClu = m }
        }
        bestClu?.let { clu ->
            if (bestCluD <= cluHit) {
                hidePopup()
                val z = mapCtl.zoom(); val zmax = mapCtl.maxZoom()
                val members = clusterMembersNear(clu)
                // Zooming in splits a spread-out cluster; but at (near) max zoom, or when the photos are
                // co-located (zoom can never separate them), open a picker of the photos instead of a no-op zoom.
                val cantSplit = z >= zmax - 0.75 || (members.size >= 2 && clusterSpanM(members) < 4.0)
                if (members.size >= 2 && cantSplit) openClusterPicker(members)
                else mapCtl.setView(clu.lat, clu.lon, minOf(z + 2.0, zmax))
                return
            }
        }
        // 1) Dot-first: if a shown photo dot is within a touch radius, select the NEAREST one and show no balloon.
        val hitPx = 22f * resources.displayMetrics.density
        var bestAsset: String? = null; var bestDotD = Float.MAX_VALUE
        for (ph in photoSorted) {
            val s = mapCtl.screenOf(ph.lat, ph.lon) ?: continue
            val d = hypot((tapS.x - s.x).toDouble(), (tapS.y - s.y).toDouble()).toFloat()
            if (d < bestDotD) { bestDotD = d; bestAsset = ph.asset }
        }
        if (bestAsset != null && bestDotD <= hitPx) { hidePopup(); openPhoto(bestAsset!!, false); return }
        // 2) Otherwise show the track balloon at the nearest track point (if the tap landed near the track).
        val hit = nearestTrackPt(lat, lon) ?: run { hidePopup(); return }
        val ptS = mapCtl.screenOf(hit.p.lat, hit.p.lon)
        if (ptS == null || hypot((tapS.x - ptS.x).toDouble(), (tapS.y - ptS.y).toDouble()) > 44) { hidePopup(); return }
        // build a bold speed + coords + time + distance-from-start balloon (web .tk-pop)
        val kmh = if (hit.p.speed != null) "${Math.round(maxOf(0.0, hit.p.speed!! * 3.6))} km/h" else "– km/h"
        val when_ = if (hit.p.ts > 0) java.text.DateFormat.getTimeInstance().format(Date((hit.p.ts * 1000).toLong())) else ""
        val fromStart = Geo.fmtDist(distFromStart(hit.v, hit.i))
        val body = "${String.format("%.5f, %.5f", hit.p.lat, hit.p.lon)}" +
            (if (when_.isNotEmpty()) "\n$when_" else "") + "\n$fromStart from start"
        val sp = android.text.SpannableStringBuilder()
        sp.append(kmh, android.text.style.StyleSpan(android.graphics.Typeface.BOLD), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.append("\n").append(body)
        findViewById<TextView>(R.id.tp_text).text = sp
        popupLatLng = LL(hit.p.lat, hit.p.lon)
        trackPopup.visibility = View.VISIBLE
        trackPopup.post { positionPopup() }
    }

    // Reposition the balloon so its tail tip sits on the anchored track point; hide it when off-screen.
    private fun positionPopup() {
        val ll = popupLatLng
        if (ll == null || gridOpen || fsOpen) { trackPopup.visibility = View.GONE; return }
        val s = mapCtl.screenOf(ll.lat, ll.lon) ?: run { trackPopup.visibility = View.GONE; return }
        val content = findViewById<View>(R.id.content)
        if (s.x < 0 || s.y < 0 || s.x > content.width || s.y > content.height) { trackPopup.visibility = View.GONE; return }
        trackPopup.visibility = View.VISIBLE
        val w = trackPopup.width; val h = trackPopup.height
        if (w == 0 || h == 0) { trackPopup.post { positionPopup() }; return }
        trackPopup.translationX = s.x - w / 2f
        trackPopup.translationY = s.y - h
    }

    private class Hit(val p: TrackPoint, val v: TripView, val i: Int)
    private fun nearestTrackPt(lat: Double, lon: Double): Hit? {
        var best: Hit? = null; var bd = Double.MAX_VALUE
        for (v in views.values) for (i in v.pts.indices) {
            val d = Geo.haversine(lat, lon, v.pts[i].lat, v.pts[i].lon)
            if (d < bd) { bd = d; best = Hit(v.pts[i], v, i) }
        }
        return best
    }
    private fun distFromStart(v: TripView, idx: Int): Double {
        var d = 0.0; var k = 1
        while (k <= idx && k < v.pts.size) { d += Geo.haversine(v.pts[k-1].lat, v.pts[k-1].lon, v.pts[k].lat, v.pts[k].lon); k++ }
        return d
    }

    // ----- locate / follow -----
    private fun setFollow(on: Boolean) {
        followMode = on
        btnLocate.setColorFilter(if (on) Color.parseColor("#4da3ff") else Color.WHITE)
    }
    private fun followIfNeeded() {
        val v = liveOrFocusView() ?: return
        if (followMode && v.pts.isNotEmpty()) { val h = v.pts.last(); mapCtl.centerOn(h.lat, h.lon, true) }
    }
    private fun locateTraveler() {
        setFollow(true)
        val v = liveOrFocusView() ?: return
        if (v.pts.isNotEmpty()) { val h = v.pts.last(); mapCtl.setView(h.lat, h.lon, maxOf(mapCtl.zoom(), 16.0)) }
    }
    private fun liveOrFocusView(): TripView? =
        activeTripId?.let { views[it] } ?: focusId?.let { views[it] }

    // ----- speed pill -----
    private fun updatePill() {
        val v = activeTripId?.let { views[it] }
        if (v == null) { speedPill.visibility = View.GONE; return }
        val t = tripsById[activeTripId]
        var startedEpoch: Double? = null
        if (t?.startedAt != null) { val ms = parseIsoMs(t.startedAt); if (ms != null) startedEpoch = ms / 1000.0 }
        if (startedEpoch == null && v.pts.isNotEmpty()) startedEpoch = v.pts.first().ts
        val hms = if (startedEpoch != null) Geo.fmtHMS(System.currentTimeMillis() / 1000.0 - startedEpoch) else "00:00:00"
        val dist = Geo.fmtDist(distFromStart(v, v.pts.size - 1))
        val sub = " · ⏱ $hms · $dist"
        if (v.latestSpeed == null) {
            speedPill.text = "– km/h$sub"; speedPill.visibility = View.VISIBLE; speedPill.alpha = 1f; return
        }
        val kmh = Math.round(maxOf(0.0, v.latestSpeed!! * 3.6))
        val age = if (v.latestSpeedTs > 0) maxOf(0.0, System.currentTimeMillis() / 1000.0 - v.latestSpeedTs) else 0.0
        val expected = if (v.mode == "distance") (v.minDistanceM.coerceAtLeast(1)) / maxOf(v.latestSpeed!!, 0.5)
                       else v.intervalS.toDouble()
        val staleStart = maxOf(5.0, 1.3 * expected)
        val staleFull = staleStart + maxOf(15.0, 2 * expected)
        var ageTxt = ""; var stale = false; var alpha = 1f
        if (age > staleStart) {
            stale = true
            val f = minOf(1.0, (age - staleStart) / (staleFull - staleStart))
            alpha = (1f - f * 0.75f).toFloat()
            ageTxt = if (age < 90) " · ${Math.round(age)}s" else " · ${Math.round(age / 60)}m"
        }
        speedPill.text = "${if (stale) "~" else ""}$kmh km/h$ageTxt$sub"
        speedPill.alpha = alpha
        speedPill.visibility = View.VISIBLE
    }
    private fun parseIsoMs(iso: String): Long? = try {
        val clean = iso.substringBefore('.').substringBefore('+').removeSuffix("Z")
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(clean)?.time
    } catch (e: Exception) { null }

    // ----- admin controls -----
    private fun applyAdminVisibility() {
        val vis = if (isAdmin) View.VISIBLE else View.GONE
        btnStart.visibility = vis
        if (::gridAdapter.isInitialized) gridAdapter.admin = isAdmin
        findViewById<View>(R.id.pg_tagrow).visibility = vis   // line-2 tagging row: admin only
        findViewById<android.widget.Switch>(R.id.sw_grid_tag).apply {
            visibility = vis
            if (!isAdmin) isChecked = false
        }
        if (!isAdmin && ::masterRel.isInitialized) { masterRel.visibility = View.GONE; masterWorld.visibility = View.GONE }
        findViewById<View>(R.id.btn_edit_track).visibility = vis
    }
    private fun updateActiveBox(active: Boolean, trip: Trip?) {
        tripLive = active
        if (!isAdmin) { syncSpacingRows(); return }
        if (active) {
            btnStop.visibility = View.VISIBLE; btnStart.visibility = View.GONE; btnApply.visibility = View.VISIBLE
            if (trip != null) prefillSpacing(trip)
        } else {
            btnStop.visibility = View.GONE; btnStart.visibility = View.VISIBLE; btnApply.visibility = View.GONE
            spacingPrefillId = null
        }
        syncSpacingRows()
    }
    private fun prefillSpacing(t: Trip) {
        if (t.id == spacingPrefillId) return
        spacingPrefillId = t.id
        modeSpinner.setSelection(modeKeys.indexOf(t.mode).coerceAtLeast(0))
        ivSeek.progress = (t.intervalS - 1).coerceIn(0, 58); ivLabel.text = t.intervalS.toString()
        (t.minDistanceM ?: 25).let { distSeek.progress = (it - 1).coerceIn(0, 98); distLabel.text = it.toString() }
        syncSpacingRows()
    }
    private fun curMode(): String = modeKeys.getOrElse(modeSpinner.selectedItemPosition) { "hybrid" }
    private fun syncSpacingRows() {
        val m = curMode()
        ivRow.visibility = if (tripLive && m != "distance") View.VISIBLE else View.GONE
        distRow.visibility = if (tripLive && m != "time") View.VISIBLE else View.GONE
    }
    private fun doStart() {
        val mode = curMode()
        val name = tripName.text.toString().trim()
        val minDist = if (mode == "distance" || mode == "hybrid") 25 else null
        ui.launch {
            runCatching { api.start(name, mode, 5, minDist, startColor) }
            tripName.setText("")
            autoFollowLive = true
            val st = runCatching { api.state() }.getOrNull()
            if (st?.active == true && st.trip != null) {
                updateActiveBox(true, st.trip); refreshTrips(); setOnlyTrip(st.trip.id, true)
            }
            (findViewById<TabLayout>(R.id.tabs)).getTabAt(0)?.select()
        }
    }
    private fun doApply() {
        val id = activeTripId ?: return
        val mode = curMode()
        val minDist = if (mode == "distance" || mode == "hybrid") distSeek.progress + 1 else null
        ui.launch { runCatching { api.spacing(id, mode, ivSeek.progress + 1, minDist) }; refreshTrips() }
    }

    private fun openEditDialog(t: Trip) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        val name = EditText(this).apply { setText(t.name ?: ""); hint = "Name" }
        root.addView(name)
        var color = t.color ?: "#1565c0"
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val sw = View(this).apply { layoutParams = LinearLayout.LayoutParams(60, 60); background = GradientDrawable().apply { setColor(TripAdapter.parseColor(color)) } }
        val colorBtn = Button(this).apply { text = "Colour" }
        colorBtn.setOnClickListener { pickColor(color) { c -> color = c; (sw.background as GradientDrawable).setColor(TripAdapter.parseColor(c)) } }
        colorRow.addView(sw); colorRow.addView(colorBtn)
        root.addView(colorRow)
        val hide = CheckBox(this).apply { text = "🙈 Hide from viewers"; isChecked = t.hidden; setTextColor(Color.parseColor("#e6edf3")) }
        root.addView(hide)
        AlertDialog.Builder(this).setTitle("Edit trip").setView(root)
            .setPositiveButton("Save") { _, _ ->
                ui.launch {
                    runCatching { api.rename(t.id, name.text.toString(), color, hide.isChecked) }
                    views[t.id]?.color = TripAdapter.parseColor(color)
                    refreshTrips(); renderTracks()
                }
            }.setNegativeButton("Cancel", null).show()
    }
    private fun confirmDelete(t: Trip) {
        AlertDialog.Builder(this).setTitle("Delete trip?")
            .setMessage("Delete this trip and all its track points and photo markers?")
            .setPositiveButton("Delete") { _, _ ->
                ui.launch {
                    runCatching { api.delete(t.id) }
                    if (views.containsKey(t.id)) { views.remove(t.id); if (focusId == t.id) focusId = null; rebuildPhotos(); renderTracks(); renderPhotos(); if (views.isEmpty()) closeDock() }
                    refreshTrips()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private val presetColors = listOf("#1565c0","#e53935","#43a047","#fb8c00","#8e24aa","#00acc1","#fdd835","#6d4c41","#3949ab","#00897b","#d81b60","#7cb342")
    private fun pickColor(current: String, cb: (String) -> Unit) {
        val grid = android.widget.GridLayout(this).apply { columnCount = 4; val p=(12*resources.displayMetrics.density).toInt(); setPadding(p,p,p,p) }
        val dlg = AlertDialog.Builder(this).setTitle("Track colour").setView(grid).create()
        for (c in presetColors) {
            val sz = (48 * resources.displayMetrics.density).toInt()
            val v = View(this).apply {
                layoutParams = android.view.ViewGroup.MarginLayoutParams(sz, sz).apply { val m=(6*resources.displayMetrics.density).toInt(); setMargins(m,m,m,m) }
                background = GradientDrawable().apply { setColor(TripAdapter.parseColor(c)); cornerRadius = 8f; if (c.equals(current, true)) setStroke(4, Color.WHITE) }
                setOnClickListener { cb(c); dlg.dismiss() }
            }
            grid.addView(v)
        }
        dlg.show()
    }
    private fun setStartColor(c: String) { startColor = c; (tripColorSw.background as? GradientDrawable ?: GradientDrawable().also { it.shape = GradientDrawable.OVAL; tripColorSw.background = it }).setColor(TripAdapter.parseColor(c)) }

    private fun openSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        val url = EditText(this).apply { setText(prefs.baseUrl); hint = "Add-on base URL" }
        val tok = EditText(this).apply { setText(prefs.token); hint = "App token" }
        root.addView(TextView(this).apply { text = "Base URL" }); root.addView(url)
        root.addView(TextView(this).apply { text = "Token" }); root.addView(tok)
        val liveSw = android.widget.Switch(this).apply {
            text = "Live track direct from phone"
            isChecked = prefs.liveSource == "direct"
            val t = (12 * resources.displayMetrics.density).toInt(); setPadding(0, t, 0, 0)
        }
        val liveHint = TextView(this).apply {
            textSize = 12f; alpha = 0.7f
            val n = activeTripId?.let { runCatching { liveStore.countForTrip(it) }.getOrNull() } ?: 0
            text = "On: the active trip renders from GPS fixes mymessages sends straight to this device " +
                "(keeps growing during a network/MQTT outage), merged with the add-on track. Off: poll the " +
                "add-on only. A stopped trip always loads from the add-on." +
                (if (activeTripId != null) "\nStored locally for the active trip: $n points." else "")
        }
        root.addView(liveSw); root.addView(liveHint)
        AlertDialog.Builder(this).setTitle("Settings").setView(root)
            .setPositiveButton("Save & reconnect") { _, _ ->
                prefs.baseUrl = url.text.toString(); prefs.token = tok.text.toString()
                prefs.liveSource = if (liveSw.isChecked) "direct" else "network"; recreate()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    // ----- lifecycle -----
    override fun onBackPressed() {
        when {
            editMode -> exitTrackEdit()
            fsOpen -> closeFullscreen()
            chartFullOpen -> closeChartFull()
            statsOpen -> closeStats()
            replayView != null -> closeReplay()
            gridOpen -> closeGrid()
            dockOpen -> closeDock()
            drawer.isDrawerOpen(findViewByGravityStart()) -> drawer.closeDrawers()
            else -> super.onBackPressed()
        }
    }

    override fun onStart() { super.onStart(); mlCtl.onStart(); gCtl.onStart() }
    override fun onResume() { super.onResume(); mlCtl.onResume(); gCtl.onResume(); selfLoc.start(); if (::tagQueue.isInitialized) flushTagQueue() }
    override fun onPause() { selfLoc.stop(); mlCtl.onPause(); gCtl.onPause(); super.onPause() }
    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        if (req == REQ_LOC && res.isNotEmpty() && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED)
            selfLoc.start()   // begin location now that it's granted
    }
    override fun onStop() { mlCtl.onStop(); gCtl.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mlCtl.onLowMemory(); gCtl.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val mlb = Bundle(); mlCtl.onSaveInstanceState(mlb); outState.putBundle("ml_state", mlb)
        val gb = Bundle(); gCtl.onSaveInstanceState(gb); outState.putBundle("g_state", gb)
    }
    override fun onDestroy() { ui.cancel(); mlCtl.onDestroy(); gCtl.onDestroy(); super.onDestroy() }
}
