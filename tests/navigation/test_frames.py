"""Unit tests for frame transformations, quaternion math, and orientation conversions."""

import numpy as np
import pytest
from src.navigation.ins import (
    quat_multiply,
    quat_conjugate,
    quat_normalize,
    quat_to_rotmat,
    rotmat_to_quat,
    rotate_vector_by_quat,
    delta_quat_from_rotation_vector,
    skew_symmetric,
)
from src.navigation.types import PhoneToVehicleTransform


def test_quaternion_identity():
    """Verify identity quaternion properties."""
    q_id = np.array([1.0, 0.0, 0.0, 0.0])
    v = np.array([1.0, 2.0, 3.0])

    # Multiplication by identity
    q_res = quat_multiply(q_id, np.array([0.7071, 0.7071, 0.0, 0.0]))
    np.testing.assert_allclose(q_res, np.array([0.7071, 0.7071, 0.0, 0.0]), atol=1e-6)

    # Rotation by identity
    v_rot = rotate_vector_by_quat(v, q_id)
    np.testing.assert_allclose(v_rot, v, atol=1e-7)

    # Rotation matrix of identity
    R_id = quat_to_rotmat(q_id)
    np.testing.assert_allclose(R_id, np.eye(3), atol=1e-7)


def test_quaternion_normalization():
    """Verify explicit quaternion normalization for non-unit quaternions."""
    q_unnorm = np.array([2.0, 0.0, 0.0, 0.0])
    q_norm = quat_normalize(q_unnorm)
    np.testing.assert_allclose(q_norm, np.array([1.0, 0.0, 0.0, 0.0]), atol=1e-12)
    np.testing.assert_allclose(np.linalg.norm(q_norm), 1.0, atol=1e-12)

    # Near-zero quaternion fallback
    q_zero = np.array([1e-15, 0.0, 0.0, 0.0])
    np.testing.assert_allclose(quat_normalize(q_zero), np.array([1.0, 0.0, 0.0, 0.0]), atol=1e-12)


def test_90_degree_rotations():
    """Verify 90-degree rotations about X, Y, and Z axes."""
    v = np.array([1.0, 0.0, 0.0])

    # 90-degree rotation about Z axis (Yaw)
    q_z90 = np.array([np.cos(np.pi / 4), 0.0, 0.0, np.sin(np.pi / 4)])
    v_z90 = rotate_vector_by_quat(v, q_z90)
    np.testing.assert_allclose(v_z90, np.array([0.0, 1.0, 0.0]), atol=1e-6)

    # 90-degree rotation about Y axis (Pitch)
    q_y90 = np.array([np.cos(np.pi / 4), 0.0, np.sin(np.pi / 4), 0.0])
    v_y90 = rotate_vector_by_quat(v, q_y90)
    np.testing.assert_allclose(v_y90, np.array([0.0, 0.0, -1.0]), atol=1e-6)

    # 90-degree rotation about X axis (Roll)
    q_x90 = np.array([np.cos(np.pi / 4), np.sin(np.pi / 4), 0.0, 0.0])
    v_x90 = rotate_vector_by_quat(np.array([0.0, 1.0, 0.0]), q_x90)
    np.testing.assert_allclose(v_x90, np.array([0.0, 0.0, 1.0]), atol=1e-6)


def test_rotmat_quat_roundtrip():
    """Verify roundtrip conversion between rotation matrix and quaternion."""
    angles = [0.1, -0.4, 0.8]
    for angle in angles:
        q_orig = delta_quat_from_rotation_vector(np.array([angle, 0.2, -0.3]))
        R = quat_to_rotmat(q_orig)
        q_conv = rotmat_to_quat(R)

        # Handle q and -q equivalence
        if np.sign(q_orig[0]) != np.sign(q_conv[0]):
            q_conv = -q_conv

        np.testing.assert_allclose(q_conv, q_orig, atol=1e-6)


def test_phone_to_vehicle_transform():
    """Verify Phone-to-Vehicle frame transformation."""
    # 90 deg rotation phone to vehicle
    R_v_p = np.array([[0, -1, 0], [1, 0, 0], [0, 0, 1]], dtype=np.float64)
    transform = PhoneToVehicleTransform(R_v_p=R_v_p)

    v_phone = np.array([1.0, 0.0, 0.0])
    v_vehicle = transform.R_v_p @ v_phone
    np.testing.assert_allclose(v_vehicle, np.array([0.0, 1.0, 0.0]), atol=1e-7)


def test_skew_symmetric():
    """Verify skew-symmetric matrix properties: [v]_x @ u == v x u."""
    v = np.array([1.0, 2.0, 3.0])
    u = np.array([4.0, 5.0, 6.0])

    V_skew = skew_symmetric(v)
    cross_exp = np.cross(v, u)
    cross_act = V_skew @ u

    np.testing.assert_allclose(cross_act, cross_exp, atol=1e-7)
