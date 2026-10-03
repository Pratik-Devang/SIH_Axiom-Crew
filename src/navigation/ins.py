"""IMU-driven strapdown inertial navigation propagation engine and frame algebra."""

import numpy as np
from src.navigation.types import NominalState, ImuSample, PhoneToVehicleTransform


def quat_multiply(q1: np.ndarray, q2: np.ndarray) -> np.ndarray:
    """Hamilton quaternion product q1 x q2.

    Quaternion format: [w, x, y, z] (scalar first).
    """
    w1, x1, y1, z1 = q1
    w2, x2, y2, z2 = q2

    w = w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2
    x = w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2
    y = w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2
    z = w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2

    return np.array([w, x, y, z], dtype=np.float64)


def quat_conjugate(q: np.ndarray) -> np.ndarray:
    """Returns conjugate of Hamilton quaternion q [w, -x, -y, -z]."""
    return np.array([q[0], -q[1], -q[2], -q[3]], dtype=np.float64)


def quat_normalize(q: np.ndarray) -> np.ndarray:
    """Normalizes quaternion to unit magnitude."""
    norm = np.linalg.norm(q)
    if norm < 1e-12:
        return np.array([1.0, 0.0, 0.0, 0.0], dtype=np.float64)
    return q / norm


def quat_to_rotmat(q: np.ndarray) -> np.ndarray:
    """Converts unit quaternion [w, x, y, z] to 3x3 rotation matrix.

    R transforms a vector from body/phone frame to world frame: v_world = R @ v_phone.
    """
    q_norm = quat_normalize(q)
    w, x, y, z = q_norm

    R = np.array(
        [
            [1.0 - 2.0 * (y**2 + z**2), 2.0 * (x * y - w * z), 2.0 * (x * z + w * y)],
            [2.0 * (x * y + w * z), 1.0 - 2.0 * (x**2 + z**2), 2.0 * (y * z - w * x)],
            [2.0 * (x * z - w * y), 2.0 * (y * z + w * x), 1.0 - 2.0 * (x**2 + y**2)],
        ],
        dtype=np.float64,
    )
    return R


def rotmat_to_quat(R: np.ndarray) -> np.ndarray:
    """Converts 3x3 rotation matrix to unit quaternion [w, x, y, z]."""
    tr = np.trace(R)
    if tr > 0.0:
        S = np.sqrt(tr + 1.0) * 2.0
        w = 0.25 * S
        x = (R[2, 1] - R[1, 2]) / S
        y = (R[0, 2] - R[2, 0]) / S
        z = (R[1, 0] - R[0, 1]) / S
    elif (R[0, 0] > R[1, 1]) and (R[0, 0] > R[2, 2]):
        S = np.sqrt(1.0 + R[0, 0] - R[1, 1] - R[2, 2]) * 2.0
        w = (R[2, 1] - R[1, 2]) / S
        x = 0.25 * S
        y = (R[0, 1] + R[1, 0]) / S
        z = (R[0, 2] + R[2, 0]) / S
    elif R[1, 1] > R[2, 2]:
        S = np.sqrt(1.0 + R[1, 1] - R[0, 0] - R[2, 2]) * 2.0
        w = (R[0, 2] - R[2, 0]) / S
        x = (R[0, 1] + R[1, 0]) / S
        y = 0.25 * S
        z = (R[1, 2] + R[2, 1]) / S
    else:
        S = np.sqrt(1.0 + R[2, 2] - R[0, 0] - R[1, 1]) * 2.0
        w = (R[1, 0] - R[0, 1]) / S
        x = (R[0, 2] + R[2, 0]) / S
        y = (R[1, 2] + R[2, 1]) / S
        z = 0.25 * S

    return quat_normalize(np.array([w, x, y, z], dtype=np.float64))


def rotate_vector_by_quat(v: np.ndarray, q: np.ndarray) -> np.ndarray:
    """Rotates 3D vector v by quaternion q [w, x, y, z]. Returns R(q) @ v."""
    R = quat_to_rotmat(q)
    return R @ np.asarray(v, dtype=np.float64)


def delta_quat_from_rotation_vector(delta_theta: np.ndarray) -> np.ndarray:
    """Computes incremental delta quaternion from axis-angle rotation vector delta_theta.

    Handles theta -> 0 boundary safely using Taylor series expansion.
    """
    delta_theta = np.asarray(delta_theta, dtype=np.float64)
    theta = np.linalg.norm(delta_theta)

    if theta > 1e-8:
        half_theta = 0.5 * theta
        w = np.cos(half_theta)
        scale = np.sin(half_theta) / theta
        vec = scale * delta_theta
    else:
        # Taylor expansion around theta = 0
        theta_sq = theta**2
        w = 1.0 - theta_sq / 8.0
        scale = 0.5 - theta_sq / 48.0
        vec = scale * delta_theta

    return quat_normalize(np.array([w, vec[0], vec[1], vec[2]], dtype=np.float64))


def skew_symmetric(v: np.ndarray) -> np.ndarray:
    """Returns 3x3 skew-symmetric cross-product matrix [v]_x."""
    v = np.asarray(v, dtype=np.float64)
    return np.array(
        [
            [0.0, -v[2], v[1]],
            [v[2], 0.0, -v[0]],
            [-v[1], v[0], 0.0],
        ],
        dtype=np.float64,
    )


class InsPropagator:
    """Strapdown Inertial Navigation System (INS) propagator."""

    def __init__(
        self,
        g_world: np.ndarray = np.array([0.0, 0.0, -9.81], dtype=np.float64),
        max_dt: float = 5.0,
    ):
        self.g_world = np.asarray(g_world, dtype=np.float64)
        self.max_dt = float(max_dt)

    def propagate(
        self,
        state: NominalState,
        sample: ImuSample,
        dt: float,
        phone_to_veh: PhoneToVehicleTransform = None,
    ) -> NominalState:
        """Propagates nominal state over delta t using IMU sample.

        Handles both raw acceleration (with gravity reaction) and linear acceleration.
        Biases ba and bg are subtracted in the Phone frame before transformation.
        Enforces strictly increasing timestamps and max dt limits.
        """
        if not np.isfinite(dt) or dt <= 0.0:
            raise ValueError(f"Invalid timestamp delta dt={dt}. Timestamps must be strictly increasing.")

        if dt > self.max_dt:
            raise ValueError(f"Unreasonable timestamp gap dt={dt} s exceeds maximum allowed gap {self.max_dt} s.")

        if not (np.all(np.isfinite(sample.accel)) and np.all(np.isfinite(sample.gyro))):
            raise ValueError("NaN or Inf detected in IMU sample")

        # Correct sensor biases in Phone frame
        a_p = sample.accel - state.ba
        w_p = sample.gyro - state.bg

        # 1. Orientation propagation
        delta_theta = w_p * dt
        delta_q = delta_quat_from_rotation_vector(delta_theta)
        q_next = quat_normalize(quat_multiply(state.q, delta_q))

        # 2. Transform acceleration to World ENU frame
        R_p_w = quat_to_rotmat(q_next)
        if sample.is_linear_accel:
            # Gravity already removed by Android OS
            a_world = R_p_w @ a_p
        else:
            # Raw acceleration: add gravity vector reaction
            a_world = R_p_w @ a_p + self.g_world

        # 3. Velocity propagation
        v_next = state.v + a_world * dt

        # 4. Position propagation
        p_next = state.p + state.v * dt + 0.5 * a_world * (dt**2)

        return NominalState(
            p=p_next,
            v=v_next,
            q=q_next,
            ba=state.ba.copy(),
            bg=state.bg.copy(),
            timestamp=sample.timestamp,
        )
