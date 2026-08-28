"""Unit tests for GNSS, TCN speed, NHC, and ZUPT measurement updates, NIS gating, and numerical Jacobians."""

import numpy as np
import pytest
from src.navigation.types import NominalState, PhoneToVehicleTransform
from src.navigation.eskf import validate_covariance, inject_error_and_reset
from src.navigation.gnss_update import (
    GnssMeasurement,
    update_gnss_position,
    update_gnss_velocity,
)
from src.navigation.ai_update import (
    TcnSpeedMeasurement,
    update_tcn_speed,
    compute_h_tcn,
)
from src.navigation.constraints import (
    NhcMeasurement,
    ZuptMeasurement,
    update_nhc,
    update_zupt,
    compute_h_nhc,
)


def test_gnss_position_update_convergence():
    """Verify GNSS position update reduces position variance and corrects position error."""
    state = NominalState(
        p=np.array([10.0, -5.0, 2.0]),
        v=np.zeros(3),
        q=np.array([1.0, 0.0, 0.0, 0.0]),
    )
    P = np.eye(15, dtype=np.float64) * 10.0

    gnss_meas = GnssMeasurement(
        timestamp=1.0,
        position=np.array([0.0, 0.0, 0.0]),
        std_pos=np.array([1.0, 1.0, 2.0]),
    )

    state_up, P_up, accepted, nis = update_gnss_position(state, P, gnss_meas)

    assert accepted
    assert nis < 15.0
    assert np.linalg.norm(state_up.p) < np.linalg.norm(state.p)
    assert np.all(np.diag(P_up[0:3, 0:3]) < np.diag(P[0:3, 0:3]))

    val_res = validate_covariance(P_up)
    assert val_res.is_valid, val_res.reason


def test_gnss_velocity_update_convergence():
    """Verify GNSS velocity update reduces velocity variance and corrects velocity error."""
    state = NominalState(
        p=np.zeros(3),
        v=np.array([5.0, 0.0, 0.0]),
        q=np.array([1.0, 0.0, 0.0, 0.0]),
    )
    P = np.eye(15, dtype=np.float64) * 2.0

    gnss_meas = GnssMeasurement(
        timestamp=1.0,
        velocity=np.array([10.0, 0.0, 0.0]),
        std_vel=np.array([0.5, 0.5, 1.0]),
    )

    state_up, P_up, accepted, nis = update_gnss_velocity(state, P, gnss_meas)

    assert accepted
    assert state_up.v[0] > state.v[0]
    assert np.all(np.diag(P_up[3:6, 3:6]) < np.diag(P[3:6, 3:6]))

    val_res = validate_covariance(P_up)
    assert val_res.is_valid, val_res.reason


def test_nis_outlier_rejection():
    """Verify NIS gate rejects extreme outlier measurement and leaves state/covariance unchanged."""
    state = NominalState(
        p=np.array([0.0, 0.0, 0.0]),
        v=np.zeros(3),
        q=np.array([1.0, 0.0, 0.0, 0.0]),
    )
    P = np.eye(15, dtype=np.float64) * 0.1

    outlier_gnss = GnssMeasurement(
        timestamp=1.0,
        position=np.array([1000.0, 1000.0, 1000.0]),
        std_pos=np.array([1.0, 1.0, 1.0]),
    )

    state_up, P_up, accepted, nis = update_gnss_position(state, P, outlier_gnss, nis_confidence=0.999)

    assert not accepted
    assert nis > 100.0
    np.testing.assert_allclose(state_up.p, state.p)
    np.testing.assert_allclose(P_up, P)


def test_tcn_forward_speed_update():
    """Verify TCN forward speed update corrects forward velocity in vehicle frame."""
    state = NominalState(
        p=np.zeros(3),
        v=np.array([5.0, 0.0, 0.0]),
        q=np.array([1.0, 0.0, 0.0, 0.0]),
    )
    P = np.eye(15, dtype=np.float64) * 4.0

    tcn_meas = TcnSpeedMeasurement(timestamp=1.0, speed=8.0, std_speed=0.5)

    state_up, P_up, accepted, nis = update_tcn_speed(state, P, tcn_meas)

    assert accepted
    assert state_up.v[0] > state.v[0]

    val_res = validate_covariance(P_up)
    assert val_res.is_valid, val_res.reason


def test_nhc_lateral_vertical_speed_constraint():
    """Verify Non-Holonomic Constraint (NHC) zeroes vehicle lateral and vertical speeds."""
    state = NominalState(
        p=np.zeros(3),
        v=np.array([10.0, 2.0, -1.5]),
        q=np.array([1.0, 0.0, 0.0, 0.0]),
    )
    P = np.eye(15, dtype=np.float64) * 2.0

    nhc_meas = NhcMeasurement(timestamp=1.0, std_lateral=0.05, std_vertical=0.05)

    state_up, P_up, accepted, nis = update_nhc(state, P, nhc_meas)

    assert accepted
    assert abs(state_up.v[1]) < abs(state.v[1])
    assert abs(state_up.v[2]) < abs(state.v[2])

    val_res = validate_covariance(P_up)
    assert val_res.is_valid, val_res.reason


def test_zupt_stationary_velocity_reset():
    """Verify Zero-Velocity Update (ZUPT) zeroes residual velocity drift when stationary."""
    state = NominalState(
        p=np.zeros(3),
        v=np.array([0.5, -0.3, 0.1]),
        q=np.array([1.0, 0.0, 0.0, 0.0]),
    )
    P = np.eye(15, dtype=np.float64) * 0.5

    zupt_meas = ZuptMeasurement(timestamp=1.0, std_velocity=0.01)

    state_up, P_up, accepted, nis = update_zupt(state, P, zupt_meas)

    assert accepted
    np.testing.assert_allclose(state_up.v, np.zeros(3), atol=0.05)

    val_res = validate_covariance(P_up)
    assert val_res.is_valid, val_res.reason


def test_finite_difference_h_tcn_and_h_nhc_verification():
    """Verify analytic Jacobians H_tcn and H_nhc against finite-difference approximation."""
    state = NominalState(
        p=np.array([10.0, 5.0, 0.0]),
        v=np.array([12.0, 2.0, -0.5]),
        q=np.array([0.9239, 0.0, 0.0, 0.3827]),
        ba=np.array([0.01, -0.01, 0.0]),
        bg=np.array([0.001, 0.0, -0.001]),
    )

    R_v_p = np.array([[0.7071, -0.7071, 0], [0.7071, 0.7071, 0], [0, 0, 1]], dtype=np.float64)
    phone_to_veh = PhoneToVehicleTransform(R_v_p=R_v_p)

    eps = 1e-6

    # 1. Verify H_tcn
    pred_tcn0, H_tcn_analytic = compute_h_tcn(state, phone_to_veh=phone_to_veh)
    H_tcn_numeric = np.zeros((1, 15), dtype=np.float64)

    for i in range(15):
        delta_x = np.zeros(15)
        delta_x[i] = eps
        state_pert, _ = inject_error_and_reset(state, delta_x, np.eye(15))
        pred_tcn_pert, _ = compute_h_tcn(state_pert, phone_to_veh=phone_to_veh)

        H_tcn_numeric[0, i] = (pred_tcn_pert - pred_tcn0) / eps

    np.testing.assert_allclose(H_tcn_analytic, H_tcn_numeric, atol=1e-3, rtol=1e-3)

    # 2. Verify H_nhc
    pred_nhc0, H_nhc_analytic = compute_h_nhc(state, phone_to_veh=phone_to_veh)
    H_nhc_numeric = np.zeros((2, 15), dtype=np.float64)

    for i in range(15):
        delta_x = np.zeros(15)
        delta_x[i] = eps
        state_pert, _ = inject_error_and_reset(state, delta_x, np.eye(15))
        pred_nhc_pert, _ = compute_h_nhc(state_pert, phone_to_veh=phone_to_veh)

        H_nhc_numeric[:, i] = (pred_nhc_pert - pred_nhc0) / eps

    np.testing.assert_allclose(H_nhc_analytic, H_nhc_numeric, atol=1e-3, rtol=1e-3)
