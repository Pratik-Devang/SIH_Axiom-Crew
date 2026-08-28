"""Error-State Kalman Filter (ESKF) core engine, Jacobians, prediction, and validation."""

from dataclasses import dataclass, field
import numpy as np
from src.navigation.types import NominalState, ImuSample, ValidationResult
from src.navigation.ins import (
    quat_to_rotmat,
    quat_multiply,
    quat_normalize,
    delta_quat_from_rotation_vector,
    skew_symmetric,
)


@dataclass
class EskfConfig:
    """Noise spectral density parameters and physical constants.

    - sigma_a: Accelerometer noise standard deviation (m/s^2 / sqrt(s))
    - sigma_g: Gyroscope noise standard deviation (rad/s / sqrt(s))
    - sigma_ba: Accelerometer bias random walk standard deviation (m/s^3 / sqrt(s))
    - sigma_bg: Gyroscope bias random walk standard deviation (rad/s^2 / sqrt(s))
    """

    sigma_a: float = 0.1
    sigma_g: float = 0.01
    sigma_ba: float = 0.001
    sigma_bg: float = 0.0001


def validate_covariance(P: np.ndarray, sym_tol: float = 1e-5, psd_tol: float = -1e-7) -> ValidationResult:
    """Validates covariance matrix for numerical sanity: finite values, symmetry, and PSD eigenvalues."""
    if not np.all(np.isfinite(P)):
        return ValidationResult(is_valid=False, reason="Covariance contains NaN or Inf values")

    max_asym = np.max(np.abs(P - P.T))
    if max_asym > sym_tol:
        return ValidationResult(is_valid=False, reason=f"Covariance asymmetry {max_asym} exceeds tolerance {sym_tol}")

    eigvals = np.linalg.eigvalsh(P)
    min_eig = np.min(eigvals)
    if min_eig < psd_tol:
        return ValidationResult(
            is_valid=False, reason=f"Minimum eigenvalue {min_eig} violates PSD bound {psd_tol}"
        )

    return ValidationResult(is_valid=True, reason="Covariance is numerically valid")


def compute_continuous_f_matrix(state: NominalState, sample: ImuSample) -> np.ndarray:
    """Computes continuous-time 15x15 system matrix F_c.

    State error vector: delta_x = [delta_p (3), delta_v (3), delta_theta (3), delta_ba (3), delta_bg (3)]
    Defined under right-multiplicative attitude error convention and phone-frame biases.
    """
    F_c = np.zeros((15, 15), dtype=np.float64)

    # Correct measurements in Phone frame
    f_p = sample.accel - state.ba
    w_p = sample.gyro - state.bg

    R_p_w = quat_to_rotmat(state.q)

    # d(delta_p)/dt = delta_v
    F_c[0:3, 3:6] = np.eye(3, dtype=np.float64)

    # d(delta_v)/dt = -R * [f_p]_x * delta_theta - R * delta_ba
    F_c[3:6, 6:9] = -R_p_w @ skew_symmetric(f_p)
    F_c[3:6, 9:12] = -R_p_w

    # d(delta_theta)/dt = -[w_p]_x * delta_theta - delta_bg
    F_c[6:9, 6:9] = -skew_symmetric(w_p)
    F_c[6:9, 12:15] = -np.eye(3, dtype=np.float64)

    return F_c


def compute_continuous_g_matrix(state: NominalState) -> np.ndarray:
    """Computes continuous-time 15x12 process noise mapping matrix G_c."""
    G_c = np.zeros((15, 12), dtype=np.float64)
    R_p_w = quat_to_rotmat(state.q)

    G_c[3:6, 0:3] = -R_p_w
    G_c[6:9, 3:6] = -np.eye(3, dtype=np.float64)
    G_c[9:12, 6:9] = np.eye(3, dtype=np.float64)
    G_c[12:15, 9:12] = np.eye(3, dtype=np.float64)

    return G_c


def predict_covariance(
    P: np.ndarray,
    state: NominalState,
    sample: ImuSample,
    dt: float,
    config: EskfConfig = EskfConfig(),
) -> np.ndarray:
    """Predicts error state covariance matrix P over delta t.

    Uses single dt multiplication for discrete process noise Q_d = G_c * (Q_c * dt) * G_c^T.
    """
    F_c = compute_continuous_f_matrix(state, sample)
    G_c = compute_continuous_g_matrix(state)

    # Continuous noise spectral density Q_c (12x12)
    Q_c = np.diag(
        [
            config.sigma_a**2,
            config.sigma_a**2,
            config.sigma_a**2,
            config.sigma_g**2,
            config.sigma_g**2,
            config.sigma_g**2,
            config.sigma_ba**2,
            config.sigma_ba**2,
            config.sigma_ba**2,
            config.sigma_bg**2,
            config.sigma_bg**2,
            config.sigma_bg**2,
        ]
    )

    # First-order discrete transition matrix F_k
    I15 = np.eye(15, dtype=np.float64)
    F_k = I15 + F_c * dt + 0.5 * (F_c @ F_c) * (dt**2)

    # Discrete process noise covariance Q_d
    Q_d = G_c @ (Q_c * dt) @ G_c.T

    P_next = F_k @ P @ F_k.T + Q_d

    # Enforce covariance symmetry
    return 0.5 * (P_next + P_next.T)


def inject_error_and_reset(state: NominalState, delta_x: np.ndarray, P: np.ndarray) -> tuple[NominalState, np.ndarray]:
    """Injects 15D error state into nominal state and applies reset Jacobian G_reset to covariance.

    Returns updated NominalState and reset covariance P_reset.
    """
    delta_p = delta_x[0:3]
    delta_v = delta_x[3:6]
    delta_theta = delta_x[6:9]
    delta_ba = delta_x[9:12]
    delta_bg = delta_x[12:15]

    # 1. Inject error state into nominal state
    p_new = state.p + delta_p
    v_new = state.v + delta_v
    delta_q = delta_quat_from_rotation_vector(delta_theta)
    q_new = quat_normalize(quat_multiply(state.q, delta_q))
    ba_new = state.ba + delta_ba
    bg_new = state.bg + delta_bg

    state_new = NominalState(
        p=p_new,
        v=v_new,
        q=q_new,
        ba=ba_new,
        bg=bg_new,
        timestamp=state.timestamp,
    )

    # 2. Reset covariance with G_reset
    G_reset = np.eye(15, dtype=np.float64)
    G_reset[6:9, 6:9] = np.eye(3, dtype=np.float64) - 0.5 * skew_symmetric(delta_theta)

    P_reset = G_reset @ P @ G_reset.T
    P_reset = 0.5 * (P_reset + P_reset.T)

    return state_new, P_reset
