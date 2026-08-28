package com.percorsa.sensorlogger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var sensorEngine: SensorEngine
    private var lastRecordedFile: File? = null

    // MAP
    private lateinit var mapWebView: WebView
    private var mapReady = false
    private var lastMapLat = 0.0
    private var lastMapLon = 0.0
    private var lastMapBearing = 0f

    // UI - visible views
    private lateinit var tvGpsAccuracyBadge: TextView
    private lateinit var tvGpsCoordinates: TextView
    private lateinit var tvGpsSpeed: TextView
    private lateinit var tvSpeedLarge: TextView
    private lateinit var tvImuHz: TextView
    private lateinit var tvAccuracyVal: TextView
    private lateinit var tvSampleCount: TextView
    private lateinit var tvAccelValues: TextView
    private lateinit var tvOrientValues: TextView
    private lateinit var tvTripStatus: TextView
    private lateinit var tvTripDuration: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnCalibrate: Button
    private lateinit var btnClearBuffer: Button
    private lateinit var btnShare: Button

    // Hidden back-compat stubs
    private lateinit var tvImuSystemStatus: TextView
    private lateinit var tvTripRecordingStatus: TextView
    private lateinit var tvTripSummaryDetails: TextView
    private lateinit var tvAccelStatus: TextView
    private lateinit var tvAccelHz: TextView
    private lateinit var tvGyroStatus: TextView
    private lateinit var tvGyroHz: TextView
    private lateinit var tvLiveMotionData: TextView
    private lateinit var tvBufferCount: TextView
    private lateinit var pbBufferProgress: ProgressBar
    private lateinit var tvTimingDiagnostics: TextView

    private val PERMISSION_REQUEST_CODE = 1001
    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiRunnable = object : Runnable {
        override fun run() {
            updateUi()
            uiHandler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sensorEngine = SensorEngine(this)

        mapWebView            = findViewById(R.id.mapWebView)
        tvGpsAccuracyBadge    = findViewById(R.id.tvGpsAccuracyBadge)
        tvGpsCoordinates      = findViewById(R.id.tvGpsCoordinates)
        tvGpsSpeed            = findViewById(R.id.tvGpsSpeed)
        tvSpeedLarge          = findViewById(R.id.tvSpeedLarge)
        tvImuHz               = findViewById(R.id.tvImuHz)
        tvAccuracyVal         = findViewById(R.id.tvAccuracyVal)
        tvSampleCount         = findViewById(R.id.tvSampleCount)
        tvAccelValues         = findViewById(R.id.tvAccelValues)
        tvOrientValues        = findViewById(R.id.tvOrientValues)
        tvTripStatus          = findViewById(R.id.tvTripStatus)
        tvTripDuration        = findViewById(R.id.tvTripDuration)
        btnRecord             = findViewById(R.id.btnRecord)
        btnCalibrate          = findViewById(R.id.btnCalibrate)
        btnClearBuffer        = findViewById(R.id.btnClearBuffer)
        btnShare              = findViewById(R.id.btnShare)

        // stubs
        tvImuSystemStatus     = findViewById(R.id.tvImuSystemStatus)
        tvTripRecordingStatus = findViewById(R.id.tvTripRecordingStatus)
        tvTripSummaryDetails  = findViewById(R.id.tvTripSummaryDetails)
        tvAccelStatus         = findViewById(R.id.tvAccelStatus)
        tvAccelHz             = findViewById(R.id.tvAccelHz)
        tvGyroStatus          = findViewById(R.id.tvGyroStatus)
        tvGyroHz              = findViewById(R.id.tvGyroHz)
        tvLiveMotionData      = findViewById(R.id.tvLiveMotionData)
        tvBufferCount         = findViewById(R.id.tvBufferCount)
        pbBufferProgress      = findViewById(R.id.pbBufferProgress)
        tvTimingDiagnostics   = findViewById(R.id.tvTimingDiagnostics)

        initMap()
        checkPermissions()

        btnRecord.setOnClickListener {
            if (sensorEngine.isRecording) {
                sensorEngine.stopRecording()
                btnRecord.text = "● REC"
                btnRecord.setBackgroundColor(0xFFDC2626.toInt())
                btnShare.isEnabled = lastRecordedFile?.exists() == true
                tvTripStatus.text = "● SAVED"
                tvTripStatus.setTextColor(0xFF34D399.toInt())
                Toast.makeText(this, "Trip saved!", Toast.LENGTH_SHORT).show()
            } else {
                mapWebView.evaluateJavascript("clearPath();", null)
                val recorder = CsvRecorder(this)
                lastRecordedFile = recorder.file
                sensorEngine.startRecording(recorder)
                btnRecord.text = "■ STOP"
                btnRecord.setBackgroundColor(0xFF1E293B.toInt())
                btnShare.isEnabled = false
                tvTripStatus.text = "● RECORDING"
                tvTripStatus.setTextColor(0xFFEF4444.toInt())
                Toast.makeText(this, "Recording started!", Toast.LENGTH_SHORT).show()
            }
        }

        btnCalibrate.setOnClickListener {
            sensorEngine.calibrateVehicleFrame()
            Toast.makeText(this, "Frame calibrated ✓", Toast.LENGTH_SHORT).show()
        }

        btnClearBuffer.setOnClickListener {
            Toast.makeText(this, "Buffer cleared", Toast.LENGTH_SHORT).show()
        }

        btnShare.setOnClickListener { shareCsv() }
    }

    // ─── MAP ─────────────────────────────────────────────────────────────────

    private fun initMap() {
        with(mapWebView.settings) {
            javaScriptEnabled   = true
            domStorageEnabled   = true
            cacheMode           = WebSettings.LOAD_DEFAULT
            mixedContentMode    = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess     = true
            setRenderPriority(android.webkit.WebSettings.RenderPriority.HIGH)
        }
        mapWebView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        mapWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                mapReady = true
                // Bootstrap from last known location immediately
                bootstrapLastKnownLocation()
            }
        }
        mapWebView.loadDataWithBaseURL(
            "https://nominatim.openstreetmap.org",
            mapHtml(), "text/html", "UTF-8", null
        )
    }

    private fun bootstrapLastKnownLocation() {
        if (!hasLocationPermission()) return
        try {
            val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return
            // Try GPS first, fallback to network for immediate rough fix
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            for (provider in providers) {
                try {
                    val loc = lm.getLastKnownLocation(provider)
                    if (loc != null && loc.accuracy < 200f) {
                        val js = "updatePosition(${loc.latitude}, ${loc.longitude}, " +
                                 "${loc.bearing}, ${loc.accuracy}, true);"
                        mapWebView.post { mapWebView.evaluateJavascript(js, null) }
                        break
                    }
                } catch (e: SecurityException) { /* skip */ }
            }
        } catch (e: Exception) { /* ignore */ }
    }

    private fun mapHtml(): String = """
<!DOCTYPE html>
<html>
<head>
  <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
  <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
  <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
  <style>
    * { margin:0; padding:0; box-sizing:border-box; }
    html, body, #map { height:100%; width:100%; background:#1A2232; }
    .leaflet-control-zoom {
      border: none !important;
      margin: 8px !important;
    }
    .leaflet-control-zoom a {
      background: #1E293B !important;
      color: #38BDF8 !important;
      border: 1px solid #334155 !important;
      width: 32px !important;
      height: 32px !important;
      line-height: 32px !important;
      font-size: 18px !important;
      border-radius: 8px !important;
    }
    .leaflet-control-attribution {
      font-size: 7px; opacity: 0.4; background: transparent !important; color: #aaa !important;
    }
    .pulsedot {
      animation: pulse 2s infinite;
    }
    @keyframes pulse {
      0%   { box-shadow: 0 0 0 0 rgba(56,189,248,0.5); }
      70%  { box-shadow: 0 0 0 12px rgba(56,189,248,0); }
      100% { box-shadow: 0 0 0 0 rgba(56,189,248,0); }
    }
  </style>
</head>
<body>
<div id="map"></div>
<script>
  var map = L.map('map', {
    zoomControl: true,
    attributionControl: true,
    zoomAnimation: true,
    fadeAnimation: false,
    preferCanvas: true
  }).setView([20.5937, 78.9629], 15);

  /* OpenStreetMap — free, no API key required */
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '&copy; OpenStreetMap',
    maxZoom: 19,
    subdomains: ['a','b','c'],
    keepBuffer: 4,
    updateWhenIdle: false,
    updateWhenZooming: false
  }).addTo(map);

  /* Heading arrow vehicle marker */
  function makeIcon(bearing) {
    var svg = '<svg xmlns="http://www.w3.org/2000/svg" width="40" height="40" viewBox="0 0 40 40">' +
      '<circle cx="20" cy="20" r="11" fill="#38BDF8" stroke="#ffffff" stroke-width="2.5" class="pulsedot"/>' +
      '<polygon points="20,4 15,18 20,15 25,18" fill="#ffffff" opacity="0.95"/>' +
      '</svg>';
    return L.divIcon({
      html: '<div style="transform-origin:center;transform:rotate('+bearing+'deg)">'+svg+'</div>',
      iconSize: [40,40],
      iconAnchor: [20,20],
      className: ''
    });
  }

  var accuracyCircle = null;
  var marker = null;
  var path = L.polyline([], {
    color:'#38BDF8', weight:4.5, opacity:0.85,
    lineJoin:'round', lineCap:'round',
    smoothFactor: 1
  }).addTo(map);

  var isFirstFix = true;
  var lastBearing = 0;

  function updatePosition(lat, lon, bearing, accuracyM, isBootstrap) {
    var ll = [lat, lon];

    if (marker) {
      marker.setIcon(makeIcon(bearing));
      marker.setLatLng(ll);
    } else {
      marker = L.marker(ll, {icon: makeIcon(bearing), zIndexOffset: 1000}).addTo(map);
    }

    /* accuracy circle - only if reasonable */
    if (accuracyCircle) map.removeLayer(accuracyCircle);
    if (accuracyM > 0 && accuracyM < 60) {
      accuracyCircle = L.circle(ll, {
        radius: accuracyM,
        color:'#38BDF8', fillColor:'#38BDF8',
        fillOpacity: 0.07, weight: 1, dashArray:'4'
      }).addTo(map);
    }

    if (!isBootstrap) {
      path.addLatLng(ll);
    }

    if (isFirstFix) {
      map.setView(ll, 17, {animate: false});
      isFirstFix = false;
    } else if (!isBootstrap) {
      /* Smooth pan without zoom jumps */
      map.panTo(ll, {animate: true, duration: 0.3, easeLinearity: 0.5});
    }
  }

  function clearPath() {
    path.setLatLngs([]);
    isFirstFix = true;
  }

  /* Re-centre button */
  var RecenterControl = L.Control.extend({
    onAdd: function() {
      var btn = L.DomUtil.create('button');
      btn.innerHTML = '⊙';
      btn.title = 'Centre on vehicle';
      btn.style.cssText = 'background:#1E293B;color:#38BDF8;border:1px solid #334155;' +
        'border-radius:8px;width:34px;height:34px;font-size:20px;cursor:pointer;' +
        'display:flex;align-items:center;justify-content:center;';
      L.DomEvent.on(btn, 'click', function(e) {
        L.DomEvent.stopPropagation(e);
        if (marker) map.setView(marker.getLatLng(), 17, {animate:true, duration:0.5});
      });
      return btn;
    }
  });
  new RecenterControl({position:'topright'}).addTo(map);
</script>
</body>
</html>
    """.trimIndent()

    // ─── PERMISSIONS ─────────────────────────────────────────────────────────

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

    private fun checkPermissions() {
        val needed = mutableListOf<String>()
        if (!hasLocationPermission())
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED)
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        if (needed.isNotEmpty())
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        sensorEngine.start()
        if (mapReady) bootstrapLastKnownLocation()
    }

    // ─── LIFECYCLE ────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        sensorEngine.start()
        uiHandler.post(uiRunnable)
    }

    override fun onPause() {
        super.onPause()
        sensorEngine.stop()
        uiHandler.removeCallbacks(uiRunnable)
    }

    // ─── UI UPDATE ────────────────────────────────────────────────────────────

    private fun updateUi() {
        val snap = sensorEngine.getSnapshot()
        val hasGps = snap.hasGps && snap.latitude != 0.0

        // Speed
        val kmh = snap.gpsSpeedMps * 3.6f
        tvSpeedLarge.text = "%.0f".format(Locale.US, kmh)
        tvGpsSpeed.text   = "%.1f km/h".format(Locale.US, kmh)

        // IMU rate
        tvImuHz.text = "%.0f".format(Locale.US, snap.imuHz)

        // Accuracy card
        if (hasGps) {
            val acc = snap.gpsAccuracyM
            tvAccuracyVal.text = "%.0f".format(Locale.US, acc)
            val accColor = when {
                acc <= 8f  -> 0xFF34D399.toInt()
                acc <= 20f -> 0xFF38BDF8.toInt()
                acc <= 40f -> 0xFFF59E0B.toInt()
                else       -> 0xFFF87171.toInt()
            }
            tvAccuracyVal.setTextColor(accColor)
        } else {
            tvAccuracyVal.text = "--"
            tvAccuracyVal.setTextColor(0xFF64748B.toInt())
        }

        // Sample count
        tvSampleCount.text = if (snap.loggedCsvRows > 9999)
            "%.0fk".format(snap.loggedCsvRows / 1000.0)
        else snap.loggedCsvRows.toString()

        // GPS badge + coordinates
        if (hasGps) {
            val acc = snap.gpsAccuracyM
            val (label, color) = when {
                acc <= 8f  -> "● GPS EXCELLENT" to 0xFF34D399.toInt()
                acc <= 20f -> "● GPS GOOD"      to 0xFF38BDF8.toInt()
                acc <= 40f -> "● GPS FAIR"      to 0xFFF59E0B.toInt()
                else       -> "● GPS WEAK"      to 0xFFF87171.toInt()
            }
            tvGpsAccuracyBadge.text = label
            tvGpsAccuracyBadge.setTextColor(color)
            tvGpsCoordinates.text = "%.5f, %.5f  ±%.0fm".format(
                Locale.US, snap.latitude, snap.longitude, acc)
        } else {
            tvGpsAccuracyBadge.text = "● ACQUIRING"
            tvGpsAccuracyBadge.setTextColor(0xFF64748B.toInt())
            tvGpsCoordinates.text = "Waiting for satellite fix..."
        }

        // Map: update on meaningful movement (3m threshold ≈ 0.000027°)
        if (mapReady && hasGps) {
            val latDelta = abs(snap.latitude  - lastMapLat)
            val lonDelta = abs(snap.longitude - lastMapLon)
            val brgDelta = abs(snap.gpsBearingDeg - lastMapBearing)
            if (latDelta > 0.000027 || lonDelta > 0.000027 || brgDelta > 5f) {
                lastMapLat     = snap.latitude
                lastMapLon     = snap.longitude
                lastMapBearing = snap.gpsBearingDeg
                val js = "updatePosition(${snap.latitude}, ${snap.longitude}, " +
                         "${snap.gpsBearingDeg}, ${snap.gpsAccuracyM}, false);"
                mapWebView.evaluateJavascript(js, null)
            }
        }

        // Accel
        tvAccelValues.text = "X:%+.2f  Y:%+.2f  Z:%+.2f".format(
            Locale.US, snap.accelX, snap.accelY, snap.accelZ)

        // Orientation from quaternion (all Double)
        val q0 = snap.quatW.toDouble(); val q1 = snap.quatX.toDouble()
        val q2 = snap.quatY.toDouble(); val q3 = snap.quatZ.toDouble()
        val pitch = Math.toDegrees(Math.asin((2.0*(q0*q2-q3*q1)).coerceIn(-1.0,1.0)))
        val roll  = Math.toDegrees(Math.atan2(2.0*(q0*q1+q2*q3), 1.0-2.0*(q1*q1+q2*q2)))
        val yaw   = Math.toDegrees(Math.atan2(2.0*(q0*q3+q1*q2), 1.0-2.0*(q2*q2+q3*q3)))
        tvOrientValues.text = "P:%+.0f°  R:%+.0f°  Y:%+.0f°".format(Locale.US, pitch, roll, yaw)

        // Trip timer
        val sec = (snap.loggedCsvRows / 100L)
        tvTripDuration.text = "Duration: %02d:%02d".format(sec/60, sec%60)
    }

    // ─── EXPORT ───────────────────────────────────────────────────────────────

    private fun shareCsv() {
        val f = lastRecordedFile ?: return
        if (!f.exists()) return
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share Trip CSV"))
    }
}
