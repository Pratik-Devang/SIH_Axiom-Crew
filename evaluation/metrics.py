"""Numerically precise regression metrics used by the frozen-model evaluator."""

from __future__ import annotations

import numpy as np


def regression_metrics(prediction: np.ndarray, target: np.ndarray) -> dict[str, float]:
    """Return velocity-regression metrics in m/s without presentation rounding."""
    prediction = np.asarray(prediction, dtype=np.float64)
    target = np.asarray(target, dtype=np.float64)
    if prediction.ndim != 1 or target.ndim != 1 or len(prediction) != len(target) or not len(target):
        raise ValueError("prediction and target must be non-empty one-dimensional arrays of equal length")
    if not np.isfinite(prediction).all() or not np.isfinite(target).all():
        raise ValueError("metrics require finite predictions and targets")

    error = prediction - target
    absolute_error = np.abs(error)
    residual_sum = float(np.sum(error ** 2))
    total_sum = float(np.sum((target - np.mean(target)) ** 2))
    r2 = float("nan") if total_sum == 0.0 else 1.0 - residual_sum / total_sum
    return {
        "mae_mps": float(np.mean(absolute_error)),
        "mae_kmh": float(np.mean(absolute_error) * 3.6),
        "rmse_mps": float(np.sqrt(np.mean(error ** 2))),
        "rmse_kmh": float(np.sqrt(np.mean(error ** 2)) * 3.6),
        "r2": r2,
        "bias_mps": float(np.mean(error)),
        "bias_kmh": float(np.mean(error) * 3.6),
        "p95_absolute_error_mps": float(np.percentile(absolute_error, 95)),
        "p95_absolute_error_kmh": float(np.percentile(absolute_error, 95) * 3.6),
        "max_absolute_error_mps": float(np.max(absolute_error)),
        "max_absolute_error_kmh": float(np.max(absolute_error) * 3.6),
    }
