# Percorsa

AI and ML powered resilient navigation for GNSS-denied environments.

## Immediate prototype goal

Replay a recorded IO-VNBD vehicle trip, introduce a controlled GNSS outage,
continue estimating the trajectory with IMU-based dead reckoning, and compare
Percorsa against simpler baselines.

## Repository map

- `configs/`: Experiment and runtime configuration.
- `data/`: Local datasets, processed trips, manifests and split definitions.
- `src/`: Data, ML, navigation, constraints, evaluation and dashboard modules.
- `scripts/`: Small entry points for preparing data, training and running demos.
- `models/`: Model checkpoints and deployment exports.
- `results/`: Generated metrics, plots and trajectories.
- `tests/`: Unit and integration tests.
- `demo/`: Hackathon demo instructions and backup assets.
- `android/`: Android logger and edge-inference application.
- `docs/`: Architecture decisions, interfaces and experiment notes.

## Prototype flow

```text
IO-VNBD data
    -> standard trip format
    -> preprocessing and outage simulation
    -> speed estimate
    -> INS and EKF
    -> vehicle and road constraints
    -> evaluation and dashboard
```

## Data policy

Raw data, processed datasets, model binaries and generated results are ignored
by Git. Keep only manifests, configuration, documentation and small example
files in the repository.

