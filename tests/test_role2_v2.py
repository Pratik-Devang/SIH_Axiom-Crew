from __future__ import annotations

import numpy as np
import pandas as pd
import torch

from src.ml.dataset import SpeedWindowDataset, make_window_arrays
from src.ml.preprocessing import INPUT_COLUMNS, fit_normalization, apply_normalization, standardize_trip_dataframe


def test_standardize_trip_dataframe():
    raw_df = pd.DataFrame({
        "accel_x": [1.0, 2.0],
        "accel_y": [0.0, 0.0],
        "accel_z": [9.8, 9.8],
        "gyro_yaw": [0.01, 0.02],
        "gyro_pitch": [0.0, 0.0],
        "gyro_roll": [0.0, 0.0],
        "vehicle_speed": [36.0, 72.0],  # in km/hr
    })
    std_df = standardize_trip_dataframe(raw_df)
    assert "speed_mps" in std_df.columns
    assert np.isclose(std_df["speed_mps"].iloc[0], 10.0)  # 36 km/h = 10 m/s
    assert np.isclose(std_df["speed_mps"].iloc[1], 20.0)  # 72 km/h = 20 m/s
    assert "gyro_x" in std_df.columns


def test_speed_window_dataset_no_cross_trip_leakage():
    # 2 trips of 25 samples each
    df1 = pd.DataFrame({col: np.random.randn(25) for col in INPUT_COLUMNS})
    df1["speed_mps"] = np.float32(10.0)
    
    df2 = pd.DataFrame({col: np.random.randn(25) for col in INPUT_COLUMNS})
    df2["speed_mps"] = np.float32(20.0)

    dataset = SpeedWindowDataset([df1, df2], window_samples=20, stride=1)
    
    # 25 samples with window 20 -> 6 windows per trip, 12 windows total
    assert len(dataset) == 12
    
    x0, y0 = dataset[0]
    x5, y5 = dataset[5]   # last window of trip 1
    x6, y6 = dataset[6]   # first window of trip 2
    
    assert tuple(x0.shape) == (6, 20)
    assert float(y5) == 10.0
    assert float(y6) == 20.0  # clean separation between trips


def test_normalization_fitting():
    df = pd.DataFrame({col: np.ones(50) * i for i, col in enumerate(INPUT_COLUMNS)})
    stats = fit_normalization([df])
    norm_df = apply_normalization(df, stats)
    
    for col in INPUT_COLUMNS:
        assert np.allclose(norm_df[col].to_numpy(), 0.0)
