import numpy as np
import pandas as pd
import pytest

from src.preprocessing.sensor_filter import (
    SPIKE_FLAG_BITS,
    filter_sensor_spikes,
    prepare_filtered_10hz_view,
)


def imu_frame(samples: int = 40, rate_hz: float = 100.0) -> pd.DataFrame:
    return pd.DataFrame(
        {
            "time_since_start_s": np.arange(samples) / rate_hz,
            "accel_x": np.zeros(samples),
            "accel_y": np.zeros(samples),
            "accel_z": np.full(samples, 9.81),
            "gyro_x": np.zeros(samples),
            "gyro_y": np.zeros(samples),
            "gyro_z": np.zeros(samples),
        }
    )


def test_isolated_spike_is_flagged_without_overwriting_raw_value():
    frame = imu_frame()
    frame.loc[15, "accel_x"] = 100.0

    filtered = filter_sensor_spikes(frame)

    assert filtered.loc[15, "accel_x"] == 100.0
    assert filtered.loc[15, "filtered_accel_x"] == 0.0
    assert filtered.loc[15, "sensor_spike_detected"]
    assert filtered.loc[15, "quality_flags"] & SPIKE_FLAG_BITS["accel_x"]
    assert filtered["sensor_spike_detected"].sum() == 1


def test_sustained_motion_change_becomes_the_new_baseline():
    frame = imu_frame()
    frame.loc[15:, "accel_x"] = 5.0

    filtered = filter_sensor_spikes(frame)

    assert filtered.loc[15, "filtered_accel_x"] == 0.0
    assert filtered.loc[25, "filtered_accel_x"] == 5.0
    assert not filtered.loc[25, "sensor_spike_detected"]


def test_invalid_value_is_replaced_only_in_filtered_channel():
    frame = imu_frame()
    frame.loc[15, "gyro_z"] = np.nan

    filtered = filter_sensor_spikes(frame)

    assert np.isnan(filtered.loc[15, "gyro_z"])
    assert filtered.loc[15, "filtered_gyro_z"] == 0.0
    assert filtered.loc[15, "sensor_spike_detected"]


def test_model_view_uses_filtered_values_at_exactly_10_hz():
    frame = imu_frame(samples=101)
    frame.loc[10, "accel_x"] = 100.0

    model_view = prepare_filtered_10hz_view(frame)

    assert np.diff(model_view["time_since_start_s"]).tolist() == pytest.approx(
        [0.1] * 10
    )
    assert model_view["accel_x"].abs().max() == 0.0
    assert model_view.loc[1, "filtered_accel_x"] == 0.0
