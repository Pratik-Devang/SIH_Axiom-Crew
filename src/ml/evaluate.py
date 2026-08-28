from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import torch
from torch.utils.data import DataLoader

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from src.ml.dataset import SpeedWindowDataset
from src.ml.preprocessing import apply_normalization, chronological_split, load_config, load_standardized_trip, save_json
from src.ml.tcn import build_model

ARTIFACTS = ROOT / "artifacts"


def metrics(pred: np.ndarray, target: np.ndarray) -> dict[str, float]:
    err = pred - target
    return {"mae": float(np.mean(np.abs(err))), "rmse": float(np.sqrt(np.mean(err**2)))}


def motion_states(frame, window_samples: int, stride: int) -> np.ndarray:
    end_indices = list(range(window_samples - 1, len(frame), stride))
    speed = frame["speed_mps"].to_numpy(float)
    yaw = frame["vehicle_yaw_rate_deg_s"].to_numpy(float)
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
    ckpt = torch.load(ARTIFACTS / "tcn_best.pt", map_location="cpu")
    config = ckpt["config"]
    trip, meta = load_standardized_trip(config)
    splits = chronological_split(trip, config["data"]["train_fraction"], config["data"]["validation_fraction"])
    test_raw = splits["test"]
    test_norm = apply_normalization(test_raw, ckpt["normalization"])
    test_ds = SpeedWindowDataset(test_norm, config["data"]["window_samples"], config["data"]["stride"])
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
    pred = np.concatenate(preds)
    target = np.concatenate(targets)
    log_var = np.concatenate(log_vars) if log_vars else None
    result = metrics(pred, target)
    result.update(
        {
            "sample_rate_hz": config["data"]["sample_rate_hz"],
            "window_seconds": config["data"]["window_seconds"],
            "window_samples": config["data"]["window_samples"],
            "input_channels": config["model"]["input_channels"],
            "target_source_column": meta["target_source_column"],
            "target_unit": "m/s",
            "test_samples": int(len(target)),
            "motion_state_method": "Derived from reference speed, acceleration, and vehicle yaw rate; not official labels.",
        }
    )

    states = motion_states(test_raw, config["data"]["window_samples"], config["data"]["stride"])
    per_state = {}
    for state in sorted(set(states)):
        mask = states == state
        if int(mask.sum()) >= 5:
            per_state[str(state)] = {"samples": int(mask.sum()), **metrics(pred[mask], target[mask])}
    result["by_motion_state"] = per_state
    if log_var is not None:
        # Clip to the same bounds used during training so reported stats are
        # physically meaningful (std in [0.14, 2.72] m/s range).
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
    print(result)
    print(f"saved: {ARTIFACTS / 'speed_metrics.json'}")


if __name__ == "__main__":
    main()
