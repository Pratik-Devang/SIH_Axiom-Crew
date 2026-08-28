"""Navigation state, measurement, uncertainty, and configuration data structures."""

from dataclasses import dataclass, field
import numpy as np


@dataclass
class NominalState:
    """16-element nominal state vector container.

    - p: 3D position in ENU frame (m) [east, north, up]
    - v: 3D velocity in ENU frame (m/s)
    - q: Orientation quaternion [w, x, y, z] (Phone frame to ENU frame)
    - ba: Accelerometer bias in Phone frame (m/s^2)
    - bg: Gyroscope bias in Phone frame (rad/s)
    - timestamp: State timestamp (seconds)
    """

    p: np.ndarray = field(default_factory=lambda: np.zeros(3, dtype=np.float64))
    v: np.ndarray = field(default_factory=lambda: np.zeros(3, dtype=np.float64))
    q: np.ndarray = field(default_factory=lambda: np.array([1.0, 0.0, 0.0, 0.0], dtype=np.float64))
    ba: np.ndarray = field(default_factory=lambda: np.zeros(3, dtype=np.float64))
    bg: np.ndarray = field(default_factory=lambda: np.zeros(3, dtype=np.float64))
    timestamp: float = 0.0

    def to_vector(self) -> np.ndarray:
        """Returns concatenated 16-element numpy array [p, v, q, ba, bg]."""
        return np.concatenate([self.p, self.v, self.q, self.ba, self.bg])

    def copy(self) -> "NominalState":
        """Returns a deep copy of the nominal state."""
        return NominalState(
            p=self.p.copy(),
            v=self.v.copy(),
            q=self.q.copy(),
            ba=self.ba.copy(),
            bg=self.bg.copy(),
            timestamp=self.timestamp,
        )


@dataclass
class ImuSample:
    """IMU measurement frame.

    - timestamp: Measurement timestamp (seconds)
    - accel: 3D acceleration vector [ax, ay, az] (m/s^2) in Phone frame
    - gyro: 3D angular velocity vector [gx, gy, gz] (rad/s) in Phone frame
    - is_linear_accel: True if gravity has already been excluded (e.g. Android TYPE_LINEAR_ACCELERATION)
    """

    timestamp: float
    accel: np.ndarray
    gyro: np.ndarray
    is_linear_accel: bool = False

    def __post_init__(self):
        self.accel = np.asarray(self.accel, dtype=np.float64)
        self.gyro = np.asarray(self.gyro, dtype=np.float64)


@dataclass
class PhoneToVehicleTransform:
    """Fixed rotation matrix / quaternion transforming vectors from Phone frame to Vehicle frame.

    R_v_p: 3x3 rotation matrix (v_vehicle = R_v_p @ v_phone)
    """

    R_v_p: np.ndarray = field(default_factory=lambda: np.eye(3, dtype=np.float64))

    def __post_init__(self):
        self.R_v_p = np.asarray(self.R_v_p, dtype=np.float64)


@dataclass
class ConstraintsConfig:
    """Configurable parameters for kinematic vehicle constraints (NHC and ZUPT).

    - nhc_std_lateral: Lateral velocity noise standard deviation (m/s)
    - nhc_std_vertical: Vertical velocity noise standard deviation (m/s)
    - nhc_nis_confidence: NIS Chi-Square gating confidence level for NHC
    - zupt_std_velocity: Zero-velocity noise standard deviation (m/s)
    - zupt_nis_confidence: NIS Chi-Square gating confidence level for ZUPT
    """

    nhc_std_lateral: float = 0.1
    nhc_std_vertical: float = 0.1
    nhc_nis_confidence: float = 0.999
    zupt_std_velocity: float = 0.01
    zupt_nis_confidence: float = 0.999


@dataclass
class ValidationResult:
    """Numerical & sensor validation status result."""

    is_valid: bool
    reason: str = ""
