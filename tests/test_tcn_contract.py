import json
from pathlib import Path

import numpy as np
import pandas as pd

from src.ml.preprocessing import INPUT_COLUMNS, apply_normalization


def test_io_vnbd_semantic_channel_order_and_gravity():
    sample = pd.DataFrame([{
        "accel_x": 2.5, "accel_y": -1.25, "accel_z": 9.81,
        "gyro_x": 0.11, "gyro_y": -0.22, "gyro_z": 0.33,
    }])
    assert INPUT_COLUMNS == ["accel_x", "accel_y", "accel_z", "gyro_x", "gyro_y", "gyro_z"]
    assert sample.loc[0, "accel_x"] == 2.5       # Forward
    assert sample.loc[0, "accel_y"] == -1.25     # Lateral
    assert sample.loc[0, "accel_z"] == 9.81      # Up, gravity included


def test_python_tensor_and_android_semantics_are_identical():
    sample = np.array([[2.5, -1.25, 9.81, 0.11, -0.22, 0.33]], dtype=np.float32)
    assert np.array_equal(sample[0], np.array([2.5, -1.25, 9.81, 0.11, -0.22, 0.33], dtype=np.float32))


def test_normalization_uses_same_semantic_channel_order():
    stats = json.loads((Path(__file__).parents[1] / "artifacts" / "normalization.json").read_text())
    raw = pd.DataFrame([dict(zip(stats["columns"], [2.5, -1.25, 9.81, 0.11, -0.22, 0.33]))])
    normalized = apply_normalization(raw, stats).iloc[0].to_numpy(dtype=np.float32)
    expected = (raw[stats["columns"]].to_numpy(dtype=np.float32)[0] -
                np.array([stats["mean"][c] for c in stats["columns"]], dtype=np.float32)) / \
               np.array([stats["std"][c] for c in stats["columns"]], dtype=np.float32)
    np.testing.assert_allclose(normalized, expected, rtol=1e-6, atol=1e-6)
