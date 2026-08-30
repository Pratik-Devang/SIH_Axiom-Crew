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
    """Fixed rotation matrix transforming vectors from Phone frame to Vehicle frame.

    R_v_p: 3x3 rotation matrix (v_vehicle = R_v_p @ v_phone)
    """

    R_v_p: np.ndarray = field(default_factory=lambda: np.eye(3, dtype=np.float64))

    def __post_init__(self):
        self.R_v_p = np.asarray(self.R_v_p, dtype=np.float64)


@dataclass
class ConstraintsConfig:
    """Configurable parameters for kinematic vehicle constraints (NHC and ZUPT)."""

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


# ---------------------------------------------------------------------------
# GNSS trust / estimation mode enumerations
# ---------------------------------------------------------------------------

class GnssStatus:
    """GNSS signal trust state constants."""
    GOOD = "GOOD"
    DEGRADED = "DEGRADED"
    LOST = "LOST"
    RECOVERING = "RECOVERING"


class EstimationMode:
    """Active navigation estimation mode constants."""
    FUSED = "FUSED"
    INS = "INS"
    INS_TCN = "INS_TCN"
    ROUTE_AWARE_INS = "ROUTE_AWARE_INS"
    RECOVERING = "RECOVERING"


# ---------------------------------------------------------------------------
# Full NavigationState — single source of truth for UI & evaluation
# ---------------------------------------------------------------------------

@dataclass
class NavigationState:
    """Complete navigation state emitted by RouteAwareNavigator.

    This is the single source of truth consumed by the UI, evaluation
    pipeline, and Android integration layer. It contains all information
    needed to render the map marker, route progress, diagnostics, and
    to perform offline evaluation without re-running the estimator.

    Position / velocity
    -------------------
    east, north, up : float
        3D position in local ENU frame (metres).
    velocity_east, velocity_north, velocity_up : float
        3D velocity in ENU (m/s).
    speed : float
        Scalar forward speed (m/s), always >= 0.
    heading_deg : float
        Vehicle heading, degrees clockwise from North [0, 360).
    latitude, longitude : float
        Geodetic WGS-84 position in degrees (derived from ENU via origin).

    Estimation quality
    ------------------
    timestamp : float
        State timestamp (seconds, matching IMU sample time).
    gnss_status : str
        One of GnssStatus constants: GOOD, DEGRADED, LOST, RECOVERING.
    estimation_mode : str
        One of EstimationMode constants: FUSED, INS, INS_TCN,
        ROUTE_AWARE_INS, RECOVERING.
    position_covariance : np.ndarray
        3x3 ENU position covariance (metres^2). Diagonal entries give
        the position uncertainty ellipsoid semi-axes.
    confidence : float
        Scalar overall navigation confidence in [0.0, 1.0].

    Route progress
    --------------
    route_segment_index : int
        Index of the currently matched route segment.
    route_progress_m : float
        Cumulative route arc-length progress (metres).
    distance_to_next_maneuver_m : float
        Distance in metres to the next upcoming route maneuver.
    current_route_bearing_deg : float
        Bearing of the currently matched route segment (CW from North).
    next_route_bearing_deg : float
        Bearing of the next route segment after the upcoming maneuver.
    lateral_route_error_m : float
        Signed perpendicular distance from the matched segment centreline.
        Positive = left of travel direction, Negative = right.
    heading_route_error_deg : float
        Signed angular difference between vehicle heading and route bearing.

    Active constraints
    ------------------
    active_constraints : list of str
        List of constraint labels active at this timestep.
        E.g. ['GNSS_POS', 'GNSS_VEL', 'TCN', 'NHC', 'ROUTE_LAT', 'ZUPT'].
    """

    # ── Timestamps ────────────────────────────────────────────────────────────
    timestamp: float = 0.0

    # ── Position ──────────────────────────────────────────────────────────────
    east: float = 0.0
    north: float = 0.0
    up: float = 0.0
    latitude: float = 0.0
    longitude: float = 0.0

    # ── Velocity ──────────────────────────────────────────────────────────────
    velocity_east: float = 0.0
    velocity_north: float = 0.0
    velocity_up: float = 0.0
    speed: float = 0.0
    heading_deg: float = 0.0

    # ── Estimation quality ────────────────────────────────────────────────────
    gnss_status: str = GnssStatus.LOST
    estimation_mode: str = EstimationMode.INS
    position_covariance: np.ndarray = field(
        default_factory=lambda: np.eye(3, dtype=np.float64) * 100.0
    )
    confidence: float = 0.0

    # ── Route progress ────────────────────────────────────────────────────────
    route_segment_index: int = 0
    route_progress_m: float = 0.0
    distance_to_next_maneuver_m: float = 0.0
    current_route_bearing_deg: float = 0.0
    next_route_bearing_deg: float = 0.0
    lateral_route_error_m: float = 0.0
    heading_route_error_deg: float = 0.0

    # ── Active constraints ────────────────────────────────────────────────────
    active_constraints: list = field(default_factory=list)

    def __post_init__(self):
        self.position_covariance = np.asarray(self.position_covariance, dtype=np.float64)
