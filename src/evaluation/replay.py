"""End-to-end controlled outage replay used by the CLI and dashboard."""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd

from src.constraints.gnss_trust import GNSSFix, GNSSTrustManager
from src.constraints.stop_detection import StopDetector
from src.constraints.vehicle import VehicleConstraintDetector
from src.evaluation.baselines import last_fix_baseline
from src.evaluation.metrics import horizontal_errors, trajectory_metrics
from src.navigation.planar_ekf import PlanarEkf
from src.preprocessing.coordinates import (
    LatLonOrigin,
    add_local_coordinates,
    enu_to_latlon,
)
from src.preprocessing.outages import simulate_gnss_outage

PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONSTRAINT_CONFIG = PROJECT_ROOT / "configs" / "role4.yaml"


def _column(frame: pd.DataFrame, *names: str) -> str | None:
    return next((name for name in names if name in frame.columns), None)


def _optional_number(frame: pd.DataFrame, column: str | None, index: int):
    if column is None:
        return None
    try:
        value = float(frame[column].iloc[index])
    except (TypeError, ValueError):
        return None
    return value if np.isfinite(value) else None


def run_outage_replay(
    frame: pd.DataFrame,
    outage_start_s: float,
    outage_duration_s: float,
    speed_override_mps=None,
    speed_variance=None,
    *,
    use_stop_constraint: bool = True,
    use_non_holonomic_constraint: bool = True,
    use_gnss_trust: bool = True,
    recovery_updates: int = 5,
    constraint_config_path: str | Path = DEFAULT_CONSTRAINT_CONFIG,
) -> tuple[pd.DataFrame, dict[str, dict[str, float]]]:
    """Run a standardized trip through a constrained planar EKF.

    Reference GNSS remains in the returned frame for evaluation, but neither
    GNSS position nor GNSS-derived speed is passed to the estimator during the
    configured outage. Stop detection can trigger a zero-speed update, the
    planar motion model structurally enforces the non-holonomic constraint,
    and returning GNSS fixes pass trust and innovation gates before a gradual
    recovery update.
    """
    latitude = _column(frame, "latitude", "latitude_deg")
    longitude = _column(frame, "longitude", "longitude_deg")
    if latitude is None or longitude is None:
        raise ValueError("Replay requires latitude and longitude ground truth")
    if "time_since_start_s" not in frame:
        raise ValueError("Replay requires time_since_start_s")
    if recovery_updates < 0:
        raise ValueError("recovery_updates cannot be negative")

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

    finite_reference = np.isfinite(east) & np.isfinite(north)
    replay["gnss_available"] = (
        replay["gnss_available"].to_numpy(bool) & finite_reference
    )
    if not finite_reference[0]:
        raise ValueError("Replay requires a valid GNSS fix in the first row")

    speed_col = _column(replay, "speed_mps", "vehicle_speed", "speed_kmh")
    has_independent_speed = speed_override_mps is not None
    if has_independent_speed:
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

    # Model uncertainty is not calibrated yet. It is retained for reporting,
    # but the EKF deliberately uses its conservative configured speed noise.
    if speed_variance is not None:
        supplied_variance = np.asarray(speed_variance, dtype=float)
        if supplied_variance.shape != (len(replay),):
            raise ValueError("speed_variance must contain one value per row")

    yaw_col = _column(replay, "gyro_z", "gyro_yaw", "gyroscope_yaw_rads")
    yaw_rate = (
        np.zeros(len(replay))
        if yaw_col is None
        else pd.to_numeric(replay[yaw_col], errors="coerce").fillna(0.0).to_numpy(float)
    )

    available = replay["gnss_available"].to_numpy(bool)
    unavailable_indices = np.flatnonzero(~available)
    initialization_end = (
        int(unavailable_indices[0]) if unavailable_indices.size else len(replay)
    )
    moving = np.flatnonzero(
        (np.arange(len(replay)) < initialization_end)
        & (np.hypot(east - east[0], north - north[0]) > 1.0)
    )
    heading = 0.0
    if moving.size:
        j = int(moving[0])
        heading = float(np.arctan2(east[j] - east[0], north[j] - north[0]))
    initial_speed_allowed = has_independent_speed or bool(available[0])
    initial_speed = (
        speed_mps[0]
        if initial_speed_allowed and np.isfinite(speed_mps[0])
        else 0.0
    )
    ekf = PlanarEkf(np.array([east[0], north[0], initial_speed, heading]))

    config_path = Path(constraint_config_path)
    stop_detector = (
        StopDetector(config_path=config_path, enable_logging=False)
        if use_stop_constraint
        else None
    )
    nhc_detector = (
        VehicleConstraintDetector(config_path=config_path, enable_logging=False)
        if use_non_holonomic_constraint
        else None
    )
    trust_manager = (
        GNSSTrustManager(config_path=config_path, enable_logging=False)
        if use_gnss_trust
        else None
    )

    estimates = np.zeros((len(replay), 4))
    uncertainty = np.zeros(len(replay))
    modes: list[str] = []
    active_constraints: list[str] = []
    gnss_trusted = np.zeros(len(replay), dtype=bool)
    gnss_scores = np.zeros(len(replay), dtype=float)
    gnss_reasons: list[str] = []
    stop_detected = np.zeros(len(replay), dtype=bool)
    nhc_active = np.zeros(len(replay), dtype=bool)
    nhc_violation = np.zeros(len(replay), dtype=bool)
    recovery_remaining = 0

    accuracy_col = _column(replay, "gnss_accuracy_m", "gps_accuracy_m", "gps_accuracy")
    hdop_col = _column(replay, "gnss_hdop", "gps_hdop", "hdop")
    satellites_col = _column(replay, "satellite_count", "num_satellites")
    gnss_speed_col = _column(replay, "gps_speed_mps", "gnss_speed_mps")

    try:
        for i in range(len(replay)):
            if i:
                dt = times[i] - times[i - 1]
                if not np.isfinite(dt) or dt <= 0:
                    dt = 0.1
                ekf.predict(dt, yaw_rate[i])

            constraints: list[str] = []
            gnss_is_available = bool(replay["gnss_available"].iloc[i])

            # Prefer the receiver's own speed while GNSS is available. The TCN
            # is an outage measurement, not a reason to reject otherwise valid
            # GNSS before the outage when the model is outside its training
            # domain (for example, a walking recording).
            gnss_speed = (
                _optional_number(replay, gnss_speed_col, i)
                if gnss_is_available
                else None
            )
            if gnss_speed is not None and gnss_speed >= 0.0:
                ekf.update_speed(gnss_speed)
                constraints.append("GNSS_SPEED")
            elif has_independent_speed and np.isfinite(speed_mps[i]):
                ekf.update_speed(speed_mps[i])
                constraints.append("TCN_SPEED")
            elif gnss_is_available and np.isfinite(speed_mps[i]):
                ekf.update_speed(speed_mps[i])
                constraints.append("REFERENCE_SPEED")

            if stop_detector is not None:
                stop_event = stop_detector.update(times[i], float(ekf.state[2]))
                stop_detected[i] = stop_event.is_stopped
                zupt = stop_detector.to_constraint_event(stop_event)
                if zupt is not None:
                    ekf.apply_zero_velocity(zupt.confidence)
                    constraints.append("ZUPT")

            if nhc_detector is not None:
                nhc_state = nhc_detector.update(
                    times[i],
                    float(ekf.state[2]),
                    float(np.degrees(ekf.state[3])),
                )
                nhc_event = nhc_detector.to_constraint_event(nhc_state)
                nhc_active[i] = nhc_event is not None
                nhc_violation[i] = nhc_state.violation
                if nhc_event is not None:
                    # The reduced planar state has no lateral velocity state:
                    # its along-heading propagation enforces NHC structurally.
                    constraints.append("NHC")

            mode = "dead_reckoning"
            trust_reason = "controlled_outage" if not gnss_is_available else "not_evaluated"
            if not gnss_is_available:
                recovery_remaining = recovery_updates
            else:
                preliminary_accepted = True
                trust_score = 1.0
                if trust_manager is not None:
                    accuracy = _optional_number(replay, accuracy_col, i)
                    hdop = _optional_number(replay, hdop_col, i)
                    satellites = _optional_number(replay, satellites_col, i)
                    gnss_speed = _optional_number(replay, gnss_speed_col, i)
                    fix = GNSSFix(
                        timestamp=float(times[i]),
                        lat=float(replay[latitude].iloc[i]),
                        lon=float(replay[longitude].iloc[i]),
                        hdop=hdop,
                        accuracy_m=accuracy,
                        num_satellites=None if satellites is None else int(satellites),
                        speed_m_s=gnss_speed,
                    )
                    decision = trust_manager.evaluate(fix, now=float(times[i]))
                    preliminary_accepted = decision.accepted
                    trust_score = decision.score
                    trust_reason = decision.reason

                gnss_scores[i] = trust_score
                if preliminary_accepted:
                    accuracy = _optional_number(replay, accuracy_col, i)
                    base_accuracy = 4.0 if accuracy is None else max(accuracy, 1.0)
                    effective_accuracy = base_accuracy / max(trust_score, 0.25)
                    recovering = recovery_remaining > 0
                    if recovering and recovery_updates > 0:
                        fraction = recovery_remaining / recovery_updates
                        effective_accuracy *= 1.0 + 3.0 * fraction
                    update_accepted = ekf.update_gnss(
                        east[i], north[i], effective_accuracy
                    )
                    if update_accepted:
                        gnss_trusted[i] = True
                        constraints.append("GNSS")
                        if recovering:
                            mode = "recovery"
                            recovery_remaining -= 1
                        else:
                            mode = "GNSS"
                    else:
                        trust_reason = "innovation_rejected"
                        recovery_remaining = max(recovery_remaining, recovery_updates)
                else:
                    recovery_remaining = max(recovery_remaining, recovery_updates)

            modes.append(mode)
            gnss_reasons.append(trust_reason)
            active_constraints.append(",".join(constraints) if constraints else "NONE")
            estimates[i] = ekf.state
            uncertainty[i] = ekf.horizontal_uncertainty_m
    finally:
        if stop_detector is not None:
            stop_detector.close()
        if nhc_detector is not None:
            nhc_detector.close()
        if trust_manager is not None:
            trust_manager.close()

    replay["estimated_east"] = estimates[:, 0]
    replay["estimated_north"] = estimates[:, 1]
    replay["estimated_speed_mps"] = estimates[:, 2]
    replay["estimated_heading_rad"] = estimates[:, 3]
    replay["position_uncertainty_m"] = uncertainty
    replay["navigation_mode"] = modes
    replay["active_constraints"] = active_constraints
    replay["gnss_trusted"] = gnss_trusted
    replay["gnss_trust_score"] = gnss_scores
    replay["gnss_trust_reason"] = gnss_reasons
    replay["stop_detected"] = stop_detected
    replay["nhc_active"] = nhc_active
    replay["nhc_violation"] = nhc_violation
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
