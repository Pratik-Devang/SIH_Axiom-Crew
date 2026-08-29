package com.percorsa.sensorlogger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

enum class MapCameraState {
    FOLLOWING,
    FREE_BROWSE
}

class MainActivity : AppCompatActivity() {

    companion object {
        var navController: NavigationController? = null
    }

    // ── Map Layer ──────────────────────────────────────────────────────────────
    private lateinit var mapWebView: WebView
    private var mapReady = false
    private var cameraState = MapCameraState.FOLLOWING
    private var lastMapLat = 0.0
    private var lastMapLon = 0.0
    private var lastMapBearing = 0f
    private var routeDrawn = false

    // ── Top Instrument HUD ──────────────────────────────────────────────────────
    private lateinit var tvHudGnssBadge: TextView
    private lateinit var tvHudMlBadge: TextView
    private lateinit var tvHudLatLon: TextView
    private lateinit var tvHudSpeed: TextView
    private lateinit var btnHudRec: TextView
    private lateinit var btnHudCal: TextView
    private lateinit var btnDevMode: TextView

    // ── Maneuver Card (active navigation) ──────────────────────────────────────
    private lateinit var maneuverCard: LinearLayout
    private lateinit var tvManeuverIcon: TextView
    private lateinit var tvManeuverDistance: TextView
    private lateinit var tvManeuverInstruction: TextView
    private lateinit var tvGnssBadge: TextView
    private lateinit var rowSecondManeuver: LinearLayout
    private lateinit var tvSecondManeuverIcon: TextView
    private lateinit var tvSecondManeuverInstruction: TextView

    // ── Floating Map Controls ──────────────────────────────────────────────────
    private lateinit var btnCompass: FrameLayout
    private lateinit var tvCompassNeedle: TextView
    private lateinit var btnRecenter: FrameLayout
    private lateinit var tvRecenterIcon: TextView

    // ── Full Search Overlay Screen ─────────────────────────────────────────────
    private lateinit var panelSearchOverlay: LinearLayout
    private lateinit var btnSearchBack: TextView
    private lateinit var etSearchInput: EditText
    private lateinit var btnSearchClear: TextView
    private lateinit var searchProgressBar: ProgressBar
    private lateinit var tvSearchError: TextView
    private lateinit var rvSearchResults: RecyclerView
    private lateinit var searchAdapter: SearchResultsAdapter

    private lateinit var chipHome: TextView
    private lateinit var chipWork: TextView
    private lateinit var chipPetrol: TextView
    private lateinit var chipHospital: TextView
    private lateinit var chipFood: TextView

    // ── Bottom Sheets ──────────────────────────────────────────────────────────
    private lateinit var bottomSheet: LinearLayout

    // Instrument panel (IDLE state — was panelIdleSheet, kept same ID)
    private lateinit var panelIdleSheet: LinearLayout
    private lateinit var tvMetricSpeed: TextView
    private lateinit var tvMetricImuHz: TextView
    private lateinit var tvMetricGpsAcc: TextView
    private lateinit var tvMetricSamples: TextView
    private lateinit var tvAccelX: TextView
    private lateinit var tvAccelY: TextView
    private lateinit var tvAccelZ: TextView
    private lateinit var tvOrientPitch: TextView
    private lateinit var tvOrientRoll: TextView
    private lateinit var tvOrientYaw: TextView
    private lateinit var tvTripMode: TextView
    private lateinit var tvTripDrDistance: TextView
    private lateinit var tvTripDuration: TextView
    private lateinit var btnClearPath: Button
    private lateinit var btnExportCsv: Button
    private lateinit var btnNavigate: Button

    private lateinit var panelRoutePreviewSheet: LinearLayout
    private lateinit var tvDestinationName: TextView
    private lateinit var tvDestinationAddress: TextView
    private lateinit var tvRouteDistance: TextView
    private lateinit var tvRouteEta: TextView
    private lateinit var routeProgressBar: ProgressBar
    private lateinit var tvRouteError: TextView
    private lateinit var btnCancelRoute: Button
    private lateinit var btnStartNav: Button

    private lateinit var panelNavigatingSheet: LinearLayout
    private lateinit var tvNavSpeed: TextView
    private lateinit var tvNavDistance: TextView
    private lateinit var tvNavEta: TextView
    private lateinit var tvDrStatusLine: TextView
    private lateinit var btnEndNav: Button

    private lateinit var panelArrivedSheet: LinearLayout
    private lateinit var tvArrivedDestName: TextView
    private lateinit var btnDone: Button

    // ── Trip tracking ───────────────────────────────────────────────────────────
    private var tripStartMs = 0L
    private var drPathDistanceM = 0.0
    private var lastDrLat = 0.0
    private var lastDrLon = 0.0

    // ── Handlers & Timers ──────────────────────────────────────────────────────
    private val uiHandler = Handler(Looper.getMainLooper())
    private val searchDebounceHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    private val uiRunnable = object : Runnable {
        override fun run() {
            navController?.tick()
            val state = navController?.state?.value
            if (state != null) {
                renderState(state)
            }
            uiHandler.postDelayed(this, 100)
        }
    }

    private val PERMISSION_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (navController == null) {
            navController = NavigationController(this)
        }

        bindViews()
        setupSearchExperience()
        setupInstrumentActions()
        setupMapWebView()
        checkPermissions()

        btnDevMode.setOnClickListener {
            startActivity(Intent(this, DebugActivity::class.java))
        }

        btnRecenter.setOnClickListener {
            cameraState = MapCameraState.FOLLOWING
            updateRecenterButtonAppearance()
            val s = navController?.state?.value ?: return@setOnClickListener
            if (s.hasValidPosition) {
                mapWebView.evaluateJavascript(
                    "map.panTo([${s.latitude}, ${s.longitude}], {animate:true, duration:0.5});", null
                )
            }
        }

        // Long-press recenter = reset bearing to north
        btnRecenter.setOnLongClickListener {
            // Leaflet is north-up; reset the focused camera instead of calling a MapLibre API.
            mapWebView.evaluateJavascript("if(marker) map.setView(marker.getLatLng(), map.getZoom(), {animate:true});", null)
            true
        }

        btnCompass.setOnClickListener {
            val s = navController?.state?.value ?: return@setOnClickListener
            if (s.hasValidPosition) {
                mapWebView.evaluateJavascript(
                    "map.setView([${s.latitude}, ${s.longitude}], 16, {animate:true});", null
                )
            }
        }

        btnCancelRoute.setOnClickListener {
            navController?.cancelSearch()
            mapWebView.evaluateJavascript("clearRoute();", null)
            routeDrawn = false
        }

        btnStartNav.setOnClickListener {
            navController?.beginDriving()
            cameraState = MapCameraState.FOLLOWING
            updateRecenterButtonAppearance()
        }

        btnEndNav.setOnClickListener {
            navController?.stopNavigation()
            mapWebView.evaluateJavascript("clearRoute(); clearPath();", null)
            routeDrawn = false
            resetTripCounters()
            cameraState = MapCameraState.FOLLOWING
            updateRecenterButtonAppearance()
        }

        btnDone.setOnClickListener {
            navController?.stopNavigation()
            mapWebView.evaluateJavascript("clearRoute(); clearPath();", null)
            routeDrawn = false
            resetTripCounters()
            cameraState = MapCameraState.FOLLOWING
            updateRecenterButtonAppearance()
        }
    }

    private fun bindViews() {
        mapWebView                  = findViewById(R.id.mapWebView)

        // Top HUD
        tvHudGnssBadge              = findViewById(R.id.tvHudGnssBadge)
        tvHudMlBadge                = findViewById(R.id.tvHudMlBadge)
        tvHudLatLon                 = findViewById(R.id.tvHudLatLon)
        tvHudSpeed                  = findViewById(R.id.tvHudSpeed)
        btnHudRec                   = findViewById(R.id.btnHudRec)
        btnHudCal                   = findViewById(R.id.btnHudCal)
        btnDevMode                  = findViewById(R.id.btnDevMode)

        // Maneuver card
        maneuverCard                = findViewById(R.id.maneuverCard)
        tvManeuverIcon              = findViewById(R.id.tvManeuverIcon)
        tvManeuverDistance          = findViewById(R.id.tvManeuverDistance)
        tvManeuverInstruction       = findViewById(R.id.tvManeuverInstruction)
        tvGnssBadge                 = findViewById(R.id.tvGnssBadge)
        rowSecondManeuver           = findViewById(R.id.rowSecondManeuver)
        tvSecondManeuverIcon        = findViewById(R.id.tvSecondManeuverIcon)
        tvSecondManeuverInstruction = findViewById(R.id.tvSecondManeuverInstruction)

        // FABs
        btnCompass                  = findViewById(R.id.btnCompass)
        tvCompassNeedle             = findViewById(R.id.tvCompassNeedle)
        btnRecenter                 = findViewById(R.id.btnRecenter)
        tvRecenterIcon              = findViewById(R.id.tvRecenterIcon)

        // Search overlay
        panelSearchOverlay          = findViewById(R.id.panelSearchOverlay)
        btnSearchBack               = findViewById(R.id.btnSearchBack)
        etSearchInput               = findViewById(R.id.etSearchInput)
        btnSearchClear              = findViewById(R.id.btnSearchClear)
        searchProgressBar           = findViewById(R.id.searchProgressBar)
        tvSearchError               = findViewById(R.id.tvSearchError)
        rvSearchResults             = findViewById(R.id.rvSearchResults)

        chipHome                    = findViewById(R.id.chipHome)
        chipWork                    = findViewById(R.id.chipWork)
        chipPetrol                  = findViewById(R.id.chipPetrol)
        chipHospital                = findViewById(R.id.chipHospital)
        chipFood                    = findViewById(R.id.chipFood)

        // Bottom sheets
        bottomSheet                 = findViewById(R.id.bottomSheet)
        panelIdleSheet              = findViewById(R.id.panelIdleSheet)

        // Instrument panel widgets (inside panelIdleSheet)
        tvMetricSpeed               = findViewById(R.id.tvMetricSpeed)
        tvMetricImuHz               = findViewById(R.id.tvMetricImuHz)
        tvMetricGpsAcc              = findViewById(R.id.tvMetricGpsAcc)
        tvMetricSamples             = findViewById(R.id.tvMetricSamples)
        tvAccelX                    = findViewById(R.id.tvAccelX)
        tvAccelY                    = findViewById(R.id.tvAccelY)
        tvAccelZ                    = findViewById(R.id.tvAccelZ)
        tvOrientPitch               = findViewById(R.id.tvOrientPitch)
        tvOrientRoll                = findViewById(R.id.tvOrientRoll)
        tvOrientYaw                 = findViewById(R.id.tvOrientYaw)
        tvTripMode                  = findViewById(R.id.tvTripMode)
        tvTripDrDistance            = findViewById(R.id.tvTripDrDistance)
        tvTripDuration              = findViewById(R.id.tvTripDuration)
        btnClearPath                = findViewById(R.id.btnClearPath)
        btnExportCsv                = findViewById(R.id.btnExportCsv)
        btnNavigate                 = findViewById(R.id.btnNavigate)

        panelRoutePreviewSheet      = findViewById(R.id.panelRoutePreviewSheet)
        tvDestinationName           = findViewById(R.id.tvDestinationName)
        tvDestinationAddress        = findViewById(R.id.tvDestinationAddress)
        tvRouteDistance             = findViewById(R.id.tvRouteDistance)
        tvRouteEta                  = findViewById(R.id.tvRouteEta)
        routeProgressBar            = findViewById(R.id.routeProgressBar)
        tvRouteError                = findViewById(R.id.tvRouteError)
        btnCancelRoute              = findViewById(R.id.btnCancelRoute)
        btnStartNav                 = findViewById(R.id.btnStartNav)

        panelNavigatingSheet        = findViewById(R.id.panelNavigatingSheet)
        tvNavSpeed                  = findViewById(R.id.tvNavSpeed)
        tvNavDistance               = findViewById(R.id.tvNavDistance)
        tvNavEta                    = findViewById(R.id.tvNavEta)
        tvDrStatusLine              = findViewById(R.id.tvDrStatusLine)
        btnEndNav                   = findViewById(R.id.btnEndNav)

        panelArrivedSheet           = findViewById(R.id.panelArrivedSheet)
        tvArrivedDestName           = findViewById(R.id.tvArrivedDestName)
        btnDone                     = findViewById(R.id.btnDone)
    }

    private fun setupSearchExperience() {
        searchAdapter = SearchResultsAdapter { result ->
            hideKeyboard()
            panelSearchOverlay.visibility = View.GONE
            navController?.startNavigation(result)
        }
        rvSearchResults.layoutManager = LinearLayoutManager(this)
        rvSearchResults.adapter = searchAdapter

        btnSearchBack.setOnClickListener {
            hideKeyboard()
            panelSearchOverlay.visibility = View.GONE
        }

        btnSearchClear.setOnClickListener {
            etSearchInput.text.clear()
            navController?.cancelSearch()
            showRecentOrResults(navController?.state?.value ?: return@setOnClickListener)
        }

        etSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString() ?: ""
                btnSearchClear.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE

                searchRunnable?.let { searchDebounceHandler.removeCallbacks(it) }
                if (q.trim().length >= 2) {
                    searchRunnable = Runnable {
                        navController?.search(q.trim())
                    }
                    searchDebounceHandler.postDelayed(searchRunnable!!, 400)
                } else if (q.isEmpty()) {
                    navController?.cancelSearch()
                    showRecentOrResults(navController?.state?.value ?: return)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        etSearchInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchRunnable?.let { searchDebounceHandler.removeCallbacks(it) }
                navController?.search(v.text.toString())
                hideKeyboard()
                true
            } else false
        }

        chipHome.setOnClickListener { triggerChipSearch("Home") }
        chipWork.setOnClickListener { triggerChipSearch("Work") }
        chipPetrol.setOnClickListener { triggerChipSearch("Petrol Pump") }
        chipHospital.setOnClickListener { triggerChipSearch("Hospital") }
        chipFood.setOnClickListener { triggerChipSearch("Restaurant") }
    }

    private fun setupInstrumentActions() {
        // REC button — toggle CSV recording
        btnHudRec.setOnClickListener {
            val nc = navController ?: return@setOnClickListener
            if (nc.sensorEngine.isRecording) {
                nc.stopRecording()
                btnHudRec.text = "REC"
                btnHudRec.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
            } else {
                val recorder = CsvRecorder(this)
                nc.startRecording(recorder)
                btnHudRec.text = "● REC"
                btnHudRec.setTextColor(ContextCompat.getColor(this, R.color.nav_red))
            }
        }

        // CAL button — calibrate vehicle frame
        btnHudCal.setOnClickListener {
            navController?.calibrateVehicleFrame()
            Toast.makeText(this, "Vehicle frame calibrated", Toast.LENGTH_SHORT).show()
        }

        // Navigate — open search overlay
        btnNavigate.setOnClickListener {
            openSearchOverlay()
        }

        // Clear path on map
        btnClearPath.setOnClickListener {
            mapWebView.evaluateJavascript("clearPath();", null)
            resetTripCounters()
        }

        // Export CSV — share the current recording file
        btnExportCsv.setOnClickListener {
            val engine = navController?.sensorEngine ?: return@setOnClickListener
            val file = engine.csvRecorder?.file
            if (file != null && file.exists()) {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "${packageName}.provider",
                    file
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Share Sensor Log"))
            } else {
                Toast.makeText(this, "No recording active — tap REC first", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun triggerChipSearch(query: String) {
        openSearchOverlay()
        etSearchInput.setText(query)
        etSearchInput.setSelection(query.length)
        navController?.search(query)
    }

    private fun openSearchOverlay() {
        panelSearchOverlay.visibility = View.VISIBLE
        etSearchInput.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(etSearchInput, InputMethodManager.SHOW_IMPLICIT)
        showRecentOrResults(navController?.state?.value ?: return)
    }

    private fun showRecentOrResults(state: NavigationState) {
        searchAdapter.userLocation = if (state.hasValidPosition) LatLon(state.latitude, state.longitude) else null
        if (etSearchInput.text.isEmpty()) {
            searchAdapter.submitList(state.recentSearches)
        } else {
            searchAdapter.submitList(state.searchResults)
        }
    }

    private fun resetTripCounters() {
        tripStartMs = 0L
        drPathDistanceM = 0.0
        lastDrLat = 0.0
        lastDrLon = 0.0
    }

    // ── Map WebView Setup ──────────────────────────────────────────────────────

    private fun setupMapWebView() {
        with(mapWebView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = true
            setRenderPriority(WebSettings.RenderPriority.HIGH)
        }
        mapWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        mapWebView.addJavascriptInterface(WebAppInterface(), "AndroidNative")

        mapWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                mapReady = true
                bootstrapLastKnownLocation()
            }
        }
        mapWebView.loadDataWithBaseURL(
            "https://nominatim.openstreetmap.org",
            buildMapHtml(), "text/html", "UTF-8", null
        )
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun onUserMapGesture() {
            runOnUiThread {
                cameraState = MapCameraState.FREE_BROWSE
                updateRecenterButtonAppearance()
            }
        }
    }

    private fun bootstrapLastKnownLocation() {
        if (!hasLocationPermission()) return
        try {
            val lm = getSystemService(LOCATION_SERVICE) as? android.location.LocationManager ?: return
            for (provider in listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER
            )) {
                try {
                    val loc = lm.getLastKnownLocation(provider)
                    if (loc != null && loc.accuracy < 200f) {
                        val js = "updatePosition(${loc.latitude},${loc.longitude},${loc.bearing},${loc.accuracy},true,false);"
                        mapWebView.post { mapWebView.evaluateJavascript(js, null) }
                        break
                    }
                } catch (_: SecurityException) {}
            }
        } catch (_: Exception) {}
    }

    // ── State Rendering ────────────────────────────────────────────────────────

    private fun renderState(state: NavigationState) {
        renderTopHud(state)
        renderBottomSheet(state)
        renderGnssPill(state)
        renderMap(state)
        renderCompass(state)
        renderInstrumentPanel(state)
    }

    private fun renderTopHud(state: NavigationState) {
        // Maneuver card visible only during active navigation
        val isNavigating = state.navMode == NavMode.NAVIGATING ||
                state.navMode == NavMode.GNSS_DEGRADED ||
                state.navMode == NavMode.GNSS_DENIED

        maneuverCard.visibility = if (isNavigating) View.VISIBLE else View.GONE

        if (isNavigating) {
            val m = state.nextManeuver
            tvManeuverIcon.text = maneuverIcon(m?.type)
            tvManeuverInstruction.text = m?.instruction ?: "Continue on route"
            tvManeuverDistance.text = if (m != null) {
                if (m.distanceM < 1000) "%.0f m".format(Locale.US, m.distanceM)
                else "%.1f km".format(Locale.US, m.distanceM / 1000.0)
            } else state.distanceFormatted

            val m2 = state.secondManeuver
            if (m2 != null) {
                rowSecondManeuver.visibility = View.VISIBLE
                tvSecondManeuverIcon.text = maneuverIcon(m2.type)
                tvSecondManeuverInstruction.text = m2.instruction
            } else {
                rowSecondManeuver.visibility = View.GONE
            }
        }

        // HUD speed (always)
        tvHudSpeed.text = state.speedKmh.toString()

        // HUD lat/lon display
        if (state.hasValidPosition) {
            val latStr = "%.5f° %s".format(Locale.US, abs(state.latitude), if (state.latitude >= 0) "N" else "S")
            val lonStr = "%.5f° %s".format(Locale.US, abs(state.longitude), if (state.longitude >= 0) "E" else "W")
            val accStr = if (state.positionAccuracy < 9999f) " ±%.0fm".format(Locale.US, state.positionAccuracy) else ""
            tvHudLatLon.text = "$latStr  $lonStr$accStr"
        } else {
            tvHudLatLon.text = "acquiring position…"
        }

        // HUD GNSS badge
        val gnssLabel = when (state.gnssQuality) {
            GnssQuality.GOOD       -> "● GNSS LOCK"
            GnssQuality.FAIR       -> "◑ GNSS FAIR"
            GnssQuality.POOR       -> "◑ GNSS POOR"
            GnssQuality.DENIED     -> "● TRACKING ON SENSORS"
            GnssQuality.RECOVERING -> "◑ GPS RETURNING"
        }
        val gnssHudColor = gnssColor(state.gnssQuality)
        tvHudGnssBadge.text = gnssLabel
        tvHudGnssBadge.setTextColor(gnssHudColor)
        tvHudMlBadge.text = state.mlStatusLabel
        tvHudMlBadge.setTextColor(mlColor(state))

        // REC button state sync
        val isRec = state.isRecording
        btnHudRec.text = if (isRec) "● REC" else "REC"
        btnHudRec.setTextColor(
            ContextCompat.getColor(this, if (isRec) R.color.nav_red else R.color.text_tertiary)
        )
    }

    private fun renderBottomSheet(state: NavigationState) {
        val allSheets = listOf(panelIdleSheet, panelRoutePreviewSheet, panelNavigatingSheet, panelArrivedSheet)

        fun show(sheet: LinearLayout) {
            allSheets.forEach { it.visibility = if (it == sheet) View.VISIBLE else View.GONE }
        }

        when (state.navMode) {
            NavMode.IDLE -> {
                show(panelIdleSheet)
                // Instrument panel is rendered by renderInstrumentPanel()
            }
            NavMode.SEARCHING -> {
                // Search overlay is already full-screen; keep instrument panel visible beneath
                searchProgressBar.visibility = if (state.searchLoading) View.VISIBLE else View.GONE
                tvSearchError.visibility = if (state.searchError != null) View.VISIBLE else View.GONE
                tvSearchError.text = state.searchError ?: ""
                showRecentOrResults(state)
            }
            NavMode.ROUTE_PREVIEW -> {
                show(panelRoutePreviewSheet)
                tvDestinationName.text = state.destination?.name ?: ""
                tvDestinationAddress.text = state.destination?.address ?: ""
                tvRouteDistance.text = state.route?.distanceFormatted ?: "--"
                tvRouteEta.text = state.route?.durationFormatted ?: "--"
                routeProgressBar.visibility = if (state.routeLoading) View.VISIBLE else View.GONE
                tvRouteError.visibility = if (state.routeError != null) View.VISIBLE else View.GONE
                tvRouteError.text = state.routeError ?: ""
                btnStartNav.isEnabled = state.route != null && !state.routeLoading
            }
            NavMode.NAVIGATING, NavMode.GNSS_DEGRADED, NavMode.GNSS_DENIED -> {
                show(panelNavigatingSheet)
                tvNavSpeed.text = state.speedKmh.toString()
                tvNavDistance.text = state.distanceFormatted
                tvNavEta.text = state.etaFormatted

                val statusLine = state.statusLine
                tvDrStatusLine.text = statusLine
                tvDrStatusLine.visibility = if (statusLine.isNotEmpty()) View.VISIBLE else View.GONE
            }
            NavMode.ARRIVED -> {
                show(panelArrivedSheet)
                tvArrivedDestName.text = state.destination?.name ?: ""
            }
            NavMode.ERROR -> {
                show(panelIdleSheet)
                state.errorMessage?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun renderInstrumentPanel(state: NavigationState) {
        // Only update instrument panel widgets when they are visible (IDLE mode)
        if (panelIdleSheet.visibility != View.VISIBLE) return

        val snap = navController?.sensorEngine?.getSnapshot() ?: return

        // 4 metric cards
        tvMetricSpeed.text = state.speedKmh.toString()
        tvMetricImuHz.text = "%.0f".format(Locale.US, snap.imuHz)
        tvMetricGpsAcc.text = if (snap.hasGps && snap.gpsAccuracyM < 9999f)
            "%.0f".format(Locale.US, snap.gpsAccuracyM) else "--"
        tvMetricSamples.text = snap.loggedCsvRows.toString()

        // Accel row
        tvAccelX.text = "Ax %+.2f".format(Locale.US, snap.accelX)
        tvAccelY.text = "Ay %+.2f".format(Locale.US, snap.accelY)
        tvAccelZ.text = "Az %+.2f".format(Locale.US, snap.accelZ)

        // Orientation from quaternion (w, x, y, z)
        val w = snap.quatW.toDouble()
        val x = snap.quatX.toDouble()
        val y = snap.quatY.toDouble()
        val z = snap.quatZ.toDouble()

        val sinrCosp = 2.0 * (w * x + y * z)
        val cosrCosp = 1.0 - 2.0 * (x * x + y * y)
        val roll = Math.toDegrees(atan2(sinrCosp, cosrCosp))

        val sinp = 2.0 * (w * y - z * x)
        val pitch = if (abs(sinp) >= 1.0) Math.copySign(90.0, sinp)
                    else Math.toDegrees(asin(sinp))

        val sinyCosp = 2.0 * (w * z + x * y)
        val cosyCosp = 1.0 - 2.0 * (y * y + z * z)
        val yaw = Math.toDegrees(atan2(sinyCosp, cosyCosp))

        tvOrientPitch.text = "P %+.1f°".format(Locale.US, pitch)
        tvOrientRoll.text  = "R %+.1f°".format(Locale.US, roll)
        tvOrientYaw.text   = "Y %+.1f°".format(Locale.US, yaw)

        // Trip status — track DR path length while position valid
        if (state.hasValidPosition) {
            if (tripStartMs == 0L) tripStartMs = System.currentTimeMillis()
            if (lastDrLat != 0.0 && state.drActive) {
                val dLat = Math.toRadians(state.latitude - lastDrLat)
                val dLon = Math.toRadians(state.longitude - lastDrLon)
                val a = Math.sin(dLat / 2).let { it * it } +
                        Math.cos(Math.toRadians(lastDrLat)) *
                        Math.cos(Math.toRadians(state.latitude)) *
                        Math.sin(dLon / 2).let { it * it }
                drPathDistanceM += 6_371_000.0 * 2.0 * asin(sqrt(a))
            }
            lastDrLat = state.latitude
            lastDrLon = state.longitude
        }

        // Trip mode badge
        val modeLabel = when (state.gnssQuality) {
            GnssQuality.GOOD       -> "● GNSS LOCK"
            GnssQuality.FAIR       -> "◑ GNSS FAIR"
            GnssQuality.POOR       -> "◑ GNSS POOR"
            GnssQuality.DENIED     -> "● TRACKING ON SENSORS"
            GnssQuality.RECOVERING -> "◑ GPS RETURNING"
        }
        tvTripMode.text = modeLabel
        tvTripMode.setTextColor(gnssColor(state.gnssQuality))

        tvTripDrDistance.text = "Trip: %.0f m".format(Locale.US, drPathDistanceM)

        if (tripStartMs > 0L) {
            val elapsedS = (System.currentTimeMillis() - tripStartMs) / 1000L
            tvTripDuration.text = "%02d:%02d".format(elapsedS / 60, elapsedS % 60)
        } else {
            tvTripDuration.text = "00:00"
        }
    }

    private fun renderGnssPill(state: NavigationState) {
        // Maneuver card GNSS pill — color-coded semantic label
        val label = when (state.gnssQuality) {
            GnssQuality.DENIED     -> "SENSORS"
            GnssQuality.RECOVERING -> "GPS BACK"
            GnssQuality.POOR       -> "GPS POOR"
            GnssQuality.FAIR       -> "GPS OK"
            GnssQuality.GOOD       -> "GNSS"
        }
        tvGnssBadge.text = label
        tvGnssBadge.setTextColor(gnssColor(state.gnssQuality))
        tvGnssBadge.setBackgroundResource(gnssPillBg(state.gnssQuality))
    }

    private fun renderCompass(state: NavigationState) {
        tvCompassNeedle.rotation = -state.heading
    }

    private fun renderMap(state: NavigationState) {
        if (!mapReady || !state.hasValidPosition) return

        val latDelta = abs(state.latitude - lastMapLat)
        val lonDelta = abs(state.longitude - lastMapLon)
        val brgDelta = abs(state.heading - lastMapBearing)

        if (latDelta > 0.000015 || lonDelta > 0.000015 || brgDelta > 3f) {
            lastMapLat = state.latitude
            lastMapLon = state.longitude
            lastMapBearing = state.heading
            val drFlag = if (state.drActive) "true" else "false"
            val acc = if (state.positionAccuracy < 1000f) state.positionAccuracy else 0f
            val js = "updatePosition(${state.latitude},${state.longitude},${state.heading},$acc,false,$drFlag);"
            mapWebView.evaluateJavascript(js, null)
        }

        if (!routeDrawn && state.route != null) {
            routeDrawn = true
            drawRoute(state.route!!, state.destination)
        }

        if (routeDrawn && state.route == null) {
            routeDrawn = false
            mapWebView.evaluateJavascript("clearRoute();", null)
        }

        val isNavigating = state.navMode == NavMode.NAVIGATING ||
                state.navMode == NavMode.GNSS_DEGRADED ||
                state.navMode == NavMode.GNSS_DENIED

        if (isNavigating && cameraState == MapCameraState.FOLLOWING) {
            val js = "if(marker) map.panTo(marker.getLatLng(), {animate:true,duration:0.3,easeLinearity:0.5});"
            mapWebView.evaluateJavascript(js, null)
        }
    }

    private fun drawRoute(route: Route, destination: GeocodingResult?) {
        val coordsJson = route.polyline.joinToString(",") { "[${it.lat},${it.lon}]" }
        val destLat = destination?.location?.lat ?: route.polyline.lastOrNull()?.lat ?: return
        val destLon = destination?.location?.lon ?: route.polyline.lastOrNull()?.lon ?: return
        val js = "drawRoute([$coordsJson], $destLat, $destLon);"
        mapWebView.evaluateJavascript(js, null)
    }

    private fun updateRecenterButtonAppearance() {
        // Always neutral — recenter is not a state indicator, just an action
        tvRecenterIcon.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
    }

    // ── Leaflet HTML ───────────────────────────────────────────────────────────

    private fun buildMapHtml(): String = """
<!DOCTYPE html>
<html>
<head>
  <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
  <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
  <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
  <style>
    * { margin:0; padding:0; box-sizing:border-box; }
    html, body, #map { height:100%; width:100%; background:#0B1220; }
    .leaflet-control-zoom {
      display:block!important; border:1px solid rgba(61,214,245,.28)!important;
      border-radius:14px!important; overflow:hidden; box-shadow:0 8px 22px rgba(0,0,0,.34)!important;
    }
    .leaflet-control-zoom a {
      width:42px!important; height:42px!important; line-height:40px!important;
      color:#EDF1F7!important; background:rgba(11,18,32,.94)!important;
      border-color:#232D42!important; font-size:24px!important;
    }
    .leaflet-control-zoom a:hover { background:#182238!important; color:#3DD6F5!important; }
    .leaflet-control-attribution {
      font-size:8px; opacity:0.3; background:transparent!important; color:#8A93A6!important; margin-bottom: 260px!important;
    }
    /* Dark night-mode filter for OSM tiles to match #0B1220 deep space-navy */
    .leaflet-tile-pane {
      filter: brightness(0.62) invert(1) contrast(3.2) hue-rotate(200deg) saturate(0.35);
    }
    @keyframes snapPulse {
      0%   { transform: scale(0.95); opacity: 0.9; }
      50%  { transform: scale(1.05); opacity: 1; }
      100% { transform: scale(0.95); opacity: 0.9; }
    }
  </style>
</head>
<body>
<div id="map"></div>
<script>
  // Bootstrap at street-city zoom (15), not subcontinent (5)
  var map = L.map('map', {
    zoomControl: true, attributionControl: true,
    zoomAnimation: true, fadeAnimation: false, preferCanvas: true
  }).setView([20.5937, 78.9629], 15);

  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '&copy; OpenStreetMap contributors', maxZoom: 19,
    subdomains: ['a','b','c'], keepBuffer:4,
    updateWhenIdle:false, updateWhenZooming:false
  }).addTo(map);

  map.on('dragstart zoomstart', function() {
    if (window.AndroidNative && window.AndroidNative.onUserMapGesture) {
      window.AndroidNative.onUserMapGesture();
    }
  });

  // ── Signature Percorsa Vehicle Marker: Trust Halo & Expanding Uncertainty Cone ──
  function makeVehicleIcon(bearing, isDr) {
    var haloColor = isDr ? '#FFB020' : '#3DD6F5';
    var svg = '<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96" viewBox="0 0 96 96">';
    
    if (isDr) {
      // Widening translucent uncertainty cone projecting forward
      svg += '<polygon points="48,48 20,2 76,2" fill="url(#coneGradient)" opacity="0.45"/>' +
             '<defs><linearGradient id="coneGradient" x1="0" y1="1" x2="0" y2="0">' +
             '<stop offset="0%" stop-color="#FFB020" stop-opacity="0.8"/>' +
             '<stop offset="100%" stop-color="#FFB020" stop-opacity="0.05"/>' +
             '</linearGradient></defs>' +
             // Amber outer trust ring
             '<circle cx="48" cy="48" r="22" fill="#FFB020" fill-opacity="0.18" stroke="#FFB020" stroke-width="2.5"/>';
    } else {
      // Thin steady cyan trust ring (tight covariance)
      svg += '<circle cx="48" cy="48" r="18" fill="#3DD6F5" fill-opacity="0.15" stroke="#3DD6F5" stroke-width="2"/>';
    }

    // Vehicle Core Geometry
    svg += '<circle cx="48" cy="48" r="10" fill="#0B1220" stroke="' + haloColor + '" stroke-width="2"/>' +
           '<polygon points="48,32 40,54 48,50 56,54" fill="' + haloColor + '"/>' +
           '</svg>';

    return L.divIcon({
      html: '<div style="transform-origin:center;transform:rotate('+bearing+'deg);transition:transform 0.15s cubic-bezier(0.4, 0, 0.2, 1);">' + svg + '</div>',
      iconSize:[96,96], iconAnchor:[48,48], className:''
    });
  }

  function makeDestIcon() {
    return L.divIcon({
      html: '<div style="width:24px;height:24px;background:#0B1220;border:2.5px solid #3DD6F5;border-radius:50%;display:flex;align-items:center;justify-content:center;box-shadow:0 0 12px #3DD6F5;"><div style="width:8px;height:8px;background:#3DD6F5;border-radius:50%;"></div></div>',
      iconSize:[24,24], iconAnchor:[12,12], className:''
    });
  }

  function makeStartIcon() {
    return L.divIcon({
      html: '<div style="width:16px;height:16px;background:#0B1220;border:3px solid #EDF1F7;border-radius:50%;box-shadow:0 1px 8px rgba(0,0,0,.55)"></div>',
      iconSize:[16,16], iconAnchor:[8,8], className:''
    });
  }

  var marker = null;
  var startMarker = null;
  var destMarker = null;
  var accuracyCircle = null;
  var routeCasing = null;
  var routePolyline = null;
  var trackPath = L.polyline([], {color:'#3DD6F5',weight:3.5,opacity:0.65,dashArray:'4 3'}).addTo(map);
  var isFirstFix = true;

  function updatePosition(lat, lon, bearing, accuracyM, isBootstrap, isDr) {
    var ll = [lat, lon];
    isDr = isDr === true || isDr === 'true';
    if (marker) {
      marker.setIcon(makeVehicleIcon(bearing, isDr));
      marker.setLatLng(ll);
    } else {
      marker = L.marker(ll, {icon: makeVehicleIcon(bearing, isDr), zIndexOffset:1000}).addTo(map);
    }
    if (accuracyCircle) map.removeLayer(accuracyCircle);
    if (accuracyM > 0 && accuracyM < 80) {
      accuracyCircle = L.circle(ll, {
        radius: accuracyM, color: isDr ? '#FFB020' : '#3DD6F5',
        fillColor: isDr ? '#FFB020' : '#3DD6F5',
        fillOpacity: isDr ? 0.12 : 0.06, weight:1.5, dashArray: isDr ? '3 3' : null
      }).addTo(map);
    }
    if (!isBootstrap) trackPath.addLatLng(ll);
    if (isFirstFix) {
      map.setView(ll, 16, {animate:false});
      isFirstFix = false;
    }
  }

  function drawRoute(coords, destLat, destLon) {
    if (!coords || coords.length < 2) return;
    if (routeCasing) { map.removeLayer(routeCasing); routeCasing = null; }
    if (routePolyline) { map.removeLayer(routePolyline); routePolyline = null; }
    if (startMarker)   { map.removeLayer(startMarker); startMarker = null; }
    if (destMarker)    { map.removeLayer(destMarker); destMarker = null; }
    routeCasing = L.polyline(coords, {
      color:'#07101C', weight:10, opacity:0.78,
      lineJoin:'round', lineCap:'round'
    }).addTo(map);
    routePolyline = L.polyline(coords, {
      color:'#3DD6F5', weight:5.5, opacity:0.95,
      lineJoin:'round', lineCap:'round'
    }).addTo(map);
    startMarker = L.marker(coords[0], {icon: makeStartIcon(), interactive:false, zIndexOffset:850}).addTo(map);
    destMarker = L.marker([destLat, destLon], {icon: makeDestIcon(), zIndexOffset:900}).addTo(map);
    var bounds = routePolyline.getBounds().pad(0.18);
    map.fitBounds(bounds, {animate:true, duration:0.5});
  }

  function clearRoute() {
    if (routeCasing) { map.removeLayer(routeCasing); routeCasing = null; }
    if (routePolyline) { map.removeLayer(routePolyline); routePolyline = null; }
    if (startMarker)   { map.removeLayer(startMarker); startMarker = null; }
    if (destMarker)    { map.removeLayer(destMarker); destMarker = null; }
  }

  function clearPath() {
    trackPath.setLatLngs([]);
    isFirstFix = true;
  }
</script>
</body>
</html>
    """.trimIndent()

    // ── Permissions & Lifecycle ────────────────────────────────────────────────

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun checkPermissions() {
        val needed = mutableListOf<String>()
        if (!hasLocationPermission()) needed += Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED)
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        if (needed.isNotEmpty())
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_CODE)
        else
            navController?.start()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        navController?.start()
        if (mapReady) bootstrapLastKnownLocation()
    }

    override fun onResume() {
        super.onResume()
        navController?.start()
        uiHandler.post(uiRunnable)
    }

    override fun onPause() {
        super.onPause()
        // Do NOT call navController.stop() here so DebugActivity can share the live stream
        uiHandler.removeCallbacks(uiRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            navController?.stop()
        }
        navController = null
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etSearchInput.windowToken, 0)
    }

    private fun gnssColor(q: GnssQuality): Int = when (q) {
        GnssQuality.GOOD       -> ContextCompat.getColor(this, R.color.gnss_good)
        GnssQuality.FAIR       -> ContextCompat.getColor(this, R.color.gnss_fair)
        GnssQuality.POOR       -> ContextCompat.getColor(this, R.color.gnss_poor)
        GnssQuality.DENIED     -> ContextCompat.getColor(this, R.color.nav_amber)    // amber, not red
        GnssQuality.RECOVERING -> ContextCompat.getColor(this, R.color.gnss_recovering)
    }

    private fun gnssPillBg(q: GnssQuality): Int = when (q) {
        GnssQuality.GOOD       -> R.drawable.pill_gnss_good
        GnssQuality.FAIR       -> R.drawable.pill_gnss_fair
        GnssQuality.POOR       -> R.drawable.pill_gnss_poor
        GnssQuality.DENIED     -> R.drawable.pill_gnss_poor   // amber pill for DENIED (not red)
        GnssQuality.RECOVERING -> R.drawable.pill_gnss_recovering
    }

    private fun mlColor(state: NavigationState): Int = ContextCompat.getColor(
        this,
        when {
            state.mlInferenceActive -> R.color.ml_active
            state.mlError != null -> R.color.nav_red
            state.mlModelLoaded -> R.color.ml_ready
            else -> R.color.text_tertiary
        }
    )

    private fun maneuverIcon(type: ManeuverType?): String = when (type) {
        ManeuverType.TURN_LEFT    -> "←"
        ManeuverType.TURN_RIGHT   -> "→"
        ManeuverType.SLIGHT_LEFT  -> "↖"
        ManeuverType.SLIGHT_RIGHT -> "↗"
        ManeuverType.SHARP_LEFT   -> "↙"
        ManeuverType.SHARP_RIGHT  -> "↘"
        ManeuverType.U_TURN       -> "↺"
        ManeuverType.ROUNDABOUT   -> "⊙"
        ManeuverType.ARRIVE       -> "🏁"
        ManeuverType.DEPART       -> "↑"
        else                      -> "↑"
    }
}
