# Android Client

The Android client contains:

- Timestamped accelerometer, gyroscope, magnetometer and GNSS logging.
- Foreground sensor service and ring buffers.
- ONNX Runtime speed-model inference from a normalized 5-second, 10 Hz IMU window.
- Navigation state and confidence display.
- Offline map and route replay.

For the internal prototype, prioritize a working logger and UI shell. Keep the
validated Python replay pipeline as the primary demonstration.

---

## Build Environment & Compatibility Specifications

- **Android Studio Version**: Android Studio Ladybug (2024.2+) / Koala (2024.1+)
- **Android Gradle Plugin (AGP)**: `8.9.0`
- **Gradle Version**: `9.5.0` (Gradle Wrapper target `8.13`)
- **JDK Version**: Java 17 / Java 21 (Targeting Java 11 bytecode compatibility)
- **Minimum Android SDK (`minSdk`)**: `21` (Android 5.0 Lollipop)
- **Compile SDK (`compileSdk`)**: `34`
- **Target SDK (`targetSdk`)**: `37`
- **Application Namespace / ID**: `com.percorsa.sensorlogger`

---

## Hardware Requirements & Phone Sensors

- **Required Sensors**:
  - Accelerometer (`Sensor.TYPE_ACCELEROMETER`)
  - Gyroscope (`Sensor.TYPE_GYROSCOPE`)

---

## How to Build & Run

### Building the APK via CLI

From the `android/` directory:

```bash
# Run unit tests
./gradlew testDebugUnitTest

# Run lint checks
./gradlew lintDebug

# Assemble Debug APK
./gradlew assembleDebug
```

The compiled APK will be generated at:
`android/app/build/outputs/apk/debug/app-debug.apk`

### Running on a Physical Device

1. Enable **Developer Options** and **USB Debugging** on the target Android device.
2. Connect the phone via USB and authorize ADB debugging.
3. Install via ADB:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. Or open the `android/` project folder directly in Android Studio and click **Run 'app'**.

---

## Recorded Sensor Data Specification

The current implementation logs high-frequency motion data:
- **Raw Accelerometer**: 3-axis specific force ($X, Y, Z$) in $m/s^2$,
  including gravity, in the raw phone frame.
- **Raw Gyroscope**: 3-axis angular velocity ($X, Y, Z$) in $rad/s$ in the raw
  phone frame.
- **Canonical TCN stream**: a separate 10 Hz, 50-sample window; raw callback
  replay data is preserved in the `*_raw_imu.csv` sidecar.
- **Trip Logging**: `CsvRecorder` outputs timestamped CSV diagnostics; see
  `docs/android_logger_schema.md` for the exact schema.

---

## TCN speed inference

The app bundles `tcn.onnx` and its training normalization values. `SensorEngine`
creates a canonical `[1, 6, 50]` tensor after the initial five-second warm-up,
runs deterministic ONNX inference on a dedicated worker, and supplies valid
forward-speed estimates to the active ESKF measurement update while GNSS is
untrusted and vehicle motion has been established. A causal output filter
limits implausible speed jumps before they reach navigation. Developer Mode reports model loading, buffer readiness, raw
and filtered speed, inference latency, rate limiting, rejected predictions, and
errors.

## Known Incomplete Features

- Quantitative ESKF accuracy validation against an independent reference.
- Full quantitative ESKF accuracy validation against an independent reference
  remains required for accuracy claims. The app now uses
  `PercorsaEskfProvider` as the single active estimator;
  `SimplifiedInsProvider` remains available only as a reference/fallback
  implementation. `PercorsaEskfProviderStub` is retained as a deprecated
  historical placeholder and is not on the production path.
- Offline route replay display and production routing-provider hardening.

