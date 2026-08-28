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
    }.issubset(replay)
    assert (~replay["gnss_available"]).sum() == 30
    assert np.isfinite(metrics["percorsa"]["rmse_m"])
