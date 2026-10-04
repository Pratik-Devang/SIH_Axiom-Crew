# Models

This folder stores local training checkpoints and exported edge-deployment models.

```text
models/
├── checkpoints/    Local training run checkpoints (*.pt files).
├── exports/        ONNX and other edge-deployment model files.
└── README.md       This file
```

## Convention

Every exported model must be stored alongside its companion metadata files:

- `normalization.json` — per-channel mean and standard deviation used during training (required for correct inference).
- `model_info.json` — architecture name, parameter count, training dataset, and Git commit hash.

> [!IMPORTANT]
> The **canonical production model** lives in `artifacts/`, not here. The files in `artifacts/tcn_best.pt`, `artifacts/tcn.onnx`, and `artifacts/normalization.json` are the artifacts consumed by the Android app and the evaluation pipeline.
> This `models/` folder is for work-in-progress checkpoints and experimental exports during active development.

## Deploying a Model to Android

After exporting and verifying a new ONNX model, sync it to the Android asset bundle:

```powershell
python scripts/deploy_android_tcn.py
```

This copies `artifacts/tcn.onnx` and `artifacts/normalization.json` into `android/app/src/main/assets/` and triggers the Gradle `verifyTcnAssets` integrity check on next build.
