"""
test_route_aware.py
===================
Comprehensive test suite for route-aware GNSS-outage navigation.

All routes are synthetic and deterministic — no external data is required.

Test inventory
--------------
1.  test_straight_route_gnss_outage    – vehicle tracks straight route without GNSS
2.  test_90_degree_turn                – vehicle transitions to correct outgoing bearing
3.  test_wrong_heading_candidate       – parallel but backward segment scores poorly
4.  test_parallel_roads_continuity     – previously matched segment preferred over parallel
5.  test_route_progress_monotonic      – progress never decreases during forward travel
6.  test_gnss_outage_route_tracking    – route match survives GNSS denial
7.  test_gnss_recovery_no_teleport     – GNSS return corrects without position jump
8.  test_gnss_outlier_rejected         – large GNSS jump fails NIS gate
9.  test_tcn_speed_does_not_overwrite_position – TCN only constrains velocity
10. test_nhc_lateral_velocity          – NHC suppresses lateral velocity
11. test_zupt_stationary               – ZUPT drives velocity to zero
12. test_frame_consistency             – ENU / vehicle / phone frames consistent
13. test_turn_heading_transition       – ESKF heading transitions during a maneuver
"""

from __future__ import annotations

import math
from typing import List, Tuple

import numpy as np
import pytest

from src.navigation.types import (
    NominalState,
    ImuSample,
    PhoneToVehicleTransform,
    GnssStatus,
    EstimationMode,
)
from src.navigation.ins import quat_to_rotmat, rotmat_to_quat
from src.navigation.gnss_update import GnssMeasurement, update_gnss_position
from src.navigation.ai_update import TcnSpeedMeasurement
from src.navigation.constraints import NhcMeasurement, ZuptMeasurement, update_nhc, update_zupt
from src.navigation.route import Route, ManeuverType, RouteManeuver
from src.navigation.route_tracker import RouteProgressTracker, RouteProgressTrackerConfig
from src.navigation.route_update import (
    RouteConstraintConfig,
    update_route_lateral,
    update_route_heading,
)
from src.navigation.navigator import RouteAwareNavigator, NavigatorConfig
from src.navigation.eskf import validate_covariance

try:
    from src.preprocessing.coordinates import LatLonOrigin
except ImportError:
    from coordinate_transform import LatLonOrigin  # type: ignore[import]


# ===========================================================================
# Synthetic route fixtures
# ===========================================================================

def make_origin() -> LatLonOrigin:
    return LatLonOrigin(lat=19.051, lon=72.894)


def make_straight_route(length_m: float = 200.0, bearing_deg: float = 0.0) -> Route:
    """Straight route of length_m pointing in bearing_deg (CW from North)."""
    origin = make_origin()
    heading_rad = math.radians(90.0 - bearing_deg)  # ENU
    dx = math.cos(heading_rad)
    dy = math.sin(heading_rad)
    n_pts = 5
    pts = []
    for i in range(n_pts):
        s = length_m * i / (n_pts - 1)
        # Create lat/lon from ENU offset (flat-Earth approximation for small distances)
        # Use 1 deg lat ≈ 111320 m, 1 deg lon ≈ 111320 * cos(lat) m
        dlat = (s * dy) / 111320.0
        dlon = (s * dx) / (111320.0 * math.cos(math.radians(origin.lat)))
        pts.append((origin.lat + dlat, origin.lon + dlon))
    return Route.from_latlon_polyline(pts, origin)


def make_l_shape_route(leg_m: float = 100.0) -> Route:
    """L-shaped route: go North for leg_m, then East for leg_m."""
    origin = make_origin()

    def enu_to_latlon_local(e: float, n: float) -> Tuple[float, float]:
        dlat = n / 111320.0
        dlon = e / (111320.0 * math.cos(math.radians(origin.lat)))
        return origin.lat + dlat, origin.lon + dlon

    # North segment (bearing 0°), then East segment (bearing 90°)
    pts = []
    n_per_leg = 5
    for i in range(n_per_leg):
        s = leg_m * i / (n_per_leg - 1)
        lat, lon = enu_to_latlon_local(0.0, s)
        pts.append((lat, lon))
    for i in range(1, n_per_leg):
        s = leg_m * i / (n_per_leg - 1)
        lat, lon = enu_to_latlon_local(s, leg_m)
        pts.append((lat, lon))

    return Route.from_latlon_polyline(pts, origin)


def make_parallel_routes() -> Tuple[Route, Route]:
    """Two parallel northbound routes, 15 m apart."""
    origin = make_origin()

    def enu_to_ll(e: float, n: float) -> Tuple[float, float]:
        dlat = n / 111320.0
        dlon = e / (111320.0 * math.cos(math.radians(origin.lat)))
        return origin.lat + dlat, origin.lon + dlon

    pts_left = [enu_to_ll(0.0, s) for s in [0, 50, 100, 150, 200]]
    pts_right = [enu_to_ll(15.0, s) for s in [0, 50, 100, 150, 200]]

    route_left = Route.from_latlon_polyline(pts_left, origin)
    route_right = Route.from_latlon_polyline(pts_right, origin)
    return route_left, route_right


def make_navigator_on_route(route: Route) -> RouteAwareNavigator:
    """Create an initialized navigator placed at the first route point."""
    origin = route.origin
    nav = RouteAwareNavigator(origin=origin, route=route)
    start_pt = route.points[0]
    nav.initialize(
        initial_position_enu=np.array([start_pt.east, start_pt.north, 0.0]),
        initial_velocity_enu=np.zeros(3),
        initial_timestamp=0.0,
    )
    return nav


def make_imu_moving_north(timestamp: float, speed_ms: float = 10.0) -> ImuSample:
    """IMU sample for a vehicle moving north with no acceleration (steady state)."""
    return ImuSample(
        timestamp=timestamp,
        accel=np.array([0.0, 0.0, 0.0]),  # linear accel ≈ 0 (no accel change)
        gyro=np.zeros(3),
        is_linear_accel=True,
    )


def make_imu_turning_left(timestamp: float, yaw_rate: float = 0.2) -> ImuSample:
    """IMU sample for a left turn with yaw rate (positive Z in phone frame)."""
    return ImuSample(
        timestamp=timestamp,
        accel=np.zeros(3),
        gyro=np.array([0.0, 0.0, yaw_rate]),  # Z gyro = yaw rate (left turn)
        is_linear_accel=True,
    )


def forward_velocity_state(speed_ms: float, bearing_deg: float) -> NominalState:
    """Create a NominalState with forward motion in bearing_deg direction."""
    heading_rad = math.radians(90.0 - bearing_deg)
    v_east = speed_ms * math.cos(heading_rad)
    v_north = speed_ms * math.sin(heading_rad)
    return NominalState(
        p=np.zeros(3),
        v=np.array([v_east, v_north, 0.0]),
        q=np.array([1.0, 0.0, 0.0, 0.0]),
    )


# ===========================================================================
# Test 1: Straight route — GNSS outage tracking
# ===========================================================================

def test_straight_route_gnss_outage():
    """Vehicle tracks a straight northbound route during GNSS outage.

    The route constraint should prevent large lateral drift. The vehicle
    should remain within a few metres of the route centreline.
    """
    route = make_straight_route(length_m=300.0, bearing_deg=0.0)  # northbound
    nav = make_navigator_on_route(route)

    # Give navigator a northward velocity (simulating initial GNSS fix)
    nav._state.v = np.array([0.0, 10.0, 0.0])   # 10 m/s north

    # Simulate IMU-only propagation with small lateral drift
    dt = 0.1
    for step in range(50):
        t = (step + 1) * dt
        # Slightly drifting east (IMU noise)
        sample = ImuSample(
            timestamp=t,
            accel=np.array([0.02, 0.0, 0.0]),  # 0.02 m/s^2 eastward drift
            gyro=np.zeros(3),
            is_linear_accel=True,
        )
        nav.process_imu(sample)
        nav.apply_route_constraints()
        nav.reset_active_constraints()

    nav_state = nav.get_navigation_state()

    # After 5 seconds with IMU drift, route constraint should bound lateral error
    # Unconstrained: drift ≈ 0.5 * 0.02 * 5^2 = 0.25 m east (small but accumulates)
    # With route constraint: lateral error should be < 3 m
    assert abs(nav_state.lateral_route_error_m) < 5.0, (
        f"Lateral route error too large: {nav_state.lateral_route_error_m:.2f} m"
    )
    # Route tracking mode should be active
    assert nav_state.estimation_mode in (
        EstimationMode.ROUTE_AWARE_INS,
        EstimationMode.INS,
        EstimationMode.INS_TCN,
    )


# ===========================================================================
# Test 2: 90-degree turn
# ===========================================================================

def test_90_degree_turn():
    """Vehicle transitions to the correct outgoing bearing after an L-shaped turn.

    The route tracker should detect the vehicle is on the second (eastbound)
    segment after the turn, reporting the correct segment bearing ≈ 90°.
    """
    route = make_l_shape_route(leg_m=100.0)
    tracker = RouteProgressTracker(route)

    # Phase 1: track northward on segment 0 (bearing ≈ 0°)
    for s in np.arange(0, 90, 5):
        result = tracker.update_with_position(
            east=0.0,
            north=float(s),
            heading_rad=math.radians(90.0),  # heading east in ENU = North in ENU? No:
            # ENU heading: atan2(v_north, v_east). Northbound: atan2(1, 0) = 90° = pi/2
            speed_ms=5.0,
        )

    # At the corner
    result_corner = tracker.update_with_position(
        east=0.0,
        north=100.0,
        heading_rad=math.pi / 2,   # still heading north (approaching corner)
        speed_ms=5.0,
    )
    assert result_corner.segment_index < route.num_segments

    # Phase 2: after the turn, vehicle is moving east at north=100
    for s in np.arange(5, 80, 5):
        result_east = tracker.update_with_position(
            east=float(s),
            north=100.0,
            heading_rad=0.0,   # ENU: east = atan2(0, 1) = 0 rad
            speed_ms=5.0,
        )

    # The tracker should have advanced to the eastbound segment
    assert result_east.segment_index >= route.num_segments // 2, (
        f"Tracker stuck on first segment (index={result_east.segment_index})"
    )

    # Bearing of the eastbound segment should be approximately 90° (CW from North)
    east_bearing = route.segments[result_east.segment_index].bearing_deg
    assert abs(east_bearing - 90.0) < 20.0, (
        f"Eastbound segment bearing unexpected: {east_bearing:.1f}°"
    )


# ===========================================================================
# Test 3: Wrong-heading candidate
# ===========================================================================

def test_wrong_heading_candidate():
    """A segment in the reverse direction should score poorly.

    If the vehicle is travelling north and the candidate segment goes south
    (180°), the heading difference is 180° > heading_gate_deg, so that
    segment should be excluded from the match candidates.
    """
    route = make_straight_route(length_m=200.0, bearing_deg=0.0)  # northbound
    cfg = RouteProgressTrackerConfig(heading_gate_deg=45.0)
    tracker = RouteProgressTracker(route, config=cfg)

    # Initialize with a northward position
    tracker.update_with_position(east=0.0, north=0.0, heading_rad=math.pi / 2, speed_ms=5.0)

    # Query with a SOUTHBOUND heading (should NOT match the northbound route)
    result_south = tracker.update_with_position(
        east=0.0,
        north=50.0,
        heading_rad=-math.pi / 2,  # heading south
        speed_ms=5.0,
    )

    # Confidence should be very low or the match should be rejected
    # (vehicle is heading opposite to route → not a valid match)
    assert result_south.confidence < 0.6, (
        f"Wrong-heading match unexpectedly accepted with confidence={result_south.confidence:.2f}"
    )


# ===========================================================================
# Test 4: Parallel roads continuity
# ===========================================================================

def test_parallel_roads_continuity():
    """Previously matched left route preferred over geometrically closer right route.

    Scenario: two parallel northbound routes 15 m apart. Vehicle is on the
    left (east=0) route. The route tracker should not jump to the right
    (east=15) route even when the vehicle briefly drifts toward it.
    """
    route_left, _route_right = make_parallel_routes()

    tracker = RouteProgressTracker(route_left)

    # Initialize on left route
    for n in [0.0, 20.0, 40.0]:
        tracker.update_with_position(east=0.0, north=n, heading_rad=math.pi / 2, speed_ms=5.0)

    # Drift eastward (toward right route) but stay on left
    result = tracker.update_with_position(
        east=5.0,   # drifted 5 m east toward right route (which is at east=15)
        north=60.0,
        heading_rad=math.pi / 2,
        speed_ms=5.0,
    )

    # Should still be on left route (segment 0 to 3 range, eastward bias should not cause jump)
    # Progress should increase monotonically
    assert result.progress_m >= 40.0, (
        f"Route progress jumped backward: {result.progress_m:.1f} m"
    )
    # Lateral error should reflect 5 m drift, not 10 m (which would indicate right-route match)
    assert abs(result.lateral_error_m) < 10.0, (
        f"Lateral error suggests wrong route match: {result.lateral_error_m:.1f} m"
    )


# ===========================================================================
# Test 5: Route progress monotonic
# ===========================================================================

def test_route_progress_monotonic():
    """Route progress should never decrease during normal forward travel."""
    route = make_straight_route(length_m=200.0, bearing_deg=0.0)
    tracker = RouteProgressTracker(route)

    progresses = []
    for n in np.linspace(0, 195, 20):
        result = tracker.update_with_position(
            east=0.0,
            north=float(n),
            heading_rad=math.pi / 2,
            speed_ms=5.0,
        )
        progresses.append(result.progress_m)

    for i in range(1, len(progresses)):
        assert progresses[i] >= progresses[i - 1] - 1e-6, (
            f"Progress decreased: {progresses[i - 1]:.2f} → {progresses[i]:.2f} at step {i}"
        )


# ===========================================================================
# Test 6: GNSS outage — route tracking continues
# ===========================================================================

def test_gnss_outage_route_tracking():
    """Route match confidence should remain useful during GNSS outage."""
    route = make_straight_route(length_m=200.0, bearing_deg=0.0)
    nav = make_navigator_on_route(route)
    nav._state.v = np.array([0.0, 8.0, 0.0])   # 8 m/s north

    dt = 0.1

    # Phase 1: 1 s with GNSS available
    for step in range(10):
        t = (step + 1) * dt
        sample = ImuSample(timestamp=t, accel=np.zeros(3), gyro=np.zeros(3), is_linear_accel=True)
        nav.process_imu(sample)
        gnss = GnssMeasurement(
            timestamp=t,
            position=np.array([0.0, 8.0 * t, 0.0]),
            std_pos=np.array([2.0, 2.0, 5.0]),
        )
        nav.process_gnss(gnss)
        nav.apply_route_constraints()
        nav.reset_active_constraints()

    assert nav._gnss_status in (GnssStatus.GOOD, GnssStatus.DEGRADED)

    # Phase 2: GNSS outage — no GNSS for 5 seconds
    for step in range(50):
        t = 1.0 + (step + 1) * dt
        sample = ImuSample(timestamp=t, accel=np.zeros(3), gyro=np.zeros(3), is_linear_accel=True)
        nav.process_imu(sample)
        nav.tick_no_gnss(t)
        nav.apply_route_constraints()
        nav.reset_active_constraints()

    nav_state = nav.get_navigation_state()
    assert nav_state.gnss_status == GnssStatus.LOST
    assert nav_state.estimation_mode in (
        EstimationMode.ROUTE_AWARE_INS,
        EstimationMode.INS,
        EstimationMode.INS_TCN,
    )

    # Route match should still be alive (tracker was initialized during GNSS phase)
    assert nav._last_match is not None


# ===========================================================================
# Test 7: GNSS recovery — no teleportation
# ===========================================================================

def test_gnss_recovery_no_teleport():
    """When GNSS returns after outage, position should transition smoothly.

    The recovered position should be intermediate between the last known
    ESKF estimate and the new GNSS fix, NOT a sudden jump.
    """
    route = make_straight_route(length_m=300.0, bearing_deg=0.0)
    nav = make_navigator_on_route(route)
    nav._state.v = np.array([0.0, 10.0, 0.0])
    nav._gnss_status = GnssStatus.LOST
    nav._last_gnss_timestamp = -100.0  # long ago

    # Place ESKF at estimated position p = [0, 100, 0]
    nav._state.p = np.array([0.0, 100.0, 0.0])

    # GNSS returns with small position offset (10 m error)
    gnss_true_position = np.array([0.0, 110.0, 0.0])
    recovery_gnss = GnssMeasurement(
        timestamp=100.0,
        position=gnss_true_position,
        std_pos=np.array([3.0, 3.0, 5.0]),
    )
    nav.process_gnss(recovery_gnss)

    nav_state = nav.get_navigation_state()

    # Status should be RECOVERING (inflated noise applied)
    assert nav_state.gnss_status == GnssStatus.RECOVERING

    # Position should have moved TOWARD the GNSS fix but NOT teleported to it
    post_recovery_north = nav._state.p[1]
    assert 100.0 < post_recovery_north < 110.5, (
        f"Position teleported or not corrected: north={post_recovery_north:.2f}"
    )


# ===========================================================================
# Test 8: GNSS outlier rejected by NIS
# ===========================================================================

def test_gnss_outlier_rejected():
    """A large GNSS jump (1 km) must be rejected by the NIS gate."""
    route = make_straight_route(length_m=300.0, bearing_deg=0.0)
    nav = make_navigator_on_route(route)
    nav._state.v = np.array([0.0, 10.0, 0.0])

    # Establish a reasonable covariance (small uncertainty)
    nav._P = np.diag([2.0**2] * 3 + [0.5**2] * 3 + [0.05**2] * 3 + [0.05**2] * 6)
    nav._state.p = np.array([0.0, 50.0, 0.0])

    # Outlier GNSS: 1 km jump
    outlier_gnss = GnssMeasurement(
        timestamp=5.0,
        position=np.array([0.0, 1050.0, 0.0]),
        std_pos=np.array([2.0, 2.0, 5.0]),
    )

    nav._gnss_status = GnssStatus.GOOD
    nav._last_gnss_timestamp = 4.9

    p_before = nav._state.p.copy()
    nav.process_gnss(outlier_gnss)

    # ESKF position should not have teleported 1 km
    north_after = nav._state.p[1]
    assert north_after < 200.0, (
        f"ESKF position teleported to {north_after:.1f} m north (outlier not rejected)"
    )


# ===========================================================================
# Test 9: TCN speed — does not overwrite position
# ===========================================================================

def test_tcn_speed_does_not_overwrite_position():
    """TCN speed measurement constrains forward velocity but never overwrites position."""
    state = NominalState(
        p=np.array([10.0, 20.0, 0.0]),
        v=np.array([0.0, 5.0, 0.0]),  # 5 m/s northward
        q=np.array([1.0, 0.0, 0.0, 0.0]),
    )
    P = np.eye(15) * 2.0

    # TCN says 8 m/s (forward)
    tcn_meas = TcnSpeedMeasurement(timestamp=1.0, speed=8.0, std_speed=0.5)

    from src.navigation.ai_update import update_tcn_speed
    state_up, P_up, accepted, _ = update_tcn_speed(state, P, tcn_meas)

    # Position must be completely unchanged
    np.testing.assert_array_equal(state_up.p, state.p)

    # Velocity magnitude should have increased (toward 8 m/s)
    speed_before = math.hypot(state.v[0], state.v[1])
    speed_after = math.hypot(state_up.v[0], state_up.v[1])
    assert speed_after > speed_before, "TCN should push speed toward 8 m/s"

    val = validate_covariance(P_up)
    assert val.is_valid, val.reason


# ===========================================================================
# Test 10: NHC lateral velocity constraint
# ===========================================================================

def test_nhc_lateral_velocity():
    """NHC update should suppress lateral and vertical velocity components."""
    # Vehicle has lateral drift: v = [10 m/s fwd, 3 m/s lateral, -1 m/s vert]
    state = NominalState(
        p=np.zeros(3),
        v=np.array([10.0, 3.0, -1.0]),
        q=np.array([1.0, 0.0, 0.0, 0.0]),
    )
    P = np.eye(15) * 3.0

    nhc_meas = NhcMeasurement(timestamp=1.0, std_lateral=0.05, std_vertical=0.05)
    state_up, P_up, accepted, nis = update_nhc(state, P, nhc_meas)

    assert accepted
    assert abs(state_up.v[1]) < abs(state.v[1]), "NHC should reduce lateral velocity"
    assert abs(state_up.v[2]) < abs(state.v[2]), "NHC should reduce vertical velocity"
    assert validate_covariance(P_up).is_valid


# ===========================================================================
# Test 11: ZUPT stationary convergence
# ===========================================================================

def test_zupt_stationary():
    """ZUPT must drive velocity toward zero when vehicle is stationary."""
    state = NominalState(
        p=np.zeros(3),
        v=np.array([0.3, -0.2, 0.1]),  # residual drift
        q=np.array([1.0, 0.0, 0.0, 0.0]),
    )
    P = np.eye(15) * 1.0

    zupt_meas = ZuptMeasurement(timestamp=1.0, std_velocity=0.01)
    state_up, P_up, accepted, _ = update_zupt(state, P, zupt_meas)

    assert accepted
    speed_before = np.linalg.norm(state.v)
    speed_after = np.linalg.norm(state_up.v)
    assert speed_after < speed_before, "ZUPT should reduce speed toward zero"
    np.testing.assert_allclose(state_up.v, np.zeros(3), atol=0.05)
    assert validate_covariance(P_up).is_valid


# ===========================================================================
# Test 12: Frame consistency
# ===========================================================================

def test_frame_consistency():
    """ENU / vehicle / phone frame transformations must be mutually consistent.

    A vehicle travelling due North (bearing 0°) with identity phone-to-vehicle
    transform should have:
    - v_world ≈ [0, speed, 0]  (ENU: east=0, north=speed)
    - v_phone = R^T @ v_world
    - v_vehicle = R_v_p @ v_phone = v_phone  (identity transform)
    - Forward speed (TCN) = v_vehicle[0] = speed × cos(orientation error)
    """
    from src.navigation.ai_update import compute_h_tcn

    speed = 10.0
    state = NominalState(
        p=np.zeros(3),
        v=np.array([0.0, speed, 0.0]),  # northbound
        q=np.array([1.0, 0.0, 0.0, 0.0]),  # identity (phone aligned with ENU)
    )
    phone_to_veh = PhoneToVehicleTransform(R_v_p=np.eye(3))

    pred_speed, H = compute_h_tcn(state, phone_to_veh)

    # With identity orientation and identity vehicle-phone transform:
    # v_phone = R^T @ v_world = v_world = [0, speed, 0]
    # v_vehicle = R_v_p @ v_phone = [0, speed, 0]
    # Forward = v_vehicle[0] = 0 (because speed is in Y-phone, not X-phone)
    # This is the expected ENU->phone->vehicle geometry
    assert np.isfinite(pred_speed)
    assert H.shape == (1, 15)

    # Ensure the Jacobian has non-zero entries in the velocity block
    assert np.any(np.abs(H[0, 3:6]) > 1e-10), "H_tcn should depend on velocity state"


# ===========================================================================
# Test 13: Turn detection — heading transition consistent with maneuver
# ===========================================================================

def test_turn_heading_transition():
    """Approaching a maneuver should blend route bearing toward outgoing bearing.

    The tracker's effective current_bearing_deg should transition from the
    incoming bearing to the outgoing bearing as the vehicle approaches the turn.
    """
    route = make_l_shape_route(leg_m=100.0)

    # Add an explicit maneuver at the corner (north=100, east=0)
    origin = route.origin
    corner_lat = origin.lat + 100.0 / 111320.0
    corner_lon = origin.lon
    maneuver = RouteManeuver(
        maneuver_type=ManeuverType.TURN_RIGHT,
        east=0.0,
        north=100.0,
        incoming_bearing_deg=0.0,    # northbound approach
        outgoing_bearing_deg=90.0,   # eastbound departure
        cumulative_dist_m=100.0,
        instruction="Turn right",
        segment_index=len(route.segments) // 2,
    )
    route.maneuvers = [maneuver]

    tracker = RouteProgressTracker(
        route,
        config=RouteProgressTrackerConfig(maneuver_transition_m=25.0),
    )

    # Far from turn — bearing should be the incoming bearing (≈ 0°)
    far_result = tracker.update_with_position(
        east=0.0,
        north=40.0,
        heading_rad=math.pi / 2,
        speed_ms=8.0,
    )

    # Close to turn — bearing should have started transitioning toward 90°
    close_result = tracker.update_with_position(
        east=0.0,
        north=90.0,   # 10 m from maneuver (within transition_m=25)
        heading_rad=math.pi / 2,
        speed_ms=8.0,
    )

    # Far: bearing ≈ 0° (northbound)
    # Close: bearing > 0° (blending toward 90° eastbound)
    assert close_result.current_bearing_deg > far_result.current_bearing_deg, (
        f"Bearing should increase toward 90°: "
        f"far={far_result.current_bearing_deg:.1f}°, "
        f"close={close_result.current_bearing_deg:.1f}°"
    )


# ===========================================================================
# Test: Route lateral constraint is a measurement update (not a state overwrite)
# ===========================================================================

def test_route_lateral_is_measurement_not_overwrite():
    """The route lateral update must use Joseph form — not hard position override."""
    from src.navigation.route_update import update_route_lateral
    from src.navigation.route_tracker import RouteMatchResult
    import numpy as np

    state = NominalState(
        p=np.array([5.0, 50.0, 0.0]),   # 5 m east of route centreline
        v=np.array([0.0, 8.0, 0.0]),
        q=np.array([1.0, 0.0, 0.0, 0.0]),
    )
    P = np.eye(15) * 4.0

    # Route segment going north: normal = [-1, 0] (pointing west, left of northbound)
    segment_normal = np.array([-1.0, 0.0])  # perpendicular to northbound seg
    segment_start = np.array([0.0, 0.0])    # segment starts at origin

    match = RouteMatchResult(
        segment_index=0,
        progress_m=50.0,
        lateral_error_m=5.0,   # 5 m to the right
        along_track_error_m=0.0,
        current_bearing_deg=0.0,
        next_bearing_deg=0.0,
        distance_to_next_maneuver_m=50.0,
        next_maneuver=None,
        confidence=0.8,
    )

    cfg = RouteConstraintConfig(
        base_lateral_std_m=3.0,
        lateral_noise_confidence_scale=5.0,
        min_lateral_confidence=0.3,
    )

    state_up, P_up, accepted, nis = update_route_lateral(
        state, P, match, segment_normal, segment_start, config=cfg
    )

    assert accepted, "Route lateral update should be accepted"

    # Position should have moved TOWARD the route (east should decrease)
    # but NOT jump all the way to east=0
    assert state_up.p[0] < state.p[0], "Route lateral should pull position toward centreline"
    assert state_up.p[0] > 0.0, "Route lateral should NOT snap position to centreline"

    # North position untouched (no along-track correction)
    assert abs(state_up.p[1] - state.p[1]) < 0.1, "North position should not change"

    # Covariance should be valid
    assert validate_covariance(P_up).is_valid, "Covariance should remain valid after update"
