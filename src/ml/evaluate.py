from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd
import torch
from torch.utils.data import DataLoader

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from src.ml.dataset import SpeedWindowDataset
from src.ml.preprocessing import (
    apply_normalization,
    load_config,
    load_split_trips,
    load_standardized_trip,
    save_json,
)
from src.ml.tcn import build_model

ARTIFACTS = ROOT / "artifacts"
ARTIFACTS_V2 = ROOT / "artifacts" / "v2"


def metrics(pred: np.ndarray, target: np.ndarray) -> dict[str, float]:
    err = pred - target
    return {"mae": float(np.mean(np.abs(err))), "rmse": float(np.sqrt(np.mean(err**2)))}


def motion_states_for_trip(frame: pd.DataFrame, window_samples: int, stride: int) -> np.ndarray:
    end_indices = list(range(window_samples - 1, len(frame), stride))
    speed = frame["speed_mps"].to_numpy(float)
    if "vehicle_yaw_rate_deg_s" in frame.columns:
        yaw = frame["vehicle_yaw_rate_deg_s"].to_numpy(float)
    elif "gyro_z" in frame.columns:
        yaw = np.degrees(frame["gyro_z"].to_numpy(float))
    elif "gyro_yaw" in frame.columns:
        yaw = np.degrees(frame["gyro_yaw"].to_numpy(float))
    else:
        yaw = np.zeros_like(speed)

    accel = np.gradient(speed, 0.1)
    states = []
    for i in end_indices:
        if speed[i] < 0.5:
            states.append("stationary")
        elif abs(yaw[i]) > 5.0:
            states.append("turning")
        elif accel[i] > 0.5:
            states.append("acceleration")
        elif accel[i] < -0.5:
            states.append("braking")
        else:
            states.append("cruising")
    return np.asarray(states)


def main() -> None:
    ckpt_path = ARTIFACTS / "tcn_best.pt"
    if not ckpt_path.exists():
        ckpt_path = ARTIFACTS_V2 / "tcn_best.pt"

    ckpt = torch.load(ckpt_path, map_location="cpu")
    config = ckpt["config"]
    
    split_trips = load_split_trips(config)
    test_raw_trips = split_trips["test"]
    _, meta = load_standardized_trip(config)

    test_norm_trips = [apply_normalization(frame, ckpt["normalization"]) for frame in test_raw_trips]
    test_ds = SpeedWindowDataset(test_norm_trips, config["data"]["window_samples"], config["data"]["stride"])
    loader = DataLoader(test_ds, batch_size=config["training"]["batch_size"], shuffle=False)

    model = build_model(config)
    model.load_state_dict(ckpt["model_state_dict"])
    model.eval()

    preds, targets, log_vars = [], [], []
    with torch.no_grad():
        for x, y in loader:
            out = model(x)
            if config["model"].get("predict_uncertainty", False):
                log_vars.append(out[:, 1].cpu().numpy())
                out = out[:, 0]
            preds.append(out.cpu().numpy())
            targets.append(y.cpu().numpy())

    pred = np.concatenate(preds) if preds else np.array([], dtype=np.float32)
    target = np.concatenate(targets) if targets else np.array([], dtype=np.float32)
    log_var = np.concatenate(log_vars) if log_vars else None

    result = metrics(pred, target) if len(pred) > 0 else {"mae": 0.0, "rmse": 0.0}
    result.update(
        {
            "sample_rate_hz": config["data"]["sample_rate_hz"],
            "window_seconds": config["data"]["window_seconds"],
            "window_samples": config["data"]["window_samples"],
            "input_channels": config["model"]["input_channels"],
            "target_source_column": meta["target_source_column"],
            "target_unit": "m/s",
            "test_samples": int(len(target)),
            "test_trips": len(test_raw_trips),
            "motion_state_method": "Derived from reference speed, acceleration, and vehicle yaw rate; not official labels.",
        }
    )

    all_states = []
    for frame in test_raw_trips:
        st = motion_states_for_trip(frame, config["data"]["window_samples"], config["data"]["stride"])
        all_states.append(st)
    states = np.concatenate(all_states) if all_states else np.array([])

    per_state = {}
    if len(states) == len(pred):
        for state in sorted(set(states)):
            mask = states == state
            if int(mask.sum()) >= 5:
                per_state[str(state)] = {"samples": int(mask.sum()), **metrics(pred[mask], target[mask])}
    result["by_motion_state"] = per_state

    if log_var is not None and len(log_var) > 0:
        clipped = np.clip(log_var, -4.0, 2.0)
        nll = 0.5 * (clipped + (target - pred) ** 2 / np.exp(clipped))
        result["uncertainty"] = {
            "representation": "model output column 0 is speed_mean_mps; column 1 is log_variance",
            "log_var_bounds_train": [-4.0, 2.0],
            "mean_predicted_std_mps": float(np.mean(np.sqrt(np.exp(clipped)))),
            "raw_mean_predicted_std_mps": float(np.mean(np.sqrt(np.exp(log_var)))),
            "gaussian_nll": float(np.mean(nll)),
            "calibrated": False,
        }

    save_json(result, ARTIFACTS / "speed_metrics.json")
    save_json(result, ARTIFACTS_V2 / "speed_metrics.json")
    print(result)
    print(f"saved: {ARTIFACTS / 'speed_metrics.json'}")


if __name__ == "__main__":
    main()
