import pandas as pd
import pytest

from src.data.live_trip import normalize_trip_frame, safe_trip_id


def test_android_aliases_and_relative_time_are_normalized():
    source = pd.DataFrame(
        {
            "timestamp_ns": [2_000_000_000, 2_100_000_000],
            "ax": [0, 1],
            "ay": [0, 1],
            "az": [9.8, 9.7],
            "gx": [0, 0],
            "gy": [0, 0],
            "gz": [0, 0],
        }
    )
    frame, result = normalize_trip_frame(source, "trip 1")
    assert frame["time_since_start_s"].tolist() == pytest.approx([0.0, 0.1])
    assert frame["accel_x"].tolist() == [0, 1]
    assert "filtered_accel_x" in frame
    assert "quality_flags" in frame
    assert result.trip_id == "trip_1"
    assert result.replay_ready is False


def test_safe_trip_id_removes_path_components():
    assert safe_trip_id("../../my trip.csv") == "my_trip"
