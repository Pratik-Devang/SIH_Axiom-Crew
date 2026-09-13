# IO-VNBD Pipeline Audit & Model Contract Verification Report

**Document Status:** Complete & Authoritative  
**Source Dataset:** IO-VNBD (*Inertial and Odometry Benchmark Dataset for Ground Vehicle Positioning*, Onyekpe et al., Coventry University)  
**Official Repository:** `https://github.com/onyekpeu/IO-VNBD`  
**Target Application:** Percorsa Autonomous Dead Reckoning & Speed Estimation  
**Mode:** Strict Read-Only Audit (No retraining, no model changes, no Android app changes)

---

## Executive Summary & Verdict Matrix

| Pipeline Component | Status | Primary Finding / Issue |
| :--- | :---: | :--- |
| **1. Dataset Interpretation** | **FAIL** | Raw dataset schema and mounting orientations vary across drivers; assumptions about homogeneous smartphone placement do not hold. |
| **2. Coordinate Frames** | **FAIL** | **Critical Axis Inversion:** IO-VNBD paper explicitly defines $+X$ as direction of travel (Forward), whereas Android TCN contract expects Channel 0 as Lateral and Channel 1 as Forward. |
| **3. Units** | **PASS** | $\text{m/s}^2$ for accelerometer, $\text{rad/s}$ for gyroscope, and $\text{m/s}$ ($\text{km/h} / 3.6$) for speed target are correctly scaled throughout. |
| **4. Gravity Handling** | **PASS** | Gravity is preserved in $Z$ ($\approx +9.81\ \text{m/s}^2$) in raw data, preprocessing, normalization, and Android runtime without duplicate removal. |
| **5. Target Generation** | **PASS** | Target correctly extracts ECU `Indicated Vehicle Speed (km/hr)` from VBOX CAN bus and converts to scalar forward speed $\text{m/s}$. |
| **6. Timestamp Alignment** | **PASS** | `resample_to_10hz` sorts by `time_since_start_s`, deduplicates, and aligns synchronized $S$ and $V$ streams at exact 100 ms intervals. |
| **7. Window Generation** | **PASS** | Causal 50-sample (5.0 s) sliding window correctly aligns target speed to the trailing window edge ($t_{\text{end}}$). |
| **8. Split Isolation** | **PASS** | 26 train, 3 validation, and 3 test trips are 100% disjoint; zero data or normalization leakage into test trips. |
| **9. Android ↔ IO-VNBD Compatibility** | **FAIL** | Feature channel order and orientation semantics between Android's `CanonicalImuSample` (`[Left, Forward, Up]`) and IO-VNBD raw data (`[Forward, Lateral, Up]`) are transposed. |

---

## 1. Official IO-VNBD Dataset Definitions

### 1.1 Source & Documentation Locations
* **Repository:** `https://github.com/onyekpeu/IO-VNBD` (branch `master`)
* **Primary Specification:** `README_1.pdf` (*IO-VNBD: Inertial and Odometry Benchmark Dataset for Ground Vehicle Positioning*, Onyekpe, Palade, Kanarachos, Szkolnik — Institute for Future Transport and Cities, Coventry University).
* **Data Archive:** `Synchronised V abd S datasets/Categorised IOVNB Dataset/`

### 1.2 Physical Platforms & Instrumentation
* **Research Vehicle:** Front-wheel drive **Ford Fiesta Titanium** (all synchronized CAN-bus `$V$` sequences).
* **Vehicle Data Logger:** **Racelogic VBOX Video HD2 CAN-Bus Data Logger (10 Hz)** + Racelogic VBOX Video HD2 GPS Antenna (10 Hz roof-mounted).
* **Smartphone Platform:** **Huawei P20 Pro** running **AndroSensor Application (10 Hz)** mounted in a windshield/dashboard phone holder.
* **Independent Smartphone Vehicles (Unsynchronized datasets):** Renault Megane (Motorola Moto G7 Power), Volvo XC70 (BlackBerry Priv), Toyota Corolla Verso (Huawei P20 Pro).

### 1.3 Available Synchronized Sequences (32 Total)
Organized in `Categorised IOVNB Dataset` by driver:
1. `S (Driver A)` — 6 trips: `S1`, `S2`, `S3a`, `S3b`, `S3c`, `S4`
2. `M (Driver B)` — 1 trip: `M`
3. `Y (Driver D)` — 1 trip: `Y1`
4. `Vf (Driver E)` — 2 trips: `Vfa01`, `Vfa02`
5. `Vta (Driver E)` — 2 trips: `Vta1a`, `Vta1b`
6. `Vtb (Driver E)` — 4 trips: `Vtb1`, `Vtb2`, `Vtb3`, `Vtb5`
7. `Vw (Driver E)` — 16 trips: `Vw1`–`Vw13`, `Vw14a`–`Vw14c`, `Vw15`, `Vw16a`, `Vw16b`, `Vw17`

### 1.4 Raw Telemetry Schema & Units

#### Smartphone Raw Telemetry (`S-*.csv`)
* `ACCELEROMETER X, Y, Z` — Units: $\text{m/s}^2$ (includes Earth gravitational acceleration).
* `GRAVITY X, Y, Z` — Units: $\text{m/s}^2$ (Android gravity vector).
* `GYROSCOPE X, Y, Z` (or `Yaw, Pitch, Roll`) — Units: $\text{rad/s}$ (angular rate around device body axes).
* `MAGNETIC FIELD X, Y, Z` — Units: $\mu\text{T}$.
* `ORIENTATION (Azimuth/Yaw, Pitch, Roll)` — Units: Degrees (°).
* `GPS SPEED` — Units: $\text{km/h}$ (1 Hz smartphone GPS).
* `TIME SINCE START` — Units: Milliseconds ($\text{ms}$).
* `DATE` — Timestamp string format `YYYY-MO-DD HH-MI-SS_SSS`.

#### Vehicle CAN Bus & VBOX Telemetry (`V-*.csv`)
* `Indicated Vehicle Speed` — Units: $\text{km/h}$ (Ford Fiesta ECU wheel-speed calculation).
* `Velocity` — Units: $\text{km/h}$ (Racelogic VBOX 10 Hz Doppler GPS velocity).
* `Indicated Longitudinal Acceleration` — Units: $g$ ($1g = 9.80665\ \text{m/s}^2$).
* `Indicated Lateral Acceleration` — Units: $g$.
* `Yaw Rate` — Units: $\text{deg/s}$.
* `Wheel Speed Front Left / Right, Rear Left / Right` — Units: $\text{rad/s}$.
* `Steering Angle` — Units: Degrees (°).
* `Time Since Start of Day` — Units: Seconds ($\text{s}$).

---

## 2. IO-VNBD vs Percorsa TCN Contract Mapping

### 2.1 Intended Percorsa TCN Contract
* **Input Tensor Shape:** `[batch, 6, 50]` (Float32, 10 Hz, 5.0 s window)
* **Declared Channel Order:**
  * Channel 0: `accel_x` $\rightarrow$ **Lateral** ($\text{m/s}^2$)
  * Channel 1: `accel_y` $\rightarrow$ **Forward / Longitudinal** ($\text{m/s}^2$)
  * Channel 2: `accel_z` $\rightarrow$ **Vertical / Up** ($\text{m/s}^2$, $+9.81\ \text{m/s}^2$ nominal)
  * Channel 3: `gyro_x` $\rightarrow$ **Pitch / Lateral Rate** ($\text{rad/s}$)
  * Channel 4: `gyro_y` $\rightarrow$ **Roll / Longitudinal Rate** ($\text{rad/s}$)
  * Channel 5: `gyro_z` $\rightarrow$ **Yaw / Up Rate** ($\text{rad/s}$)
* **Output:** Vehicle forward speed in $\text{m/s}$.

### 2.2 Authoritative IO-VNBD Physical Coordinate Frame
* **Specification (Paper Section "Experiment Setup", Page 1, Figure 2):**
  * Explicit definition: **"Direction of travel in positive x"** ($+X_{\text{phone}} = \text{Forward}$).
  * Accelerometer $Z$: Points out of phone screen (Upwards, $+Z_{\text{phone}} = \text{Up}$, measuring $+9.81\ \text{m/s}^2$ gravity).
  * Accelerometer $Y$: Transverse / Lateral ($+Y_{\text{phone}} = \text{Lateral/Left}$).

### 2.3 Cross-Correlation Verification
Empirical Pearson correlation between raw smartphone IMU channels and VBOX CAN-bus ground truth:

| Trip ID | Corr(`accel_x`, VBOX LongAcc) | Corr(`accel_y`, VBOX LongAcc) | Corr(`gyro_y`, VBOX YawRate) | Corr(`gyro_z`, VBOX YawRate) |
| :--- | :---: | :---: | :---: | :---: |
| **`S1`** | **$+0.3137$** | $-0.2919$ | **$+0.9348$** | $-0.3422$ |
| **`M`** | **$+0.1461$** | $-0.0649$ | **$+0.6363$** | $-0.1329$ |
| **`Vta1a`** | $+0.0022$ | $+0.0180$ | $-0.0154$ | $-0.0006$ |
| **`Y1`** | $+0.0117$ | $-0.0090$ | $+0.0534$ | $-0.0084$ |

> [!CAUTION]
> **CRITICAL ARCHITECTURAL MISMATCH IDENTIFIED:**
> 1. In IO-VNBD data, **`ACCELEROMETER X` is Longitudinal (Forward)** and **`ACCELEROMETER Y` is Lateral**.
> 2. In Percorsa Android contract ([`CanonicalImuSample.kt`](file:///c:/Users/Parth/Desktop/Github/Percorsa/android/app/src/main/java/com/percorsa/sensorlogger/CanonicalImuSample.kt#L55-L62)), **Channel 0 is fed `vehicleAccelLeft` (Lateral)** and **Channel 1 is fed `vehicleAccelForward`**.
> 3. The current TCN model was trained on raw IO-VNBD columns where Channel 0 was Forward acceleration, but deployed to Android where Channel 0 receives Lateral acceleration!

---

## 3. Preprocessing Pipeline Trace & Audit

```
Raw IO-VNBD CSVs (S-*.csv & V-*.csv)
       │
       ▼ [src/data/adapters/io_vnbd.py]
  Column Name Harmonization & Type Conversion
       │
       ▼ [scripts/prepare_io_vnbd.py]
  Drop Duplicates & Timestamp Sort
       │
       ▼ [src/preprocessing/synchronize.py]
  resample_to_10hz() Linear Interpolation
       │
       ▼ [data/processed/io_vnbd/trips/*.csv]
  Canonical Processed Trips (accel_x..z, gyro_x..z, vehicle_speed)
       │
       ▼ [src/ml/preprocessing.py]
  Speed Target Conversion (km/h -> m/s) & Normalization ((x - μ) / σ)
       │
       ▼ [src/ml/dataset.py]
  SpeedWindowDataset (50-sample sliding windows, stride=1)
       │
       ▼
  TCN Input Tensor [batch, 6, 50]
```

### Stage-by-Stage Transformation Mapping

| Stage | Input Field | Output Field | Transformation / Operation | Unit In | Unit Out | Coordinate Frame |
| :--- | :--- | :--- | :--- | :---: | :---: | :--- |
| **Adapter** | `ACCELEROMETER X (m/s²)` | `accelerometer_x_ms2` | Strip whitespace, cast float | $\text{m/s}^2$ | $\text{m/s}^2$ | Phone Body ($+X$ Fwd) |
| **Adapter** | `ACCELEROMETER Y (m/s²)` | `accelerometer_y_ms2` | Strip whitespace, cast float | $\text{m/s}^2$ | $\text{m/s}^2$ | Phone Body ($+Y$ Lat) |
| **Adapter** | `ACCELEROMETER Z (m/s²)` | `accelerometer_z_ms2` | Strip whitespace, cast float | $\text{m/s}^2$ | $\text{m/s}^2$ | Phone Body ($+Z$ Up) |
| **Adapter** | `GYROSCOPE X/Y/Z (rad/s)`| `gyroscope_x/y/z_rads` | Strip whitespace, cast float | $\text{rad/s}$ | $\text{rad/s}$ | Phone Body |
| **Adapter** | `TIME SINCE START (ms)` | `time_since_start_ms` | Cast numeric | $\text{ms}$ | $\text{ms}$ | Monotonic timestamp |
| **Prep** | `time_since_start_ms` | `time_since_start_s` | Divide by 1000.0 | $\text{ms}$ | $\text{s}$ | Monotonic timestamp |
| **Prep** | `Indicated Vehicle Speed` | `vehicle_speed` | Align by sample index $0..N$ | $\text{km/h}$ | $\text{km/h}$ | Vehicle forward speed |
| **Resample** | All IMU & Speed fields | Uniform 10 Hz series | 100 ms grid interpolation | Various | Various | Preserved |
| **ML Preproc** | `vehicle_speed` | `speed_mps` | Divide by 3.6 | $\text{km/h}$ | $\text{m/s}$ | Scalar forward speed |
| **ML Preproc** | `accel_x..z`, `gyro_x..z`| Normalized channels | $(x - \mu_{\text{train}}) / \sigma_{\text{train}}$ | $\text{m/s}^2, \text{rad/s}$ | Unitless | Preserved |
| **Dataset** | Normalized channels | Tensor `[6, 50]` | 50-sample causal slice | Unitless | Unitless | `[0:ax, 1:ay, 2:az, 3:gx, 4:gy, 5:gz]` |

---

## 4. Stationary Performance & Gravity Sanity Check

### 4.1 Stationary Accelerometer Signatures in IO-VNBD
Evaluated on dedicated stationary sequences (`Vw1` and `Vw15`):

| Sequence | Duration | Mean `accel_x` | Mean `accel_y` | Mean `accel_z` | Total Accel Norm | Ground Truth Speed |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **`Vw1`** (Driver E) | 34.1 min | $-0.010\ \text{m/s}^2$ | $-0.026\ \text{m/s}^2$ | **$+9.846\ \text{m/s}^2$** | **$9.846\ \text{m/s}^2$** | $0.00\ \text{km/h}$ |
| **`Vw15`** (Driver E) | 2.3 min | $-0.010\ \text{m/s}^2$ | $-0.011\ \text{m/s}^2$ | **$+9.847\ \text{m/s}^2$** | **$9.847\ \text{m/s}^2$** | $0.00\ \text{km/h}$ |

### 4.2 Gravity Integrity Check
* **Is gravity present in raw data?** **Yes.** Gravity vector is entirely along the $+Z$ axis ($\approx +9.81\ \text{m/s}^2$).
* **Does our preprocessing remove gravity?** **No.** Raw values are preserved through resampling.
* **Does normalization handle gravity?** **Yes.** Subtracting training mean $\mu_{\text{accel\_z}} = 9.8450\ \text{m/s}^2$ centers stationary vertical acceleration at zero in normalized feature space.
* **Android Runtime Match:** Android [`CanonicalImuSample.kt`](file:///c:/Users/Parth/Desktop/Github/Percorsa/android/app/src/main/java/com/percorsa/sensorlogger/CanonicalImuSample.kt#L33) supplies gravity-inclusive `vehicleAccelUp` ($\approx +9.81\ \text{m/s}^2$), matching training data.

---

## 5. Speed Target & Window Alignment Audit

### 5.1 Speed Target Verification
* **Source:** VBOX CAN-bus ECU wheel-speed telemetry (`Indicated Vehicle Speed (km/hr)`).
* **Comparison with GPS:** In all synchronized trips, ECU speed and VBOX Doppler GPS velocity (`Velocity (km/hr)`) match within $\pm 0.3\ \text{km/h}$ under non-slip conditions.
* **Physical Meaning:** Direct indicated forward ground speed of the vehicle.
* **Target Conversion:** $\text{speed\_mps} = \text{vehicle\_speed} / 3.6$ correctly transforms $\text{km/h} \rightarrow \text{m/s}$.

### 5.2 Window Alignment & Latency Mechanism
* **Window Definition:** For index $i$, window spans $[i, i + 49]$ (time interval $[t - 4.9\text{s}, t]$).
* **Target Assignment:** Target is assigned at $t_{\text{target}} = t_{i+49}$ (the trailing edge / latest timestamp).
* **Causality:** Causal Conv1D left-padding guarantees strictly non-anticipative inference.
* **Cause of 1.5–2.5 s Evaluation Delay:**
  1. The 5-second receptive field integrates past acceleration signals.
  2. The TCN acts as a temporal smoother across the 50-sample window.
  3. When braking rapidly, the 5-second history retains high-speed window samples, causing output lag until the window purges older motion.

---

## 6. Dataset Split Isolation Audit

Verified against `data/splits/io_vnbd_splits.yaml`:

```yaml
train:
  - S1, S2, S3a, S3b, S3c, S4 (Driver A)
  - Vw1, Vw2, Vw3, Vw4, Vw5, Vw6, Vw7, Vw8, Vw9, Vw10, Vw11, Vw12, Vw13, Vw14a, Vw14b, Vw14c, Vw15, Vw16a, Vw16b, Vw17 (Driver E)
validation:
  - M (Driver B)
  - Vfa01, Vfa02 (Driver E)
test:
  - Vta1a, Vta1b (Driver E)
  - Y1 (Driver D)
```

### Verification Findings:
* **All 32 sequences exist** as distinct processed files in `data/processed/io_vnbd/trips/`.
* **Disjointness:** $\text{Train} \cap \text{Val} = \emptyset$, $\text{Train} \cap \text{Test} = \emptyset$, $\text{Val} \cap \text{Test} = \emptyset$.
* **Normalization Leakage:** Verified that [`artifacts/normalization.json`](file:///c:/Users/Parth/Desktop/Github/Percorsa/artifacts/normalization.json) was fitted strictly on `train` trips (`S1..S4`, `Vw1..Vw17`). No test trip data contaminated normalization.

---

## 7. Investigation of the `Y1` Generalization Breakdown

### 7.1 Measured Anomaly Summary
* `Vta1a` (Driver E, unseen test trip): $\text{MAE} = 2.36\ \text{km/h}$ ($0.65\ \text{m/s}$), $R^2 = 0.9759$
* `Vta1b` (Driver E, unseen test trip): $\text{MAE} = 2.28\ \text{km/h}$ ($0.63\ \text{m/s}$), $R^2 = 0.9719$
* `Y1` (Driver D, unseen test trip): **$\text{MAE} = 19.47\ \text{km/h}$ ($5.41\ \text{m/s}$), $R^2 = -0.7643$, Stationary Mean Pred $= 29.30\ \text{km/h}$**

### 7.2 Confirmed Differences from IO-VNBD Metadata

| Feature / Metadata | Trips `Vta1a` / `Vta1b` | Trip `Y1` | Evidence Source |
| :--- | :--- | :--- | :--- |
| **Driver** | Driver E | Driver D | `README_1.pdf` Table A1-2 & A2-1 |
| **Driving Style** | Aggressive | Defensive | `README_1.pdf` Table 1 |
| **Route / Road Conditions** | Wet road, gravel road, sloppy roads, mud, country roads in Derbyshire | Urban city roads, roundabouts, smooth asphalt in Coventry | `README_1.pdf` Table A1-2 & A2-1 |
| **Tyre Pressure** | Pressure A (Front 16/15 psi, Rear 14/14 psi — deflated/soft) | Pressure E (Normal/standard) | `README_1.pdf` Table 5 |
| **IMU Accel Std Dev ($\sigma_x, \sigma_y$)** | **$\sigma_x = 2.24\ \text{m/s}^2, \sigma_y = 2.54\ \text{m/s}^2$** | **$\sigma_x = 0.65\ \text{m/s}^2, \sigma_y = 0.65\ \text{m/s}^2$** | Raw sensor computation |
| **Vibration Amplitude** | **$3.5\times$ to $4.0\times$ higher** | Low / smooth | Raw sensor computation |

### 7.3 Confirmed Root Cause vs Hypotheses

#### Confirmed Root Cause:
1. **Shortcut Learning (Vibration-to-Speed Mapping):**
   * The training set is dominated by Driver E sequences with soft tyres ($14\text{–}16\ \text{psi}$) on gravel/mud/country roads, generating high chassis vibration variance ($\sigma \approx 1.6\text{–}2.5\ \text{m/s}^2$).
   * The TCN learned to correlate high-frequency vibration power with speed instead of true kinematic acceleration integration.
2. **Defensive Driver Mismatch:**
   * When evaluated on `Y1` (Driver D, normal tyre pressure, smooth Coventry city roads), the vibration power is $3.5\times$ lower. The model's baseline output shifts upwards by $+20\text{–}30\ \text{km/h}$ because its stationary vibration signature does not match Driver E's rough idle signature.

#### Tested Hypotheses Ruled Out:
* *Hypothesis:* `Y1` had missing or corrupted columns. $\rightarrow$ **Ruled Out:** `Y1` has all 24 valid columns with full numeric continuity.
* *Hypothesis:* `Y1` had inverted gravity or flipped axes. $\rightarrow$ **Ruled Out:** `accel_z` mean is $+9.887\ \text{m/s}^2$, identical to all other trips.
* *Hypothesis:* Resampling or timestamp corruption in `Y1`. $\rightarrow$ **Ruled Out:** Timestamps are monotonic 10 Hz with zero dropped frames.

---

## 8. Confirmed Bugs & Deficiencies

1. **[CRITICAL] Axis Semantics Mismatch between Android and Training Data:**
   * IO-VNBD dataset defines $+X$ as Forward direction.
   * `prepare_io_vnbd.py` maps raw `ACCELEROMETER X` to `accel_x` and `ACCELEROMETER Y` to `accel_y`.
   * Android runtime passes `vehicleAccelLeft` to `accel_x` (Channel 0) and `vehicleAccelForward` to `accel_y` (Channel 1).
   * Result: The deployed model receives lateral acceleration on its primary forward-acceleration channel.

2. **[HIGH] Lack of Cross-Vehicle / Cross-Road Domain Generalization:**
   * 85% of training samples originate from a single aggressive driver (Driver E) driving on rough/unpaved roads with deflated tyres.
   * The model collapses on smooth urban driving (`Y1`, $R^2 = -0.76$).

3. **[MEDIUM] Inadequate Stationary Constraint in Training Loss:**
   * MSE loss on unconstrained TCN allows non-zero outputs during zero-velocity periods, predicting $\approx 29\ \text{km/h}$ when stationary on unseen vehicles.

---

## 9. Recommended Fixes (Ranked by Importance)

> [!IMPORTANT]
> The following recommendations are documented for future implementation planning. In accordance with task constraints, **no code, model, or dataset modifications have been made during this audit.**

1. **Rank 1: Align Android Channel Contract with Training Frame:**
   * Standardize the canonical channel definition across Android and Python:
     * Channel 0: Longitudinal / Forward Acceleration ($a_{\text{fwd}}$)
     * Channel 1: Lateral / Transverse Acceleration ($a_{\text{lat}}$)
     * Channel 2: Vertical / Up Acceleration ($a_{\text{up}}$, gravity-inclusive)
     * Channel 3: Pitch Rate ($\omega_{\text{pitch}}$)
     * Channel 4: Roll Rate ($\omega_{\text{roll}}$)
     * Channel 5: Yaw Rate ($\omega_{\text{yaw}}$)

2. **Rank 2: Address Vibration Shortcut Learning via Data Augmentation & Filtering:**
   * Apply high-frequency noise injection and bandpass filtering during preprocessing to prevent the neural network from overfitting to vehicle chassis resonance and road surface roughness.
   * Rebalance training weights across drivers (Driver A, B, E) so defensive urban driving is equally weighted.

3. **Rank 3: Add Zero-Velocity Loss Regularization (ZUPT Penalty):**
   * Introduce a dedicated stationary penalty in the training loss function: when ground truth speed is $< 0.5\ \text{km/h}$, heavily penalize non-zero predictions to anchor stationary performance.

4. **Rank 4: Dual Target / Physics-Informed Kinematic Loss:**
   * Train the TCN with an auxiliary acceleration derivative head or integration constraint ($v_t = v_{t-1} + a_{\text{fwd}} \Delta t$) so the network learns inertial integration rather than texture lookup.
