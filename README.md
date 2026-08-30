# 🛰️ Percorsa: AI/ML-Powered Resilient Navigation for GNSS-Denied Environments

[![Python 3.10+](https://img.shields.io/badge/python-3.10+-blue.svg)](https://www.python.org/downloads/)
[![ONNX Runtime](https://img.shields.io/badge/inference-ONNX%20Runtime-green.svg)](https://onnxruntime.ai/)
[![Smart India Hackathon](https://img.shields.io/badge/SIH-PS168-orange.svg)](https://www.sih.gov.in/)
[![License](https://img.shields.io/badge/license-MIT-purple.svg)](LICENSE)

**Percorsa** is an end-to-end resilient dead reckoning and sensor-fusion navigation system engineered to sustain continuous, highly accurate vehicle positioning during severe GNSS (GPS) outages—such as urban canyons, tunnels, underpasses, parking structures, and electronic jamming/spoofing environments.

---

## 📌 Executive Summary

Modern navigation systems fail abruptly when satellite signals are obstructed or spoofed. Traditional Inertial Navigation Systems (INS) using smartphone IMUs experience exponential drift (hundreds of meters within seconds) due to double-integration of noisy accelerometer and gyroscope data.

**Percorsa solves this through a multi-tier AI and kinematic sensor-fusion architecture:**
1. **AI Speed Estimation (TCN)**: A lightweight, causal Temporal Convolutional Network predicts forward vehicle velocity directly from 6-axis smartphone IMU vibrations (suspension pitch dive, road oscillation signatures, yaw rates), replacing error-prone double-integration.
2. **Error-State Extended Kalman Filter (ESKF/EKF)**: Fuses high-frequency IMU kinematics with AI speed predictions and periodic GNSS fixes.
3. **Physical Vehicle Constraints**: Enforces Non-Holonomic Constraints (NHC: zero lateral/vertical velocity) and Zero Velocity Updates (ZUPT) to lock drift during stationary halts.
4. **Map-Aided Localization**: Aligns estimated trajectories with road network graphs to eliminate residual heading errors.
5. **Edge Deployment Ready**: Optimized for ultra-low latency (<2.3 ms per inference) running directly on Android smartphones via ONNX Runtime Mobile, with full offline capability and secure cloud sync.

---

## 🚀 Key Benchmark Results

Evaluated on realistic driving datasets (IO-VNBD benchmark across 27 validated multi-vehicle trips):

| Metric | Last-Fix Baseline | Percorsa (AI + EKF) | Improvement |
| :--- | :--- | :--- | :--- |
| **Max Drift (45s GNSS Outage, 514.5m driven)** | `427.95 m` | **`1.64 m`** | **99.6% error reduction** |
| **Drift Percentage of Distance Traveled** | `83.18%` | **`0.32%`** | **~260x higher precision** |
| **RMSE Position Error** | `254.51 m` | **`0.64 m`** | **Sub-meter accuracy** |
| **Mean Absolute Error (MAE)** | `226.84 m` | **`0.54 m`** | **Sub-meter accuracy** |
| **Inference Latency (CPU / Mobile)** | N/A | **`~2.25 ms`** | **Real-time 10 Hz edge execution** |
| **Exported Model Size (ONNX)** | N/A | **`376 KB`** | **Ultra-lightweight edge footprint** |

---

## 🏗️ System Architecture & Workflow

```text
+---------------------------------------------------------------------------------------+
|                                    DATA INGESTION                                     |
|  6-Axis Smartphone IMU (Accel X/Y/Z, Gyro X/Y/Z) + GNSS Fixes (10-100 Hz Async Stream)|
+------------------------------------------+--------------------------------------------+
                                           |
                                           v
+---------------------------------------------------------------------------------------+
|                            PREPROCESSING & SYNCHRONIZATION                            |
|       - 10 Hz Resampling & Linear Interpolation   - Strict Monotonicity & NaN Gates   |
|       - Causal Sliding Window: [Batch, 6 Channels, 50 Samples] (5.0s Temporal History)|
+------------------------------------------+--------------------------------------------+
                                           |
                    +----------------------+----------------------+
                    |                                             |
                    v                                             v
+---------------------------------------+     +-----------------------------------------+
|        AI SPEED ESTIMATOR (TCN)       |     |        KINEMATIC INS INTEGRATOR         |
|  - Dilated Causal 1D Convolutions     |     |  - High-frequency Strapdown IMU         |
|  - Receptive Field: 3.1s (RF=31)      |     |  - Orientation & Attitude Tracking      |
|  - Params: 348,417 (~376 KB ONNX)     |     +--------------------+--------------------+
|  - Output: Forward Speed v_x (m/s)    |                          |
+-------------------+-------------------+                          |
                    |                                              |
                    +----------------------+-----------------------+
                                           |
                                           v
+---------------------------------------------------------------------------------------+
|                         SENSOR FUSION & ESTIMATION FILTER                             |
|                           (Error-State Kalman Filter)                                 |
|                                                                                       |
|   * Prediction: Strapdown Inertial Kinematics                                         |
|   * Measurement Updates:                                                              |
|       - GNSS Availability: Full Position/Velocity Correction                          |
|       - GNSS Outage: TCN AI Speed Updates + Non-Holonomic Constraints (v_y=0, v_z=0)  |
|       - Stop Detection: Zero Velocity Updates (ZUPT) + Zero Angular Rate Updates      |
+------------------------------------------+--------------------------------------------+
                                           |
                                           v
+---------------------------------------------------------------------------------------+
|                               MAP-MATCHING CONSTRAINTS                                |
|          Snaps filtered coordinates to OSM road network topology & heading            |
+------------------------------------------+--------------------------------------------+
                                           |
                                           v
+---------------------------------------------------------------------------------------+
|                             USER INTERFACES & DEPLOYMENT                              |
|   1. Streamlit Live Replay & Benchmark Dashboard                                      |
|   2. FastAPI REST & Sensor Ingestion Server                                           |
|   3. Native Android Logger & Real-time Edge ONNX App                                  |
+---------------------------------------------------------------------------------------+
```

---

## 📂 Repository Map

```text
SIH_Axiom-Crew/
├── android/              # Android native logger and edge-inference application (Kotlin)
├── artifacts/            # Trained weights (tcn_best.pt), ONNX (tcn.onnx), metrics & benchmarks
├── configs/              # Experiment configs, hyperparameter specs, and split definitions
├── data/                 # Local trip datasets, manifests, and processed IO-VNBD data (gitignored)
├── demo/                 # Hackathon presentation guidelines, assets, and demo scenarios
├── docs/                 # Architectural Decision Records (ADRs), API specs, and interface contracts
├── models/               # Checkpoint storage and model versioning
├── results/              # Evaluation plots, trajectory comparisons, and drift benchmarks
├── scripts/              # Command-line tools for training, evaluation, replays, and API hosting
│   ├── benchmark.py                  # CPU/GPU latency & throughput profiler
│   ├── generate_benchmark_deliverable.py # Generates official drift metrics & comparison charts
│   ├── run_api.py                    # Launches FastAPI ingestion server
│   ├── run_dashboard.py              # Launches Streamlit interactive dashboard
│   ├── run_replay.py                 # CLI trajectory replay under controlled GNSS outages
│   └── train_speed_model.py          # End-to-end TCN training pipeline
├── src/                  # Core modular source code
│   ├── api/              # FastAPI endpoints for real-time sensor uploads
│   ├── constraints/      # Non-holonomic vehicle constraints, ZUPT, and map snapping
│   ├── dashboard/        # Streamlit web application for visual replays & metrics
│   ├── data/             # Dataset loaders and IO-VNBD / Android adapters
│   ├── evaluation/       # Benchmark metric calculators (MAE, RMSE, Drift %, Outage scorecards)
│   ├── maps/             # Road network graph parsing & map-matching integration
│   ├── ml/               # TCN architecture, training engine, ONNX exporter & verifier
│   ├── navigation/       # INS kinematics, Planar EKF, and Error-State Kalman Filter (ESKF)
│   └── preprocessing/    # 10 Hz interpolation, timestamp sanitization, and coordinate transforms
├── tests/                # Automated pytest suite (unit and integration tests)
├── README.md             # Project documentation & summary
├── requirements.txt      # Python dependencies
└── walkthrough_details.md# Complete step-by-step ML & integration walkthrough guide
```

---

## ⚡ Quickstart Guide

### 1. Environment Setup

Clone the repository and install dependencies:

```powershell
# Create or activate your virtual environment
python -m venv .venv
.venv\Scripts\activate

# Install dependencies
pip install -r requirements.txt

# Run automated tests to verify installation
python -m pytest tests/ -q
```

### 2. Launch Interactive Replay Dashboard

Run the Streamlit dashboard to inspect trip datasets, trigger synthetic/real GNSS outages, and visualize live trajectory reconstruction vs. baselines:

```powershell
python scripts/run_dashboard.py
```
> *Access the dashboard in your browser at `http://localhost:8501`.*

### 3. Run Command-Line Trajectory Replay

Evaluate navigation performance under a simulated GNSS outage on any processed trip:

```powershell
python scripts/run_replay.py data/processed/io_vnbd/trips/A1.csv --start 50 --duration 45
```

### 4. Start the Secure Android Ingestion API

Start the local FastAPI service for live smartphone sensor streaming:

```powershell
$env:PERCORSA_API_KEY = python -c "import secrets; print(secrets.token_urlsafe(32))"
python scripts/run_api.py
```

---

## 🧠 ML Model Pipeline (Role 2 — TCN Speed Regressor)

The speed model uses a **Temporal Convolutional Network (TCN)** trained with causal dilated 1D convolutions:

* **Input Tensor**: `[Batch, 6, 50]` (6 channels: `[accel_x, accel_y, accel_z, gyro_x, gyro_y, gyro_z]`, 50 samples @ 10 Hz = 5.0 seconds history).
* **Architecture**: 4 residual blocks with channel dimensions `[128, 128, 128, 128]`, kernel size 3, dilations `[1, 2, 4, 8]`.
* **Receptive Field**: 3.1 seconds ($1 + (3 - 1) \times (1 + 2 + 4 + 8) = 31\text{ samples}$).
* **Output**: Scalar forward velocity `speed_mps` ($m/s$).

```powershell
# Train the TCN model
python -m src.ml.train

# Evaluate on test set (5 unseen OOD trips)
python -m src.ml.evaluate

# Export to optimized ONNX format for mobile deployment
python -m src.ml.export_onnx

# Verify strict numerical parity between PyTorch & ONNX
python -m src.ml.verify_onnx

# Run inference latency benchmark
python scripts/benchmark.py
```

---

## 📱 Mobile App (Edge Inference)

The `android/` directory contains the native Kotlin application supporting:
* **Background Sensor Logging**: Continuous 10 Hz sampling of accelerometer, gyroscope, and GNSS positions.
* **On-Device Edge Inference**: Direct evaluation of `tcn.onnx` using **ONNX Runtime Android** with negligible battery impact.
* **Cloud Sync**: Secure streaming over HTTPS with bearer token authentication to the Percorsa API backend.
* **Pre-built APKs**: Ready-to-install debug and release builds available in the root and artifacts directory (`Percorsa-Navigation-Final.apk`).

---

## 👥 Team & Acknowledgments

Developed by **Axiom-Crew** for **Smart India Hackathon (SIH) Problem Statement 168**: *AI and ML powered resilient navigation for GNSS-denied environments*.
