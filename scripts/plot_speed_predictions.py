"""Plot TCN Model Speed Predictions vs Actual Reference Speed on Test Data."""

from __future__ import annotations

import sys
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import torch
from torch.utils.data import DataLoader

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from src.ml.dataset import SpeedWindowDataset
from src.ml.preprocessing import apply_normalization, load_split_trips
from src.ml.tcn import build_model


def main() -> None:
    ckpt_path = ROOT / "artifacts" / "tcn_best.pt"
    if not ckpt_path.exists():
        ckpt_path = ROOT / "artifacts" / "v2" / "tcn_best.pt"

    print(f"Loading model checkpoint from: {ckpt_path}")
    ckpt = torch.load(ckpt_path, map_location="cpu")
    config = ckpt["config"]

    # Load test split
    split_trips = load_split_trips(config)
    test_raw_trips = split_trips["test"]

    # Apply normalization
    test_norm_trips = [apply_normalization(f, ckpt["normalization"]) for f in test_raw_trips]
    test_ds = SpeedWindowDataset(test_norm_trips, config["data"]["window_samples"], config["data"]["stride"])
    loader = DataLoader(test_ds, batch_size=256, shuffle=False)

    # Load TCN Model
    model = build_model(config)
    model.load_state_dict(ckpt["model_state_dict"])
    model.eval()

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

    # Convert to km/h for easy reading
    pred_kmh = pred_mps * 3.6
    target_kmh = target_mps * 3.6

    time_s = np.arange(len(pred_mps)) * 0.1  # 10 Hz = 0.1s step

    # Save visual plot
    plots_dir = ROOT / "results" / "plots"
    plots_dir.mkdir(parents=True, exist_ok=True)
    plot_file = plots_dir / "v2_speed_prediction_vs_actual.png"

    plt.figure(figsize=(14, 6))
    plt.plot(time_s[:500], target_kmh[:500], label="Actual Vehicle Speed (km/h)", color="blue", linewidth=1.5)
    plt.plot(time_s[:500], pred_kmh[:500], label="TCN Model Predicted Speed (km/h)", color="red", linestyle="--", alpha=0.85, linewidth=1.5)

    plt.title("Percorsa Role 2 — TCN Speed Prediction vs Reference Vehicle Speed (First 50s Test Run)", fontsize=13, fontweight="bold")
    plt.xlabel("Time (seconds)", fontsize=11)
    plt.ylabel("Speed (km/h)", fontsize=11)
    plt.grid(True, linestyle=":", alpha=0.6)
    plt.legend(fontsize=11)
    plt.tight_layout()
    plt.savefig(plot_file, dpi=150)
    plt.close()

    print("\n" + "=" * 70)
    print("VISUAL VERIFICATION SUMMARY")
    print("=" * 70)
    print(f"Total Test Samples Evaluated : {len(pred_mps):,}")
    print(f"Time Evaluated               : {len(pred_mps)*0.1:.1f} seconds")
    print(f"Mean Absolute Error (MAE)    : {np.mean(np.abs(pred_mps - target_mps)):.3f} m/s ({np.mean(np.abs(pred_kmh - target_kmh)):.2f} km/h)")
    print(f"Visual Plot Saved To         : {plot_file}")
    print("=" * 70)
    print("\nSAMPLE PREDICTIONS TABLE (First 10 Windows):")
    print(f"{'Sample #':<10} | {'Actual (km/h)':<15} | {'Predicted (km/h)':<18} | {'Error (km/h)':<12}")
    print("-" * 65)
    for idx in range(10):
        err = abs(pred_kmh[idx] - target_kmh[idx])
        print(f"{idx+1:<10} | {target_kmh[idx]:<15.2f} | {pred_kmh[idx]:<18.2f} | {err:<12.2f}")
    print("-" * 65)


if __name__ == "__main__":
    main()
