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

    // ── Top Bar / Overlays ──────────────────────────────────────────────────────
    private lateinit var searchBarContainer: LinearLayout
    private lateinit var searchBarClickable: LinearLayout
    private lateinit var tvSearchHint: TextView
    private lateinit var btnDevMode: TextView

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
    private lateinit var panelIdleSheet: LinearLayout
    private lateinit var tvGnssIdleBadge: TextView
    private lateinit var btnShortcutHome: LinearLayout
    private lateinit var btnShortcutWork: LinearLayout

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
            cameraState = MapCameraState.FOLLOWING
            updateRecenterButtonAppearance()
        }

        btnDone.setOnClickListener {
            navController?.stopNavigation()
            mapWebView.evaluateJavascript("clearRoute(); clearPath();", null)
            routeDrawn = false
            cameraState = MapCameraState.FOLLOWING
            updateRecenterButtonAppearance()
        }
    }

    private fun bindViews() {
        mapWebView                  = findViewById(R.id.mapWebView)
        searchBarContainer          = findViewById(R.id.searchBarContainer)
        searchBarClickable          = findViewById(R.id.searchBarClickable)
        tvSearchHint                = findViewById(R.id.tvSearchHint)
        btnDevMode                  = findViewById(R.id.btnDevMode)

        maneuverCard                = findViewById(R.id.maneuverCard)
        tvManeuverIcon              = findViewById(R.id.tvManeuverIcon)
        tvManeuverDistance          = findViewById(R.id.tvManeuverDistance)
        tvManeuverInstruction       = findViewById(R.id.tvManeuverInstruction)
        tvGnssBadge                 = findViewById(R.id.tvGnssBadge)
        rowSecondManeuver           = findViewById(R.id.rowSecondManeuver)
        tvSecondManeuverIcon        = findViewById(R.id.tvSecondManeuverIcon)
        tvSecondManeuverInstruction = findViewById(R.id.tvSecondManeuverInstruction)

        btnCompass                  = findViewById(R.id.btnCompass)
        tvCompassNeedle             = findViewById(R.id.tvCompassNeedle)
        btnRecenter                 = findViewById(R.id.btnRecenter)
        tvRecenterIcon              = findViewById(R.id.tvRecenterIcon)

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

        bottomSheet                 = findViewById(R.id.bottomSheet)
        panelIdleSheet              = findViewById(R.id.panelIdleSheet)
        tvGnssIdleBadge             = findViewById(R.id.tvGnssIdleBadge)
        btnShortcutHome             = findViewById(R.id.btnShortcutHome)
        btnShortcutWork             = findViewById(R.id.btnShortcutWork)

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

        searchBarClickable.setOnClickListener {
            openSearchOverlay()
        }

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
                    searchDebounceHandler.postDelayed(searchRunnable!!, 400) // 400ms debounce
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

        btnShortcutHome.setOnClickListener { openSearchOverlay(); triggerChipSearch("Home") }
        btnShortcutWork.setOnClickListener { openSearchOverlay(); triggerChipSearch("Work") }
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
        renderTopBar(state)
        renderBottomSheet(state)
        renderGnssPill(state)
        renderMap(state)
        renderCompass(state)
    }

    private fun renderTopBar(state: NavigationState) {
        val isNavigating = state.navMode == NavMode.NAVIGATING ||
                state.navMode == NavMode.GNSS_DEGRADED ||
                state.navMode == NavMode.GNSS_DENIED

        searchBarContainer.visibility = if (isNavigating) View.GONE else View.VISIBLE
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
    }

    private fun renderBottomSheet(state: NavigationState) {
        val allSheets = listOf(panelIdleSheet, panelRoutePreviewSheet, panelNavigatingSheet, panelArrivedSheet)

        fun show(sheet: LinearLayout) {
            allSheets.forEach { it.visibility = if (it == sheet) View.VISIBLE else View.GONE }
        }

        when (state.navMode) {
            NavMode.IDLE -> {
                show(panelIdleSheet)
                tvGnssIdleBadge.text = "● ${state.gnssQuality.label()}"
                tvGnssIdleBadge.setTextColor(gnssColor(state.gnssQuality))
                tvGnssIdleBadge.setBackgroundResource(gnssPillBg(state.gnssQuality))
            }
            NavMode.SEARCHING -> {
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

    private fun renderGnssPill(state: NavigationState) {
        tvGnssBadge.text = state.gnssQuality.label()
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
        if (cameraState == MapCameraState.FREE_BROWSE) {
            tvRecenterIcon.setTextColor(ContextCompat.getColor(this, R.color.nav_amber))
        } else {
            tvRecenterIcon.setTextColor(ContextCompat.getColor(this, R.color.percorsa_primary))
        }
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
    html, body, #map { height:100%; width:100%; background:#1A2232; }
    .leaflet-control-zoom { display:none!important; }
    .leaflet-control-attribution {
      font-size:8px; opacity:0.4; background:transparent!important; color:#94A3B8!important; margin-bottom: 240px!important;
    }
    @keyframes pulse {
      0%   { box-shadow: 0 0 0 0 rgba(56,189,248,0.5); }
      70%  { box-shadow: 0 0 0 14px rgba(56,189,248,0); }
      100% { box-shadow: 0 0 0 0 rgba(56,189,248,0); }
    }
    @keyframes pulseDr {
      0%   { box-shadow: 0 0 0 0 rgba(245,158,11,0.6); }
      70%  { box-shadow: 0 0 0 14px rgba(245,158,11,0); }
      100% { box-shadow: 0 0 0 0 rgba(245,158,11,0); }
    }
  </style>
</head>
<body>
<div id="map"></div>
<script>
  var map = L.map('map', {
    zoomControl: false, attributionControl: true,
    zoomAnimation: true, fadeAnimation: false, preferCanvas: true
  }).setView([20.5937, 78.9629], 5);

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

  function makeVehicleIcon(bearing, isDr) {
    var color = isDr ? '#F59E0B' : '#38BDF8';
    var animClass = isDr ? 'pulseDr' : 'pulse';
    var svg = '<svg xmlns="http://www.w3.org/2000/svg" width="48" height="48" viewBox="0 0 48 48">' +
      '<circle cx="24" cy="24" r="14" fill="' + color + '" stroke="#ffffff" stroke-width="3" ' +
      'style="animation:' + animClass + ' 2s infinite;"/>' +
      '<polygon points="24,4 18,22 24,18 30,22" fill="#ffffff" opacity="0.95"/>' +
      '</svg>';
    return L.divIcon({
      html: '<div style="transform-origin:center;transform:rotate('+bearing+'deg);transition:transform 0.15s linear;">' + svg + '</div>',
      iconSize:[48,48], iconAnchor:[24,24], className:''
    });
  }

  function makeDestIcon() {
    return L.divIcon({
      html: '<div style="font-size:32px;line-height:1;filter:drop-shadow(0 2px 4px rgba(0,0,0,0.5));">📍</div>',
      iconSize:[34,40], iconAnchor:[17,40], className:''
    });
  }

  var marker = null;
  var destMarker = null;
  var accuracyCircle = null;
  var routePolyline = null;
  var trackPath = L.polyline([], {color:'#38BDF8',weight:3,opacity:0.4,dashArray:'6 4'}).addTo(map);
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
        radius: accuracyM, color: isDr ? '#F59E0B' : '#38BDF8',
        fillColor: isDr ? '#F59E0B' : '#38BDF8',
        fillOpacity: 0.08, weight:1, dashArray:'4'
      }).addTo(map);
    }
    if (!isBootstrap) trackPath.addLatLng(ll);
    if (isFirstFix) {
      map.setView(ll, 16, {animate:false});
      isFirstFix = false;
    }
  }

  function drawRoute(coords, destLat, destLon) {
    if (routePolyline) { map.removeLayer(routePolyline); routePolyline = null; }
    if (destMarker)    { map.removeLayer(destMarker); destMarker = null; }
    routePolyline = L.polyline(coords, {
      color:'#0284C7', weight:6, opacity:0.85,
      lineJoin:'round', lineCap:'round'
    }).addTo(map);
    destMarker = L.marker([destLat, destLon], {icon: makeDestIcon(), zIndexOffset:900}).addTo(map);
    var bounds = routePolyline.getBounds().pad(0.15);
    map.fitBounds(bounds, {animate:true, duration:0.5});
  }

  function clearRoute() {
    if (routePolyline) { map.removeLayer(routePolyline); routePolyline = null; }
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
        navController?.stop()
        uiHandler.removeCallbacks(uiRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
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
        GnssQuality.DENIED     -> ContextCompat.getColor(this, R.color.gnss_denied)
        GnssQuality.RECOVERING -> ContextCompat.getColor(this, R.color.gnss_recovering)
    }

    private fun gnssPillBg(q: GnssQuality): Int = when (q) {
        GnssQuality.GOOD       -> R.drawable.pill_gnss_good
        GnssQuality.FAIR       -> R.drawable.pill_gnss_fair
        GnssQuality.POOR       -> R.drawable.pill_gnss_poor
        GnssQuality.DENIED     -> R.drawable.pill_gnss_denied
        GnssQuality.RECOVERING -> R.drawable.pill_gnss_recovering
    }

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
