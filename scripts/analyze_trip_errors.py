"""Evaluate speed estimation error on each test trip individually to find outliers."""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd
import torch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from src.ml.dataset import SpeedWindowDataset
from src.ml.preprocessing import apply_normalization, load_split_trips
from src.ml.tcn import build_model


def main() -> None:
    ckpt_path = ROOT / "artifacts" / "tcn_best.pt"
    if not ckpt_path.exists():
        ckpt_path = ROOT / "artifacts" / "v2" / "tcn_best.pt"

    ckpt = torch.load(ckpt_path, map_location="cpu")
    config = ckpt["config"]

    # Load split trips
    split_trips = load_split_trips(config)
    test_raw_trips = split_trips["test"]

    # Load TCN Model
    model = build_model(config)
    model.load_state_dict(ckpt["model_state_dict"])
    model.eval()

    print("\n" + "=" * 70)
    print("INDIVIDUAL TEST TRIP EVALUATION")
    print("=" * 70)

    total_samples = 0
    errors = []

    for idx, trip_df in enumerate(test_raw_trips):
        trip_id = trip_df["trip_id"].iloc[0] if "trip_id" in trip_df.columns else f"trip_{idx}"

        # Apply normalization to this single trip
        norm_trip = apply_normalization(trip_df, ckpt["normalization"])
        trip_ds = SpeedWindowDataset([norm_trip], config["data"]["window_samples"], config["data"]["stride"])

        if len(trip_ds) == 0:
            print(f"Trip {trip_id}: Empty dataset after windowing")
            continue

        loader = torch.utils.data.DataLoader(trip_ds, batch_size=256, shuffle=False)

        preds = []
        targets = []
        with torch.no_grad():
            for x, y in loader:
                out = model(x)
                if config["model"].get("predict_uncertainty", False):
                    out = out[:, 0]
                preds.append(out.cpu().numpy())
                targets.append(y.cpu().numpy())

        pred_mps = np.concatenate(preds)
        target_mps = np.concatenate(targets)

        mae_mps = np.mean(np.abs(pred_mps - target_mps))
        mae_kmh = mae_mps * 3.6

        print(f"Trip {trip_id:<8} | Samples: {len(pred_mps):<5} | MAE: {mae_mps:.3f} m/s ({mae_kmh:.2f} km/h)")

        total_samples += len(pred_mps)
        errors.append(mae_mps)

    print("=" * 70)


if __name__ == "__main__":
    main()
