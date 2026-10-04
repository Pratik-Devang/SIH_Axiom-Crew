# Results

This folder stores output artifacts produced by the evaluation pipeline and replay scripts.

```text
results/
├── metrics/        Machine-readable and presentation-ready metric tables (JSON/CSV).
├── plots/          Trajectory, speed, error, and uncertainty figures (PNG/PDF).
├── trajectories/   Timestamped replay outputs for each evaluated method.
└── README.md       This file
```

## Convention

Each result file or subfolder should record:

- **Configuration**: Which `config.yaml` or command-line flags were used.
- **Dataset trip**: Trip ID and split (train / validation / test).
- **Git commit**: Short commit hash of the codebase that produced the result.

This ensures results are reproducible and traceable back to a specific model checkpoint and evaluation run.

## Generating Results

Run the evaluation pipeline from the repository root:

```powershell
python evaluation/evaluate.py
```

Outputs are written to `evaluation/results/baseline_v1/` by default (configurable in `evaluation/config.yaml`).

For outage replay figures:

```powershell
python scripts/run_replay.py
```
