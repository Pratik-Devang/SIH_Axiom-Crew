"""Simple, transparent baselines for outage comparisons."""

from __future__ import annotations

import numpy as np


def last_fix_baseline(east, north, gnss_available) -> tuple[np.ndarray, np.ndarray]:
    """Hold the last available GNSS fix throughout an outage."""
    east = np.asarray(east, dtype=float)
    north = np.asarray(north, dtype=float)
    available = np.asarray(gnss_available, dtype=bool)
    out_e, out_n = np.empty_like(east), np.empty_like(north)
    last_e = last_n = np.nan
    for i in range(len(east)):
        if available[i] and np.isfinite(east[i]) and np.isfinite(north[i]):
            last_e, last_n = east[i], north[i]
        out_e[i], out_n[i] = last_e, last_n
    return out_e, out_n


def constant_velocity_baseline(
    times_s, east, north, gnss_available
) -> tuple[np.ndarray, np.ndarray]:
    """Extrapolate velocity measured between the last two GNSS fixes."""
    times = np.asarray(times_s, dtype=float)
    east = np.asarray(east, dtype=float)
    north = np.asarray(north, dtype=float)
    available = np.asarray(gnss_available, dtype=bool)
    out_e, out_n = np.empty_like(east), np.empty_like(north)
    fixes: list[tuple[float, float, float]] = []
    velocity = np.zeros(2)
    for i, time_s in enumerate(times):
        if available[i] and np.all(np.isfinite([east[i], north[i]])):
            fixes.append((time_s, east[i], north[i]))
            if len(fixes) >= 2:
                dt = fixes[-1][0] - fixes[-2][0]
                if dt > 0:
                    velocity = (
                        np.array(
                            [fixes[-1][1] - fixes[-2][1], fixes[-1][2] - fixes[-2][2]]
                        )
                        / dt
                    )
            out_e[i], out_n[i] = east[i], north[i]
        elif fixes:
            elapsed = time_s - fixes[-1][0]
            out_e[i], out_n[i] = np.array(fixes[-1][1:]) + velocity * elapsed
        else:
            out_e[i] = out_n[i] = np.nan
    return out_e, out_n
