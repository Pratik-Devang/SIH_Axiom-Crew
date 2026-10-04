"""Plots produced solely from evaluator prediction outputs."""

from __future__ import annotations

from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pandas as pd


def _finish(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    plt.tight_layout()
    plt.savefig(path, dpi=150)
    plt.close()


def generate_plots(predictions: pd.DataFrame, per_trip: pd.DataFrame, output_dir: Path) -> list[str]:
    """Write scientific plots from actual predictions and return their filenames."""
    output_dir.mkdir(parents=True, exist_ok=True)
    plotted = []
    plt.figure(figsize=(12, 5))
    for trip_id, group in predictions.groupby("trip_id", sort=False):
        plt.plot(group["timestamp"], group["ground_truth_velocity"], linewidth=0.8, label=f"{trip_id} ground truth")
        plt.plot(group["timestamp"], group["predicted_velocity"], linewidth=0.8, linestyle="--", label=f"{trip_id} prediction")
    plt.xlabel("time (s; trip-local)")
    plt.ylabel("forward velocity (m/s)")
    plt.title("TCN predicted velocity vs. ground truth")
    plt.legend(ncol=2, fontsize=8)
    _finish(output_dir / "velocity_vs_ground_truth.png")
    plotted.append("velocity_vs_ground_truth.png")

    plt.figure(figsize=(12, 4))
    for trip_id, group in predictions.groupby("trip_id", sort=False):
        plt.plot(group["timestamp"], group["error"], linewidth=0.8, label=str(trip_id))
    plt.axhline(0.0, color="black", linewidth=0.8)
    plt.xlabel("time (s; trip-local)")
    plt.ylabel("prediction error (m/s)")
    plt.title("Velocity error over time")
    plt.legend()
    _finish(output_dir / "velocity_error.png")
    plotted.append("velocity_error.png")

    plt.figure(figsize=(8, 4))
    plt.hist(predictions["error"], bins=80, color="#2563eb", edgecolor="white")
    plt.xlabel("prediction error (m/s)")
    plt.ylabel("window count")
    plt.title("Velocity prediction-error distribution")
    _finish(output_dir / "error_distribution.png")
    plotted.append("error_distribution.png")

    plt.figure(figsize=(8, 4))
    plt.bar(per_trip["trip_id"].astype(str), per_trip["mae_mps"], color="#059669")
    plt.xlabel("held-out trip")
    plt.ylabel("MAE (m/s)")
    plt.title("Per-trip velocity MAE")
    _finish(output_dir / "per_trip_mae.png")
    plotted.append("per_trip_mae.png")
    return plotted
