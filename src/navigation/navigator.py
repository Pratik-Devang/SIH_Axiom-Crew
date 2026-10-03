"""
navigator.py
============
Route-aware navigation orchestrator (RouteAwareNavigator).

Architecture
============

    IMU sample
        ↓
    InsPropagator.propagate()  →  NominalState (p, v, q, ba, bg)
        ↓
    predict_covariance()       →  P (15×15 error covariance)
        ↓
    Measurement update cascade (all via Joseph-form perform_joseph_update):
        ├── TCN forward speed         (update_tcn_speed)
        ├── GNSS position             (update_gnss_position)   [when GNSS GOOD/DEGRADED]
        ├── GNSS velocity             (update_gnss_velocity)   [when GNSS GOOD/DEGRADED]
        ├── NHC lateral/vertical ≈ 0  (update_nhc)
        ├── ZUPT velocity ≈ 0         (update_zupt)            [when stationary]
        ├── Route lateral constraint  (update_route_lateral)   [when route confident]
        └── Route heading constraint  (update_route_heading)   [when not mid-turn]
        ↓
    RouteProgressTracker.update_with_position()
        ↓
    NavigationState (full structured output)

GNSS trust state machine
-------------------------

    GOOD      ─→ DEGRADED ─→ LOST  ←─ (no fix)
      ↑                        ↓
      └─── RECOVERING ←────────┘

Transitions:
- GOOD       : GNSS accuracy ≤ 15m, recent fix.
- DEGRADED   : accuracy 15–30m, or slightly stale.
- LOST       : No fix for > gnss_lost_timeout_s seconds.
- RECOVERING : Fix received after LOST state; runs NIS-validation for
               recovery_steps steps before transitioning to GOOD/DEGRADED.

Route-aware GNSS outage behaviour
----------------------------------
During LOST:
    - INS propagation continues normally.
    - TCN speed constraint applied.
    - NHC and ZUPT remain active.
    - Route lateral and heading constraints applied to prevent drift
      away from the selected road.
    - Route progress is advanced by integrating ESKF forward speed.

During RECOVERING:
    - GNSS measurement is first validated by NIS (already in the
      perform_joseph_update framework).
    - If NIS passes: GNSS update is accepted with inflated noise for
      the first ``recovery_steps`` timesteps → no teleportation.
    - Route progress is re-established from the new position.

Important
---------
- This class does NOT rewrite the ESKF mathematics.
- All ESKF measurement updates go through perform_joseph_update.
- Route constraints are applied as measurements, not state overwrites.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import List, Optional

import numpy as np

from src.navigation.types import (
    NominalState,
    ImuSample,
    PhoneToVehicleTransform,
    ConstraintsConfig,
    ValidationResult,
    GnssStatus,
    EstimationMode,
    NavigationState,
)
from src.navigation.ins import InsPropagator, quat_to_rotmat
from src.navigation.eskf import EskfConfig, predict_covariance, validate_covariance
from src.navigation.gnss_update import GnssMeasurement, update_gnss_position, update_gnss_velocity
from src.navigation.ai_update import TcnSpeedMeasurement, update_tcn_speed
from src.navigation.constraints import (
    NhcMeasurement,
    ZuptMeasurement,
    update_nhc,
    update_zupt,
)
from src.navigation.route import Route
from src.navigation.route_tracker import RouteProgressTracker, RouteProgressTrackerConfig, RouteMatchResult
from src.navigation.route_update import (
    RouteConstraintConfig,
    update_route_lateral,
    update_route_heading,
)

try:
    from src.preprocessing.coordinates import LatLonOrigin, enu_to_latlon
except ImportError:
    from coordinate_transform import LatLonOrigin, enu_to_latlon  # type: ignore[import]


# --------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------

def _speed_from_velocity(v: np.ndarray) -> float:
    """Horizontal speed in m/s from ENU velocity vector."""
    return float(math.hypot(v[0], v[1]))


def _heading_from_velocity(v: np.ndarray, min_speed: float = 0.3) -> float:
    """Vehicle heading (degrees CW from North) from ENU velocity.

    Falls back to 0.0 when speed is below min_speed (avoids noise at rest).
    """
    speed = math.hypot(v[0], v[1])
    if speed < min_speed:
        return 0.0
    return (math.degrees(math.atan2(v[0], v[1])) + 360.0) % 360.0


def _yaw_rate_from_gyro(gyro_phone: np.ndarray, R_v_p: np.ndarray) -> float:
    """Estimate yaw rate (rad/s) by projecting gyro into vehicle frame.

    Yaw rate = Z-component of angular rate in vehicle frame.
    """
    omega_vehicle = R_v_p @ gyro_phone
    return float(omega_vehicle[2])


# --------------------------------------------------------------------------
# Navigator configuration
# --------------------------------------------------------------------------

@dataclass
class NavigatorConfig:
    """All configuration parameters for RouteAwareNavigator."""

    # ESKF noise parameters
    eskf: EskfConfig = field(default_factory=EskfConfig)

    # Vehicle constraint parameters (NHC, ZUPT)
    constraints: ConstraintsConfig = field(default_factory=ConstraintsConfig)

    # Route constraint parameters
    route_constraint: RouteConstraintConfig = field(default_factory=RouteConstraintConfig)

    # Route tracker parameters
    route_tracker: RouteProgressTrackerConfig = field(default_factory=RouteProgressTrackerConfig)

    # Initial diagonal covariance values [p_e, p_n, p_u, v_e, v_n, v_u,
    #                                       th_x, th_y, th_z, ba_x, ba_y, ba_z,
    #                                       bg_x, bg_y, bg_z]
    initial_p_std: float = 5.0      # metres
    initial_v_std: float = 0.5      # m/s
    initial_att_std: float = 0.05   # radians
    initial_ba_std: float = 0.05    # m/s^2
    initial_bg_std: float = 0.005   # rad/s

    # GNSS trust thresholds
    gnss_good_accuracy_m: float = 15.0
    gnss_degraded_accuracy_m: float = 30.0
    gnss_lost_timeout_s: float = 5.0

    # Recovery: number of update steps with inflated noise on GNSS return
    gnss_recovery_steps: int = 5
    gnss_recovery_noise_factor: float = 4.0

    # Stationary detection speed threshold (m/s)
    zupt_speed_threshold_ms: float = 0.2

    # Whether to apply route constraints during GNSS-available phase too
    route_constraint_during_gnss: bool = True


# --------------------------------------------------------------------------
# Main navigator class
# --------------------------------------------------------------------------

class RouteAwareNavigator:
    """Route-aware navigation orchestrator.

    Fuses IMU (INS + ESKF), TCN forward speed, vehicle kinematic
    constraints (NHC, ZUPT), GNSS measurements, and planned route
    geometry to produce a complete :class:`NavigationState`.

    Parameters
    ----------
    origin : LatLonOrigin
        Local ENU frame origin. Must match the ESKF state coordinate frame
        and the route geometry origin.
    phone_to_veh : PhoneToVehicleTransform
        Phone-to-vehicle frame rotation.
    route : Route, optional
        The planned navigation route. If None, route constraints are skipped
        and the navigator operates as pure INS + ESKF + TCN + NHC + ZUPT.
    config : NavigatorConfig, optional
        Tuning parameters.

    Usage
    -----
    >>> nav = RouteAwareNavigator(origin, phone_to_veh, route)
    >>> nav.initialize(imu_sample, gnss_pos_enu)
    >>> for imu, gnss, tcn in data_stream:
    ...     state = nav.process_imu(imu)
    ...     if gnss is not None:
    ...         state = nav.process_gnss(gnss)
    ...     if tcn is not None:
    ...         state = nav.process_tcn(tcn)
    ...     nav_state = nav.get_navigation_state()
    """

    def __init__(
        self,
        origin: LatLonOrigin,
        phone_to_veh: PhoneToVehicleTransform = None,
        route: Optional[Route] = None,
        config: Optional[NavigatorConfig] = None,
    ) -> None:
        self.origin = origin
        self.phone_to_veh = phone_to_veh or PhoneToVehicleTransform()
        self.route = route
        self.config = config or NavigatorConfig()

        # INS propagator
        self._ins = InsPropagator()

        # Route progress tracker
        self._tracker: Optional[RouteProgressTracker] = None
        if route is not None:
            self._tracker = RouteProgressTracker(
                route, config=self.config.route_tracker
            )

        # ESKF state and covariance
        self._state: Optional[NominalState] = None
        self._P: Optional[np.ndarray] = None

        # GNSS trust machine
        self._gnss_status: str = GnssStatus.LOST
        self._last_gnss_timestamp: float = -999.0
        self._gnss_recovery_remaining: int = 0

        # Last known good route match
        self._last_match: Optional[RouteMatchResult] = None

        # Last active constraints (for NavigationState output)
        self._active_constraints: List[str] = []

        # Initialized flag
        self._initialized: bool = False

    # ------------------------------------------------------------------
    # Initialization
    # ------------------------------------------------------------------

    def initialize(
        self,
        initial_position_enu: np.ndarray,
        initial_velocity_enu: Optional[np.ndarray] = None,
        initial_q: Optional[np.ndarray] = None,
        initial_timestamp: float = 0.0,
    ) -> None:
        """Initialize the ESKF state and covariance.

        Parameters
        ----------
        initial_position_enu : np.ndarray
            3D ENU position [east, north, up] in metres.
        initial_velocity_enu : np.ndarray, optional
            3D ENU velocity. Defaults to zero.
        initial_q : np.ndarray, optional
            Initial orientation quaternion [w, x, y, z]. Defaults to identity.
        initial_timestamp : float
            Initial timestamp in seconds.
        """
        cfg = self.config
        p = np.asarray(initial_position_enu, dtype=np.float64)
        v = initial_velocity_enu if initial_velocity_enu is not None else np.zeros(3)
        q = initial_q if initial_q is not None else np.array([1.0, 0.0, 0.0, 0.0])

        self._state = NominalState(
            p=p.copy(),
            v=np.asarray(v, dtype=np.float64),
            q=np.asarray(q, dtype=np.float64),
            ba=np.zeros(3),
            bg=np.zeros(3),
            timestamp=initial_timestamp,
        )

        # Initial covariance diagonal
        P_diag = np.array([
            cfg.initial_p_std**2,  cfg.initial_p_std**2,  cfg.initial_p_std**2,
            cfg.initial_v_std**2,  cfg.initial_v_std**2,  cfg.initial_v_std**2,
            cfg.initial_att_std**2, cfg.initial_att_std**2, cfg.initial_att_std**2,
            cfg.initial_ba_std**2, cfg.initial_ba_std**2,  cfg.initial_ba_std**2,
            cfg.initial_bg_std**2,  cfg.initial_bg_std**2,  cfg.initial_bg_std**2,
        ], dtype=np.float64)
        self._P = np.diag(P_diag)
        self._initialized = True

        # Initialize route progress if route is available
        if self._tracker is not None:
            self._tracker.update_with_position(
                east=float(p[0]),
                north=float(p[1]),
                heading_rad=_heading_from_velocity(np.asarray(v)),
                speed_ms=_speed_from_velocity(np.asarray(v)),
            )

    # ------------------------------------------------------------------
    # IMU propagation
    # ------------------------------------------------------------------

    def process_imu(self, sample: ImuSample) -> "RouteAwareNavigator":
        """Propagate ESKF state through an IMU sample.

        This should be called for every IMU sample, regardless of GNSS
        or TCN availability.

        Parameters
        ----------
        sample : ImuSample
            Accelerometer and gyroscope measurement in phone frame.

        Returns
        -------
        self (for chaining)
        """
        if not self._initialized:
            raise RuntimeError("Navigator not initialized. Call initialize() first.")

        dt = sample.timestamp - self._state.timestamp
        if not (0.0 < dt <= 5.0):
            # Skip invalid or too-large timesteps
            return self

        # 1. INS mechanization
        self._state = self._ins.propagate(
            self._state, sample, dt, phone_to_veh=self.phone_to_veh
        )

        # 2. ESKF covariance prediction
        self._P = predict_covariance(
            self._P, self._state, sample, dt, config=self.config.eskf
        )

        # 3. NHC update (always, while vehicle is moving)
        speed = _speed_from_velocity(self._state.v)
        if speed > 0.05:
            nhc_meas = NhcMeasurement(
                timestamp=sample.timestamp,
                std_lateral=self.config.constraints.nhc_std_lateral,
                std_vertical=self.config.constraints.nhc_std_vertical,
            )
            state_nhc, P_nhc, accepted_nhc, _ = update_nhc(
                self._state, self._P, nhc_meas,
                phone_to_veh=self.phone_to_veh,
                nis_confidence=self.config.constraints.nhc_nis_confidence,
            )
            if accepted_nhc:
                self._state = state_nhc
                self._P = P_nhc

        return self

    # ------------------------------------------------------------------
    # GNSS update
    # ------------------------------------------------------------------

    def process_gnss(self, measurement: GnssMeasurement) -> "RouteAwareNavigator":
        """Apply GNSS position and velocity measurement to the ESKF.

        Internally updates the GNSS trust state machine and applies
        inflated noise during the recovery period after outage.

        Parameters
        ----------
        measurement : GnssMeasurement
            GNSS position/velocity measurement in ENU metres.

        Returns
        -------
        self (for chaining)
        """
        if not self._initialized:
            return self

        cfg = self.config
        constraints = list(self._active_constraints)

        # Determine accuracy-based quality
        if measurement.position is None:
            self._update_gnss_status_no_fix(measurement.timestamp)
            return self

        pos_accuracy = float(np.mean(measurement.std_pos[:2]))
        was_lost = self._gnss_status == GnssStatus.LOST

        if pos_accuracy <= cfg.gnss_good_accuracy_m:
            new_quality = GnssStatus.GOOD
        elif pos_accuracy <= cfg.gnss_degraded_accuracy_m:
            new_quality = GnssStatus.DEGRADED
        else:
            new_quality = GnssStatus.DEGRADED

        if was_lost:
            # Entering recovery: inflate noise for the first N updates
            self._gnss_status = GnssStatus.RECOVERING
            self._gnss_recovery_remaining = cfg.gnss_recovery_steps

        if self._gnss_status == GnssStatus.RECOVERING and self._gnss_recovery_remaining > 0:
            # Inflate measurement noise during recovery (prevents teleportation)
            factor = 1.0 + cfg.gnss_recovery_noise_factor * (
                self._gnss_recovery_remaining / cfg.gnss_recovery_steps
            )
            inflated_std_pos = measurement.std_pos * factor
            recovery_meas = GnssMeasurement(
                timestamp=measurement.timestamp,
                position=measurement.position,
                velocity=measurement.velocity,
                std_pos=inflated_std_pos,
                std_vel=measurement.std_vel,
            )
            state_up, P_up, accepted_pos, _ = update_gnss_position(
                self._state, self._P, recovery_meas
            )
            if accepted_pos:
                self._state = state_up
                self._P = P_up
                constraints.append("GNSS_POS_RECOVER")
                self._gnss_recovery_remaining -= 1
                if self._gnss_recovery_remaining <= 0:
                    self._gnss_status = new_quality
        else:
            # Normal GNSS update
            state_up, P_up, accepted_pos, _ = update_gnss_position(
                self._state, self._P, measurement
            )
            if accepted_pos:
                self._state = state_up
                self._P = P_up
                constraints.append("GNSS_POS")
                self._gnss_status = new_quality
            else:
                # NIS rejected — may be outlier; stay in current mode
                if self._gnss_status != GnssStatus.LOST:
                    self._gnss_status = GnssStatus.DEGRADED

        # Velocity update (if available)
        if measurement.velocity is not None:
            state_vel, P_vel, accepted_vel, _ = update_gnss_velocity(
                self._state, self._P, measurement
            )
            if accepted_vel:
                self._state = state_vel
                self._P = P_vel
                constraints.append("GNSS_VEL")

        self._last_gnss_timestamp = measurement.timestamp
        self._active_constraints = constraints

        # Re-establish route progress after GNSS update
        if self._tracker is not None and measurement.position is not None:
            speed = _speed_from_velocity(self._state.v)
            heading_rad = math.atan2(self._state.v[0], self._state.v[1])
            self._last_match = self._tracker.update_with_position(
                east=float(self._state.p[0]),
                north=float(self._state.p[1]),
                heading_rad=heading_rad,
                speed_ms=speed,
            )

        return self

    # ------------------------------------------------------------------
    # TCN speed update
    # ------------------------------------------------------------------

    def process_tcn(self, measurement: TcnSpeedMeasurement) -> "RouteAwareNavigator":
        """Apply TCN forward speed measurement to the ESKF.

        TCN speed ONLY constrains the forward velocity component.
        It does NOT overwrite position, heading, or route progress.

        Parameters
        ----------
        measurement : TcnSpeedMeasurement
            TCN forward-speed measurement.

        Returns
        -------
        self (for chaining)
        """
        if not self._initialized:
            return self

        state_up, P_up, accepted, _ = update_tcn_speed(
            self._state, self._P, measurement, phone_to_veh=self.phone_to_veh
        )
        if accepted:
            self._state = state_up
            self._P = P_up
            if "TCN" not in self._active_constraints:
                self._active_constraints.append("TCN")

        return self

    # ------------------------------------------------------------------
    # ZUPT (stationary)
    # ------------------------------------------------------------------

    def process_zupt(self, timestamp: float) -> "RouteAwareNavigator":
        """Apply Zero-Velocity Update when vehicle is confirmed stationary.

        Parameters
        ----------
        timestamp : float
            Current timestamp.

        Returns
        -------
        self (for chaining)
        """
        if not self._initialized:
            return self

        zupt_meas = ZuptMeasurement(
            timestamp=timestamp,
            std_velocity=self.config.constraints.zupt_std_velocity,
        )
        state_up, P_up, accepted, _ = update_zupt(
            self._state, self._P, zupt_meas,
            nis_confidence=self.config.constraints.zupt_nis_confidence,
        )
        if accepted:
            self._state = state_up
            self._P = P_up
            if "ZUPT" not in self._active_constraints:
                self._active_constraints.append("ZUPT")

        return self

    # ------------------------------------------------------------------
    # Route constraint application
    # ------------------------------------------------------------------

    def apply_route_constraints(self, gyro_phone: Optional[np.ndarray] = None) -> "RouteAwareNavigator":
        """Apply route lateral and heading constraints to the ESKF.

        This should be called after IMU propagation and before emitting the
        NavigationState. It is a no-op if no route is configured or if
        route confidence is too low.

        Parameters
        ----------
        gyro_phone : np.ndarray, optional
            Current gyro measurement in phone frame (for yaw-rate inhibit of
            heading constraint during turns). Defaults to zero.

        Returns
        -------
        self (for chaining)
        """
        if not self._initialized or self._tracker is None or not self.route:
            return self

        cfg = self.config

        # Update route match from current ESKF position
        speed = _speed_from_velocity(self._state.v)
        heading_rad = math.atan2(self._state.v[0], self._state.v[1]) if speed > 0.2 else 0.0
        match = self._tracker.update_with_position(
            east=float(self._state.p[0]),
            north=float(self._state.p[1]),
            heading_rad=heading_rad,
            speed_ms=speed,
        )
        self._last_match = match

        # Only apply constraints when GNSS is lost OR config asks for it during GNSS too
        gnss_lost = self._gnss_status in (GnssStatus.LOST, GnssStatus.RECOVERING)
        should_apply = gnss_lost or cfg.route_constraint_during_gnss

        if not should_apply or not match or match.confidence < cfg.route_constraint.min_lateral_confidence:
            return self

        # Get matched segment geometry
        seg_idx = match.segment_index
        if seg_idx >= len(self.route.segments):
            return self
        seg = self.route.segments[seg_idx]

        segment_normal = seg.unit_normal  # 2D [n_east, n_north]
        segment_start = np.array([seg.start.east, seg.start.north], dtype=np.float64)

        # 1. Lateral constraint
        state_lat, P_lat, accepted_lat, _ = update_route_lateral(
            self._state, self._P, match,
            segment_normal_enu=segment_normal,
            segment_start_enu=segment_start,
            config=cfg.route_constraint,
        )
        if accepted_lat:
            self._state = state_lat
            self._P = P_lat
            if "ROUTE_LAT" not in self._active_constraints:
                self._active_constraints.append("ROUTE_LAT")

        # 2. Heading constraint (GNSS outage only, and not mid-turn)
        if gnss_lost:
            yaw_rate = 0.0
            if gyro_phone is not None:
                yaw_rate = _yaw_rate_from_gyro(gyro_phone, self.phone_to_veh.R_v_p)

            state_hdg, P_hdg, accepted_hdg, _ = update_route_heading(
                self._state, self._P, match,
                phone_to_veh_R=self.phone_to_veh.R_v_p,
                yaw_rate_rad_s=yaw_rate,
                config=cfg.route_constraint,
            )
            if accepted_hdg:
                self._state = state_hdg
                self._P = P_hdg
                if "ROUTE_HDG" not in self._active_constraints:
                    self._active_constraints.append("ROUTE_HDG")

        return self

    # ------------------------------------------------------------------
    # GNSS status update when no fix is received
    # ------------------------------------------------------------------

    def _update_gnss_status_no_fix(self, current_timestamp: float) -> None:
        """Update GNSS status when no fix is received at this timestep."""
        age = current_timestamp - self._last_gnss_timestamp
        if age >= self.config.gnss_lost_timeout_s:
            self._gnss_status = GnssStatus.LOST

    def tick_no_gnss(self, timestamp: float) -> None:
        """Call this every timestep when no GNSS measurement is available."""
        self._update_gnss_status_no_fix(timestamp)

    # ------------------------------------------------------------------
    # NavigationState output
    # ------------------------------------------------------------------

    def get_navigation_state(self) -> NavigationState:
        """Build and return the current NavigationState.

        Returns
        -------
        NavigationState
            Complete navigation state with position, velocity, quality,
            route progress, and active constraints.
        """
        if not self._initialized or self._state is None:
            return NavigationState()

        state = self._state
        speed = _speed_from_velocity(state.v)
        heading_deg = _heading_from_velocity(state.v)

        # Convert ENU to lat/lon
        try:
            lat, lon = enu_to_latlon(float(state.p[0]), float(state.p[1]), self.origin)
        except Exception:
            lat, lon = float(self.origin.lat), float(self.origin.lon)

        # Covariance: extract 3x3 position block
        pos_cov = self._P[0:3, 0:3].copy()

        # Confidence: heuristic from position covariance trace
        pos_std = math.sqrt(max(0.0, float(np.trace(pos_cov[:2, :2])) / 2.0))
        confidence = max(0.0, min(1.0, 1.0 / (1.0 + pos_std / 10.0)))

        # Estimation mode
        gnss_lost = self._gnss_status in (GnssStatus.LOST,)
        gnss_recovering = self._gnss_status == GnssStatus.RECOVERING
        has_tcn = "TCN" in self._active_constraints
        has_route = self._last_match is not None and self._last_match.confidence > 0.2

        if gnss_recovering:
            mode = EstimationMode.RECOVERING
        elif gnss_lost:
            if has_route:
                mode = EstimationMode.ROUTE_AWARE_INS
            elif has_tcn:
                mode = EstimationMode.INS_TCN
            else:
                mode = EstimationMode.INS
        else:
            mode = EstimationMode.FUSED

        # Route progress fields
        if self._last_match is not None:
            match = self._last_match
            seg_idx = match.segment_index
            prog_m = match.progress_m
            dist_man = match.distance_to_next_maneuver_m
            cur_bearing = match.current_bearing_deg
            next_bearing = match.next_bearing_deg
            lat_err = match.lateral_error_m

            # Heading error: route bearing vs vehicle heading (CW from North)
            route_heading_deg = (90.0 - math.degrees(
                _bearing_to_heading_rad_local(cur_bearing)
            )) % 360.0
            hdg_err = _angle_diff_deg(heading_deg, cur_bearing)
        else:
            seg_idx = 0
            prog_m = 0.0
            dist_man = 0.0
            cur_bearing = 0.0
            next_bearing = 0.0
            lat_err = 0.0
            hdg_err = 0.0

        return NavigationState(
            timestamp=state.timestamp,
            east=float(state.p[0]),
            north=float(state.p[1]),
            up=float(state.p[2]),
            latitude=lat,
            longitude=lon,
            velocity_east=float(state.v[0]),
            velocity_north=float(state.v[1]),
            velocity_up=float(state.v[2]),
            speed=speed,
            heading_deg=heading_deg,
            gnss_status=self._gnss_status,
            estimation_mode=mode,
            position_covariance=pos_cov,
            confidence=confidence,
            route_segment_index=seg_idx,
            route_progress_m=prog_m,
            distance_to_next_maneuver_m=dist_man,
            current_route_bearing_deg=cur_bearing,
            next_route_bearing_deg=next_bearing,
            lateral_route_error_m=lat_err,
            heading_route_error_deg=hdg_err,
            active_constraints=list(self._active_constraints),
        )

    def reset_active_constraints(self) -> None:
        """Clear per-timestep active constraint labels. Call at the start of each cycle."""
        self._active_constraints = []


# --------------------------------------------------------------------------
# Private utility (local to this module only)
# --------------------------------------------------------------------------

def _bearing_to_heading_rad_local(bearing_deg: float) -> float:
    """Convert CW-from-North bearing (deg) to CCW-from-East heading (rad)."""
    return math.radians(90.0 - bearing_deg)


def _angle_diff_deg(a: float, b: float) -> float:
    """Smallest signed difference a - b, wrapped to (-180, 180]."""
    return ((a - b + 180.0) % 360.0) - 180.0
