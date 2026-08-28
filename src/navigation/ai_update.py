"""TCN forward-speed measurement update for ESKF."""

from dataclasses import dataclass
import numpy as np
from src.navigation.types import NominalState, PhoneToVehicleTransform
from src.navigation.ins import quat_to_rotmat, skew_symmetric
from src.navigation.gnss_update import perform_joseph_update


@dataclass
class TcnSpeedMeasurement:
    """TCN forward-speed measurement container.

    - timestamp: Measurement timestamp (seconds)
    - speed: Forward speed scalar (m/s) in vehicle frame (along X-axis)
    - std_speed: Measurement standard deviation (m/s)
    """

    timestamp: float
    speed: float
    std_speed: float = 0.5


def compute_h_tcn(
    state: NominalState,
    phone_to_veh: PhoneToVehicleTransform = PhoneToVehicleTransform(),
) -> tuple[float, np.ndarray]:
    """Computes predicted forward speed h_tcn(x) and Jacobian H_tcn (1x15).

    Under right-multiplicative attitude error convention:
    v_phone = R(q)^T * v_world
    v_vehicle = R_v_p * v_phone = R_v_p * R(q)^T * v_world
    h_tcn = e1^T * v_vehicle
    d(h)/d(delta_v) = e1^T * R_v_p * R(q)^T
    d(h)/d(delta_theta) = e1^T * R_v_p * [R(q)^T * v_world]_x
    """
    e1 = np.array([1.0, 0.0, 0.0], dtype=np.float64)
    R_p_w = quat_to_rotmat(state.q)  # Phone -> World
    R_w_p = R_p_w.T                  # World -> Phone
    R_v_p = phone_to_veh.R_v_p       # Phone -> Vehicle

    v_phone = R_w_p @ state.v
    v_vehicle = R_v_p @ v_phone
    predicted_speed = float(e1 @ v_vehicle)

    H_tcn = np.zeros((1, 15), dtype=np.float64)

    # d(h)/d(delta_v)
    H_tcn[0, 3:6] = e1 @ R_v_p @ R_w_p

    # d(h)/d(delta_theta) = e1^T * R_v_p * [v_phone]_x
    H_tcn[0, 6:9] = e1 @ R_v_p @ skew_symmetric(v_phone)

    return predicted_speed, H_tcn


def update_tcn_speed(
    state: NominalState,
    P: np.ndarray,
    measurement: TcnSpeedMeasurement,
    phone_to_veh: PhoneToVehicleTransform = PhoneToVehicleTransform(),
    nis_confidence: float = 0.999,
) -> tuple[NominalState, np.ndarray, bool, float]:
    """Fuses TCN forward speed measurement into ESKF."""
    pred_speed, H_tcn = compute_h_tcn(state, phone_to_veh=phone_to_veh)

    innovation = np.array([measurement.speed - pred_speed], dtype=np.float64)
    R_tcn = np.array([[measurement.std_speed**2]], dtype=np.float64)

    return perform_joseph_update(state, P, innovation, H_tcn, R_tcn, nis_confidence=nis_confidence)
