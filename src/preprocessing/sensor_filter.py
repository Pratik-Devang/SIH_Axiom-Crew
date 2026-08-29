"""Causal IMU spike filtering with raw-value preservation."""

from __future__ import annotations

from collections import deque

import numpy as np
import pandas as pd

from src.preprocessing.synchronize import resample_to_10hz

IMU_COLUMNS = (
    "accel_x",
    "accel_y",
    "accel_z",
    "gyro_x",
    "gyro_y",
    "gyro_z",
)

FILTERED_PREFIX = "filtered_"
SPIKE_FLAG_BITS = {column: 1 << index for index, column in enumerate(IMU_COLUMNS)}
INVALID_FLAG_BITS = {
    column: 1 << (index + 8) for index, column in enumerate(IMU_COLUMNS)
}

# Floors prevent ordinary low-noise variations from being classified as spikes
# when the rolling MAD is close to zero. Units are m/s^2 and rad/s respectively.
MIN_ABSOLUTE_DEVIATION = {
    "accel_x": 3.0,
    "accel_y": 3.0,
    "accel_z": 3.0,
    "gyro_x": 0.75,
    "gyro_y": 0.75,
    "gyro_z": 0.75,
}


def _causal_hampel_channel(
    values: np.ndarray,
    *,
    window_size: int,
    n_sigma: float,
    min_absolute_deviation: float,
    min_history: int,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Filter isolated outliers using only current and previous samples.

    The history stores raw finite observations. This lets a sustained physical
    change become the new baseline instead of being suppressed indefinitely,
    while the rolling median remains robust to one-off sensor glitches.
    """
    filtered = np.empty(len(values), dtype=float)
    spikes = np.zeros(len(values), dtype=bool)
    invalid = np.zeros(len(values), dtype=bool)
    history: deque[float] = deque(maxlen=window_size)

    for index, raw_value in enumerate(values):
        if not np.isfinite(raw_value):
            invalid[index] = True
            filtered[index] = float(np.median(history)) if history else 0.0
            continue

        value = float(raw_value)
        replacement = value
        if len(history) >= min_history:
            history_values = np.asarray(history, dtype=float)
            center = float(np.median(history_values))
            mad = float(np.median(np.abs(history_values - center)))
            robust_sigma = 1.4826 * mad
            threshold = max(
                float(min_absolute_deviation), float(n_sigma) * robust_sigma
            )
            if abs(value - center) > threshold:
                spikes[index] = True
                replacement = center

        filtered[index] = replacement
        history.append(value)

    return filtered, spikes, invalid


def filter_sensor_spikes(
    frame: pd.DataFrame,
    *,
    columns: tuple[str, ...] = IMU_COLUMNS,
    window_size: int = 9,
    n_sigma: float = 6.0,
    min_history: int = 5,
) -> pd.DataFrame:
    """Append filtered IMU channels and merge detections into quality flags.

    Original sensor columns are never overwritten. ``quality_flags`` is a
    numeric bitmask: bits 0-5 mark spikes and bits 8-13 mark invalid values,
    ordered according to :data:`IMU_COLUMNS`.
    """
    if window_size < 3:
        raise ValueError("window_size must be at least 3")
    if min_history < 1 or min_history > window_size:
        raise ValueError("min_history must be between 1 and window_size")
    if n_sigma <= 0:
        raise ValueError("n_sigma must be positive")

    result = frame.copy()
    existing_flags = (
        pd.to_numeric(result["quality_flags"], errors="coerce").fillna(0)
        if "quality_flags" in result
        else pd.Series(np.zeros(len(result)), index=result.index)
    ).to_numpy(dtype=np.uint32)
    spike_labels: list[list[str]] = [[] for _ in range(len(result))]

    for column in columns:
        if column not in result:
            raise KeyError(f"Missing IMU column: {column}")
        values = pd.to_numeric(result[column], errors="coerce").to_numpy(float)
        filtered, spikes, invalid = _causal_hampel_channel(
            values,
            window_size=window_size,
            n_sigma=n_sigma,
            min_absolute_deviation=MIN_ABSOLUTE_DEVIATION[column],
            min_history=min_history,
        )
        result[f"{FILTERED_PREFIX}{column}"] = filtered
        existing_flags[spikes] |= np.uint32(SPIKE_FLAG_BITS[column])
        existing_flags[invalid] |= np.uint32(INVALID_FLAG_BITS[column])
        for index in np.flatnonzero(spikes | invalid):
            spike_labels[int(index)].append(column)

    result["quality_flags"] = existing_flags
    result["sensor_spike_detected"] = [bool(labels) for labels in spike_labels]
    result["filtered_sensor_columns"] = [",".join(labels) for labels in spike_labels]
    return result


def prepare_filtered_10hz_view(
    frame: pd.DataFrame,
    *,
    time_column: str = "time_since_start_s",
) -> pd.DataFrame:
    """Create the synchronized 10 Hz model view from filtered IMU channels."""
    filtered = (
        frame.copy()
        if all(f"{FILTERED_PREFIX}{column}" in frame for column in IMU_COLUMNS)
        else filter_sensor_spikes(frame)
    )
    model_view = filtered.copy()
    for column in IMU_COLUMNS:
        model_view[column] = model_view[f"{FILTERED_PREFIX}{column}"]
    return resample_to_10hz(model_view, time_column=time_column)
