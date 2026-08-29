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
- **Minimum Android SDK (`minSdk`)**: `24` (Android 7.0 Nougat)
- **Compile SDK (`compileSdk`)**: `37`
- **Target SDK (`targetSdk`)**: `37`
- **Application Namespace / ID**: `com.percorsa.navigation`

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
- **Raw Accelerometer**: 3-axis linear acceleration ($X, Y, Z$) in $m/s^2$.
- **Raw Gyroscope**: 3-axis angular velocity ($X, Y, Z$) in $rad/s$.
- **Resampling & Buffering**: In-memory ring buffer with $100\text{ Hz}$ resampling (`SensorResampler`), timing jitter metrics (`SensorTimingTracker`), and rate tracking (`SensorRateTracker`).
- **Trip Logging**: `TripLogger` outputs structured timestamped CSV/JSON logs.

---

## TCN speed inference

The app bundles `tcn.onnx` and its training normalization values. `SensorEngine`
creates a canonical `[1, 6, 50]` tensor after the initial five-second warm-up,
runs deterministic ONNX inference at 10 Hz, and injects valid forward-speed
estimates into the fallback dead-reckoning provider while GNSS is untrusted.
Developer Mode reports buffer readiness, inference age, predicted speed, and
model-loading errors.

## Known Incomplete Features

- GNSS / GPS location stream integration.
- 3-axis Magnetometer logging.
- Full ESKF fusion of the TCN speed measurement; the current app applies it to
  `SimplifiedInsProvider` while the ESKF provider remains a stub.
- Offline map rendering and live route replay display.

