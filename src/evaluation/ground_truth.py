"""Provenance-aware checks for real-world navigation references.

This module deliberately does not call an estimator.  It describes what a
recording contains and whether it is suitable for quantitative validation.
In particular, latitude/longitude columns are observations unless the caller
supplies an explicit, independently justified reference provenance.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass

import numpy as np
import pandas as pd


IMU_COLUMNS = ("accel_x", "accel_y", "accel_z", "gyro_x", "gyro_y", "gyro_z")
TRUSTED_REFERENCE_PROVENANCE = frozenset({"surveyed", "rtk", "external_reference"})


@dataclass(frozen=True)
class ReferenceAudit:
    """Machine-readable description of a candidate reference recording."""

    rows: int
    has_imu: bool
    position_source: str | None
    speed_source: str | None
    timestamp_source: str | None
    timestamp_monotonic: bool
    duplicate_timestamps: int
    median_sample_period_s: float | None
    p95_sample_period_s: float | None
    finite_position_rows: int
    accuracy_source: str | None
    accuracy_le_15m_fraction: float | None
    max_position_step_m: float | None
    provenance: str
    quantitative_validation_ready: bool
    reason: str

    def as_dict(self) -> dict[str, object]:
        return asdict(self)


def _first(frame: pd.DataFrame, names: tuple[str, ...]) -> str | None:
    return next((name for name in names if name in frame.columns), None)


def _numeric(frame: pd.DataFrame, column: str | None) -> np.ndarray | None:
    if column is None:
        return None
    return pd.to_numeric(frame[column], errors="coerce").to_numpy(dtype=float)


def audit_reference_frame(
    frame: pd.DataFrame,
    *,
    provenance: str = "unknown",
    position_source: str | None = None,
) -> ReferenceAudit:
    """Audit a candidate reference without treating GNSS as truth by default.

    ``provenance`` must be explicitly supplied as ``surveyed``, ``rtk`` or
    ``external_reference`` before the result can be marked suitable for
    quantitative validation.  A normal GNSS latitude/longitude stream is
    therefore retained as an observation and reported as not ready.
    """
    time_col = _first(frame, ("time_since_start_s", "timestamp_s", "timestamp"))
    time = _numeric(frame, time_col)
    if time is None and time_col == "timestamp":
        parsed = pd.to_datetime(frame[time_col], errors="coerce")
        time = (parsed - parsed.iloc[0]).dt.total_seconds().to_numpy(dtype=float)

    duplicate_timestamps = 0
    monotonic = False
    periods = np.array([], dtype=float)
    if time is not None:
        finite_time = np.isfinite(time)
        valid_time = time[finite_time]
        duplicate_timestamps = int(pd.Series(valid_time).duplicated().sum())
        monotonic = bool(len(valid_time) < 2 or np.all(np.diff(valid_time) >= 0.0))
        periods = np.diff(valid_time)
        periods = periods[np.isfinite(periods) & (periods > 0.0)]

    lat_col = _first(frame, ("reference_latitude", "latitude", "latitude_deg"))
    lon_col = _first(frame, ("reference_longitude", "longitude", "longitude_deg"))
    enu_e = _first(frame, ("reference_east_m", "east"))
    enu_n = _first(frame, ("reference_north_m", "north"))
    if enu_e and enu_n:
        position_name = position_source or "enu"
        px, py = _numeric(frame, enu_e), _numeric(frame, enu_n)
    elif lat_col and lon_col:
        position_name = position_source or "latitude_longitude_observation"
        px, py = _numeric(frame, lat_col), _numeric(frame, lon_col)
    else:
        position_name, px, py = None, None, None

    finite_position_rows = 0
    max_step = None
    if px is not None and py is not None:
        finite = np.isfinite(px) & np.isfinite(py)
        finite_position_rows = int(finite.sum())
        if finite_position_rows >= 2:
            if position_name == "enu":
                steps = np.hypot(np.diff(px), np.diff(py))
            else:
                radius = 6_378_137.0
                lat_rad = np.radians(px[:-1])
                steps = radius * np.hypot(
                    np.radians(np.diff(px)),
                    np.cos(lat_rad) * np.radians(np.diff(py)),
                )
            steps = steps[np.isfinite(steps)]
            max_step = float(np.max(steps)) if len(steps) else None

    speed_col = _first(
        frame,
        ("reference_speed_mps", "speed_reference", "vehicle_speed", "speed_mps"),
    )
    accuracy_col = _first(
        frame, ("reference_accuracy_m", "gnss_accuracy_m", "gps_accuracy_m", "accuracy_m")
    )
    accuracy = _numeric(frame, accuracy_col)
    accuracy_fraction = None
    if accuracy is not None:
        finite_accuracy = np.isfinite(accuracy)
        if finite_accuracy.any():
            accuracy_fraction = float(np.mean(accuracy[finite_accuracy] <= 15.0))

    has_imu = all(column in frame.columns for column in IMU_COLUMNS)
    explicit_reference = provenance in TRUSTED_REFERENCE_PROVENANCE
    ready = bool(
        explicit_reference
        and has_imu
        and position_name is not None
        and finite_position_rows >= 2
        and monotonic
    )
    if ready:
        reason = "Explicit independent reference provenance and valid synchronized samples."
    elif position_name == "latitude_longitude_observation":
        reason = "GNSS coordinates are observations, not independently established ground truth."
    elif not has_imu:
        reason = "Candidate reference is missing one or more canonical IMU channels."
    elif position_name is None:
        reason = "No reference position columns were found."
    else:
        reason = "Reference provenance is not explicitly independent or timestamps are invalid."

    return ReferenceAudit(
        rows=len(frame),
        has_imu=has_imu,
        position_source=position_name,
        speed_source=speed_col,
        timestamp_source=time_col,
        timestamp_monotonic=monotonic,
        duplicate_timestamps=duplicate_timestamps,
        median_sample_period_s=float(np.median(periods)) if len(periods) else None,
        p95_sample_period_s=float(np.percentile(periods, 95)) if len(periods) else None,
        finite_position_rows=finite_position_rows,
        accuracy_source=accuracy_col,
        accuracy_le_15m_fraction=accuracy_fraction,
        max_position_step_m=max_step,
        provenance=provenance,
        quantitative_validation_ready=ready,
        reason=reason,
    )
