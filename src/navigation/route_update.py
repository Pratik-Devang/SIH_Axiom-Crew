"""
route_update.py
===============
Route-geometry measurement updates for the 15-state Error-State Kalman Filter.

Architecture
------------
The route constraint is implemented as a proper ESKF measurement update —
NOT as a direct position overwrite. Two separate scalar measurements are
available:

1. **Lateral constraint**: The vehicle's ENU position projected perpendicular
   to the matched route segment should be zero (it should lie on the road
   centreline). This is a 1-D measurement:

       z_lat = 0
       h_lat(x) = n_seg^T * (p_enu - p_seg_start_enu)
                = n_seg[0] * (p[0] - p_start[0]) + n_seg[1] * (p[1] - p_start[1])

       H_lat = [ n_seg[0],  n_seg[1],  0,  0, ..., 0 ]    (1 x 15)
               ↑ d(h)/d(delta_p_east)  ↑ d(h)/d(delta_p_north)

   The measurement noise R_lat is made adaptive: larger when route confidence
   is low (GNSS outage with uncertain heading), smaller when GNSS-aided.

2. **Heading alignment constraint** (optional, outage only): The vehicle
   heading encoded in the ESKF quaternion should match the current route
   segment bearing. This is used as a soft constraint only when the route
   tracker has high confidence and the vehicle is clearly NOT mid-turn.

       z_hdg = 0
       h_hdg(x) = wrap_pi(psi_eskf(q) - psi_route)

   This is not injected during turns (high yaw rate) or low confidence
   because forcing heading toward the route bearing during a real turn
   would produce a wrong measurement.

Design rules (preserved from specification)
-------------------------------------------
- NEVER overwrite state.position = nearest_route_point.
- NEVER overwrite state.q = route_quaternion.
- NIS gating remains active — impossible innovations are rejected.
- Confidence thresholds prevent garbage matches from corrupting the ESKF.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Optional, Tuple

import numpy as np

from src.navigation.types import NominalState
from src.navigation.ins import quat_to_rotmat
from src.navigation.gnss_update import perform_joseph_update
from src.navigation.route_tracker import RouteMatchResult


# --------------------------------------------------------------------------
# Configuration
# --------------------------------------------------------------------------

@dataclass
class RouteConstraintConfig:
    """Tuning parameters for the route constraint update."""

    # Minimum route confidence to apply lateral constraint
    min_lateral_confidence: float = 0.30

    # Minimum route confidence to apply heading constraint
    min_heading_confidence: float = 0.55

    # Base lateral measurement noise std (metres)
    base_lateral_std_m: float = 3.0

    # Scaling factor applied when confidence is low (conservative → no over-trust)
    lateral_noise_confidence_scale: float = 8.0

    # Heading constraint noise std (radians)
    heading_std_rad: float = 0.15  # ≈ 8.6°

    # Maximum yaw rate (rad/s) above which heading constraint is skipped
    # (vehicle is mid-turn; the route bearing would fight the real turn)
    max_yaw_rate_for_heading_constraint: float = 0.20  # rad/s ≈ 11.5°/s

    # NIS confidence level for lateral and heading measurement gates
    nis_confidence: float = 0.99

    # Distance to next maneuver (m) below which heading constraint is disabled
    # (approaching a turn; bearing is transitioning)
    maneuver_inhibit_m: float = 15.0


_DEFAULT_ROUTE_CFG = RouteConstraintConfig()


# --------------------------------------------------------------------------
# Utility: extract 2D ENU heading from ESKF quaternion
# --------------------------------------------------------------------------

def _heading_from_state(
    state: NominalState,
    phone_to_veh_R: np.ndarray,
) -> float:
    """Extract vehicle heading in radians (CCW from East) from ESKF state.

    v_world = R_phone_to_world @ v_phone
    v_vehicle = R_v_p @ v_phone
    Forward vehicle direction in world = R_phone_to_world @ R_v_p^T @ e1

    Returns
    -------
    float
        Heading angle in radians (CCW from East / ENU convention).
    """
    e1 = np.array([1.0, 0.0, 0.0])               # forward in vehicle frame
    R_p_w = quat_to_rotmat(state.q)               # phone → world
    R_p_v = phone_to_veh_R.T                      # vehicle → phone  =>  ^T = phone → vehicle^{-1}

    # Forward vehicle axis expressed in world/ENU frame
    fwd_world = R_p_w @ (R_p_v @ e1)
    return math.atan2(fwd_world[1], fwd_world[0])   # atan2(north, east) → ENU heading


def _bearing_to_heading_rad(bearing_deg: float) -> float:
    """Convert CW-from-North bearing (deg) to CCW-from-East heading (rad)."""
    return math.radians(90.0 - bearing_deg)


def _wrap_pi(angle_rad: float) -> float:
    """Wrap angle to (-pi, pi]."""
    return (angle_rad + math.pi) % (2.0 * math.pi) - math.pi


# --------------------------------------------------------------------------
# Public API
# --------------------------------------------------------------------------

def update_route_lateral(
    state: NominalState,
    P: np.ndarray,
    match: RouteMatchResult,
    segment_normal_enu: np.ndarray,
    segment_start_enu: np.ndarray,
    config: RouteConstraintConfig = _DEFAULT_ROUTE_CFG,
) -> Tuple[NominalState, np.ndarray, bool, float]:
    """Fuse the lateral route constraint into the ESKF.

    The measurement model is:
        z_lat = 0
        h_lat(x) = n_seg . (p_enu - p_seg_start)
                 = n[0]*(p[0]-ps[0]) + n[1]*(p[1]-ps[1])

    Jacobian (1 x 15):
        H[0, 0] = n[0]   (d h / d delta_p_east)
        H[0, 1] = n[1]   (d h / d delta_p_north)
        All other entries = 0.

    Parameters
    ----------
    state : NominalState
        Current ESKF nominal state.
    P : np.ndarray
        15x15 error covariance.
    match : RouteMatchResult
        Current route match with lateral_error_m and confidence.
    segment_normal_enu : np.ndarray
        2D unit normal to the matched segment [n_east, n_north].
    segment_start_enu : np.ndarray
        2D ENU start of the matched segment [east, north].
    config : RouteConstraintConfig

    Returns
    -------
    (state, P, accepted, nis) : standard Joseph-update return signature.
    """
    if match.confidence < config.min_lateral_confidence:
        return state, P, False, 0.0

    # Innovation: z - h(x) = 0 - h(x)
    # h(x) = n . (p_enu - p_seg_start)
    p_2d = state.p[0:2]  # [east, north]
    h_lat = float(segment_normal_enu @ (p_2d - segment_start_enu))
    innovation = np.array([-h_lat], dtype=np.float64)

    # Measurement matrix H (1 x 15)
    H_lat = np.zeros((1, 15), dtype=np.float64)
    H_lat[0, 0] = segment_normal_enu[0]   # d(h)/d(delta_p_east)
    H_lat[0, 1] = segment_normal_enu[1]   # d(h)/d(delta_p_north)

    # Adaptive noise: larger when confidence is low
    effective_std = config.base_lateral_std_m + (
        config.lateral_noise_confidence_scale * (1.0 - match.confidence)
    )
    R_lat = np.array([[effective_std ** 2]], dtype=np.float64)

    return perform_joseph_update(state, P, innovation, H_lat, R_lat, nis_confidence=config.nis_confidence)


def update_route_heading(
    state: NominalState,
    P: np.ndarray,
    match: RouteMatchResult,
    phone_to_veh_R: np.ndarray,
    yaw_rate_rad_s: float = 0.0,
    config: RouteConstraintConfig = _DEFAULT_ROUTE_CFG,
) -> Tuple[NominalState, np.ndarray, bool, float]:
    """Fuse the route heading alignment constraint into the ESKF.

    This is a SOFT constraint that only activates when:
    - Route confidence is high enough.
    - Vehicle is NOT mid-turn (yaw rate below threshold).
    - Vehicle is NOT approaching a maneuver transition zone.

    The heading measurement model acts on the attitude error state delta_theta.
    Under the right-multiplicative ESKF convention, a heading perturbation of
    delta_psi (yaw about Z) is:

        h_hdg(x) ≈ psi_eskf(q) - psi_route

    The Jacobian H_hdg (1 x 15) maps the yaw component of delta_theta
    (index 8 in the 15D error state) to the heading measurement:

        H_hdg[0, 8] ≈ 1.0

    (Linearised about the current estimate; valid for small heading errors.)

    Parameters
    ----------
    state : NominalState
    P : np.ndarray
    match : RouteMatchResult
    phone_to_veh_R : np.ndarray
        3x3 phone-to-vehicle rotation matrix.
    yaw_rate_rad_s : float
        Current estimated yaw rate. High values inhibit the constraint.
    config : RouteConstraintConfig

    Returns
    -------
    (state, P, accepted, nis)
    """
    # Gate: skip during turns, near maneuvers, or low confidence
    if match.confidence < config.min_heading_confidence:
        return state, P, False, 0.0
    if abs(yaw_rate_rad_s) > config.max_yaw_rate_for_heading_constraint:
        return state, P, False, 0.0
    if match.distance_to_next_maneuver_m < config.maneuver_inhibit_m:
        return state, P, False, 0.0

    # Compute heading error
    eskf_heading_rad = _heading_from_state(state, phone_to_veh_R)
    route_heading_rad = _bearing_to_heading_rad(match.current_bearing_deg)
    heading_error = _wrap_pi(eskf_heading_rad - route_heading_rad)

    # Innovation = 0 - h(x) = -heading_error
    innovation = np.array([-heading_error], dtype=np.float64)

    # H_hdg (1 x 15): yaw is delta_theta[2] → index 8 in 15D error state
    H_hdg = np.zeros((1, 15), dtype=np.float64)
    H_hdg[0, 8] = 1.0

    R_hdg = np.array([[config.heading_std_rad ** 2]], dtype=np.float64)

    return perform_joseph_update(state, P, innovation, H_hdg, R_hdg, nis_confidence=config.nis_confidence)
