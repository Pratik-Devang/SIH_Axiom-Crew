import numpy as np
import pandas as pd

from src.evaluation.metrics import trajectory_metrics
from src.evaluation.replay import run_outage_replay
from src.preprocessing.outages import gnss_available_mask


def synthetic_trip(samples: int = 80) -> pd.DataFrame:
    time = np.arange(samples) * 0.1
    north_m = time * 5.0
    latitude = 19.05 + north_m / 111_000.0
    return pd.DataFrame(
        {
            "trip_id": "synthetic",
            "time_since_start_s": time,
            "latitude": latitude,
            "longitude": np.full(samples, 72.89),
            "vehicle_speed": np.full(samples, 18.0),
            "gyro_z": np.zeros(samples),
        }
    )


def test_outage_mask_boundaries():
    mask = gnss_available_mask([0.0, 1.0, 2.0, 3.0], 1.0, 2.0)
    assert mask.tolist() == [True, False, False, True]


def test_metrics_are_zero_for_identical_trajectory():
    metrics = trajectory_metrics([0, 1], [0, 0], [0, 1], [0, 0])
    assert metrics["rmse_m"] == 0.0


def test_replay_produces_estimate_and_outage_metrics():
    replay, metrics = run_outage_replay(synthetic_trip(), 2.0, 3.0)
    assert {
        "estimated_east",
        "estimated_north",
        "position_error_m",
        "position_uncertainty_m",
        "navigation_mode",
        "active_constraints",
        "gnss_trusted",
    }.issubset(replay)
    assert (~replay["gnss_available"]).sum() == 30
    assert not replay.loc[~replay["gnss_available"], "gnss_trusted"].any()
    assert set(replay.loc[~replay["gnss_available"], "navigation_mode"]) == {
        "dead_reckoning"
    }
    assert np.isfinite(metrics["percorsa"]["rmse_m"])


def test_recovery_mode_bridges_outage_and_normal_gnss():
    replay, _ = run_outage_replay(
        synthetic_trip(samples=100), 2.0, 3.0, recovery_updates=3
    )
    after_outage = replay.loc[replay["time_since_start_s"] >= 5.0]
    assert after_outage["navigation_mode"].iloc[:3].tolist() == ["recovery"] * 3
    assert "GNSS" in set(after_outage["navigation_mode"].iloc[3:])


def test_poor_accuracy_fix_is_rejected_by_trust_gate():
    trip = synthetic_trip()
    trip["gps_accuracy_m"] = 3.0
    trip.loc[10, "gps_accuracy_m"] = 100.0
    replay, _ = run_outage_replay(trip, 4.0, 1.0)
    assert not bool(replay.loc[10, "gnss_trusted"])
    assert replay.loc[10, "navigation_mode"] == "dead_reckoning"
    assert "accuracy" in replay.loc[10, "gnss_trust_reason"].lower()


def test_reference_speed_is_not_used_during_outage():
    trip = synthetic_trip()
    trip["vehicle_speed"] = 18.0
    trip.loc[20:49, "vehicle_speed"] = 180.0
    replay, _ = run_outage_replay(
        trip,
        2.0,
        3.0,
        use_stop_constraint=False,
        use_non_holonomic_constraint=False,
    )
    outage_speed = replay.loc[~replay["gnss_available"], "estimated_speed_mps"]
    assert float(outage_speed.max()) < 10.0


def test_outage_at_start_does_not_leak_reference_initialization():
    trip = synthetic_trip(samples=40)
    trip["vehicle_speed"] = 180.0
    replay, _ = run_outage_replay(
        trip,
        0.0,
        2.0,
        use_stop_constraint=False,
        use_non_holonomic_constraint=False,
    )
    denied = replay.loc[~replay["gnss_available"]]
    assert np.allclose(denied["estimated_speed_mps"], 0.0)
    assert np.allclose(denied["estimated_heading_rad"], 0.0)


def test_stop_and_non_holonomic_constraints_are_exposed():
    trip = synthetic_trip(samples=40)
    trip["vehicle_speed"] = 0.0
    replay, _ = run_outage_replay(trip, 3.0, 0.5)
    assert replay["stop_detected"].any()
    assert replay.loc[replay["stop_detected"], "active_constraints"].str.contains(
        "ZUPT"
    ).all()

    moving, _ = run_outage_replay(synthetic_trip(samples=40), 3.0, 0.5)
    assert moving["nhc_active"].any()
    assert moving.loc[moving["nhc_active"], "active_constraints"].str.contains(
        "NHC"
    ).all()
