"""Main entry point for reproducible, evaluation-only Percorsa TCN assessment."""

from __future__ import annotations

import argparse
import hashlib
import json
import platform
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd
import torch
import yaml
from torch.utils.data import DataLoader

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from evaluation.metrics import regression_metrics
from evaluation.plots import generate_plots
from src.ml.dataset import SpeedWindowDataset
from src.ml.preprocessing import apply_normalization, read_csv_flexible, resolve_split_paths, standardize_trip_dataframe
from src.ml.tcn import build_model


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _git_sha() -> str | None:
    try:
        return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def _load_yaml(path: Path) -> dict:
    with path.open(encoding="utf-8") as handle:
        return yaml.safe_load(handle)


def _normalization_as_mapping(stats: dict) -> dict:
    """Normalize list- and mapping-encoded statistics to one comparable form."""
    columns = stats.get("columns")
    if not isinstance(columns, list):
        raise RuntimeError("EVALUATION BLOCKER: normalization statistics do not declare an ordered column list.")
    return {
        "columns": columns,
        "mean": {column: stats["mean"][column] if isinstance(stats["mean"], dict) else stats["mean"][index] for index, column in enumerate(columns)},
        "std": {column: stats["std"][column] if isinstance(stats["std"], dict) else stats["std"][index] for index, column in enumerate(columns)},
    }


def _validate_contract(cfg: dict, checkpoint: dict, info: dict, normalization: dict, configured_test: list[str]) -> dict:
    model_cfg, data_cfg = checkpoint.get("config", {}).get("model"), checkpoint.get("config", {}).get("data")
    if not model_cfg or not data_cfg:
        raise RuntimeError("EVALUATION BLOCKER: checkpoint lacks its model/data configuration.")
    checks = {
        "window_size": data_cfg.get("window_samples"),
        "stride": data_cfg.get("stride"),
        "sampling_rate_hz": data_cfg.get("sample_rate_hz"),
    }
    for name, checkpoint_value in checks.items():
        if cfg.get(name) != checkpoint_value:
            raise RuntimeError(f"EVALUATION BLOCKER: config {name}={cfg.get(name)!r} conflicts with checkpoint {checkpoint_value!r}.")
    if cfg["input_features"] != normalization.get("columns"):
        raise RuntimeError("EVALUATION BLOCKER: configured feature order conflicts with checkpoint normalization.")
    expected_shape = info.get("input_shape")
    if expected_shape and expected_shape[-2:] != [len(cfg["input_features"]), cfg["window_size"]]:
        raise RuntimeError("EVALUATION BLOCKER: model-info input shape conflicts with evaluator contract.")
    if model_cfg.get("input_channels") != len(cfg["input_features"]):
        raise RuntimeError("EVALUATION BLOCKER: checkpoint channel count conflicts with feature list.")
    checkpoint_test = checkpoint.get("split_trips", {}).get("test")
    info_test = info.get("split_trips", {}).get("test")
    # The deployed root bundle predates tracked trip-name metadata. The existing
    # src.ml.evaluate explicitly restores data selection from this manifest in
    # that case; do the same, but reject any explicit conflicting trip list.
    if (checkpoint_test is not None and checkpoint_test != configured_test) or (info_test is not None and info_test != configured_test):
        raise RuntimeError("EVALUATION BLOCKER: manifest test trips conflict with explicit checkpoint/model metadata.")
    embedded_normalization = _normalization_as_mapping(checkpoint.get("normalization", {}))
    file_normalization = _normalization_as_mapping(normalization)
    if embedded_normalization != file_normalization:
        raise RuntimeError("EVALUATION BLOCKER: normalization file does not exactly match frozen checkpoint normalization.")
    return embedded_normalization


def _window_rows(raw_trips: list[pd.DataFrame], prediction: np.ndarray, window: int, stride: int) -> pd.DataFrame:
    records: list[pd.DataFrame] = []
    offset = 0
    for frame in raw_trips:
        starts = np.arange(0, len(frame) - window + 1, stride)
        n = len(starts)
        ends = starts + window - 1
        piece = pd.DataFrame({
            "trip_id": frame["trip_id"].iloc[0],
            "timestamp": pd.to_numeric(frame["time_since_start_s"], errors="raise").to_numpy()[ends],
            "ground_truth_velocity": frame["speed_mps"].to_numpy(dtype=np.float64)[ends],
            "predicted_velocity": prediction[offset:offset + n],
        })
        records.append(piece)
        offset += n
    if offset != len(prediction):
        raise RuntimeError("EVALUATION BLOCKER: generated window rows do not align with inference output.")
    result = pd.concat(records, ignore_index=True)
    result["error"] = result["predicted_velocity"] - result["ground_truth_velocity"]
    result["absolute_error"] = result["error"].abs()
    result["squared_error"] = result["error"] ** 2
    return result


def _summary(model_path: str, cfg: dict, overall: dict, per_trip: pd.DataFrame) -> str:
    lines = [
        "PERCORSA TCN — PRELIMINARY MODEL EVALUATION", "",
        f"Model: {model_path}", f"Dataset: {cfg['dataset']}", f"Test split: {cfg['test_split']} (complete held-out trips)",
        f"Samples: {int(per_trip['sample_count'].sum())}", f"Trips: {len(per_trip)}", "", "Overall metrics:",
        f"MAE: {overall['mae_mps']:.6f} m/s ({overall['mae_kmh']:.6f} km/h)",
        f"RMSE: {overall['rmse_mps']:.6f} m/s ({overall['rmse_kmh']:.6f} km/h)", f"R²: {overall['r2']:.6f}",
        f"Bias: {overall['bias_mps']:.6f} m/s", f"P95 absolute error: {overall['p95_absolute_error_mps']:.6f} m/s",
        f"Maximum absolute error: {overall['max_absolute_error_mps']:.6f} m/s", "", "Per-trip results:",
    ]
    for _, item in per_trip.iterrows():
        lines.append(f"{item.trip_id}: n={int(item.sample_count)}, MAE={item.mae_mps:.6f} m/s, RMSE={item.rmse_mps:.6f} m/s, R²={item.r2:.6f}")
    lines.extend(["", "State-wise evaluation was not performed because the current evaluation dataset does not provide validated state labels.", "", "This evaluation measures the existing ML model's velocity-estimation performance. It does NOT by itself establish complete GNSS-denied navigation performance or the SIH positional-drift requirement."])
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate the frozen Percorsa TCN on its held-out IO-VNBD trips.")
    parser.add_argument("--config", type=Path, default=ROOT / "evaluation" / "config.yaml")
    args = parser.parse_args()
    cfg = _load_yaml(args.config)
    paths = {name: ROOT / cfg[name] for name in ("model_path", "model_info_path", "normalization_path", "dataset_path", "split_manifest", "output_directory")}
    for name in ("model_path", "model_info_path", "normalization_path", "dataset_path", "split_manifest"):
        if not paths[name].exists():
            raise FileNotFoundError(f"EVALUATION BLOCKER: configured {name} does not exist: {paths[name]}")

    torch.manual_seed(int(cfg["seed"]))
    np.random.seed(int(cfg["seed"]))
    checkpoint = torch.load(paths["model_path"], map_location="cpu", weights_only=False)
    info, normalization = json.loads(paths["model_info_path"].read_text(encoding="utf-8")), json.loads(paths["normalization_path"].read_text(encoding="utf-8"))
    split_paths = resolve_split_paths(sorted(paths["dataset_path"].glob("*.csv")), paths["split_manifest"])
    test_paths = split_paths[cfg["test_split"]]
    configured_test = [path.stem for path in test_paths]
    normalization = _validate_contract(cfg, checkpoint, info, normalization, configured_test)
    raw_trips = [standardize_trip_dataframe(read_csv_flexible(path)) for path in test_paths]
    normalized_trips = [apply_normalization(frame, normalization) for frame in raw_trips]
    dataset = SpeedWindowDataset(normalized_trips, cfg["window_size"], cfg["stride"], cfg["input_features"])
    if not len(dataset):
        raise RuntimeError("EVALUATION BLOCKER: held-out test split produced no valid windows.")
    model = build_model(checkpoint["config"])
    model.load_state_dict(checkpoint["model_state_dict"], strict=True)
    model.eval()
    outputs = []
    with torch.inference_mode():
        for x, _ in DataLoader(dataset, batch_size=int(cfg["inference_batch_size"]), shuffle=False):
            outputs.append(model(x).detach().cpu().numpy().reshape(-1))
    prediction = np.concatenate(outputs).astype(np.float64)
    predictions = _window_rows(raw_trips, prediction, cfg["window_size"], cfg["stride"])
    overall = regression_metrics(predictions["predicted_velocity"].to_numpy(), predictions["ground_truth_velocity"].to_numpy())
    trip_rows = []
    for trip_id, group in predictions.groupby("trip_id", sort=False):
        trip_rows.append({"trip_id": trip_id, "sample_count": len(group), **regression_metrics(group.predicted_velocity.to_numpy(), group.ground_truth_velocity.to_numpy())})
    per_trip = pd.DataFrame(trip_rows)
    output = paths["output_directory"]
    plot_names = generate_plots(predictions, per_trip, output / "plots")
    predictions.to_csv(output / "predictions.csv", index=False)
    per_trip.to_csv(output / "per_trip_metrics.csv", index=False)
    metrics = {"model": info["model"], "model_version": info.get("version"), "dataset": cfg["dataset"], "split": "held_out_test", "sample_count": len(predictions), "trip_count": len(per_trip), "overall": overall, "state_wise_evaluation": {"performed": False, "reason": "No validated state labels are present in the processed evaluation dataset."}}
    (output / "metrics.json").write_text(json.dumps(metrics, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    metadata = {"model_filename": cfg["model_path"], "model_sha256": _sha256(paths["model_path"]), "model_version": info.get("version"), "model_format": "PyTorch checkpoint", "model_selection": "Existing evaluator, ONNX exporter/verifier, benchmark, and Android deployment scripts select artifacts/tcn_best.pt first.", "legacy_provenance_observation": "This deployed checkpoint has no explicit trip-name split metadata. artifacts/model_info.json reports a legacy test-trip count, so the repository's current canonical manifest is used exactly as src.ml.evaluate specifies.", "input_shape": info["input_shape"], "input_features": cfg["input_features"], "sampling_rate_hz": cfg["sampling_rate_hz"], "window_size": cfg["window_size"], "stride": cfg["stride"], "target_variable": "speed_mps", "target_units": cfg["target_units"], "target_transformation": cfg["target_transformation"], "preprocessing": "Existing standardize_trip_dataframe plus frozen checkpoint z-score normalization; no evaluator-specific transformation.", "normalization_file": cfg["normalization_path"], "normalization_sha256": _sha256(paths["normalization_path"]), "dataset": cfg["dataset"], "test_split": cfg["test_split"], "test_trips": configured_test, "number_of_trips": len(per_trip), "number_of_samples": len(predictions), "git_commit_sha": _git_sha(), "python_version": sys.version, "package_versions": {"torch": torch.__version__, "numpy": np.__version__, "pandas": pd.__version__, "pyyaml": yaml.__version__}, "evaluation_timestamp_utc": datetime.now(timezone.utc).isoformat(), "plots": plot_names, "state_wise_evaluation": "Not performed: no validated state labels."}
    (output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    (output / "summary.txt").write_text(_summary(cfg["model_path"], cfg, overall, per_trip), encoding="utf-8")
    print(f"Evaluation complete: {len(predictions)} windows across {len(per_trip)} held-out trips. Results: {output}")


if __name__ == "__main__":
    main()
