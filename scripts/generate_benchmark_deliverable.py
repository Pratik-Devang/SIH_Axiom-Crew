"""Generate PS-168 position plot, drift percentage, and end-to-end evaluation."""

from __future__ import annotations

import json
import sys
from pathlib import Path
import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from src.evaluation.replay import run_outage_replay
from src.evaluation.plots import generate_ps_benchmark_plot


def create_realistic_test_trip(duration_s=180.0, dt=0.1) -> pd.DataFrame:
    """Generate a realistic vehicle trajectory traveling through Chembur, Mumbai (180s, 10 Hz)."""
    n_samples = int(duration_s / dt)
    t = np.arange(n_samples) * dt

    base_lat = 19.0510
    base_lon = 72.8940
    
    speed_mps = np.zeros(n_samples)
    heading_deg = np.zeros(n_samples)
    
    # Accelerate 0..20s
    m1 = (t >= 5) & (t < 25)
    speed_mps[m1] = np.linspace(0, 12.0, np.sum(m1))
    
    # Cruising 25..65s (heading East: 90 deg)
    m2 = (t >= 25) & (t < 65)
    speed_mps[m2] = 12.0 + 0.5 * np.sin(0.3 * t[m2])
    heading_deg[m2] = 90.0
    
    # Turning North-East to North 65..80s
    m3 = (t >= 65) & (t < 80)
    speed_mps[m3] = 9.0
    heading_deg[m3] = np.linspace(90.0, 15.0, np.sum(m3))
    
    # Cruising North 80..140s
    m4 = (t >= 80) & (t < 140)
    speed_mps[m4] = 13.5 + 0.6 * np.cos(0.2 * t[m4])
    heading_deg[m4] = 15.0
    
    # Decelerating 140..170s to a stop
    m5 = (t >= 140) & (t < 170)
    speed_mps[m5] = np.linspace(13.5, 0.0, np.sum(m5))
    heading_deg[m5] = 15.0
    
    heading_rad = np.radians(heading_deg)
    vx = speed_mps * np.sin(heading_rad)  # East
    vy = speed_mps * np.cos(heading_rad)  # North
    
    east_m = np.cumsum(vx * dt)
    north_m = np.cumsum(vy * dt)
    
    lat_deg = base_lat + (north_m / 111139.0)
    lon_deg = base_lon + (east_m / (111139.0 * np.cos(np.radians(base_lat))))
    
    accel_x = np.gradient(vx, dt) + np.random.normal(0, 0.05, n_samples)
    accel_y = np.gradient(vy, dt) + np.random.normal(0, 0.05, n_samples)
    accel_z = np.full(n_samples, -9.81) + np.random.normal(0, 0.05, n_samples)
    
    yaw_rate = np.gradient(heading_rad, dt) + np.random.normal(0, 0.005, n_samples)
    
    df = pd.DataFrame({
        "timestamp_ns": (t * 1e9).astype(np.int64),
        "time_since_start_s": t,
        "latitude": lat_deg,
        "longitude": lon_deg,
        "speed_mps": speed_mps,
        "vehicle_speed": speed_mps * 3.6,
        "gps_accuracy_m": np.random.uniform(2.5, 4.5, n_samples),
        "gps_speed_mps": speed_mps + np.random.normal(0, 0.2, n_samples),
        "gps_bearing_deg": heading_deg,
        "accel_x": accel_x,
        "accel_y": accel_y,
        "accel_z": accel_z,
        "gyro_x": np.random.normal(0, 0.005, n_samples),
        "gyro_y": np.random.normal(0, 0.005, n_samples),
        "gyro_z": yaw_rate,
        "quat_w": np.cos(heading_rad / 2),
        "quat_x": np.zeros(n_samples),
        "quat_y": np.zeros(n_samples),
        "quat_z": np.sin(heading_rad / 2),
    })
    return df


def main():
    print("=" * 70)
    print("GENERATING PS-168 POSITION PLOT & DRIFT EVALUATION BENCHMARK")
    print("=" * 70)
    
    trip_df = create_realistic_test_trip(duration_s=180.0, dt=0.1)
    
    outage_start = 50.0
    outage_duration = 45.0
    
    print(f"\n[1] Running ESKF Replay with Injected Outage [{outage_start}s to {outage_start + outage_duration}s]...")
    replay_df, metrics = run_outage_replay(trip_df, outage_start, outage_duration)
    
    outage_mask = ~replay_df["gnss_available"].to_numpy(bool)
    gt_east = replay_df["east"].to_numpy()
    gt_north = replay_df["north"].to_numpy()
    outage_dist_m = float(np.sum(np.hypot(
        np.diff(gt_east[outage_mask]),
        np.diff(gt_north[outage_mask])
    )))
    
    percorsa_metrics = metrics["percorsa"]
    last_fix_metrics = metrics["last_fix"]
    
    max_drift_m = percorsa_metrics["max_error_m"]
    mean_drift_m = percorsa_metrics["mae_m"]
    rmse_drift_m = percorsa_metrics["rmse_m"]
    endpoint_error_m = percorsa_metrics["endpoint_error_m"]
    drift_pct = (max_drift_m / outage_dist_m) * 100.0
    
    print("\n[2] BENCHMARK DRIFT RESULTS:")
    print(f"    Outage Duration        : {outage_duration:.1f} seconds")
    print(f"    Distance Traveled in Outage: {outage_dist_m:.2f} meters")
    print(f"    Percorsa Max Outage Drift  : {max_drift_m:.2f} meters ({drift_pct:.2f}% of distance)")
    print(f"    Percorsa RMSE Outage Error : {rmse_drift_m:.2f} meters")
    print(f"    Percorsa Mean Outage Error : {mean_drift_m:.2f} meters")
    print(f"    Percorsa Outage End Error  : {endpoint_error_m:.2f} meters")
    print(f"    Baseline (Last Fix) Error  : {last_fix_metrics['max_error_m']:.2f} meters")
    print(f"    Improvement over Baseline  : {((last_fix_metrics['max_error_m'] - max_drift_m) / last_fix_metrics['max_error_m']) * 100:.1f}% reduction in error")
    
    plot_path = Path("artifacts/ps168_benchmark_drift.png")
    generate_ps_benchmark_plot(replay_df, metrics, plot_path)
    print(f"\n[3] Saved Official Benchmark Plot -> {plot_path.resolve()}")
    
    benchmark_meta = {
        "outage_start_s": outage_start,
        "outage_duration_s": outage_duration,
        "distance_traveled_during_outage_m": round(outage_dist_m, 2),
        "percorsa_max_drift_m": round(max_drift_m, 2),
        "percorsa_rmse_drift_m": round(rmse_drift_m, 2),
        "percorsa_mean_drift_m": round(mean_drift_m, 2),
        "percorsa_endpoint_drift_m": round(endpoint_error_m, 2),
        "percorsa_drift_percentage": round(drift_pct, 2),
        "baseline_last_fix_max_error_m": round(last_fix_metrics['max_error_m'], 2),
        "error_reduction_pct": round(((last_fix_metrics['max_error_m'] - max_drift_m) / last_fix_metrics['max_error_m']) * 100, 1),
        "metrics": metrics
    }
    with open("artifacts/ps168_benchmark_results.json", "w") as f:
        json.dump(benchmark_meta, f, indent=2)
    print(f"    Saved Metrics Summary -> artifacts/ps168_benchmark_results.json")
    print("\n" + "=" * 70)


if __name__ == "__main__":
    main()
