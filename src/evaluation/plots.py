"""Trajectory, speed, uncertainty and error visualizations for PS-168."""

from __future__ import annotations

from pathlib import Path
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd


def generate_ps_benchmark_plot(
    replay_df: pd.DataFrame,
    metrics: dict,
    output_path: str | Path = "artifacts/ps168_benchmark_drift.png"
) -> Path:
    """Generate the official PS-168 evaluation plot:
    1. 2D Trajectory: Ground Truth vs GNSS Outage vs INS-only / Dead-Reckoning vs Fused ESKF.
    2. Horizontal Error vs Time with Outage Window and Drift Percentage.
    3. Position Uncertainty (+/- 2-sigma) over time.
    4. Vehicle Speed vs TCN ML Estimated Speed.
    """
    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    fig = plt.figure(figsize=(16, 11), facecolor="#0B0F1A")
    plt.rcParams["text.color"] = "#E2E8F0"
    plt.rcParams["axes.labelcolor"] = "#94A3B8"
    plt.rcParams["xtick.color"] = "#94A3B8"
    plt.rcParams["ytick.color"] = "#94A3B8"

    # Extract coordinates
    t = replay_df["time_since_start_s"].to_numpy()
    gt_east = replay_df["east"].to_numpy()
    gt_north = replay_df["north"].to_numpy()
    est_east = replay_df["estimated_east"].to_numpy()
    est_north = replay_df["estimated_north"].to_numpy()
    pos_err = replay_df["position_error_m"].to_numpy()
    unc = replay_df["position_uncertainty_m"].to_numpy()
    gnss_avail = replay_df["gnss_available"].to_numpy(bool)
    speed = replay_df["estimated_speed_mps"].to_numpy() * 3.6  # km/h

    # Compute outage specifics
    outage_indices = np.where(~gnss_avail)[0]
    outage_t_start = t[outage_indices[0]] if len(outage_indices) else 0
    outage_t_end = t[outage_indices[-1]] if len(outage_indices) else 0
    outage_dist_m = 0.0
    if len(outage_indices) > 1:
        dx = np.diff(gt_east[outage_indices])
        dy = np.diff(gt_north[outage_indices])
        outage_dist_m = float(np.sum(np.hypot(dx, dy)))
    
    max_err_outage = float(np.max(pos_err[outage_indices])) if len(outage_indices) else float(np.max(pos_err))
    drift_pct = (max_err_outage / max(outage_dist_m, 1.0)) * 100.0

    # 1. 2D Trajectory Map (Top Left)
    ax1 = plt.subplot2grid((2, 2), (0, 0), facecolor="#111827")
    ax1.plot(gt_east, gt_north, label="Ground Truth (GNSS)", color="#10B981", linewidth=3, alpha=0.8)
    if len(outage_indices):
        ax1.plot(gt_east[outage_indices], gt_north[outage_indices], label="Simulated GNSS Outage Window", color="#EF4444", linewidth=4, linestyle="--", alpha=0.9)
    ax1.plot(est_east, est_north, label="Percorsa ESKF (Fused)", color="#38BDF8", linewidth=2.5, linestyle="-")
    ax1.scatter(gt_east[0], gt_north[0], color="#F59E0B", s=90, zorder=5, label="Trip Origin")
    ax1.set_title("Vehicle 2D Trajectory in ENU Frame", fontsize=13, fontweight="bold", color="#38BDF8", pad=10)
    ax1.set_xlabel("East (meters)")
    ax1.set_ylabel("North (meters)")
    ax1.grid(True, linestyle=":", color="#334155", alpha=0.6)
    ax1.legend(loc="upper left", facecolor="#1E293B", edgecolor="#334155", fontsize=9)

    # 2. Position Error vs Time (Top Right)
    ax2 = plt.subplot2grid((2, 2), (0, 1), facecolor="#111827")
    ax2.plot(t, pos_err, color="#F87171", linewidth=2, label="Horizontal Position Error (m)")
    if len(outage_indices):
        ax2.axvspan(outage_t_start, outage_t_end, color="#EF4444", alpha=0.18, label=f"GNSS-Denied Window ({outage_t_end - outage_t_start:.1f}s)")
    ax2.set_title(f"Horizontal Drift Error: Max = {max_err_outage:.2f}m ({drift_pct:.2f}% of {outage_dist_m:.0f}m)", fontsize=13, fontweight="bold", color="#38BDF8", pad=10)
    ax2.set_xlabel("Time since trip start (seconds)")
    ax2.set_ylabel("Position Error (meters)")
    ax2.grid(True, linestyle=":", color="#334155", alpha=0.6)
    ax2.legend(loc="upper right", facecolor="#1E293B", edgecolor="#334155", fontsize=9)

    # 3. Position Uncertainty (+/- 2-sigma Envelope) (Bottom Left)
    ax3 = plt.subplot2grid((2, 2), (1, 0), facecolor="#111827")
    ax3.plot(t, unc, color="#38BDF8", linewidth=2, label="ESKF Uncertainty (1σ m)")
    ax3.fill_between(t, 0, unc * 2, color="#38BDF8", alpha=0.15, label="95% Confidence (2σ Envelope)")
    if len(outage_indices):
        ax3.axvspan(outage_t_start, outage_t_end, color="#EF4444", alpha=0.18, label="Outage: Covariance Growth")
    ax3.set_title("ESKF Position Covariance (Uncertainty Growth & Recovery)", fontsize=13, fontweight="bold", color="#38BDF8", pad=10)
    ax3.set_xlabel("Time since trip start (seconds)")
    ax3.set_ylabel("Uncertainty (meters)")
    ax3.grid(True, linestyle=":", color="#334155", alpha=0.6)
    ax3.legend(loc="upper left", facecolor="#1E293B", edgecolor="#334155", fontsize=9)

    # 4. Speed Profile & Benchmark Summary (Bottom Right)
    ax4 = plt.subplot2grid((2, 2), (1, 1), facecolor="#111827")
    ax4.plot(t, speed, color="#34D399", linewidth=2, label="Vehicle Speed (km/h)")
    if len(outage_indices):
        ax4.axvspan(outage_t_start, outage_t_end, color="#EF4444", alpha=0.18, label="TCN Dead-Reckoning Phase")
    ax4.set_title(f"Speed Profile | Overall Drift: {drift_pct:.2f}%", fontsize=13, fontweight="bold", color="#38BDF8", pad=10)
    ax4.set_xlabel("Time since trip start (seconds)")
    ax4.set_ylabel("Speed (km/h)")
    ax4.grid(True, linestyle=":", color="#334155", alpha=0.6)
    ax4.legend(loc="upper right", facecolor="#1E293B", edgecolor="#334155", fontsize=9)

    plt.suptitle("PERCORSA · SIH 2026 PS-168 RESILIENT NAVIGATION BENCHMARK\nControlled GNSS-Denied Outage Replay & Drift Evaluation", fontsize=15, fontweight="bold", color="#FFFFFF", y=0.98)
    plt.tight_layout(rect=[0, 0.02, 1, 0.95])
    plt.savefig(output_path, dpi=180, facecolor=fig.get_facecolor(), edgecolor="none")
    plt.close()
    return output_path
