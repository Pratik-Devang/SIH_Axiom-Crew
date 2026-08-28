"""End-to-end controlled outage replay used by the CLI and dashboard."""

from __future__ import annotations

import numpy as np
import pandas as pd

from src.evaluation.baselines import last_fix_baseline
from src.evaluation.metrics import horizontal_errors, trajectory_metrics
from src.navigation.planar_ekf import PlanarEkf
from src.preprocessing.coordinates import (
    LatLonOrigin,
    add_local_coordinates,
    enu_to_latlon,
)
from src.preprocessing.outages import simulate_gnss_outage


def _column(frame: pd.DataFrame, *names: str) -> str | None:
    return next((name for name in names if name in frame.columns), None)


def run_outage_replay(
    frame: pd.DataFrame,
    outage_start_s: float,
    outage_duration_s: float,
    speed_override_mps=None,
    speed_variance=None,
) -> tuple[pd.DataFrame, dict[str, dict[str, float]]]:
    """Run a standardized trip through a planar speed-aided EKF.

    GNSS samples remain in the returned frame as hidden ground truth, but are
    not passed to the filter during the configured outage.
    """
    latitude = _column(frame, "latitude", "latitude_deg")
    longitude = _column(frame, "longitude", "longitude_deg")
    if latitude is None or longitude is None:
        raise ValueError("Replay requires latitude and longitude ground truth")
    if "time_since_start_s" not in frame:
        raise ValueError("Replay requires time_since_start_s")

    replay = (
        frame.copy()
        .sort_values("time_since_start_s")
        .drop_duplicates("time_since_start_s")
        .reset_index(drop=True)
    )
    replay = add_local_coordinates(replay, latitude, longitude)
    replay = simulate_gnss_outage(replay, outage_start_s, outage_duration_s)
    times = pd.to_numeric(replay["time_since_start_s"], errors="coerce").to_numpy(float)
    east = replay["east"].to_numpy(float)
    north = replay["north"].to_numpy(float)

    speed_col = _column(replay, "speed_mps", "vehicle_speed", "speed_kmh")
    if speed_override_mps is not None:
        speed_mps = np.asarray(speed_override_mps, dtype=float)
        if speed_mps.shape != (len(replay),):
            raise ValueError("speed_override_mps must contain one value per row")
        replay["speed_source"] = "tcn_onnx"
    elif speed_col is None:
        speed_mps = np.zeros(len(replay))
        replay["speed_source"] = "zero_fallback"
    else:
        speed_mps = (
            pd.to_numeric(replay[speed_col], errors="coerce")
            .fillna(0.0)
            .to_numpy(float)
        )
        if speed_col != "speed_mps":
            speed_mps = speed_mps / 3.6
        replay["speed_source"] = speed_col
    variance = (
        None if speed_variance is None else np.asarray(speed_variance, dtype=float)
    )
    yaw_col = _column(replay, "gyro_z", "gyro_yaw", "gyroscope_yaw_rads")
    yaw_rate = (
        np.zeros(len(replay))
        if yaw_col is None
        else pd.to_numeric(replay[yaw_col], errors="coerce").fillna(0.0).to_numpy(float)
    )

    moving = np.flatnonzero(np.hypot(east - east[0], north - north[0]) > 1.0)
    heading = 0.0
    if moving.size:
        j = int(moving[0])
        heading = float(np.arctan2(east[j] - east[0], north[j] - north[0]))
    ekf = PlanarEkf(np.array([east[0], north[0], speed_mps[0], heading]))

    estimates = np.zeros((len(replay), 4))
    uncertainty = np.zeros(len(replay))
    for i in range(len(replay)):
        if i:
            dt = times[i] - times[i - 1]
            if not np.isfinite(dt) or dt <= 0:
                dt = 0.1
            ekf.predict(dt, yaw_rate[i])
        ekf.update_speed(speed_mps[i], None if variance is None else float(variance[i]))
        if bool(replay["gnss_available"].iloc[i]):
            accuracy_col = _column(replay, "gps_accuracy_m", "gps_accuracy")
            accuracy = None if accuracy_col is None else replay[accuracy_col].iloc[i]
            ekf.update_gnss(east[i], north[i], accuracy)
        estimates[i] = ekf.state
        uncertainty[i] = ekf.horizontal_uncertainty_m

    replay["estimated_east"] = estimates[:, 0]
    replay["estimated_north"] = estimates[:, 1]
    replay["estimated_speed_mps"] = estimates[:, 2]
    replay["estimated_heading_rad"] = estimates[:, 3]
    replay["position_uncertainty_m"] = uncertainty
    replay["position_error_m"] = horizontal_errors(
        east, north, estimates[:, 0], estimates[:, 1]
    )

    origin = LatLonOrigin(
        float(replay[latitude].iloc[0]), float(replay[longitude].iloc[0])
    )
    estimated_geo = [enu_to_latlon(e, n, origin) for e, n in estimates[:, :2]]
    replay["estimated_latitude"] = [point[0] for point in estimated_geo]
    replay["estimated_longitude"] = [point[1] for point in estimated_geo]

    last_east, last_north = last_fix_baseline(east, north, replay["gnss_available"])
    outage = ~replay["gnss_available"].to_numpy(bool)
    metrics = {
        "percorsa": trajectory_metrics(
            east, north, estimates[:, 0], estimates[:, 1], outage
        ),
        "last_fix": trajectory_metrics(east, north, last_east, last_north, outage),
    }
    return replay, metrics
