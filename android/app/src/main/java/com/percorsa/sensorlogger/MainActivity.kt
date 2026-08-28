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
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
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

/**
 * Main navigation activity.
 *
 * This activity is the consumer-facing navigation UI.
 * It consumes [NavigationState] from [NavigationController] and renders:
 *   - The Leaflet map (full-screen, WebView)
 *   - A search bar ("Where to?") or maneuver card at the top
 *   - A bottom panel that switches between idle/search/route-preview/navigating/arrived
 *
 * IMPORTANT:
 * This activity does NOT read raw sensor data directly.
 * All position, heading, speed, GNSS, and route information comes from [NavigationState].
 * Raw sensor data is only shown in [DebugActivity].
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** Shared reference used by DebugActivity to access the same controller instance. */
        var navController: NavigationController? = null
    }

    // ── Map ────────────────────────────────────────────────────────────────────
    private lateinit var mapWebView: WebView
    private var mapReady = false
    private var lastMapLat = 0.0
    private var lastMapLon = 0.0
    private var lastMapBearing = 0f
    private var routeDrawn = false

    // ── Top bar ────────────────────────────────────────────────────────────────
    private lateinit var searchBarContainer: LinearLayout
    private lateinit var searchBarClickable: LinearLayout
    private lateinit var tvSearchHint: TextView
    private lateinit var btnDevMode: TextView
    private lateinit var maneuverCard: LinearLayout
    private lateinit var tvManeuverIcon: TextView
    private lateinit var tvManeuverDistance: TextView
    private lateinit var tvManeuverInstruction: TextView
    private lateinit var tvGnssBadge: TextView

    // ── Floating controls ──────────────────────────────────────────────────────
    private lateinit var btnRecenter: TextView

    // ── Bottom panels ──────────────────────────────────────────────────────────
    private lateinit var panelIdle: LinearLayout
    private lateinit var tvGnssIdleBadge: TextView

    private lateinit var panelSearch: LinearLayout
    private lateinit var etSearch: EditText
    private lateinit var btnSearchCancel: TextView
    private lateinit var searchProgressBar: ProgressBar
    private lateinit var tvSearchError: TextView
    private lateinit var rvSearchResults: RecyclerView
    private lateinit var searchAdapter: SearchResultsAdapter

    private lateinit var panelRoutePreview: LinearLayout
    private lateinit var tvDestinationName: TextView
    private lateinit var tvDestinationAddress: TextView
    private lateinit var tvRouteDistance: TextView
    private lateinit var tvRouteEta: TextView
    private lateinit var routeProgressBar: ProgressBar
    private lateinit var tvRouteError: TextView
    private lateinit var btnCancelRoute: Button
    private lateinit var btnStartNav: Button

    private lateinit var panelNavigating: LinearLayout
    private lateinit var tvNavSpeed: TextView
    private lateinit var tvNavDistance: TextView
    private lateinit var tvNavEta: TextView
    private lateinit var tvDrStatusLine: TextView
    private lateinit var btnEndNav: Button

    private lateinit var panelArrived: LinearLayout
    private lateinit var tvArrivedDestName: TextView
    private lateinit var btnDone: Button

    // ── UI update ──────────────────────────────────────────────────────────────
    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiRunnable = object : Runnable {
        override fun run() {
            navController?.tick()
            renderState(navController?.state?.value ?: return)
            uiHandler.postDelayed(this, 100)
        }
    }

    private val PERMISSION_CODE = 1001
    private var lastRenderedMode: NavMode? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Create NavigationController (owns SensorEngine + DR + services)
        if (navController == null) {
            navController = NavigationController(this)
        }

        bindViews()
        setupSearch()
        setupMapWebView()
        checkPermissions()

        btnDevMode.setOnClickListener {
            startActivity(Intent(this, DebugActivity::class.java))
        }

        btnRecenter.setOnClickListener {
            val s = navController?.state?.value ?: return@setOnClickListener
            if (s.hasValidPosition) {
                mapWebView.evaluateJavascript(
                    "map.setView([${s.latitude}, ${s.longitude}], 17, {animate:true, duration:0.5});", null)
            }
        }

        btnCancelRoute.setOnClickListener {
            navController?.cancelSearch()
            mapWebView.evaluateJavascript("clearRoute();", null)
        }

        btnStartNav.setOnClickListener {
            navController?.beginDriving()
        }

        btnEndNav.setOnClickListener {
            navController?.stopNavigation()
            mapWebView.evaluateJavascript("clearRoute(); clearPath();", null)
            routeDrawn = false
        }

        btnDone.setOnClickListener {
            navController?.stopNavigation()
            mapWebView.evaluateJavascript("clearRoute(); clearPath();", null)
            routeDrawn = false
        }
    }

    private fun bindViews() {
        mapWebView             = findViewById(R.id.mapWebView)
        searchBarContainer     = findViewById(R.id.searchBarContainer)
        searchBarClickable     = findViewById(R.id.searchBarClickable)
        tvSearchHint           = findViewById(R.id.tvSearchHint)
        btnDevMode             = findViewById(R.id.btnDevMode)
        maneuverCard           = findViewById(R.id.maneuverCard)
        tvManeuverIcon         = findViewById(R.id.tvManeuverIcon)
        tvManeuverDistance     = findViewById(R.id.tvManeuverDistance)
        tvManeuverInstruction  = findViewById(R.id.tvManeuverInstruction)
        tvGnssBadge            = findViewById(R.id.tvGnssBadge)
        btnRecenter            = findViewById(R.id.btnRecenter)
        panelIdle              = findViewById(R.id.panelIdle)
        tvGnssIdleBadge        = findViewById(R.id.tvGnssIdleBadge)
        panelSearch            = findViewById(R.id.panelSearch)
        etSearch               = findViewById(R.id.etSearch)
        btnSearchCancel        = findViewById(R.id.btnSearchCancel)
        searchProgressBar      = findViewById(R.id.searchProgressBar)
        tvSearchError          = findViewById(R.id.tvSearchError)
        rvSearchResults        = findViewById(R.id.rvSearchResults)
        panelRoutePreview      = findViewById(R.id.panelRoutePreview)
        tvDestinationName      = findViewById(R.id.tvDestinationName)
        tvDestinationAddress   = findViewById(R.id.tvDestinationAddress)
        tvRouteDistance        = findViewById(R.id.tvRouteDistance)
        tvRouteEta             = findViewById(R.id.tvRouteEta)
        routeProgressBar       = findViewById(R.id.routeProgressBar)
        tvRouteError           = findViewById(R.id.tvRouteError)
        btnCancelRoute         = findViewById(R.id.btnCancelRoute)
        btnStartNav            = findViewById(R.id.btnStartNav)
        panelNavigating        = findViewById(R.id.panelNavigating)
        tvNavSpeed             = findViewById(R.id.tvNavSpeed)
        tvNavDistance          = findViewById(R.id.tvNavDistance)
        tvNavEta               = findViewById(R.id.tvNavEta)
        tvDrStatusLine         = findViewById(R.id.tvDrStatusLine)
        btnEndNav              = findViewById(R.id.btnEndNav)
        panelArrived           = findViewById(R.id.panelArrived)
        tvArrivedDestName      = findViewById(R.id.tvArrivedDestName)
        btnDone                = findViewById(R.id.btnDone)
    }

    private fun setupSearch() {
        searchAdapter = SearchResultsAdapter { result ->
            hideKeyboard()
            navController?.startNavigation(result)
        }
        rvSearchResults.layoutManager = LinearLayoutManager(this)
        rvSearchResults.adapter = searchAdapter

        searchBarClickable.setOnClickListener {
            showSearchPanel()
        }

        btnSearchCancel.setOnClickListener {
            navController?.cancelSearch()
            hideKeyboard()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString() ?: ""
                if (query.length >= 2) navController?.search(query)
                else if (query.isEmpty()) navController?.cancelSearch().also { showSearchPanel() }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        etSearch.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                navController?.search(v.text.toString())
                hideKeyboard()
                true
            } else false
        }
    }

    private fun showSearchPanel() {
        panelIdle.visibility = View.GONE
        panelSearch.visibility = View.VISIBLE
        panelRoutePreview.visibility = View.GONE
        panelNavigating.visibility = View.GONE
        panelArrived.visibility = View.GONE
        etSearch.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    // ── Map ────────────────────────────────────────────────────────────────────

    private fun setupMapWebView() {
        with(mapWebView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = true
            setRenderPriority(android.webkit.WebSettings.RenderPriority.HIGH)
        }
        mapWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
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

    private fun bootstrapLastKnownLocation() {
        if (!hasLocationPermission()) return
        try {
            val lm = getSystemService(LOCATION_SERVICE) as? android.location.LocationManager ?: return
            for (provider in listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER)) {
                try {
                    val loc = lm.getLastKnownLocation(provider)
                    if (loc != null && loc.accuracy < 200f) {
                        val js = "updatePosition(${loc.latitude},${loc.longitude},${loc.bearing},${loc.accuracy},true);"
                        mapWebView.post { mapWebView.evaluateJavascript(js, null) }
                        break
                    }
                } catch (_: SecurityException) {}
            }
        } catch (_: Exception) {}
    }

    // ── State rendering ────────────────────────────────────────────────────────

    private fun renderState(state: NavigationState) {
        renderTopBar(state)
        renderBottomPanel(state)
        renderGnssBadge(state)
        renderMap(state)
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
        }
    }

    private fun renderBottomPanel(state: NavigationState) {
        // Avoid flickering by only switching when mode actually changes
        val allPanels = listOf(panelIdle, panelSearch, panelRoutePreview, panelNavigating, panelArrived)

        fun show(panel: LinearLayout) {
            allPanels.forEach { it.visibility = if (it == panel) View.VISIBLE else View.GONE }
        }

        when (state.navMode) {
            NavMode.IDLE -> {
                show(panelIdle)
                tvGnssIdleBadge.text = "● ${state.gnssQuality.label()}"
                tvGnssIdleBadge.setTextColor(gnssColor(state.gnssQuality))
            }
            NavMode.SEARCHING -> {
                show(panelSearch)
                searchProgressBar.visibility =
                    if (state.searchLoading) View.VISIBLE else View.GONE
                tvSearchError.visibility =
                    if (state.searchError != null) View.VISIBLE else View.GONE
                tvSearchError.text = state.searchError ?: ""
                searchAdapter.submitList(state.searchResults)
            }
            NavMode.ROUTE_PREVIEW -> {
                show(panelRoutePreview)
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
                show(panelNavigating)
                tvNavSpeed.text = state.speedKmh.toString()
                tvNavDistance.text = state.distanceFormatted
                tvNavEta.text = state.etaFormatted
                val statusLine = state.statusLine
                tvDrStatusLine.text = statusLine
                tvDrStatusLine.visibility = if (statusLine.isNotEmpty()) View.VISIBLE else View.GONE
            }
            NavMode.ARRIVED -> {
                show(panelArrived)
                tvArrivedDestName.text = state.destination?.name ?: ""
            }
            NavMode.ERROR -> {
                show(panelIdle)
                state.errorMessage?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun renderGnssBadge(state: NavigationState) {
        tvGnssBadge.text = state.gnssQuality.label()
        tvGnssBadge.setTextColor(gnssColor(state.gnssQuality))
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

        // Draw route when it first appears
        if (!routeDrawn && state.route != null) {
            routeDrawn = true
            drawRoute(state.route!!, state.destination)
        }

        // Clear route if navigation stopped
        if (routeDrawn && state.route == null) {
            routeDrawn = false
            mapWebView.evaluateJavascript("clearRoute();", null)
        }

        // Pan camera during navigation
        val isNavigating = state.navMode == NavMode.NAVIGATING ||
                state.navMode == NavMode.GNSS_DEGRADED ||
                state.navMode == NavMode.GNSS_DENIED
        if (isNavigating) {
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

    // ── Map HTML ──────────────────────────────────────────────────────────────

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
    .leaflet-control-zoom { border:none!important; margin:8px!important; }
    .leaflet-control-zoom a {
      background:#1E293B!important; color:#38BDF8!important;
      border:1px solid #334155!important; width:34px!important; height:34px!important;
      line-height:34px!important; font-size:18px!important; border-radius:8px!important;
    }
    .leaflet-control-attribution {
      font-size:7px; opacity:0.3; background:transparent!important; color:#888!important;
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
    zoomControl: true, attributionControl: true,
    zoomAnimation: true, fadeAnimation: false, preferCanvas: true
  }).setView([20.5937, 78.9629], 5);

  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '&copy; OpenStreetMap', maxZoom: 19,
    subdomains: ['a','b','c'], keepBuffer:4,
    updateWhenIdle:false, updateWhenZooming:false
  }).addTo(map);

  function makeVehicleIcon(bearing, isDr) {
    var color = isDr ? '#F59E0B' : '#38BDF8';
    var animClass = isDr ? 'pulseDr' : 'pulse';
    var svg = '<svg xmlns="http://www.w3.org/2000/svg" width="44" height="44" viewBox="0 0 44 44">' +
      '<circle cx="22" cy="22" r="12" fill="' + color + '" stroke="#fff" stroke-width="2.5" ' +
      'style="animation:' + animClass + ' 2s infinite;"/>' +
      '<polygon points="22,5 17,20 22,17 27,20" fill="#fff" opacity="0.95"/>' +
      '</svg>';
    return L.divIcon({
      html: '<div style="transform-origin:center;transform:rotate('+bearing+'deg)">' + svg + '</div>',
      iconSize:[44,44], iconAnchor:[22,22], className:''
    });
  }

  function makeDestIcon() {
    return L.divIcon({
      html: '<div style="font-size:28px;line-height:1;text-shadow:0 1px 3px rgba(0,0,0,.6)">📍</div>',
      iconSize:[30,36], iconAnchor:[15,36], className:''
    });
  }

  var marker = null;
  var destMarker = null;
  var accuracyCircle = null;
  var routePolyline = null;
  var trackPath = L.polyline([], {color:'#38BDF8',weight:3,opacity:0.5,dashArray:'6 4'}).addTo(map);
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
        fillOpacity: 0.07, weight:1, dashArray:'4'
      }).addTo(map);
    }
    if (!isBootstrap) trackPath.addLatLng(ll);
    if (isFirstFix) {
      map.setView(ll, 17, {animate:false});
      isFirstFix = false;
    }
  }

  function drawRoute(coords, destLat, destLon) {
    if (routePolyline) { map.removeLayer(routePolyline); routePolyline = null; }
    if (destMarker)    { map.removeLayer(destMarker); destMarker = null; }
    routePolyline = L.polyline(coords, {
      color:'#0284C7', weight:5.5, opacity:0.85,
      lineJoin:'round', lineCap:'round'
    }).addTo(map);
    destMarker = L.marker([destLat, destLon], {icon: makeDestIcon(), zIndexOffset:900}).addTo(map);
    var bounds = routePolyline.getBounds().pad(0.1);
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

    // ── Permissions ────────────────────────────────────────────────────────────

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

    // ── Lifecycle ──────────────────────────────────────────────────────────────

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
        imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
    }

    private fun gnssColor(q: GnssQuality): Int = when (q) {
        GnssQuality.GOOD   -> 0xFF34D399.toInt()
        GnssQuality.FAIR   -> 0xFF38BDF8.toInt()
        GnssQuality.POOR   -> 0xFFF59E0B.toInt()
        GnssQuality.DENIED -> 0xFFF87171.toInt()
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
