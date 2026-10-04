# Evaluation

This folder contains the frozen TCN evaluation pipeline, configuration, and result outputs.

## Overview

The evaluator is intentionally separate from the training pipeline. It loads the **frozen deployed bundle** (`artifacts/tcn_best.pt`) and evaluates it against held-out test trips — it never retrain or mutate the model.

## Running the Evaluator

From the repository root:

```powershell
python evaluation/evaluate.py
```

## Folder Contents

```text
evaluation/
├── config.yaml       # Evaluation configuration (trip list, split, metrics)
├── evaluate.py       # Main evaluation script
├── metrics.py        # MAE, RMSE, and per-segment metric utilities
├── plots.py          # Trajectory and speed error plot generators
├── results/          # Output: per-trip metric JSONs and figures
└── README.md         # This file
```

## What It Evaluates

The evaluator uses the manifest's held-out whole trips (`Vta1a`, `Vta1b`, `Y1`) with:
- The checkpoint's exact 6-channel z-score normalization.
- Causal `[batch, 6, 50]` input windows.
- Output written to `evaluation/results/baseline_v1/`.

The root artifact (`artifacts/tcn_best.pt`) is the canonical model because all downstream tools — ONNX exporter, verifier, benchmark, and Android deployment script — select it first. Its legacy model-info count predates the current named split manifest; the evaluator uses the current manifest in that legacy case (recorded in `metadata.json`).

The evaluator halts on any mismatch in model, normalization, feature order, input shape, or split rather than silently substituting an artifact.

## What Is Intentionally Omitted

State-wise (motion class) results are not produced. The processed IO-VNBD trip files have no validated state-label column, and the evaluator does not derive one from unlabeled data.
