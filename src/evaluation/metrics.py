"""Metrics for a controlled GNSS-denied replay."""

from __future__ import annotations

import numpy as np


def horizontal_errors(
    truth_east, truth_north, estimate_east, estimate_north
) -> np.ndarray:
    arrays = [
        np.asarray(x, dtype=float)
        for x in (truth_east, truth_north, estimate_east, estimate_north)
    ]
    if len({a.shape for a in arrays}) != 1:
        raise ValueError("Truth and estimate arrays must have the same shape")
    return np.hypot(arrays[2] - arrays[0], arrays[3] - arrays[1])


def trajectory_metrics(
    truth_east, truth_north, estimate_east, estimate_north, mask=None
) -> dict[str, float]:
    errors = horizontal_errors(truth_east, truth_north, estimate_east, estimate_north)
    valid = np.isfinite(errors)
    if mask is not None:
        valid &= np.asarray(mask, dtype=bool)
    selected = errors[valid]
    if selected.size == 0:
        return {
            key: float("nan")
            for key in ("rmse_m", "mae_m", "max_error_m", "endpoint_error_m")
        }
    return {
        "rmse_m": float(np.sqrt(np.mean(selected**2))),
        "mae_m": float(np.mean(selected)),
        "max_error_m": float(np.max(selected)),
        "endpoint_error_m": float(selected[-1]),
    }
