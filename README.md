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

## Data policy

Raw data and processed datasets are gitignored; keep only manifests,
configuration, documentation and small example files in the repository.
Trained model weights and ONNX exports live in `artifacts/` and are
committed so the team can reproduce evaluation without retraining.

Store normalization parameters and model metadata next to every exported model.

## Role 2 — TCN speed prototype

Use the project virtual environment on Windows:

```powershell
# Inspect the data and verify the pipeline
.\.venv\Scripts\python.exe scripts\inspect_data.py

# Train (only needed if retraining from scratch)
python -m src.ml.train

# Evaluate the saved checkpoint
python -m src.ml.evaluate

# Export to ONNX for edge deployment
python -m src.ml.export_onnx

# Verify PyTorch vs ONNX numerical parity
python -m src.ml.verify_onnx

# CPU latency benchmark
.\.venv\Scripts\python.exe scripts\benchmark.py

# Run test suite
.\.venv\Scripts\python.exe -m pytest tests/ -v
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
