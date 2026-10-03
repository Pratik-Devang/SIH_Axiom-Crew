import numpy as np

from scripts.prototype_io_vnbd_alignment import LOCKED, SAMPLE_TRIPS, _rotation_kabsch, estimate_sync, split_monotonic


def test_split_monotonic_detects_reset_and_gap():
    first = np.arange(25, dtype=float) * 0.1
    # The first row after a reset is discarded as the discontinuity boundary.
    second = np.arange(26, dtype=float) * 0.1
    times = np.r_[first, second]
    segments = split_monotonic(times)
    assert len(segments) == 2
    assert [s["rows"] for s in segments] == [25, 25]


def test_rotation_kabsch_maps_phone_vectors_to_vehicle():
    angle = np.deg2rad(90.0)
    expected = np.array([[np.cos(angle), -np.sin(angle), 0.0], [np.sin(angle), np.cos(angle), 0.0], [0.0, 0.0, 1.0]])
    phone = np.array([[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]] * 10)
    vehicle = (expected @ phone.T).T
    result = _rotation_kabsch(phone, vehicle, np.ones(len(phone)))
    assert np.allclose(result, expected, atol=1e-10)
    assert np.allclose((result @ phone.T).T, vehicle, atol=1e-10)


def test_sync_uses_target_independent_dynamic_signals():
    t = np.arange(0.0, 20.0, 0.1)
    signal = np.sin(t) + 0.2 * np.sin(3.0 * t)
    p = {
        "ptime": t,
        "vtime": t + 100.0,
        "accel": np.column_stack([signal, np.zeros_like(signal), np.zeros_like(signal)]),
        "gravity": np.zeros((len(t), 3)),
        "vehicle_long": signal,
        "vehicle_lat": np.zeros_like(signal),
    }
    segment = {"start_index": 0, "end_index_exclusive": len(t)}
    result = estimate_sync(p, segment, segment)
    assert abs(result["offset_vehicle_minus_phone_s"]) <= 0.1
    assert result["correlation_after"] > 0.95


def test_locked_test_trips_are_not_in_prototype_scope():
    assert not set(SAMPLE_TRIPS) & LOCKED
    assert set(SAMPLE_TRIPS) == {"S1", "S2", "S3a", "S3b", "S4", "M", "Vfa01", "Vfa02"}
