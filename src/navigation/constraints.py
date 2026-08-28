"""Kinematic vehicle constraints: Non-Holonomic Constraints (NHC) and Zero-Velocity Updates (ZUPT)."""

from dataclasses import dataclass, field
import numpy as np
from src.navigation.types import NominalState, PhoneToVehicleTransform
from src.navigation.ins import quat_to_rotmat, skew_symmetric
from src.navigation.gnss_update import perform_joseph_update


@dataclass
class NhcMeasurement:
    """Non-Holonomic Constraint (NHC) measurement container.

    Constrains vehicle lateral (Y) and vertical (Z) velocities to zero.
    """

    timestamp: float
    std_lateral: float = 0.1
    std_vertical: float = 0.1


@dataclass
class ZuptMeasurement:
    """Zero-Velocity Update (ZUPT) measurement container.

    Constrains 3D velocity to zero when vehicle is confidently stationary.
    """

    timestamp: float
    std_velocity: float = 0.01


def compute_h_nhc(
    state: NominalState,
    phone_to_veh: PhoneToVehicleTransform = PhoneToVehicleTransform(),
) -> tuple[np.ndarray, np.ndarray]:
    """Computes predicted lateral & vertical vehicle speeds h_nhc(x) and Jacobian H_nhc (2x15)."""
    E23 = np.array([[0.0, 1.0, 0.0], [0.0, 0.0, 1.0]], dtype=np.float64)  # Y and Z axes
    R_p_w = quat_to_rotmat(state.q)
    R_w_p = R_p_w.T
    R_v_p = phone_to_veh.R_v_p

    v_phone = R_w_p @ state.v
    v_veh_lat_vert = E23 @ R_v_p @ v_phone

    H_nhc = np.zeros((2, 15), dtype=np.float64)

    # d(h)/d(delta_v)
    H_nhc[0:2, 3:6] = E23 @ R_v_p @ R_w_p

    # d(h)/d(delta_theta) = E23 * R_v_p * [v_phone]_x
    H_nhc[0:2, 6:9] = E23 @ R_v_p @ skew_symmetric(v_phone)

    return v_veh_lat_vert, H_nhc


def update_nhc(
    state: NominalState,
    P: np.ndarray,
    measurement: NhcMeasurement,
    phone_to_veh: PhoneToVehicleTransform = PhoneToVehicleTransform(),
    nis_confidence: float = 0.999,
) -> tuple[NominalState, np.ndarray, bool, float]:
    """Fuses Non-Holonomic Constraints (NHC) into ESKF."""
    pred_lat_vert, H_nhc = compute_h_nhc(state, phone_to_veh=phone_to_veh)

    # Innovation r = [0, 0] - pred_lat_vert
    innovation = -pred_lat_vert
    R_nhc = np.diag([measurement.std_lateral**2, measurement.std_vertical**2])

    return perform_joseph_update(state, P, innovation, H_nhc, R_nhc, nis_confidence=nis_confidence)


def update_zupt(
    state: NominalState,
    P: np.ndarray,
    measurement: ZuptMeasurement,
    nis_confidence: float = 0.999,
) -> tuple[NominalState, np.ndarray, bool, float]:
    """Fuses Zero-Velocity Update (ZUPT) into ESKF when vehicle is stationary."""
    # Innovation r = [0, 0, 0] - v_nominal
    innovation = -state.v

    # Measurement matrix H_zupt (3x15): [0_3, I_3, 0_3, 0_3, 0_3]
    H_zupt = np.zeros((3, 15), dtype=np.float64)
    H_zupt[0:3, 3:6] = np.eye(3, dtype=np.float64)

    R_zupt = np.eye(3, dtype=np.float64) * (measurement.std_velocity**2)

    return perform_joseph_update(state, P, innovation, H_zupt, R_zupt, nis_confidence=nis_confidence)
