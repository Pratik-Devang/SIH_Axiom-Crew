from __future__ import annotations

import pytest
torch = pytest.importorskip("torch")
import numpy as np
import pandas as pd

from src.ml.dataset import SpeedWindowDataset, make_window_arrays
from src.ml.preprocessing import (
    INPUT_COLUMNS,
    apply_normalization,
    fit_normalization,
    resolve_split_paths,
    standardize_trip_dataframe,
    validate_training_input_contract,
)


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
    df1 = pd.DataFrame({col: np.random.randn(25) for col in INPUT_COLUMNS})
    df1["speed_mps"] = np.float32(10.0)
    
    df2 = pd.DataFrame({col: np.random.randn(25) for col in INPUT_COLUMNS})
    df2["speed_mps"] = np.float32(20.0)

    dataset = SpeedWindowDataset([df1, df2], window_samples=20, stride=1)
    
    assert len(dataset) == 12
    
    x0, y0 = dataset[0]
    x5, y5 = dataset[5]
    x6, y6 = dataset[6]
    
    assert tuple(x0.shape) == (6, 20)
    assert float(y5) == 10.0
    assert float(y6) == 20.0


def test_speed_window_dataset_handles_short_trip_before_valid_trip():
    short = pd.DataFrame({col: np.zeros(5, dtype=np.float32) for col in INPUT_COLUMNS})
    short["speed_mps"] = np.float32(1.0)
    valid = pd.DataFrame({col: np.zeros(25, dtype=np.float32) for col in INPUT_COLUMNS})
    valid["speed_mps"] = np.float32(7.0)

    dataset = SpeedWindowDataset([short, valid], window_samples=20, stride=1)

    assert len(dataset) == 6
    _, target = dataset[0]
    assert float(target) == 7.0


def test_normalization_fitting():
    df = pd.DataFrame({col: np.ones(50) * i for i, col in enumerate(INPUT_COLUMNS)})
    stats = fit_normalization([df])
    norm_df = apply_normalization(df, stats)
    
    for col in INPUT_COLUMNS:
        assert np.allclose(norm_df[col].to_numpy(), 0.0)


def test_explicit_split_manifest_is_complete_and_disjoint(tmp_path):
    trips = [tmp_path / f"{name}.csv" for name in ("a", "b", "c")]
    manifest = tmp_path / "splits.yaml"
    manifest.write_text("train: [a]\nvalidation: [b.csv]\ntest: [c]\n", encoding="utf-8")

    resolved = resolve_split_paths(trips, manifest)

    assert [path.stem for path in resolved["train"]] == ["a"]
    assert [path.stem for path in resolved["validation"]] == ["b"]
    assert [path.stem for path in resolved["test"]] == ["c"]


def test_explicit_split_manifest_rejects_trip_leakage(tmp_path):
    trips = [tmp_path / f"{name}.csv" for name in ("a", "b")]
    manifest = tmp_path / "splits.yaml"
    manifest.write_text("train: [a]\nvalidation: [a]\ntest: [b]\n", encoding="utf-8")

    with pytest.raises(ValueError, match="assigned to both"):
        resolve_split_paths(trips, manifest)


def test_explicit_split_manifest_rejects_unassigned_trips(tmp_path):
    trips = [tmp_path / f"{name}.csv" for name in ("a", "b", "c", "d")]
    manifest = tmp_path / "splits.yaml"
    manifest.write_text("train: [a]\nvalidation: [b]\ntest: [c]\n", encoding="utf-8")

    with pytest.raises(ValueError, match="missing"):
        resolve_split_paths(trips, manifest)


def test_training_contract_accepts_gravity_in_positive_z():
    frame = pd.DataFrame({col: np.zeros(100, dtype=np.float32) for col in INPUT_COLUMNS})
    frame["accel_z"] = np.float32(9.81)
    frame["speed_mps"] = np.float32(0.0)

    summary = validate_training_input_contract([frame])

    assert np.isclose(summary["median_accel_z_mps2"], 9.81)


def test_training_contract_rejects_opposite_z_axis():
    frame = pd.DataFrame({col: np.zeros(100, dtype=np.float32) for col in INPUT_COLUMNS})
    frame["accel_z"] = np.float32(-9.81)
    frame["speed_mps"] = np.float32(0.0)

    with pytest.raises(ValueError, match="Z-up"):
        validate_training_input_contract([frame])
