# Percorsa

AI and ML powered resilient navigation for GNSS-denied environments.

## Immediate prototype goal

Replay a recorded IO-VNBD vehicle trip, introduce a controlled GNSS outage,
continue estimating the trajectory with IMU-based dead reckoning, and compare
Percorsa against simpler baselines.

## Repository map

- `configs/`: Experiment and runtime configuration.
- `data/`: Local datasets, processed trips, manifests and split definitions.
- `src/`: Source packages organised by function:
  - `src/ml/`: Role 2 TCN speed model (authoritative implementation).
  - `src/data/`: Dataset schema and IO-VNBD/PPC/UrbanNav adapters.
  - `src/preprocessing/`: Coordinate transforms, outage simulation, sync utilities.
  - `src/navigation/`: INS, planar EKF and ESKF.
  - `src/constraints/`: Vehicle, stop-detection, GNSS-trust and map constraints.
  - `src/evaluation/`: Metrics, baselines and plots.
  - `src/dashboard/`: Interactive replay dashboard.
- `scripts/`: Small entry points for data inspection, benchmarking and demos.
- `artifacts/`: Trained model weights, ONNX export, normalization stats and metrics.
- `results/`: Generated plots and trajectories.
- `tests/`: Unit and integration tests.
- `demo/`: Hackathon demo instructions and backup assets.
- `android/`: Android logger and edge-inference application.
- `docs/`: Architecture decisions, interfaces and experiment notes.

## Prototype flow

```text
IO-VNBD data
    -> standard trip format
    -> preprocessing and outage simulation
    -> speed estimate (src/ml/)
    -> INS and EKF
    -> vehicle and road constraints
    -> evaluation and dashboard
```

## Run the integrated prototype

From the repository root, activate the `percorsa` environment and run:

```powershell
python -m pytest -q
python scripts/run_dashboard.py
```

The dashboard loads processed CSV trips from
`data/processed/io_vnbd/trips/`. If that directory is empty, it starts with a
clearly labelled synthetic route so the interface can still be tested. With a
real trip, the committed ONNX TCN estimates speed from 20 IMU samples, the
planar EKF continues the trajectory through the selected GNSS outage, and the
evaluation layer compares it with a last-fix baseline.

The dashboard also accepts Android CSV uploads directly. Current IMU-only
exports open in sensor mode. Uploads containing latitude and longitude unlock
the controlled-outage journey replay automatically.

For a command-line replay:

```powershell
python scripts/run_replay.py data/processed/io_vnbd/trips/A1.csv --start 60 --duration 30
```

### Secure Android ingestion API

Generate a temporary API key and start the local server:

```powershell
$env:PERCORSA_API_KEY = python -c "import secrets; print(secrets.token_urlsafe(32))"
python scripts/run_api.py
```

The Android integration contract and security notes are documented in
`docs/android_ingestion.md`. Never commit the real API key.

## Data policy

Raw data and processed datasets are gitignored; keep only manifests,
configuration, documentation and small example files in the repository.
Trained model weights and ONNX exports live in `artifacts/` and are
committed so the team can reproduce evaluation without retraining.

Store normalization parameters and model metadata next to every exported model.

## Role 2 — TCN speed prototype

Use the project Conda environment on Windows:

```powershell
# Inspect the data and verify the pipeline
python scripts\inspect_data.py

# Train (only needed if retraining from scratch)
python -m src.ml.train

# Evaluate the saved checkpoint
python -m src.ml.evaluate

# Export to ONNX for edge deployment
python -m src.ml.export_onnx

# Verify PyTorch vs ONNX numerical parity
python -m src.ml.verify_onnx

# CPU latency benchmark
python scripts\benchmark.py

# Run test suite
python -m pytest tests/ -v
```

The TCN input is a causal 2 second IMU window sampled at 10 Hz:
`[batch, 6, 20]`. The six channels are accelerometer X/Y/Z in `m/s^2` and
gyroscope yaw/pitch/roll in `rad/s`. The final model output is
`[speed_mean_mps, log_variance]`.

Artifacts are written to `artifacts/`: `tcn_best.pt`, `tcn.onnx`,
`normalization.json`, `speed_metrics.json`, `model_info.json`,
`onnx_parity.json`, and `latency.json`.

### V1 known limitations

- Uncertainty head is implemented but currently unreliable (log_var saturates).
- Turning error is high (MAE ≈ 5.2 m/s) — IMU alone cannot resolve heading.
- Only one IO-VNBD trip has been used for training.
