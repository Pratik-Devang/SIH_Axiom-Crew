"""Data loading and preprocessing for the IO-VNBD speed prototype."""

from __future__ import annotations

import json
import random
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd
import torch
import yaml


PROJECT_ROOT = Path(__file__).resolve().parents[2]
CONFIG_PATH = PROJECT_ROOT / "configs" / "tcn.yaml"

INPUT_COLUMNS = ["accel_x", "accel_y", "accel_z", "gyro_yaw", "gyro_pitch", "gyro_roll"]
TARGET_COLUMN = "speed_mps"


def load_config(path: str | Path = CONFIG_PATH) -> dict[str, Any]:
    with Path(path).open("r", encoding="utf-8") as f:
        return yaml.safe_load(f)


def set_seed(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    torch.use_deterministic_algorithms(False)


def read_csv_flexible(path: Path) -> pd.DataFrame:
    for encoding in ("utf-8", "latin1"):
        try:
            return pd.read_csv(path, encoding=encoding)
        except UnicodeDecodeError:
            continue
    return pd.read_csv(path, encoding="latin1")


def _find_one(raw_dir: Path, pattern: str) -> Path:
    matches = sorted(p for p in raw_dir.glob(pattern) if p.is_file() and p.suffix.lower() in {".csv", ".txt"})
    if not matches:
        raise FileNotFoundError(f"No file matching {pattern!r} in {raw_dir}")
    return matches[0]


def find_io_vnbd_pair(config: dict[str, Any]) -> tuple[Path, Path]:
    raw_dir = PROJECT_ROOT / config["data"]["raw_dir"]
    smartphone = _find_one(raw_dir, config["data"]["smartphone_glob"])
    vehicle = _find_one(raw_dir, config["data"]["vehicle_glob"])
    return smartphone, vehicle


def _clean_name(name: str) -> str:
    return " ".join(name.strip().lower().split())


def _find_column(columns: list[str], required_terms: list[str]) -> str:
    cleaned = {col: _clean_name(col) for col in columns}
    for col, clean in cleaned.items():
        if all(term in clean for term in required_terms):
            return col
    raise KeyError(f"Could not find a column containing terms: {required_terms}")


def load_standardized_trip(config: dict[str, Any]) -> tuple[pd.DataFrame, dict[str, Any]]:
    smartphone_path, vehicle_path = find_io_vnbd_pair(config)
    phone = read_csv_flexible(smartphone_path)
    vehicle = read_csv_flexible(vehicle_path)

    if len(phone) != len(vehicle):
        raise ValueError(f"S/V row mismatch: smartphone={len(phone)} vehicle={len(vehicle)}")

    accel_x = _find_column(list(phone.columns), ["accelerometer", "x"])
    accel_y = _find_column(list(phone.columns), ["accelerometer", "y"])
    accel_z = _find_column(list(phone.columns), ["accelerometer", "z"])
    gyro_yaw = _find_column(list(phone.columns), ["gyroscope", "yaw"])
    gyro_pitch = _find_column(list(phone.columns), ["gyroscope", "pitch"])
    gyro_roll = _find_column(list(phone.columns), ["gyroscope", "roll"])
    phone_time = _find_column(list(phone.columns), ["time since start", "ms"])
    phone_date = _find_column(list(phone.columns), ["date"])

    speed_kmh = _find_column(list(vehicle.columns), ["indicated vehicle speed"])
    vehicle_time = _find_column(list(vehicle.columns), ["time since start of day"])
    sample_period = _find_column(list(vehicle.columns), ["sample period"])
    yaw_rate = _find_column(list(vehicle.columns), ["yaw rate"])

    trip = pd.DataFrame(
        {
            "sample_index": np.arange(len(phone), dtype=np.int64),
            "timestamp_s": phone[phone_time].astype(float) / 1000.0,
            "phone_datetime": phone[phone_date].astype(str),
            "vehicle_time_s": vehicle[vehicle_time].astype(float),
            "accel_x": phone[accel_x].astype(float),
            "accel_y": phone[accel_y].astype(float),
            "accel_z": phone[accel_z].astype(float),
            "gyro_yaw": phone[gyro_yaw].astype(float),
            "gyro_pitch": phone[gyro_pitch].astype(float),
            "gyro_roll": phone[gyro_roll].astype(float),
            "speed_mps": vehicle[speed_kmh].astype(float) / 3.6,
            "vehicle_yaw_rate_deg_s": vehicle[yaw_rate].astype(float),
            "sample_period_s": vehicle[sample_period].astype(float),
        }
    )

    metadata = {
        "smartphone_file": str(smartphone_path.relative_to(PROJECT_ROOT)),
        "vehicle_file": str(vehicle_path.relative_to(PROJECT_ROOT)),
        "rows": int(len(trip)),
        "input_source_columns": {
            "accel_x": accel_x,
            "accel_y": accel_y,
            "accel_z": accel_z,
            "gyro_yaw": gyro_yaw,
            "gyro_pitch": gyro_pitch,
            "gyro_roll": gyro_roll,
        },
        "target_source_column": speed_kmh,
        "target_unit": "m/s",
        "target_conversion": "km/hr divided by 3.6",
        "timestamp_columns": {"smartphone": phone_time, "vehicle": vehicle_time},
        "sample_period_column": sample_period,
    }
    return trip, metadata


def chronological_split(df: pd.DataFrame, train_fraction: float, validation_fraction: float) -> dict[str, pd.DataFrame]:
    n = len(df)
    train_end = int(n * train_fraction)
    val_end = train_end + int(n * validation_fraction)
    return {
        "train": df.iloc[:train_end].reset_index(drop=True),
        "validation": df.iloc[train_end:val_end].reset_index(drop=True),
        "test": df.iloc[val_end:].reset_index(drop=True),
    }


def fit_normalization(train_df: pd.DataFrame, columns: list[str] = INPUT_COLUMNS) -> dict[str, Any]:
    mean = train_df[columns].mean().to_dict()
    std = train_df[columns].std(ddof=0).replace(0.0, 1.0).to_dict()
    return {"columns": columns, "mean": mean, "std": std}


def apply_normalization(df: pd.DataFrame, stats: dict[str, Any]) -> pd.DataFrame:
    out = df.copy()
    for col in stats["columns"]:
        out[col] = (out[col] - stats["mean"][col]) / stats["std"][col]
    return out


def save_json(data: dict[str, Any], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
