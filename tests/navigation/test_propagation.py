"""Unit tests for INS strapdown kinematics, propagation, ESKF covariance prediction, and numerical Jacobians."""

import numpy as np
import pytest
from src.navigation.types import NominalState, ImuSample, PhoneToVehicleTransform
from src.navigation.ins import (
    InsPropagator,
    delta_quat_from_rotation_vector,
    quat_multiply,
    quat_conjugate,
    quat_to_rotmat,
    rotate_vector_by_quat,
)
from src.navigation.eskf import (
    EskfConfig,
    predict_covariance,
    validate_covariance,
    compute_continuous_f_matrix,
    compute_continuous_g_matrix,
    inject_error_and_reset,
)


def test_small_angle_quaternion_propagation():
    """Verify small-angle delta quaternion formulation for theta -> 0 boundary."""
    delta_theta_small = np.array([1e-10, -2e-10, 3e-10])
    dq_small = delta_quat_from_rotation_vector(delta_theta_small)

    delta_theta_mod = np.array([1e-3, -2e-3, 3e-3])
    dq_mod = delta_quat_from_rotation_vector(delta_theta_mod)

    np.testing.assert_allclose(np.linalg.norm(dq_small), 1.0, atol=1e-12)
    np.testing.assert_allclose(np.linalg.norm(dq_mod), 1.0, atol=1e-12)
    np.testing.assert_allclose(dq_small[1:], 0.5 * delta_theta_small, atol=1e-12)


def test_raw_acceleration_with_gravity():
    """Verify raw accelerometer propagation with gravity reaction [0, 0, +9.81]."""
    propagator = InsPropagator(g_world=np.array([0.0, 0.0, -9.81]))
    state = NominalState(
        p=np.zeros(3),
        v=np.zeros(3),
        q=np.array([1.0, 0.0, 0.0, 0.0]),
        ba=np.zeros(3),
        bg=np.zeros(3),
        timestamp=0.0,
    )

    sample = ImuSample(
        timestamp=0.1,
        accel=np.array([0.0, 0.0, 9.81]),
        gyro=np.zeros(3),
        is_linear_accel=False,
    )

    next_state = propagator.propagate(state, sample, dt=0.1)
    np.testing.assert_allclose(next_state.v, np.zeros(3), atol=1e-6)
    np.testing.assert_allclose(next_state.p, np.zeros(3), atol=1e-6)


def test_linear_acceleration_without_double_gravity():
    """Verify Android TYPE_LINEAR_ACCELERATION propagation."""
    propagator = InsPropagator(g_world=np.array([0.0, 0.0, -9.81]))
    state = NominalState(
        p=np.zeros(3),
        v=np.zeros(3),
        q=np.array([1.0, 0.0, 0.0, 0.0]),
        ba=np.zeros(3),
        bg=np.zeros(3),
        timestamp=0.0,
    )

    sample = ImuSample(
        timestamp=0.1,
        accel=np.array([0.0, 0.0, 0.0]),
        gyro=np.zeros(3),
        is_linear_accel=True,
    )

    next_state = propagator.propagate(state, sample, dt=0.1)
    np.testing.assert_allclose(next_state.v, np.zeros(3), atol=1e-6)
    np.testing.assert_allclose(next_state.p, np.zeros(3), atol=1e-6)


def test_constant_acceleration_propagation():
    """Verify position integration under constant forward linear acceleration."""
    propagator = InsPropagator()
    state = NominalState(
        p=np.zeros(3),
        v=np.zeros(3),
        q=np.array([1.0, 0.0, 0.0, 0.0]),
        timestamp=0.0,
    )

    a_forward = 2.0
    dt = 1.0
    sample = ImuSample(
        timestamp=dt,
        accel=np.array([a_forward, 0.0, 0.0]),
        gyro=np.zeros(3),
        is_linear_accel=True,
    )

    next_state = propagator.propagate(state, sample, dt=dt)
    np.testing.assert_allclose(next_state.v, np.array([2.0, 0.0, 0.0]), atol=1e-6)
    np.testing.assert_allclose(next_state.p, np.array([1.0, 0.0, 0.0]), atol=1e-6)


def test_constant_angular_velocity_propagation():
    """Verify orientation integration under constant angular velocity."""
    propagator = InsPropagator()
    state = NominalState(
        p=np.zeros(3),
        v=np.zeros(3),
        q=np.array([1.0, 0.0, 0.0, 0.0]),
        timestamp=0.0,
    )

    w_z = np.pi / 2.0  # 90 deg/s about Z axis
    dt = 1.0
    sample = ImuSample(
        timestamp=dt,
        accel=np.zeros(3),
        gyro=np.array([0.0, 0.0, w_z]),
        is_linear_accel=True,
    )

    next_state = propagator.propagate(state, sample, dt=dt)

    # After 1 sec at pi/2 rad/s about Z, [1,0,0] should rotate to [0,1,0]
    v_rot = rotate_vector_by_quat(np.array([1.0, 0.0, 0.0]), next_state.q)
    np.testing.assert_allclose(v_rot, np.array([0.0, 1.0, 0.0]), atol=1e-6)


def test_sensor_bias_subtraction_in_phone_frame():
    """Verify accelerometer and gyroscope bias subtraction in Phone frame."""
    propagator = InsPropagator(g_world=np.array([0.0, 0.0, -9.81]))

    # Phone flat with bias ba=[0.1, 0.2, 0.3] and bg=[0.01, 0.02, 0.03]
    state = NominalState(
        p=np.zeros(3),
        v=np.zeros(3),
        q=np.array([1.0, 0.0, 0.0, 0.0]),
        ba=np.array([0.1, 0.2, 0.3]),
        bg=np.array([0.01, 0.02, 0.03]),
        timestamp=0.0,
    )

    # Sensor reads exact gravity + bias and gyro rate + bias
    sample = ImuSample(
        timestamp=0.1,
        accel=np.array([0.1, 0.2, 9.81 + 0.3]),
        gyro=np.array([0.01, 0.02, 0.03]),
        is_linear_accel=False,
    )

    next_state = propagator.propagate(state, sample, dt=0.1)

    # After bias subtraction, net acceleration is [0,0,0] and gyro rate is [0,0,0]
    np.testing.assert_allclose(next_state.v, np.zeros(3), atol=1e-6)
    np.testing.assert_allclose(next_state.p, np.zeros(3), atol=1e-6)
    np.testing.assert_allclose(next_state.q, np.array([1.0, 0.0, 0.0, 0.0]), atol=1e-6)


def test_timestamp_validation_and_unreasonable_gaps():
    """Verify strictly increasing timestamp enforcement and max gap rejection."""
    propagator = InsPropagator(max_dt=5.0)
    state = NominalState()

    # dt <= 0 (duplicate or non-monotonic timestamp)
    sample_valid = ImuSample(timestamp=1.0, accel=np.zeros(3), gyro=np.zeros(3))
    with pytest.raises(ValueError, match="strictly increasing"):
        propagator.propagate(state, sample_valid, dt=0.0)

    with pytest.raises(ValueError, match="strictly increasing"):
        propagator.propagate(state, sample_valid, dt=-0.1)

    # Unreasonable dt gap > 5.0 seconds
    with pytest.raises(ValueError, match="Unreasonable timestamp gap"):
        propagator.propagate(state, sample_valid, dt=10.0)

    # NaN/Inf check
    sample_nan = ImuSample(timestamp=1.0, accel=np.array([np.nan, 0.0, 0.0]), gyro=np.zeros(3))
    with pytest.raises(ValueError, match="NaN or Inf"):
        propagator.propagate(state, sample_nan, dt=0.1)


# --- ESKF Prediction & Covariance Tests ---


def test_covariance_prediction_and_growth():
    """Verify ESKF covariance growth and positive-definiteness during prediction."""
    P0 = np.eye(15, dtype=np.float64) * 0.01
    state = NominalState(p=np.zeros(3), v=np.ones(3), q=np.array([1.0, 0.0, 0.0, 0.0]))
    sample = ImuSample(timestamp=0.1, accel=np.array([1.0, 0.0, 9.81]), gyro=np.array([0.1, 0.0, 0.0]))
    config = EskfConfig(sigma_a=0.2, sigma_g=0.02)

    P1 = predict_covariance(P0, state, sample, dt=0.1, config=config)

    val_res = validate_covariance(P1)
    assert val_res.is_valid, val_res.reason
    assert np.all(np.diag(P1) >= np.diag(P0))


def test_covariance_validation_rules():
    """Verify validate_covariance catches asymmetry, non-PSD, and NaN values."""
    P_valid = np.eye(15, dtype=np.float64)
    assert validate_covariance(P_valid).is_valid

    P_asym = P_valid.copy()
    P_asym[0, 1] = 1.0
    assert not validate_covariance(P_asym).is_valid

    P_neg = P_valid.copy()
    P_neg[0, 0] = -1.0
    assert not validate_covariance(P_neg).is_valid

    P_nan = P_valid.copy()
    P_nan[0, 0] = np.nan
    assert not validate_covariance(P_nan).is_valid


def test_error_injection_and_reset():
    """Verify error state injection into nominal state and covariance reset matrix G_reset."""
    state = NominalState(
        p=np.array([1.0, 2.0, 3.0]),
        v=np.array([0.1, 0.2, 0.3]),
        q=np.array([1.0, 0.0, 0.0, 0.0]),
        ba=np.array([0.01, -0.01, 0.0]),
        bg=np.array([0.001, 0.0, -0.001]),
    )
    P = np.eye(15, dtype=np.float64) * 0.1

    delta_x = np.zeros(15)
    delta_x[0:3] = [0.1, -0.1, 0.05]
    delta_x[3:6] = [0.01, 0.02, -0.01]
    delta_x[6:9] = [0.001, -0.002, 0.003]

    state_new, P_new = inject_error_and_reset(state, delta_x, P)

    np.testing.assert_allclose(state_new.p, [1.1, 1.9, 3.05], atol=1e-6)
    np.testing.assert_allclose(state_new.v, [0.11, 0.22, 0.29], atol=1e-6)

    val_res = validate_covariance(P_new)
    assert val_res.is_valid, val_res.reason


def test_finite_difference_f_matrix_verification():
    """Verify analytic continuous transition matrix F_c against finite-difference approximation."""
    state = NominalState(
        p=np.zeros(3),
        v=np.array([5.0, 1.0, 0.2]),
        q=np.array([0.9659, 0.0, 0.0, 0.2588]),
        ba=np.array([0.05, -0.02, 0.01]),
        bg=np.array([0.001, 0.002, -0.001]),
    )
    sample = ImuSample(
        timestamp=0.01,
        accel=np.array([0.5, 0.1, 9.81]),
        gyro=np.array([0.02, -0.01, 0.05]),
        is_linear_accel=True,
    )
    propagator = InsPropagator()

    F_c_analytic = compute_continuous_f_matrix(state, sample)

    dt = 1e-5
    eps = 1e-6

    nominal_next = propagator.propagate(state, sample, dt)

    F_c_numeric = np.zeros((15, 15), dtype=np.float64)

    for i in range(15):
        delta_x = np.zeros(15)
        delta_x[i] = eps

        state_pert, _ = inject_error_and_reset(state, delta_x, np.eye(15))
        pert_next = propagator.propagate(state_pert, sample, dt)

        dp = pert_next.p - nominal_next.p
        dv = pert_next.v - nominal_next.v

        dq = quat_multiply(quat_conjugate(nominal_next.q), pert_next.q)
        dtheta = 2.0 * dq[1:]

        dba = pert_next.ba - nominal_next.ba
        dbg = pert_next.bg - nominal_next.bg

        d_err = np.concatenate([dp, dv, dtheta, dba, dbg])

        col_i = (d_err - delta_x) / (dt * eps)
        F_c_numeric[:, i] = col_i

    np.testing.assert_allclose(F_c_analytic, F_c_numeric, atol=1e-2, rtol=1e-2)
