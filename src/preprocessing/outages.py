"""Controlled GNSS outage generation for reproducible evaluation."""

from __future__ import annotations

import numpy as np
import pandas as pd


def gnss_available_mask(times_s, start_s: float, duration_s: float) -> np.ndarray:
    """Return True where GNSS is available and False inside the outage."""
    times = np.asarray(times_s, dtype=float)
    if duration_s < 0:
        raise ValueError("duration_s cannot be negative")
    end_s = float(start_s) + float(duration_s)
    return ~((times >= float(start_s)) & (times < end_s))


def simulate_gnss_outage(
    frame: pd.DataFrame,
    start_s: float,
    duration_s: float,
    time_column: str = "time_since_start_s",
) -> pd.DataFrame:
    """Copy a trip and add a reproducible ``gnss_available`` column."""
    if time_column not in frame:
        raise KeyError(f"Missing time column: {time_column}")
    result = frame.copy()
    result["gnss_available"] = gnss_available_mask(
        result[time_column], start_s, duration_s
    )
    return result
