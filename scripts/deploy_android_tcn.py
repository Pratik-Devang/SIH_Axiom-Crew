"""Deploy one verified TCN artifact bundle into the Android application."""

from __future__ import annotations

import hashlib
import json
import shutil
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT / "artifacts"
ANDROID_ASSETS = ROOT / "android" / "app" / "src" / "main" / "assets"
FEATURES = ["accel_x", "accel_y", "accel_z", "gyro_x", "gyro_y", "gyro_z"]


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def checked_hash(path: Path, expected: str) -> None:
    actual = sha256_file(path)
    if actual != expected:
        raise RuntimeError(f"Hash mismatch for {path.name}: expected {expected}, got {actual}")


def values_in_feature_order(values: dict[str, float] | list[float]) -> list[float]:
    if isinstance(values, dict):
        return [float(values[name]) for name in FEATURES]
    if len(values) != len(FEATURES):
        raise ValueError(f"Expected {len(FEATURES)} normalization values, got {len(values)}")
    return [float(value) for value in values]


def main() -> None:
    manifest_path = ARTIFACTS / "artifact_manifest.json"
    metrics_path = ARTIFACTS / "speed_metrics.json"
    model_path = ARTIFACTS / "tcn.onnx"
    normalization_path = ARTIFACTS / "normalization.json"

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    run_id = manifest.get("run_id")
    if not run_id or run_id == "legacy-untracked":
        raise RuntimeError("Artifacts have no traceable run_id; retrain and evaluate before deployment")

    metrics = json.loads(metrics_path.read_text(encoding="utf-8"))
    if metrics.get("run_id") != run_id:
        raise RuntimeError("Metrics and ONNX artifact belong to different training runs")

    checked_hash(model_path, manifest["files"]["onnx"]["sha256"])
    checked_hash(normalization_path, manifest["files"]["normalization"]["sha256"])

    normalization = json.loads(normalization_path.read_text(encoding="utf-8"))
    if normalization.get("columns") != FEATURES:
        raise RuntimeError("Normalization feature order does not match the Android TCN contract")
    android_normalization = {
        "columns": FEATURES,
        "mean": values_in_feature_order(normalization["mean"]),
        "std": values_in_feature_order(normalization["std"]),
    }
    if not all(value > 0.0 for value in android_normalization["std"]):
        raise RuntimeError("Normalization standard deviations must be positive")

    ANDROID_ASSETS.mkdir(parents=True, exist_ok=True)
    shutil.copy2(model_path, ANDROID_ASSETS / "tcn.onnx")
    (ANDROID_ASSETS / "normalization.json").write_text(
        json.dumps(android_normalization, indent=2) + "\n", encoding="utf-8"
    )
    deployment = {
        "run_id": run_id,
        "source_onnx_sha256": manifest["files"]["onnx"]["sha256"],
        "deployed_onnx_sha256": sha256_file(ANDROID_ASSETS / "tcn.onnx"),
        "metrics": {"mae_mps": metrics["mae"], "rmse_mps": metrics["rmse"]},
    }
    (ANDROID_ASSETS / "tcn_deployment.json").write_text(
        json.dumps(deployment, indent=2) + "\n", encoding="utf-8"
    )
    print(f"Deployed Android TCN run {run_id}")


if __name__ == "__main__":
    main()
