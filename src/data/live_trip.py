"""Validation and normalization for Android and dashboard trip uploads."""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd

from src.preprocessing.sensor_filter import filter_sensor_spikes

MAX_TRIP_ROWS = 500_000
SAFE_TRIP_ID = re.compile(r"[^a-zA-Z0-9_-]+")
SENSOR_COLUMNS = (
    "accel_x",
    "accel_y",
    "accel_z",
    "gyro_x",
    "gyro_y",
    "gyro_z",
)

ALIASES = {
    "ax": "accel_x",
    "ay": "accel_y",
    "az": "accel_z",
    "gx": "gyro_x",
    "gy": "gyro_y",
    "gz": "gyro_z",
    "latitude_deg": "latitude",
    "longitude_deg": "longitude",
    "gps_latitude": "latitude",
    "gps_longitude": "longitude",
    "accuracy_m": "gps_accuracy_m",
    "speed_mps": "gps_speed_mps",
    "bearing_deg": "gps_bearing_deg",
}


@dataclass(frozen=True)
class TripValidation:
    trip_id: str
    rows: int
    duration_s: float
    replay_ready: bool
    has_imu: bool
    has_gnss: bool
    issues: tuple[str, ...]

    def as_dict(self) -> dict[str, object]:
        return {
            "trip_id": self.trip_id,
            "rows": self.rows,
            "duration_s": self.duration_s,
            "replay_ready": self.replay_ready,
            "has_imu": self.has_imu,
            "has_gnss": self.has_gnss,
            "issues": list(self.issues),
        }


def safe_trip_id(value: str | None) -> str:
    """Return a filename-safe trip identifier, never a path."""
    candidate = Path(value or "uploaded_trip").stem.strip()
    candidate = SAFE_TRIP_ID.sub("_", candidate).strip("_-")
    return candidate[:64] or "uploaded_trip"


def normalize_trip_frame(
    source: pd.DataFrame, trip_id: str | None = None
) -> tuple[pd.DataFrame, TripValidation]:
    """Normalize a current or future Android export into Percorsa columns."""
    if len(source) < 2:
        raise ValueError("A trip must contain at least two samples")
    if len(source) > MAX_TRIP_ROWS:
        raise ValueError(f"Trip exceeds the {MAX_TRIP_ROWS:,} row limit")

    frame = source.copy()
    frame.columns = [str(column).strip() for column in frame.columns]
    frame = frame.rename(
        columns={old: new for old, new in ALIASES.items() if old in frame}
    )
    identifier = safe_trip_id(
        trip_id or (str(frame["trip_id"].iloc[0]) if "trip_id" in frame else None)
    )

    if "time_since_start_s" not in frame:
        if "timestamp_ns" not in frame:
            raise ValueError("Missing time_since_start_s or timestamp_ns")
        timestamp_ns = pd.to_numeric(frame["timestamp_ns"], errors="coerce")
        first = timestamp_ns.dropna().iloc[0]
        frame["time_since_start_s"] = (timestamp_ns - first) / 1_000_000_000.0

    frame["time_since_start_s"] = pd.to_numeric(
        frame["time_since_start_s"], errors="coerce"
    )
    frame = frame.dropna(subset=["time_since_start_s"])
    frame = (
        frame.sort_values("time_since_start_s", kind="stable")
        .drop_duplicates("time_since_start_s", keep="first")
        .reset_index(drop=True)
    )
    if len(frame) < 2:
        raise ValueError("Trip has fewer than two valid timestamps")

    missing_imu = [column for column in SENSOR_COLUMNS if column not in frame]
    if missing_imu:
        raise ValueError(f"Missing IMU columns: {', '.join(missing_imu)}")
    for column in SENSOR_COLUMNS:
        values = pd.to_numeric(frame[column], errors="coerce").replace(
            [np.inf, -np.inf], np.nan
        )
        if values.notna().sum() < 2:
            raise ValueError(f"IMU column {column} has insufficient numeric data")
        # Preserve the raw values. The causal filtering layer produces separate
        # ``filtered_*`` columns and records replacements in quality_flags.
        frame[column] = values

    frame = filter_sensor_spikes(frame)

    issues: list[str] = []
    filtered_rows = int(frame["sensor_spike_detected"].sum())
    if filtered_rows:
        issues.append(
            f"Filtered isolated IMU spikes or invalid values in {filtered_rows} rows; "
            "raw sensor columns were preserved."
        )
    has_gnss = {"latitude", "longitude"}.issubset(frame)
    if has_gnss:
        frame["latitude"] = pd.to_numeric(frame["latitude"], errors="coerce")
        frame["longitude"] = pd.to_numeric(frame["longitude"], errors="coerce")
        invalid = ~frame["latitude"].between(-90, 90) | ~frame["longitude"].between(
            -180, 180
        )
        frame.loc[invalid, ["latitude", "longitude"]] = np.nan
        has_gnss = int(frame[["latitude", "longitude"]].dropna().shape[0]) >= 2
    if not has_gnss:
        issues.append(
            "No usable GNSS coordinates. Sensor diagnostics are available, "
            "but route replay needs latitude and longitude."
        )

    numeric_optional = (
        "gps_accuracy_m",
        "gps_speed_mps",
        "gps_bearing_deg",
        "satellite_count",
    )
    for column in numeric_optional:
        if column in frame:
            frame[column] = pd.to_numeric(frame[column], errors="coerce")

    frame["trip_id"] = identifier
    duration = float(
        frame["time_since_start_s"].iloc[-1] - frame["time_since_start_s"].iloc[0]
    )
    validation = TripValidation(
        trip_id=identifier,
        rows=len(frame),
        duration_s=duration,
        replay_ready=has_gnss,
        has_imu=True,
        has_gnss=has_gnss,
        issues=tuple(issues),
    )
    return frame, validation
