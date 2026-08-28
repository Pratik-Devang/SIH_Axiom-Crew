"""Interactive Black-Box Verification of the Speed TCN Model.

Simulates two different vehicle states (Stationary vs Accelerating) and sends 
them to the TCN model, just like testing a backend REST API with different payloads.
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import torch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from src.ml.tcn import build_model


def run_test_payload(model, name: str, imu_matrix: np.ndarray, mean: dict, std: dict) -> None:
    # 1. Normalize the simulated input using our training statistics
    columns = ["accel_x", "accel_y", "accel_z", "gyro_x", "gyro_y", "gyro_z"]
    norm_imu = np.zeros_like(imu_matrix, dtype=np.float32)
    
    for idx, col in enumerate(columns):
        norm_imu[idx, :] = (imu_matrix[idx, :] - mean[col]) / std[col]

    # Convert to PyTorch tensor shaped [1, 6, 20] (Batch size 1, 6 channels, 20 samples)
    x = torch.from_numpy(norm_imu).unsqueeze(0)
    
    # 2. Query the model (Inference / POST request)
    with torch.no_grad():
        out = model(x)
        speed_mean_mps = float(out[0, 0])
        log_var = float(out[0, 1])
        predicted_std_mps = float(np.sqrt(np.exp(log_var)))

    speed_kmh = speed_mean_mps * 3.6
    print(f"\n>>> SENDING TEST PAYLOAD: {name}")
    print("-" * 55)
    print(f"  Accel (Avg) : X={np.mean(imu_matrix[0]):.2f}, Y={np.mean(imu_matrix[1]):.2f}, Z={np.mean(imu_matrix[2]):.2f} m/s²")
    print(f"  Gyro  (Avg) : X={np.mean(imu_matrix[3]):.2f}, Y={np.mean(imu_matrix[4]):.2f}, Z={np.mean(imu_matrix[5]):.2f} rad/s")
    print("-" * 55)
    print(f"  BACKEND RESPONSE:")
    print(f"  Predicted Speed       : {speed_mean_mps:.3f} m/s ({speed_kmh:.2f} km/h)")
    print(f"  Prediction Confidence : ±{predicted_std_mps:.3f} m/s")
    print("=" * 55)


def main() -> None:
    ckpt_path = ROOT / "artifacts" / "tcn_best.pt"
    if not ckpt_path.exists():
        ckpt_path = ROOT / "artifacts" / "v2" / "tcn_best.pt"

    print(f"Loading checkpoint from: {ckpt_path}")
    ckpt = torch.load(ckpt_path, map_location="cpu")
    config = ckpt["config"]
    normalization = ckpt["normalization"]

    # Load model
    model = build_model(config)
    model.load_state_dict(ckpt["model_state_dict"])
    model.eval()

    # ==================================================================
    # SIMULATION PAYLOAD 1: Stationary Car
    # Accelerometer feels only gravity (9.8 m/s² down). Gyroscopes are zero.
    # ==================================================================
    stationary_imu = np.zeros((6, 20), dtype=np.float32)
    stationary_imu[2, :] = 9.81  # gravity on Z axis

    run_test_payload(
        model,
        "STATIONARY CAR (Only Gravity, No Rotation)",
        stationary_imu,
        normalization["mean"],
        normalization["std"]
    )

    # ==================================================================
    # SIMULATION PAYLOAD 2: Accelerating Car
    # Dynamic forward acceleration on X axis, plus moderate road vibration on Z.
    # ==================================================================
    accelerating_imu = np.zeros((6, 20), dtype=np.float32)
    accelerating_imu[0, :] = 2.50   # 2.5 m/s² forward acceleration
    accelerating_imu[2, :] = 9.81 + np.random.normal(0, 0.5, 20)  # gravity + road noise
    accelerating_imu[5, :] = 0.05   # slight yaw rate rotation (gentle curve)

    run_test_payload(
        model,
        "HIGH ACCELERATION CAR (Moving Forward + Vibration)",
        accelerating_imu,
        normalization["mean"],
        normalization["std"]
    )


if __name__ == "__main__":
    main()
