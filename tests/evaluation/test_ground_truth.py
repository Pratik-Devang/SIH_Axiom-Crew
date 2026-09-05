import numpy as np
import pandas as pd

from src.evaluation.ground_truth import audit_reference_frame


def candidate_frame() -> pd.DataFrame:
    return pd.DataFrame(
        {
            "time_since_start_s": [0.0, 0.1, 0.2],
            "accel_x": [0.0, 0.0, 0.0],
            "accel_y": [0.0, 0.0, 0.0],
            "accel_z": [9.8, 9.8, 9.8],
            "gyro_x": [0.0, 0.0, 0.0],
            "gyro_y": [0.0, 0.0, 0.0],
            "gyro_z": [0.0, 0.0, 0.0],
            "latitude": [19.0, 19.00001, 19.00002],
            "longitude": [72.0, 72.00001, 72.00002],
            "gps_accuracy_m": [3.0, 4.0, 20.0],
            "vehicle_speed": [0.0, 5.0, 5.0],
        }
    )


def test_gnss_observation_is_not_called_ground_truth():
    audit = audit_reference_frame(candidate_frame(), provenance="gnss_receiver")
    assert audit.quantitative_validation_ready is False
    assert audit.position_source == "latitude_longitude_observation"
    assert audit.accuracy_le_15m_fraction == 2 / 3
    assert audit.median_sample_period_s == 0.1


def test_explicit_independent_reference_can_be_validation_ready():
    frame = candidate_frame().rename(
        columns={"latitude": "reference_latitude", "longitude": "reference_longitude"}
    )
    audit = audit_reference_frame(frame, provenance="external_reference")
    assert audit.quantitative_validation_ready is True
    assert audit.has_imu is True


def test_bad_timestamps_prevent_quantitative_validation():
    frame = candidate_frame()
    frame.loc[2, "time_since_start_s"] = 0.05
    audit = audit_reference_frame(frame, provenance="rtk")
    assert audit.timestamp_monotonic is False
    assert audit.quantitative_validation_ready is False
