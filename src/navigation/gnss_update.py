"""GNSS position and velocity measurement updates for ESKF using numerically stable linear solves."""

from dataclasses import dataclass, field
import numpy as np
from scipy.stats import chi2
from src.navigation.types import NominalState, ValidationResult
from src.navigation.eskf import inject_error_and_reset, validate_covariance


@dataclass
class GnssMeasurement:
    """GNSS measurement container.

    - timestamp: Measurement timestamp (seconds)
    - position: 3D position [east, north, up] in ENU frame (m), or None if unavailable
    - velocity: 3D velocity [veast, vnorth, vup] in ENU frame (m/s), or None if unavailable
    - std_pos: 3D position measurement standard deviation [std_e, std_n, std_u] (m)
    - std_vel: 3D velocity measurement standard deviation [std_ve, std_vn, std_vu] (m/s)
    """

    timestamp: float
    position: np.ndarray = None
    velocity: np.ndarray = None
    std_pos: np.ndarray = field(default_factory=lambda: np.array([3.0, 3.0, 5.0], dtype=np.float64))
    std_vel: np.ndarray = field(default_factory=lambda: np.array([0.5, 0.5, 1.0], dtype=np.float64))

    def __post_init__(self):
        if self.position is not None:
            self.position = np.asarray(self.position, dtype=np.float64)
        if self.velocity is not None:
            self.velocity = np.asarray(self.velocity, dtype=np.float64)
        self.std_pos = np.asarray(self.std_pos, dtype=np.float64)
        self.std_vel = np.asarray(self.std_vel, dtype=np.float64)


def perform_joseph_update(
    state: NominalState,
    P: np.ndarray,
    innovation: np.ndarray,
    H: np.ndarray,
    R: np.ndarray,
    nis_confidence: float = 0.999,
) -> tuple[NominalState, np.ndarray, bool, float]:
    """Executes Joseph-form ESKF measurement update with NIS validation check.

    Uses numerically stable linear solves instead of explicit matrix inversion.
    Returns (updated_state, updated_P, accepted_bool, nis_value).
    """
    dim_z = len(innovation)

    # Innovation covariance S = H * P * H^T + R
    S = H @ P @ H.T + R
    S = 0.5 * (S + S.T)  # Enforce symmetry

    try:
        # Solve S @ y = innovation => y = S^-1 @ innovation
        y = np.linalg.solve(S, innovation)
        # Solve S @ K^T = H @ P^T => K^T = S^-1 @ H @ P^T => K = (S^-1 @ H @ P)^T
        K_T = np.linalg.solve(S, H @ P.T)
        K = K_T.T
    except np.linalg.LinAlgError:
        return state, P, False, float("inf")

    # NIS innovation check: r^T * S^-1 * r <= chi2_threshold
    nis = float(innovation.T @ y)
    chi2_thresh = chi2.ppf(nis_confidence, df=dim_z)

    if nis > chi2_thresh or not np.isfinite(nis):
        # Reject outlier measurement
        return state, P, False, nis

    # Error state correction delta_x = K * r
    delta_x = K @ innovation

    # Joseph form covariance update: P_new = (I - K*H) * P * (I - K*H)^T + K * R * K^T
    I15 = np.eye(15, dtype=np.float64)
    I_KH = I15 - K @ H
    P_new = I_KH @ P @ I_KH.T + K @ R @ K.T
    P_new = 0.5 * (P_new + P_new.T)

    # Inject error state and apply reset
    state_new, P_reset = inject_error_and_reset(state, delta_x, P_new)

    return state_new, P_reset, True, nis


def update_gnss_position(
    state: NominalState,
    P: np.ndarray,
    measurement: GnssMeasurement,
    nis_confidence: float = 0.999,
) -> tuple[NominalState, np.ndarray, bool, float]:
    """Fuses 3D GNSS position measurement into ESKF using explicit position model z = p + noise."""
    if measurement.position is None:
        return state, P, False, 0.0

    # Innovation r = z - h(x) = position_gnss - position_nominal
    innovation = measurement.position - state.p

    # Measurement matrix H_pos (3x15): [I_3, 0_3, 0_3, 0_3, 0_3]
    H_pos = np.zeros((3, 15), dtype=np.float64)
    H_pos[0:3, 0:3] = np.eye(3, dtype=np.float64)

    # Noise matrix R_pos (3x3)
    R_pos = np.diag(measurement.std_pos**2)

    return perform_joseph_update(state, P, innovation, H_pos, R_pos, nis_confidence=nis_confidence)


def update_gnss_velocity(
    state: NominalState,
    P: np.ndarray,
    measurement: GnssMeasurement,
    nis_confidence: float = 0.999,
) -> tuple[NominalState, np.ndarray, bool, float]:
    """Fuses 3D GNSS velocity measurement into ESKF using explicit velocity model z = v + noise."""
    if measurement.velocity is None:
        return state, P, False, 0.0

    # Innovation r = z - h(x) = velocity_gnss - velocity_nominal
    innovation = measurement.velocity - state.v

    # Measurement matrix H_vel (3x15): [0_3, I_3, 0_3, 0_3, 0_3]
    H_vel = np.zeros((3, 15), dtype=np.float64)
    H_vel[0:3, 3:6] = np.eye(3, dtype=np.float64)

    # Noise matrix R_vel (3x3)
    R_vel = np.diag(measurement.std_vel**2)

    return perform_joseph_update(state, P, innovation, H_vel, R_vel, nis_confidence=nis_confidence)
